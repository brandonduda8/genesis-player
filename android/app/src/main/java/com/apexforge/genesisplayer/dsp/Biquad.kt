package com.apexforge.genesisplayer.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * RBJ "Audio EQ Cookbook" biquad design (peaking, low shelf, high shelf) plus exact response evaluation.
 *
 * Output layout of every coefficient array: `[b0, b1, b2, a1, a2]`, already divided by a0, for the difference
 * equation `y[n] = b0 x[n] + b1 x[n-1] + b2 x[n-2] - a1 y[n-1] - a2 y[n-2]`.
 *
 * Hard guarantees (tested): for ANY input (NaN, +-Inf, negative, absurd sample rate) the result is finite and
 * strictly stable (both poles inside the unit circle). Inputs are sanitised first (NaN -> safe default, then
 * clamped: f0 to [20 Hz, 0.45 fs], Q to [0.3, 10], gain to [-12, +12] dB); if, after that, a filter were ever
 * non-finite or unstable, the identity filter is returned instead.
 *
 * Pure Kotlin, no allocation in [coefficientsInto] / [magnitudeDbAt] (safe for the audio thread).
 */
object BiquadMath {
    const val MIN_F0_HZ = 20.0
    const val MAX_F0_FRACTION_OF_FS = 0.45
    const val MIN_Q = 0.3
    const val MAX_Q = 10.0
    const val MAX_ABS_GAIN_DB = 12.0
    private const val DEFAULT_FS = 48000.0
    private const val DEFAULT_F0 = 1000.0
    private const val DEFAULT_Q = 0.7071067811865476

    fun sanitizeFs(fs: Double): Double = if (!(fs > 0.0)) DEFAULT_FS else fs.coerceIn(1000.0, 1.0e7)

    fun sanitizeFreqHz(f0: Double, fs: Double): Double {
        val hi = max(MIN_F0_HZ, MAX_F0_FRACTION_OF_FS * sanitizeFs(fs))
        return if (f0.isNaN()) DEFAULT_F0.coerceIn(MIN_F0_HZ, hi) else f0.coerceIn(MIN_F0_HZ, hi)
    }

    fun sanitizeGainDb(g: Double): Double = if (g.isNaN()) 0.0 else g.coerceIn(-MAX_ABS_GAIN_DB, MAX_ABS_GAIN_DB)

    fun sanitizeQ(q: Double): Double = if (q.isNaN()) DEFAULT_Q else q.coerceIn(MIN_Q, MAX_Q)

    /** Allocating convenience wrapper around [coefficientsInto]. Returns normalised [b0,b1,b2,a1,a2]. */
    fun coefficients(type: BandType, fs: Double, f0: Double, gainDb: Double, q: Double): DoubleArray {
        val out = DoubleArray(5)
        coefficientsInto(out, 0, type, fs, f0, gainDb, q)
        return out
    }

    /** Allocation-free variant: writes 5 values into [out] starting at [off]. */
    fun coefficientsInto(out: DoubleArray, off: Int, type: BandType, fs0: Double, f00: Double, gain0: Double, q0: Double) {
        val fs = sanitizeFs(fs0)
        val f0 = sanitizeFreqHz(f00, fs)
        val gainDb = sanitizeGainDb(gain0)
        val q = sanitizeQ(q0)

        val w0 = 2.0 * PI * f0 / fs
        val cw = cos(w0)
        val sw = sin(w0)
        val alpha = sw / (2.0 * q)
        val a = 10.0.pow(gainDb / 40.0)

        val b0: Double; val b1: Double; val b2: Double
        val a0: Double; val a1: Double; val a2: Double
        when (type) {
            BandType.PEAK -> {
                b0 = 1.0 + alpha * a; b1 = -2.0 * cw; b2 = 1.0 - alpha * a
                a0 = 1.0 + alpha / a; a1 = -2.0 * cw; a2 = 1.0 - alpha / a
            }
            BandType.LOW_SHELF -> {
                val t = 2.0 * sqrt(a) * alpha
                b0 = a * ((a + 1.0) - (a - 1.0) * cw + t)
                b1 = 2.0 * a * ((a - 1.0) - (a + 1.0) * cw)
                b2 = a * ((a + 1.0) - (a - 1.0) * cw - t)
                a0 = (a + 1.0) + (a - 1.0) * cw + t
                a1 = -2.0 * ((a - 1.0) + (a + 1.0) * cw)
                a2 = (a + 1.0) + (a - 1.0) * cw - t
            }
            BandType.HIGH_SHELF -> {
                val t = 2.0 * sqrt(a) * alpha
                b0 = a * ((a + 1.0) + (a - 1.0) * cw + t)
                b1 = -2.0 * a * ((a - 1.0) + (a + 1.0) * cw)
                b2 = a * ((a + 1.0) + (a - 1.0) * cw - t)
                a0 = (a + 1.0) - (a - 1.0) * cw + t
                a1 = 2.0 * ((a - 1.0) - (a + 1.0) * cw)
                a2 = (a + 1.0) - (a - 1.0) * cw - t
            }
        }
        // true division (not multiply-by-reciprocal) so that b == a normalises to exactly [1, x, y, x, y]
        val nb0 = b0 / a0; val nb1 = b1 / a0; val nb2 = b2 / a0
        val na1 = a1 / a0; val na2 = a2 / a0
        if (finite(nb0) && finite(nb1) && finite(nb2) && finite(na1) && finite(na2) && stable(na1, na2)) {
            out[off] = nb0; out[off + 1] = nb1; out[off + 2] = nb2; out[off + 3] = na1; out[off + 4] = na2
        } else {
            out[off] = 1.0; out[off + 1] = 0.0; out[off + 2] = 0.0; out[off + 3] = 0.0; out[off + 4] = 0.0
        }
    }

    private fun finite(x: Double) = !x.isNaN() && !x.isInfinite()

    /** Jury stability triangle, strict: |a2| < 1 and |a1| < 1 + a2. */
    private fun stable(a1: Double, a2: Double) = abs(a2) < 1.0 && abs(a1) < 1.0 + a2

    /** True when [c] (5 finite values, layout above) has both poles strictly inside the unit circle. */
    fun isStable(c: DoubleArray): Boolean {
        if (c.size < 5) return false
        for (i in 0 until 5) if (!finite(c[i])) return false
        return stable(c[3], c[4])
    }

    /** Exact magnitude response in dB of the biquad [c] at [freqHz] for sample rate [fs]. */
    fun magnitudeDb(c: DoubleArray, fs: Double, freqHz: Double): Double {
        val fsS = sanitizeFs(fs)
        val f = if (freqHz.isNaN()) 0.0 else freqHz.coerceIn(0.0, fsS / 2.0)
        val w = 2.0 * PI * f / fsS
        return magnitudeDbAt(c, 0, cos(w), sin(w), cos(2.0 * w), sin(2.0 * w))
    }

    /** Magnitude in dB given precomputed cos/sin of w and 2w; allocation-free. */
    fun magnitudeDbAt(c: DoubleArray, off: Int, cw: Double, sw: Double, c2w: Double, s2w: Double): Double {
        val b0 = c[off]; val b1 = c[off + 1]; val b2 = c[off + 2]; val a1 = c[off + 3]; val a2 = c[off + 4]
        val nr = b0 + b1 * cw + b2 * c2w
        val ni = -(b1 * sw + b2 * s2w)
        val dr = 1.0 + a1 * cw + a2 * c2w
        val di = -(a1 * sw + a2 * s2w)
        val num = nr * nr + ni * ni
        val den = dr * dr + di * di
        return 10.0 * log10(max(num, 1e-300) / max(den, 1e-300))
    }
}
