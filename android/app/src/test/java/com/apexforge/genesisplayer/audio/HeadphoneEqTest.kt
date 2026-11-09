package com.apexforge.genesisplayer.audio

import com.apexforge.genesisplayer.data.ParametricEq
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

class HeadphoneEqTest {
    private val bassShelf = HeadphoneEq.Profile("t", "Test shelf", HeadphoneEq.Status.MEASURED, 1.0, "test",
        listOf(ParametricEq.Filter(ParametricEq.Type.LSC, 105.0, 6.0, 0.7)))
    private val est = bassShelf.copy(id = "e", label = "Estimated", status = HeadphoneEq.Status.ESTIMATED, defaultStrength = 0.5)

    @Test fun defaultForTheNamedHeadphoneIsOnAtItsOwnStrength() {
        val c = HeadphoneEq.defaultFor(listOf(bassShelf, est), "e")
        assertTrue(c.active)
        assertEquals(0.5, c.strength, 1e-9)
        assertTrue(c.label(), c.label().endsWith("50% · estimated"))
        assertFalse(HeadphoneEq.defaultFor(listOf(bassShelf), "missing").active)
    }

    @Test fun planIsClipSafeAndScaled() {
        val full = HeadphoneEq.plan(HeadphoneEq.Config(true, bassShelf, 1.0), 44100.0)!!
        assertTrue("preamp never positive and covers the +6 dB shelf", full.preampDb <= -5.9 && full.preampDb >= -6.1)
        val half = HeadphoneEq.plan(HeadphoneEq.Config(true, bassShelf, 0.5), 44100.0)!!
        assertEquals(3.0, half.filters.single().gainDb, 1e-9)
        assertNull(HeadphoneEq.plan(HeadphoneEq.Config(false, bassShelf, 1.0), 44100.0))
        assertNull(HeadphoneEq.plan(HeadphoneEq.Config(true, bassShelf, 0.0), 44100.0))
    }

    @Test fun setBumpsGenerationAndClampsStrength() {
        val g = HeadphoneEq.generation
        HeadphoneEq.set(HeadphoneEq.Config(true, bassShelf, 3.0))
        assertEquals(g + 1, HeadphoneEq.generation)
        assertEquals(1.0, HeadphoneEq.config.strength, 1e-9)
        HeadphoneEq.set(HeadphoneEq.Config())
    }

    private fun rms(x: ShortArray, from: Int) = sqrt((from until x.size).sumOf { x[it].toDouble() * x[it] } / (x.size - from))

    @Test fun process16LiftsTheBassRelativeToTheMidsAndNeverWraps() {
        val sr = 44100.0
        val c = HeadphoneEq.Config(true, bassShelf, 1.0)
        val p = HeadphoneEq.plan(c, sr)!!
        fun through(freq: Double, amp: Double): Double {
            val n = 44100
            val s = ShortArray(n) { (amp * sin(2 * PI * freq * it / sr)).toInt().toShort() }
            val before = rms(s, n / 2)
            HeadphoneEq.process16(s, n, ParametricEq.Chain(p.filters, sr, 1, p.preampDb), FloatArray(n))
            return 20 * log10(rms(s, n / 2) / before)
        }
        val low = through(40.0, 20000.0)
        val mid = through(1000.0, 20000.0)
        assertEquals("1 kHz only gets the preamp", p.preampDb, mid, 0.2)
        assertTrue("40 Hz lifted ~6 dB over 1 kHz (low=$low mid=$mid)", low - mid in 5.0..6.5)
        // full-scale bass through a +6 dB shelf: the preamp keeps it in range, so nothing wraps around
        val loud = ShortArray(4410) { (32767 * sin(2 * PI * 40.0 * it / sr)).toInt().toShort() }
        HeadphoneEq.process16(loud, loud.size, ParametricEq.Chain(p.filters, sr, 1, p.preampDb), FloatArray(loud.size))
        assertTrue(loud.all { it > Short.MIN_VALUE })
    }

    @Test fun stereoFramesAreProcessedPerChannel() {
        val p = HeadphoneEq.plan(HeadphoneEq.Config(true, bassShelf, 1.0), 48000.0)!!
        val s = ShortArray(9600) { if (it % 2 == 0) 10000 else 0 } // L = DC-ish square, R = silence
        HeadphoneEq.process16(s, s.size, ParametricEq.Chain(p.filters, 48000.0, 2, p.preampDb), FloatArray(s.size))
        assertTrue("right channel stays silent", (1 until s.size step 2).all { s[it].toInt() == 0 })
    }

    @Test fun formatLabelShowsOnlyWhatTheStreamReports() {
        assertEquals("FLAC · 24-bit · 96 kHz · Lossless",
            FormatLabel.label(FormatLabel.info("audio/flac", 96000, 2, 24, null)))
        assertEquals("FLAC · 16-bit · 44.1 kHz · Lossless",
            FormatLabel.label(FormatLabel.info("audio/flac", 44100, 2, FormatLabel.bitsForPcmEncoding(2), 900_000)))
        assertEquals("ALAC · 48 kHz · Lossless", FormatLabel.label(FormatLabel.info("audio/alac", 48000, 2, null, null)))
        assertEquals("MP3 · 44.1 kHz · 320 kbps", FormatLabel.label(FormatLabel.info("audio/mpeg", 44100, 2, 16, 320_000)))
        assertEquals("Opus · 48 kHz · Mono · 96 kbps", FormatLabel.label(FormatLabel.info("audio/opus", 48000, 1, null, 96_000)))
        assertEquals(24, FormatLabel.bitsForPcmEncoding(0x15))
        assertNull(FormatLabel.bitsForPcmEncoding(-1))
    }
}
