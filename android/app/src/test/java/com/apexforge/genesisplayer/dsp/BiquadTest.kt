package com.apexforge.genesisplayer.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BiquadTest {
    private val types = BandType.values()

    private fun finite(c: DoubleArray) = c.all { !it.isNaN() && !it.isInfinite() }

    /** Simple independent complex number, test-side. */
    private class C(val re: Double, val im: Double) {
        operator fun plus(o: C) = C(re + o.re, im + o.im)
        operator fun times(o: C) = C(re * o.re - im * o.im, re * o.im + im * o.re)
        operator fun times(k: Double) = C(re * k, im * k)
        fun abs() = sqrt(re * re + im * im)
        fun div(o: C): C { val d = o.re * o.re + o.im * o.im; return C((re * o.re + im * o.im) / d, (im * o.re - re * o.im) / d) }
    }

    /** Cookbook transfer function evaluated straight from the published formulas (un-normalised), in dB. */
    private fun cookbookDb(type: BandType, fs: Double, f0: Double, gainDb: Double, q: Double, f: Double): Double {
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2 * PI * f0 / fs
        val alpha = sin(w0) / (2 * q)
        val cw = cos(w0)
        val b: DoubleArray; val aa: DoubleArray
        when (type) {
            BandType.PEAK -> {
                b = doubleArrayOf(1 + alpha * a, -2 * cw, 1 - alpha * a); aa = doubleArrayOf(1 + alpha / a, -2 * cw, 1 - alpha / a)
            }
            BandType.LOW_SHELF -> {
                val s = 2 * sqrt(a) * alpha
                b = doubleArrayOf(a * ((a + 1) - (a - 1) * cw + s), 2 * a * ((a - 1) - (a + 1) * cw), a * ((a + 1) - (a - 1) * cw - s))
                aa = doubleArrayOf((a + 1) + (a - 1) * cw + s, -2 * ((a - 1) + (a + 1) * cw), (a + 1) + (a - 1) * cw - s)
            }
            BandType.HIGH_SHELF -> {
                val s = 2 * sqrt(a) * alpha
                b = doubleArrayOf(a * ((a + 1) + (a - 1) * cw + s), -2 * a * ((a - 1) + (a + 1) * cw), a * ((a + 1) + (a - 1) * cw - s))
                aa = doubleArrayOf((a + 1) - (a - 1) * cw + s, 2 * ((a - 1) - (a + 1) * cw), (a + 1) - (a - 1) * cw - s)
            }
        }
        val w = 2 * PI * f / fs
        val z1 = C(cos(-w), sin(-w)); val z2 = C(cos(-2 * w), sin(-2 * w))
        val num = C(b[0], 0.0) + z1 * b[1] + z2 * b[2]
        val den = C(aa[0], 0.0) + z1 * aa[1] + z2 * aa[2]
        return 20 * log10(num.div(den).abs())
    }

    @Test fun impulseResponseAtEdgeSettingsIsFiniteAndDecays() {
        var count = 0
        for (fs in doubleArrayOf(44100.0, 48000.0, 96000.0)) {
            for (type in types) for (f0 in doubleArrayOf(20.0, 20000.0)) for (q in doubleArrayOf(0.3, 10.0)) for (g in doubleArrayOf(-12.0, 12.0)) {
                val c = BiquadMath.coefficients(type, fs, f0, g, q)
                assertTrue("finite", finite(c))
                assertTrue("stable $type f0=$f0 q=$q g=$g fs=$fs", BiquadMath.isStable(c))
                var z1 = 0.0; var z2 = 0.0
                val n = 600000
                var maxAll = 0.0; var maxTail = 0.0
                for (i in 0 until n) {
                    val x = if (i == 0) 1.0 else 0.0
                    val y = c[0] * x + z1
                    z1 = c[1] * x - c[3] * y + z2
                    z2 = c[2] * x - c[4] * y
                    assertTrue("finite sample", !y.isNaN() && !y.isInfinite())
                    val a = abs(y)
                    if (a > maxAll) maxAll = a
                    if (i >= n - n / 20 && a > maxTail) maxTail = a
                }
                assertTrue("bounded $maxAll", maxAll < 100.0)
                assertTrue("decayed tail $maxTail for $type f0=$f0 q=$q g=$g fs=$fs", maxTail < 1e-4)
                count++
            }
        }
        assertEquals(3 * 3 * 2 * 2 * 2, count)
    }

    @Test fun garbageInputsGiveFiniteStableFilters() {
        val bad = doubleArrayOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -5.0, 0.0, 1e300, -1e300, 1e-300, 7.0)
        var n = 0
        for (type in types) for (fs in bad) for (f0 in bad) for (g in bad) for (q in bad) {
            val c = BiquadMath.coefficients(type, fs, f0, g, q)
            assertTrue("finite", finite(c))
            assertTrue("stable", BiquadMath.isStable(c))
            n++
        }
        assertTrue(n > 6000)
    }

    @Test fun clampsApply() {
        for (type in types) {
            val lo = BiquadMath.coefficients(type, 48000.0, 5.0, 3.0, 1.0)
            val lo2 = BiquadMath.coefficients(type, 48000.0, 20.0, 3.0, 1.0)
            assertEquals(0.0, abs(lo[0] - lo2[0]) + abs(lo[3] - lo2[3]), 0.0)
            val hi = BiquadMath.coefficients(type, 48000.0, 40000.0, 3.0, 1.0)
            val hi2 = BiquadMath.coefficients(type, 48000.0, 0.45 * 48000.0, 3.0, 1.0)
            assertEquals(0.0, abs(hi[0] - hi2[0]) + abs(hi[3] - hi2[3]), 0.0)
            val q1 = BiquadMath.coefficients(type, 48000.0, 1000.0, 3.0, 0.01)
            val q2 = BiquadMath.coefficients(type, 48000.0, 1000.0, 3.0, 0.3)
            assertEquals(0.0, abs(q1[0] - q2[0]) + abs(q1[4] - q2[4]), 0.0)
            val g1 = BiquadMath.coefficients(type, 48000.0, 1000.0, 50.0, 1.0)
            val g2 = BiquadMath.coefficients(type, 48000.0, 1000.0, 12.0, 1.0)
            assertEquals(0.0, abs(g1[0] - g2[0]) + abs(g1[4] - g2[4]), 0.0)
        }
    }

    @Test fun peakingGainAtCentreEqualsGainAndShelfCentreIsHalf() {
        for (fs in doubleArrayOf(44100.0, 48000.0, 96000.0)) for (f0 in doubleArrayOf(40.0, 250.0, 1000.0, 4000.0, 12000.0)) {
            for (g in doubleArrayOf(-12.0, -5.5, 0.7, 6.0, 12.0)) for (q in doubleArrayOf(0.3, 0.707, 2.0, 10.0)) {
                val p = BiquadMath.coefficients(BandType.PEAK, fs, f0, g, q)
                assertEquals("peak centre", g, BiquadMath.magnitudeDb(p, fs, f0), 1e-6)
                val ls = BiquadMath.coefficients(BandType.LOW_SHELF, fs, f0, g, q)
                assertEquals("low shelf centre", g / 2.0, BiquadMath.magnitudeDb(ls, fs, f0), 1e-6)
                val hs = BiquadMath.coefficients(BandType.HIGH_SHELF, fs, f0, g, q)
                assertEquals("high shelf centre", g / 2.0, BiquadMath.magnitudeDb(hs, fs, f0), 1e-6)
            }
        }
    }

    @Test fun shelfPlateausAreExact() {
        val ls = BiquadMath.coefficients(BandType.LOW_SHELF, 48000.0, 500.0, 9.0, 0.8)
        assertEquals(9.0, BiquadMath.magnitudeDb(ls, 48000.0, 0.0), 1e-9)
        assertEquals(0.0, BiquadMath.magnitudeDb(ls, 48000.0, 24000.0), 1e-9)
        val hs = BiquadMath.coefficients(BandType.HIGH_SHELF, 48000.0, 5000.0, -7.0, 0.8)
        assertEquals(-7.0, BiquadMath.magnitudeDb(hs, 48000.0, 24000.0), 1e-9)
        assertEquals(0.0, BiquadMath.magnitudeDb(hs, 48000.0, 0.0), 1e-9)
    }

    @Test fun magnitudeMatchesIndependentCookbookEvaluation() {
        var worst = 0.0
        for (type in types) for (fs in doubleArrayOf(44100.0, 48000.0)) for (f0 in doubleArrayOf(30.0, 300.0, 3000.0, 15000.0)) {
            for (g in doubleArrayOf(-12.0, -3.0, 4.0, 12.0)) for (q in doubleArrayOf(0.3, 1.0, 10.0)) {
                val c = BiquadMath.coefficients(type, fs, f0, g, q)
                var f = 10.0
                while (f < fs / 2) {
                    val d = abs(BiquadMath.magnitudeDb(c, fs, f) - cookbookDb(type, fs, f0, g, q, f))
                    if (d > worst) worst = d
                    f *= 1.37
                }
            }
        }
        assertTrue("max deviation vs independent cookbook $worst dB", worst < 1e-9)
    }

    @Test fun zeroGainIsExactIdentity() {
        for (type in types) for (f0 in doubleArrayOf(20.0, 333.0, 9000.0)) for (q in doubleArrayOf(0.3, 1.0, 10.0)) {
            val c = BiquadMath.coefficients(type, 48000.0, f0, 0.0, q)
            assertEquals(1.0, c[0], 0.0); assertEquals(c[3], c[1], 0.0); assertEquals(c[4], c[2], 0.0)
            assertEquals(0.0, BiquadMath.magnitudeDb(c, 48000.0, 1234.0), 1e-12)
        }
    }

    @Test fun isStableRejectsBadFilters() {
        assertFalse(BiquadMath.isStable(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0)))
        assertFalse(BiquadMath.isStable(doubleArrayOf(1.0, 0.0, 0.0, -2.0, 1.0)))
        assertFalse(BiquadMath.isStable(doubleArrayOf(1.0, 0.0, 0.0, 1.5, 0.2)))
        assertFalse(BiquadMath.isStable(doubleArrayOf(1.0, 0.0, 0.0, Double.NaN, 0.2)))
        assertFalse(BiquadMath.isStable(doubleArrayOf(1.0, 0.0)))
        assertTrue(BiquadMath.isStable(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0)))
    }

    @Test fun coefficientsIntoMatchesCoefficients() {
        val out = DoubleArray(12) { -7.0 }
        BiquadMath.coefficientsInto(out, 4, BandType.HIGH_SHELF, 48000.0, 6000.0, 5.0, 1.3)
        val c = BiquadMath.coefficients(BandType.HIGH_SHELF, 48000.0, 6000.0, 5.0, 1.3)
        for (i in 0 until 5) assertEquals(c[i], out[4 + i], 0.0)
        assertEquals(-7.0, out[3], 0.0); assertEquals(-7.0, out[9], 0.0)
    }
}
