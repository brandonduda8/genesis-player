package com.apexforge.genesisplayer.dsp

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EasyMappingTest {
    private val fs = 48000.0

    /** Copy of FourStageEq.PRESETS (FourStageEq.kt imports android.*, so it cannot compile in the JVM runner):
     *  name -> (bassOn, bass steps, low, mid, high). Keep in sync by hand. */
    private val legacy = linkedMapOf(
        "Flat" to intArrayOf(0, 0, 0, 0, 0),
        "Bass Heavy" to intArrayOf(1, 10, 5, -1, 1),
        "Bright" to intArrayOf(0, 0, -1, 1, 7),
        "Vocal" to intArrayOf(0, 0, -2, 6, 3)
    )

    private fun resp(p: EqParams, f: Double): Double {
        var db = 0.0
        for (b in p.bands) if (b.enabled) {
            val c = BiquadMath.coefficients(b.type, fs, b.freqHz.toDouble(), b.gainDb.toDouble(), b.q.toDouble())
            db += BiquadMath.magnitudeDb(c, fs, f)
        }
        return db
    }

    @Test fun allZeroIsExactlyFlat() {
        assertEquals(EqParams(), EasyMapping.toParams(false, 0, 0, 0, 0))
        // bassOn with 0 steps is also flat
        assertEquals(EqParams(), EasyMapping.toParams(true, 0, 0, 0, 0))
        // bass steps are ignored when bassOn is false
        assertEquals(EqParams(), EasyMapping.toParams(false, 12, 0, 0, 0))
    }

    @Test fun bassShelfIsRealDbAndOnlyWhenOn() {
        val on = EasyMapping.toParams(true, 12, 0, 0, 0)
        assertEquals(9f, on.bands[0].gainDb, 1e-6f)
        assertEquals(BandType.LOW_SHELF, on.bands[0].type)
        assertTrue(on.bands[0].freqHz in 55f..70f)
        assertEquals(4.5f, EasyMapping.toParams(true, 6, 0, 0, 0).bands[0].gainDb, 1e-6f)
        assertEquals(0f, EasyMapping.toParams(false, 12, 0, 0, 0).bands[0].gainDb, 0f)
    }

    @Test fun bandLayoutUsesStatedFrequencies() {
        val p = EasyMapping.toParams(false, 0, 4, 4, 4)
        assertEquals(160f, p.bands[1].freqHz, 0f); assertEquals(320f, p.bands[2].freqHz, 0f)
        assertEquals(640f, p.bands[3].freqHz, 0f); assertEquals(1250f, p.bands[4].freqHz, 0f)
        assertEquals(2500f, p.bands[5].freqHz, 0f)
        assertEquals(5000f, p.bands[6].freqHz, 0f); assertEquals(10000f, p.bands[7].freqHz, 0f)
        assertEquals(p.bands[1].gainDb, p.bands[2].gainDb, 0f)
        assertEquals(p.bands[3].gainDb, p.bands[5].gainDb, 0f)
    }

    @Test fun outputAlwaysLegalEvenForWildInputs() {
        for (v in listOf(-99, -12, -1, 0, 1, 12, 99)) for (s in listOf(-5, 0, 12, 50)) {
            val p = EasyMapping.toParams(true, s, v, v, v)
            assertEquals(8, p.bands.size)
            for (b in p.bands) assertTrue(b.gainDb in EqParams.GAIN_MIN_DB..EqParams.GAIN_MAX_DB)
        }
    }

    /** Stage response is checked with the bass stage off; the bass shelf is a separate stage (tested above). */
    @Test fun legacyPresetsStageResponseMatchesStageValue() {
        for ((name, v) in legacy) {
            val p = EasyMapping.toParams(false, 0, v[2], v[3], v[4])
            val stages = listOf(80.0 to v[2], 1000.0 to v[3], 8000.0 to v[4])
            for ((hz, want) in stages) {
                val got = resp(p, hz)
                val tol = maxOf(abs(want) * 0.35, 1.5)
                if (want == 0) assertTrue("$name @$hz got=$got", abs(got) <= 1.5)
                else {
                    assertTrue("$name @$hz sign want=$want got=$got", (got > 0) == (want > 0))
                    assertTrue("$name @$hz want=$want got=$got tol=$tol", abs(got - want) <= tol)
                }
            }
        }
    }

    @Test fun flatLegacyPresetHasFlatResponse() {
        val p = EasyMapping.toParams(false, 0, 0, 0, 0)
        for (f in listOf(40.0, 80.0, 1000.0, 8000.0, 14000.0)) assertEquals(0.0, resp(p, f), 1e-9)
    }

    @Test fun bassHeavyLegacyAddsLowEnd() {
        val v = legacy["Bass Heavy"]!!
        val p = EasyMapping.toParams(true, v[1], v[2], v[3], v[4])
        assertTrue(resp(p, 60.0) > 5.0)
        assertTrue(resp(p, 60.0) > resp(p, 1000.0) + 4.0)
    }

    @Test fun lowIsMonotonicAt160Hz() {
        var prev = Double.NEGATIVE_INFINITY
        for (d in -12..12) {
            val r = resp(EasyMapping.toParams(false, 0, d, 0, 0), 160.0)
            // 1e-3 dB slack: at the +/-12 dB band clamp the stage refinement can jitter ~1e-5 dB (not audible)
            assertTrue("d=$d r=$r prev=$prev", r >= prev - 1e-3)
            prev = r
        }
    }

    @Test fun midAndHighAreMonotonicToo() {
        var pm = Double.NEGATIVE_INFINITY; var ph = Double.NEGATIVE_INFINITY; var pb = Double.NEGATIVE_INFINITY
        for (d in -12..12) {
            val m = resp(EasyMapping.toParams(false, 0, 0, d, 0), 1000.0)
            val h = resp(EasyMapping.toParams(false, 0, 0, 0, d), 8000.0)
            assertTrue(m >= pm); assertTrue(h >= ph); pm = m; ph = h
        }
        for (s in 0..12) {
            val b = resp(EasyMapping.toParams(true, s, 0, 0, 0), 60.0)
            assertTrue(b >= pb); pb = b
        }
    }

    @Test fun describeMentionsDb() {
        assertTrue(EasyMapping.describe(true, 12, 1, 2, 3).contains("9.0 dB"))
    }
}
