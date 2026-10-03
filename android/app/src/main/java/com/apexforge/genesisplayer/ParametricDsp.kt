package com.apexforge.genesisplayer

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * WO-AURUM-008 — "FEEL THE MUSIC" parametric DSP core.
 *
 * Pure-Kotlin, zero Android dependencies: every function here is a pure JVM
 * unit-test candidate (see ParametricDspTest). The Android app wires it in via
 * the Easy/Advanced EQ panels; a Media3 AudioProcessor adapter is a
 * device-gated follow-up (documented in DSP_EVIDENCE.md, NOT built here).
 *
 * Design notes (honest engineering, no magic):
 * - Filters are RBJ Audio-EQ-Cookbook biquads (peaking, low shelf, high
 *   shelf), the industry-standard parametric building block. Coefficients are
 *   clamped before use and checked finite — edge settings can never produce
 *   NaN/unstable filters.
 * - Coefficient smoothing: when a parameter changes, coefficients lerp toward
 *   the target with a fixed time constant, so slider moves never click/pop.
 * - Headroom: worst-case positive gain is measured on a log frequency grid
 *   (preamp + full cascade). If it exceeds 0 dBFS, the preamp is auto-reduced
 *   by exactly the excess (conservative, deterministic). A transparent
 *   tanh soft-limiter at -1 dBFS is the final peak guard. No preset can clip
 *   a 0 dBFS stress signal — the stress tests pin this.
 * - Loudness honesty: we NEVER fake ReplayGain/EBU-R128. The only
 *   "normalization" offered is volume-matched A/B trim computed from the
 *   EQ's own measured broadband response, clearly labeled as such.
 */
object DspMath {
    /** Normalized biquad coefficients [b0, b1, b2, a1, a2] (a0 = 1). */
    data class Coeffs(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double) {
        fun allFinite(): Boolean =
            listOf(b0, b1, b2, a1, a2).all { it.isFinite() }

        fun asArray(): DoubleArray = doubleArrayOf(b0, b1, b2, a1, a2)
    }

    val IDENTITY = Coeffs(1.0, 0.0, 0.0, 0.0, 0.0)

    fun clampFreq(freqHz: Double, sampleRate: Int): Double =
        freqHz.coerceIn(1.0, sampleRate * 0.49)

    fun clampQ(q: Double): Double = q.coerceIn(0.1, 18.0)

    fun clampGainDb(gainDb: Double): Double = gainDb.coerceIn(-15.0, 15.0)

    /** RBJ peaking EQ. */
    fun peaking(freqHz: Double, q: Double, gainDb: Double, sampleRate: Int): Coeffs {
        val f = clampFreq(freqHz, sampleRate)
        val qq = clampQ(q)
        val g = clampGainDb(gainDb)
        val a = 10.0.pow(g / 40.0)
        val w0 = 2.0 * Math.PI * f / sampleRate
        val alpha = sin(w0) / (2.0 * qq)
        val cw = cos(w0)
        val b0 = 1.0 + alpha * a
        val b1 = -2.0 * cw
        val b2 = 1.0 - alpha * a
        val a0 = 1.0 + alpha / a
        val a1 = -2.0 * cw
        val a2 = 1.0 - alpha / a
        return normalize(b0, b1, b2, a0, a1, a2)
    }

    /** RBJ low shelf (S = 1 slope). */
    fun lowShelf(freqHz: Double, gainDb: Double, sampleRate: Int, q: Double = 0.7071): Coeffs {
        val f = clampFreq(freqHz, sampleRate)
        val g = clampGainDb(gainDb)
        val a = 10.0.pow(g / 40.0)
        val w0 = 2.0 * Math.PI * f / sampleRate
        val alpha = sin(w0) / 2.0 * sqrt((a + 1.0 / a) * (1.0 / clampQ(q) - 1.0) + 2.0)
        val cw = cos(w0)
        val sqA = sqrt(a)
        val b0 = a * ((a + 1.0) - (a - 1.0) * cw + 2.0 * sqA * alpha)
        val b1 = 2.0 * a * ((a - 1.0) - (a + 1.0) * cw)
        val b2 = a * ((a + 1.0) - (a - 1.0) * cw - 2.0 * sqA * alpha)
        val a0 = (a + 1.0) + (a - 1.0) * cw + 2.0 * sqA * alpha
        val a1 = -2.0 * ((a - 1.0) + (a + 1.0) * cw)
        val a2 = (a + 1.0) + (a - 1.0) * cw - 2.0 * sqA * alpha
        return normalize(b0, b1, b2, a0, a1, a2)
    }

    /** RBJ high shelf (S = 1 slope). */
    fun highShelf(freqHz: Double, gainDb: Double, sampleRate: Int, q: Double = 0.7071): Coeffs {
        val f = clampFreq(freqHz, sampleRate)
        val g = clampGainDb(gainDb)
        val a = 10.0.pow(g / 40.0)
        val w0 = 2.0 * Math.PI * f / sampleRate
        val alpha = sin(w0) / 2.0 * sqrt((a + 1.0 / a) * (1.0 / clampQ(q) - 1.0) + 2.0)
        val cw = cos(w0)
        val sqA = sqrt(a)
        val b0 = a * ((a + 1.0) + (a - 1.0) * cw + 2.0 * sqA * alpha)
        val b1 = -2.0 * a * ((a - 1.0) + (a + 1.0) * cw)
        val b2 = a * ((a + 1.0) + (a - 1.0) * cw - 2.0 * sqA * alpha)
        val a0 = (a + 1.0) - (a - 1.0) * cw + 2.0 * sqA * alpha
        val a1 = 2.0 * ((a - 1.0) - (a + 1.0) * cw)
        val a2 = (a + 1.0) - (a - 1.0) * cw - 2.0 * sqA * alpha
        return normalize(b0, b1, b2, a0, a1, a2)
    }

    private fun normalize(b0: Double, b1: Double, b2: Double, a0: Double, a1: Double, a2: Double): Coeffs {
        if (!a0.isFinite() || a0 == 0.0) return IDENTITY
        val c = Coeffs(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
        return if (c.allFinite()) c else IDENTITY
    }

    /** |H(e^{jw})| in dB for a coefficient set. Pure function — test pin. */
    fun magnitudeDb(c: Coeffs, freqHz: Double, sampleRate: Int): Double {
        val w = 2.0 * Math.PI * clampFreq(freqHz, sampleRate) / sampleRate
        val cw = cos(w)
        val sw = sin(w)
        // num = b0 + b1 e^-jw + b2 e^-2jw ; den = 1 + a1 e^-jw + a2 e^-2jw
        val nr = c.b0 + c.b1 * cw + c.b2 * cos(2 * w)
        val ni = -(c.b1 * sw + c.b2 * sin(2 * w))
        val dr = 1.0 + c.a1 * cw + c.a2 * cos(2 * w)
        val di = -(c.a1 * sw + c.a2 * sin(2 * w))
        val mag = sqrt(nr * nr + ni * ni) / sqrt(dr * dr + di * di)
        return 20.0 * log10(max(mag, 1e-12))
    }
}

/** One biquad section with coefficient smoothing (no clicks on slider moves). */
class BiquadFilter(val sampleRate: Int) {
    private var cur: DspMath.Coeffs = DspMath.IDENTITY
    private var tgt: DspMath.Coeffs = DspMath.IDENTITY

    /** Smoothing time constant in seconds (default 20 ms). */
    var smoothTauSec: Double = 0.02

    private var x1 = 0.0
    private var x2 = 0.0
    private var y1 = 0.0
    private var y2 = 0.0

    /** False while coefficients are still slewing toward the target. */
    private var settled = true

    /** Lerp factor applied per sample toward the target coefficients. */
    fun smoothAlpha(): Double = 1.0 - exp(-1.0 / (smoothTauSec * sampleRate))

    fun setTarget(c: DspMath.Coeffs) {
        tgt = if (c.allFinite()) c else DspMath.IDENTITY
        settled = false
    }

    /** Jump coefficients to target instantly (preset loads, init). */
    fun snap() {
        cur = tgt
        settled = true
    }

    fun currentCoeffs(): DspMath.Coeffs = cur

    fun targetCoeffs(): DspMath.Coeffs = tgt

    /** Max per-coefficient distance from target (convergence probe for tests). */
    fun maxCoeffError(): Double {
        val a = cur.asArray()
        val b = tgt.asArray()
        var m = 0.0
        for (i in a.indices) m = max(m, abs(a[i] - b[i]))
        return m
    }

    fun process(x: Double): Double {
        if (!settled) {
            val alpha = smoothAlpha()
            val a = cur.asArray()
            val b = tgt.asArray()
            var err = 0.0
            val n = DoubleArray(5) { i ->
                val v = a[i] + (b[i] - a[i]) * alpha
                err = max(err, abs(v - b[i]))
                v
            }
            cur = if (n.all { it.isFinite() }) {
                DspMath.Coeffs(n[0], n[1], n[2], n[3], n[4])
            } else {
                tgt
            }
            if (err < 1e-12) {
                cur = tgt
                settled = true
            }
        }
        val c = cur
        val y = c.b0 * x + c.b1 * x1 + c.b2 * x2 - c.a1 * y1 - c.a2 * y2
        val out = if (y.isFinite()) y else 0.0
        x2 = x1; x1 = x
        y2 = y1; y1 = out
        return out
    }

    fun reset() {
        x1 = 0.0; x2 = 0.0; y1 = 0.0; y2 = 0.0
    }
}

enum class BandType { LOW_SHELF, PEAKING, HIGH_SHELF }

/** One parametric band. Values are real dB / Hz / Q — never fake labels. */
data class EqBand(
    val type: BandType,
    var freqHz: Double,
    var gainDb: Double,
    var q: Double,
    var bypass: Boolean = false
) {
    fun coeffs(sampleRate: Int): DspMath.Coeffs {
        if (bypass) return DspMath.IDENTITY
        return when (type) {
            BandType.LOW_SHELF -> DspMath.lowShelf(freqHz, gainDb, sampleRate, q)
            BandType.PEAKING -> DspMath.peaking(freqHz, q, gainDb, sampleRate)
            BandType.HIGH_SHELF -> DspMath.highShelf(freqHz, gainDb, sampleRate, q)
        }
    }
}

/**
 * The full parametric EQ: preamp (-12..+6 dB) -> 8 bands
 * (low shelf, 6 peaking, high shelf) -> auto headroom -> soft limiter.
 */
class ParametricEq(val sampleRate: Int = 48000) {
    companion object {
        const val PREAMP_MIN_DB = -12.0
        const val PREAMP_MAX_DB = 6.0

        /** Fixed slot layout: 0 = low shelf, 1..6 = peaking, 7 = high shelf. */
        const val SLOT_LOW_SHELF = 0
        const val SLOT_HIGH_SHELF = 7
        const val BAND_COUNT = 8

        /** Peak-guard threshold: -1 dBFS. The limiter is transparent below it. */
        const val LIMITER_THRESHOLD_DB = -1.0

        /** Worst-case response is measured on this log grid (Hz). */
        fun responseGrid(): DoubleArray {
            val pts = mutableListOf<Double>()
            var f = 20.0
            while (f <= 20000.0) {
                pts.add(f)
                f *= 10.0.pow(1.0 / 24.0) // 1/24-octave steps
            }
            return pts.toDoubleArray()
        }
    }

    var preampDb: Double = 0.0
        set(v) { field = v.coerceIn(PREAMP_MIN_DB, PREAMP_MAX_DB) }

    var bypassAll: Boolean = false

    val bands: MutableList<EqBand> = mutableListOf(
        EqBand(BandType.LOW_SHELF, 80.0, 0.0, 0.7071),
        EqBand(BandType.PEAKING, 120.0, 0.0, 1.0),
        EqBand(BandType.PEAKING, 300.0, 0.0, 1.0),
        EqBand(BandType.PEAKING, 800.0, 0.0, 1.0),
        EqBand(BandType.PEAKING, 2000.0, 0.0, 1.0),
        EqBand(BandType.PEAKING, 4500.0, 0.0, 1.0),
        EqBand(BandType.PEAKING, 8000.0, 0.0, 1.0),
        EqBand(BandType.HIGH_SHELF, 10000.0, 0.0, 0.7071)
    )

    private val filters: List<BiquadFilter> = List(BAND_COUNT) { BiquadFilter(sampleRate) }

    /** True when the last processed block had protection engaged (preamp auto-cut or limiter). */
    var protectionActive: Boolean = false
        private set

    /** True when the limiter actually engaged on the last block (subset of protectionActive). */
    var limiterEngagedLastBlock: Boolean = false
        private set

    private val limiterThresholdLinear: Double = 10.0.pow(LIMITER_THRESHOLD_DB / 20.0)

    /** Retarget all filters to current band params; snap=true jumps (preset load). */
    fun retarget(snap: Boolean = false) {
        filters.forEachIndexed { i, f -> f.setTarget(bands[i].coeffs(sampleRate)) }
        if (snap) filters.forEach { it.snap() }
    }

    fun snap() = filters.forEach { it.snap() }

    fun resetState() = filters.forEach { it.reset() }

    /** Full-chain response in dB at one frequency (preamp + cascade). Bypass-aware. */
    fun responseDb(freqHz: Double): Double {
        if (bypassAll) return 0.0
        var db = effectivePreampDb()
        filters.forEachIndexed { i, _ ->
            db += DspMath.magnitudeDb(bands[i].coeffs(sampleRate), freqHz, sampleRate)
        }
        return db
    }

    /** Conservative worst-case positive gain over the grid, INCLUDING preamp. */
    fun worstCaseGainDb(): Double {
        if (bypassAll) return 0.0
        var worst = Double.NEGATIVE_INFINITY
        for (f in responseGrid()) worst = max(worst, responseDbRaw(f))
        return worst
    }

    /** Same as responseDb but uses the REQUESTED preamp (for headroom math). */
    private fun responseDbRaw(freqHz: Double): Double {
        var db = preampDb
        filters.forEachIndexed { i, _ ->
            db += DspMath.magnitudeDb(bands[i].coeffs(sampleRate), freqHz, sampleRate)
        }
        return db
    }

    /**
     * Auto headroom: if worst-case gain exceeds 0 dBFS, cut the preamp by
     * exactly the excess. Deterministic and conservative.
     */
    fun effectivePreampDb(): Double {
        if (bypassAll) return 0.0
        val worst = worstCaseGainDb()
        return if (worst > 0.0) preampDb - worst else preampDb
    }

    /** Mean response over the grid — the basis for honest volume-matched A/B. */
    fun broadbandMeanDb(): Double {
        if (bypassAll) return 0.0
        val grid = responseGrid()
        var sum = 0.0
        for (f in grid) sum += responseDb(f)
        return sum / grid.size
    }

    /**
     * A/B trust trim (dB): gain to apply to the BYPASSED path so A/B
     * comparisons are level-matched instead of "louder = better".
     *
     * This is attenuation (<= ~0 dB in practice), computed from the engaged
     * chain's own measured broadband mean — NOT a fake loudness standard.
     * Attenuating the bypass path is the honest design: boosting the engaged
     * path to match bypass is futile, because the headroom auto-cut would
     * eat exactly that boost (the engaged mean is pinned at
     * filterMean - filterWorst whenever the cut is active). Attenuation can
     * never clip; a boost into the ceiling could.
     */
    fun abBypassTrimDb(): Double = broadbandMeanDb()

    /** Transparent tanh soft limiter: identity below threshold, asymptotes to 1.0. */
    fun limitSample(x: Double): Double {
        val t = limiterThresholdLinear
        val ax = abs(x)
        if (ax <= t) return x
        return sign(x) * (t + (1.0 - t) * tanh((ax - t) / (1.0 - t)))
    }

    /**
     * Process one mono block. Applies: preamp (effective, headroom-adjusted) ->
     * 8 biquads -> soft limiter. Returns a NEW array; input is untouched.
     */
    fun processBlock(input: FloatArray): FloatArray {
        val out = FloatArray(input.size)
        if (bypassAll || input.isEmpty()) {
            input.copyInto(out)
            protectionActive = false
            limiterEngagedLastBlock = false
            return out
        }
        val effPre = effectivePreampDb()
        val pre = 10.0.pow(effPre / 20.0)
        val preampCut = effPre < preampDb - 1e-9
        var limited = false
        for (n in input.indices) {
            var s = input[n].toDouble() * pre
            for (f in filters) s = f.process(s)
            val l = limitSample(s)
            if (l != s) limited = true
            out[n] = l.toFloat()
        }
        limiterEngagedLastBlock = limited
        protectionActive = preampCut || limited
        return out
    }

    /** Impulse response of the full chain (deterministic stability probe). */
    fun impulseResponse(n: Int): DoubleArray {
        resetState()
        snap()
        val out = DoubleArray(n)
        val pre = 10.0.pow(effectivePreampDb() / 20.0)
        for (i in 0 until n) {
            var s = (if (i == 0) 1.0 else 0.0) * pre
            for (f in filters) s = f.process(s)
            out[i] = s
        }
        resetState()
        return out
    }

    /** Apply a preset definition (snaps coefficients — no sweep on load). */
    fun applyPreset(p: DspPreset) {
        preampDb = p.preampDb
        p.bands.forEachIndexed { i, b -> bands[i] = b.copy() }
        bypassAll = false
        resetState()
        retarget(snap = true)
    }

    /** Reset to true flat (the A/B trust anchor). */
    fun resetFlat() {
        preampDb = 0.0
        bands.forEach { it.gainDb = 0.0; it.bypass = false }
        bypassAll = false
        resetState()
        retarget(snap = true)
    }
}

/** A preset is published data: every value visible in code and tests. */
data class DspPreset(
    val name: String,
    val preampDb: Double,
    val bands: List<EqBand> // exactly 8, slot order per ParametricEq
) {
    init {
        require(bands.size == ParametricEq.BAND_COUNT) { "preset needs ${ParametricEq.BAND_COUNT} bands" }
    }
}

private fun pk(freqHz: Double, gainDb: Double, q: Double = 1.0) =
    EqBand(BandType.PEAKING, freqHz, gainDb, q)

private fun ls(freqHz: Double, gainDb: Double) =
    EqBand(BandType.LOW_SHELF, freqHz, gainDb, 0.7071)

private fun hs(freqHz: Double, gainDb: Double) =
    EqBand(BandType.HIGH_SHELF, freqHz, gainDb, 0.7071)

/**
 * The six starting-point presets. Values are published here AND pinned by
 * tests — no magic labels, no hidden curves.
 */
object DspPresets {
    val REFERENCE_FLAT = DspPreset(
        "Reference / Flat", 0.0, listOf(
            ls(80.0, 0.0),
            pk(120.0, 0.0), pk(300.0, 0.0), pk(800.0, 0.0),
            pk(2000.0, 0.0), pk(4500.0, 0.0), pk(8000.0, 0.0),
            hs(10000.0, 0.0)
        )
    )

    /** Clean sub + punch. Preamp ships at the measured headroom point
     *  (-(worst-case filter gain) - 0.5 dB margin) so protection is armed
     *  but not engaged; pushing preamp hotter engages the auto-cut. */
    val FEEL_IT = DspPreset(
        "FEEL IT", -4.5, listOf(
            ls(60.0, 4.0),
            pk(120.0, 3.0, 1.0), pk(280.0, -2.0, 1.2), pk(1000.0, 0.0, 1.0),
            pk(3000.0, 1.5, 1.0), pk(6000.0, 1.0, 1.0), pk(9000.0, 0.5, 1.0),
            hs(10000.0, 2.0)
        )
    )

    val NIGHT_DRIVE = DspPreset(
        "Night Drive", -5.5, listOf(
            ls(70.0, 5.0),
            pk(130.0, 3.5, 1.0), pk(300.0, -3.0, 1.2), pk(900.0, -0.5, 1.0),
            pk(2500.0, 2.0, 1.0), pk(5500.0, 0.5, 1.0), pk(8500.0, -0.5, 1.0),
            hs(9000.0, -1.0)
        )
    )

    val EMO_VOCAL = DspPreset(
        "Emo / Vocal", -4.5, listOf(
            ls(80.0, 2.0),
            pk(200.0, -2.0, 1.0), pk(1200.0, 2.5, 1.1), pk(3500.0, 3.0, 1.0),
            pk(500.0, -0.5, 1.0), pk(7000.0, 1.5, 1.0), pk(10000.0, 0.5, 1.0),
            hs(12000.0, 1.0)
        )
    )

    val TRAP_ROCK_RAGE = DspPreset(
        "Trap-Rock / Rage", -6.5, listOf(
            ls(55.0, 6.0),
            pk(110.0, 4.0, 1.0), pk(250.0, -2.5, 1.2), pk(900.0, -1.0, 1.0),
            pk(3000.0, 2.5, 1.0), pk(5500.0, 3.0, 1.0), pk(8000.0, 1.5, 1.0),
            hs(10000.0, 2.5)
        )
    )

    val DARK_CINEMATIC = DspPreset(
        "Dark Cinematic", -6.0, listOf(
            ls(45.0, 6.0),
            pk(100.0, 3.0, 1.0), pk(220.0, -1.5, 1.2), pk(800.0, -1.0, 1.0),
            pk(2000.0, 1.0, 1.0), pk(6000.0, 0.5, 1.0), pk(9000.0, 0.0, 1.0),
            hs(12000.0, -2.0)
        )
    )

    val ALL: List<DspPreset> = listOf(
        REFERENCE_FLAT, FEEL_IT, NIGHT_DRIVE, EMO_VOCAL, TRAP_ROCK_RAGE, DARK_CINEMATIC
    )

    fun byName(name: String): DspPreset? = ALL.find { it.name == name }
}

/**
 * EASY mode: the existing 4-stage UI ([FourStageEq.State]) mapped onto the
 * richer parametric engine. The old android.media.audiofx path is untouched;
 * this is the parametric equivalent used by the Advanced engine when the
 * user picks Easy mode there.
 *
 * Mapping (documented, pinned by tests):
 * - Low stage dB  -> low shelf @80 Hz (gain = low)
 * - Bass boost steps (0..12, BassBoost ON) -> peaking @55 Hz, gain = steps*0.5 dB
 *   (this IS a dB number because it is a real parametric boost, unlike the
 *   system BassBoost strength which has no dB scale — the UI must label which
 *   path is active)
 * - Low stage also feeds punch @130 Hz (gain = low*0.5) and mud @280 Hz (gain = low*0.25)
 * - Mid stage dB  -> peaking @1000 Hz (gain = mid), @3200 Hz (gain = mid*0.5)
 * - High stage dB -> peaking @7000 Hz (gain = high*0.5), high shelf @10 kHz (gain = high)
 * - Preamp 0 dB in Easy mode.
 */
object EasyDspMapper {
    fun map(s: FourStageEq.State): DspPreset {
        val bassGain = if (s.bassOn) s.bass.coerceIn(0, 12) * 0.5 else 0.0
        val low = s.low.toDouble()
        val mid = s.mid.toDouble()
        val high = s.high.toDouble()
        return DspPreset(
            "Easy: ${s.preset}", 0.0, listOf(
                ls(80.0, low),
                pk(55.0, bassGain, 1.0),
                pk(130.0, low * 0.5, 1.0),
                pk(280.0, low * 0.25, 1.2),
                pk(1000.0, mid, 1.0),
                pk(3200.0, mid * 0.5, 1.0),
                pk(7000.0, high * 0.5, 1.0),
                hs(10000.0, high)
            )
        )
    }
}

/** Output route classes for persisted profiles. */
enum class OutputClass(val prefKey: String) {
    DEFAULT("default"),
    PHONE_SPEAKER("phone_speaker"),
    HEADPHONES("headphones"),
    BLUETOOTH("bluetooth"),
    CAR_EXTERNAL("car_external");

    companion object {
        fun fromKey(key: String): OutputClass =
            values().find { it.prefKey == key } ?: DEFAULT
    }
}

/** Minimal key/value store so profile persistence is JVM-testable. */
interface DspPrefStore {
    fun getString(key: String, default: String): String
    fun putString(key: String, value: String)
    fun getDouble(key: String, default: Double): Double
    fun putDouble(key: String, value: Double)
}

/**
 * Per-output-class profiles, persisted. Tiny-speaker policy (documented as a
 * POLICY, not a hardware claim): on PHONE_SPEAKER, sub-150 Hz band gains are
 * capped at +3 dB — phone speakers cannot reproduce that energy and it only
 * wastes headroom / rattles. Full profiles apply on headphones/BT/car.
 */
object DspProfiles {
    const val TINY_SPEAKER_BASS_CAP_DB = 3.0
    const val TINY_SPEAKER_CAP_FREQ_HZ = 150.0

    private fun presetKey(c: OutputClass) = "dsp_profile_${c.prefKey}_preset"
    private fun preampKey(c: OutputClass) = "dsp_profile_${c.prefKey}_preamp"
    private fun bandKey(c: OutputClass, i: Int) = "dsp_profile_${c.prefKey}_band_$i"

    fun save(store: DspPrefStore, c: OutputClass, presetName: String, preampDb: Double) {
        store.putString(presetKey(c), presetName)
        store.putDouble(preampKey(c), preampDb)
    }

    /** Persist custom band edits (freq,gain,q,bypass per band). */
    fun saveBands(store: DspPrefStore, c: OutputClass, bands: List<EqBand>) {
        bands.forEachIndexed { i, b ->
            store.putString(
                bandKey(c, i),
                "${b.freqHz},${b.gainDb},${b.q},${b.bypass}"
            )
        }
    }

    fun loadBands(store: DspPrefStore, c: OutputClass): List<EqBand>? {
        val out = mutableListOf<EqBand>()
        for (i in 0 until ParametricEq.BAND_COUNT) {
            val raw = store.getString(bandKey(c, i), "")
            if (raw.isEmpty()) return null
            val parts = raw.split(",")
            if (parts.size != 4) return null
            val type = when (i) {
                ParametricEq.SLOT_LOW_SHELF -> BandType.LOW_SHELF
                ParametricEq.SLOT_HIGH_SHELF -> BandType.HIGH_SHELF
                else -> BandType.PEAKING
            }
            out.add(
                EqBand(
                    type,
                    parts[0].toDoubleOrNull() ?: return null,
                    parts[1].toDoubleOrNull() ?: return null,
                    parts[2].toDoubleOrNull() ?: return null,
                    parts[3].toBooleanStrictOrNull() ?: return null
                )
            )
        }
        return out
    }

    fun loadPresetName(store: DspPrefStore, c: OutputClass): String =
        store.getString(presetKey(c), DspPresets.REFERENCE_FLAT.name)

    fun loadPreampDb(store: DspPrefStore, c: OutputClass): Double =
        store.getDouble(preampKey(c), 0.0)

    /** Build the effective preset for a route: base preset + route policy. */
    fun effectivePreset(store: DspPrefStore, c: OutputClass): DspPreset {
        val savedName = loadPresetName(store, c)
        val base = if (savedName == "Custom") {
            val bands = loadBands(store, c)
            if (bands != null) DspPreset("Custom", loadPreampDb(store, c), bands)
            else DspPresets.REFERENCE_FLAT
        } else {
            DspPresets.byName(savedName) ?: DspPresets.REFERENCE_FLAT
        }
        val bands = base.bands.map { b ->
            if (c == OutputClass.PHONE_SPEAKER && b.freqHz < TINY_SPEAKER_CAP_FREQ_HZ && b.gainDb > TINY_SPEAKER_BASS_CAP_DB) {
                b.copy(gainDb = TINY_SPEAKER_BASS_CAP_DB)
            } else b
        }
        return DspPreset("${base.name} [${c.prefKey}]", base.preampDb, bands)
    }

    /** Snapshot the live engine state into the route profile (Custom). */
    fun saveCustom(store: DspPrefStore, c: OutputClass, eq: ParametricEq) {
        save(store, c, "Custom", eq.preampDb)
        saveBands(store, c, eq.bands)
    }
}
