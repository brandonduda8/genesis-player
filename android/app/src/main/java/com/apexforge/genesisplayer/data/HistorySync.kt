package com.apexforge.genesisplayer.data

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

private const val TAG = "GenesisPlayer"

/**
 * Batched play/skip/completion sync (APOLLO-LIVE.md §2.3).
 *
 * Events accumulate on-device and flush in one POST on Wi-Fi or every
 * N events, outbox-style retry. Lossy-by-design: this is statistical
 * signal, not a ledger — if a batch is dropped after retry exhaustion the
 * app logs it and moves on. The machine folds counts into apollo_taste.json
 * (play-through rate per artist/genre); the on-device HistoryStore stays the
 * UI source of truth. The machine never exports taste data anywhere.
 */
object HistorySync {
    private const val PREFS = "apollo_history"
    private const val K_OUTBOX = "history_outbox"
    private const val FLUSH_EVERY = 20
    private const val MAX_QUEUED = 500

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun record(context: Context, trackId: String, event: String) {
        try {
            val app = context.applicationContext
            val p = prefs(app)
            val out = JSONArray(p.getString(K_OUTBOX, "[]") ?: "[]")
            out.put(
                JSONObject()
                    .put("track_id", trackId)
                    .put("event", event) // play | skip | completion
                    .put("at", System.currentTimeMillis())
            )
            // Lossy-by-design: cap the queue, drop the oldest first.
            while (out.length() > MAX_QUEUED) out.remove(0)
            p.edit().putString(K_OUTBOX, out.toString()).apply()
            maybeFlush(app, out.length())
        } catch (e: Exception) {
            Log.w(TAG, "HistorySync: record failed (${e.message})")
        }
    }

    fun queued(context: Context): Int {
        return try {
            JSONArray(prefs(context).getString(K_OUTBOX, "[]") ?: "[]").length()
        } catch (e: Exception) { 0 }
    }

    private fun maybeFlush(app: Context, queued: Int) {
        if (queued >= FLUSH_EVERY || ApolloNet.isOnWifi(app)) {
            flush(app)
        }
    }

    /** Fire-and-forget flush. Never blocks the caller, never throws. */
    fun flush(context: Context) {
        val app = context.applicationContext
        Thread {
            try {
                val p = prefs(app)
                val out = JSONArray(p.getString(K_OUTBOX, "[]") ?: "[]")
                if (out.length() == 0) return@Thread
                val events = JSONArray()
                for (i in 0 until out.length()) events.put(out.getJSONObject(i))
                val body = JSONObject()
                    .put("device_id", ApolloStore.deviceId(app))
                    .put("events", events)
                    .toString()
                val (code, _) = ApolloNet.postJson(ApolloNet.HISTORY_URL, body)
                if (code == 200) {
                    p.edit().putString(K_OUTBOX, "[]").apply()
                    Log.i(TAG, "HistorySync: flushed ${events.length()} event(s)")
                } else {
                    Log.i(TAG, "HistorySync: machine unreachable (HTTP $code); ${events.length()} event(s) stay queued")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "HistorySync: flush failed (${t.message})")
            }
        }.apply { isDaemon = true; name = "history-sync" }.start()
    }
}
