package com.apexforge.genesisplayer

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.apexforge.genesisplayer.data.vault.RoutingDataSource
import com.apexforge.genesisplayer.data.vault.Vault
import com.apexforge.genesisplayer.data.vault.VaultCrypto
import com.apexforge.genesisplayer.data.vault.VaultDataSource
import com.apexforge.genesisplayer.data.vault.VaultIndex
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyStore
import javax.crypto.SecretKey

/** WO-AURUM-2.2 §4 on a real device/emulator: Keystore, encrypted store, playback through ExoPlayer, cap. */
@RunWith(AndroidJUnit4::class)
class VaultInstrumentedTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    private fun wav(seconds: Int): ByteArray {
        val n = 44_100 * seconds
        val b = ByteBuffer.allocate(44 + n * 2).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + n * 2).put("WAVE".toByteArray()).put("fmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(44_100).putInt(88_200).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(n * 2)
        for (i in 0 until n) b.putShort((Math.sin(2 * Math.PI * 440 * i / 44_100.0) * 8000).toInt().toShort())
        return b.array()
    }

    private fun waitIdle() { Vault.clearAll(ctx); Vault.awaitIdle() }

    @Before fun clean() {
        waitIdle()
        ctx.getSharedPreferences("aurum_vault", Context.MODE_PRIVATE).edit().remove("cap_bytes").commit()
    }
    @After fun tidy() = clean()

    @Test fun keystoreKeyIsNonExportableAndDataKeyIsStable() {
        val k1 = Vault.key(ctx)
        assertEquals(32, k1.size)
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val kek = ks.getKey(Vault.KEK_ALIAS, null) as SecretKey
        assertNull("Keystore key material must not be exportable", kek.encoded)
        val wrapped = ctx.getSharedPreferences("aurum_vault", Context.MODE_PRIVATE).getString("wrapped_dek", null)
        assertNotNull(wrapped)
        assertFalse("raw data key must never be stored", wrapped!!.contains(android.util.Base64.encodeToString(k1, android.util.Base64.NO_WRAP)))
        assertTrue(k1.contentEquals(Vault.key(ctx)))
    }

    @Test fun storedTrackIsCiphertextOnDiskAndPlaysThroughExoPlayer() {
        val plain = wav(5)
        assertNull(Vault.store(ctx, "it-tone", "Tone", "Test", ByteArrayInputStream(plain)))
        val e = Vault.entry(ctx, "it-tone")!!
        val f = Vault.fileFor(ctx, e.fileId)
        val bytes = f.readBytes()
        assertFalse("no RIFF header on disk", String(bytes, Charsets.ISO_8859_1).contains("RIFF"))
        assertTrue(f.parentFile!!.list()!!.none { it.endsWith(".part") || it.contains("Tone") })
        VaultCrypto.Reader(Vault.key(ctx), f).use { assertEquals(plain.size.toLong(), it.plainLength) }

        var player: ExoPlayer? = null
        val ins = InstrumentationRegistry.getInstrumentation()
        ins.runOnMainSync {
            player = ExoPlayer.Builder(ctx)
                .setMediaSourceFactory(DefaultMediaSourceFactory(RoutingDataSource.Factory(ctx))).build().apply {
                    volume = 0f
                    setMediaItem(MediaItem.fromUri(VaultDataSource.uriFor(e.fileId)))
                    prepare(); play()
                }
        }
        var state = 0; var pos = 0L; var err: String? = null
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            ins.runOnMainSync { state = player!!.playbackState; pos = player!!.currentPosition; err = player!!.playerError?.message }
            if (err != null || pos > 1500) break
            Thread.sleep(200)
        }
        ins.runOnMainSync { player!!.release() }
        assertNull("player error: $err", err)
        assertTrue("decrypted stream must actually play (state=$state pos=$pos)", pos > 1500)
    }

    @Test fun capEvictsOldestFirst() {
        val one = wav(2) // ~176 KB plain
        ctx.getSharedPreferences("aurum_vault", Context.MODE_PRIVATE).edit().putLong("cap_bytes", 400_000).commit()
        assertNull(Vault.store(ctx, "a", "A", "x", ByteArrayInputStream(one))); Thread.sleep(5)
        assertNull(Vault.store(ctx, "b", "B", "x", ByteArrayInputStream(one))); Thread.sleep(5)
        assertNull(Vault.store(ctx, "c", "C", "x", ByteArrayInputStream(one)))
        assertEquals(listOf("c", "b"), Vault.all(ctx).map { it.trackId })
        assertEquals(listOf("a"), Vault.lastEvictions.map { it.trackId })
        assertTrue(Vault.usedBytes(ctx) <= 400_000)
        assertEquals(VaultIndex.DEFAULT_CAP_BYTES, 2L * 1024 * 1024 * 1024)
    }

    @Test fun tooBigForCapIsRefusedAndLeavesNothing() {
        ctx.getSharedPreferences("aurum_vault", Context.MODE_PRIVATE).edit().putLong("cap_bytes", 1000).commit()
        assertNotNull(Vault.store(ctx, "big", "Big", "x", ByteArrayInputStream(wav(1))))
        assertNull(Vault.entry(ctx, "big"))
        assertTrue(File(ctx.filesDir, "vault").list()!!.all { it == "index.bin" })
    }
}
