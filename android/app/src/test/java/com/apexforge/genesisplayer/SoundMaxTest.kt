package com.apexforge.genesisplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

/**
 * JVM tests for the SoundMax "pulverizing" extensions (SoundMax.kt).
 *
 * Pure JVM (no Android, no emulator): PULVERIZE curve checkpoints, bass
 * excursion guard, limiter transfer curves, loudness compensation, flat
 * unity, and the 0 dBFS stress fixture across every preset including the
 * new one.
 *
 * Shape checkpoints are RELATIVE (dB vs the 1 kHz mids): the headroom
 * auto-cut shifts absolute levels, so absolute pins would fight the
 * anti-clip guarantee. Relative shape is invariant under the cut. The
 * expected values below were computed from the published band data with
 * an independent Python mirror (tools/soundmax_reference.py) — they are
 * pins, not hand-waved.
 */
class SoundMaxTest {

    private fun eqWith(preset: DspPreset): ParametricEq {
        val eq = ParametricEq(48000)
        eq.applyPreset(preset)
        return eq
    }

    /** Shape of a preset relative to its 1 kHz response (dB). */
    private fun shape(preset: DspPreset): (Double) -> Double {
        val eq = eqWith(preset)
        val ref = eq.responseDb(1000.0)
        return { f -> eq.responseDb(f) - ref }
    }

    // ---------- PULVERIZE profile ----------

    @Test
    fun pulverizeHasPublishedStructure() {
        val p = SoundMaxPresets.PULVERIZE
        assertEquals("Pulverize / Rage-Max", p.name)
        assertEquals(8, p.bands.size)
        assertEquals(BandType.LOW_SHELF, p.bands[0].type)
        assertEquals(BandType.HIGH_SHELF, p.bands[7].type)
        assertEquals(-7.5, p.preampDb, 1e-9)
        for (b in p.bands) {
            assertTrue("gain ${b.gainDb}", b.gainDb in -15.0..15.0)
            assertTrue("freq ${b.freqHz}", b.freqHz in 20.0..20000.0)
        }
        // Band intents per the published design.
        assertEquals(60.0, p.bands[0].freqHz, 1e-9)   // sub-bass shelf 45-80 Hz
        assertEquals(120.0, p.bands[1].freqHz, 1e-9)  // punch 90-160 Hz
        assertEquals(280.0, p.bands[2].freqHz, 1e-9)  // mud cut 200-350 Hz
        assertEquals(3500.0, p.bands[4].freqHz, 1e-9) // presence 2-5 kHz
        assertEquals(12000.0, p.bands[6].freqHz, 1e-9)// air 10-14 kHz
        assertTrue(p.bands[2].gainDb < 0.0)           // mud is a cut
    }

    @Test
    fun pulverizeCurveCheckpoints() {
        // Pins from the independent Python mirror (relative to 1 kHz).
        val s = shape(SoundMaxPresets.PULVERIZE)
        assertEquals("+X dB at 50 Hz (sub-bass)", 5.95, s(50.0), 0.75)
        assertEquals("+Y dB at 120 Hz (punch)", 5.73, s(120.0), 0.75)
        assertEquals("cut at 300 Hz (mud)", -1.28, s(300.0), 0.75)
        assertEquals("+Z dB at 4 kHz (presence)", 4.43, s(4000.0), 0.75)
        // Band-region sanity: sub shelf hot across 45-80, mud cut negative,
        // presence clearly positive.
        assertTrue("sub 45 Hz ${s(45.0)}", s(45.0) > 5.0)
        assertTrue("sub 80 Hz ${s(80.0)}", s(80.0) > 4.0)
        assertTrue("mud 280 Hz ${s(280.0)}", s(280.0) < -0.5)
        assertTrue("presence 3500 Hz ${s(3500.0)}", s(3500.0) > 3.0)
        assertTrue("air 12000 Hz ${s(12000.0)}", s(12000.0) > 2.0)
    }

    @Test
    fun pulverizeShipsAtMeasuredHeadroomPoint() {
        val eq = eqWith(SoundMaxPresets.PULVERIZE)
        // Armed but not engaged: worst case sits below 0 dBFS, preamp untouched.
        assertTrue("worst ${eq.worstCaseGainDb()}", eq.worstCaseGainDb() < 0.0)
        assertEquals("preamp untouched", eq.preampDb, eq.effectivePreampDb(), 1e-9)
        // Protection stays idle on ordinary material.
        val music = FloatArray(4800) { i ->
            (0.4 * sin(2.0 * PI * 440.0 * i / 48000.0)).toFloat()
        }
        eq.processBlock(music)
        assertFalse("protection idle", eq.protectionActive)
    }

    @Test
    fun pulverizeWorstCaseFrequencyIsSubBass() {
        // The low shelf dominates the worst case — the stress anchor must be
        // down low, which is exactly what the stress test then hammers.
        val f = eqWith(SoundMaxPresets.PULVERIZE).worstCaseFreqHz()
        assertTrue("worst-case freq $f Hz", f < 100.0)
    }

    // ---------- bass excursion guard ----------

    @Test
    fun phoneSpeakerGuardCapsSubBassAndReseatsPreamp() {
        val guarded = SoundMaxGuard.excursionGuard(SoundMaxPresets.PULVERIZE, SoundMaxRoute.PHONE_SPEAKER)
        assertEquals(2.0, guarded.bands[0].gainDb, 1e-9)  // sub shelf capped
        assertEquals(3.0, guarded.bands[1].gainDb, 1e-9)  // punch capped
        // Mids and highs untouched.
        for (i in 2..7) {
            assertEquals(
                "band $i untouched",
                SoundMaxPresets.PULVERIZE.bands[i].gainDb, guarded.bands[i].gainDb, 1e-9
            )
        }
        // Preamp re-seated at the new measured headroom point (-4.5 dB):
        // the guarded profile is hotter overall because it no longer defends
        // inaudible sub-bass.
        assertEquals(-4.5, guarded.preampDb, 1e-9)
        val eq = eqWith(guarded)
        assertTrue("guarded worst ${eq.worstCaseGainDb()}", eq.worstCaseGainDb() < 0.0)
        assertEquals("guarded preamp untouched", eq.preampDb, eq.effectivePreampDb(), 1e-9)
    }

    @Test
    fun guardLeavesCapableRoutesUntouched() {
        for (route in listOf(
            SoundMaxRoute.HEADPHONES, SoundMaxRoute.BLUETOOTH,
            SoundMaxRoute.CAR_EXTERNAL, SoundMaxRoute.DEFAULT
        )) {
            val out = SoundMaxGuard.excursionGuard(SoundMaxPresets.PULVERIZE, route)
            assertEquals("preamp $route", SoundMaxPresets.PULVERIZE.preampDb, out.preampDb, 1e-9)
            out.bands.forEachIndexed { i, b ->
                assertEquals("band $i $route", SoundMaxPresets.PULVERIZE.bands[i].gainDb, b.gainDb, 1e-9)
            }
        }
    }

    @Test
    fun guardDoesNotTouchNonBassPresets() {
        // A preset with no sub-bass boost passes through unchanged (same
        // object identity — no copy made when nothing is capped).
        val flat = SoundMaxGuard.excursionGuard(DspPresets.REFERENCE_FLAT, SoundMaxRoute.PHONE_SPEAKER)
        assertEquals(0.0, flat.preampDb, 1e-9)
        assertTrue(flat.bands.all { abs(it.gainDb) < 1e-9 })
    }

    // ---------- limiter ----------

    @Test
    fun softKneeMatchesExistingTanhLimiter() {
        // Proves the analysis: SOFT_TANH at -1 dBFS IS ParametricEq.limitSample.
        val eq = ParametricEq(48000)
        val lim = SoundMaxLimiter(48000, -1.0, LimiterKnee.SOFT_TANH)
        var worst = 0.0
        var x = -3.0
        while (x <= 3.0) {
            worst = maxOf(worst, abs(lim.processSample(x) - eq.limitSample(x)))
            x += 0.01
        }
        assertTrue("max divergence $worst", worst < 1e-12)
    }

    @Test
    fun softKneeAnalysisPins() {
        // Documents the measured analysis in code: C1-continuous knee
        // (slope 1 at threshold), ~1 dB knee width, asymptote at 1.0.
        val t = 10.0.pow(-1.0 / 20.0)
        val lim = SoundMaxLimiter(48000, -1.0, LimiterKnee.SOFT_TANH)
        assertEquals(0.891251, t, 1e-6)
        // Transparent below threshold.
        assertEquals(0.5, lim.processSample(0.5), 1e-12)
        // Slope at threshold ~= 1 (no kink).
        val e = 1e-4
        val slope = (lim.processSample(t + e) - lim.processSample(t - e)) / (2 * e)
        assertEquals(1.0, slope, 0.01)
        // 0 dBFS in -> -0.228 dBFS out.
        assertEquals(-0.228, 20.0 * log10(lim.processSample(1.0)), 0.01)
        // Asymptote: 5x overdrive still <= 1.0.
        assertTrue(lim.processSample(5.0) <= 1.0)
        assertTrue(lim.processSample(5.0) > 0.9999)
    }

    /** Steady-state transfer: long DC steps, measured over the settled tail. */
    private fun transferCurve(knee: LimiterKnee): List<Pair<Double, Double>> {
        val lim = SoundMaxLimiter(48000, -1.0, knee)
        return listOf(0.1, 0.5, 0.89, 1.0, 1.5, 2.0, 5.0).map { dc ->
            lim.reset()
            var acc = 0.0
            var n = 0
            repeat(4800) { i ->
                val y = abs(lim.processSample(dc))
                if (i >= 3800) {
                    acc += y
                    n++
                }
            }
            dc to acc / n
        }
    }

    @Test
    fun limiterTransferCurvesNeverExceedCeiling() {
        val ceiling = 10.0.pow(-1.0 / 20.0)
        for (knee in LimiterKnee.values()) {
            val curve = transferCurve(knee)
            // Monotonic non-decreasing (no foldback weirdness).
            for (i in 1 until curve.size) {
                assertTrue(
                    "$knee monotonic at ${curve[i].first}",
                    curve[i].second >= curve[i - 1].second - 1e-9
                )
            }
            // Transparent well below the ceiling.
            assertEquals("$knee transparent", 0.1, curve[0].second, 1e-9)
            assertEquals("$knee transparent", 0.5, curve[1].second, 1e-9)
            if (knee == LimiterKnee.SOFT_TANH) {
                // Soft: creeps toward 1.0, never exceeds it.
                for ((_, out) in curve) assertTrue("$knee <= 1.0: $out", out <= 1.0 + 1e-9)
                assertTrue("soft asymptote", curve.last().second > 0.9999)
            } else {
                // Hard: clamps AT the ceiling, never above.
                for ((_, out) in curve) assertTrue("$knee <= ceiling: $out", out <= ceiling + 1e-9)
                for ((dc, out) in curve.drop(3)) {
                    assertEquals("$knee clamps at $dc", ceiling, out, 1e-6)
                }
            }
        }
    }

    @Test
    fun hardKneeHasZeroOvershootOnSteps() {
        // The whole point of the instantaneous-attack design: even a
        // pathological full-scale DC step never exceeds the ceiling —
        // not just in steady state but on every single sample.
        val lim = SoundMaxLimiter(48000, -1.0, LimiterKnee.HARD_KNEE)
        val ceiling = lim.ceilingLinear
        val input = FloatArray(4800) { if (it < 100) 0.0f else 2.0f }
        val out = lim.processBlock(input)
        var peak = 0.0
        for (s in out) peak = maxOf(peak, abs(s.toDouble()))
        assertTrue("peak $peak vs ceiling $ceiling", peak <= ceiling * (1.0 + 1e-9))
        assertTrue("engaged", lim.engagedLastBlock)
    }

    @Test
    fun hardKneeReleaseRecovers() {
        // After a hot burst, the limiter must return to unity gain on quiet
        // material — 50 ms release ~= fully recovered after ~10 constants.
        val lim = SoundMaxLimiter(48000, -1.0, LimiterKnee.HARD_KNEE)
        repeat(1000) { lim.processSample(2.0) } // hot burst ducks the gain
        var y = 0.0
        repeat(24000) { y = lim.processSample(0.1) } // 0.5 s of quiet
        assertEquals("gain recovered", 0.1, abs(y), 0.005)
    }

    @Test
    fun hardKneeIsTransparentWhenQuiet() {
        val lim = SoundMaxLimiter(48000, -1.0, LimiterKnee.HARD_KNEE)
        val input = FloatArray(4800) { (0.5 * sin(it * 0.05)).toFloat() }
        val out = lim.processBlock(input)
        assertFalse(lim.engagedLastBlock)
        var worst = 0.0
        for (i in input.indices) worst = maxOf(worst, abs(out[i] - input[i]).toDouble())
        assertTrue("quiet passthrough delta $worst", worst < 1e-9)
    }

    // ---------- loudness compensation ----------

    @Test
    fun loudnessCompensationAnchors() {
        // Full volume: no compensation. Silence: full compensation.
        // (Approximation — see LoudnessCompensation KDoc.)
        val full = LoudnessCompensation.compensation(1.0)
        assertEquals(0.0, full.lowShelfDb, 1e-12)
        assertEquals(0.0, full.highShelfDb, 1e-12)
        val quiet = LoudnessCompensation.compensation(0.0)
        assertEquals(14.0, quiet.lowShelfDb, 1e-12)
        assertEquals(6.0, quiet.highShelfDb, 1e-12)
        val half = LoudnessCompensation.compensation(0.5)
        assertEquals(5.0, half.lowShelfDb, 1e-12)
        assertEquals(2.5, half.highShelfDb, 1e-12)
    }

    @Test
    fun loudnessCompensationInterpolatesAndClamps() {
        // Midpoint of the 0.75 and 0.50 anchors.
        val mid = LoudnessCompensation.compensation(0.625)
        assertEquals(3.5, mid.lowShelfDb, 1e-9)
        assertEquals(1.75, mid.highShelfDb, 1e-9)
        // Out-of-range inputs clamp to the ends.
        assertEquals(
            LoudnessCompensation.compensation(0.0).lowShelfDb,
            LoudnessCompensation.compensation(-0.5).lowShelfDb, 1e-12
        )
        assertEquals(
            LoudnessCompensation.compensation(1.0).lowShelfDb,
            LoudnessCompensation.compensation(1.5).lowShelfDb, 1e-12
        )
    }

    @Test
    fun loudnessCompensationGrowsAsVolumeFalls() {
        // Fletcher-Munson shape: compensation increases monotonically as
        // the volume drops, and bass always needs more than treble.
        var prev = LoudnessCompensation.compensation(1.0)
        for (v in listOf(0.9, 0.75, 0.6, 0.5, 0.4, 0.25, 0.1, 0.0)) {
            val c = LoudnessCompensation.compensation(v)
            assertTrue("low grows at $v", c.lowShelfDb >= prev.lowShelfDb - 1e-12)
            assertTrue("high grows at $v", c.highShelfDb >= prev.highShelfDb - 1e-12)
            assertTrue("bass > treble at $v", c.lowShelfDb >= c.highShelfDb - 1e-12)
            prev = c
        }
        // The bands helper returns a real low shelf + high shelf pair.
        val bands = LoudnessCompensation.compensationBands(0.25)
        assertEquals(2, bands.size)
        assertEquals(BandType.LOW_SHELF, bands[0].type)
        assertEquals(BandType.HIGH_SHELF, bands[1].type)
        assertEquals(9.0, bands[0].gainDb, 1e-9)
        assertEquals(4.0, bands[1].gainDb, 1e-9)
    }

    // ---------- flat unity + full stress fixture ----------

    @Test
    fun flatModeUnityWithinPointZeroOneDb() {
        val eq = eqWith(DspPresets.REFERENCE_FLAT)
        var worst = 0.0
        var f = 20.0
        while (f <= 20000.0) {
            worst = maxOf(worst, abs(eq.responseDb(f)))
            f *= 1.05
        }
        assertTrue("flat worst deviation $worst dB", worst < 0.01)
    }

    @Test
    fun noPresetClipsZeroDbfsStressSignalIncludingPulverize() {
        val presets = DspPresets.ALL + SoundMaxPresets.ALL
        for (p in presets) {
            val eq = eqWith(p)
            // Stress at the preset's own worst-case frequency AND fixed probes.
            val stressFreqs = mutableListOf(eq.worstCaseFreqHz())
            stressFreqs.addAll(listOf(60.0, 1000.0, 8000.0))
            for (sf in stressFreqs) {
                val w = 2.0 * PI * sf / 48000
                val input = FloatArray(48000) { sin(w * it).toFloat() } // 0 dBFS
                val out = eq.processBlock(input)
                val peak = out.maxOf { abs(it.toDouble()) }
                assertTrue(
                    "${p.name} @ ${sf.toInt()} Hz: peak $peak exceeds 1.0",
                    peak <= 1.0
                )
            }
            // The guarded phone-speaker variant must survive too.
            val guarded = eqWith(
                SoundMaxGuard.excursionGuard(p, SoundMaxRoute.PHONE_SPEAKER)
            )
            val gf = guarded.worstCaseFreqHz()
            val w = 2.0 * PI * gf / 48000
            val input = FloatArray(48000) { sin(w * it).toFloat() }
            val peak = guarded.processBlock(input).maxOf { abs(it.toDouble()) }
            assertTrue("${p.name} guarded: peak $peak exceeds 1.0", peak <= 1.0)
        }
    }

    @Test
    fun worstCaseFreqHzAgreesWithGridScan() {
        // The helper the committed ParametricDspTest already calls: verify it
        // returns the grid argmax of the chain response.
        for (p in DspPresets.ALL + SoundMaxPresets.ALL) {
            val eq = eqWith(p)
            val f = eq.worstCaseFreqHz()
            var worstF = 20.0
            var worst = Double.NEGATIVE_INFINITY
            var g = 20.0
            while (g <= 20000.0) {
                val r = eq.responseDb(g)
                if (r > worst) {
                    worst = r
                    worstF = g
                }
                g *= 10.0.pow(1.0 / 24.0)
            }
            assertEquals("${p.name} argmax", worstF, f, 1e-9)
        }
    }
}
