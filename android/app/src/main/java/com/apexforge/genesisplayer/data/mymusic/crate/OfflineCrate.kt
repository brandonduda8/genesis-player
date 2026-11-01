package com.apexforge.genesisplayer.data.mymusic.crate

import android.content.Context
import android.util.Log
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import com.apexforge.genesisplayer.data.Track
import com.apexforge.genesisplayer.data.mymusic.MyMusic
import java.io.File
import java.util.concurrent.Executors

/**
 * The Offline Crate: for each song in the owner's history, the legitimate copies that exist, by the same artist only.
 * Matching runs on the phone, artists in order of how much the owner plays them, and resumes where it stopped.
 * Downloads go to app-private storage and happen only where the rights holder allows it.
 */
object OfflineCrate {
    private const val TAG = "AurumCrate"
    private const val FILE = "aurum_crate.json"
    private const val OFFLINE_DIR = "offline"
    private val net = Executors.newSingleThreadExecutor()
    private val dl = Executors.newSingleThreadExecutor()

    val version = mutableIntStateOf(0)
    val progress = mutableStateOf<String?>(null)
    val downloading = mutableStateOf<Set<String>>(emptySet())

    @Volatile var matches: Map<String, CrateMatch> = emptyMap(); private set
    @Volatile var checkedArtists: Set<String> = emptySet(); private set
    @Volatile var checkedAtMs = 0L; private set
    @Volatile private var running = false

    fun offlineFile(context: Context, songKey: String): File =
        File(File(context.filesDir, OFFLINE_DIR), songKey.replace(Regex("[^A-Za-z0-9._-]"), "_").take(120) + ".mp3")

    fun load(context: Context) {
        val f = File(context.filesDir, FILE)
        if (!f.exists()) return
        try {
            val (m, at) = CrateMatcher.decode(f.readText())
            matches = m.associateBy { it.songKey }
            checkedAtMs = at
            val model = MyMusic.model
            checkedArtists = m.mapNotNull { x -> model?.songs?.get(x.songKey)?.key?.substringBefore('|') }.toSet()
        } catch (e: Exception) {
            Log.e(TAG, "crate file unreadable; starting fresh", e)
        }
    }

    private fun save(context: Context) {
        val f = File(context.filesDir, FILE)
        File(f.path + ".tmp").also { it.writeText(CrateMatcher.encode(matches.values.toList(), checkedAtMs)) }.renameTo(f)
    }

    /** Check up to [maxArtists] artists (heaviest first), skipping ones already checked unless [recheck]. */
    fun matchLibrary(context: Context, maxArtists: Int = 200, recheck: Boolean = false) {
        if (running) return
        val model = MyMusic.model ?: return
        val app = context.applicationContext
        running = true
        net.execute {
            try {
                val artists = model.artists.take(maxArtists).filter { recheck || it.key !in checkedArtists }
                artists.forEachIndexed { i, a ->
                    progress.value = "Checking ${a.name} (${i + 1} of ${artists.size})"
                    val candidates = ArrayList<Candidate>()
                    try {
                        CrateMatcher.pickArtistAccount(a.name, CrateNet.audiusUsers(a.name))?.let { acct ->
                            candidates += CrateNet.audiusTracksOf(acct)
                        }
                    } catch (e: Exception) { Log.w(TAG, "Audius unavailable for an artist: ${e.message}") }
                    try {
                        CrateNet.archiveSearch(a.name).filter { CrateMatcher.sameArtist(it.creator, a.name) }.take(3)
                            .forEach { candidates += CrateNet.archiveFiles(it) }
                    } catch (e: Exception) { Log.w(TAG, "Archive unavailable for an artist: ${e.message}") }
                    val next = HashMap(matches)
                    a.songKeys.forEach { k ->
                        val s = model.songs[k] ?: return@forEach
                        next[k] = CrateMatcher.classify(k, s.title, s.artist, candidates)
                    }
                    matches = next
                    checkedArtists = checkedArtists + a.key
                    checkedAtMs = System.currentTimeMillis()
                    save(app)
                    publish()
                    Thread.sleep(150) // polite to free public APIs
                }
                progress.value = null
            } catch (e: Exception) {
                Log.e(TAG, "matching stopped", e)
                progress.value = "Stopped: ${e.message}. Tap Match again to resume."
            } finally {
                running = false
            }
        }
    }

    fun download(context: Context, songKeys: List<String>) {
        val app = context.applicationContext
        val todo = songKeys.filter { matches[it]?.downloadable == true && !offlineFile(app, it).exists() }
        if (todo.isEmpty()) return
        downloading.value = downloading.value + todo
        dl.execute {
            for (k in todo) {
                try {
                    val bytes = CrateNet.download(matches.getValue(k).candidate!!.downloadUrl, offlineFile(app, k))
                    Log.i(TAG, "saved offline: ${bytes / 1024} KB")
                } catch (e: Exception) {
                    Log.w(TAG, "download failed: ${e.message}")
                } finally {
                    downloading.value = downloading.value - k
                    publish()
                }
            }
        }
    }

    fun removeOffline(context: Context, songKey: String) {
        offlineFile(context, songKey).delete()
        publish()
    }

    fun isOffline(context: Context, songKey: String) = offlineFile(context, songKey).exists()

    /**
     * Which Aurum (Media3) track plays this song: the offline copy, else the EXACT stream. Alternate versions are
     * never auto-substituted; they play only when picked explicitly from the Crate.
     */
    fun aurumTrackId(context: Context, songKey: String): String? = when {
        isOffline(context, songKey) -> "offline:$songKey"
        matches[songKey]?.kind == MatchKind.EXACT -> "crate:$songKey"
        else -> null
    }

    /** Media3-playable tracks for the Library: offline files, exact streams, and alternates (labelled). */
    fun tracks(context: Context): List<Track> {
        val model = MyMusic.model ?: return emptyList()
        val out = ArrayList<Track>()
        for ((k, m) in matches) {
            val s = model.songs[k] ?: continue
            val c = m.candidate
            val art = c?.artworkUrl?.ifEmpty { null } ?: "https://i.ytimg.com/vi/${s.videoId}/hqdefault.jpg"
            val f = offlineFile(context, k)
            if (f.exists()) out += Track("offline:$k", s.artist, s.title, android.net.Uri.fromFile(f).toString(), art, c?.durationS ?: 0)
            if (c != null && m.kind != MatchKind.UNAVAILABLE) {
                val title = if (m.kind == MatchKind.ALTERNATE) "${s.title} · ${c.title}" else s.title
                out += Track((if (m.kind == MatchKind.EXACT) "crate:" else "alt:") + k, s.artist, title, c.streamUrl, art, c.durationS)
            }
        }
        return out
    }

    private fun publish() {
        MyMusic.refresh()
        version.intValue = version.intValue + 1
    }
}
