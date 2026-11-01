package com.apexforge.genesisplayer.data.vault

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.RandomAccessFile
import java.security.SecureRandom
import java.nio.file.Files

class VaultCryptoTest {
    private val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
    private fun tmp() = Files.createTempDirectory("vault").toFile()

    /** A fake "audio" payload that LOOKS like an MP3 (ID3 + frame sync) so leaks would be detectable. */
    private fun mp3Like(n: Int): ByteArray {
        val b = ByteArray(n) { (it * 31 + 7).toByte() }
        "ID3".toByteArray().copyInto(b, 0, 0, minOf(3, n))
        var i = 10
        while (i + 1 < n) { b[i] = 0xFF.toByte(); b[i + 1] = 0xFB.toByte(); i += 417 }
        return b
    }

    private fun readAll(r: VaultCrypto.Reader): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(10_000)
        var pos = 0L
        while (true) {
            val n = r.read(pos, buf, 0, buf.size)
            if (n < 0) break
            out.write(buf, 0, n); pos += n
        }
        return out.toByteArray()
    }

    @Test fun roundTripsExactlyAcrossChunkBoundaries() {
        for (size in listOf(1, 1000, 4096, 4097, 3 * 4096, 3 * 4096 + 5, 300_000)) {
            val f = File(tmp(), "x")
            val plain = mp3Like(size)
            assertEquals(size.toLong(), VaultCrypto.encrypt(key, ByteArrayInputStream(plain), f, chunk = 4096))
            VaultCrypto.Reader(key, f).use { r ->
                assertEquals(size.toLong(), r.plainLength)
                assertArrayEquals("size $size", plain, readAll(r))
            }
        }
    }

    @Test fun randomAccessSeekReturnsTheRightBytes() {
        val f = File(tmp(), "x")
        val plain = mp3Like(250_000)
        VaultCrypto.encrypt(key, ByteArrayInputStream(plain), f, chunk = 8192)
        VaultCrypto.Reader(key, f).use { r ->
            val rnd = java.util.Random(1)
            repeat(200) {
                val pos = rnd.nextInt(plain.size).toLong()
                val buf = ByteArray(300)
                val n = r.read(pos, buf, 0, buf.size)
                assertArrayEquals(plain.copyOfRange(pos.toInt(), pos.toInt() + n), buf.copyOf(n))
            }
            assertEquals(-1, r.read(plain.size.toLong(), ByteArray(4), 0, 4))
        }
    }

    @Test fun ciphertextLeaksNoAudioSignatureAndEachFileHasItsOwnNonce() {
        val d = tmp()
        val plain = mp3Like(100_000)
        val a = File(d, "a"); val b = File(d, "b")
        VaultCrypto.encrypt(key, ByteArrayInputStream(plain), a)
        VaultCrypto.encrypt(key, ByteArrayInputStream(plain), b)
        val ca = a.readBytes(); val cb = b.readBytes()
        assertFalse(String(ca, Charsets.ISO_8859_1).contains("ID3"))
        fun syncs(x: ByteArray) = (0 until x.size - 1).count { x[it] == 0xFF.toByte() && x[it + 1] == 0xFB.toByte() }
        assertTrue("plaintext fixture should carry MP3 frame syncs", syncs(plain) > 200)
        assertTrue("ciphertext must look random (got ${syncs(ca)} frame syncs)", syncs(ca) < 20)
        assertFalse("same plaintext must not give same ciphertext", ca.copyOfRange(17, 200).contentEquals(cb.copyOfRange(17, 200)))
        assertFalse("nonce prefixes must differ", ca.copyOfRange(5, 13).contentEquals(cb.copyOfRange(5, 13)))
    }

    @Test fun anyFlippedBitIsRejected() {
        val f = File(tmp(), "x")
        VaultCrypto.encrypt(key, ByteArrayInputStream(mp3Like(20_000)), f, chunk = 4096)
        RandomAccessFile(f, "rw").use { raf -> raf.seek(9000); val b = raf.read(); raf.seek(9000); raf.write(b xor 1) }
        try { VaultCrypto.Reader(key, f).use { readAll(it) }; fail("tamper not detected") } catch (e: VaultCrypto.VaultCorrupt) {}
    }

    @Test fun truncationAtAChunkBoundaryIsDetected() {
        val f = File(tmp(), "x")
        VaultCrypto.encrypt(key, ByteArrayInputStream(mp3Like(4096 * 3)), f, chunk = 4096)
        RandomAccessFile(f, "rw").use { it.setLength(17L + 2 * (4096 + 16)) } // drop the whole last chunk
        try { VaultCrypto.Reader(key, f).use { readAll(it) }; fail("truncation not detected") } catch (e: VaultCrypto.VaultCorrupt) {}
    }

    @Test fun wrongKeyIsRejected() {
        val f = File(tmp(), "x")
        VaultCrypto.encrypt(key, ByteArrayInputStream(mp3Like(5000)), f)
        val other = ByteArray(32).also { SecureRandom().nextBytes(it) }
        try { VaultCrypto.Reader(other, f).use { readAll(it) }; fail("wrong key accepted") } catch (e: VaultCrypto.VaultCorrupt) {}
    }

    @Test fun emptyInputIsRefusedAndLeavesNothing() {
        val d = tmp()
        try { VaultCrypto.encrypt(key, ByteArrayInputStream(ByteArray(0)), File(d, "e")); fail() } catch (e: java.io.IOException) {}
        assertEquals(0, d.list()!!.size)
    }

    @Test fun noPlaintextOrPartFileIsLeftBehind() {
        val d = tmp()
        VaultCrypto.encrypt(key, ByteArrayInputStream(mp3Like(70_000)), File(d, "opaque"))
        assertEquals(listOf("opaque"), d.list()!!.toList())
    }

    @Test fun indexBlobIsAuthenticatedAndRoundTrips() {
        val e = listOf(VaultEntry("t1", "f1", "Song", "Artist", 10, 26, 5L))
        val blob = VaultCrypto.sealBlob(key, VaultIndex.encode(e))
        assertFalse(String(blob, Charsets.ISO_8859_1).contains("Song"))
        assertEquals(e, VaultIndex.decode(VaultCrypto.openBlob(key, blob)))
        blob[blob.size - 1] = (blob[blob.size - 1].toInt() xor 1).toByte()
        try { VaultCrypto.openBlob(key, blob); fail() } catch (x: VaultCrypto.VaultCorrupt) {}
    }

    @Test fun evictionIsOldestFirstAndNeverTheNewTrack() {
        fun e(id: String, c: Long, at: Long) = VaultEntry(id, id, "", "", c, c, at)
        val all = listOf(e("old", 40, 1), e("mid", 40, 2), e("new", 40, 3))
        assertEquals(emptyList<VaultEntry>(), VaultIndex.evictionPlan(all, 120))
        assertEquals(listOf("old"), VaultIndex.evictionPlan(all, 100)!!.map { it.trackId })
        assertEquals(listOf("old", "mid"), VaultIndex.evictionPlan(all, 40, keep = "new")!!.map { it.trackId })
        assertNull(VaultIndex.evictionPlan(listOf(e("huge", 500, 9)), 100, keep = "huge"))
    }

    @Test fun downloadPermissionComesOnlyFromTheSource() {
        assertEquals("abc123", VaultIndex.audiusTrackId("https://api.audius.co/v1/tracks/abc123/stream?app_name=aurum"))
        assertNull(VaultIndex.audiusTrackId("https://soundcloud.com/x/y"))
        assertTrue(VaultIndex.audiusPermission("""{"data":{"downloadable":true}}""").allowed)
        assertTrue(VaultIndex.audiusPermission("""{"data":{"is_downloadable":true}}""").allowed)
        assertFalse(VaultIndex.audiusPermission("""{"data":{"is_downloadable":true,"is_download_gated":true}}""").allowed)
        assertFalse(VaultIndex.audiusPermission("""{"data":{"title":"x"}}""").allowed)
    }
}
