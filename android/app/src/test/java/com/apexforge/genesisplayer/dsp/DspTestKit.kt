package com.apexforge.genesisplayer.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** Deterministic helpers shared by the engine-builder tests (WO-AURUM-008). Test-only. */
object DspTestKit {
    const val FS = 48000

    /** Small deterministic LCG, uniform in [-1, 1). */
    class Rng(seed: Long) {
        private var s = seed
        fun next(): Double {
            s = s * 6364136223846793005L + 1442695040888963407L
            return ((s ushr 11).toDouble() / (1L shl 53).toDouble()) * 2.0 - 1.0
        }
        fun uniform(lo: Double, hi: Double) = lo + (next() * 0.5 + 0.5) * (hi - lo)
        fun logUniform(lo: Double, hi: Double) = exp(uniform(ln(lo), ln(hi)))
    }

    fun sine(n: Int, freq: Double, amp: Double, fs: Int = FS, phase: Double = 0.0): FloatArray =
        FloatArray(n) { (amp * sin(2.0 * PI * freq * it / fs + phase)).toFloat() }

    fun noise(n: Int, amp: Double, seed: Long = 1L): FloatArray {
        val r = Rng(seed)
        return FloatArray(n) { (amp * r.next()).toFloat() }
    }

    fun maxAbs(x: FloatArray, from: Int = 0, to: Int = x.size): Float {
        var m = 0f
        for (i in from until to) { val a = abs(x[i]); if (a > m) m = a }
        return m
    }

    fun maxJump(x: FloatArray, from: Int, to: Int = x.size): Double {
        var m = 0.0
        for (i in max(from, 1) until to) m = max(m, abs(x[i].toDouble() - x[i - 1].toDouble()))
        return m
    }

    fun allFinite(x: FloatArray): Boolean { for (v in x) if (v.isNaN() || v.isInfinite()) return false; return true }

    fun bands(vararg edits: Pair<Int, BandParams>): List<BandParams> {
        val l = EqParams.flatBands().toMutableList()
        for ((i, b) in edits) l[i] = b
        return l
    }

    fun peak(freq: Float, gain: Float, q: Float = 1f, enabled: Boolean = true) = BandParams(BandType.PEAK, freq, gain, q, enabled)
    fun lowShelf(freq: Float, gain: Float, q: Float = 0.707f) = BandParams(BandType.LOW_SHELF, freq, gain, q)
    fun highShelf(freq: Float, gain: Float, q: Float = 0.707f) = BandParams(BandType.HIGH_SHELF, freq, gain, q)

    /** Runs [input] (mono or interleaved) through [eq] in chunks of [chunkFrames] frames; returns a new array. */
    fun run(eq: ParametricEq, input: FloatArray, chunkFrames: Int = 1000): FloatArray {
        val out = input.copyOf()
        val ch = eq.channels
        val frames = out.size / ch
        var f = 0
        while (f < frames) {
            val n = minOf(chunkFrames, frames - f)
            eq.process(out, f * ch, n)
            f += n
        }
        return out
    }

    /** Hann-windowed single-bin amplitude estimate of [x] over [from, to) at [freq]. */
    fun amplitude(x: FloatArray, from: Int, to: Int, freq: Double, fs: Int = FS): Double {
        var re = 0.0; var im = 0.0; var ws = 0.0
        val n = to - from
        for (i in from until to) {
            val w = 0.5 - 0.5 * cos(2.0 * PI * (i - from + 0.5) / n)
            val ph = 2.0 * PI * freq * i / fs
            re += w * x[i] * cos(ph); im += w * x[i] * sin(ph); ws += w
        }
        return 2.0 * sqrt(re * re + im * im) / ws
    }

    /** Measured steady-state sine gain in dB through [eq] at [freq] (settle, then analyse). */
    fun measuredGainDb(eq: ParametricEq, freq: Double, amp: Double = 0.1, settleSec: Double = 0.8, anaSec: Double = 0.5): Double {
        val settle = (settleSec * FS).toInt()
        val ana = (anaSec * FS).toInt()
        val x = sine(settle + ana + eq.latencyFrames, freq, amp)
        eq.reset()
        val y = run(eq, x)
        val lag = eq.latencyFrames
        // output sample n corresponds to input sample n - lag; analyse with matching phase
        val shifted = FloatArray(settle + ana)
        for (i in shifted.indices) shifted[i] = y[i + lag]
        val got = amplitude(shifted, settle, settle + ana, freq)
        return 20.0 * log10(got / amp)
    }

    /** Pink-ish noise (Paul Kellet economy filter). */
    fun pink(n: Int, seed: Long): FloatArray {
        val r = Rng(seed)
        var b0 = 0.0; var b1 = 0.0; var b2 = 0.0
        return FloatArray(n) {
            val w = r.next()
            b0 = 0.99765 * b0 + w * 0.0990460
            b1 = 0.96300 * b1 + w * 0.2965164
            b2 = 0.57000 * b2 + w * 1.0526913
            (b0 + b1 + b2 + w * 0.1848).toFloat()
        }
    }

    fun normalizeToPeak(x: FloatArray, peak: Float): FloatArray {
        val m = maxAbs(x)
        if (m == 0f) return x
        // divide in double and clamp so the float result never exceeds [peak]
        return FloatArray(x.size) { val v = (x[it].toDouble() * peak / m).toFloat(); if (v > peak) peak else if (v < -peak) -peak else v }
    }

    /** Sum of several logarithmic sine sweeps (20 Hz..20 kHz) with different durations/phases, peak 1.0. */
    fun sweptSum(n: Int): FloatArray {
        val acc = DoubleArray(n)
        val sweeps = 5
        for (k in 0 until sweeps) {
            val dur = n.toDouble() / (1 + k)       // different sweep rates, repeated k+1 times
            val l = ln(20000.0 / 20.0)
            var phase = 0.3 * k
            for (i in 0 until n) {
                val t = (i % dur.toInt()) / dur
                val f = 20.0 * exp(l * t)
                phase += 2.0 * PI * f / FS
                acc[i] += sin(phase)
            }
        }
        return normalizeToPeak(FloatArray(n) { acc[it].toFloat() }, 1.0f)
    }
}
