package com.apexforge.genesisplayer.data

import android.content.Context
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import com.apexforge.genesisplayer.data.ApolloStore.DecisionState
import com.apexforge.genesisplayer.data.ApolloStore.PendingDecision
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

private const val TAG = "GenesisPlayer"

/** One verified find waiting for Brandon's tap. Never fabricated. */
data class DropItem(
    val id: String,
    val artist: String,
    val title: String,
    val why: String,
    val genre: String,
    /** "tailnet" (rich) or "public" (limited fallback). */
    val source: String
)

/**
 * The "Apollo drops" channel (APOLLO-LIVE.md §2.2). Poll-based — Apollo
 * cannot push to the phone ($0 rule, no push infra).
 *
 * - Polled on launch and on Refresh Music (v1 cadence).
 * - Tailnet down → the public apollo_suggestions.json feed, with a
 *   "limited" note. Nothing older than the last successful poll is shown.
 * - Empty → no card, no noise.
 * - Approve/reject taps feed the decision bridge: decision recorded locally
 *   (state machine in ApolloStore) → POST /apollo/decide → machine
 *   `watch --decisions` → verified publish. The card shows
 *   "Awaiting publish…" — never "added".
 *
 * CONTRACT NOTE for the machine-side sibling: this posts decisions to
 * POST /apollo/decide {client_request_id, suggestion_id, decision}. If that
 * endpoint is unreachable, decisions stay RECORDED locally and retry — the
 * UI honestly shows "Awaiting publish…".
 *
 * HONESTY RULE (NEVER-2): a decision only becomes PUBLISHED when the machine
 * reports the suggestion's status as "published" (i.e. the catalog version
 * actually advanced and the track is in it). "approved" keeps the card on
 * "Awaiting publish…" — never "Published ✓".
 */
object ApolloDrops {
    /** Live list; observed by the Fresh Signals rail. Empty = no card. */
    val drops = mutableStateOf<List<DropItem>>(emptyList())

    /** True when rendering the public feed instead of the tailnet channel. */
    val limited = mutableStateOf(false)

    /** Epoch ms of the last successful poll (any source). */
    val lastPollAt = mutableStateOf(0L)

    /**
     * Decided-suggestion statuses from the last drops poll, keyed by
     * suggestion id (forward-compatible "decided" extra on the machine's
     * /apollo/drops). Drives the honest reconcile below: only a real
     * "published" status may flip a SYNCING decision to PUBLISHED.
     */
    internal var decidedStatuses: Map<String, String> = emptyMap()

    fun current(): List<DropItem> = drops.value

    /** Fire-and-forget poll. Never blocks the caller, never throws. */
    fun poll(context: Context) {
        val app = context.applicationContext
        Thread {
            try {
                // 1. Rich channel: the tailnet drops endpoint.
                val (code, payload) = ApolloNet.get(ApolloNet.DROPS_URL)
                if (code == 200 && !payload.isNullOrEmpty()) {
                    val items = parseDropsPayload(JSONObject(payload), "tailnet")
                    applyPoll(app, items, limited = false)
                    DecideSync.kick(app)
                    return@Thread
                }
                Log.i(TAG, "ApolloDrops: tailnet drops unavailable (HTTP $code); trying public feed")
                // 2. Zero-auth fallback: the public suggestions feed.
                val (pCode, pPayload) = ApolloNet.get(ApolloNet.PUBLIC_SUGGESTIONS_URL)
                if (pCode == 200 && !pPayload.isNullOrEmpty()) {
                    val items = parseDropsPayload(JSONObject(pPayload), "public")
                    applyPoll(app, items, limited = true)
                } else {
                    Log.i(TAG, "ApolloDrops: public feed also unavailable (HTTP $pCode); keeping last render")
                }
                DecideSync.kick(app)
            } catch (t: Throwable) {
                Log.w(TAG, "ApolloDrops: poll failed (${t.message}); keeping last render")
            }
        }.apply { isDaemon = true; name = "apollo-drops" }.start()
    }

    /**
     * Internal (not private) so the API-34 instrumented tests pin the drops
     * payload contract: pending + hunter_fresh + legacy "suggestions" arrays,
     * non-pending statuses skipped, ids deduped.
     */
    internal fun parseDropsPayload(root: JSONObject, source: String): List<DropItem> {
        // Tailnet shape: {pending: [...], hunter_fresh: [...]}.
        // Public shape:  {pending: [...]} (older files used "suggestions").
        val out = mutableListOf<DropItem>()
        val seen = mutableSetOf<String>()
        fun take(arr: JSONArray?) {
            if (arr == null) return
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.optString("status", "pending") != "pending") continue
                val id = o.optString("id", "")
                if (id.isEmpty() || id in seen) continue
                seen.add(id)
                out.add(
                    DropItem(
                        id = id,
                        artist = o.optString("artist", "Unknown"),
                        title = o.optString("title", "Untitled"),
                        why = o.optString("why", ""),
                        genre = o.optString("genre", ""),
                        source = source
                    )
                )
            }
        }
        take(root.optJSONArray("pending"))
        take(root.optJSONArray("hunter_fresh"))
        take(root.optJSONArray("suggestions"))
        // Forward-compatible machine extra: decided suggestions
        // (approved/rejected/published) with their real status. Absence keeps
        // the old reconcile behavior (never claim published).
        val decided = mutableMapOf<String, String>()
        root.optJSONArray("decided")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id", "")
                val st = o.optString("status", "")
                if (id.isNotEmpty() && st.isNotEmpty()) decided[id] = st
            }
        }
        decidedStatuses = decided
        return out
    }

    private fun applyPoll(app: Context, items: List<DropItem>, limited: Boolean) {
        drops.value = items
        this.limited.value = limited
        lastPollAt.value = System.currentTimeMillis()
        Log.i(TAG, "ApolloDrops: polled ${items.size} drop(s) (limited=$limited)")
        reconcileDecisions(app, items)
    }

    /**
     * Reconcile pending decisions against the fresh poll — honestly.
     * A SYNCING decision becomes PUBLISHED ONLY when the machine's "decided"
     * array reports its status as "published" (catalog version really
     * advanced). "approved" keeps the card on "Awaiting publish…"; anything
     * unconfirmed stays SYNCING — never a premature "Published ✓" (NEVER-2).
     */
    private fun reconcileDecisions(app: Context, items: List<DropItem>) {
        for (d in ApolloStore.decisions(app)) {
            if (d.state != DecisionState.SYNCING) continue
            when (decidedStatuses[d.id]) {
                "published" -> {
                    ApolloStore.setDecisionState(app, d.id, DecisionState.PUBLISHED)
                    Log.i(TAG, "ApolloDrops: decision ${d.id} verified published machine-side")
                }
                "approved" -> Log.i(
                    TAG,
                    "ApolloDrops: decision ${d.id} approved machine-side; " +
                        "card stays on Awaiting publish…"
                )
                "rejected" -> {
                    ApolloStore.removeDecision(app, d.id)
                    Log.i(TAG, "ApolloDrops: decision ${d.id} rejected machine-side; record dropped")
                }
                else -> Log.i(
                    TAG,
                    "ApolloDrops: decision ${d.id} not confirmed machine-side; staying SYNCING"
                )
            }
        }
    }

    // ---- approve / reject: the decision bridge ----

    /**
     * Approve a drop or a chat pending-decision. Records the decision
     * (RECORDED) and kicks the sync; the machine's verified publish runs
     * after. Never claims "added".
     */
    /**
     * BRKN wave 4: snapshot suggestions are not live drops, so their real
     * metadata (artist/title/why from the machine snapshot) is passed in by
     * the caller and preserved in the recorded decision — never invented,
     * never blanked. The sync path is unchanged (DecideSync, one route).
     */
    fun approve(
        context: Context, id: String,
        artist: String = "", title: String = "", why: String = ""
    ) {
        val app = context.applicationContext
        val drop = drops.value.find { it.id == id }
        // Approved leaves the Fresh Signals rail immediately (pending-only);
        // the decision still syncs as "Awaiting publish…".
        drops.value = drops.value.filter { it.id != id }
        val existing = ApolloStore.decisions(app).find { it.id == id }
        val d = existing?.copy(state = DecisionState.RECORDED)
            ?: PendingDecision(
                id = id,
                kind = "suggest_to_library",
                artist = drop?.artist ?: artist,
                title = drop?.title ?: title.ifEmpty { id },
                why = drop?.why ?: why,
                state = DecisionState.RECORDED,
                decidedAt = System.currentTimeMillis()
            )
        ApolloStore.putDecision(app, d)
        DecideSync.kick(app)
        Log.i(TAG, "ApolloDrops: approved $id (recorded; awaiting verified publish)")
    }

    fun reject(
        context: Context, id: String,
        artist: String = "", title: String = "", why: String = ""
    ) {
        val app = context.applicationContext
        // Reject removes the card immediately; the decision still syncs so
        // the machine stops suggesting it.
        drops.value = drops.value.filter { it.id != id }
        val existing = ApolloStore.decisions(app).find { it.id == id }
        if (existing != null) {
            ApolloStore.putDecision(app, existing.copy(state = DecisionState.RECORDED))
        } else {
            ApolloStore.putDecision(
                app,
                PendingDecision(
                    id = id,
                    kind = "reject",
                    artist = artist, title = title.ifEmpty { id }, why = why,
                    state = DecisionState.RECORDED,
                    decidedAt = System.currentTimeMillis()
                )
            )
        }
        DecideSync.kick(app)
        Log.i(TAG, "ApolloDrops: rejected $id")
    }

    /** Decisions still needing a tap or a sync, for the status line. */
    fun openDecisions(context: Context): List<PendingDecision> =
        ApolloStore.decisions(context).filter {
            it.state == DecisionState.AWAITING_TAP ||
                it.state == DecisionState.RECORDED ||
                it.state == DecisionState.SYNCING
        }
}

/**
 * Fire-and-forget decision sync: pushes RECORDED decisions to the tailnet
 * machine (POST /apollo/decide). Undelivered decisions stay RECORDED and
 * retry on the next poll — nothing is lost, nothing is double-sent (the
 * client_request_id is deterministic per decision id: idempotent retries).
 */
object DecideSync {
    fun kick(context: Context) {
        val app = context.applicationContext
        Thread {
            try {
                val open = ApolloStore.decisions(app).filter {
                    it.state == DecisionState.RECORDED
                }
                if (open.isEmpty()) return@Thread
                for (d in open) {
                    val decision = if (d.kind == "reject") "reject" else "approve"
                    val body = JSONObject()
                        .put("client_request_id", "decide:${d.id}")
                        .put("suggestion_id", d.id)
                        .put("decision", decision)
                        .put("device_id", ApolloStore.deviceId(app))
                        .toString()
                    val (code, _) = ApolloNet.postJson(ApolloNet.DECIDE_URL, body)
                    when (code) {
                        200 -> {
                            ApolloStore.setDecisionState(app, d.id, DecisionState.SYNCING)
                            if (decision == "reject") {
                                // Reject is fully applied once the machine
                                // acknowledges; drop the local record.
                                ApolloStore.removeDecision(app, d.id)
                            }
                        }
                        404 -> Log.i(
                            TAG,
                            "ApolloDrops: /apollo/decide not on the machine yet; " +
                                "decision ${d.id} stays RECORDED, will retry"
                        )
                        -1 -> {
                            Log.i(TAG, "ApolloDrops: machine unreachable; decision ${d.id} stays RECORDED")
                            break
                        }
                        else -> {
                            Log.w(TAG, "ApolloDrops: decide HTTP $code for ${d.id}")
                            if (code in 400..499) {
                                ApolloStore.setDecisionState(app, d.id, DecisionState.FAILED)
                            }
                            break
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "DecideSync: failed (${t.message})")
            }
        }.apply { isDaemon = true; name = "apollo-decide" }.start()
    }
}
