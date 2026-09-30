package com.apexforge.genesisplayer.dsp

import kotlin.math.log10
import kotlin.math.pow

/** What we honestly know about a track's loudness. Never guessed. */
sealed class LoudnessInfo {
    object Unknown : LoudnessInfo()
    data class MeasuredLufs(val integratedLufs: Double, val source: String) : LoudnessInfo()
}

object Loudness {
    const val MATCH_LOW_HZ = 30.0
    const val MATCH_HIGH_HZ = 16000.0
    const val MATCH_POINTS = 240
    const val MATCH_CLAMP_DB = 6.0

    /**
     * Gain to reach [targetLufs], or null when the loudness is Unknown (never guess).
     * = target - measured, clamped to [-maxCutDb, +maxBoostDb]; boost defaults to 0 so headroom is preserved.
     */
    fun normalizationGainDb(
        info: LoudnessInfo,
        targetLufs: Double = -14.0,
        maxBoostDb: Double = 0.0,
        maxCutDb: Double = 12.0
    ): Double? = when (info) {
        is LoudnessInfo.Unknown -> null
        is LoudnessInfo.MeasuredLufs -> {
            val raw = targetLufs - info.integratedLufs
            if (raw.isNaN() || raw.isInfinite()) null
            else raw.coerceAtMost(maxBoostDb.coerceAtLeast(0.0)).coerceAtLeast(-maxCutDb.coerceAtLeast(0.0))
        }
    }

    /**
     * Level compensation (dB) to apply to the BYPASSED (dry) signal so A/B against the EQ'd signal is
     * volume-matched. This is a SPECTRAL POWER MATCH on a pink-noise model, NOT a loudness measurement
     * (not LUFS, not ReplayGain, no K-weighting, no gating).
     *
     * Method: [MATCH_POINTS] frequencies uniform on a log axis from 30 Hz to 16 kHz (uniform log grid = equal
     * energy per octave = pink noise). Result = 10*log10(mean(|H(f)|^2)) where H is the cascade of all
     * enabled bands times the preamp gain. Autoheadroom reduction and the limiter are NOT included.
     * Clamped to +-6 dB; exactly 0.0 for flat/bypassed params.
     */
    fun matchedBypassGainDb(p: EqParams, fs: Double): Double {
        if (p.bypass) return 0.0
        val active = p.bands.filter { it.enabled && it.gainDb != 0f }
        if (active.isEmpty() && p.preampDb == 0f) return 0.0
        val coefs = active.map {
            BiquadMath.coefficients(it.type, fs, it.freqHz.toDouble(), it.gainDb.toDouble(), it.q.toDouble())
        }
        val lo = Math.log(MATCH_LOW_HZ)
        val hi = Math.log(MATCH_HIGH_HZ)
        var acc = 0.0
        for (i in 0 until MATCH_POINTS) {
            val f = Math.exp(lo + (hi - lo) * (i + 0.5) / MATCH_POINTS)
            var db = p.preampDb.toDouble()
            for (c in coefs) db += BiquadMath.magnitudeDb(c, fs, f)
            acc += 10.0.pow(db / 10.0)
        }
        val out = 10.0 * log10(acc / MATCH_POINTS)
        return out.coerceIn(-MATCH_CLAMP_DB, MATCH_CLAMP_DB)
    }
}

fun normalizationGainDb(
    info: LoudnessInfo, targetLufs: Double = -14.0, maxBoostDb: Double = 0.0, maxCutDb: Double = 12.0
): Double? = Loudness.normalizationGainDb(info, targetLufs, maxBoostDb, maxCutDb)

fun matchedBypassGainDb(p: EqParams, fs: Double): Double = Loudness.matchedBypassGainDb(p, fs)
