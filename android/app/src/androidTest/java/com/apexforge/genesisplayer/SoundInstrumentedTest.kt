package com.apexforge.genesisplayer

import android.Manifest
import android.content.Context
import android.media.audiofx.Visualizer
import android.os.Build
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.apexforge.genesisplayer.audio.SoundCurve
import com.apexforge.genesisplayer.data.vault.RoutingDataSource
import com.apexforge.genesisplayer.data.vault.Vault
import com.apexforge.genesisplayer.data.vault.VaultDataSource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.hypot

/**
 * Aurum 2.3 sound, measured on the device's real effect chain (not asserted from code):
 *  1. the Aurum preset's band levels are what the device Equalizer actually holds afterwards;
 *  2. the clip guard (DynamicsProcessing limiter) is on for a boosting preset, off for Flat (Android 9+);
 *  3. with a 60 Hz + 1 kHz test signal, Bass+ raises 60 Hz relative to 1 kHz versus Flat, read from the
 *     post-effect output with Visualizer. If the device gives Visualizer no audio (e.g. a silent emulator sink),
 *     part 3 is reported SKIPPED, never passed.
 */
@RunWith(AndroidJUnit4::class)
class SoundInstrumentedTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val ins = InstrumentationRegistry.getInstrumentation()
    private var player: ExoPlayer? = null
    private var fx: AudioFxController? = null
    private val tag = "AurumSoundTest"

    private fun twoTone(seconds: Int): ByteArray {
        val n = 44_100 * seconds
        val b = ByteBuffer.allocate(44 + n * 2).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + n * 2).put("WAVE".toByteArray()).put("fmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(44_100).putInt(88_200).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(n * 2)
        for (i in 0 until n) {
            val t = i / 44_100.0
            b.putShort(((Math.sin(2 * Math.PI * 60 * t) + Math.sin(2 * Math.PI * 1000 * t)) * 5000).toInt().toShort())
        }
        return b.array()
    }

    private fun startTone(): Int {
        Vault.remove(ctx, "sound-it"); Vault.awaitIdle()
        assertNull(Vault.store(ctx, "sound-it", "Two tone", "Aurum CI", ByteArrayInputStream(twoTone(30))))
        val e = Vault.entry(ctx, "sound-it")!!
        var sid = 0
        ins.runOnMainSync {
            player = ExoPlayer.Builder(ctx).setMediaSourceFactory(DefaultMediaSourceFactory(RoutingDataSource.Factory(ctx)))
                .build().apply { setMediaItem(MediaItem.fromUri(VaultDataSource.uriFor(e.fileId))); prepare(); play() }
        }
        val end = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < end) {
            ins.runOnMainSync { sid = player!!.audioSessionId }
            var pos = 0L; ins.runOnMainSync { pos = player!!.currentPosition }
            if (sid != 0 && pos > 500) break
            Thread.sleep(200)
        }
        assertTrue("audio session", sid != 0)
        return sid
    }

    @After fun tearDown() {
        ins.runOnMainSync { player?.release() }
        fx?.release()
        ctx.getSharedPreferences("genesis_audiofx", Context.MODE_PRIVATE).edit().clear().commit()
        Vault.remove(ctx, "sound-it"); Vault.awaitIdle()
    }

    @Test fun presetsReachTheDeviceEqAndTheClipGuardFollows() {
        val sid = startTone()
        val f = AudioFxController(ctx, sid).also { fx = it }
        assumeTrue("device has no Equalizer effect", f.eqAvailable)
        for (p in SoundCurve.Preset.values()) {
            f.applyAurumPreset(p)
            val want = f.aurumLevels(p)!!
            val have = IntArray(want.size) { f.equalizer!!.getBandLevel(it.toShort()).toInt() }
            Log.i(tag, "${p.name}: wanted ${want.joinToString()} device holds ${have.joinToString()}")
            assertTrue("${p.name} band levels applied (want ${want.joinToString()}, have ${have.joinToString()})",
                SoundCurve.distanceMb(want, have) <= 10)
            assertEquals(p, f.aurumPreset())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val boosting = SoundCurve.peakBoostMb(want) > 0 || f.isBassEnabled()
                if (boosting) {
                    assertNotNull("clip guard created for ${p.name}", f.limiter)
                    assertTrue("clip guard on for ${p.name}", f.limiter!!.enabled)
                    val lim = f.limiter!!.getLimiterByChannelIndex(0)
                    assertTrue("ceiling under full scale", lim.threshold < 0f)
                }
            }
        }
        f.clearAurumPreset()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && !f.isLoudEnabled()) assertTrue(f.limiter?.enabled != true)
    }

    @Test fun bassPresetMeasurablyLiftsTheLowToneOnTheRealOutput() {
        ins.uiAutomation.grantRuntimePermission(ctx.packageName, Manifest.permission.RECORD_AUDIO)
        val sid = startTone()
        val f = AudioFxController(ctx, sid).also { fx = it }
        assumeTrue("device has no Equalizer effect", f.eqAvailable)
        f.setBassEnabled(false) // measure the EQ curve alone
        val vis = try { Visualizer(sid) } catch (e: Exception) { null }
        assumeTrue("Visualizer unavailable on this device: SKIPPED, not measured", vis != null)
        val v = vis!!
        try {
            v.captureSize = Visualizer.getCaptureSizeRange()[1]
            v.scalingMode = Visualizer.SCALING_MODE_AS_PLAYED
            v.enabled = true
            fun lowToHighDb(): Double? {
                val fft = ByteArray(v.captureSize)
                var low = 0.0; var high = 0.0; var frames = 0
                val rate = v.samplingRate / 1000.0 // Hz
                fun bin(hz: Double) = (hz * v.captureSize / rate).toInt().coerceIn(1, v.captureSize / 2 - 1)
                repeat(20) {
                    Thread.sleep(100)
                    if (v.getFft(fft) == Visualizer.SUCCESS) {
                        fun mag(k: Int) = hypot(fft[2 * k].toDouble(), fft[2 * k + 1].toDouble())
                        val l = (bin(60.0) - 1..bin(60.0) + 1).maxOf { mag(it) }
                        val h = (bin(1000.0) - 1..bin(1000.0) + 1).maxOf { mag(it) }
                        if (l + h > 0) { low += l; high += h; frames++ }
                    }
                }
                if (frames == 0 || low == 0.0 || high == 0.0) return null
                return 20 * Math.log10(low / high)
            }
            f.applyAurumPreset(SoundCurve.Preset.FLAT); Thread.sleep(800)
            val flat = lowToHighDb()
            f.applyAurumPreset(SoundCurve.Preset.BASS); Thread.sleep(800)
            val bass = lowToHighDb()
            Log.i(tag, "MEASURED low/high: flat=$flat dB bass=$bass dB")
            assumeTrue("Visualizer captured no audio (silent sink): SKIPPED, not measured", flat != null && bass != null)
            assertTrue("Bass+ must lift 60 Hz vs 1 kHz on the real output (flat=$flat, bass=$bass)", bass!! - flat!! >= 2.0)
        } finally {
            v.release()
        }
    }
}
