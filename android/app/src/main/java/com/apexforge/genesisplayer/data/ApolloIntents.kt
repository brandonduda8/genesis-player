package com.apexforge.genesisplayer.data

import android.util.Log
import com.apexforge.genesisplayer.data.ApolloStore.PendingDecision

private const val TAG = "GenesisPlayer"

/**
 * The Apollo intent vocabulary (APOLLO-LIVE.md §2.1), client-side.
 *
 * Two layers:
 *  1. [TransportCommand] — pause/resume/next/previous. Executed directly on
 *     the player. Voice and text share this path: a transcribed "pause" and
 *     a typed "pause" are the same command.
 *  2. [ApolloAction] — the fixed intent vocabulary. Resolved on-device by
 *     [LocalBrain] against real stores (Library, RatingsStore, HistoryStore,
 *     drops) and, when the tailnet brain is reachable, by the machine
 *     (/apollo/chat). The app executes ONLY these ops; unknown ops from the
 *     machine are ignored + logged, never guessed.
 *
 * Hard rule, enforced in both layers: every track_id emitted must exist in
 * the active catalog version. Nothing is ever invented.
 */
enum class TransportCommand { PAUSE, RESUME, NEXT, PREVIOUS }

sealed class ApolloAction {
    /** Play one catalog track now. track_id must exist in the catalog. */
    data class Play(val trackId: String) : ApolloAction()

    /** Insert after the current track. Max batch 10; ids catalog-verified. */
    data class QueueUpNext(val trackIds: List<String>) : ApolloAction()

    /** Client-side genre gate at track transitions (Flow D). Client owns the timer. */
    data class MoodGate(val excludeGenres: Set<String>, val windowMinutes: Int) : ApolloAction()

    /** Return the real-signal "why" for a track (Flow G). */
    data class Explain(val trackId: String) : ApolloAction()

    /** Read hunter drops + the suggestions feed; verified finds only. */
    data class Discover(val count: Int) : ApolloAction()

    /** Same effect as the thumbs button — routes through the /rate pipeline. */
    data class TasteFeedback(val trackId: String, val rating: String) : ApolloAction()

    /**
     * Creates pending-decision objects ONLY. Never mutates the catalog.
     * The strict suggest → tap → verified-publish pipeline runs machine-side
     * after his tap. The app may only show awaiting_tap → recorded →
     * syncing → published | failed.
     */
    data class SuggestToLibrary(val suggestions: List<PendingDecision>) : ApolloAction()

    /** Queue health, sync state, catalog version, pending decisions — honest states. */
    object Status : ApolloAction()

    /** Ambiguous request; the only legal "no action" path besides Status/None. */
    data class Clarify(val question: String) : ApolloAction()

    /** Small talk / out of scope. Apollo stays in lane. */
    object None : ApolloAction()

    /** Transport commands (voice + text share this path). */
    data class Transport(val command: TransportCommand) : ApolloAction()
}

/** Everything the on-device brain may consult. All real, all on-device. */
data class BrainCtx(
    val tracks: List<Track>,
    val byId: Map<String, Track>,
    val nowPlayingId: String?,
    val queueTailIds: List<String>,
    val likedArtists: Set<String>,
    val likedGenres: Set<String>,
    val dislikedIds: Set<String>,
    val ratings: Map<String, String>,
    val playCounts: Map<String, Int>,
    val drops: List<DropItem>,
    val catalogVersion: Int,
    val pendingCount: Int,
    val unsyncedRatings: Int
)

data class ChatPlan(
    val reply: String,
    val actions: List<ApolloAction> = emptyList(),
    val pending: List<PendingDecision> = emptyList(),
    /** trackId -> why-line for every Apollo-driven pick (Flow G). */
    val whyLines: Map<String, String> = emptyMap()
)

/**
 * Real-signal why-lines (Flow G). Every explanation cites a real signal —
 * a rating, a play count, a hunter run, or a taste weight. No retrofitted
 * romance, no invented stories.
 */
object WhyLine {
    fun forTrack(trackId: String, ctx: BrainCtx): String {
        val t = ctx.byId[trackId]
        val drop = ctx.drops.find {
            it.artist.equals(t?.artist, ignoreCase = true) &&
                it.title.equals(t?.title, ignoreCase = true)
        }
        return when {
            ctx.ratings[trackId] == "like" ->
                "You liked this one — it's in your lane."
            (ctx.playCounts[trackId] ?: 0) >= 3 ->
                "You've played this ${ctx.playCounts[trackId]}× — it stuck."
            drop != null ->
                "From this morning's hunt: ${drop.why.take(160)}"
            t != null && t.artist in ctx.likedArtists ->
                "More ${t.artist} — you've been liking their sound."
            t != null && t.genre.isNotEmpty() && t.genre in ctx.likedGenres ->
                "${t.genre} has been hitting for you lately."
            t != null && t.genre.isNotEmpty() ->
                "Filed under ${t.genre} in your crate."
            else -> "From your crate."
        }
    }
}

/**
 * Deterministic on-device intent parser — the Phase-1 chat brain
 * (ROADMAP §1.1: scripted intents first). No network, no guessing.
 * Used when the tailnet brain is unreachable AND as the honest fallback
 * everywhere: every answer is built from real on-device data.
 */
object LocalBrain {

    private val MOOD_BANGERS = setOf("phonk", "rage", "trap", "drill", "hard", "metal", "punk", "dubstep", "hardcore", "screamo")
    private val MOOD_SOFT = setOf("soft", "chill", "lofi", "acoustic", "ambient", "soul", "jazz", "r&b", "rnb", "indie")
    private val MOOD_LATE = setOf("ambient", "lofi", "chill", "dark", "witch", "cloud", "night", "slow")

    fun resolve(message: String, ctx: BrainCtx): ChatPlan {
        val m = message.trim().lowercase()
        if (m.isEmpty()) return ChatPlan(
            reply = "Say the word — a mood, an artist, or \"what's fresh\".",
            actions = listOf(ApolloAction.Clarify("What should I play?"))
        )

        // 1. Transport — voice and text share this path.
        VoiceCommand.parseTransport(m)?.let {
            return ChatPlan(reply = transportReply(it), actions = listOf(ApolloAction.Transport(it)))
        }

        // 2. Mood gate set / clear (Flow D).
        parseMoodGate(m)?.let { return it }

        // 3. Explain (Flow G).
        if (m.contains("why this") || m == "why" || m.contains("why this track")) {
            val id = ctx.nowPlayingId
            return if (id != null && id in ctx.byId) {
                ChatPlan(
                    reply = WhyLine.forTrack(id, ctx),
                    actions = listOf(ApolloAction.Explain(id))
                )
            } else {
                ChatPlan(reply = "Nothing's playing right now — play something and ask me again.")
            }
        }

        // 4. Status — honest states only.
        if (m == "status" || m.contains("how's the queue") || m.contains("sync status") ||
            m.contains("what version") || m.contains("catalog version")
        ) {
            val gate = "" // filled by the client (needs prefs); kept honest here
            return ChatPlan(
                reply = "Queue tail: ${ctx.queueTailIds.size} up. " +
                    "Catalog v${ctx.catalogVersion}. " +
                    "${ctx.pendingCount} decision(s) pending. " +
                    "${ctx.unsyncedRatings} rating(s) waiting to sync.$gate",
                actions = listOf(ApolloAction.Status)
            )
        }

        // 5. Discovery — verified finds only, never fabricated.
        if (m.contains("fresh") || m.contains("what's new") || m.contains("whats new") ||
            m == "drops" || m.contains("new music") || m.contains("discover")
        ) {
            return discovery(ctx)
        }

        // 6. Energy match — "continue this energy".
        if (m.contains("continue this energy") || m.contains("keep this going") ||
            m.contains("more like this") || m.contains("keep the vibe") ||
            m.contains("this vibe")
        ) {
            return energyMatch(ctx)
        }

        // 7. Mood play — before artist, so "play bangers" isn't an artist lookup.
        parseMood(m, ctx)?.let { return it }

        // 8. Artist play / more.
        parseArtist(m, ctx)?.let { return it }

        // 9. Suggest to library — pending-decision only, never "added".
        if (m.startsWith("add ") || m.startsWith("save ") || m.contains("add to my library") ||
            m.contains("put in my library")
        ) {
            return suggestToLibrary(m, ctx)
        }

        // 10. Surprise me — a genuine shuffle of the crate, never invented.
        if (m.contains("surprise me") || m == "surprise") {
            val pool = eligible(ctx).shuffled(java.util.Random(System.currentTimeMillis() / 86400000L))
            val ids = pool.take(5).map { it.id }
            return if (ids.isEmpty()) {
                ChatPlan(
                    reply = "The crate's empty from where I'm standing — play something first.",
                    actions = listOf(ApolloAction.Clarify("Nothing to surprise you with yet."))
                )
            } else {
                ChatPlan(
                    reply = "Rolling the dice — ${ids.size} from deep in your crate, up next.",
                    actions = listOf(ApolloAction.QueueUpNext(ids)),
                    whyLines = ids.associateWith { WhyLine.forTrack(it, ctx) }
                )
            }
        }

        // 11. Small talk — Apollo stays in lane, stays genuine.
        if (m in setOf("thanks", "thank you", "thx", "hey", "hello", "hi", "yo",
                "cool", "nice", "dope", "bet", "ok", "okay")
        ) {
            return ChatPlan(reply = "Locked in. Say the word when the mood shifts.")
        }

        // 12. Honest fallback — never a fake answer.
        Log.i(TAG, "LocalBrain: no intent matched for '$message'")
        return ChatPlan(
            reply = "I didn't catch that — here's what's playing instead.",
            actions = listOf(ApolloAction.Clarify("I didn't catch that — here's what's playing instead."))
        )
    }

    private fun transportReply(c: TransportCommand) = when (c) {
        TransportCommand.PAUSE -> "Paused."
        TransportCommand.RESUME -> "Back on."
        TransportCommand.NEXT -> "Skipping ahead."
        TransportCommand.PREVIOUS -> "Running it back."
    }

    private fun eligible(ctx: BrainCtx): List<Track> =
        ctx.tracks.filter { it.id !in ctx.dislikedIds }

    private fun genreOf(t: Track) = t.genre.lowercase()

    // ---- mood ----

    private fun parseMood(m: String, ctx: BrainCtx): ChatPlan? {
        val pool = eligible(ctx)
        val match: (Track) -> Boolean
        val label: String
        when {
            m.contains("banger") || m.contains("rage") || m.contains("hard") ||
                m.contains("turn up") || m.contains("hype") || m.contains("phonk") ||
                m.contains("mosh") -> {
                match = { t -> MOOD_BANGERS.any { genreOf(t).contains(it) } }
                label = "bangers"
            }
            m.contains("soft") || m.contains("chill") || m.contains("calm") ||
                m.contains("mellow") || m.contains("relax") -> {
                match = { t -> MOOD_SOFT.any { genreOf(t).contains(it) } }
                label = "something soft"
            }
            m.contains("late night") || m.contains("latenight") || m.contains("night") -> {
                match = { t -> MOOD_LATE.any { genreOf(t).contains(it) } }
                label = "late-night"
            }
            else -> return null
        }
        var picks = pool.filter(match).take(10).map { it.id }
        if (picks.isEmpty()) {
            // Honest: no genre-tagged tracks for this mood. Fall back to
            // liked-artist lane rather than faking it — and say so.
            val lane = pool.filter { it.artist in ctx.likedArtists }.take(10).map { it.id }
            return if (lane.isNotEmpty()) {
                ChatPlan(
                    reply = "Nothing in your crate is tagged \"$label\" yet — staying in your liked lane instead.",
                    actions = listOf(ApolloAction.QueueUpNext(lane)),
                    whyLines = lane.associateWith { WhyLine.forTrack(it, ctx) }
                )
            } else {
                ChatPlan(
                    reply = "Nothing in your crate is tagged \"$label\" yet — the genre backfill will fix that. Here's what's playing instead.",
                    actions = listOf(ApolloAction.Clarify("No \"$label\" tags in the crate yet."))
                )
            }
        }
        return ChatPlan(
            reply = "On it — $label up next, ${picks.size} deep. The rest of your queue stays as it was.",
            actions = listOf(ApolloAction.QueueUpNext(picks)),
            whyLines = picks.associateWith { WhyLine.forTrack(it, ctx) }
        )
    }

    // ---- artist ----

    private fun parseArtist(m: String, ctx: BrainCtx): ChatPlan? {
        // "play X" / "more X" / "put on X"
        val name = when {
            m.startsWith("play ") -> m.removePrefix("play ").trim()
            m.startsWith("more ") -> m.removePrefix("more ").trim()
            m.startsWith("put on ") -> m.removePrefix("put on ").trim()
            else -> return null
        }
        if (name.isEmpty() || name.length > 60) return null
        val hits = eligible(ctx).filter { it.artist.lowercase().contains(name) }
        if (hits.isEmpty()) return null // not an artist we know -> fall through to clarify
        return if (m.startsWith("more ")) {
            val ids = hits.take(10).map { it.id }
            ChatPlan(
                reply = "More ${hits.first().artist} — ${ids.size} queued up next.",
                actions = listOf(ApolloAction.QueueUpNext(ids)),
                whyLines = ids.associateWith { WhyLine.forTrack(it, ctx) }
            )
        } else {
            val first = hits.first()
            ChatPlan(
                reply = "Playing ${first.artist} — ${first.title}.",
                actions = listOf(ApolloAction.Play(first.id)),
                whyLines = mapOf(first.id to WhyLine.forTrack(first.id, ctx))
            )
        }
    }

    // ---- energy ----

    private fun energyMatch(ctx: BrainCtx): ChatPlan {
        val now = ctx.nowPlayingId?.let { ctx.byId[it] }
            ?: return ChatPlan(
                reply = "Nothing's playing yet — play something and I'll match its energy.",
                actions = listOf(ApolloAction.Clarify("Nothing playing to match."))
            )
        val pool = eligible(ctx).filter { it.id != now.id }
        val genreHits = if (now.genre.isNotEmpty())
            pool.filter { genreOf(it).contains(genreOf(now)) } else emptyList()
        val artistHits = pool.filter { it.artist.equals(now.artist, ignoreCase = true) }
        val picks = (genreHits + artistHits).distinctBy { it.id }.take(10).map { it.id }
        if (picks.isEmpty()) {
            return ChatPlan(
                reply = "Couldn't find a clean energy match in the crate — here's what's playing instead.",
                actions = listOf(ApolloAction.Clarify("No energy match found."))
            )
        }
        val lane = if (now.genre.isNotEmpty()) now.genre else now.artist
        return ChatPlan(
            reply = "Keeping this energy — $lane lane, ${picks.size} queued up next.",
            actions = listOf(ApolloAction.QueueUpNext(picks)),
            whyLines = picks.associateWith { WhyLine.forTrack(it, ctx) }
        )
    }

    // ---- discovery ----

    private fun discovery(ctx: BrainCtx): ChatPlan {
        val drops = ctx.drops
        if (drops.isEmpty()) {
            return ChatPlan(
                reply = "Signal's quiet right now — no fresh drops waiting. The radar sweeps again at 7 AM.",
                actions = listOf(ApolloAction.Discover(0))
            )
        }
        val top = drops.first()
        val rest = if (drops.size > 1) " Plus ${drops.size - 1} more waiting on the Fresh Signals rail." else ""
        return ChatPlan(
            reply = "${drops.size} fresh find(s) waiting — \"${top.artist} — ${top.title}\" is the strongest match to your taste.$rest" +
                " Tap approve on the rail and they go through the verified publish.",
            actions = listOf(ApolloAction.Discover(drops.size))
        )
    }

    // ---- mood gate ----

    private fun parseMoodGate(m: String): ChatPlan? {
        val clear = m.contains("drop the gate") || m.contains("clear the gate") ||
            m.contains("ungate") || m.contains("lift the gate")
        if (clear) {
            return ChatPlan(
                reply = "Gate dropped — everything's back in rotation.",
                actions = listOf(ApolloAction.MoodGate(emptySet(), 0))
            )
        }
        val wantsGate = m.contains("skip everything") || m.contains("no ") && m.contains("for the") ||
            m.contains("gate ") || (m.contains("skip") && m.contains("for "))
        if (!wantsGate) return null
        val genres = mutableSetOf<String>()
        for (g in MOOD_BANGERS + MOOD_SOFT + MOOD_LATE) {
            if (m.contains(g)) genres.add(g)
        }
        if (genres.isEmpty()) {
            // genre words like "soft" are inside the sets above; nothing found -> clarify
            return ChatPlan(
                reply = "Which lane should I gate? Say it like \"skip everything soft for the next hour\".",
                actions = listOf(ApolloAction.Clarify("Which lane should I gate?"))
            )
        }
        val minutes = when {
            m.contains("30 min") || m.contains("half hour") -> 30
            m.contains("15 min") -> 15
            m.contains("2 hour") || m.contains("two hour") -> 120
            m.contains("hour") -> 60
            else -> 60
        }
        val label = genres.joinToString(" + ")
        return ChatPlan(
            reply = "Done — $label is gated for the next $minutes minutes. Say \"drop the gate\" anytime.",
            actions = listOf(ApolloAction.MoodGate(genres, minutes))
        )
    }

    // ---- suggest to library: pending-decision only ----

    private fun suggestToLibrary(m: String, ctx: BrainCtx): ChatPlan {
        // Match named drops only — never invent a track.
        val named = ctx.drops.filter { d ->
            m.contains(d.artist.lowercase()) || m.contains(d.title.lowercase())
        }
        if (named.isEmpty()) {
            return ChatPlan(
                reply = "Tell me which find — name the artist or track from the Fresh Signals rail and I'll record it for the verified publish.",
                actions = listOf(ApolloAction.Clarify("Which find should I record?"))
            )
        }
        val now = System.currentTimeMillis()
        val decisions = named.map {
            PendingDecision(
                id = it.id,
                kind = "suggest_to_library",
                artist = it.artist,
                title = it.title,
                why = it.why,
                state = ApolloStore.DecisionState.AWAITING_TAP,
                decidedAt = now
            )
        }
        val names = named.joinToString(", ") { "\"${it.artist} — ${it.title}\"" }
        return ChatPlan(
            reply = "Recorded $names — awaiting your tap. Nothing's added until the verified publish lands; you'll see \"Awaiting publish…\" on the card.",
            actions = listOf(ApolloAction.SuggestToLibrary(decisions)),
            pending = decisions
        )
    }
}

/**
 * Transport commands shared by voice and text. "pause", "stop the music",
 * "hold up" -> PAUSE; "resume", "play" (bare) -> RESUME; "next", "skip" ->
 * NEXT; "previous", "back", "go back" -> PREVIOUS.
 */
object VoiceCommand {
    fun parseTransport(m: String): TransportCommand? {
        val t = m.trim().lowercase()
        return when {
            t == "pause" || t == "stop" || t == "stop the music" ||
                t == "hold up" || t == "hold on" || t == "quiet" -> TransportCommand.PAUSE
            t == "resume" || t == "play" || t == "keep playing" ||
                t == "unpause" -> TransportCommand.RESUME
            t == "next" || t == "skip" || t == "skip this" ||
                t == "next song" || t == "next track" -> TransportCommand.NEXT
            t == "previous" || t == "back" || t == "go back" ||
                t == "last song" || t == "previous song" -> TransportCommand.PREVIOUS
            else -> null
        }
    }
}
