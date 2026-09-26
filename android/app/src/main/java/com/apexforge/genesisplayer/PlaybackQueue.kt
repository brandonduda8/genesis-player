package com.apexforge.genesisplayer

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.apexforge.genesisplayer.data.Track
import kotlin.random.Random

/**
 * Tap-to-play queue engine (2026-09-25 repair).
 *
 * The old playTracks() resolved the ENTIRE playlist serially
 * (ordered.mapNotNull { itemFor(it) }) before any audio started: a tap could
 * wait on 50-494 SoundCloud resolutions, and signed URLs could expire before
 * their track's turn came. The new flow:
 *
 *  1. [QueuePlanner.plan] — pure, instant: dislike filter + taste boost +
 *     tap-index mapping. No network.
 *  2. [PlaybackQueue.start] — resolve ONLY the tapped track, hand it to the
 *     player immediately (tap-to-first-audio after ONE resolution), then
 *     resolve + append the rest in the background in batches.
 *  3. [SoundCloudRefresher] — re-resolve SoundCloud signed URLs at every
 *     track transition (plus pre-resolve the upcoming track), so a queue-time
 *     URL never dies mid-playlist.
 *
 * QueuePlanner and SoundCloudRefresher are pure / injectable so the API-34
 * instrumented tests can prove the behavior without a network.
 *
 * QueuePlanner is also the seam the Phase-2c Apollo DJ will drive
 * (predictive next-up): it owns queue ORDER, the service owns playback.
 */

/** Result of pure queue planning: filtered+boosted ids and the tapped track. */
data class QueuePlan(
    val orderedIds: List<String>,
    /** The tapped track's id after filtering (nearest survivor when filtered out). */
    val startId: String?,
    val boostedCount: Int
)

/**
 * Load-bearing log formatting for the DEBUG queue-dump gate hook
 * (PlayerService.debugQueueDump). Kept here, next to queue ownership, so the
 * format has exactly one home. Pure — pinned by BrknWavesTest.
 */
object QueueDump {
    fun format(ids: List<String>, currentIndex: Int): String =
        "QueueDump: order [${ids.joinToString(",")}] (current=$currentIndex)"
}

object QueuePlanner {
    /**
     * Pure queue planning. No Android framework calls — unit-testable.
     *
     * @param ids tap-order ids (playlist/library order)
     * @param startIndex index the user tapped in [ids]
     */
    fun plan(
        ids: List<String>,
        startIndex: Int,
        isDisliked: (String) -> Boolean,
        likedArtists: Set<String>,
        likedGenres: Set<String>,
        trackOf: (String) -> Track?
    ): QueuePlan {
        val kept = ids.filter { !isDisliked(it) }
        val ordered: List<String>
        val boosted: Int
        if (likedArtists.isEmpty() && likedGenres.isEmpty()) {
            ordered = kept
            boosted = 0
        } else {
            val (b, rest) = kept.partition { id ->
                val t = trackOf(id)
                t != null && (t.artist in likedArtists ||
                    (t.genre.isNotEmpty() && t.genre in likedGenres))
            }
            ordered = b.reversed() + rest.reversed() // MUTATION 3a
            boosted = b.size
        }
        // Map the tapped index through the filters. The tapped track itself
        // when it survives; otherwise the nearest surviving track at/after
        // the tap (never index 0 of an unrelated head — the old bug silently
        // restarted the queue head when the tapped track was filtered out).
        val tappedId = ids.getOrNull(startIndex)
        val startId = when {
            tappedId != null && tappedId in ordered -> tappedId
            else -> ids.drop(startIndex.coerceAtLeast(0)).firstOrNull { it in ordered }
                ?: ordered.firstOrNull()
        }
        return QueuePlan(ordered, startId, boosted)
    }

    /**
     * Shuffle-everything order (Phase 1): the full catalog in random order,
     * dislikes excluded. Pure — the seed pins it for tests; the service
     * passes a time-based seed so every tap shuffles fresh. The tap-to-play
     * path still resolves only the first track before first audio.
     */
    fun shuffleOrder(
        ids: List<String>,
        isDisliked: (String) -> Boolean,
        seed: Long
    ): List<String> = ids.filter { !isDisliked(it) }.shuffled(Random(seed))

    /**
     * "More like this" (Phase 1): a queue seeded from one track's
     * artist/genre/mood, ranked by on-device taste signals. Pure and
     * deterministic — ties keep catalog order. Only catalog ids in, only
     * catalog ids out; the seed itself and dislikes are excluded.
     *
     * Scoring: same artist +3, same genre +2, shared genre token +1
     * (so "dark phonk" rides the "phonk" lane), liked artist +1,
     * liked genre +1. boostedCount = tracks scoring above zero.
     */
    fun moreLikeThis(
        seed: Track,
        ids: List<String>,
        isDisliked: (String) -> Boolean,
        likedArtists: Set<String>,
        likedGenres: Set<String>,
        trackOf: (String) -> Track?
    ): QueuePlan {
        val seedGenre = seed.genre.lowercase()
        val seedTokens = seedGenre.split(Regex("[^a-z0-9]+"))
            .filter { it.isNotEmpty() }.toSet()
        val seedArtist = seed.artist.lowercase()
        // Triple(position, id, score) — no local class, keeps it simple.
        val ranked = ids.mapIndexedNotNull { pos, id ->
            if (id == seed.id || isDisliked(id)) return@mapIndexedNotNull null
            val t = trackOf(id) ?: return@mapIndexedNotNull null
            var s = 0
            if (t.artist.lowercase() == seedArtist) s += 3
            val g = t.genre.lowercase()
            if (g.isNotEmpty()) {
                if (g == seedGenre) s += 2
                else if (seedTokens.isNotEmpty() &&
                    g.split(Regex("[^a-z0-9]+")).any { it in seedTokens }
                ) s += 1
            }
            if (t.artist in likedArtists) s += 1
            if (t.genre.isNotEmpty() && t.genre in likedGenres) s += 1
            Triple(pos, id, s)
        }.sortedWith(compareByDescending<Triple<Int, String, Int>> { it.third }
            .thenByDescending { it.first }) // MUTATION 3b
        val ordered = ranked.map { it.second }
        return QueuePlan(ordered, ordered.firstOrNull(), ranked.count { it.third > 0 })
    }
}

/**
 * Tap-to-play orchestration. Runs on ONE background thread; all player
 * mutations go through [PlayerControl], which the service implements by
 * posting to the main thread.
 *
 * @param resolve blocking per-track MediaItem builder (itemFor); null = skip
 * @param isStale generation guard: abort when the user tapped a newer queue
 */
class PlaybackQueue(
    private val resolve: (String) -> MediaItem?,
    private val control: PlayerControl,
    private val isStale: () -> Boolean
) {
    interface PlayerControl {
        /** Show the FIRST item immediately: setMediaItems([item]) + prepare + play. */
        fun setAndPlayFirst(item: MediaItem)
        /** Append a resolved batch to the live playlist. */
        fun appendItems(items: List<MediaItem>)
    }

    fun start(plan: QueuePlan, batchSize: Int = 8) {
        val startId = plan.startId ?: return
        val order = plan.orderedIds
        // 1. Resolve ONLY the tapped track first. If it is unresolvable
        //    (dead link, SC resolve failed), walk forward to the next
        //    playable track — never stall the tap on the whole playlist.
        var first: MediaItem? = null
        var firstId: String? = null
        val startPos = order.indexOf(startId).takeIf { it >= 0 } ?: 0
        for (i in startPos until order.size) {
            if (isStale()) return
            val item = resolve(order[i])
            if (item != null) {
                first = item
                firstId = order[i]
                break
            }
        }
        val head = first ?: return
        if (isStale()) return
        // 2. Tap-to-first-audio: the player's first call happens after exactly
        //    the resolutions above (1 in the healthy case), not after N.
        control.setAndPlayFirst(head)
        // 3. Background: resolve the rest DOWNWARD from the tap, then wrap to
        //    the head (AUDIT §2.1 defect 2). After the tapped track, playback
        //    continues from the tap position — it never restarts at the
        //    playlist head. Any Up Next UI built on this ordering is honest.
        val firstPos = order.indexOf(firstId).takeIf { it >= 0 } ?: startPos
        val rest = (order.drop(firstPos + 1) + order.take(firstPos))
            .filter { it != firstId }
        val batch = ArrayList<MediaItem>(batchSize)
        for (id in rest) {
            if (isStale()) return
            val item = resolve(id) ?: continue
            batch.add(item)
            if (batch.size >= batchSize) {
                control.appendItems(batch.toList())
                batch.clear()
            }
        }
        if (batch.isNotEmpty() && !isStale()) control.appendItems(batch.toList())
    }
}

/**
 * SoundCloud signed-URL freshness at track transitions.
 *
 * Signed cf-media.sndcdn.com URLs expire within minutes. A URL resolved at
 * queue-build time for track #200 is likely dead by the time its turn comes.
 * The service calls [refresh] at every track transition (for the just-started
 * item and the upcoming one); it returns a rebuilt MediaItem with a freshly
 * resolved URL, or null when no refresh applies.
 *
 * @param resolve (permalink) -> fresh signed URL or null. Blocking: call off
 * the main thread. Null keeps the old item (never fake a URL).
 */
object SoundCloudRefresher {
    fun refresh(
        item: MediaItem,
        track: Track,
        resolve: (String) -> String?
    ): MediaItem? {
        if (track.soundcloudUrl.isEmpty()) return null
        val freshUrl = resolve(track.soundcloudUrl) ?: return null
        if (freshUrl.isEmpty()) return null
        if (item.localConfiguration?.uri.toString() == freshUrl) return null
        return item.buildUpon().setUri(freshUrl).build()
    }

    /**
     * Swap a refreshed item into the live playlist. Main thread only.
     *
     * The swap happens only while slot [index] still exists and still holds
     * [expectedMediaId] (the queue may have been re-planned or reordered
     * while the resolver was on the network). The queue keeps its length,
     * order and current index; when the swapped slot is the current item,
     * its playback position is restored. Returns true when swapped.
     */
    fun applyAt(player: Player, index: Int, expectedMediaId: String, fresh: MediaItem): Boolean {
        if (index < 0 || index >= player.mediaItemCount) return false
        if (player.getMediaItemAt(index).mediaId != expectedMediaId) return false
        val isCurrent = index == player.currentMediaItemIndex
        val pos = if (isCurrent) player.currentPosition else C.TIME_UNSET
        player.replaceMediaItem(index, fresh)
        if (isCurrent && pos != C.TIME_UNSET) player.seekTo(index, pos)
        return true
    }
}
