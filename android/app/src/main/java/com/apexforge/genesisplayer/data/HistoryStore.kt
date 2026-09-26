package com.apexforge.genesisplayer.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * V1 on-device listening history: per-track play / skip / completion counters,
 * plus a timestamped recent-plays list (BRKN wave 3) backing the Library's
 * Recently Played section. The full Apollo learning loop (re-ranking For You
 * from these signals) is v2; v1 records honestly and surfaces counts in the UI.
 */
object HistoryStore {
    private const val PREFS = "genesis_history"
    private const val K_RECENT = "recent_plays"
    private const val RECENT_CAP = 20

    /** One timestamped recent play, newest first. */
    data class RecentPlay(val trackId: String, val playedAt: Long)

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun recordPlay(context: Context, trackId: String) {
        val p = prefs(context)
        p.edit().putInt("play_$trackId", p.getInt("play_$trackId", 0) + 1).apply()
        recordRecent(context, trackId)
        HistorySync.record(context, trackId, "play")
    }

    /** Newest-first recent plays, capped. Never throws. */
    fun recentPlays(context: Context): List<RecentPlay> {
        return try {
            val arr = JSONArray(prefs(context).getString(K_RECENT, "[]") ?: "[]")
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                RecentPlay(o.optString("id", ""), o.optLong("ts", 0L))
            }.filter { it.trackId.isNotEmpty() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun recordRecent(context: Context, trackId: String) {
        try {
            val p = prefs(context)
            val updated = pushRecent(recentPlays(context), trackId)
            val out = JSONArray()
            for (r in updated) out.put(JSONObject().put("id", r.trackId).put("ts", r.playedAt))
            p.edit().putString(K_RECENT, out.toString()).apply()
        } catch (e: Exception) {
            // Best-effort; the counters above already recorded the play.
        }
    }

    /**
     * Pure recent-list update: newest-first, de-duplicated, capped.
     * No I/O, no network — this is what the instrumented tests pin, so the
     * test path never spawns HistorySync flush threads.
     */
    fun pushRecent(
        existing: List<RecentPlay>,
        trackId: String,
        now: Long = System.currentTimeMillis()
    ): List<RecentPlay> {
        val out = mutableListOf(RecentPlay(trackId, now))
        for (r in existing) {
            if (r.trackId != trackId) out.add(r)
            if (out.size >= RECENT_CAP) break
        }
        return out
    }

    fun recordSkip(context: Context, trackId: String) {
        val p = prefs(context)
        p.edit().putInt("skip_$trackId", p.getInt("skip_$trackId", 0) + 1).apply()
        HistorySync.record(context, trackId, "skip")
    }

    fun recordCompletion(context: Context, trackId: String) {
        val p = prefs(context)
        p.edit().putInt("done_$trackId", p.getInt("done_$trackId", 0) + 1).apply()
        HistorySync.record(context, trackId, "completion")
    }

    fun plays(context: Context, trackId: String): Int =
        prefs(context).getInt("play_$trackId", 0)
}
