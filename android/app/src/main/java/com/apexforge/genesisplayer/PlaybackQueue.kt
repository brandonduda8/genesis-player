package com.apexforge.genesisplayer

import androidx.media3.common.MediaItem
import com.apexforge.genesisplayer.data.Track

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
 */

/** Result of pure queue planning: filtered+boosted ids and the tapped track. */
data class QueuePlan(
    val orderedIds: List<String>,
    /** The tapped track's id after filtering (nearest survivor when filtered out). */
    val startId: String?,
    val boostedCount: Int
)

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
            ordered = b + rest
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
        // 3. Background: resolve the rest in tap order (minus the head) and
        //    append in batches so a 494-track queue streams in progressively.
        val rest = order.filter { it != firstId }
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
}
