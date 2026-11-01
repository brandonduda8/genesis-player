package com.apexforge.genesisplayer.data.mymusic

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import androidx.compose.runtime.mutableIntStateOf
import com.apexforge.genesisplayer.data.Library
import com.apexforge.genesisplayer.data.Playlist
import com.apexforge.genesisplayer.data.Track
import java.io.File
import com.apexforge.genesisplayer.data.vault.Vault
import com.apexforge.genesisplayer.data.vault.VaultDataSource
import java.util.concurrent.Executors

/**
 * The owner's music, on the owner's phone. Holds the imported Takeout, the Apollo model built from it, the in-app
 * feedback and the phone's own audio files, and publishes them as the app's Library.
 * Storage: app-private files only (aurum_my_music.json, aurum_feedback.json). Nothing here is ever uploaded.
 */
object MyMusic {
    private const val TAG = "AurumMyMusic"
    private const val DATA = "aurum_my_music.json"
    private const val FEEDBACK = "aurum_feedback.json"
    private val worker = Executors.newSingleThreadExecutor()

    /** Bumps whenever the library changes, so Compose screens recompose. */
    val version = mutableIntStateOf(0)

    @Volatile var imported: ImportResult? = null; private set
    @Volatile var model: ApolloModel? = null; private set
    @Volatile var feedback: Feedback = Feedback(); private set
    @Volatile var localTracks: List<Track> = emptyList(); private set
    private val recent = ArrayDeque<String>()
    @Volatile private var appContext: Context? = null
    @Volatile private var vaultHooked = false

    /** Re-publish the Library (the Offline Crate calls this when matches or downloads change). */
    fun refresh() = publish()

    fun hasData(context: Context) = File(context.filesDir, DATA).exists()

    /** Launch: load the saved import, if any, and build Apollo off the main thread. */
    fun load(context: Context) {
        val app = context.applicationContext
        appContext = app
        if (!vaultHooked) { vaultHooked = true; Vault.addListener { publish() } } // cap evictions, removals
        worker.execute {
            try {
                File(app.filesDir, FEEDBACK).takeIf { it.exists() }?.let { feedback = MyMusicCodec.decodeFeedback(it.readText()) }
                File(app.filesDir, DATA).takeIf { it.exists() }?.let { f ->
                    val r = MyMusicCodec.decode(f.readText())
                    imported = r
                    model = ApolloEngine.build(r.events, r.searches, r.playlists, System.currentTimeMillis())
                }
                com.apexforge.genesisplayer.data.mymusic.crate.OfflineCrate.load(app)
                try { migrateOwned(app) } catch (e: Exception) { Log.e(TAG, "owned-file migration paused: ${e.message}") }
                publish()
            } catch (e: Exception) {
                Log.e(TAG, "load failed", e)
            }
        }
    }

    /** Import a Takeout .zip or watch-history.json the owner picked. Blocking; call off the main thread. */
    fun importUri(context: Context, uri: Uri): ImportAudit {
        appContext = context.applicationContext
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment ?: "import"
        val r = context.contentResolver.openInputStream(uri)?.use { Takeout.read(name, it) }
            ?: throw IllegalStateException("could not open $name")
        require(r.events.isNotEmpty()) {
            "No YouTube Music plays found in $name. Pick the Takeout .zip or watch-history.json (JSON format)."
        }
        val merged = imported?.let { old -> merge(old, r) } ?: r
        File(context.filesDir, DATA).writeText(MyMusicCodec.encode(merged))
        imported = merged
        model = ApolloEngine.build(merged.events, merged.searches, merged.playlists, System.currentTimeMillis())
        publish()
        return r.audit
    }

    /** A newer Takeout overlaps the old one: keep each (video, time) play exactly once. */
    private fun merge(a: ImportResult, b: ImportResult): ImportResult {
        val ev = (a.events + b.events).distinctBy { it.videoId to it.timeMs }.sortedBy { it.timeMs }
        val se = (a.searches + b.searches).distinctBy { it.query to it.timeMs }
        val pl = (b.playlists + a.playlists).distinctBy { it.name }
        val times = ev.map { it.timeMs }.filter { it > 0 }
        return ImportResult(ev, se, pl, b.audit.copy(keptPlays = ev.size, firstMs = times.minOrNull() ?: 0,
            lastMs = times.maxOrNull() ?: 0))
    }

    fun forget(context: Context) {
        File(context.filesDir, DATA).delete()
        File(context.filesDir, FEEDBACK).delete()
        imported = null; model = null; feedback = Feedback()
        publish()
    }

    // ---------- feedback (learns in-app) ----------
    fun like(context: Context, key: String) = update(context) { it.copy(likes = it.likes + key, dislikes = it.dislikes - key) }
    fun dislike(context: Context, key: String) = update(context) { it.copy(dislikes = it.dislikes + key, likes = it.likes - key) }
    fun clearRating(context: Context, key: String) = update(context) { it.copy(likes = it.likes - key, dislikes = it.dislikes - key) }
    fun skipped(context: Context, key: String) = update(context) { it.copy(skips = it.skips + (key to (it.skips[key] ?: 0) + 1)) }
    fun completed(context: Context, key: String) {
        synchronized(recent) { recent.remove(key); recent.addFirst(key); while (recent.size > 10) recent.removeLast() }
        update(context) { it.copy(completes = it.completes + (key to (it.completes[key] ?: 0) + 1)) }
    }
    fun recentKeys(): List<String> = synchronized(recent) { recent.toList() }

    private fun update(context: Context, f: (Feedback) -> Feedback) {
        feedback = f(feedback)
        val text = MyMusicCodec.encodeFeedback(feedback)
        val dir = context.filesDir
        worker.execute { File(dir, FEEDBACK).writeText(text) }
        publish()
    }

    // ---------- the phone's own audio (Media3 path: EQ, visualizer, background) ----------
    fun scanLocal(context: Context) {
        val app = context.applicationContext
        worker.execute {
            val out = ArrayList<Track>()
            try {
                val cols = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
                    MediaStore.Audio.Media.DURATION)
                app.contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cols,
                    "${MediaStore.Audio.Media.IS_MUSIC} != 0", null, "${MediaStore.Audio.Media.TITLE} ASC")?.use { c ->
                    while (c.moveToNext()) {
                        val id = c.getLong(0)
                        val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                        out.add(Track(id = "local:$id", artist = c.getString(2)?.takeIf { it != "<unknown>" } ?: "Unknown artist",
                            title = c.getString(1) ?: "Untitled", streamUrl = uri.toString(), artworkUrl = "",
                            durationS = c.getLong(3) / 1000))
                    }
                }
            } catch (e: SecurityException) {
                Log.w(TAG, "no permission to read audio files yet")
            }
            localTracks = out
            publish()
        }
    }

    // ---------- music the owner imports into the vault (files they own) ----------
    // 2.2: each file is streamed from the picker straight into the encrypted vault (AES-256-GCM, Keystore-wrapped
    // key). Nothing is copied in plaintext. These entries are pinned: the offline cap never evicts them.

    private fun meta(context: Context, uri: Uri, fallback: String): Pair<String, String> {
        val r = android.media.MediaMetadataRetriever()
        return try {
            r.setDataSource(context, uri)
            (r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_TITLE) ?: fallback) to
                (r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: "Your file")
        } catch (e: Exception) {
            fallback to "Your file"
        } finally {
            try { r.release() } catch (_: Exception) {}
        }
    }

    /** Encrypt the picked audio files into the vault. Returns how many were added. Blocking: call off the main thread. */
    fun importAudio(context: Context, uris: List<Uri>): Int {
        var n = 0
        val have = Vault.all(context).filter(Vault::isPinned).map { it.title to it.artist }.toMutableSet()
        for (uri in uris) {
            try {
                val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                } ?: "track-${System.currentTimeMillis()}"
                val (title, artist) = meta(context, uri, name.substringBeforeLast('.'))
                if (!have.add(title to artist)) continue // already imported
                val err = context.contentResolver.openInputStream(uri)?.use {
                    Vault.store(context, Vault.OWNED_PREFIX + java.util.UUID.randomUUID(), title, artist, it)
                } ?: "could not open"
                if (err == null) n++ else Log.w(TAG, "could not import a file: $err")
            } catch (e: Exception) {
                Log.w(TAG, "could not import a file: ${e.message}")
            }
        }
        publish()
        return n
    }

    fun removeOwned(context: Context, id: String) = Vault.remove(context, id) { publish() }

    fun ownedTracks(context: Context): List<Track> = try {
        Vault.all(context).filter(Vault::isPinned).sortedBy { it.title.lowercase() }.map { e ->
            Track(e.trackId, e.artist, e.title, VaultDataSource.uriFor(e.fileId).toString(), "", 0)
        }
    } catch (e: Exception) { Log.e(TAG, "vault unreadable: ${e.message}"); emptyList() }

    /** Plaintext files an earlier build copied into files/owned: encrypt each, verify, then delete the plaintext. */
    private fun migrateOwned(context: Context) {
        val dir = File(context.filesDir, "owned")
        if (!dir.isDirectory) return
        dir.listFiles()?.forEach { f ->
            if (f.name.endsWith(".part")) { f.delete(); return@forEach }
            if (!f.isFile) return@forEach
            val (title, artist) = meta(context, Uri.fromFile(f), f.nameWithoutExtension)
            Vault.migratePlaintext(context, f, Vault.OWNED_PREFIX + "legacy-" + f.name.hashCode().toUInt().toString(16), title, artist)
        }
        if (dir.list().isNullOrEmpty()) dir.delete()
    }

    // ---------- the app's Library ----------
    fun mixes(): List<Mix> {
        val m = model ?: return emptyList()
        val now = System.currentTimeMillis()
        return ApolloEngine.mixes(m, feedback, now, ApolloEngine.localHour(now))
    }

    fun track(key: String): Track? = Library.track(key)

    @Synchronized
    private fun publish() {
        val m = model
        val yt = m?.songs?.values?.sortedByDescending { it.heat }?.map { s ->
            Track(id = s.key, artist = s.artist, title = s.title, streamUrl = "",
                artworkUrl = "https://i.ytimg.com/vi/${s.videoId}/hqdefault.jpg", durationS = 0, youtubeId = s.videoId)
        }.orEmpty()
        val crate = appContext?.let { com.apexforge.genesisplayer.data.mymusic.crate.OfflineCrate.tracks(it) }.orEmpty()
        val owned = appContext?.let { ownedTracks(it) }.orEmpty()
        val playlists = mixes().map { Playlist(it.name, it.songKeys) } +
            (if (localTracks.isNotEmpty()) listOf(Playlist("On this phone", localTracks.map { it.id })) else emptyList())
        Library.applyRemote(yt + crate + owned + localTracks, playlists)
        version.intValue = version.intValue + 1
    }
}
