package com.apexforge.genesisplayer.data.vault

import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Aurum vault file format: AES-256-GCM over fixed-size chunks, so a track can be decrypted as a stream and SEEKED
 * without ever writing plaintext anywhere.
 *
 *   header (17 bytes):  "AVLT" | version(1) | nonce prefix (8 random bytes, unique per file) | chunk size (4, BE)
 *   chunk i:            AES-256-GCM(plaintext_i), followed by its 16-byte tag
 *   chunk i nonce:      nonce prefix (8) || i (4, BE). Unique per (file, chunk) under one key.
 *   chunk i AAD:        the header || i (4, BE) || last-flag (1)
 *
 * The last-flag binds the final chunk. Truncating a file at a chunk boundary makes the new final chunk fail its tag,
 * so a cut file is detected, not silently played short. Any flipped bit fails that chunk's tag.
 */
object VaultCrypto {
    private val MAGIC = byteArrayOf('A'.code.toByte(), 'V'.code.toByte(), 'L'.code.toByte(), 'T'.code.toByte())
    const val VERSION: Byte = 1
    const val HEADER = 17
    const val TAG = 16
    const val DEFAULT_CHUNK = 64 * 1024

    class VaultCorrupt(msg: String) : java.io.IOException(msg)

    private fun cipher(mode: Int, key: ByteArray, nonce: ByteArray, aad: ByteArray): Cipher =
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            updateAAD(aad)
        }

    private fun nonce(prefix: ByteArray, i: Int) = ByteBuffer.allocate(12).put(prefix).putInt(i).array()
    private fun aad(header: ByteArray, i: Int, last: Boolean) =
        ByteBuffer.allocate(header.size + 5).put(header).putInt(i).put(if (last) 1 else 0).array()

    /**
     * Encrypt [input] to [dest] chunk by chunk (only one plaintext chunk is ever in memory) and return the number of
     * plaintext bytes. Writes "dest.part" and renames it, so a crash never leaves a half file under the final name.
     */
    fun encrypt(key: ByteArray, input: InputStream, dest: File, chunk: Int = DEFAULT_CHUNK,
                random: SecureRandom = SecureRandom()): Long {
        require(key.size == 32) { "AES-256 needs a 32-byte key" }
        val prefix = ByteArray(8).also { random.nextBytes(it) }
        val header = ByteBuffer.allocate(HEADER).put(MAGIC).put(VERSION).put(prefix).putInt(chunk).array()
        val part = File(dest.path + ".part")
        var total = 0L
        part.outputStream().buffered().use { out ->
            out.write(header)
            var cur = readFull(input, chunk)
            var i = 0
            while (true) {
                val next = if (cur.size == chunk) readFull(input, chunk) else ByteArray(0)
                val last = next.isEmpty()
                out.write(cipher(Cipher.ENCRYPT_MODE, key, nonce(prefix, i), aad(header, i, last)).doFinal(cur))
                total += cur.size
                if (last) break
                cur = next
                i++
            }
        }
        if (total == 0L) { part.delete(); throw java.io.IOException("empty download: nothing to store") }
        if (!part.renameTo(dest)) { part.delete(); throw java.io.IOException("could not finalise vault file") }
        return total
    }

    private fun readFull(input: InputStream, n: Int): ByteArray {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(buf, off, n - off)
            if (r < 0) break
            off += r
        }
        return if (off == n) buf else buf.copyOf(off)
    }

    /** Random-access decrypting reader. Holds one decrypted chunk in memory; never writes plaintext anywhere. */
    class Reader(private val key: ByteArray, file: File) : AutoCloseable {
        private val raf = RandomAccessFile(file, "r")
        private val header = ByteArray(HEADER)
        private val prefix: ByteArray
        private val chunk: Int
        private val chunks: Int
        val plainLength: Long
        private var cachedIndex = -1
        private var cached = ByteArray(0)

        init {
            if (raf.length() < HEADER + TAG) throw VaultCorrupt("too short")
            raf.readFully(header)
            if (!header.copyOfRange(0, 4).contentEquals(MAGIC) || header[4] != VERSION) throw VaultCorrupt("not an Aurum vault file")
            prefix = header.copyOfRange(5, 13)
            chunk = ByteBuffer.wrap(header, 13, 4).int
            if (chunk <= 0 || chunk > 16 * 1024 * 1024) throw VaultCorrupt("bad chunk size")
            val body = raf.length() - HEADER
            val full = (body / (chunk + TAG)).toInt()
            val rem = body % (chunk + TAG)
            if (rem in 1..TAG.toLong()) throw VaultCorrupt("truncated chunk")
            chunks = full + if (rem > 0) 1 else 0
            if (chunks == 0) throw VaultCorrupt("empty")
            plainLength = body - chunks.toLong() * TAG
        }

        private fun load(i: Int): ByteArray {
            if (i == cachedIndex) return cached
            val start = HEADER + i.toLong() * (chunk + TAG)
            val len = (minOf(start + chunk + TAG, raf.length()) - start).toInt()
            val ct = ByteArray(len)
            raf.seek(start)
            raf.readFully(ct)
            cached = try {
                cipher(Cipher.DECRYPT_MODE, key, nonce(prefix, i), aad(header, i, i == chunks - 1)).doFinal(ct)
            } catch (e: AEADBadTagException) {
                throw VaultCorrupt("chunk $i failed authentication (tampered, truncated or wrong key)")
            }
            cachedIndex = i
            return cached
        }

        /** Read up to [len] plaintext bytes starting at [pos]. Returns -1 at end of track. */
        fun read(pos: Long, buf: ByteArray, off: Int, len: Int): Int {
            if (pos >= plainLength) return -1
            val i = (pos / chunk).toInt()
            val inChunk = (pos % chunk).toInt()
            val plain = load(i)
            val n = minOf(len, plain.size - inChunk)
            System.arraycopy(plain, inChunk, buf, off, n)
            return n
        }

        override fun close() = raf.close()
    }

    /** Small authenticated blobs (the vault index): random 12-byte nonce || AES-256-GCM(data). */
    fun sealBlob(key: ByteArray, data: ByteArray, random: SecureRandom = SecureRandom()): ByteArray {
        val n = ByteArray(12).also { random.nextBytes(it) }
        return n + cipher(Cipher.ENCRYPT_MODE, key, n, "AVLT-INDEX".toByteArray()).doFinal(data)
    }

    fun openBlob(key: ByteArray, blob: ByteArray): ByteArray = try {
        cipher(Cipher.DECRYPT_MODE, key, blob.copyOfRange(0, 12), "AVLT-INDEX".toByteArray()).doFinal(blob, 12, blob.size - 12)
    } catch (e: AEADBadTagException) {
        throw VaultCorrupt("index failed authentication")
    }
}
