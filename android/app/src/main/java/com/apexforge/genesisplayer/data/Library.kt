package com.apexforge.genesisplayer.data

import android.content.Context
import org.json.JSONObject

data class Track(
    val id: String,
    val artist: String,
    val title: String,
    val streamUrl: String,
    val artworkUrl: String,
    val durationS: Long
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

    fun load(context: Context) {
        if (::tracks.isInitialized) return
        val json = context.assets.open("tracks.json").bufferedReader().use { it.readText() }
        val root = JSONObject(json)
        tracks = root.getJSONArray("tracks").let { arr ->
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                Track(
                    id = o.getString("id"),
                    artist = o.getString("artist"),
                    title = o.getString("title"),
                    streamUrl = o.getString("stream_url"),
                    artworkUrl = o.optString("artwork_url", ""),
                    durationS = o.optLong("duration_s", 0)
                )
            }
        }
        byId.clear()
        tracks.forEach { byId[it.id] = it }
        playlists = root.getJSONArray("playlists").let { arr ->
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                val ids = o.getJSONArray("track_ids")
                // keep only tracks that actually exist in the native library
                val valid = List(ids.length()) { j -> ids.getString(j) }.filter { byId.containsKey(it) }
                Playlist(o.getString("name"), valid)
            }.filter { it.trackIds.isNotEmpty() }
        }
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

    fun track(id: String): Track? = byId[id]
}
