package com.apexforge.genesisplayer.data

import android.content.Context
import org.json.JSONObject

data class Track(
    val id: String,
    val artist: String,
    val title: String,
    val streamUrl: String,
    val artworkUrl: String,
    val durationS: Long,
    /** Genre from the remote catalog (empty when unknown). Drives taste learning + queue boosts. */
    val genre: String = "",
    /**
     * SoundCloud permalink (e.g. https://soundcloud.com/tha-aadity/phoenix).
     * When non-empty, [streamUrl] is ignored and the playable URL is resolved
     * at play time via [SoundCloudResolver] — never catalogued, never stored.
     */
    val soundcloudUrl: String = "",
    /** Mood tag from the remote catalog (empty when unknown). Feeds [EnergyRules]. */
    val mood: String = ""
)

data class Playlist(val name: String, val trackIds: List<String>)

data class ForYouItem(
    val id: String,
    val artist: String,
    val title: String,
    val streamUrl: String,
    val artworkUrl: String,
    val why: String
)

object Library {
    lateinit var tracks: List<Track>
        private set
    lateinit var playlists: List<Playlist>
        private set
    lateinit var forYou: List<ForYouItem>
        private set

    private val byId = mutableMapOf<String, Track>()

    /** Bundled catalog from assets. Safe to call repeatedly. */
    fun load(context: Context) {
        if (::tracks.isInitialized) return
        val json = context.assets.open("tracks.json").bufferedReader().use { it.readText() }
        applyJson(JSONObject(json))
    }

    /**
     * Parse a catalog JSON root into the active library. Internal (not
     * private) so the API-34 instrumented tests can prove genre flows
     * through — the bundled genre-drop (AUDIT §2.1 defect 3) is pinned here.
     */
    internal fun applyJson(root: JSONObject) {
        val parsed = root.getJSONArray("tracks").let { arr ->
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                Track(
                    id = o.getString("id"),
                    artist = o.getString("artist"),
                    title = o.getString("title"),
                    streamUrl = o.getString("stream_url"),
                    artworkUrl = o.optString("artwork_url", ""),
                    durationS = o.optLong("duration_s", 0),
                    genre = o.optString("genre", ""),
                    soundcloudUrl = o.optString("soundcloud_url", ""),
                    mood = o.optString("mood", "")
                )
            }
        }
        val ids = parsed.associateBy { it.id }
        val parsedPl = root.getJSONArray("playlists").let { arr ->
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                val plIds = o.getJSONArray("track_ids")
                // keep only tracks that actually exist in the native library
                val valid = List(plIds.length()) { j -> plIds.getString(j) }.filter { ids.containsKey(it) }
                Playlist(o.getString("name"), valid)
            }.filter { it.trackIds.isNotEmpty() }
        }
        applyRemote(parsed, parsedPl)
        if (!::forYou.isInitialized) {
            forYou = root.getJSONArray("for_you").let { arr ->
                List(arr.length()) { i ->
                    val o = arr.getJSONObject(i)
                    ForYouItem(
                        id = o.getString("id"),
                        artist = o.getString("artist"),
                        title = o.getString("title"),
                        streamUrl = o.getString("stream_url"),
                        artworkUrl = o.optString("artwork_url", ""),
                        why = o.getString("why")
                    )
                }
            }
        }
    }

    /**
     * Replace the active track library with a remotely fetched one.
     * For You stays bundled (the remote catalog carries no for-you picks).
     * Synchronized: PlayerService may be resolving ids on another thread.
     */
    @Synchronized
    fun applyRemote(newTracks: List<Track>, newPlaylists: List<Playlist>) {
        tracks = newTracks
        playlists = newPlaylists
        byId.clear()
        newTracks.forEach { byId[it.id] = it }
    }

    fun track(id: String): Track? = byId[id]
}
