package com.apexforge.genesisplayer.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LoudnessTest {
    private val fs = 48000.0

    @Test fun unknownReturnsNull() {
        assertNull(normalizationGainDb(LoudnessInfo.Unknown))
        assertNull(Loudness.normalizationGainDb(LoudnessInfo.Unknown, -14.0, 6.0, 12.0))
    }

    @Test fun cutIsTargetMinusMeasured() {
        val g = normalizationGainDb(LoudnessInfo.MeasuredLufs(-8.0, "test"))
        assertNotNull(g)
        assertEquals(-6.0, g!!, 1e-12)
    }

    @Test fun boostCappedAtZeroByDefault() {
        assertEquals(0.0, normalizationGainDb(LoudnessInfo.MeasuredLufs(-20.0, "t"))!!, 0.0)
    }

    @Test fun boostCapRespected() {
        assertEquals(3.0, normalizationGainDb(LoudnessInfo.MeasuredLufs(-20.0, "t"), -14.0, 3.0)!!, 1e-12)
        assertEquals(2.0, normalizationGainDb(LoudnessInfo.MeasuredLufs(-16.0, "t"), -14.0, 3.0)!!, 1e-12)
    }

    @Test fun cutCapRespected() {
        assertEquals(-12.0, normalizationGainDb(LoudnessInfo.MeasuredLufs(0.0, "t"))!!, 1e-12)
        assertEquals(-5.0, normalizationGainDb(LoudnessInfo.MeasuredLufs(0.0, "t"), -14.0, 0.0, 5.0)!!, 1e-12)
    }

    @Test fun nonFiniteMeasurementGivesNull() {
        assertNull(normalizationGainDb(LoudnessInfo.MeasuredLufs(Double.NaN, "t")))
    }

    @Test fun flatMatchIsExactlyZero() {
        assertEquals(0.0, matchedBypassGainDb(EqParams(), fs), 0.0)
        assertEquals(0.0, matchedBypassGainDb(DspPresets.byName("Reference")!!, 44100.0), 0.0)
    }

    @Test fun bassBoostPresetsGivePositiveMatch() {
        for (n in listOf("FEEL IT", "Trap-Rock/Rage")) {
            val g = matchedBypassGainDb(DspPresets.byName(n)!!, fs)
            assertTrue("$n match=$g", g > 0.0)
        }
    }

    @Test fun matchWithinClampAndScalesWithGain() {
        val small = EasyMapping.toParams(true, 4, 0, 0, 0)
        val big = EasyMapping.toParams(true, 12, 0, 0, 0)
        val a = matchedBypassGainDb(small, fs)
        val b = matchedBypassGainDb(big, fs)
        assertTrue(a > 0.0 && b > a)
        assertTrue(b <= 6.0)
    }

    @Test fun cutsGiveNegativeAndBypassZero() {
        val cut = EasyMapping.toParams(false, 0, -6, -6, -6)
        assertTrue(matchedBypassGainDb(cut, fs) < 0.0)
        assertEquals(0.0, matchedBypassGainDb(DspPresets.byName("FEEL IT")!!.copy(bypass = true), fs), 0.0)
    }

    @Test fun hugeBoostIsClampedToSixDb() {
        val p = EasyMapping.toParams(false, 0, 12, 12, 12)
        assertEquals(6.0, matchedBypassGainDb(p.copy(preampDb = 6f), fs), 0.0)
    }

    @Test fun pinkModelIsEqualEnergyPerOctave() {
        // A +6 dB peak confined to one octave of the 30..16k span (~9 octaves) should add roughly
        // 10*log10(1 + (10^0.6-1)/9) ~ 1 dB at most; check it is well under 1.5 dB and positive.
        val base = EqParams()
        val bands = base.bands.toMutableList()
        bands[3] = bands[3].copy(gainDb = 6f, q = 1.4f)
        val g = matchedBypassGainDb(base.copy(bands = bands), fs)
        assertTrue("g=$g", g > 0.2 && g < 1.5)
    }
}
