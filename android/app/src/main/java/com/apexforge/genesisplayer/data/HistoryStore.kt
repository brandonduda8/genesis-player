package com.apexforge.genesisplayer.data

import android.content.Context

/**
 * V1 on-device listening history: per-track play / skip / completion counters.
 * The full Apollo learning loop (re-ranking For You from these signals) is v2;
 * v1 records honestly and surfaces counts in the UI.
 */
object HistoryStore {
    private const val PREFS = "genesis_history"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun recordPlay(context: Context, trackId: String) {
        val p = prefs(context)
        p.edit().putInt("play_$trackId", p.getInt("play_$trackId", 0) + 1).apply()
        HistorySync.record(context, trackId, "play")
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
