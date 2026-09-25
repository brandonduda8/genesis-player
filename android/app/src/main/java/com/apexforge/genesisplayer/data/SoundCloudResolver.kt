package com.apexforge.genesisplayer.data

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.net.URLEncoder

/**
 * SoundCloud playback adapter — the official runtime path, stream-only.
 *
 * Resolution chain (verified live 2026-09-25):
 *  1. GET https://api-v2.soundcloud.com/resolve?url=<permalink>&client_id=<id>
 *     -> track JSON including media.transcodings
 *  2. pick the "progressive" transcoding (ExoPlayer plays it natively as MP3)
 *  3. GET <transcoding.url>?client_id=<id> -> {"url": "<signed cf-media.sndcdn.com URL>"}
 *  4. hand the signed URL to ExoPlayer.
 *
 * Signed URLs expire within minutes and are NEVER stored anywhere — not in
 * the catalog, not on disk, not in prefs. Resolution happens fresh at every
 * play, so there is nothing stale to catalogue.
 *
 * HARD RULE: stream only. This adapter never writes audio to disk; ExoPlayer
 * streams the signed URL directly.
 *
 * client_id: SoundCloud's public web client id, baked into their own web
 * player bundle (a-v2.sndcdn.com) — the same key soundcloud.com itself uses.
 * FALLBACK_CLIENT_ID was scraped from that bundle on 2026-09-25. If
 * SoundCloud rotates it, the resolver re-scrapes a fresh one on a 401 and
 * caches it in SharedPreferences, then retries once.
 */
object SoundCloudResolver {
    private const val TAG = "GenesisPlayer"
    private const val PREF_KEY = "sc_client_id"
    private const val API = "https://api-v2.soundcloud.com"

    /** Scraped from SoundCloud's own web player bundle (a-v2.sndcdn.com) 2026-09-25. */
    private const val FALLBACK_CLIENT_ID = "3uJIGBRwdofKn6QKzONvDxUM1Vs4bTv9"

    data class Resolved(val streamUrl: String)

    /**
     * Resolve a SoundCloud permalink to a playable signed stream URL.
     * BLOCKING — must be called off the main thread.
     * Returns null when the track cannot be resolved (not streamable, bad
     * key, network down). Callers should skip the track, never fake it.
     */
    fun resolve(context: Context, permalinkUrl: String): Resolved? {
        val app = context.applicationContext
        var cid = prefs(app).getString(PREF_KEY, null) ?: FALLBACK_CLIENT_ID
        var track = fetchTrack(permalinkUrl, cid)
        if (track == null) {
            // Key may have rotated — scrape a fresh one from SoundCloud's own
            // web bundle and retry exactly once.
            val fresh = scrapeClientId()
            if (fresh != null && fresh != cid) {
                Log.i(TAG, "SoundCloudResolver: refreshed client_id, retrying")
                prefs(app).edit().putString(PREF_KEY, fresh).apply()
                cid = fresh
                track = fetchTrack(permalinkUrl, cid)
            }
        }
        val t = track ?: run {
            Log.w(TAG, "SoundCloudResolver: resolve failed for $permalinkUrl")
            return null
        }
        val transUrl = progressiveTranscoding(t) ?: run {
            Log.w(TAG, "SoundCloudResolver: no progressive transcoding for $permalinkUrl")
            return null
        }
        return try {
            val signed = JSONObject(fetchJson("$transUrl?client_id=$cid")).optString("url", "")
            if (signed.isEmpty()) {
                Log.w(TAG, "SoundCloudResolver: empty signed URL for $permalinkUrl")
                null
            } else {
                Resolved(signed)
            }
        } catch (e: Exception) {
            Log.w(TAG, "SoundCloudResolver: signed-url fetch failed (${e.message})")
            null
        }
    }

    private fun fetchTrack(permalinkUrl: String, clientId: String): JSONObject? {
        return try {
            val enc = URLEncoder.encode(permalinkUrl, "UTF-8")
            val o = JSONObject(fetchJson("$API/resolve?url=$enc&client_id=$clientId"))
            if (o.optBoolean("streamable", false)) o else null
        } catch (e: Exception) {
            Log.w(TAG, "SoundCloudResolver: track fetch failed (${e.message})")
            null
        }
    }

    private fun progressiveTranscoding(track: JSONObject): String? {
        val arr = track.optJSONObject("media")?.optJSONArray("transcodings") ?: return null
        for (i in 0 until arr.length()) {
            val t = arr.getJSONObject(i)
            if (t.optJSONObject("format")?.optString("protocol") == "progressive") {
                return t.optString("url", "").ifEmpty { null }
            }
        }
        return null
    }

    /**
     * Scrape the public web client_id out of SoundCloud's own player bundle.
     * Best-effort fallback path; the bundled key is expected to just work.
     */
    private fun scrapeClientId(): String? {
        return try {
            val page = fetchJson("https://soundcloud.com/tha-aadity/phoenix")
            val bundleRe = Regex("https://a-v2\\.sndcdn\\.com/assets/[^\"]+\\.js")
            val idRe = Regex("client_id:\"([A-Za-z0-9]{32})\"")
            for (m in bundleRe.findAll(page)) {
                val js = try { fetchJson(m.value) } catch (e: Exception) { continue }
                val hit = idRe.find(js) ?: continue
                val id = hit.groupValues[1]
                // skip Google OAuth ids etc. — the SC web key is 32 alphanumerics
                if (!id.contains("apps.googleusercontent")) return id
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "SoundCloudResolver: client_id scrape failed (${e.message})")
            null
        }
    }
}
