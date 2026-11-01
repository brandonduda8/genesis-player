package com.apexforge.genesisplayer.data.vault

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import com.apexforge.genesisplayer.data.Track
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.Executors
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Aurum encrypted offline vault (WO-AURUM-2.2 §4).
 *
 *  - Key: a random 32-byte data key (DEK) encrypts the tracks. The DEK is stored only WRAPPED by a non-exportable
 *    AES-256-GCM key that lives in the Android Keystore (alias [KEK_ALIAS]); the raw DEK never touches disk.
 *  - Files: app-private `files/vault/<random uuid>` in the chunked AES-256-GCM format of [VaultCrypto]. Names are
 *    opaque; titles live only in the encrypted index `files/vault/index.bin`.
 *  - Downloads stream straight from the network into the encryptor. No plaintext copy is ever written, anywhere.
 *  - Only a source that grants it gets a copy (Audius with downloads enabled by the artist). Everything else is
 *    streaming only, and the UI says why.
 *  - Cap: [VaultIndex.DEFAULT_CAP_BYTES] (2 GiB) by default; oldest downloads are evicted first, and every eviction
 *    is recorded in [lastEvictions] so the UI can show it.
 *
 * This is a NEW store: nothing from 2.1 is read, moved or rewritten, so the 2.1 -> 2.2 update cannot lose data.
 */
object Vault {
    private const val TAG = "AurumVault"
    const val KEK_ALIAS = "aurum_vault_kek"
    private const val PREFS = "aurum_vault"
    private const val P_DEK = "wrapped_dek"
    private const val P_CAP = "cap_bytes"
    const val APP_NAME = "aurum_player"

    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "aurum-vault").apply { isDaemon = true } }
    private val lock = Any()
    @Volatile private var dek: ByteArray? = null
    private var entries: MutableList<VaultEntry>? = null

    /** Listeners are told (on the vault thread) whenever the index changes. */
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()
    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }
    private fun changed() = listeners.forEach { runCatching { it() } }

    /** Track ids currently downloading -> human status. */
    val inFlight = java.util.concurrent.ConcurrentHashMap<String, String>()
    @Volatile var lastEvictions: List<VaultEntry> = emptyList(); private set
    @Volatile var lastError: String? = null; private set

    /** Block until queued vault work (remove/clear/cap/downloads) has run. Tests and CI only. */
    fun awaitIdle() { io.submit {}.get() }

    private fun dir(ctx: Context) = File(ctx.filesDir, "vault").apply { mkdirs() }
    private fun indexFile(ctx: Context) = File(dir(ctx), "index.bin")
    fun fileFor(ctx: Context, fileId: String): File {
        require(fileId.matches(Regex("[0-9a-f-]{36}"))) { "bad vault id" }
        return File(dir(ctx), fileId)
    }

    // ---------------------------------------------------------------- keys

    private fun kek(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEK_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(KEK_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return gen.generateKey()
    }

    /** The unwrapped data key, created on first use. Throws if the Keystore key is gone (vault then unreadable). */
    fun key(ctx: Context): ByteArray {
        dek?.let { return it }
        synchronized(lock) {
            dek?.let { return it }
            val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val stored = prefs.getString(P_DEK, null)
            val k = if (stored != null) {
                val blob = Base64.decode(stored, Base64.NO_WRAP)
                Cipher.getInstance("AES/GCM/NoPadding").run {
                    init(Cipher.DECRYPT_MODE, kek(), GCMParameterSpec(128, blob, 0, 12))
                    doFinal(blob, 12, blob.size - 12)
                }
            } else {
                val fresh = ByteArray(32).also { SecureRandom().nextBytes(it) }
                val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, kek()) }
                val wrapped = c.iv + c.doFinal(fresh)
                prefs.edit().putString(P_DEK, Base64.encodeToString(wrapped, Base64.NO_WRAP)).commit()
                fresh
            }
            dek = k
            return k
        }
    }

    // ---------------------------------------------------------------- index

    private fun load(ctx: Context): MutableList<VaultEntry> {
        entries?.let { return it }
        val f = indexFile(ctx)
        val list = if (f.exists()) {
            try {
                VaultIndex.decode(VaultCrypto.openBlob(key(ctx), f.readBytes())).toMutableList()
            } catch (e: Exception) {
                // Never delete on a read failure: keep the files, report, and start an empty in-memory index.
                Log.e(TAG, "vault index unreadable: ${e.message}")
                lastError = "Offline index unreadable: ${e.message}"
                mutableListOf()
            }
        } else mutableListOf()
        // Drop index rows whose file vanished (e.g. cleared app storage).
        list.removeAll { !fileFor(ctx, it.fileId).exists() }
        entries = list
        return list
    }

    private fun save(ctx: Context, list: List<VaultEntry>) {
        val tmp = File(dir(ctx), "index.bin.part")
        tmp.writeBytes(VaultCrypto.sealBlob(key(ctx), VaultIndex.encode(list)))
        if (!tmp.renameTo(indexFile(ctx))) throw java.io.IOException("could not save vault index")
    }

    fun all(ctx: Context): List<VaultEntry> = synchronized(lock) { load(ctx).sortedByDescending { it.addedAtMs } }
    fun entry(ctx: Context, trackId: String): VaultEntry? = synchronized(lock) { load(ctx).firstOrNull { it.trackId == trackId } }
    fun entryByFile(ctx: Context, fileId: String): VaultEntry? = synchronized(lock) { load(ctx).firstOrNull { it.fileId == fileId } }
    fun usedBytes(ctx: Context): Long = synchronized(lock) { load(ctx).sumOf { it.cipherBytes } }

    fun capBytes(ctx: Context): Long =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(P_CAP, VaultIndex.DEFAULT_CAP_BYTES)

    fun setCap(ctx: Context, cap: Long) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(P_CAP, cap).apply()
        io.execute { synchronized(lock) { enforceCap(ctx, null) }; changed() }
    }

    private fun enforceCap(ctx: Context, keep: String?): Boolean {
        val list = load(ctx)
        val plan = VaultIndex.evictionPlan(list, capBytes(ctx), keep) ?: return false
        if (plan.isEmpty()) return true
        plan.forEach { v ->
            fileFor(ctx, v.fileId).delete()
            list.removeAll { it.fileId == v.fileId }
            Log.i(TAG, "vault: evicted (oldest first) ${v.artist} - ${v.title}")
        }
        lastEvictions = plan
        save(ctx, list)
        return true
    }

    fun remove(ctx: Context, trackId: String) = io.execute {
        synchronized(lock) {
            val list = load(ctx)
            list.filter { it.trackId == trackId }.forEach { fileFor(ctx, it.fileId).delete() }
            list.removeAll { it.trackId == trackId }
            save(ctx, list)
        }
        changed()
    }

    fun clearAll(ctx: Context) = io.execute {
        synchronized(lock) {
            dir(ctx).listFiles()?.forEach { it.delete() }
            entries = mutableListOf()
            save(ctx, emptyList())
            lastEvictions = emptyList()
        }
        Log.i(TAG, "vault: cleared")
        changed()
    }

    // ---------------------------------------------------------------- permission + download

    /** Blocking (network). Asks the SOURCE whether a local copy is allowed. */
    fun permission(track: Track): VaultIndex.Permission {
        if (track.soundcloudUrl.isNotEmpty()) return VaultIndex.Permission(false, "streaming only: SoundCloud doesn't allow offline copies")
        val id = VaultIndex.audiusTrackId(track.streamUrl)
            ?: return VaultIndex.Permission(false, "streaming only: this source doesn't offer downloads")
        return try {
            VaultIndex.audiusPermission(get("${audiusBase(track.streamUrl)}/v1/tracks/$id?app_name=$APP_NAME").use { it.readBytes().toString(Charsets.UTF_8) })
        } catch (e: Exception) {
            VaultIndex.Permission(false, "couldn't reach Audius to check permission (${e.message}); try again online")
        }
    }

    private fun audiusBase(streamUrl: String) = Regex("^(https://[^/]+)").find(streamUrl)!!.groupValues[1]

    private fun get(url: String): InputStream {
        var u = URL(url)
        repeat(5) {
            val c = (u.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000; readTimeout = 30_000; instanceFollowRedirects = false
                setRequestProperty("User-Agent", "AurumPulse")
            }
            when (val code = c.responseCode) {
                in 200..299 -> return c.inputStream
                301, 302, 303, 307, 308 -> {
                    val loc = c.getHeaderField("Location") ?: throw java.io.IOException("redirect without location")
                    val next = URL(u, loc)
                    if (next.protocol != "https") throw java.io.IOException("refusing non-https redirect")
                    u = next; c.disconnect()
                }
                else -> { c.disconnect(); throw java.io.IOException("HTTP $code") }
            }
        }
        throw java.io.IOException("too many redirects")
    }

    /**
     * Download for offline, on the vault thread. [done] gets null on success or the reason it was refused/failed.
     */
    fun download(ctx: Context, track: Track, done: (String?) -> Unit) {
        if (inFlight.putIfAbsent(track.id, "checking permission") != null) { done("already downloading"); return }
        changed()
        io.execute {
            val result = try {
                val perm = permission(track)
                if (!perm.allowed) perm.reason
                else {
                    inFlight[track.id] = "downloading"; changed()
                    val id = VaultIndex.audiusTrackId(track.streamUrl)!!
                    get("${audiusBase(track.streamUrl)}/v1/tracks/$id/download?app_name=$APP_NAME").use { store(ctx, track.id, track.title, track.artist, it) }
                }
            } catch (e: Exception) {
                "download failed: ${e.message}"
            } finally {
                inFlight.remove(track.id)
            }
            lastError = result
            changed()
            done(result)
        }
    }

    /** Encrypt [input] into a new opaque vault file and index it. Returns null on success, else why. Vault thread. */
    fun store(ctx: Context, trackId: String, title: String, artist: String, input: InputStream): String? {
        val k = key(ctx)
        val fileId = UUID.randomUUID().toString()
        val f = fileFor(ctx, fileId)
        val plain = try { VaultCrypto.encrypt(k, input, f) } catch (e: Exception) {
            File(f.path + ".part").delete(); f.delete(); return "could not store: ${e.message}"
        }
        synchronized(lock) {
            val list = load(ctx)
            list.filter { it.trackId == trackId }.forEach { fileFor(ctx, it.fileId).delete() }
            list.removeAll { it.trackId == trackId }
            list += VaultEntry(trackId, fileId, title, artist, plain, f.length(), System.currentTimeMillis())
            if (!enforceCap(ctx, trackId)) {
                f.delete(); list.removeAll { it.fileId == fileId }; save(ctx, list)
                return "this track is bigger than the offline cap"
            }
            save(ctx, list)
        }
        Log.i(TAG, "vault: stored $artist - $title (${plain} bytes, encrypted)")
        return null
    }

    /** DEBUG/CI only: store a generated tone without any plaintext file (WAV bytes are produced in memory). */
    fun seedTone(ctx: Context, trackId: String, seconds: Int, done: (String?) -> Unit) = io.execute {
        val r = store(ctx, trackId, "Vault test tone", "Aurum CI", ToneWav(seconds))
        changed(); done(r)
    }

    /** A streaming 44.1 kHz mono 16-bit 440 Hz WAV generator: the bytes exist only in memory, one read at a time. */
    private class ToneWav(seconds: Int) : InputStream() {
        private val samples = 44_100 * seconds
        private val dataLen = samples * 2
        private val header = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + dataLen); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1); putInt(44_100); putInt(88_200)
            putShort(2); putShort(16); put("data".toByteArray()); putInt(dataLen)
        }.array()
        private var pos = 0
        override fun read(): Int {
            if (pos >= 44 + dataLen) return -1
            val b = if (pos < 44) header[pos].toInt() and 0xFF else {
                val i = (pos - 44) / 2
                val s = (Math.sin(2 * Math.PI * 440 * i / 44_100.0) * 8000).toInt()
                if ((pos - 44) % 2 == 0) s and 0xFF else (s shr 8) and 0xFF
            }
            pos++
            return b
        }
    }
}
