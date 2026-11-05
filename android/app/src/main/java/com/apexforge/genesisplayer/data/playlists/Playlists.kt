package com.apexforge.genesisplayer.data.playlists

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.mutableIntStateOf
import com.apexforge.genesisplayer.data.Library
import com.apexforge.genesisplayer.data.mymusic.MyMusic
import com.apexforge.genesisplayer.data.mymusic.crate.OfflineCrate
import java.io.File
import java.util.UUID

/**
 * Aurum 2.3: the owner's playlists on this phone. Wraps [PlaylistBook] + [PlaylistStore] (files/aurum_playlists.json)
 * with a Compose-observable [version], and resolves entries against the live library. All on-device; nothing uploads.
 */
object Playlists {
    private const val TAG = "AurumPlaylists"
    const val FILE = "aurum_playlists.json"

    val version = mutableIntStateOf(0)
    @Volatile var book: PlaylistBook = PlaylistBook(); private set
    @Volatile var readOnlyReason: String? = null; private set
    private var store: PlaylistStore? = null

    @Synchronized fun load(context: Context) {
        val s = PlaylistStore(File(context.applicationContext.filesDir, FILE))
        store = s
        book = s.load()
        readOnlyReason = s.readOnlyReason
        Log.i(TAG, "loaded ${book.playlists.size} playlists" + (readOnlyReason?.let { " (read-only: $it)" } ?: ""))
        version.intValue++
    }

    private fun ensure(context: Context) { if (store == null) load(context) }

    @Synchronized private fun commit(context: Context, next: PlaylistBook) {
        ensure(context)
        store!!.save(next) // throws if read-only: a newer Aurum's file is never overwritten
        book = next
        version.intValue++
    }

    private fun now() = System.currentTimeMillis()
    private fun newId() = UUID.randomUUID().toString()

    /** Entry snapshot for a library track id (Takeout song key, owned:, phone, offline:/crate:). */
    fun entryFor(id: String): Entry? {
        val key = id.removePrefix("offline:").removePrefix("crate:").removePrefix("alt:")
        val canonical = if (MyMusic.model?.songs?.containsKey(key) == true) key else id
        val t = Library.track(canonical) ?: Library.track(id) ?: return null
        return Entry(canonical, t.artist, t.title)
    }

    fun create(context: Context, name: String): UserPlaylist {
        ensure(context)
        val (b, p) = book.create(name, now(), newId())
        commit(context, b)
        return p
    }

    fun rename(context: Context, id: String, name: String) { ensure(context); commit(context, book.rename(id, name, now())) }
    fun delete(context: Context, id: String) { ensure(context); commit(context, book.delete(id)) }
    fun removeAt(context: Context, id: String, index: Int) { ensure(context); commit(context, book.removeAt(id, index, now())) }
    fun move(context: Context, id: String, from: Int, to: Int) { ensure(context); commit(context, book.move(id, from, to, now())) }

    /** Returns how many were actually added (tracks already in the playlist are skipped). */
    fun add(context: Context, id: String, trackIds: List<String>): Int {
        ensure(context)
        val (b, n) = book.add(id, trackIds.mapNotNull(::entryFor), now())
        if (n > 0) commit(context, b)
        return n
    }

    /** The Aurum (Media3) track for an entry, or null when it can only play through YouTube / not at all. */
    fun aurumId(context: Context, entryId: String): String? = when {
        entryId.startsWith("owned:") || entryId.startsWith("local:") || entryId.startsWith("phone:") ->
            entryId.takeIf { Library.track(it) != null }
        Library.track(entryId)?.let { it.youtubeId.isEmpty() && it.streamUrl.isNotEmpty() } == true -> entryId
        else -> OfflineCrate.aurumTrackId(context, entryId)
    }

    fun worksOffline(aurumId: String) = aurumId.startsWith("offline:") || aurumId.startsWith("owned:") ||
        Library.track(aurumId)?.streamUrl?.let { it.startsWith("content:") || it.startsWith("file:") } == true

    fun onYouTube(entryId: String) = Library.track(entryId)?.youtubeId?.isNotEmpty() == true

    fun available(context: Context, e: Entry) = Library.track(e.id) != null || aurumId(context, e.id) != null

    fun plan(context: Context, p: UserPlaylist, start: Int, online: Boolean, shuffle: Boolean) =
        PlaylistPlayback.plan(p.entries, start, online, shuffle, { aurumId(context, it) }, ::worksOffline, ::onYouTube)

    // ---------- backup / transfer (owner-initiated, via the system file picker; never automatic) ----------

    fun exportTo(context: Context, uri: Uri, ids: Collection<String>? = null) {
        val text = PlaylistBook.export(book, ids, now())
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray()) }
            ?: throw IllegalStateException("could not open the file for writing")
    }

    fun importFrom(context: Context, uri: Uri): ImportReport {
        ensure(context)
        val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: throw IllegalStateException("could not open the file")
        val byKey = HashMap<String, String>()
        MyMusic.model?.songs?.values?.forEach { s -> byKey.putIfAbsent(PlaylistBook.matchKey(s.artist, s.title), s.key) }
        Library.tracks.forEach { t -> byKey.putIfAbsent(PlaylistBook.matchKey(t.artist, t.title), t.id) }
        val (b, rep) = PlaylistImport.merge(book, text, now(), ::newId,
            exists = { Library.track(it) != null }, byMatchKey = { byKey[it] })
        commit(context, b)
        return rep
    }
}
