package com.apexforge.genesisplayer.data.mymusic

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.time.Instant
import java.util.zip.ZipInputStream

/** One play of one YouTube video, exactly as the owner's Google Takeout recorded it. */
data class PlayEvent(
    val videoId: String,
    val rawTitle: String,
    val rawArtist: String,
    val timeMs: Long,
    val viaMusicApp: Boolean
)

data class SearchEvent(val query: String, val timeMs: Long, val inMusicApp: Boolean)

data class ImportedPlaylist(val name: String, val videoIds: List<String>)

/** What was read, what was kept and why anything was skipped. Shown to the owner after every import. */
data class ImportAudit(
    val files: List<String>,
    val watchEntries: Int,
    val musicAppEntries: Int,
    val keptPlays: Int,
    val keptViaMusicApp: Int,
    val skipped: Map<String, Int>,
    val searches: Int,
    val musicSearches: Int,
    val playlists: Int,
    val firstMs: Long,
    val lastMs: Long
)

data class ImportResult(
    val events: List<PlayEvent>,
    val searches: List<SearchEvent>,
    val playlists: List<ImportedPlaylist>,
    val audit: ImportAudit
)

/**
 * Google Takeout reader: "YouTube and YouTube Music" history (JSON) and playlists (CSV), from the .zip itself or
 * from a single extracted JSON file. Rules (identical to the desktop audit, so the numbers match):
 *  - ads ("From Google Ads") are skipped;
 *  - entries without a video id (visits, removed items) are skipped;
 *  - entries without a channel (deleted or private videos) are skipped;
 *  - a play is MUSIC when it came from the YouTube Music app, or its channel is an auto-generated "- Topic" or VEVO
 *    channel, or the same video was also played in YouTube Music. Everything else is regular YouTube and skipped.
 * Nothing is invented: every kept play is a line of the owner's own history.
 */
object Takeout {
    private val VID = Regex("[?&]v=([A-Za-z0-9_-]{11})")

    fun videoId(url: String?): String? = url?.let { VID.find(it)?.groupValues?.get(1) }

    fun parseTime(s: String): Long = try { Instant.parse(s).toEpochMilli() } catch (e: Exception) { 0L }

    fun parseWatch(json: String): Pair<List<PlayEvent>, Map<String, Int>> {
        val arr = JSONArray(json)
        val skipped = linkedMapOf<String, Int>()
        fun skip(reason: String) { skipped[reason] = (skipped[reason] ?: 0) + 1 }
        val musicIds = HashSet<String>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.optString("header") == "YouTube Music") videoId(o.optString("titleUrl"))?.let { musicIds.add(it) }
        }
        val out = ArrayList<PlayEvent>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (isAd(o)) { skip("ad"); continue }
            val vid = videoId(o.optString("titleUrl")) ?: run { skip("no video id (visit or removed)"); null } ?: continue
            val channel = o.optJSONArray("subtitles")?.optJSONObject(0)?.optString("name").orEmpty()
            if (channel.isEmpty()) { skip("no channel (deleted or private video)"); continue }
            val viaMusic = o.optString("header") == "YouTube Music"
            val music = viaMusic || channel.endsWith(" - Topic") || channel.contains("VEVO") || vid in musicIds
            if (!music) { skip("regular YouTube, not music"); continue }
            out.add(PlayEvent(vid, o.optString("title").removePrefix("Watched "), channel, parseTime(o.optString("time")),
                viaMusic))
        }
        return out to skipped
    }

    private fun isAd(o: JSONObject): Boolean {
        val d = o.optJSONArray("details") ?: return false
        for (i in 0 until d.length()) if (d.optJSONObject(i)?.optString("name") == "From Google Ads") return true
        return false
    }

    fun parseSearch(json: String): List<SearchEvent> {
        val arr = JSONArray(json)
        val out = ArrayList<SearchEvent>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val t = o.optString("title")
            if (!t.startsWith("Searched for ")) continue
            out.add(SearchEvent(t.removePrefix("Searched for ").trim(), parseTime(o.optString("time")),
                o.optString("header") == "YouTube Music"))
        }
        return out
    }

    /** Takeout playlist CSV: a header row containing "Video ID" (case varies), then one video per row. */
    fun parsePlaylistCsv(name: String, csv: String): ImportedPlaylist? {
        val lines = csv.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        val headerAt = lines.indexOfFirst { it.lowercase().startsWith("video id") }
        if (headerAt < 0) return null
        val ids = lines.drop(headerAt + 1).mapNotNull { row ->
            row.substringBefore(',').trim().takeIf { it.length == 11 && it.all { c -> c.isLetterOrDigit() || c == '-' || c == '_' } }
        }
        val clean = name.substringAfterLast('/').removeSuffix(".csv").removeSuffix("-videos").removeSuffix(" videos").trim()
        return if (ids.isEmpty()) null else ImportedPlaylist(clean, ids)
    }

    /** Accepts the Takeout .zip or a single watch-history.json / search-history.json. */
    fun read(fileName: String, input: InputStream): ImportResult {
        val buffered = input.buffered()
        buffered.mark(4)
        val magic = ByteArray(2).also { buffered.read(it) }
        buffered.reset()
        val texts = LinkedHashMap<String, String>()
        if (magic[0] == 'P'.code.toByte() && magic[1] == 'K'.code.toByte()) {
            ZipInputStream(buffered).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    val n = e.name
                    if (!e.isDirectory && (n.endsWith("watch-history.json") || n.endsWith("search-history.json") ||
                            (n.contains("/playlists/") && n.endsWith(".csv")))) {
                        texts[n] = readAll(zip)
                    }
                }
            }
        } else {
            texts[fileName] = readAll(buffered)
        }
        return assemble(texts)
    }

    private fun readAll(s: InputStream): String {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (true) { val n = s.read(buf); if (n < 0) break; out.write(buf, 0, n) }
        return out.toString(Charsets.UTF_8.name())
    }

    fun assemble(texts: Map<String, String>): ImportResult {
        val events = ArrayList<PlayEvent>()
        val searches = ArrayList<SearchEvent>()
        val playlists = ArrayList<ImportedPlaylist>()
        val skipped = linkedMapOf<String, Int>()
        var watchEntries = 0
        var musicEntries = 0
        for ((name, text) in texts) {
            val trimmed = text.trimStart()
            when {
                name.endsWith(".csv") -> parsePlaylistCsv(name, text)?.let { playlists.add(it) }
                name.contains("search-history") || (trimmed.startsWith("[") && trimmed.contains("\"Searched for ")) &&
                    !trimmed.contains("\"Watched ") -> searches.addAll(parseSearch(text))
                else -> {
                    val arr = JSONArray(text)
                    watchEntries += arr.length()
                    for (i in 0 until arr.length()) if (arr.getJSONObject(i).optString("header") == "YouTube Music") musicEntries++
                    val (ev, sk) = parseWatch(text)
                    events.addAll(ev)
                    sk.forEach { (k, v) -> skipped[k] = (skipped[k] ?: 0) + v }
                }
            }
        }
        events.sortBy { it.timeMs }
        val times = events.map { it.timeMs }.filter { it > 0 }
        return ImportResult(events, searches, playlists, ImportAudit(
            files = texts.keys.toList(), watchEntries = watchEntries, musicAppEntries = musicEntries,
            keptPlays = events.size, keptViaMusicApp = events.count { it.viaMusicApp }, skipped = skipped,
            searches = searches.size, musicSearches = searches.count { it.inMusicApp }, playlists = playlists.size,
            firstMs = times.minOrNull() ?: 0L, lastMs = times.maxOrNull() ?: 0L))
    }
}

/** Display + identity rules: one artist across its Topic, VEVO and plain channels; titles without video dressing. */
object Normalize {
    private val TOPIC = Regex(" - Topic$")
    /** "Artist - Song", "Artist- Song", "Artist – Song", "Artist: Song". */
    private val PREFIX = Regex("^(.+?)\\s*[-\u2013\u2014:]\\s*(.+)$")
    private val VEVO = Regex("(?i)vevo$")
    private val DRESSING = Regex(
        "\\s*[(\\[][^)\\]]*(?i:official|lyric|lyrics|audio|visuali[sz]er|video|explicit|hd|4k|remaster(ed)?)[^)\\]]*[)\\]]")

    fun artistKey(raw: String): String =
        VEVO.replace(TOPIC.replace(raw.trim(), ""), "").lowercase().filter { it.isLetterOrDigit() }

    /** Readable artist name from a channel name: drops " - Topic" and a trailing VEVO. */
    fun artistDisplay(raw: String): String = VEVO.replace(TOPIC.replace(raw.trim(), ""), "").trim()

    fun cleanTitle(rawTitle: String, artistDisplay: String): String {
        var t = rawTitle.removePrefix("Watched ").trim()
        val prefix = PREFIX.find(t)
        if (prefix != null && artistKey(prefix.groupValues[1]) == artistKey(artistDisplay)) t = prefix.groupValues[2]
        t = DRESSING.replace(t, "").replace("\"", "").replace(Regex("\\s+"), " ").trim()
        return t.ifEmpty { rawTitle.trim() }
    }

    fun alnum(s: String): String = s.lowercase().filter { it.isLetterOrDigit() }

    fun songKey(artistKey: String, cleanTitle: String): String =
        artistKey + "|" + cleanTitle.lowercase().filter { it.isLetterOrDigit() }
}
