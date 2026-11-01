package com.apexforge.genesisplayer.data.mymusic.crate

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Network for the Offline Crate: Audius (open API) and the Internet Archive. Plain java.net so the exact same code
 * runs on the phone and in the CI contract test. Requests carry only an artist name from the owner's library;
 * no history, no account, no identifiers.
 */
object CrateNet {
    const val AUDIUS = "https://api.audius.co"
    const val APP = "aurum_pulse"
    private const val UA = "AurumPulse/2.1 (personal music player)"

    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    fun get(url: String, timeoutMs: Int = 15_000): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = timeoutMs
        c.readTimeout = timeoutMs
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty("Accept", "application/json")
        try {
            if (c.responseCode !in 200..299) throw java.io.IOException("HTTP ${c.responseCode} for ${url.substringBefore('?')}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    fun audiusUsers(name: String): List<CrateMatcher.AudiusUser> =
        CrateMatcher.parseAudiusUsers(get("$AUDIUS/v1/users/search?query=${enc(name)}&app_name=$APP"))

    fun audiusTracksOf(user: CrateMatcher.AudiusUser, maxPages: Int = 3): List<Candidate> {
        val out = ArrayList<Candidate>()
        for (page in 0 until maxPages) {
            val batch = CrateMatcher.parseAudiusTracks(
                get("$AUDIUS/v1/users/${user.id}/tracks?limit=100&offset=${page * 100}&app_name=$APP"), AUDIUS, APP, user)
            out += batch.map { if (it.artistName.isEmpty()) it.copy(artistName = user.name) else it }
            if (batch.size < 100) break
        }
        return out
    }

    fun audiusTrending(): List<Candidate> = CrateMatcher.parseAudiusTracks(get("$AUDIUS/v1/tracks/trending?limit=5&app_name=$APP"), AUDIUS, APP, null)

    fun archiveSearch(artist: String, rows: Int = 20): List<CrateMatcher.ArchiveItem> {
        val q = "creator:(\"${artist.replace("\"", "")}\") AND mediatype:(audio) AND licenseurl:(*creativecommons*)"
        return CrateMatcher.parseArchiveSearch(get("https://archive.org/advancedsearch.php?q=${enc(q)}" +
            "&fl%5B%5D=identifier&fl%5B%5D=creator&fl%5B%5D=licenseurl&rows=$rows&output=json"))
    }

    fun archiveFiles(item: CrateMatcher.ArchiveItem): List<Candidate> =
        CrateMatcher.parseArchiveFiles(get("https://archive.org/metadata/${enc(item.identifier)}"), item)

    /** Stream a permitted download to [dest] atomically (".part" then rename). Returns bytes written. */
    fun download(url: String, dest: File): Long {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20_000
        c.readTimeout = 60_000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", UA)
        try {
            if (c.responseCode !in 200..299) throw java.io.IOException("HTTP ${c.responseCode}")
            val part = File(dest.parentFile, dest.name + ".part")
            dest.parentFile?.mkdirs()
            val n = c.inputStream.use { inp -> part.outputStream().use { out -> inp.copyTo(out, 64 * 1024) } }
            if (n < 10_000) { part.delete(); throw java.io.IOException("download too small ($n bytes)") }
            if (!part.renameTo(dest)) throw java.io.IOException("could not save ${dest.name}")
            return n
        } finally {
            c.disconnect()
        }
    }
}
