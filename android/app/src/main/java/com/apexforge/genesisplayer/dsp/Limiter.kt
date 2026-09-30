package com.apexforge.genesisplayer.dsp

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Lookahead peak limiter with a hard output guarantee: |output| <= ceiling for every sample.
 *
 * Algorithm (per frame n, peak linked across channels):
 *  1. need[n] = min(1, ceiling / peak[n]).
 *  2. t[n] = min(need[n-L..n])           (sliding minimum over the lookahead window, monotonic deque).
 *  3. b[n] = mean(t[n-L..n])             (boxcar of the same length => smooth linear attack; every t in the
 *                                          mean is <= need[n-L], hence b[n] <= need[n-L]).
 *  4. s[n] = min(b[n], release(s[n-1]))  (exponential release, time constant 80 ms; 20 ms when [enabled] is false).
 *  5. out[n] = in[n-L] * s[n]            (input delayed by L = lookahead frames).
 * Because s[n] <= need[n-L], the delayed sample is always attenuated enough. A final clamp to +-ceiling (the
 * largest float <= the ceiling) makes the guarantee immune to rounding; it never engages in normal operation.
 *
 * Exact unity path: when no sample in the window exceeds the ceiling, b == 1.0 exactly (tracked by a counter,
 * not by float sums) and once release is within 1e-7 of unity it snaps to exactly 1.0; samples are then
 * passed bit-exact (delay only).
 *
 * Input sanitising: NaN -> 0, +-Inf -> +-Float.MAX_VALUE (then limited).
 * Latency is fixed: [latencyFrames] (about 1.5 ms). No allocation, locks or boxing in [process].
 * [enabled] = false turns detection off (gain releases to 1, no clamp) but KEEPS the delay, so latency is constant.
 */
class Limiter(sampleRate: Int, val channels: Int, ceilingDb: Double = -0.3) {
    val latencyFrames: Int = max(1, Math.round(LOOKAHEAD_SEC * sampleRate).toInt())

    /** Linear ceiling as the largest float not above 10^(ceilingDb/20). */
    val ceilingLinear: Float

    /** When false: no limiting, gain releases to exactly 1 (fast), no clamp. Delay is kept. */
    var enabled: Boolean = true

    /** Max gain reduction in dB (>= 0) during the most recent [process] call. */
    @Volatile var gainReductionDb: Double = 0.0
        private set

    private val ceil: Double
    private val relCoefOn: Double
    private val relCoefOff: Double
    private val window = latencyFrames + 1
    private val delay = FloatArray(latencyFrames * channels)
    private var delayPos = 0
    private val tRing = DoubleArray(window)
    private var tPos = 0
    private var tSum = window.toDouble()
    private var nonUnity = 0
    private val dqVal = DoubleArray(window + 1)
    private val dqIdx = LongArray(window + 1)
    private var dqHead = 0
    private var dqSize = 0
    private var frameIndex = 0L
    private var gain = 1.0

    init {
        require(sampleRate > 0 && channels >= 1)
        val c = 10.0.pow((if (ceilingDb.isNaN()) -0.3 else ceilingDb.coerceIn(-60.0, 0.0)) / 20.0)
        var cf = c.toFloat()
        if (cf.toDouble() > c) cf = Float.fromBits(cf.toRawBits() - 1)
        ceilingLinear = cf
        ceil = cf.toDouble()
        relCoefOn = 1.0 - exp(-1.0 / (RELEASE_SEC * sampleRate))
        relCoefOff = 1.0 - exp(-1.0 / (RELEASE_OFF_SEC * sampleRate))
        reset()
    }

    fun reset() {
        java.util.Arrays.fill(delay, 0f)
        java.util.Arrays.fill(tRing, 1.0)
        delayPos = 0; tPos = 0; tSum = window.toDouble(); nonUnity = 0
        dqHead = 0; dqSize = 0; frameIndex = 0L; gain = 1.0
        gainReductionDb = 0.0
    }

    /** Processes [frames] interleaved frames in place starting at sample index [offsetSamples]. */
    fun process(buf: FloatArray, offsetSamples: Int, frames: Int) {
        val ch = channels
        val lim = enabled
        val relCoef = if (lim) relCoefOn else relCoefOff
        val cap = dqVal.size
        var minGain = 1.0
        var idx = offsetSamples
        for (f in 0 until frames) {
            // 1. linked peak (sanitised)
            var peak = 0.0f
            for (c in 0 until ch) {
                var x = buf[idx + c]
                if (x != x) { x = 0f; buf[idx + c] = 0f }
                else if (x > Float.MAX_VALUE) { x = Float.MAX_VALUE; buf[idx + c] = x }
                else if (x < -Float.MAX_VALUE) { x = -Float.MAX_VALUE; buf[idx + c] = x }
                val ax = abs(x)
                if (ax > peak) peak = ax
            }
            val need = if (lim && peak > ceilingLinear) ceil / peak.toDouble() else 1.0

            // 2. sliding minimum over need[n-L..n]
            while (dqSize > 0) {
                val tail = (dqHead + dqSize - 1) % cap
                if (dqVal[tail] >= need) dqSize-- else break
            }
            if (need < 1.0 || dqSize > 0) {
                dqVal[(dqHead + dqSize) % cap] = need
                dqIdx[(dqHead + dqSize) % cap] = frameIndex
                dqSize++
            }
            while (dqSize > 0 && dqIdx[dqHead] < frameIndex - latencyFrames) {
                dqHead = (dqHead + 1) % cap; dqSize--
            }
            val t = if (dqSize > 0) dqVal[dqHead] else 1.0

            // 3. boxcar over t[n-L..n]
            val old = tRing[tPos]
            tRing[tPos] = t
            tPos++; if (tPos == window) tPos = 0
            if (old < 1.0) nonUnity--
            if (t < 1.0) nonUnity++
            val b: Double
            if (nonUnity == 0) { tSum = window.toDouble(); b = 1.0 } else { tSum += t - old; b = tSum / window }

            // 4. gain: attack through b, exponential release
            var r = gain + (1.0 - gain) * relCoef
            if (r > 1.0 - 1e-7) r = 1.0
            gain = if (b < r) b else r
            if (gain < minGain) minGain = gain

            // 5. delayed output
            val base = delayPos * ch
            if (gain == 1.0) {
                for (c in 0 until ch) {
                    val d = delay[base + c]
                    delay[base + c] = buf[idx + c]
                    buf[idx + c] = d
                }
            } else {
                for (c in 0 until ch) {
                    val d = delay[base + c]
                    delay[base + c] = buf[idx + c]
                    var v = (d.toDouble() * gain).toFloat()
                    if (lim) { if (v > ceilingLinear) v = ceilingLinear else if (v < -ceilingLinear) v = -ceilingLinear }
                    buf[idx + c] = v
                }
            }
            delayPos++; if (delayPos == latencyFrames) delayPos = 0
            frameIndex++
            idx += ch
        }
        gainReductionDb = if (minGain >= 1.0) 0.0 else -20.0 * log10(minGain)
    }

    companion object {
        const val LOOKAHEAD_SEC = 0.0015
        const val RELEASE_SEC = 0.080
        const val RELEASE_OFF_SEC = 0.020
    }
}
