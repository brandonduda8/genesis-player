package com.apexforge.genesisplayer.data.mymusic

import org.json.JSONArray
import org.json.JSONObject

/** Compact, exact serialisation of an import. Stored only in the app's private storage on the owner's phone. */
object MyMusicCodec {
    fun encode(r: ImportResult): String {
        val o = JSONObject()
        o.put("v", 1)
        o.put("events", JSONArray().also { a ->
            r.events.forEach { e -> a.put(JSONArray().put(e.videoId).put(e.rawTitle).put(e.rawArtist).put(e.timeMs).put(e.viaMusicApp)) }
        })
        o.put("searches", JSONArray().also { a ->
            r.searches.forEach { s -> a.put(JSONArray().put(s.query).put(s.timeMs).put(s.inMusicApp)) }
        })
        o.put("playlists", JSONArray().also { a ->
            r.playlists.forEach { p -> a.put(JSONObject().put("name", p.name).put("ids", JSONArray(p.videoIds))) }
        })
        val au = r.audit
        o.put("audit", JSONObject().put("files", JSONArray(au.files)).put("watchEntries", au.watchEntries)
            .put("musicAppEntries", au.musicAppEntries).put("keptPlays", au.keptPlays).put("keptViaMusicApp", au.keptViaMusicApp)
            .put("skipped", JSONObject(au.skipped as Map<*, *>)).put("searches", au.searches).put("musicSearches", au.musicSearches)
            .put("playlists", au.playlists).put("firstMs", au.firstMs).put("lastMs", au.lastMs))
        return o.toString()
    }

    fun decode(text: String): ImportResult {
        val o = JSONObject(text)
        val ev = o.getJSONArray("events").let { a ->
            List(a.length()) { i -> a.getJSONArray(i).let { e ->
                PlayEvent(e.getString(0), e.getString(1), e.getString(2), e.getLong(3), e.getBoolean(4)) } }
        }
        val se = o.getJSONArray("searches").let { a ->
            List(a.length()) { i -> a.getJSONArray(i).let { s -> SearchEvent(s.getString(0), s.getLong(1), s.getBoolean(2)) } }
        }
        val pl = o.getJSONArray("playlists").let { a ->
            List(a.length()) { i -> a.getJSONObject(i).let { p ->
                val ids = p.getJSONArray("ids")
                ImportedPlaylist(p.getString("name"), List(ids.length()) { j -> ids.getString(j) }) } }
        }
        val au = o.getJSONObject("audit")
        val sk = au.getJSONObject("skipped")
        val files = au.getJSONArray("files")
        val skipped = LinkedHashMap<String, Int>().also { m -> sk.keys().forEach { k -> m[k] = sk.getInt(k) } }
        return ImportResult(ev, se, pl, ImportAudit(List(files.length()) { files.getString(it) }, au.getInt("watchEntries"),
            au.getInt("musicAppEntries"), au.getInt("keptPlays"), au.getInt("keptViaMusicApp"), skipped,
            au.getInt("searches"), au.getInt("musicSearches"), au.getInt("playlists"), au.getLong("firstMs"), au.getLong("lastMs")))
    }

    fun encodeFeedback(f: Feedback): String = JSONObject()
        .put("likes", JSONArray(f.likes.toList())).put("dislikes", JSONArray(f.dislikes.toList()))
        .put("skips", JSONObject(f.skips as Map<*, *>)).put("completes", JSONObject(f.completes as Map<*, *>))
        .put("artistLikes", JSONArray(f.artistLikes.toList())).toString()

    fun decodeFeedback(text: String): Feedback {
        val o = JSONObject(text)
        fun set(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } ?: emptySet()
        fun map(k: String) = o.optJSONObject(k)?.let { m -> m.keys().asSequence().associateWith { m.getInt(it) } } ?: emptyMap()
        return Feedback(set("likes"), set("dislikes"), map("skips"), map("completes"), set("artistLikes"))
    }
}
