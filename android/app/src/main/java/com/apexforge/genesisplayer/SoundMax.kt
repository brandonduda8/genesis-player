package com.apexforge.genesisplayer

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.tanh

/**
 * AURUM rebuild — "pulverizing" DSP extensions (WO-AURUM-008 follow-up).
 *
 * Pure-Kotlin, zero Android dependencies: every function here is a pure JVM
 * unit-test candidate (see SoundMaxTest). Extends [ParametricDsp] without
 * modifying it — the only cross-file touch is the [worstCaseFreqHz] helper,
 * which the committed ParametricDspTest already calls (it did not compile
 * without it; this extension supplies it).
 *
 * What "pulverizing" means here, honestly: the heaviest EQ curve the
 * headroom system can carry without ever clipping a 0 dBFS stress signal,
 * a route-aware bass guard so phone speakers don't waste the headroom the
 * curve needs, a hard-knee limiter option with a provable ceiling, and
 * loudness compensation so quiet listening keeps its body. No psychoacoustic
 * fairy dust — every constant below carries its WHY.
 *
 * Device-gated (NOT verified here): how any of this sounds on real
 * hardware, Media3 AudioProcessor wiring, per-device route detection (the
 * route is passed in as an enum precisely so this file never probes).
 */
object SoundMax {

    // ------------------------------------------------------------------
    // 1. PULVERIZE / RAGE-MAX profile
    //
    // The heaviest clean profile in the fleet: every band sits inside the
    // +/-15 dB clamp with margin, and the preamp ships at the measured
    // headroom point (-(worst-case filter gain) - 0.5 dB margin = -7.5 dB),
    // so the auto headroom cut is ARMED but NOT engaged at load — same
    // convention as the six WO-AURUM-008 presets. Pushing any band hotter
    // engages the cut; nothing the user can do clips a 0 dBFS stress
    // signal (pinned by SoundMaxTest.noPresetClipsZeroDbfsStressSignalIncludingPulverize).
    //
    // Band-by-band WHY (slot layout is fixed: 0 = low shelf, 1..6 peaking,
    // 7 = high shelf):
    // ------------------------------------------------------------------

    /** Sub-bass shelf, corner 60 Hz, +7.0 dB. WHY 60 Hz: the 45-80 Hz octave
     *  (808 fundamental, kick sub) sits in the shelf's flat-top region, so
     *  the whole octave gets the full +7 dB. WHY +7 dB: the heaviest boost
     *  that keeps the chain worst-case under +7 dB, so the preamp only has
     *  to sink to -7.5 dB — pushing to +8 dB would cost another full dB of
     *  overall level to the headroom cut for 1 dB more sub. */
    const val SUB_SHELF_FREQ_HZ = 60.0
    const val SUB_SHELF_GAIN_DB = 7.0

    /** Punch peaking, 120 Hz, Q 1.0, +4.5 dB. WHY 120 Hz: kick-drum beater
     *  + chest-thump fundamental for trap/rage. WHY Q 1.0: ~1.4-octave
     *  bandwidth covers the 90-160 Hz punch window without reaching into
     *  the mud band below or thinning the 200 Hz body above. */
    const val PUNCH_FREQ_HZ = 120.0
    const val PUNCH_Q = 1.0
    const val PUNCH_GAIN_DB = 4.5

    /** Mud cut, 280 Hz, Q 1.2, -3.0 dB. WHY 280 Hz: the 200-350 Hz buildup
     *  where distorted 808s and down-tuned guitars turn to cardboard.
     *  WHY Q 1.2: surgical enough to miss the 120 Hz punch and the 900 Hz
     *  clearing band on either side. */
    const val MUD_FREQ_HZ = 280.0
    const val MUD_Q = 1.2
    const val MUD_GAIN_DB = -3.0

    /** Low-mid clearing, 900 Hz, Q 1.0, -1.0 dB. WHY: a 1 dB scoop here
     *  separates the vocal/scream lane from the wall of guitars without
     *  hollowing the mix — the "room" the presence band then fills. */
    const val CLEAR_FREQ_HZ = 900.0
    const val CLEAR_GAIN_DB = -1.0

    /** Presence peaking, 3500 Hz, Q 1.0, +3.0 dB. WHY 3.5 kHz: the 2-5 kHz
     *  presence window — vocal intelligibility, snare crack, the "in your
     *  face" band that makes rage vocals cut instead of sitting. */
    const val PRESENCE_FREQ_HZ = 3500.0
    const val PRESENCE_GAIN_DB = 3.0

    /** Bite peaking, 7000 Hz, Q 1.0, +2.0 dB. WHY: upper-harmonic edge —
     *  guitar pick attack, hi-hat sizzle, the scream's grit. Kept at +2 dB
     *  (not +3) because the 6-9 kHz sibilance region punishes harder boosts
     *  on bright masters. */
    const val BITE_FREQ_HZ = 7000.0
    const val BITE_GAIN_DB = 2.0

    /** Air core peaking, 12000 Hz, Q 0.9, +2.0 dB. WHY 12 kHz / Q 0.9: the
     *  10-14 kHz air window in one band — Q 0.9 widens the bell to cover it
     *  instead of ringing at a single point. */
    const val AIR_FREQ_HZ = 12000.0
    const val AIR_Q = 0.9
    const val AIR_GAIN_DB = 2.0

    /** Air extension shelf, corner 14000 Hz, +1.0 dB. WHY 14 kHz: lifts the
     *  top octave's tail without touching 8-10 kHz, where the bite band
     *  already lives — stacking shelf gain there would turn harsh fast. */
    const val AIR_SHELF_FREQ_HZ = 14000.0
    const val AIR_SHELF_GAIN_DB = 1.0

    /** Measured worst-case filter gain of the curve above: +6.718 dB at
     *  20 Hz (low shelf near its plateau). Preamp = -(6.718) - 0.5 dB
     *  margin, floored to the 0.5 dB grid = -7.5 dB, so worst+preamp =
     *  -0.782 dB: protection armed, never engaged at load. Recompute (not
     *  hand-tune) if any band value changes — SoundMaxTest pins it. */
    const val PULVERIZE_PREAMP_DB = -7.5
}

/** The PULVERIZE / RAGE-MAX preset. Published data, pinned by tests. */
object SoundMaxPresets {
    val PULVERIZE = DspPreset(
        "Pulverize / Rage-Max", SoundMax.PULVERIZE_PREAMP_DB, listOf(
            EqBand(BandType.LOW_SHELF, SoundMax.SUB_SHELF_FREQ_HZ, SoundMax.SUB_SHELF_GAIN_DB, 0.7071),
            EqBand(BandType.PEAKING, SoundMax.PUNCH_FREQ_HZ, SoundMax.PUNCH_GAIN_DB, SoundMax.PUNCH_Q),
            EqBand(BandType.PEAKING, SoundMax.MUD_FREQ_HZ, SoundMax.MUD_GAIN_DB, SoundMax.MUD_Q),
            EqBand(BandType.PEAKING, SoundMax.CLEAR_FREQ_HZ, SoundMax.CLEAR_GAIN_DB, 1.0),
            EqBand(BandType.PEAKING, SoundMax.PRESENCE_FREQ_HZ, SoundMax.PRESENCE_GAIN_DB, 1.0),
            EqBand(BandType.PEAKING, SoundMax.BITE_FREQ_HZ, SoundMax.BITE_GAIN_DB, 1.0),
            EqBand(BandType.PEAKING, SoundMax.AIR_FREQ_HZ, SoundMax.AIR_GAIN_DB, SoundMax.AIR_Q),
            EqBand(BandType.HIGH_SHELF, SoundMax.AIR_SHELF_FREQ_HZ, SoundMax.AIR_SHELF_GAIN_DB, 0.7071)
        )
    )

    val ALL: List<DspPreset> = listOf(PULVERIZE)

    fun byName(name: String): DspPreset? = ALL.find { it.name == name }
}

/**
 * Frequency (Hz) of the worst-case positive chain gain over the response
 * grid — the stress-test anchor. Uses responseDb (effective preamp): the
 * argmax is identical to the raw-preamp version because the two differ by
 * a constant. Returns 20 Hz when bypassed (flat grid).
 */
fun ParametricEq.worstCaseFreqHz(): Double {
    var worstF = 20.0
    var worst = Double.NEGATIVE_INFINITY
    for (f in ParametricEq.responseGrid()) {
        val r = responseDb(f)
        if (r > worst) {
            worst = r
            worstF = f
        }
    }
    return worstF
}

// ----------------------------------------------------------------------
// 2. Bass excursion guard — psychoacoustic-aware low-frequency ceiling.
//
// WHY it exists: a phone speaker's cone displacement for constant SPL rises
// ~12 dB/octave below its resonance (~150-300 Hz on a phone). Feeding the
// full +7 dB sub shelf to it converts to heat, rattle, and — worse — the
// headroom auto-cut then steals ~5 dB from the ENTIRE mix to protect a band
// the speaker physically cannot reproduce. The missing-fundamental effect
// means the 120 Hz punch band carries the perceived bass on tiny drivers
// anyway, so the guard caps the sub shelf hard and the punch band lightly,
// then re-seats the preamp at the new measured headroom point (the guarded
// profile is ~3 dB hotter overall than the unguarded one would be on the
// same speaker, because it no longer defends inaudible sub-bass).
//
// Route is an ENUM passed by the caller — this file never probes hardware.
// ----------------------------------------------------------------------

/** Output route for the excursion guard. Passed in, never probed. */
enum class SoundMaxRoute {
    DEFAULT,
    PHONE_SPEAKER,
    HEADPHONES,
    BLUETOOTH,
    CAR_EXTERNAL
}

object SoundMaxGuard {
    /** Phone-speaker sub-shelf cap: +2.0 dB. WHY: leaves a taste of sub
     *  without spending headroom the driver can't turn into sound. */
    const val PHONE_SUB_CAP_DB = 2.0

    /** Bands counted as "sub-bass" for the cap: low shelves below 100 Hz. */
    const val SUB_CAP_FREQ_HZ = 100.0

    /** Phone-speaker punch cap: +3.0 dB. WHY: the 90-160 Hz punch band is
     *  what a phone speaker CAN reproduce and what carries perceived bass
     *  via the missing fundamental — it keeps most of its weight. */
    const val PHONE_PUNCH_CAP_DB = 3.0
    const val PUNCH_LO_HZ = 90.0
    const val PUNCH_HI_HZ = 160.0

    /** Headroom margin re-applied when re-seating the preamp (dB). */
    const val HEADROOM_MARGIN_DB = 0.5

    /**
     * Route-adjusted copy of a preset. PHONE_SPEAKER: sub-shelf gains above
     * [PHONE_SUB_CAP_DB] are capped, punch-band gains above
     * [PHONE_PUNCH_CAP_DB] are capped, and the preamp is re-seated at the
     * new measured headroom point (-(worst) - margin, 0.5 dB grid, clamped
     * to the engine's preamp range). Every other route: preset untouched.
     */
    fun excursionGuard(preset: DspPreset, route: SoundMaxRoute): DspPreset {
        if (route != SoundMaxRoute.PHONE_SPEAKER) return preset
        var changed = false
        val bands = preset.bands.map { b ->
            val capped = when {
                b.type == BandType.LOW_SHELF && b.freqHz < SUB_CAP_FREQ_HZ && b.gainDb > PHONE_SUB_CAP_DB ->
                    PHONE_SUB_CAP_DB
                b.type == BandType.PEAKING && b.freqHz in PUNCH_LO_HZ..PUNCH_HI_HZ && b.gainDb > PHONE_PUNCH_CAP_DB ->
                    PHONE_PUNCH_CAP_DB
                else -> b.gainDb
            }
            if (capped != b.gainDb) {
                changed = true
                b.copy(gainDb = capped)
            } else b
        }
        // Nothing capped (e.g. an already-flat profile): return the preset
        // untouched — re-seating the preamp for a 0 dB worst case would cut
        // level for no reason.
        if (!changed) return preset
        val worst = filterWorstGainDb(bands, 48000)
        val preamp = (floor((-worst - HEADROOM_MARGIN_DB) * 2.0) / 2.0)
            .coerceIn(ParametricEq.PREAMP_MIN_DB, ParametricEq.PREAMP_MAX_DB)
        return DspPreset("${preset.name} [phone-speaker guard]", preamp, bands)
    }

    /** Worst-case positive FILTER gain (no preamp) over the response grid. */
    fun filterWorstGainDb(bands: List<EqBand>, sampleRate: Int): Double {
        var worst = Double.NEGATIVE_INFINITY
        for (f in ParametricEq.responseGrid()) {
            var db = 0.0
            for (b in bands) db += DspMath.magnitudeDb(b.coeffs(sampleRate), f, sampleRate)
            if (db > worst) worst = db
        }
        return worst
    }
}

// ----------------------------------------------------------------------
// 3. Limiter — analysis of the existing tanh curve + hard-knee option.
//
// Existing tanh soft limiter (ParametricEq.limitSample) analyzed:
//   threshold t = 10^(-1/20) = 0.891251 (-1.000 dBFS)
//   |x| <= t          : y = x                       (bit-transparent)
//   |x| >  t          : y = sign(x) * (t + (1-t) * tanh((|x|-t)/(1-t)))
//   slope at threshold: exactly 1.0 — the knee is C1-continuous, no kink.
//   input 1.0 (0 dBFS): output 0.974074 (-0.228 dBFS), slope there 0.42.
//   input 1.5         : output 0.999997 — the curve brickwalls toward 1.0
//                       but only asymptotically; it never quite touches.
//   So the "ceiling" of the soft limiter is 1.0 (0 dBFS) with a ~1 dB-wide
//   knee starting at -1 dBFS. Transparent below -1 dBFS, increasingly firm
//   above, never a hard clamp.
// ----------------------------------------------------------------------

/** Limiter knee behavior. */
enum class LimiterKnee {
    /** Memoryless tanh curve — bit-identical to ParametricEq.limitSample
     *  at the same ceiling. Smooth, slight ceiling creep toward 1.0. */
    SOFT_TANH,

    /** Zero-overshoot peak limiter: gain reduction is instantaneous
     *  (1 sample), recovery is exponential with [SoundMaxLimiter.releaseMs].
     *  Output can NEVER exceed the ceiling — provable, pinned by tests. */
    HARD_KNEE
}

class SoundMaxLimiter(
    val sampleRate: Int = 48000,
    /** Ceiling in dBFS. Default -1.0 dBFS matches the existing limiter's
     *  threshold; 1 dB of margin stays below true 0 dBFS for downstream
     *  float->PCM conversion and inter-sample peaks. */
    var ceilingDb: Double = -1.0,
    var knee: LimiterKnee = LimiterKnee.SOFT_TANH,
    /**
     * Gain-recovery time constant (ms). WHY 50 ms: longer than one full
     * 20 Hz cycle (50 ms), so sub-bass waveforms are not amplitude-
     * modulated into pumping/distortion; shorter than ~200 ms, so the
     * limiter fully recovers between kick hits (~500 ms apart at 120 BPM).
     * ~2400 samples at 48 kHz to 63% recovery.
     */
    var releaseMs: Double = 50.0
) {
    companion object {
        /**
         * Hard-knee attack is ONE SAMPLE by design, not a time constant.
         * WHY: a causal limiter cannot look ahead, so any finite attack
         * time lets transients through — measured +5.53 dB overshoot with
         * 68 samples over the ceiling on a full-scale step at 0.2 ms
         * attack. The ceiling guarantee is the point of this limiter, so
         * gain reduction tracks downward peaks instantly and the only
         * time constant is the release. The soft-tanh mode exists for
         * material that wants a gentler onset.
         */
        const val HARD_KNEE_ATTACK_SAMPLES = 1

        /** The memoryless tanh curve — the exact ParametricEq.limitSample
         *  formula, factored out so tests can prove equivalence. */
        fun softTanhSample(x: Double, thresholdLinear: Double): Double {
            val t = thresholdLinear
            val ax = abs(x)
            if (ax <= t) return x
            return sign(x) * (t + (1.0 - t) * tanh((ax - t) / (1.0 - t)))
        }
    }

    val ceilingLinear: Double get() = 10.0.pow(ceilingDb / 20.0)

    /** True when the last processed block engaged gain reduction. */
    var engagedLastBlock: Boolean = false
        private set

    private var gain = 1.0

    private fun releaseCoef(): Double = exp(-1.0 / ((releaseMs / 1000.0) * sampleRate))

    fun reset() {
        gain = 1.0
        engagedLastBlock = false
    }

    /** Process one sample. HARD_KNEE: output provably <= ceiling. */
    fun processSample(x: Double): Double {
        if (knee == LimiterKnee.SOFT_TANH) return softTanhSample(x, ceilingLinear)
        val ax = abs(x)
        val c = ceilingLinear
        // Instantaneous attack on reduction (zero overshoot by construction:
        // gain <= c/ax whenever ax > c, so |out| = |x|*gain <= c always),
        // exponential release on recovery.
        val target = if (ax > c) c / max(ax, 1e-12) else 1.0
        gain = if (target < gain) target else gain + (1.0 - releaseCoef()) * (target - gain)
        return x * gain
    }

    /** Process one mono block; returns a NEW array. */
    fun processBlock(input: FloatArray): FloatArray {
        val out = FloatArray(input.size)
        var engaged = false
        for (n in input.indices) {
            val y = processSample(input[n].toDouble())
            if (knee == LimiterKnee.HARD_KNEE && gain < 1.0 - 1e-12) engaged = true
            if (knee == LimiterKnee.SOFT_TANH && y != input[n].toDouble()) engaged = true
            out[n] = y.toFloat()
        }
        engagedLastBlock = engaged
        return out
    }
}

// ----------------------------------------------------------------------
// 4. Loudness compensation (Fletcher-Munson / ISO 226 style).
//
// WHAT it is: an APPROXIMATION, table-driven, pure math. Real equal-
// loudness data (ISO 226:2003) says the ear's bass/treble sensitivity
// falls off as level drops: going from ~90 phon (loud) to ~40 phon
// (quiet), 50 Hz needs roughly +15 dB more SPL to sound equally loud
// relative to 1 kHz, and 12 kHz needs roughly +6 dB. The table below
// encodes that shape at five anchor volumes; values between anchors are
// linearly interpolated.
//
// WHAT it is NOT: a calibration. Volume fraction 1.0 is assumed ≈ 90 phon
// and 0.0 ≈ 40 phon — the true SPL depends on device gain, transducer,
// and the listener, none of which this file can know. Treat every number
// as ±2 dB honest. Output is a low-shelf gain @ 60 Hz and a high-shelf
// gain @ 12 kHz for the caller to apply as two extra EQ bands.
// ----------------------------------------------------------------------

/** Compensating shelf gains (dB) for a given volume setting. */
data class LoudnessComp(val lowShelfDb: Double, val highShelfDb: Double)

object LoudnessCompensation {
    /** Anchor shelf corner frequencies (Hz). */
    const val LOW_SHELF_FREQ_HZ = 60.0
    const val HIGH_SHELF_FREQ_HZ = 12000.0

    /**
     * Anchors: volume fraction -> (low-shelf dB @ 60 Hz, high-shelf dB @ 12 kHz).
     * Shape rationale (ISO 226:2003 contour differences, 90 phon reference):
     * bass compensation grows ~quadratically as level falls while treble
     * grows ~linearly and smaller — the contours are steepest at low
     * frequencies and low phon levels.
     */
    private val TABLE: List<Pair<Double, LoudnessComp>> = listOf(
        1.00 to LoudnessComp(0.0, 0.0),
        0.75 to LoudnessComp(2.0, 1.0),
        0.50 to LoudnessComp(5.0, 2.5),
        0.25 to LoudnessComp(9.0, 4.0),
        0.00 to LoudnessComp(14.0, 6.0)
    )

    /** Compensating gains for [volumeFraction] in 0..1 (clamped). */
    fun compensation(volumeFraction: Double): LoudnessComp {
        val v = volumeFraction.coerceIn(0.0, 1.0)
        for (i in 0 until TABLE.size - 1) {
            val (vHi, cHi) = TABLE[i]
            val (vLo, cLo) = TABLE[i + 1]
            if (v <= vHi && v >= vLo) {
                val t = (vHi - v) / (vHi - vLo) // 0 at vHi, 1 at vLo
                return LoudnessComp(
                    cHi.lowShelfDb + t * (cLo.lowShelfDb - cHi.lowShelfDb),
                    cHi.highShelfDb + t * (cLo.highShelfDb - cHi.highShelfDb)
                )
            }
        }
        return TABLE.last().second // v == 0.0 exactly
    }

    /** Convenience: the two compensating bands ready to append to a chain. */
    fun compensationBands(volumeFraction: Double): List<EqBand> {
        val c = compensation(volumeFraction)
        return listOf(
            EqBand(BandType.LOW_SHELF, LOW_SHELF_FREQ_HZ, c.lowShelfDb, 0.7071),
            EqBand(BandType.HIGH_SHELF, HIGH_SHELF_FREQ_HZ, c.highShelfDb, 0.7071)
        )
    }
}
