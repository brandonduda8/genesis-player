package com.apexforge.genesisplayer.data

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Hi-Fi P1: a precision software parametric EQ, the "sound engineer" core.
 *
 * Android's built-in Equalizer exposes ~5 fixed bands per device, which is far too coarse for headphone
 * correction. This engine runs arbitrary peaking / low-shelf / high-shelf biquads (RBJ Audio-EQ-Cookbook) in
 * float, so a measured headphone correction profile (AutoEq, MIT; measurements by oratory1990) can be applied
 * exactly as published, plus a user "sound signature" on top.
 *
 * Pure Kotlin, no Android imports: pinned by JVM unit tests (ParametricEqTest). Wiring into the Media3 audio
 * pipeline is P2 (see docs/HIFI-DESIGN.md) and is gated by the emulator playback test.
 */
object ParametricEq {

    enum class Type { PK, LSC, HSC }

    data class Filter(val type: Type, val fc: Double, val gainDb: Double, val q: Double)

    data class Profile(val name: String, val preampDb: Double, val filters: List<Filter>)

    /** Normalised biquad coefficients (a0 == 1). */
    data class Coeffs(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double)

    private val FILTER_RE = Regex("""ON\s+(PK|LSC|HSC)\s+Fc\s+([\d.]+)\s+Hz\s+Gain\s+(-?[\d.]+)\s+dB\s+Q\s+([\d.]+)""")
    private val PREAMP_RE = Regex("""Preamp:\s*(-?[\d.]+)\s*dB""")

    /** Parse AutoEq's "ParametricEQ.txt" format. Lines that are OFF or malformed are ignored, never guessed. */
    fun parseAutoEq(name: String, text: String): Profile {
        val pre = PREAMP_RE.find(text)?.groupValues?.get(1)?.toDouble() ?: 0.0
        val filters = FILTER_RE.findAll(text).map { m ->
            val (t, fc, g, q) = m.destructured
            Filter(Type.valueOf(t), fc.toDouble(), g.toDouble(), q.toDouble())
        }.filter { it.fc > 0 && it.q > 0 }.toList()
        return Profile(name, pre, filters)
    }

    fun coeffs(f: Filter, sampleRate: Double): Coeffs {
        val a = 10.0.pow(f.gainDb / 40.0)
        val w0 = 2.0 * PI * f.fc / sampleRate
        val c = cos(w0)
        val alpha = sin(w0) / (2.0 * f.q)
        val s = 2.0 * sqrt(a) * alpha
        val (b, d) = when (f.type) {
            Type.PK -> doubleArrayOf(1 + alpha * a, -2 * c, 1 - alpha * a) to
                doubleArrayOf(1 + alpha / a, -2 * c, 1 - alpha / a)
            Type.LSC -> doubleArrayOf(a * ((a + 1) - (a - 1) * c + s), 2 * a * ((a - 1) - (a + 1) * c),
                a * ((a + 1) - (a - 1) * c - s)) to
                doubleArrayOf((a + 1) + (a - 1) * c + s, -2 * ((a - 1) + (a + 1) * c), (a + 1) + (a - 1) * c - s)
            Type.HSC -> doubleArrayOf(a * ((a + 1) + (a - 1) * c + s), -2 * a * ((a - 1) + (a + 1) * c),
                a * ((a + 1) + (a - 1) * c - s)) to
                doubleArrayOf((a + 1) - (a - 1) * c + s, 2 * ((a - 1) - (a + 1) * c), (a + 1) - (a - 1) * c - s)
        }
        return Coeffs(b[0] / d[0], b[1] / d[0], b[2] / d[0], d[1] / d[0], d[2] / d[0])
    }

    /** |H(f)| in dB for one biquad, evaluated on the unit circle. */
    fun magnitudeDb(c: Coeffs, freq: Double, sampleRate: Double): Double {
        val w = 2.0 * PI * freq / sampleRate
        val cr1 = cos(w); val ci1 = -sin(w); val cr2 = cos(2 * w); val ci2 = -sin(2 * w)
        val nr = c.b0 + c.b1 * cr1 + c.b2 * cr2; val ni = c.b1 * ci1 + c.b2 * ci2
        val dr = 1.0 + c.a1 * cr1 + c.a2 * cr2; val di = c.a1 * ci1 + c.a2 * ci2
        return 10.0 * log10((nr * nr + ni * ni) / (dr * dr + di * di))
    }

    fun responseDb(filters: List<Filter>, freq: Double, sampleRate: Double): Double =
        filters.sumOf { magnitudeDb(coeffs(it, sampleRate), freq, sampleRate) }

    /**
     * Clipping-safe preamp: the negative of the curve's highest boost over 20 Hz..20 kHz (401 log-spaced points),
     * so EQ boosts can never push a full-scale track over 0 dBFS. Never positive.
     */
    fun safePreampDb(filters: List<Filter>, sampleRate: Double): Double {
        var peak = Double.NEGATIVE_INFINITY
        for (i in 0..400) {
            val f = 20.0 * 1000.0.pow(i / 400.0)
            if (f < sampleRate / 2) peak = max(peak, responseDb(filters, f, sampleRate))
        }
        return -max(0.0, peak)
    }

    /**
     * Stateful multi-channel processor (transposed direct form II, double-precision state). Interleaved float
     * samples in, processed in place. One instance per audio stream; reset() on seek/format change.
     */
    class Chain(filters: List<Filter>, sampleRate: Double, private val channels: Int, preampDb: Double) {
        private val cs = filters.map { coeffs(it, sampleRate) }
        private val z1 = Array(cs.size) { DoubleArray(channels) }
        private val z2 = Array(cs.size) { DoubleArray(channels) }
        private val gain = 10.0.pow(preampDb / 20.0)

        fun process(buf: FloatArray, frames: Int = buf.size / channels) {
            for (n in 0 until frames) {
                for (ch in 0 until channels) {
                    val idx = n * channels + ch
                    var x = buf[idx] * gain
                    for (k in cs.indices) {
                        val c = cs[k]
                        val y = c.b0 * x + z1[k][ch]
                        z1[k][ch] = c.b1 * x - c.a1 * y + z2[k][ch]
                        z2[k][ch] = c.b2 * x - c.a2 * y
                        x = y
                    }
                    buf[idx] = x.toFloat()
                }
            }
        }

        fun reset() {
            z1.forEach { it.fill(0.0) }
            z2.forEach { it.fill(0.0) }
        }
    }
}
