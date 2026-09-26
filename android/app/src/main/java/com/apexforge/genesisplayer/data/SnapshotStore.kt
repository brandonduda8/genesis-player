package com.apexforge.genesisplayer.data

import android.content.Context
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import org.json.JSONObject

private const val TAG = "GenesisPlayer"

/** One snapshot track (playlist member or favorite). Never fabricated — parsed only. */
data class SnapshotTrack(
    val id: String,
    val artist: String,
    val title: String,
    val streamUrl: String,
    val artworkUrl: String,
    val genre: String
)

data class SnapshotPlaylist(val name: String, val tracks: List<SnapshotTrack>)

data class SnapshotRecentPlay(
    val trackId: String,
    val artist: String,
    val title: String,
    val playedAt: String
)

data class SnapshotSuggestion(
    val id: String,
    val artist: String,
    val title: String,
    val streamUrl: String,
    val artworkUrl: String,
    val why: String
)

data class LibrarySnapshot(
    val version: Int,
    val playlists: List<SnapshotPlaylist>,
    val favorites: List<SnapshotTrack>,
    val recentPlays: List<SnapshotRecentPlay>,
    val apolloSuggestions: List<SnapshotSuggestion>
)

/**
 * BRKN Vibes wave 3: the machine-owned library snapshot.
 *
 * Fetched on launch and on Refresh from the tailnet machine via the same
 * scoped-trust [ApolloNet] path as everything else, cached to internal
 * storage, and served from cache when offline. No cache + no route = an
 * honest empty state — never a crash, never invented content.
 *
 * CI has no tailnet route: [fetch] must fail gracefully there and the app
 * must stay alive. The attempt line is logged synchronously on the caller
 * thread (same pattern as TasteSync) so the gate can prove the fetch was
 * attempted even when the network leg hangs.
 */
object SnapshotStore {
    const val SNAPSHOT_URL = "${ApolloNet.BASE}/brknvibes/library_snapshot.json"
    private const val CACHE_FILE = "library_snapshot.json"

    /** Live snapshot; null = none yet (honest empty state in the UI). */
    val state = mutableStateOf<LibrarySnapshot?>(null)

    fun current(): LibrarySnapshot? = state.value

    /** Fire-and-forget. Never throws. */
    fun fetch(context: Context, reason: String = "launch") {
        val app = context.applicationContext
        // Synchronous attempt line: proves the fetch FIRED even if the
        // network leg later hangs (no tailnet route in CI).
        Log.i(TAG, "SnapshotStore: fetch attempt ($reason)")
        Thread {
            try {
                val (code, payload) = ApolloNet.get(SNAPSHOT_URL, connectMs = 4000, readMs = 10000)
                if (code == 200 && !payload.isNullOrEmpty()) {
                    val snap = parseSnapshot(JSONObject(payload))
                    saveCache(app, payload)
                    state.value = snap
                    // "tracks" = playable tracks (playlists + favorites), the
                    // same definition loadCache uses below. Apollo
                    // suggestions are pending picks, not tracks.
                    val t = snap.playlists.sumOf { it.tracks.size } + snap.favorites.size
                    Log.i(
                        TAG,
                        "SnapshotStore: applied version ${snap.version} " +
                            "(${snap.playlists.size} playlists, $t tracks)"
                    )
                    return@Thread
                }
                Log.i(TAG, "SnapshotStore: fetch failed (HTTP $code); trying cache")
            } catch (t: Throwable) {
                Log.i(TAG, "SnapshotStore: fetch failed (${t.javaClass.simpleName}: ${t.message}); trying cache")
            }
            loadCache(app)
        }.apply { isDaemon = true; name = "snapshot-fetch" }.start()
    }

    /**
     * Parse the snapshot JSON. Coded EXACTLY to the contracted shape:
     * version (int), generated_at (ISO-8601), playlists (each with a name
     * and a tracks array of objects carrying id, artist, title,
     * stream_url, artwork_url, genre), favorites (track objects),
     * recent_plays (objects with track_id, artist, title, played_at),
     * apollo_suggestions (objects with id, artist, title, stream_url,
     * artwork_url, why).
     *
     * NOTE: written in prose on purpose — square-bracket array notation
     * here trips the KDoc parser ("Closing bracket expected").
     *
     * Tolerant readers (opt*): missing sections become empty lists, never a
     * crash. Public (not private) so the instrumented tests — a separate
     * compilation module, where Kotlin `internal` is invisible — pin the shape.
     */
    fun parseSnapshot(root: JSONObject): LibrarySnapshot {
        fun track(o: JSONObject) = SnapshotTrack(
            id = o.optString("id", ""),
            artist = o.optString("artist", "Unknown"),
            title = o.optString("title", "Untitled"),
            streamUrl = o.optString("stream_url", ""),
            artworkUrl = o.optString("artwork_url", ""),
            genre = o.optString("genre", "")
        )
        val playlists = mutableListOf<SnapshotPlaylist>()
        root.optJSONArray("playlists")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val tracks = mutableListOf<SnapshotTrack>()
                o.optJSONArray("tracks")?.let { tarr ->
                    for (j in 0 until tarr.length()) {
                        tarr.optJSONObject(j)?.let { tracks.add(track(it)) }
                    }
                }
                playlists.add(SnapshotPlaylist(o.optString("name", "Untitled"), tracks))
            }
        }
        val favorites = mutableListOf<SnapshotTrack>()
        root.optJSONArray("favorites")?.let { arr ->
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { favorites.add(track(it)) }
            }
        }
        val recent = mutableListOf<SnapshotRecentPlay>()
        root.optJSONArray("recent_plays")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                recent.add(
                    SnapshotRecentPlay(
                        trackId = o.optString("track_id", ""),
                        artist = o.optString("artist", "Unknown"),
                        title = o.optString("title", "Untitled"),
                        playedAt = o.optString("played_at", "")
                    )
                )
            }
        }
        val suggestions = mutableListOf<SnapshotSuggestion>()
        root.optJSONArray("apollo_suggestions")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                suggestions.add(
                    SnapshotSuggestion(
                        id = o.optString("id", ""),
                        artist = o.optString("artist", "Unknown"),
                        title = o.optString("title", "Untitled"),
                        streamUrl = o.optString("stream_url", ""),
                        artworkUrl = o.optString("artwork_url", ""),
                        why = o.optString("why", "")
                    )
                )
            }
        }
        return LibrarySnapshot(
            version = root.optInt("version", 0),
            playlists = playlists,
            favorites = favorites,
            recentPlays = recent,
            apolloSuggestions = suggestions
        )
    }

    private fun saveCache(app: Context, payload: String) {
        try {
            app.openFileOutput(CACHE_FILE, Context.MODE_PRIVATE).use {
                it.write(payload.toByteArray())
            }
        } catch (t: Throwable) {
            Log.w(TAG, "SnapshotStore: cache write failed (${t.message})")
        }
    }

    private fun loadCache(app: Context) {
        try {
            val payload = app.openFileInput(CACHE_FILE).bufferedReader().use { it.readText() }
            if (payload.isBlank()) {
                Log.i(TAG, "SnapshotStore: cache empty; snapshot unavailable (honest empty state)")
                return
            }
            val snap = parseSnapshot(JSONObject(payload))
            state.value = snap
            val t = snap.playlists.sumOf { it.tracks.size } + snap.favorites.size
            Log.i(
                TAG,
                "SnapshotStore: applied version ${snap.version} from cache " +
                    "(${snap.playlists.size} playlists, $t tracks)"
            )
        } catch (t: Throwable) {
            // No cache, or a corrupt one: honest empty state, never a crash.
            Log.i(TAG, "SnapshotStore: no usable cache (${t.javaClass.simpleName}); snapshot unavailable")
        }
    }
}
