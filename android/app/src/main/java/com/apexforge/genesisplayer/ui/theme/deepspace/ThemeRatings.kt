package com.apexforge.genesisplayer.ui.theme.deepspace

import android.content.Context
import android.util.Log
import androidx.media3.session.MediaController
import org.json.JSONArray
import org.json.JSONObject

/**
 * Firewall-clean like/dislike for the Deep Space theme.
 *
 * The theme may NOT call anything in data/ (Council firewall), so this
 * reimplements the rating read/write against the SAME on-device schema the
 * data layer owns ("genesis_ratings" SharedPreferences):
 *   - "ratings": {trackId: {"r": "like"|"dislike", "ts": epochMs}}
 *   - "outbox":  [{track_id, artist, title, genre, rating, rated_at} ...]
 *     unsynced taste events for Apollo's receiver.
 *
 * Behavior parity with the data layer's Ratings.apply, with two documented
 * nuances:
 *  1. genre is "" here (the theme's state poll doesn't carry genre).
 *  2. TasteSync.syncNow can't be kicked from the theme (data/ class), so an
 *     outbox event enqueued here syncs on the next trigger the app already
 *     performs (PlayerService start) rather than instantly. Best-effort,
 *     fire-and-forget — playback never waits on it either way.
 */
object ThemeRatings {
    private const val TAG = "ThemeRatings"
    private const val PREFS = "genesis_ratings"
    private const val K_RATINGS = "ratings"
    private const val K_OUTBOX = "outbox"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** rating for a track id: "like", "dislike", or null. */
    fun get(context: Context, trackId: String): String? {
        return try {
            val root = JSONObject(prefs(context).getString(K_RATINGS, "{}") ?: "{}")
            root.optJSONObject(trackId)?.optString("r", null)
        } catch (e: Exception) {
            Log.w(TAG, "read failed (${e.message})")
            null
        }
    }

    /**
     * Record a rating ("like"/"dislike"/"clear"/null clears). Persists
     * immediately and enqueues the Apollo outbox event. A dislike on the
     * currently playing track skips to next, matching the old UI.
     * Returns true when the rating was stored.
     */
    fun apply(
        context: Context,
        controller: MediaController?,
        trackId: String?,
        artist: String,
        title: String,
        rating: String?
    ): Boolean {
        val id = trackId ?: controller?.currentMediaItem?.mediaId
        if (id.isNullOrEmpty()) {
            Log.w(TAG, "apply: no track id")
            return false
        }
        val r = when (rating) {
            "like", "dislike" -> rating
            "clear", null -> null
            else -> {
                Log.w(TAG, "apply: bad rating '$rating'")
                return false
            }
        }
        try {
            val p = prefs(context)
            val root = JSONObject(p.getString(K_RATINGS, "{}") ?: "{}")
            if (r == null) {
                root.remove(id)
            } else {
                root.put(id, JSONObject().put("r", r).put("ts", System.currentTimeMillis()))
            }
            p.edit().putString(K_RATINGS, root.toString()).apply()
            if (r != null) {
                val out = JSONArray(p.getString(K_OUTBOX, "[]") ?: "[]")
                out.put(
                    JSONObject()
                        .put("track_id", id)
                        .put("artist", artist)
                        .put("title", title)
                        .put("genre", "")
                        .put("rating", r)
                        .put("rated_at", System.currentTimeMillis())
                )
                p.edit().putString(K_OUTBOX, out.toString()).apply()
            }
            Log.i(TAG, "rating=$r track=$id")
        } catch (e: Exception) {
            Log.w(TAG, "apply: write failed (${e.message})")
            return false
        }
        if (r == "dislike" && controller?.currentMediaItem?.mediaId == id) {
            controller.seekToNext()
            Log.i(TAG, "apply: dislike on current track — skipped to next")
        }
        return true
    }
}
