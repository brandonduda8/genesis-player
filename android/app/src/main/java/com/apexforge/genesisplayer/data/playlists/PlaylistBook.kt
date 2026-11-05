package com.apexforge.genesisplayer.data.playlists

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Aurum 2.3: playlists the owner makes. Local, offline, no AI.
 *
 * An [Entry] keeps the track id AND a snapshot of artist/title. The id is a Takeout song key, `owned:…`, or a
 * phone track id. If a track later disappears (library re-import, removed file), the entry is kept and shown as
 * unavailable instead of silently vanishing, and an import can re-match it by artist + title.
 *
 * Everything here is pure Kotlin + org.json + java.io.File, so the whole model is JVM-tested.
 */
data class Entry(val id: String, val artist: String, val title: String)

data class UserPlaylist(
    val id: String,
    val name: String,
    val entries: List<Entry>,
    val createdMs: Long,
    val updatedMs: Long,
)

data class PlaylistBook(val playlists: List<UserPlaylist> = emptyList()) {

    fun get(id: String): UserPlaylist? = playlists.firstOrNull { it.id == id }

    private fun replace(p: UserPlaylist) = copy(playlists = playlists.map { if (it.id == p.id) p else it })
    private fun need(id: String) = get(id) ?: throw IllegalArgumentException("no such playlist")

    /** Trimmed, 1..[MAX_NAME] chars, unique ignoring case ("Gym" exists → "Gym (2)"). */
    fun uniqueName(raw: String, except: String? = null): String {
        val base = raw.trim().replace(Regex("\\s+"), " ").take(MAX_NAME)
        require(base.isNotEmpty()) { "a playlist needs a name" }
        val taken = playlists.filter { it.id != except }.map { it.name.lowercase() }.toSet()
        if (base.lowercase() !in taken) return base
        var n = 2
        while ("$base ($n)".lowercase() in taken) n++
        return "$base ($n)"
    }

    fun create(name: String, now: Long, id: String): Pair<PlaylistBook, UserPlaylist> {
        val p = UserPlaylist(id, uniqueName(name), emptyList(), now, now)
        return copy(playlists = playlists + p) to p
    }

    fun rename(id: String, name: String, now: Long) = replace(need(id).copy(name = uniqueName(name, except = id), updatedMs = now))

    fun delete(id: String) = copy(playlists = playlists.filterNot { it.id == id })

    /** Duplicate policy: a track appears once per playlist; re-adding it is a no-op. Returns (book, added count). */
    fun add(id: String, items: List<Entry>, now: Long): Pair<PlaylistBook, Int> {
        val p = need(id)
        val have = p.entries.map { it.id }.toMutableSet()
        val fresh = items.filter { have.add(it.id) }
        if (fresh.isEmpty()) return this to 0
        return replace(p.copy(entries = p.entries + fresh, updatedMs = now)) to fresh.size
    }

    fun removeAt(id: String, index: Int, now: Long): PlaylistBook {
        val p = need(id)
        require(index in p.entries.indices) { "no track at $index" }
        return replace(p.copy(entries = p.entries.filterIndexed { i, _ -> i != index }, updatedMs = now))
    }

    fun move(id: String, from: Int, to: Int, now: Long): PlaylistBook {
        val p = need(id)
        require(from in p.entries.indices && to in p.entries.indices) { "move out of range" }
        if (from == to) return this
        val l = p.entries.toMutableList()
        l.add(to, l.removeAt(from))
        return replace(p.copy(entries = l, updatedMs = now))
    }

    companion object {
        const val VERSION = 1
        const val MAX_NAME = 80
        const val EXPORT_KIND = "aurum_playlists"

        private fun Entry.json() = JSONObject().put("id", id).put("a", artist).put("t", title)
        private fun UserPlaylist.json() = JSONObject().put("id", id).put("name", name)
            .put("created", createdMs).put("updated", updatedMs)
            .put("entries", JSONArray().also { a -> entries.forEach { a.put(it.json()) } })

        private fun entry(o: JSONObject) = Entry(o.getString("id"), o.optString("a"), o.optString("t"))
        private fun playlist(o: JSONObject) = UserPlaylist(
            o.getString("id"), o.getString("name"),
            o.optJSONArray("entries")?.let { a -> List(a.length()) { entry(a.getJSONObject(it)) } }.orEmpty(),
            o.optLong("created"), o.optLong("updated"))

        fun encode(b: PlaylistBook): String = JSONObject().put("v", VERSION)
            .put("playlists", JSONArray().also { a -> b.playlists.forEach { a.put(it.json()) } }).toString()

        /** Throws [UnsupportedVersion] for a file written by a NEWER Aurum, so it is never overwritten by an old one. */
        fun decode(text: String): PlaylistBook {
            val o = JSONObject(text)
            val v = o.optInt("v", 1)
            if (v > VERSION) throw UnsupportedVersion(v)
            val a = o.getJSONArray("playlists")
            return PlaylistBook(List(a.length()) { playlist(a.getJSONObject(it)) })
        }

        /** A shareable file: the playlists plus artist/title for every track, so another install can re-match them. */
        fun export(b: PlaylistBook, ids: Collection<String>? = null, now: Long): String =
            JSONObject().put("kind", EXPORT_KIND).put("v", VERSION).put("exported", now)
                .put("playlists", JSONArray().also { a -> b.playlists.filter { ids == null || it.id in ids }.forEach { a.put(it.json()) } })
                .toString(2)

        /** Normalised artist|title used to re-match an entry whose id isn't in this library. */
        fun matchKey(artist: String, title: String): String {
            fun n(s: String) = s.lowercase().replace(Regex("\\(.*?\\)|\\[.*?]"), " ")
                .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
            return n(artist) + "|" + n(title)
        }
    }
}

class UnsupportedVersion(val version: Int) : Exception("playlists file is version $version; this Aurum reads up to ${PlaylistBook.VERSION}")

data class ImportReport(val playlists: Int, val entries: Int, val rematched: Int, val unavailable: Int)

object PlaylistImport {
    /**
     * Merge an exported file into [into]. Imported playlists always get NEW ids (nothing existing is replaced);
     * clashing names get " (2)". Each entry keeps its id if [exists] says the library has it, else is re-matched by
     * artist + title via [byMatchKey], else is kept as-is (shown unavailable). Nothing is dropped.
     */
    fun merge(into: PlaylistBook, text: String, now: Long, newId: () -> String,
              exists: (String) -> Boolean, byMatchKey: (String) -> String?): Pair<PlaylistBook, ImportReport> {
        val o = JSONObject(text)
        require(o.optString("kind") == PlaylistBook.EXPORT_KIND) { "not an Aurum playlists file" }
        if (o.optInt("v", 1) > PlaylistBook.VERSION) throw UnsupportedVersion(o.optInt("v"))
        val incoming = PlaylistBook.decode(JSONObject().put("v", 1).put("playlists", o.getJSONArray("playlists")).toString())
        var book = into
        var entries = 0; var rematched = 0; var unavailable = 0
        for (p in incoming.playlists) {
            val fixed = p.entries.map { e ->
                entries++
                when {
                    exists(e.id) -> e
                    else -> byMatchKey(PlaylistBook.matchKey(e.artist, e.title))?.let { rematched++; e.copy(id = it) }
                        ?: e.also { unavailable++ }
                }
            }.distinctBy { it.id }
            val (b, made) = book.create(p.name, now, newId())
            book = b.add(made.id, fixed, now).first
        }
        return book to ImportReport(incoming.playlists.size, entries, rematched, unavailable)
    }
}

/**
 * Durable file store: atomic writes (tmp + rename) and the previous good copy kept as `.bak`.
 * A main file that can't be read is preserved as `.corrupt-<ms>` and the `.bak` is used, so a bad write
 * never costs the owner their playlists. A file from a newer Aurum is refused, never overwritten.
 */
class PlaylistStore(private val file: File) {
    private val bak = File(file.path + ".bak")
    var readOnlyReason: String? = null; private set

    fun load(now: Long = System.currentTimeMillis()): PlaylistBook {
        readOnlyReason = null
        if (!file.exists()) return if (bak.exists()) tryRead(bak) ?: PlaylistBook() else PlaylistBook()
        return try {
            PlaylistBook.decode(file.readText())
        } catch (e: UnsupportedVersion) {
            readOnlyReason = e.message; PlaylistBook()
        } catch (e: Exception) {
            file.renameTo(File(file.path + ".corrupt-$now"))
            tryRead(bak) ?: PlaylistBook()
        }
    }

    private fun tryRead(f: File) = try { PlaylistBook.decode(f.readText()) } catch (e: Exception) { null }

    fun save(b: PlaylistBook) {
        check(readOnlyReason == null) { readOnlyReason!! }
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(PlaylistBook.encode(b))
        if (file.exists()) file.copyTo(bak, overwrite = true)
        if (!tmp.renameTo(file)) { tmp.copyTo(file, overwrite = true); tmp.delete() }
    }
}

/**
 * Where a playlist plays. Same rule as the Signal Archive: a song with an exact or offline copy plays in Aurum
 * (EQ, background, lock screen); otherwise it plays through YouTube. Nothing is substituted, and offline only what
 * truly works offline is queued.
 */
object PlaylistPlayback {
    enum class Route { AURUM, YOUTUBE, NONE }
    data class Plan(val route: Route, val ids: List<String>, val index: Int, val skipped: Int, val reason: String = "")

    /**
     * [aurumId] maps an entry id to its Aurum track id (offline:/crate:/owned:/phone ids), or null.
     * [worksOffline] is true for Aurum ids that play with no network. [onYouTube] tells whether YouTube can play it.
     */
    fun plan(entries: List<Entry>, start: Int, online: Boolean, shuffle: Boolean,
             aurumId: (String) -> String?, worksOffline: (String) -> Boolean, onYouTube: (String) -> Boolean,
             random: java.util.Random = java.util.Random()): Plan {
        if (entries.isEmpty()) return Plan(Route.NONE, emptyList(), 0, 0, "This playlist is empty.")
        val tapped = entries[start.coerceIn(entries.indices)]
        val tappedAurum = aurumId(tapped.id)
        fun order(ids: List<String>, first: String): Pair<List<String>, Int> =
            if (!shuffle) ids to ids.indexOf(first).coerceAtLeast(0)
            else (listOf(first) + (ids - first).shuffled(random)) to 0
        if (tappedAurum != null && (online || worksOffline(tappedAurum))) {
            val ids = entries.mapNotNull { aurumId(it.id) }.filter { online || worksOffline(it) }.distinct()
            val (o, i) = order(ids, tappedAurum)
            return Plan(Route.AURUM, o, i, entries.size - ids.size)
        }
        if (!online) return Plan(Route.NONE, emptyList(), 0, entries.size,
            "Offline: \"${tapped.title}\" needs the internet. Songs saved in your Crate still play.")
        if (onYouTube(tapped.id)) {
            val ids = entries.map { it.id }.filter(onYouTube).distinct()
            val (o, i) = order(ids, tapped.id)
            return Plan(Route.YOUTUBE, o, i, entries.size - ids.size)
        }
        return Plan(Route.NONE, emptyList(), 0, entries.size, "\"${tapped.title}\" isn't available in this library anymore.")
    }
}
