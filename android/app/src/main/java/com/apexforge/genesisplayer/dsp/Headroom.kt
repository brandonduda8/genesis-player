package com.apexforge.genesisplayer.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sin

/**
 * Worst-case boost estimation for auto-headroom. Runs on the UI/setup thread only (allocates).
 *
 * The estimate is the peak of the exact magnitude response of the whole cascade (preamp + all enabled
 * bands) on a dense log grid, plus a safety margin. It bounds the gain for any STEADY-STATE SINUSOID.
 * It is not a bound for arbitrary waveforms (the true peak-to-peak gain of a filter is its impulse-response
 * l1 norm, which can exceed the frequency-response peak); the limiter is the safety net for that.
 */
object Headroom {
    const val GRID_POINTS = 4096
    const val GRID_MIN_HZ = 10.0
    const val GRID_MAX_FRACTION_OF_FS = 0.49
    const val SAFETY_MARGIN_DB = 0.3
    private const val PEAK_EPS_DB = 1e-9

    private fun userPreamp(p: EqParams): Double {
        val v = p.preampDb.toDouble()
        return if (v.isNaN()) 0.0 else v.coerceIn(EqParams.PREAMP_MIN_DB.toDouble(), EqParams.PREAMP_MAX_DB.toDouble())
    }

    /**
     * Peak (dB, may be negative) of the static cascade response preamp + enabled bands, ignoring bypass.
     * Grid: [GRID_POINTS] log-spaced points 10 Hz..0.49 fs, plus every band centre and Nyquist.
     */
    fun peakGainDb(p: EqParams, fs0: Double): Double {
        val fs = BiquadMath.sanitizeFs(fs0)
        val n = GRID_POINTS
        val extra = EqParams.BAND_COUNT + 1
        val total = n + extra
        val freqs = DoubleArray(total)
        val hi = GRID_MAX_FRACTION_OF_FS * fs
        val lo = GRID_MIN_HZ
        val lnRatio = ln(hi / lo)
        for (i in 0 until n) freqs[i] = lo * exp(lnRatio * i / (n - 1))
        val coefs = DoubleArray(5 * EqParams.BAND_COUNT)
        val active = BooleanArray(EqParams.BAND_COUNT)
        for (b in 0 until EqParams.BAND_COUNT) {
            val bp = p.bands[b]
            val g = BiquadMath.sanitizeGainDb(bp.gainDb.toDouble())
            val f = BiquadMath.sanitizeFreqHz(bp.freqHz.toDouble(), fs)
            freqs[n + b] = f
            if (bp.enabled && g != 0.0) {
                active[b] = true
                BiquadMath.coefficientsInto(coefs, 5 * b, bp.type, fs, f, g, BiquadMath.sanitizeQ(bp.q.toDouble()))
            }
        }
        freqs[n + EqParams.BAND_COUNT] = fs / 2.0
        val pre = userPreamp(p)
        var peak = -1e30
        for (i in 0 until total) {
            val w = 2.0 * PI * freqs[i] / fs
            val cw = cos(w); val sw = sin(w); val c2 = cos(2.0 * w); val s2 = sin(2.0 * w)
            var sum = pre
            for (b in 0 until EqParams.BAND_COUNT) {
                if (active[b]) sum += BiquadMath.magnitudeDbAt(coefs, 5 * b, cw, sw, c2, s2)
            }
            peak = max(peak, sum)
        }
        return peak
    }

    /**
     * Conservative worst-case positive gain in dB (>= 0) of preamp + enabled bands: the grid peak plus
     * [SAFETY_MARGIN_DB] when that peak is positive. Returns 0 when bypassed.
     */
    fun worstCaseBoostDb(p: EqParams, fs: Double): Double {
        if (p.bypass) return 0.0
        val peak = peakGainDb(p, fs)
        return if (peak > PEAK_EPS_DB) peak + SAFETY_MARGIN_DB else 0.0
    }

    /**
     * Crude upper-bound-style estimate: preamp + sum of positive gains of enabled bands (>= 0). Kept for
     * comparison only: it over-estimates for overlapping bands, and it UNDER-estimates for shelves with high Q
     * (a high-Q shelf overshoots its plateau).
     */
    fun sumOfPositiveGainsDb(p: EqParams): Double {
        if (p.bypass) return 0.0
        var s = userPreamp(p)
        for (bp in p.bands) {
            if (!bp.enabled) continue
            val g = BiquadMath.sanitizeGainDb(bp.gainDb.toDouble())
            if (g > 0.0) s += g
        }
        return max(0.0, s)
    }
}
