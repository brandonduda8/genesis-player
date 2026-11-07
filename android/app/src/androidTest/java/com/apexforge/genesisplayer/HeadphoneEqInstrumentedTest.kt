package com.apexforge.genesisplayer

import android.Manifest
import android.content.Context
import android.media.audiofx.Visualizer
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.apexforge.genesisplayer.audio.AurumEqProcessor
import com.apexforge.genesisplayer.audio.HeadphoneEq
import com.apexforge.genesisplayer.audio.HeadphoneProfiles
import com.apexforge.genesisplayer.audio.aurumRenderers
import com.apexforge.genesisplayer.data.ParametricEq
import com.apexforge.genesisplayer.data.vault.RoutingDataSource
import com.apexforge.genesisplayer.data.vault.Vault
import com.apexforge.genesisplayer.data.vault.VaultDataSource
import org.junit.After
import org.junit.Assert.assertEquals
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
 * Aurum 2.4 golden, charge 3, measured on the device (not asserted from code):
 *  1. the bundled AutoEq catalog loads, and with no saved choice Brandon's JBL Tune 530BT estimate is ON at 50%;
 *  2. a 60 Hz + 1 kHz tone through Aurum's own renderers (the headphone-correction processor in the audio sink):
 *     a +9 dB low shelf raises 60 Hz relative to 1 kHz on the real output vs correction off. If Visualizer gets no
 *     audio on this device, part 2 is SKIPPED, never passed.
 */
@RunWith(AndroidJUnit4::class)
class HeadphoneEqInstrumentedTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val ins = InstrumentationRegistry.getInstrumentation()
    private var player: ExoPlayer? = null

    @After fun tearDown() {
        ins.runOnMainSync { player?.release() }
        HeadphoneEq.set(HeadphoneEq.Config())
        Vault.remove(ctx, "hp-it"); Vault.awaitIdle()
    }

    @Test fun catalogLoadsAndTheNamedHeadphoneIsTheDefault() {
        ctx.getSharedPreferences("aurum_headphone_eq", Context.MODE_PRIVATE).edit().clear().commit()
        HeadphoneProfiles.load(ctx)
        val ids = HeadphoneProfiles.catalog.map { it.id }
        Log.i("AurumHpTest", "catalog: $ids default=${HeadphoneProfiles.defaultId} cfg=${HeadphoneEq.config.label()}")
        assertTrue("catalog has the 530BT estimate", "jbl_tune_530bt_est" in ids)
        assertTrue("every profile parsed real filters", HeadphoneProfiles.catalog.all { it.filters.size >= 5 })
        val c = HeadphoneEq.defaultFor(HeadphoneProfiles.catalog, HeadphoneProfiles.defaultId)
        assertEquals("jbl_tune_530bt_est", c.profile?.id)
        assertTrue(c.enabled)
        assertEquals(0.5, c.strength, 1e-9)
        assertEquals(HeadphoneEq.Status.ESTIMATED, c.profile?.status)
    }

    /** The Media3 processor itself, fed real direct native-order buffers the way DefaultAudioSink feeds it. */
    private fun throughProcessor(p: AurumEqProcessor, freq: Double, rate: Int = 44_100, ch: Int = 2): Pair<ShortArray, ShortArray> {
        val frames = rate // 1 s
        val input = ShortArray(frames * ch) { i -> (12000 * Math.sin(2 * Math.PI * freq * (i / ch) / rate)).toInt().toShort() }
        val out = ShortArray(input.size)
        var w = 0
        var r = 0
        while (r < input.size) {
            val n = minOf(4096 * ch, input.size - r)
            val buf = ByteBuffer.allocateDirect(n * 2).order(ByteOrder.nativeOrder())
            for (i in 0 until n) buf.putShort(input[r + i])
            buf.flip()
            p.queueInput(buf)
            val o = p.output.order(ByteOrder.nativeOrder())
            while (o.hasRemaining()) out[w++] = o.getShort()
            r += n
        }
        assertEquals("every sample came out", input.size, w)
        return input to out
    }

    private fun rmsDb(x: ShortArray): Double {
        val h = x.size / 2 // skip the filter's settling
        return 10 * Math.log10((h until x.size).sumOf { x[it].toDouble() * x[it] } / (x.size - h))
    }

    @Test fun processorMatchesTheAnalyticResponseOnRealMedia3Buffers() {
        val shelf = HeadphoneEq.Profile("it", "IT shelf", HeadphoneEq.Status.MEASURED, 1.0, "test",
            listOf(ParametricEq.Filter(ParametricEq.Type.LSC, 150.0, 9.0, 0.7)))
        val p = AurumEqProcessor()
        val fmt = androidx.media3.common.audio.AudioProcessor.AudioFormat(44_100, 2, androidx.media3.common.C.ENCODING_PCM_16BIT)
        p.configure(fmt); p.flush()
        assertTrue(p.isActive)
        // off: bit-transparent
        HeadphoneEq.set(HeadphoneEq.Config())
        val (inOff, outOff) = throughProcessor(p, 1000.0)
        assertTrue("correction off is bit-for-bit", inOff.contentEquals(outOff))
        // on: each tone's gain equals the analytic biquad response plus the clip-safe preamp
        HeadphoneEq.set(HeadphoneEq.Config(true, shelf, 1.0))
        val plan = HeadphoneEq.plan(HeadphoneEq.config, 44_100.0)!!
        for (f in listOf(60.0, 1000.0)) {
            p.flush()
            val (i, o) = throughProcessor(p, f)
            val got = rmsDb(o) - rmsDb(i)
            val want = ParametricEq.responseDb(plan.filters, f, 44_100.0) + plan.preampDb
            Log.i("AurumHpTest", "processor gain at $f Hz: measured=$got dB analytic=$want dB")
            assertEquals("gain at $f Hz", want, got, 0.25)
        }
        // mono and float formats are accepted; anything else passes through untouched
        assertTrue(androidx.media3.common.audio.AudioProcessor.AudioFormat(48_000, 1, androidx.media3.common.C.ENCODING_PCM_FLOAT)
            .let { p.configure(it) != androidx.media3.common.audio.AudioProcessor.AudioFormat.NOT_SET })
        assertEquals(androidx.media3.common.audio.AudioProcessor.AudioFormat.NOT_SET,
            p.configure(androidx.media3.common.audio.AudioProcessor.AudioFormat(48_000, 2, androidx.media3.common.C.ENCODING_PCM_24BIT)))
    }

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

    @Test fun lowShelfMeasurablyLiftsTheLowToneThroughAurumsEngine() {
        ins.uiAutomation.grantRuntimePermission(ctx.packageName, Manifest.permission.RECORD_AUDIO)
        HeadphoneEq.set(HeadphoneEq.Config())
        Vault.remove(ctx, "hp-it"); Vault.awaitIdle()
        assertNull(Vault.store(ctx, "hp-it", "Two tone", "Aurum CI", ByteArrayInputStream(twoTone(30))))
        val e = Vault.entry(ctx, "hp-it")!!
        var sid = 0
        ins.runOnMainSync {
            player = ExoPlayer.Builder(ctx, aurumRenderers(ctx))
                .setMediaSourceFactory(DefaultMediaSourceFactory(RoutingDataSource.Factory(ctx))).build()
                .apply { setMediaItem(MediaItem.fromUri(VaultDataSource.uriFor(e.fileId))); prepare(); play() }
        }
        val end = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < end) {
            var pos = 0L
            ins.runOnMainSync { sid = player!!.audioSessionId; pos = player!!.currentPosition }
            if (sid != 0 && pos > 500) break
            Thread.sleep(200)
        }
        assertTrue("audio session", sid != 0)
        val vis = try { Visualizer(sid) } catch (ex: Exception) { null }
        assumeTrue("Visualizer unavailable: SKIPPED, not measured", vis != null)
        val v = vis!!
        try {
            v.captureSize = Visualizer.getCaptureSizeRange()[1]
            v.scalingMode = Visualizer.SCALING_MODE_AS_PLAYED
            v.enabled = true
            fun lowToHighDb(): Double? {
                val fft = ByteArray(v.captureSize)
                var low = 0.0; var high = 0.0; var frames = 0
                val rate = v.samplingRate / 1000.0
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
            Thread.sleep(800)
            val off = lowToHighDb()
            val shelf = HeadphoneEq.Profile("it", "IT shelf", HeadphoneEq.Status.MEASURED, 1.0, "test",
                listOf(ParametricEq.Filter(ParametricEq.Type.LSC, 150.0, 9.0, 0.7)))
            HeadphoneEq.set(HeadphoneEq.Config(true, shelf, 1.0))
            Thread.sleep(1200)
            val on = lowToHighDb()
            Log.i("AurumHpTest", "MEASURED low/high through Aurum's engine: off=$off dB on=$on dB")
            assumeTrue("Visualizer captured no audio (silent sink): SKIPPED, not measured", off != null && on != null)
            // Visualizer is an 8-bit FFT with ~43 Hz bins at 44.1 kHz (60 Hz sits between bins 1 and 2) and the
            // shelf's preamp pushes 1 kHz toward its quantisation floor, so it compresses the difference. This end-
            // to-end check proves direction on the device's real output; the exact size is proven by
            // processorMatchesTheAnalyticResponseOnRealMedia3Buffers below.
            assertTrue("+9 dB low shelf must lift 60 Hz vs 1 kHz on the real output (off=$off, on=$on)",
                on!! - off!! >= 1.5)
        } finally {
            v.release()
        }
    }
}
