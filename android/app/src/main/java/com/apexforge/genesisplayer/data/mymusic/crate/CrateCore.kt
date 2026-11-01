package com.apexforge.genesisplayer.data.mymusic.crate

import com.apexforge.genesisplayer.data.mymusic.Normalize
import org.json.JSONArray
import org.json.JSONObject

/**
 * Offline Crate core (pure): what a legitimate source holds, and how it relates to a song in the owner's history.
 * Ground rule: a candidate is only ever matched to a song by THE SAME ARTIST. Nothing by anyone else is ever offered
 * in a song's place (no replacement music).
 */
enum class Source { AUDIUS, ARCHIVE }

data class Candidate(
    val source: Source,
    val id: String,
    val title: String,
    val artistName: String,
    /** Audius: the account is verified. Archive: always false (identity comes from the licence + creator field). */
    val verified: Boolean,
    val streamUrl: String,
    /** Non-empty only when the rights holder permits download (artist-enabled on Audius, CC/PD licence on Archive). */
    val downloadUrl: String,
    val licence: String,
    val artworkUrl: String = "",
    val durationS: Long = 0
)

enum class MatchKind { EXACT, ALTERNATE, UNAVAILABLE }

data class CrateMatch(
    val songKey: String,
    val kind: MatchKind,
    val candidate: Candidate?,
    /** Plain words: "Official Audius account", "Remix (not the version you play)", "Same name, unverified account". */
    val note: String
) {
    /** Download is offered only with permission AND a trustworthy identity. */
    val downloadable: Boolean
        get() = candidate != null && candidate.downloadUrl.isNotEmpty() &&
            (candidate.source == Source.ARCHIVE || candidate.verified)
}

object CrateMatcher {
    private val FEAT = Regex("(?i)\\s*[(\\[]?\\s*(feat\\.?|ft\\.?|featuring|with)\\s+[^)\\]]*[)\\]]?")
    private val VERSION_WORDS = listOf("slowed", "reverb", "sped up", "spedup", "nightcore", "remix", "live", "acoustic",
        "instrumental", "radio edit", "extended", "demo", "remaster", "remastered", "version", "edit", "mix", "rework",
        "bass boosted", "8d", "chopped", "screwed")

    /** The version words a title carries, e.g. {"slowed","reverb"}. */
    fun versionTags(title: String): Set<String> {
        val t = title.lowercase()
        return VERSION_WORDS.filter { w -> Regex("(^|[^a-z])${Regex.escape(w)}([^a-z]|$)").containsMatchIn(t) }.toSet()
    }

    /** The recording's core title: no featured credits, no version words, no punctuation. */
    fun core(title: String, artist: String): String {
        var t = Normalize.cleanTitle(title, artist)
        t = FEAT.replace(t, " ")
        val lower = t.lowercase()
        var out = lower
        VERSION_WORDS.sortedByDescending { it.length }.forEach { w -> out = out.replace(Regex("(^|[^a-z])${Regex.escape(w)}([^a-z]|$)"), " ") }
        return Normalize.alnum(out)
    }

    fun sameArtist(a: String, b: String): Boolean {
        val x = Normalize.artistKey(a)
        val y = Normalize.artistKey(b)
        return x.isNotEmpty() && x == y
    }

    /**
     * Classify one of the owner's songs against the candidates the sources returned for ITS artist.
     * Order: exact on a verified account > exact elsewhere > alternate version by the same artist > unavailable.
     */
    fun classify(songKey: String, songTitle: String, songArtist: String, candidates: List<Candidate>): CrateMatch {
        val mine = core(songTitle, songArtist)
        val myTags = versionTags(songTitle)
        val sameArtist = candidates.filter { sameArtist(it.artistName, songArtist) }
        if (mine.isEmpty() || sameArtist.isEmpty()) return CrateMatch(songKey, MatchKind.UNAVAILABLE, null, "Not found on Audius or the Internet Archive")
        val sameCore = sameArtist.filter { core(it.title, it.artistName) == mine }
        val exact = sameCore.filter { versionTags(it.title) == myTags }
            .sortedWith(compareByDescending<Candidate> { it.verified }.thenByDescending { it.downloadUrl.isNotEmpty() }
                .thenBy { it.source.ordinal })
        exact.firstOrNull()?.let { c ->
            val note = when {
                c.source == Source.ARCHIVE -> "Internet Archive, ${c.licence}"
                c.verified -> "Official Audius account"
                else -> "Same name on Audius, unverified account: stream only"
            }
            return CrateMatch(songKey, MatchKind.EXACT, c, note)
        }
        sameCore.sortedWith(compareByDescending<Candidate> { it.verified }.thenBy { versionTags(it.title).size })
            .firstOrNull()?.let { c ->
                val theirs = versionTags(c.title)
                val diff = (theirs - myTags).ifEmpty { myTags - theirs }
                val label = if (diff.isEmpty()) "a different version" else diff.joinToString(" + ")
                return CrateMatch(songKey, MatchKind.ALTERNATE, c,
                    "Alternate version: ${label.replaceFirstChar { it.uppercase() }} (not the recording you play)" +
                        if (!c.verified && c.source == Source.AUDIUS) ", unverified account" else "")
            }
        return CrateMatch(songKey, MatchKind.UNAVAILABLE, null, "${songArtist} is there, this song isn't")
    }

    // ---------------- source parsers (defensive: fields vary between API versions) ----------------
    data class AudiusUser(val id: String, val name: String, val handle: String, val verified: Boolean, val followers: Int)

    fun parseAudiusUsers(json: String): List<AudiusUser> {
        val data = JSONObject(json).optJSONArray("data") ?: return emptyList()
        return (0 until data.length()).mapNotNull { i ->
            val u = data.optJSONObject(i) ?: return@mapNotNull null
            AudiusUser(u.optString("id"), u.optString("name"), u.optString("handle"),
                u.optBoolean("is_verified", false), u.optInt("follower_count", 0)).takeIf { it.id.isNotEmpty() }
        }
    }

    /** Pick the account that IS this artist: exact name or handle match; verified first, then followers. */
    fun pickArtistAccount(artist: String, users: List<AudiusUser>): AudiusUser? =
        users.filter { sameArtist(it.name, artist) || sameArtist(it.handle, artist) }
            .sortedWith(compareByDescending<AudiusUser> { it.verified }.thenByDescending { it.followers }).firstOrNull()

    fun parseAudiusTracks(json: String, base: String, app: String, account: AudiusUser?): List<Candidate> {
        val data = JSONObject(json).optJSONArray("data") ?: return emptyList()
        return (0 until data.length()).mapNotNull { i ->
            val t = data.optJSONObject(i) ?: return@mapNotNull null
            val id = t.optString("id").ifEmpty { return@mapNotNull null }
            val user = t.optJSONObject("user")
            val streamable = t.optBoolean("is_streamable", true) && !t.optBoolean("is_stream_gated", false) &&
                !t.optBoolean("is_delete", false)
            if (!streamable) return@mapNotNull null
            val dl = (t.optBoolean("is_downloadable", false) || t.optBoolean("downloadable", false) ||
                t.optJSONObject("download")?.optBoolean("is_downloadable", false) == true) &&
                !t.optBoolean("is_download_gated", false)
            val art = t.optJSONObject("artwork")?.let { a -> a.optString("480x480").ifEmpty { a.optString("150x150") } }.orEmpty()
            Candidate(Source.AUDIUS, id, t.optString("title"), user?.optString("name") ?: account?.name.orEmpty(),
                user?.optBoolean("is_verified", account?.verified ?: false) ?: (account?.verified ?: false),
                "$base/v1/tracks/$id/stream?app_name=$app",
                if (dl) "$base/v1/tracks/$id/download?app_name=$app" else "",
                if (dl) "artist-enabled download" else "stream only", art, t.optLong("duration", 0))
        }
    }

    /** Archive search docs: identifier, title, creator, licenseurl. Only explicit CC / public-domain items survive. */
    data class ArchiveItem(val identifier: String, val creator: String, val licence: String)

    fun permittedLicence(url: String): Boolean {
        val u = url.lowercase()
        return u.contains("creativecommons.org/licenses/") || u.contains("creativecommons.org/publicdomain/")
    }

    fun parseArchiveSearch(json: String): List<ArchiveItem> {
        val docs = JSONObject(json).optJSONObject("response")?.optJSONArray("docs") ?: return emptyList()
        return (0 until docs.length()).mapNotNull { i ->
            val d = docs.optJSONObject(i) ?: return@mapNotNull null
            val lic = d.optString("licenseurl")
            val creator = d.opt("creator")?.let { c -> if (c is JSONArray) c.optString(0) else c.toString() }.orEmpty()
            if (!permittedLicence(lic)) null else ArchiveItem(d.optString("identifier"), creator, lic)
        }
    }

    /** One candidate per audio file (MP3 derivatives preferred), titled from the file's own metadata. */
    fun parseArchiveFiles(json: String, item: ArchiveItem): List<Candidate> {
        val o = JSONObject(json)
        val files = o.optJSONArray("files") ?: return emptyList()
        val byTitle = LinkedHashMap<String, JSONObject>()
        for (i in 0 until files.length()) {
            val f = files.optJSONObject(i) ?: continue
            val name = f.optString("name")
            val fmt = f.optString("format").lowercase()
            if (!(fmt.contains("mp3") || name.endsWith(".mp3", true))) continue
            val title = f.optString("title").ifEmpty { name.substringBeforeLast('.').substringAfterLast('/') }
            val prev = byTitle[title]
            if (prev == null || fmt.contains("vbr")) byTitle[title] = f
        }
        val creator = o.optJSONObject("metadata")?.opt("creator")?.let { c -> if (c is JSONArray) c.optString(0) else c.toString() }
            ?: item.creator
        return byTitle.map { (title, f) ->
            val url = "https://archive.org/download/${item.identifier}/" + f.optString("name").split('/').joinToString("/") {
                java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
            Candidate(Source.ARCHIVE, "${item.identifier}/${f.optString("name")}", title, creator, false, url, url,
                "licence " + item.licence.removePrefix("https://").removePrefix("http://"),
                durationS = f.optString("length").toDoubleOrNull()?.toLong() ?: 0)
        }
    }

    // ---------------- persistence ----------------
    fun encode(matches: List<CrateMatch>, checkedAtMs: Long): String = JSONObject().put("v", 1).put("checkedAt", checkedAtMs)
        .put("matches", JSONArray().also { a ->
            matches.forEach { m ->
                val c = m.candidate
                a.put(JSONObject().put("song", m.songKey).put("kind", m.kind.name).put("note", m.note).apply {
                    if (c != null) put("c", JSONObject().put("src", c.source.name).put("id", c.id).put("title", c.title)
                        .put("artist", c.artistName).put("verified", c.verified).put("stream", c.streamUrl)
                        .put("download", c.downloadUrl).put("licence", c.licence).put("art", c.artworkUrl).put("dur", c.durationS))
                })
            }
        }).toString()

    fun decode(text: String): Pair<List<CrateMatch>, Long> {
        val o = JSONObject(text)
        val a = o.getJSONArray("matches")
        return List(a.length()) { i ->
            val m = a.getJSONObject(i)
            val c = m.optJSONObject("c")?.let { c ->
                Candidate(Source.valueOf(c.getString("src")), c.getString("id"), c.getString("title"), c.getString("artist"),
                    c.getBoolean("verified"), c.getString("stream"), c.getString("download"), c.getString("licence"),
                    c.optString("art"), c.optLong("dur"))
            }
            CrateMatch(m.getString("song"), MatchKind.valueOf(m.getString("kind")), c, m.getString("note"))
        } to o.optLong("checkedAt")
    }
}
