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

    fun hasData(context: Context) = File(context.filesDir, DATA).exists()

    /** Launch: load the saved import, if any, and build Apollo off the main thread. */
    fun load(context: Context) {
        val app = context.applicationContext
        worker.execute {
            try {
                File(app.filesDir, FEEDBACK).takeIf { it.exists() }?.let { feedback = MyMusicCodec.decodeFeedback(it.readText()) }
                File(app.filesDir, DATA).takeIf { it.exists() }?.let { f ->
                    val r = MyMusicCodec.decode(f.readText())
                    imported = r
                    model = ApolloEngine.build(r.events, r.searches, r.playlists, System.currentTimeMillis())
                }
                publish()
            } catch (e: Exception) {
                Log.e(TAG, "load failed", e)
            }
        }
    }

    /** Import a Takeout .zip or watch-history.json the owner picked. Blocking; call off the main thread. */
    fun importUri(context: Context, uri: Uri): ImportAudit {
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
        val playlists = mixes().map { Playlist(it.name, it.songKeys) } +
            (if (localTracks.isNotEmpty()) listOf(Playlist("On this phone", localTracks.map { it.id })) else emptyList())
        Library.applyRemote(yt + localTracks, playlists)
        version.intValue = version.intValue + 1
    }
}
