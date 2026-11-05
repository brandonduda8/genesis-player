package com.apexforge.genesisplayer.data.mymusic.crate

import android.content.Context
import android.util.Log
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import com.apexforge.genesisplayer.data.Track
import com.apexforge.genesisplayer.data.mymusic.MyMusic
import com.apexforge.genesisplayer.data.vault.Vault
import com.apexforge.genesisplayer.data.vault.VaultDataSource
import java.io.File
import java.util.concurrent.Executors

/**
 * The Offline Crate: for each song in the owner's history, the legitimate copies that exist, by the same artist only.
 * Matching runs on the phone, artists in order of how much the owner plays them, and resumes where it stopped.
 * Downloads happen only where the rights holder allows it, and (2.2) stream straight into the encrypted vault:
 * no plaintext copy is ever written. Plaintext files left by 2.1/2.2-preview-era builds are migrated, verified.
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

    /** Where builds before 2.2 left PLAINTEXT offline copies. Read only by [migrateLegacy]. */
    private fun legacyFile(context: Context, songKey: String): File =
        File(File(context.filesDir, OFFLINE_DIR), songKey.replace(Regex("[^A-Za-z0-9._-]"), "_").take(120) + ".mp3")

    private fun vaultId(songKey: String) = "offline:$songKey"

    /** Encrypt any plaintext offline copies an earlier build left behind; each is deleted only after a verified copy. */
    private fun migrateLegacy(context: Context) {
        val dir = File(context.filesDir, OFFLINE_DIR)
        if (!dir.isDirectory) return
        val model = MyMusic.model
        for ((k, _) in matches) {
            val f = legacyFile(context, k)
            if (!f.isFile) continue
            val s = model?.songs?.get(k)
            Vault.migratePlaintext(context, f, vaultId(k), s?.title ?: k, s?.artist ?: "")
        }
        dir.listFiles()?.filter { it.name.endsWith(".part") }?.forEach { it.delete() } // never-finished downloads
        if (dir.list().isNullOrEmpty()) dir.delete()
    }

    /** Download a permitted file straight into the vault. Returns null on success, else why. Blocking. */
    fun saveFromUrl(context: Context, trackId: String, title: String, artist: String, url: String): String? {
        val err = CrateNet.stream(url) { Vault.store(context, trackId, title, artist, it) }
        if (err != null) return err
        val e = Vault.entry(context, trackId) ?: return "not stored"
        if (e.plainBytes < 10_000) { Vault.remove(context, trackId); return "download too small (${e.plainBytes} bytes)" }
        return null
    }

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
        try { migrateLegacy(context) } catch (e: Exception) { Log.e(TAG, "offline migration paused: ${e.message}") }
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
                var unreachable = 0
                artists.forEachIndexed { i, a ->
                    progress.value = "Checking ${a.name} (${i + 1} of ${artists.size})"
                    val candidates = ArrayList<Candidate>()
                    // A source that can't be reached is NOT evidence of absence: the artist stays "not checked" and is
                    // retried next time, instead of being recorded as unavailable.
                    val reached = try {
                        CrateMatcher.pickArtistAccount(a.name, CrateNet.audiusUsers(a.name))?.let { acct ->
                            candidates += CrateNet.audiusTracksOf(acct)
                        }
                        CrateNet.archiveSearch(a.name).filter { CrateMatcher.sameArtist(it.creator, a.name) }.take(3)
                            .forEach { candidates += CrateNet.archiveFiles(it) }
                        true
                    } catch (e: Exception) {
                        Log.w(TAG, "a source was unreachable; artist left unchecked: ${e.message}")
                        false
                    }
                    if (!reached) { unreachable++; return@forEachIndexed }
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
                progress.value = if (unreachable > 0) "Stopped: $unreachable artist(s) couldn't be reached. Tap Continue matching to retry them." else null
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
        // only the EXACT recording, and only with permission: alternates never enter the offline vault
        val todo = songKeys.filter { k -> matches[k]?.let { it.kind == MatchKind.EXACT && it.downloadable } == true &&
            !isOffline(app, k) }
        if (todo.isEmpty()) return
        downloading.value = downloading.value + todo
        dl.execute {
            for (k in todo) {
                try {
                    val s = MyMusic.model?.songs?.get(k)
                    val err = saveFromUrl(app, vaultId(k), s?.title ?: k, s?.artist ?: "", matches.getValue(k).candidate!!.downloadUrl)
                    if (err == null) Log.i(TAG, "saved offline (encrypted)") else Log.w(TAG, "download failed: $err")
                } catch (e: Exception) {
                    Log.w(TAG, "download failed: ${e.message}")
                } finally {
                    downloading.value = downloading.value - k
                    publish()
                }
            }
        }
    }

    fun removeOffline(context: Context, songKey: String) = Vault.remove(context, vaultId(songKey)) { publish() }

    fun isOffline(context: Context, songKey: String) =
        try { Vault.entry(context, vaultId(songKey)) != null } catch (e: Exception) { false }

    fun status(context: Context, songKey: String): SongStatus = SongStatus.of(isOffline(context, songKey), matches[songKey])

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
            val v = try { Vault.entry(context, vaultId(k)) } catch (e: Exception) { null }
            if (v != null) out += Track(vaultId(k), s.artist, s.title, VaultDataSource.uriFor(v.fileId).toString(), art, c?.durationS ?: 0)
            if (c != null && m.kind != MatchKind.UNAVAILABLE) {
                val title = if (m.kind == MatchKind.ALTERNATE) "${s.title} · ${c.title}" else s.title
                out += Track((if (m.kind == MatchKind.EXACT) "crate:" else "alt:") + k, s.artist, title, c.streamUrl, art, c.durationS)
            }
        }
        return out
    }

    fun publish() {
        MyMusic.refresh()
        version.intValue = version.intValue + 1
    }
}
