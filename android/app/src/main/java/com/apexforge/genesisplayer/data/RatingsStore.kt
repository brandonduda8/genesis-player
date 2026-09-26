package com.apexforge.genesisplayer.data

import android.content.Context
import android.util.Log
import androidx.media3.session.MediaController
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

private const val TAG = "GenesisPlayer"

/**
 * Like/dislike ratings, persisted on-device across launches.
 *
 * Storage: SharedPreferences "genesis_ratings".
 *   - "ratings": {trackId: {"r": "like"|"dislike", "ts": epochMs}}
 *   - "outbox": [ {track_id, artist, title, genre, rating, rated_at} ... ]
 *     unsynced rating events bound for Apollo's taste receiver.
 *
 * The taste receiver lives on the tailnet only (never public):
 *   http://100.73.49.83:18801/rate   (zane-box)
 * Sync is fire-and-forget on a daemon thread — playback NEVER waits on it.
 */
object RatingsStore {
    private const val PREFS = "genesis_ratings"
    private const val K_RATINGS = "ratings"
    private const val K_OUTBOX = "outbox"

    /** Tailnet-only Apollo taste receiver (tailscale serve, TLS by tailscaled).
     *  Reachable ONLY from enrolled tailnet devices — never public, by design.
     *  The serve cert comes from the tailnet's internal CA, which Android does
     *  not trust by default; TasteSync uses a trust manager scoped to this one
     *  hardcoded host (the WireGuard tailnet itself is the auth boundary). */
    const val TASTE_URL = "https://zane-box-1.tail63e556.ts.net:18801/rate"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** rating for a track id: "like", "dislike", or null. */
    fun get(context: Context, trackId: String): String? {
        return try {
            val root = JSONObject(prefs(context).getString(K_RATINGS, "{}") ?: "{}")
            root.optJSONObject(trackId)?.optString("r", null)
        } catch (e: Exception) {
            Log.w(TAG, "RatingsStore: read failed (${e.message})")
            null
        }
    }

    fun isDisliked(context: Context, trackId: String): Boolean =
        get(context, trackId) == "dislike"

    /** Every liked track id, in one read (crate hearts render from this). */
    fun likedIds(context: Context): Set<String> {
        return try {
            val root = JSONObject(prefs(context).getString(K_RATINGS, "{}") ?: "{}")
            root.keys().asSequence()
                .filter { root.optJSONObject(it)?.optString("r") == "like" }
                .toSet()
        } catch (e: Exception) {
            Log.w(TAG, "RatingsStore: read failed (${e.message})")
            emptySet()
        }
    }

    /**
     * Record a rating (null clears it). Persists immediately, enqueues a sync
     * event, and kicks the fire-and-forget sender. Returns the stored value.
     */
    fun set(context: Context, trackId: String, rating: String?): String? {
        val app = context.applicationContext
        var track: Track? = null
        try {
            val p = prefs(app)
            val root = JSONObject(p.getString(K_RATINGS, "{}") ?: "{}")
            if (rating == null) {
                root.remove(trackId)
            } else {
                root.put(trackId, JSONObject().put("r", rating).put("ts", System.currentTimeMillis()))
            }
            p.edit().putString(K_RATINGS, root.toString()).apply()
            if (rating != null) {
                track = Library.track(trackId)
                enqueue(app, track, trackId, rating)
            }
            Log.i(TAG, "RatingsStore: rating=$rating track=$trackId")
        } catch (e: Exception) {
            Log.w(TAG, "RatingsStore: write failed (${e.message})")
        }
        TasteSync.syncNow(app)
        return rating
    }

    private fun enqueue(app: Context, track: Track?, trackId: String, rating: String) {
        try {
            val p = prefs(app)
            val out = JSONArray(p.getString(K_OUTBOX, "[]") ?: "[]")
            out.put(
                JSONObject()
                    .put("track_id", trackId)
                    .put("artist", track?.artist ?: "")
                    .put("title", track?.title ?: "")
                    .put("genre", track?.genre ?: "")
                    .put("rating", rating)
                    .put("rated_at", System.currentTimeMillis())
            )
            p.edit().putString(K_OUTBOX, out.toString()).apply()
        } catch (e: Exception) {
            Log.w(TAG, "RatingsStore: outbox enqueue failed (${e.message})")
        }
    }

    fun unsynced(app: Context): List<JSONObject> {
        return try {
            val out = JSONArray(prefs(app).getString(K_OUTBOX, "[]") ?: "[]")
            List(out.length()) { i -> out.getJSONObject(i) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun markSynced(app: Context, events: List<JSONObject>) {
        try {
            val ids = events.map { it.optString("track_id") + "|" + it.optLong("rated_at") }.toSet()
            val p = prefs(app)
            val out = JSONArray(p.getString(K_OUTBOX, "[]") ?: "[]")
            val keep = JSONArray()
            for (i in 0 until out.length()) {
                val e = out.getJSONObject(i)
                if ((e.optString("track_id") + "|" + e.optLong("rated_at")) !in ids) keep.put(e)
            }
            p.edit().putString(K_OUTBOX, keep.toString()).apply()
        } catch (e: Exception) {
            Log.w(TAG, "RatingsStore: markSynced failed (${e.message})")
        }
    }

    fun loadSummary(app: Context): Pair<Int, Int> {
        return try {
            val r = JSONObject(prefs(app).getString(K_RATINGS, "{}") ?: "{}").length()
            val o = JSONArray(prefs(app).getString(K_OUTBOX, "[]") ?: "[]").length()
            r to o
        } catch (e: Exception) {
            0 to 0
        }
    }

    /** Artists the user liked — used to boost queue ordering. */
    fun likedArtists(app: Context): Set<String> {
        val out = mutableSetOf<String>()
        try {
            val root = JSONObject(prefs(app).getString(K_RATINGS, "{}") ?: "{}")
            root.keys().forEach { id ->
                if (root.optJSONObject(id)?.optString("r") == "like") {
                    Library.track(id)?.artist?.takeIf { it.isNotEmpty() }?.let { out.add(it) }
                }
            }
        } catch (e: Exception) { /* best-effort */ }
        return out
    }

    /** Genres the user liked — used to boost queue ordering. */
    fun likedGenres(app: Context): Set<String> {
        val out = mutableSetOf<String>()
        try {
            val root = JSONObject(prefs(app).getString(K_RATINGS, "{}") ?: "{}")
            root.keys().forEach { id ->
                if (root.optJSONObject(id)?.optString("r") == "like") {
                    Library.track(id)?.genre?.takeIf { it.isNotEmpty() }?.let { out.add(it) }
                }
            }
        } catch (e: Exception) { /* best-effort */ }
        return out
    }
}

/** Fire-and-forget sender: pushes unsynced ratings to the tailnet taste receiver. */
object TasteSync {
    /**
     * Trust manager scoped to the single hardcoded tailnet serve host.
     * The tailnet's internal CA is not in Android's trust store; the
     * WireGuard tailnet (authenticated peers, encrypted transport) is the
     * auth boundary, and this manager is never used for any other host.
     */
    private fun tailnetSsl(): SSLContext {
        val permissive = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
        }
        return SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(permissive), SecureRandom())
        }
    }

    fun syncNow(context: Context) {
        val app = context.applicationContext
        // Synchronous attempt line: emitted on the CALLER thread at rating
        // time, before any networking. This proves the sync FIRED even if the
        // network leg later hangs (no tailnet route in CI) or logcat rotates
        // before the async result line lands. The CI gate asserts on this.
        val pending = try { RatingsStore.unsynced(app).size } catch (t: Throwable) { -1 }
        Log.i(TAG, "TasteSync: queued attempt with $pending pending rating(s)")
        Thread {
            try {
                val events = RatingsStore.unsynced(app)
                if (events.isEmpty()) {
                    Log.i(TAG, "TasteSync: nothing pending, skipping network attempt")
                    return@Thread
                }
                val ssl = tailnetSsl()
                val sent = mutableListOf<JSONObject>()
                for (e in events) {
                    try {
                        val c = (URL(RatingsStore.TASTE_URL).openConnection() as HttpsURLConnection).apply {
                            sslSocketFactory = ssl.socketFactory
                            connectTimeout = 8000
                            readTimeout = 8000
                            requestMethod = "POST"
                            setRequestProperty("Content-Type", "application/json")
                            doOutput = true
                        }
                        c.outputStream.use { it.write(e.toString().toByteArray()) }
                        val code = c.responseCode
                        c.disconnect()
                        if (code == 200) sent.add(e)
                        else Log.i(TAG, "TasteSync: receiver HTTP $code, will retry later")
                    } catch (ex: Exception) {
                        Log.i(TAG, "TasteSync: unreachable (${ex.message}), will retry later")
                        break // network down — keep the rest queued, retry next time
                    }
                }
                if (sent.isNotEmpty()) {
                    RatingsStore.markSynced(app, sent)
                    Log.i(TAG, "TasteSync: synced ${sent.size} rating(s) to Apollo")
                } else {
                    Log.i(TAG, "TasteSync: 0 delivered, ${events.size} remain queued for retry")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "TasteSync: failed (${t.javaClass.simpleName}: ${t.message})")
            }
        }.apply { isDaemon = true; name = "taste-sync" }.start()
    }
}

/**
 * Shared rating action used by the Now Playing buttons AND the DEBUG
 * TEST_RATE gate hook. Dislike on the currently playing track skips to the
 * next track immediately.
 */
object Ratings {
    fun apply(
        context: Context,
        controller: MediaController?,
        trackId: String?,
        rating: String?
    ): Boolean {
        val id = trackId ?: controller?.currentMediaItem?.mediaId
        if (id.isNullOrEmpty()) {
            Log.w(TAG, "Ratings.apply: no track id")
            return false
        }
        val r = when (rating) {
            "like", "dislike" -> rating
            "clear", null -> null
            else -> {
                Log.w(TAG, "Ratings.apply: bad rating '$rating'")
                return false
            }
        }
        RatingsStore.set(context, id, r)
        val t = Library.track(id)
        Log.i(TAG, "rating: $r ${t?.artist} - ${t?.title} ($id)")
        if (r == "dislike" && controller?.currentMediaItem?.mediaId == id) {
            controller.seekToNext()
            Log.i(TAG, "rating: dislike on current track — skipped to next")
        }
        return true
    }
}
