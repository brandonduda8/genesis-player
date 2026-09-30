package com.apexforge.genesisplayer.dsp

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow

/**
 * 8-band parametric EQ engine: preamp -> bands 0..7 -> limiter, on INTERLEAVED float samples.
 *
 * Processing: double-precision transposed direct form II biquads, one state pair per channel per band.
 *
 * Smoothing (no clicks): [setParams] only publishes an immutable target snapshot. The audio thread ramps the
 * underlying parameters (band gain in dB, log-frequency, log-Q, preamp in dB) linearly toward the targets over
 * about [RAMP_MS] ms, in blocks of at most [BLOCK] frames, recomputing coefficients once per block (never
 * interpolating raw coefficients, so every intermediate filter is a valid stable RBJ filter). Preamp gain and
 * the dry/wet mix are additionally interpolated per sample inside a block. A disabled band simply ramps its
 * gain to 0. A band whose gain is exactly 0 is skipped (exact identity); when a band ramps down to 0 it keeps
 * running until its state has decayed below 1e-9 so dropping it cannot produce a step.
 * The very first snapshot adopted after construction/[reset] is applied instantly (no ramp from flat).
 *
 * Bypass is a dry/wet crossfade over the same ramp time. When fully bypassed the wet path is not run at all
 * and the signal is untouched, so output == input bit for bit (apart from the constant latency, see below).
 *
 * LATENCY IS CONSTANT: the limiter delay ([latencyFrames], ~1.5 ms) is ALWAYS in the path, whether the limiter
 * is enabled, disabled or the engine bypassed. Toggling anything therefore never shifts timing. "Output ==
 * input" statements hold after aligning by [latencyFrames] (first [latencyFrames] output frames are silence).
 * While the limiter is disabled or the engine is fully bypassed, the limiter only delays (its gain releases to
 * exactly 1 within ~0.3 s if it was mid-reduction).
 *
 * Auto headroom: effective preamp = preampDb - max(0, Headroom.worstCaseBoostDb(...)) (never above the user's).
 *
 * Thread model: [setParams] / [responseDb] may be called from any thread; [process] and [reset] from one audio
 * thread. [process] does not allocate and takes no locks.
 */
class ParametricEq(val sampleRate: Int, val channels: Int) {
    init { require(sampleRate > 0 && channels >= 1) }

    private val fs = sampleRate.toDouble()
    private val nb = EqParams.BAND_COUNT
    private val limiter = Limiter(sampleRate, channels)

    /** Fixed latency in frames introduced by the always-present limiter delay. */
    val latencyFrames: Int get() = limiter.latencyFrames

    private class Snapshot(
        val types: Array<BandType>,
        val enabled: BooleanArray,
        val gainDb: DoubleArray,       // target gain, 0 for disabled bands
        val userGainDb: DoubleArray,   // sanitised gain as set by user
        val freqHz: DoubleArray,
        val logFreq: DoubleArray,
        val q: DoubleArray,
        val logQ: DoubleArray,
        val preDb: Double,             // effective preamp
        val mixTarget: Double,
        val limiterEnabled: Boolean
    )

    @Volatile private var snap: Snapshot = buildSnapshot(EqParams())

    /** User preamp plus auto-headroom reduction of the most recently set params. */
    val effectivePreampDb: Double get() = snap.preDb

    @Volatile var limiterActive: Boolean = false
        private set

    @Volatile var gainReductionDb: Double = 0.0
        private set

    private fun buildSnapshot(p: EqParams): Snapshot {
        val types = Array(nb) { p.bands[it].type }
        val enabled = BooleanArray(nb) { p.bands[it].enabled }
        val userGain = DoubleArray(nb) { BiquadMath.sanitizeGainDb(p.bands[it].gainDb.toDouble()) }
        val gain = DoubleArray(nb) { if (enabled[it]) userGain[it] else 0.0 }
        val f = DoubleArray(nb) { BiquadMath.sanitizeFreqHz(p.bands[it].freqHz.toDouble(), fs) }
        val q = DoubleArray(nb) { BiquadMath.sanitizeQ(p.bands[it].q.toDouble()) }
        val pre0 = p.preampDb.toDouble()
        val pre = if (pre0.isNaN()) 0.0 else pre0.coerceIn(EqParams.PREAMP_MIN_DB.toDouble(), EqParams.PREAMP_MAX_DB.toDouble())
        val eff = if (p.autoHeadroom) pre - Headroom.worstCaseBoostDb(p.copy(bypass = false), fs) else pre
        return Snapshot(
            types, enabled, gain, userGain, f, DoubleArray(nb) { ln(f[it]) }, q, DoubleArray(nb) { ln(q[it]) },
            eff, if (p.bypass) 0.0 else 1.0, p.limiterEnabled
        )
    }

    /** Publishes new target parameters; the audio thread ramps to them. Safe from the UI thread. */
    fun setParams(p: EqParams) { snap = buildSnapshot(p) }

    /**
     * Static response in dB at [freqHz]: effective preamp + all enabled bands at their targets. Excludes the
     * limiter and the bypass state (it describes the curve the engine converges to when not bypassed).
     */
    fun responseDb(freqHz: Double): Double {
        val s = snap
        var sum = s.preDb
        val c = DoubleArray(5)
        for (i in 0 until nb) {
            if (!s.enabled[i] || s.gainDb[i] == 0.0) continue
            BiquadMath.coefficientsInto(c, 0, s.types[i], fs, s.freqHz[i], s.gainDb[i], s.q[i])
            sum += BiquadMath.magnitudeDb(c, fs, freqHz)
        }
        return sum
    }

    // ---- audio-thread state (single thread) ----
    private val curGain = DoubleArray(nb)
    private val curLogF = DoubleArray(nb)
    private val curLogQ = DoubleArray(nb)
    private var curPre = 0.0
    private var curMix = 1.0
    private var rampRemaining = 0
    private val rampTotal = maxOf(1, Math.round(RAMP_MS * 0.001 * sampleRate).toInt())
    private var adopted: Snapshot? = null
    private val active = BooleanArray(nb)
    private val coefDirty = BooleanArray(nb)
    private val coef = DoubleArray(5 * nb)
    private val z1 = DoubleArray(nb * channels)
    private val z2 = DoubleArray(nb * channels)
    private val scratch = DoubleArray(BLOCK * channels)

    fun reset() {
        java.util.Arrays.fill(z1, 0.0)
        java.util.Arrays.fill(z2, 0.0)
        java.util.Arrays.fill(active, false)
        limiter.reset()
        adopted = null
        rampRemaining = 0
        limiterActive = false
        gainReductionDb = 0.0
    }

    private fun snapAllToTargets(s: Snapshot) {
        for (i in 0 until nb) { curGain[i] = s.gainDb[i]; curLogF[i] = s.logFreq[i]; curLogQ[i] = s.logQ[i] }
        curPre = s.preDb
        rampRemaining = 0
    }

    private fun deactivateAll() {
        java.util.Arrays.fill(active, false)
        java.util.Arrays.fill(z1, 0.0)
        java.util.Arrays.fill(z2, 0.0)
    }

    private fun adopt(s: Snapshot) {
        val first = adopted == null
        adopted = s
        for (i in 0 until nb) coefDirty[i] = true
        if (first) {
            snapAllToTargets(s); curMix = s.mixTarget; deactivateAll()
        } else if (curMix == 0.0) {
            snapAllToTargets(s); deactivateAll()   // wet path idle: start from targets, only the mix fades in
            rampRemaining = rampTotal
        } else {
            rampRemaining = rampTotal
        }
    }

    /** Processes [frames] interleaved frames in place, starting at sample index [offsetSamples]. */
    fun process(buf: FloatArray, offsetSamples: Int, frames: Int) {
        var done = 0
        var maxRed = 0.0
        while (done < frames) {
            val n = if (frames - done > BLOCK) BLOCK else frames - done
            processBlock(buf, offsetSamples + done * channels, n)
            if (limiter.gainReductionDb > maxRed) maxRed = limiter.gainReductionDb
            done += n
        }
        gainReductionDb = maxRed
        limiterActive = maxRed > 0.0
    }

    private fun processBlock(buf: FloatArray, off: Int, n: Int) {
        val s = snap
        if (s !== adopted) adopt(s)
        val ch = channels
        val mixStart = curMix
        val preStart = curPre
        val rem = rampRemaining
        val ramping = rem > 0
        if (ramping) {
            val frac = if (n >= rem) 1.0 else n.toDouble() / rem
            if (frac >= 1.0) {
                for (i in 0 until nb) { curGain[i] = s.gainDb[i]; curLogF[i] = s.logFreq[i]; curLogQ[i] = s.logQ[i] }
                curPre = s.preDb
                curMix = s.mixTarget
                rampRemaining = 0
            } else {
                for (i in 0 until nb) {
                    curGain[i] += (s.gainDb[i] - curGain[i]) * frac
                    curLogF[i] += (s.logFreq[i] - curLogF[i]) * frac
                    curLogQ[i] += (s.logQ[i] - curLogQ[i]) * frac
                }
                curPre += (s.preDb - curPre) * frac
                curMix += (s.mixTarget - curMix) * frac
                rampRemaining = rem - n
            }
        } else if (curMix != s.mixTarget) {
            curMix = s.mixTarget
        }
        val mixEnd = curMix

        if (mixStart == 0.0 && mixEnd == 0.0) {
            // Fully bypassed: untouched dry signal; wet path idle and kept at targets.
            snapAllToTargets(s)
            deactivateAll()
            limiter.enabled = false
            limiter.process(buf, off, n)
            return
        }

        // --- wet path ---
        var anyActive = false
        for (i in 0 until nb) {
            if (curGain[i] != 0.0 && !active[i]) {
                active[i] = true
                val b = i * ch
                for (c in 0 until ch) { z1[b + c] = 0.0; z2[b + c] = 0.0 }
                coefDirty[i] = true
            }
            if (active[i]) {
                anyActive = true
                if (ramping || coefDirty[i]) {
                    BiquadMath.coefficientsInto(
                        coef, 5 * i, s.types[i], fs, exp(curLogF[i]), curGain[i], exp(curLogQ[i])
                    )
                    coefDirty[i] = false
                }
            }
        }
        val gStart = if (preStart == 0.0) 1.0 else 10.0.pow(preStart / 20.0)
        val gEnd = if (curPre == 0.0) 1.0 else 10.0.pow(curPre / 20.0)

        if (anyActive || gStart != 1.0 || gEnd != 1.0) {
            val w = scratch
            val total = n * ch
            var j = 0
            for (f in 0 until n) {
                val g = if (gStart == gEnd) gEnd else gStart + (gEnd - gStart) * (f + 1) / n
                for (c in 0 until ch) {
                    var x = buf[off + j].toDouble()
                    if (x != x || x > 1e30 || x < -1e30) x = 0.0
                    w[j] = x * g
                    j++
                }
            }
            for (i in 0 until nb) {
                if (!active[i]) continue
                val k = 5 * i
                val b0 = coef[k]; val b1 = coef[k + 1]; val b2 = coef[k + 2]; val a1 = coef[k + 3]; val a2 = coef[k + 4]
                for (c in 0 until ch) {
                    var s1 = z1[i * ch + c]
                    var s2 = z2[i * ch + c]
                    var idx = c
                    for (f in 0 until n) {
                        val x = w[idx]
                        val y = b0 * x + s1
                        s1 = b1 * x - a1 * y + s2
                        s2 = b2 * x - a2 * y
                        w[idx] = y
                        idx += ch
                    }
                    if (abs(s1) < 1e-25) s1 = 0.0          // denormal guard
                    if (abs(s2) < 1e-25) s2 = 0.0
                    z1[i * ch + c] = s1
                    z2[i * ch + c] = s2
                }
                if (curGain[i] == 0.0) {                    // identity filter ringing out: retire when negligible
                    var small = true
                    for (c in 0 until ch) {
                        if (abs(z1[i * ch + c]) > 1e-9 || abs(z2[i * ch + c]) > 1e-9) { small = false; break }
                    }
                    if (small) {
                        active[i] = false
                        for (c in 0 until ch) { z1[i * ch + c] = 0.0; z2[i * ch + c] = 0.0 }
                    }
                }
            }
            if (mixStart == 1.0 && mixEnd == 1.0) {
                for (q in 0 until total) buf[off + q] = w[q].toFloat()
            } else {
                var q = 0
                for (f in 0 until n) {
                    val m = mixStart + (mixEnd - mixStart) * (f + 1) / n
                    for (c in 0 until ch) {
                        var dry = buf[off + q].toDouble()
                        if (dry != dry || dry > 1e30 || dry < -1e30) dry = 0.0
                        buf[off + q] = (dry * (1.0 - m) + w[q] * m).toFloat()
                        q++
                    }
                }
            }
        }
        limiter.enabled = s.limiterEnabled && mixEnd > 0.0
        limiter.process(buf, off, n)
    }

    companion object {
        const val BLOCK = 32
        const val RAMP_MS = 20.0
    }
}
