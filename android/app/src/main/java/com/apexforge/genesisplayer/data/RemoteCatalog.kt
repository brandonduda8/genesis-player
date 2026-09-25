package com.apexforge.genesisplayer.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "GenesisPlayer"
private const val TIMEOUT_MS = 15000

/** Shared helpers for the remote catalog/config fetch. Never blocks the caller. */
internal fun prefs(context: Context) =
    context.getSharedPreferences("genesis_remote", Context.MODE_PRIVATE)

internal fun fetchJson(url: String): String {
    // cache-buster: raw.githubusercontent.com caches ~5min without it
    val sep = if (url.contains("?")) "&" else "?"
    val u = URL("$url${sep}t=${System.currentTimeMillis()}")
    val c = (u.openConnection() as HttpURLConnection).apply {
        connectTimeout = TIMEOUT_MS
        readTimeout = TIMEOUT_MS
        setRequestProperty("User-Agent", "GenesisPlayer/1.0")
    }
    try {
        if (c.responseCode != 200) throw RuntimeException("HTTP ${c.responseCode}")
        return c.inputStream.bufferedReader().use { it.readText() }
    } finally {
        c.disconnect()
    }
}

internal fun runBackground(block: () -> Unit) {
    Thread(block).apply { isDaemon = true }.start()
}

internal fun postMain(block: () -> Unit) {
    Handler(Looper.getMainLooper()).post(block)
}

/**
 * Remote music catalog: https://raw.githubusercontent.com/brandonduda8/genesis-catalog/master/catalog.json
 *
 * - Fetched on launch on a background thread; NEVER blocks launch.
 * - Applied only when remote version > active (bundled/cached) version.
 * - Applied catalog is persisted to internal storage and re-applied on next launch.
 * - "Updated" is only ever claimed when a version actually advanced.
 * - Streams stay progressive HTTP; nothing is downloaded to disk.
 */
object RemoteCatalog {
    const val CATALOG_URL =
        "https://raw.githubusercontent.com/brandonduda8/genesis-catalog/master/catalog.json"
    const val BUNDLED_VERSION = 0

    /** Subtle UI note; set ONLY when a version actually advanced. Observed by LibraryScreen. */
    val note = mutableStateOf<String?>(null)

    fun activeVersion(context: Context): Int =
        prefs(context).getInt("catalog_version", BUNDLED_VERSION)

    fun checkForUpdates(context: Context) {
        val app = context.applicationContext
        runBackground {
            try {
                val root = JSONObject(fetchJson(CATALOG_URL))
                val v = root.optInt("version", 0)
                val cur = activeVersion(app)
                if (v > cur) {
                    val (tracks, playlists) = translate(root)
                    Library.applyRemote(tracks, playlists)
                    app.openFileOutput("remote_catalog.json", Context.MODE_PRIVATE).use {
                        it.write(root.toString().toByteArray())
                    }
                    prefs(app).edit().putInt("catalog_version", v).apply()
                    val msg = "Catalog updated — ${tracks.size} tracks (v$v)"
                    postMain { note.value = msg }
                    Log.i(TAG, "RemoteCatalog: applied version $v (${tracks.size} tracks, ${playlists.size} playlists)")
                } else {
                    Log.i(TAG, "RemoteCatalog: up to date (remote v$v, active v$cur)")
                }
            } catch (e: Exception) {
                Log.i(TAG, "RemoteCatalog: fetch failed (${e.message}); using bundled/cached")
            }
        }
    }

    /**
     * Translate the remote schema into the app's Library model.
     * Remote tracks that match a bundled stream URL keep their bundled id,
     * artwork, duration, and playlist membership — so playback queues and
     * the curated playlists survive the swap.
     */
    internal fun translate(root: JSONObject): Pair<List<Track>, List<Playlist>> {
        val bundledPl = mutableMapOf<String, MutableList<String>>()
        Library.playlists.forEach { pl ->
            pl.trackIds.forEach { bundledPl.getOrPut(it) { mutableListOf() }.add(pl.name) }
        }
        val bundledByUrl = Library.tracks.associateBy { it.streamUrl }

        val idRe = Regex("""/tracks/([^/?]+)/stream""")
        val paired = mutableListOf<Pair<Track, List<String>>>()
        val arr = root.optJSONArray("tracks") ?: return emptyList<Track>() to emptyList()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val url = o.optString("audius_stream_url", "")
            val remoteId = idRe.find(url)?.groupValues?.get(1) ?: continue
            val bundled = bundledByUrl[url]
            val plName = o.optString("playlist", "")
            val names = when {
                plName.isNotEmpty() -> listOf(plName)
                bundled != null -> bundledPl[bundled.id].orEmpty().ifEmpty { listOf("Radar") }
                else -> listOf("Radar")
            }
            paired.add(
                Track(
                    id = bundled?.id ?: remoteId,
                    artist = o.optString("artist", "Unknown"),
                    title = o.optString("title", "Untitled"),
                    streamUrl = url,
                    artworkUrl = bundled?.artworkUrl ?: "",
                    durationS = bundled?.durationS ?: 0
                ) to names
            )
        }
        val grouped = linkedMapOf<String, MutableList<String>>()
        paired.forEach { (t, names) ->
            names.forEach { name -> grouped.getOrPut(name) { mutableListOf() }.add(t.id) }
        }
        return paired.map { it.first } to grouped.map { (name, ids) -> Playlist(name, ids) }
    }

    /** Re-apply the persisted cache on launch (before any network completes). */
    fun applyCache(context: Context) {
        val v = prefs(context).getInt("catalog_version", BUNDLED_VERSION)
        if (v <= BUNDLED_VERSION) return
        try {
            val json = context.openFileInput("remote_catalog.json").bufferedReader().use { it.readText() }
            val (tracks, playlists) = translate(JSONObject(json))
            Library.applyRemote(tracks, playlists)
            Log.i(TAG, "RemoteCatalog: applied cached version $v (${tracks.size} tracks)")
        } catch (e: Exception) {
            Log.i(TAG, "RemoteCatalog: cache unreadable (${e.message}); using bundled")
            prefs(context).edit().remove("catalog_version").apply()
        }
    }
}
