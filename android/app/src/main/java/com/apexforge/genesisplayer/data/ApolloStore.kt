package com.apexforge.genesisplayer.data

import android.content.Context
import android.provider.Settings
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

private const val TAG = "GenesisPlayer"

/**
 * On-device Apollo state (SharedPreferences "apollo"). Everything here is
 * the UI source of truth; the machine never holds his library or history.
 *
 * - chat_cache: last 20 turns [{input, reply, at, degraded}] — the offline rail
 * - decisions: pending-decision objects, the tiny state machine
 *   awaiting_tap -> recorded -> syncing -> published | failed (APOLLO-LIVE §5)
 * - mood_gate: {exclude: "g1,g2", until: epochMs} — client-side genre gate
 * - wifi_only_artwork: bool — artwork downloads only on Wi-Fi when true
 */
object ApolloStore {
    private const val PREFS = "apollo"
    private const val K_CHAT = "chat_cache"
    private const val K_DECISIONS = "decisions"
    private const val K_MOOD = "mood_gate"
    private const val K_WIFI_ART = "wifi_only_artwork"

    /** Pending-decision states. The app may ONLY ever show these — never "added". */
    object DecisionState {
        const val AWAITING_TAP = "awaiting_tap"
        const val RECORDED = "recorded"
        const val SYNCING = "syncing"
        const val PUBLISHED = "published"
        const val FAILED = "failed"
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Stable per-install device id (idempotency + sync correlation, not login). */
    fun deviceId(context: Context): String {
        return try {
            Settings.Secure.getString(
                context.applicationContext.contentResolver,
                Settings.Secure.ANDROID_ID
            ) ?: "unknown-device"
        } catch (e: Exception) { "unknown-device" }
    }

    // ---- chat cache: last 20 replies (the offline rail) ----

    fun cacheReply(context: Context, input: String, reply: String, degraded: Boolean) {
        try {
            val app = context.applicationContext
            val arr = JSONArray(prefs(app).getString(K_CHAT, "[]") ?: "[]")
            arr.put(
                JSONObject()
                    .put("input", input.take(300))
                    .put("reply", reply.take(1200))
                    .put("at", System.currentTimeMillis())
                    .put("degraded", degraded)
            )
            while (arr.length() > 20) arr.remove(0)
            prefs(app).edit().putString(K_CHAT, arr.toString()).apply()
        } catch (e: Exception) {
            Log.w(TAG, "ApolloStore: cacheReply failed (${e.message})")
        }
    }

    fun cachedReplies(context: Context): List<JSONObject> {
        return try {
            val arr = JSONArray(prefs(context).getString(K_CHAT, "[]") ?: "[]")
            List(arr.length()) { i -> arr.getJSONObject(i) }
        } catch (e: Exception) { emptyList() }
    }

    // ---- pending decisions: the tiny state machine ----

    data class PendingDecision(
        val id: String,
        val kind: String,
        val artist: String,
        val title: String,
        val why: String,
        val state: String,
        val decidedAt: Long
    )

    fun putDecision(context: Context, d: PendingDecision) {
        try {
            val app = context.applicationContext
            val arr = JSONArray(prefs(app).getString(K_DECISIONS, "[]") ?: "[]")
            // upsert by id
            val out = JSONArray()
            var replaced = false
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                if (o.optString("id") == d.id) {
                    out.put(decisionJson(d)); replaced = true
                } else out.put(o)
            }
            if (!replaced) out.put(decisionJson(d))
            prefs(app).edit().putString(K_DECISIONS, out.toString()).apply()
        } catch (e: Exception) {
            Log.w(TAG, "ApolloStore: putDecision failed (${e.message})")
        }
    }

    private fun decisionJson(d: PendingDecision) = JSONObject()
        .put("id", d.id)
        .put("kind", d.kind)
        .put("artist", d.artist)
        .put("title", d.title)
        .put("why", d.why)
        .put("state", d.state)
        .put("decided_at", d.decidedAt)

    fun decisions(context: Context): List<PendingDecision> {
        return try {
            val arr = JSONArray(prefs(context).getString(K_DECISIONS, "[]") ?: "[]")
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                PendingDecision(
                    id = o.optString("id"),
                    kind = o.optString("kind"),
                    artist = o.optString("artist"),
                    title = o.optString("title"),
                    why = o.optString("why"),
                    state = o.optString("state", DecisionState.AWAITING_TAP),
                    decidedAt = o.optLong("decided_at", 0)
                )
            }
        } catch (e: Exception) { emptyList() }
    }

    fun setDecisionState(context: Context, id: String, state: String) {
        val cur = decisions(context).find { it.id == id } ?: return
        putDecision(context, cur.copy(state = state))
        Log.i(TAG, "ApolloStore: decision $id -> $state")
    }

    fun removeDecision(context: Context, id: String) {
        try {
            val app = context.applicationContext
            val arr = JSONArray(prefs(app).getString(K_DECISIONS, "[]") ?: "[]")
            val out = JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                if (o.optString("id") != id) out.put(o)
            }
            prefs(app).edit().putString(K_DECISIONS, out.toString()).apply()
        } catch (e: Exception) {
            Log.w(TAG, "ApolloStore: removeDecision failed (${e.message})")
        }
    }

    // ---- mood gate: client-side genre gate with a timer ----

    data class MoodGateState(val excludeGenres: Set<String>, val untilMs: Long) {
        fun isActive(now: Long = System.currentTimeMillis()) = now < untilMs && excludeGenres.isNotEmpty()
        fun minutesLeft(now: Long = System.currentTimeMillis()) =
            ((untilMs - now) / 60000).toInt().coerceAtLeast(0)
    }

    fun setMoodGate(context: Context, excludeGenres: Set<String>, windowMinutes: Int) {
        val until = System.currentTimeMillis() + windowMinutes * 60000L
        prefs(context.applicationContext).edit()
            .putString(K_MOOD, excludeGenres.joinToString(",") + "|" + until)
            .apply()
        Log.i(TAG, "ApolloStore: mood gate set, excluding $excludeGenres for ${windowMinutes}m")
    }

    fun clearMoodGate(context: Context) {
        prefs(context.applicationContext).edit().remove(K_MOOD).apply()
        Log.i(TAG, "ApolloStore: mood gate cleared")
    }

    fun moodGate(context: Context): MoodGateState {
        return try {
            val raw = prefs(context).getString(K_MOOD, null) ?: return MoodGateState(emptySet(), 0)
            val parts = raw.split("|")
            val genres = parts[0].split(",").map { it.trim().lowercase() }
                .filter { it.isNotEmpty() }.toSet()
            val until = parts.getOrNull(1)?.toLongOrNull() ?: 0L
            MoodGateState(genres, until)
        } catch (e: Exception) { MoodGateState(emptySet(), 0) }
    }

    /** True when the track's genre is currently gated out. */
    fun isGated(context: Context, track: Track?): Boolean {
        if (track == null) return false
        val g = moodGate(context)
        if (!g.isActive()) return false
        val tg = track.genre.lowercase()
        return tg.isNotEmpty() && g.excludeGenres.any { ex -> tg.contains(ex) }
    }

    // ---- settings ----

    fun wifiOnlyArtwork(context: Context): Boolean =
        prefs(context).getBoolean(K_WIFI_ART, false)

    fun setWifiOnlyArtwork(context: Context, v: Boolean) {
        prefs(context.applicationContext).edit().putBoolean(K_WIFI_ART, v).apply()
    }
}
