package com.apexforge.genesisplayer.data

import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.media3.session.MediaController
import com.apexforge.genesisplayer.PlayerService
import com.apexforge.genesisplayer.data.ApolloStore.PendingDecision
import com.apexforge.genesisplayer.sendGenesis
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

private const val TAG = "GenesisPlayer"

/** Snapshot of what's playing, for the §2.1 context envelope. */
data class NowPlayingSnapshot(
    val trackId: String,
    val artist: String,
    val title: String,
    val positionS: Long
)

data class ChatTurn(
    val reply: String,
    val actions: List<ApolloAction>,
    val pending: List<PendingDecision>,
    val whyLines: Map<String, String>,
    /** True when the on-device brain answered (tailnet brain unreachable). */
    val degraded: Boolean,
    /** Honest caption shown under degraded replies. Null when live. */
    val localNote: String?
)

/**
 * The /apollo/chat orchestrator (APOLLO-LIVE.md §2.1).
 *
 * Tries the tailnet machine brain first; falls back to the deterministic
 * on-device [LocalBrain] on any failure. The fallback is honest, never a
 * fake conversation: every answer is built from real on-device data, and
 * degraded replies carry the "from your device" caption.
 *
 * Action execution rules (hard):
 *  - Only the fixed intent vocabulary is executed. Unknown `op` values from
 *    the machine are IGNORED and logged — the app never guesses.
 *  - Every track_id must exist in the active catalog version. Invalid ids
 *    are dropped, never played.
 *  - `suggest_to_library` creates pending-decision objects only. The ONLY
 *    component that may claim a catalog update landed is the existing
 *    version-gated "Catalog updated — N tracks (vN)" note.
 *
 * Call [chat] off the main thread (it does network I/O).
 */
object ApolloClient {

    /** Intent vocabulary v1 — fixed, no free-form action invention. */
    private val KNOWN_INTENTS = setOf(
        "play", "queue_up_next", "mood_mode", "explain", "discover",
        "taste_feedback", "suggest_to_library", "status", "clarify", "none"
    )

    /** Action ops the app understands. Everything else is ignored + logged. */
    private val KNOWN_OPS = setOf(
        "play", "queue_up_next", "mood_mode", "set_mood_gate", "explain",
        "discover", "taste_feedback", "suggest_to_library", "status",
        "clarify", "none", "transport"
    )

    fun chat(
        app: Context,
        message: String,
        input: String, // "voice" | "text"
        now: NowPlayingSnapshot?,
        queueTail: List<String>
    ): ChatTurn {
        val ctx = buildCtx(app, now, queueTail)
        val requestId = UUID.randomUUID().toString()
        val turn = tryMachine(app, message, input, requestId, now, queueTail, ctx)
            ?: localTurn(message, ctx)
        ApolloStore.cacheReply(app, message, turn.reply, turn.degraded)
        return turn
    }

    // ---- machine path ----

    private fun tryMachine(
        app: Context,
        message: String,
        input: String,
        requestId: String,
        now: NowPlayingSnapshot?,
        queueTail: List<String>,
        ctx: BrainCtx
    ): ChatTurn? {
        return try {
            val body = JSONObject()
                .put("client_request_id", requestId)
                .put("message", message)
                .put("input", input)
                .put("context", JSONObject()
                    .put("device_id", ApolloStore.deviceId(app))
                    .put("app_version", "1.1")
                    .put("catalog_version", ctx.catalogVersion)
                    .put("now_playing", now?.let {
                        JSONObject()
                            .put("track_id", it.trackId)
                            .put("artist", it.artist)
                            .put("title", it.title)
                            .put("position_s", it.positionS)
                    } ?: JSONObject.NULL)
                    .put("queue_tail", JSONArray(queueTail))
                    .put("taste_summary", JSONObject()
                        .put("liked_artists", JSONArray(ctx.likedArtists.toList()))
                        .put("disliked_track_ids", JSONArray(ctx.dislikedIds.toList()))
                        .put("recent_genres", JSONArray(recentGenres(app, ctx).toList()))
                    )
                ).toString()
            val (code, payload) = ApolloNet.postJson(ApolloNet.CHAT_URL, body)
            if (code != 200 || payload.isNullOrEmpty()) {
                Log.i(TAG, "ApolloClient: machine chat unavailable (HTTP $code); using on-device brain")
                return null
            }
            parseMachineResponse(JSONObject(payload), ctx)
        } catch (e: Exception) {
            Log.i(TAG, "ApolloClient: machine chat failed (${e.message}); using on-device brain")
            null
        }
    }

    /**
     * Parse the machine's chat response envelope. Internal (not private) so
     * the API-34 instrumented tests pin the contract: unknown ops are
     * ignored, every track_id is catalog-validated, queue_up_next caps at 10.
     */
    internal fun parseMachineResponse(root: JSONObject, ctx: BrainCtx): ChatTurn? {
        val validIds = ctx.byId.keys
        val reply = root.optString("reply", "").ifEmpty { return null }
        val intentType = root.optJSONObject("intent")?.optString("type", "none") ?: "none"
        if (intentType !in KNOWN_INTENTS) {
            Log.w(TAG, "ApolloClient: unknown intent type '$intentType' from machine; treating as none")
        }
        val actions = mutableListOf<ApolloAction>()
        val arr = root.optJSONArray("actions")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                parseMachineAction(o, validIds)?.let { actions.add(it) }
            }
        }
        val pending = parseMachinePending(root.optJSONArray("pending"))
        val whyLines = pending.associate { it.id to it.why }
        return ChatTurn(
            reply = reply,
            actions = actions,
            pending = pending,
            whyLines = whyLines,
            degraded = false,
            localNote = null
        )
    }

    private fun parseMachineAction(o: JSONObject, validIds: Set<String>): ApolloAction? {
        val op = o.optString("op", "")
        if (op !in KNOWN_OPS) {
            // Forward-compatible: ignore what we don't understand, log it, never guess.
            Log.w(TAG, "ApolloClient: ignoring unknown op '$op' from machine")
            return null
        }
        fun validIdsOf(key: String): List<String> {
            val arr = o.optJSONArray(key) ?: return emptyList()
            val ids = List(arr.length()) { arr.optString(it) }.filter { it in validIds }
            val dropped = arr.length() - ids.size
            if (dropped > 0) Log.w(TAG, "ApolloClient: dropped $dropped unknown track_id(s) from op '$op'")
            return ids
        }
        return when (op) {
            "play" -> {
                val id = o.optString("track_id", "")
                if (id in validIds) ApolloAction.Play(id)
                else { Log.w(TAG, "ApolloClient: play with unknown track_id"); null }
            }
            "queue_up_next" -> {
                val ids = validIdsOf("track_ids").take(10)
                if (ids.isNotEmpty()) ApolloAction.QueueUpNext(ids) else null
            }
            "mood_mode", "set_mood_gate" -> {
                val genres = o.optJSONArray("exclude_genres")?.let { a ->
                    List(a.length()) { a.optString(it).lowercase() }.filter { it.isNotEmpty() }.toSet()
                } ?: emptySet()
                val minutes = o.optInt("window_minutes", 60).coerceIn(0, 240)
                ApolloAction.MoodGate(genres, minutes)
            }
            "explain" -> {
                val id = o.optString("track_id", "")
                if (id in validIds) ApolloAction.Explain(id) else null
            }
            "discover" -> ApolloAction.Discover(o.optInt("count", 3).coerceIn(0, 10))
            "taste_feedback" -> {
                val id = o.optString("track_id", "")
                val rating = o.optString("rating", "")
                if (id in validIds && rating in setOf("like", "dislike"))
                    ApolloAction.TasteFeedback(id, rating)
                else { Log.w(TAG, "ApolloClient: bad taste_feedback"); null }
            }
            "suggest_to_library" -> {
                // The pending[] array carries the decision objects; the action
                // itself just signals "see pending". Parsed separately.
                ApolloAction.SuggestToLibrary(emptyList())
            }
            "status" -> ApolloAction.Status
            "clarify" -> ApolloAction.Clarify(o.optString("question", "Can you say that another way?"))
            "transport" -> when (o.optString("command", "")) {
                "pause" -> ApolloAction.Transport(TransportCommand.PAUSE)
                "resume" -> ApolloAction.Transport(TransportCommand.RESUME)
                "next" -> ApolloAction.Transport(TransportCommand.NEXT)
                "previous" -> ApolloAction.Transport(TransportCommand.PREVIOUS)
                else -> null
            }
            else -> null // "none"
        }
    }

    private fun parseMachinePending(arr: JSONArray?): List<PendingDecision> {
        if (arr == null) return emptyList()
        val out = mutableListOf<PendingDecision>()
        val now = System.currentTimeMillis()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id", "")
            if (id.isEmpty()) continue
            val kind = o.optString("kind", "suggest_to_library")
            val tracks = o.optJSONArray("tracks")
            if (tracks != null && tracks.length() > 0) {
                for (j in 0 until tracks.length()) {
                    val t = tracks.optJSONObject(j) ?: continue
                    out.add(
                        PendingDecision(
                            id = id,
                            kind = kind,
                            artist = t.optString("artist", ""),
                            title = t.optString("title", ""),
                            why = t.optString("why", ""),
                            state = ApolloStore.DecisionState.AWAITING_TAP,
                            decidedAt = now
                        )
                    )
                }
            } else {
                out.add(
                    PendingDecision(
                        id = id,
                        kind = kind,
                        artist = o.optString("artist", ""),
                        title = o.optString("title", ""),
                        why = o.optString("why", o.optString("note", "")),
                        state = ApolloStore.DecisionState.AWAITING_TAP,
                        decidedAt = now
                    )
                )
            }
        }
        return out
    }

    // ---- local fallback ----

    private fun localTurn(message: String, ctx: BrainCtx): ChatTurn {
        val plan = LocalBrain.resolve(message, ctx)
        return ChatTurn(
            reply = plan.reply,
            actions = plan.actions,
            pending = plan.pending,
            whyLines = plan.whyLines,
            degraded = true,
            localNote = "from your device"
        )
    }

    // ---- context building ----

    private fun buildCtx(app: Context, now: NowPlayingSnapshot?, queueTail: List<String>): BrainCtx {
        val tracks = try { Library.tracks } catch (e: Exception) { emptyList() }
        val byId = tracks.associateBy { it.id }
        val ratings = mutableMapOf<String, String>()
        try {
            val root = JSONObject(
                app.getSharedPreferences("genesis_ratings", Context.MODE_PRIVATE)
                    .getString("ratings", "{}") ?: "{}"
            )
            root.keys().forEach { id -> root.optJSONObject(id)?.optString("r")?.let { ratings[id] = it } }
        } catch (e: Exception) { /* best-effort */ }
        val plays = mutableMapOf<String, Int>()
        for (t in tracks) {
            val n = HistoryStore.plays(app, t.id)
            if (n > 0) plays[t.id] = n
        }
        val (nRatings, nUnsynced) = try { RatingsStore.loadSummary(app) } catch (e: Exception) { 0 to 0 }
        return BrainCtx(
            tracks = tracks,
            byId = byId,
            nowPlayingId = now?.trackId,
            queueTailIds = queueTail,
            likedArtists = try { RatingsStore.likedArtists(app) } catch (e: Exception) { emptySet() },
            likedGenres = try { RatingsStore.likedGenres(app) } catch (e: Exception) { emptySet() },
            dislikedIds = ratings.filter { it.value == "dislike" }.keys,
            ratings = ratings,
            playCounts = plays,
            drops = ApolloDrops.current(),
            catalogVersion = try { RemoteCatalog.activeVersion(app) } catch (e: Exception) { 0 },
            pendingCount = try { ApolloStore.decisions(app).size } catch (e: Exception) { 0 },
            unsyncedRatings = nUnsynced
        )
    }

    private fun recentGenres(app: Context, ctx: BrainCtx): Set<String> {
        val out = linkedSetOf<String>()
        out.addAll(ctx.likedGenres)
        ctx.playCounts.entries.sortedByDescending { it.value }.take(10).forEach { (id, _) ->
            ctx.byId[id]?.genre?.takeIf { it.isNotEmpty() }?.let { out.add(it) }
        }
        return out.take(5).toSet()
    }

    // ---- action execution: ONLY known ops, catalog-validated ----

    fun execute(app: Context, controller: MediaController?, turn: ChatTurn) {
        for (a in turn.actions) {
            try {
                when (a) {
                    is ApolloAction.Play -> {
                        if (Library.track(a.trackId) == null) {
                            Log.w(TAG, "ApolloClient: refusing to play unknown id ${a.trackId}")
                        } else {
                            val args = Bundle().apply {
                                putStringArrayList("ids", arrayListOf(a.trackId))
                                putInt("index", 0)
                            }
                            controller?.sendGenesis(PlayerService.ACTION_PLAY_IDS, args)
                            Log.i(TAG, "ApolloClient: play ${a.trackId}")
                        }
                    }
                    is ApolloAction.QueueUpNext -> {
                        val ids = a.trackIds.filter { Library.track(it) != null }.take(10)
                        if (ids.size < a.trackIds.size) {
                            Log.w(TAG, "ApolloClient: dropped unknown ids from queue_up_next")
                        }
                        if (ids.isNotEmpty()) {
                            val args = Bundle().apply {
                                putStringArrayList("ids", ArrayList(ids))
                            }
                            controller?.sendGenesis(PlayerService.ACTION_QUEUE_UP_NEXT, args)
                            Log.i(TAG, "ApolloClient: queue_up_next ${ids.size} tracks")
                        }
                    }
                    is ApolloAction.MoodGate -> {
                        if (a.excludeGenres.isEmpty() || a.windowMinutes <= 0) {
                            ApolloStore.clearMoodGate(app)
                            Log.i(TAG, "ApolloClient: mood gate cleared")
                        } else {
                            ApolloStore.setMoodGate(app, a.excludeGenres, a.windowMinutes)
                        }
                    }
                    is ApolloAction.Explain -> {
                        // The reply already carries the why-line; nothing to drive.
                        Log.i(TAG, "ApolloClient: explain ${a.trackId}")
                    }
                    is ApolloAction.Discover -> {
                        // The reply names the finds; the Fresh Signals rail
                        // renders them. Re-poll so the rail is current.
                        ApolloDrops.poll(app)
                    }
                    is ApolloAction.TasteFeedback -> {
                        // Same effect as the thumbs button: through the /rate pipeline.
                        Ratings.apply(app, controller, a.trackId, a.rating)
                        Log.i(TAG, "ApolloClient: taste_feedback ${a.trackId}=${a.rating}")
                    }
                    is ApolloAction.SuggestToLibrary -> {
                        // Pending-decision ONLY. Never mutates the catalog.
                        // (Machine pending objects are in turn.pending; local
                        // ones ride in the action.)
                        val all = turn.pending + a.suggestions
                        all.forEach { ApolloStore.putDecision(app, it) }
                        Log.i(TAG, "ApolloClient: recorded ${all.size} pending decision(s)")
                    }
                    is ApolloAction.Status -> Log.i(TAG, "ApolloClient: status shown")
                    is ApolloAction.Clarify -> Log.i(TAG, "ApolloClient: clarify")
                    is ApolloAction.None -> { /* small talk; nothing to drive */ }
                    is ApolloAction.Transport -> TransportExecutor.execute(controller, a.command)
                }
            } catch (e: Exception) {
                Log.w(TAG, "ApolloClient: action execution failed (${e.message})")
            }
        }
        // Machine pending decisions are always recorded, even without the action.
        turn.pending.forEach { ApolloStore.putDecision(app, it) }
    }
}

/**
 * Transport execution shared by voice + text. NEXT goes through the service's
 * userSkip path (records the skip honestly); pause/resume drive the player.
 */
object TransportExecutor {
    fun execute(controller: MediaController?, cmd: TransportCommand): Boolean {
        val c = controller ?: run {
            Log.w(TAG, "TransportExecutor: no controller for $cmd")
            return false
        }
        when (cmd) {
            TransportCommand.PAUSE -> c.pause()
            TransportCommand.RESUME -> c.play()
            TransportCommand.NEXT -> c.sendGenesis(PlayerService.ACTION_SKIP_NEXT)
            TransportCommand.PREVIOUS -> c.sendGenesis(PlayerService.ACTION_SKIP_PREV)
        }
        Log.i(TAG, "TransportExecutor: executed $cmd")
        return true
    }
}
