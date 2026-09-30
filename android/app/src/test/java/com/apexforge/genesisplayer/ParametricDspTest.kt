package com.apexforge.genesisplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.PI
import kotlin.math.sin

/**
 * WO-AURUM-008 — deterministic JVM tests for the parametric DSP core.
 *
 * Pure JVM (no Android, no emulator): biquad coefficients, response curves,
 * headroom/anti-clip behavior, smoothing, profiles, presets, Easy mapping.
 * Android-instrumented needs (live audio session, Media3 hookup, screenshots)
 * are device-gated and listed in DSP_EVIDENCE.md — NOT covered here.
 */
class ParametricDspTest {

    private fun eqWith(preset: DspPreset): ParametricEq {
        val eq = ParametricEq(48000)
        eq.applyPreset(preset)
        return eq
    }

    // ---------- coefficient sanity ----------

    @Test
    fun peakingCoeffsAreFiniteAtEdges() {
        val freqs = doubleArrayOf(20.0, 20000.0, 24000.0) // 24k clamps to Nyquist*0.49
        val qs = doubleArrayOf(0.05, 0.1, 18.0, 40.0)
        val gains = doubleArrayOf(-15.0, -30.0, 15.0, 30.0)
        for (f in freqs) for (q in qs) for (g in gains) {
            val c = DspMath.peaking(f, q, g, 48000)
            assertTrue("peaking f=$f q=$q g=$g", c.allFinite())
            val s = DspMath.lowShelf(f, g, 48000)
            assertTrue("lowshelf f=$f g=$g", s.allFinite())
            val h = DspMath.highShelf(f, g, 48000)
            assertTrue("highshelf f=$f g=$g", h.allFinite())
        }
    }

    @Test
    fun peakingBoostAndCutAreSymmetric() {
        val up = DspMath.magnitudeDb(DspMath.peaking(1000.0, 1.0, 6.0, 48000), 1000.0, 48000)
        val dn = DspMath.magnitudeDb(DspMath.peaking(1000.0, 1.0, -6.0, 48000), 1000.0, 48000)
        assertEquals(6.0, up, 0.05)
        assertEquals(-6.0, dn, 0.05)
    }

    @Test
    fun shelvesHitNominalGainInTheirBand() {
        val ls = DspMath.magnitudeDb(DspMath.lowShelf(100.0, 6.0, 48000), 30.0, 48000)
        val hs = DspMath.magnitudeDb(DspMath.highShelf(10000.0, 6.0, 48000), 18000.0, 48000)
        assertTrue("low shelf deep bass $ls", ls > 5.0)
        assertTrue("high shelf top octave $hs", hs > 5.0)
        // Far side of each shelf is ~0 dB.
        val lsFar = DspMath.magnitudeDb(DspMath.lowShelf(100.0, 6.0, 48000), 10000.0, 48000)
        val hsFar = DspMath.magnitudeDb(DspMath.highShelf(10000.0, 6.0, 48000), 100.0, 48000)
        assertTrue("low shelf far side $lsFar", abs(lsFar) < 0.5)
        assertTrue("high shelf far side $hsFar", abs(hsFar) < 0.5)
    }

    // ---------- flat / impulse ----------

    @Test
    fun flatPresetIsUnityWithinTinyTolerance() {
        val eq = eqWith(DspPresets.REFERENCE_FLAT)
        var worst = 0.0
        var f = 20.0
        while (f <= 20000.0) {
            worst = maxOf(worst, abs(eq.responseDb(f)))
            f *= 1.1
        }
        assertTrue("flat worst deviation $worst dB", worst < 0.02)
    }

    @Test
    fun impulseResponseIsFiniteAndStable() {
        for (p in DspPresets.ALL) {
            val eq = eqWith(p)
            val h = eq.impulseResponse(4096)
            assertTrue("${p.name}: all finite", h.all { it.isFinite() })
            assertTrue("${p.name}: tail decays ${h[4095]}", abs(h[4095]) < 1e-4)
            val energy = h.sumOf { it * it }
            assertTrue("${p.name}: bounded energy $energy", energy < 100.0)
        }
    }

    @Test
    fun bypassAllIsBitTransparent() {
        val eq = eqWith(DspPresets.TRAP_ROCK_RAGE)
        eq.bypassAll = true
        val input = FloatArray(2048) { sin(it * 0.1).toFloat() * 0.7f }
        val out = eq.processBlock(input)
        var worst = 0.0f
        for (i in input.indices) worst = maxOf(worst, abs(out[i] - input[i]))
        assertTrue("bypass worst delta $worst", worst < 1e-6f)
    }

    @Test
    fun perBandBypassRemovesOnlyThatBand() {
        val eq = eqWith(DspPresets.FEEL_IT)
        val with_ = eq.responseDb(120.0)
        eq.bands[1].bypass = true // the 120 Hz punch band
        eq.retarget(snap = true)
        val without = eq.responseDb(120.0)
        assertTrue("bypassed band drops response: $with_ -> $without", without < with_ - 1.0)
        // Far away, response barely moves.
        val farWith = eqWith(DspPresets.FEEL_IT).responseDb(8000.0)
        assertTrue(abs(eq.responseDb(8000.0) - farWith) < 0.5)
    }

    // ---------- smoothing ----------

    @Test
    fun coefficientSmoothingConvergesWithoutSpike() {
        val f = BiquadFilter(48000)
        f.smoothTauSec = 0.02
        f.setTarget(DspMath.peaking(100.0, 1.0, 0.0, 48000))
        f.snap()
        // Abrupt retarget: 0 dB -> +12 dB low shelf.
        f.setTarget(DspMath.lowShelf(100.0, 12.0, 48000))
        var maxErr = 0.0
        var maxStep = 0.0
        var prev = f.currentCoeffs().asArray()
        repeat(48000 * 2) { // 2 s of samples: must converge
            f.process(0.0)
            val cur = f.currentCoeffs().asArray()
            for (i in cur.indices) {
                maxStep = maxOf(maxStep, abs(cur[i] - prev[i]))
                maxErr = maxOf(maxErr, abs(cur[i] - f.targetCoeffs().asArray()[i]))
            }
            prev = cur
        }
        assertTrue("converged, residual $maxErr", maxErr < 1e-9)
        // No single-sample coefficient jump larger than 2% of the total travel.
        val total = f.targetCoeffs().asArray().zip(
            DspMath.lowShelf(100.0, 0.0, 48000).asArray()
        ).maxOf { (t, s) -> abs(t - s) }
        assertTrue("max single step $maxStep vs total $total", maxStep < total * 0.02)
    }

    // ---------- headroom / anti-clip ----------

    @Test
    fun noPresetClipsZeroDbfsStressSignal() {
        for (p in DspPresets.ALL) {
            val eq = eqWith(p)
            // Stress at the preset's own worst-case frequency AND fixed probes.
            val stressFreqs = mutableListOf<Double>()
            var worstF = 20.0
            var worstDb = Double.NEGATIVE_INFINITY
            var f = 20.0
            while (f <= 20000.0) {
                val r = eq.responseDb(f)
                if (r > worstDb) { worstDb = r; worstF = f }
                f *= 1.05
            }
            stressFreqs.add(worstF)
            stressFreqs.addAll(listOf(60.0, 1000.0, 8000.0))
            for (sf in stressFreqs) {
                val w = 2.0 * Math.PI * sf / 48000
                val input = FloatArray(48000) { sin(w * it).toFloat() } // 0 dBFS
                val out = eq.processBlock(input)
                val peak = out.maxOf { abs(it.toDouble()) }
                assertTrue(
                    "${p.name} @ ${sf.toInt()} Hz: peak $peak exceeds 1.0",
                    peak <= 1.0
                )
            }
        }
    }

    @Test
    fun presetsShipAtMeasuredHeadroomPoint() {
        // Every non-flat preset's base preamp sits below its own worst-case
        // filter gain, so the auto-cut is armed but NOT engaged at load —
        // the protection indicator then means something (it lights up only
        // when user tweaks push the chain hot).
        for (p in DspPresets.ALL) {
            if (p.name == "Reference / Flat") continue
            val eq = eqWith(p)
            assertTrue("${p.name} worst ${eq.worstCaseGainDb()}", eq.worstCaseGainDb() < 0.0)
            assertEquals("${p.name} preamp untouched", eq.preampDb, eq.effectivePreampDb(), 1e-9)
            // Protection stays idle on ordinary material (cut disengaged,
            // limiter transparent) — so the indicator means something.
            val music = FloatArray(4800) { i ->
                (0.4 * sin(2.0 * PI * 440.0 * i / 48000.0)).toFloat()
            }
            eq.processBlock(music)
            assertFalse("${p.name} protection idle", eq.protectionActive)
        }
    }

    @Test
    fun headroomAutoReductionIsExactAndExposed() {
        // Slam a hot preset's preamp to max: the auto-cut must engage and be exact.
        val eq = eqWith(DspPresets.TRAP_ROCK_RAGE)
        eq.preampDb = 6.0
        val worst = eq.worstCaseGainDb()
        assertTrue("hot worst-case $worst", worst > 0.0)
        assertEquals(
            "effective preamp = requested - worst-case",
            eq.preampDb - worst, eq.effectivePreampDb(), 1e-9
        )
        // After protection, the chain's own worst case is <= 0 dB.
        var worstAfter = Double.NEGATIVE_INFINITY
        var fr = 20.0
        while (fr <= 20000.0) {
            worstAfter = maxOf(worstAfter, eq.responseDb(fr))
            fr *= 1.05
        }
        assertTrue("protected worst case $worstAfter", worstAfter <= 1e-9)
        // 0 dBFS stress at the worst frequency: protection reports, limiter guards.
        val f = eq.worstCaseFreqHz()
        val hot = FloatArray(4096) { i ->
            (0.999 * sin(2.0 * PI * f * i / 48000.0)).toFloat()
        }
        val out = eq.processBlock(hot)
        assertTrue("protection engaged", eq.protectionActive)
        for (s in out) assertTrue("no clip $s", abs(s) <= 1.0f)
    }

    @Test
    fun limiterIsTransparentWhenQuiet() {
        val eq = eqWith(DspPresets.REFERENCE_FLAT)
        val input = FloatArray(4800) { (0.5 * sin(it * 0.05)).toFloat() } // -6 dBFS
        val out = eq.processBlock(input)
        assertFalse(eq.limiterEngagedLastBlock)
        assertFalse(eq.protectionActive)
        var worst = 0.0
        for (i in input.indices) worst = maxOf(worst, abs(out[i] - input[i]).toDouble())
        assertTrue("quiet passthrough delta $worst", worst < 1e-6)
    }

    @Test
    fun limiterNeverExceedsUnity() {
        val eq = eqWith(DspPresets.REFERENCE_FLAT)
        for (amp in listOf(1.0, 2.0, 10.0)) {
            val input = FloatArray(4800) { (amp * sin(it * 0.05)).toFloat() }
            val out = eq.processBlock(input)
            val peak = out.maxOf { abs(it.toDouble()) }
            assertTrue("amp $amp peak $peak", peak <= 1.0)
        }
        assertTrue(eq.limiterEngagedLastBlock)
    }

    // ---------- presets: published checkpoints ----------

    @Test
    fun presetCheckpointsMatchPublishedIntent() {
        // Shape checkpoints are RELATIVE (dB vs the 1 kHz mids): the headroom
        // auto-cut shifts every preset's absolute level (engaged worst case is
        // <= 0 dB by construction), so absolute pins would fight the anti-clip
        // guarantee. Relative shape is invariant under the cut.
        fun shape(preset: DspPreset): (Double) -> Double {
            val eq = eqWith(preset)
            val ref = eq.responseDb(1000.0)
            return { f -> eq.responseDb(f) - ref }
        }
        val feel = shape(DspPresets.FEEL_IT)
        assertTrue("sub 60Hz ${feel(60.0)}", feel(60.0) > 2.0)
        assertTrue("mud 280Hz ${feel(280.0)}", feel(280.0) < -0.5)
        assertTrue("air 10kHz ${feel(10000.0)}", feel(10000.0) > 0.5)

        val night = shape(DspPresets.NIGHT_DRIVE)
        assertTrue("night sub ${night(70.0)}", night(70.0) > 3.0)
        assertTrue("night rolled top ${night(12000.0)}", night(12000.0) < 0.0)

        val emo = shape(DspPresets.EMO_VOCAL)
        assertTrue("vocal presence ${emo(3500.0)}", emo(3500.0) > 1.0)
        assertTrue("mud cut ${emo(200.0)}", emo(200.0) < -0.5)

        val rage = shape(DspPresets.TRAP_ROCK_RAGE)
        assertTrue("rage sub ${rage(55.0)}", rage(55.0) > 3.0)
        assertTrue("rage bite ${rage(5500.0)}", rage(5500.0) > 1.5)

        val cine = shape(DspPresets.DARK_CINEMATIC)
        assertTrue("cine sub ${cine(45.0)}", cine(45.0) > 3.0)
        assertTrue("cine dark top ${cine(18000.0)}", cine(18000.0) < -1.0)
    }

    @Test
    fun presetsAreSelfNamedWithEightBands() {
        val names = DspPresets.ALL.map { it.name }
        assertEquals(
            listOf(
                "Reference / Flat", "FEEL IT", "Night Drive",
                "Emo / Vocal", "Trap-Rock / Rage", "Dark Cinematic"
            ), names
        )
        for (p in DspPresets.ALL) {
            assertEquals(8, p.bands.size)
            assertEquals(BandType.LOW_SHELF, p.bands[0].type)
            assertEquals(BandType.HIGH_SHELF, p.bands[7].type)
            for (b in p.bands) {
                assertTrue("${p.name} gain ${b.gainDb}", b.gainDb in -15.0..15.0)
                assertTrue("${p.name} freq ${b.freqHz}", b.freqHz in 20.0..20000.0)
            }
            assertTrue("${p.name} preamp ${p.preampDb}", p.preampDb in -12.0..6.0)
        }
    }

    // ---------- A/B trust ----------

    @Test
    fun abBypassTrimLevelMatchesEngagedPath() {
        // Volume-matched A/B: attenuate the BYPASSED path by the engaged
        // chain's measured broadband mean. Boosting the engaged path is
        // futile — the headroom auto-cut eats exactly that boost (the engaged
        // mean is pinned at filterMean - filterWorst whenever the cut is
        // active) — while attenuation can never clip.
        val eq = eqWith(DspPresets.FEEL_IT)
        val trimDb = eq.abBypassTrimDb()
        assertTrue("trim is attenuation ($trimDb dB)", trimDb <= 0.0)
        assertEquals("trim == engaged broadband mean", eq.broadbandMeanDb(), trimDb, 1e-9)
        // Rich chord at a modest level: the limiter must stay completely out,
        // and the trimmed bypass must land within 1.5 dB of the engaged RMS.
        val sig = FloatArray(48000) { i ->
            val t = i / 48000.0
            (0.20 * sin(2.0 * PI * 55.0 * t) +
             0.20 * sin(2.0 * PI * 220.0 * t) +
             0.15 * sin(2.0 * PI * 880.0 * t) +
             0.10 * sin(2.0 * PI * 3520.0 * t)).toFloat()
        }
        val engaged = eq.processBlock(sig)
        assertFalse("limiter stayed out of the A/B match", eq.limiterEngagedLastBlock)
        eq.bypassAll = true
        val bypassed = eq.processBlock(sig)
        val g = 10.0.pow(trimDb / 20.0)
        var eE = 0.0
        var eB = 0.0
        for (i in sig.indices) {
            eE += engaged[i] * engaged[i]
            val b = bypassed[i] * g
            eB += b * b
        }
        val ratioDb = 10.0 * log10((eE / sig.size) / (eB / sig.size))
        assertTrue("A/B level match ${"%.2f".format(ratioDb)} dB", abs(ratioDb) < 1.5)
    }

    @Test
    fun resetFlatReturnsTrueFlat() {
        val eq = eqWith(DspPresets.DARK_CINEMATIC)
        eq.resetFlat()
        var worst = 0.0
        var f = 20.0
        while (f <= 20000.0) {
            worst = maxOf(worst, abs(eq.responseDb(f)))
            f *= 1.1
        }
        assertTrue(worst < 0.02)
        assertEquals(0.0, eq.preampDb, 0.0)
    }

    // ---------- Easy mode mapping ----------

    @Test
    fun easyMappingKeepsOldFourStageBehavior() {
        val bassHeavy = FourStageEq.State(true, 10, 5, -1, 1, "Bass Heavy")
        val eq = ParametricEq(48000)
        eq.applyPreset(EasyDspMapper.map(bassHeavy))
        assertEquals(5.0, eq.bands[0].gainDb, 1e-9)   // low shelf <- Low stage
        assertEquals(5.0, eq.bands[1].gainDb, 1e-9)   // 55 Hz <- 10 steps * 0.5
        assertEquals(-1.0, eq.bands[4].gainDb, 1e-9)  // 1 kHz <- Mid stage
        assertEquals(1.0, eq.bands[7].gainDb, 1e-9)   // high shelf <- High stage

        val flat = FourStageEq.State(false, 0, 0, 0, 0, "Flat")
        val eqFlat = ParametricEq(48000)
        eqFlat.applyPreset(EasyDspMapper.map(flat))
        assertTrue(eqFlat.bands.all { abs(it.gainDb) < 1e-9 })

        // Bass OFF means the 55 Hz band stays at 0 even with steps set.
        val off = FourStageEq.State(false, 12, 0, 0, 0, "Custom")
        val eqOff = ParametricEq(48000)
        eqOff.applyPreset(EasyDspMapper.map(off))
        assertEquals(0.0, eqOff.bands[1].gainDb, 1e-9)
    }

    // ---------- profiles ----------

    private class MapStore : DspPrefStore {
        val m = HashMap<String, Any>()
        override fun getString(key: String, default: String) = m[key] as? String ?: default
        override fun putString(key: String, value: String) { m[key] = value }
        override fun getDouble(key: String, default: Double) = m[key] as? Double ?: default
        override fun putDouble(key: String, value: Double) { m[key] = value }
    }

    @Test
    fun profilesPersistPerOutputClass() {
        val store = MapStore()
        DspProfiles.save(store, OutputClass.BLUETOOTH, "FEEL IT", -3.0)
        DspProfiles.save(store, OutputClass.PHONE_SPEAKER, "Night Drive", -4.0)
        assertEquals("FEEL IT", DspProfiles.loadPresetName(store, OutputClass.BLUETOOTH))
        assertEquals("Night Drive", DspProfiles.loadPresetName(store, OutputClass.PHONE_SPEAKER))
        assertEquals(-3.0, DspProfiles.loadPreampDb(store, OutputClass.BLUETOOTH), 1e-9)
        // Untouched class falls back to flat.
        assertEquals(
            DspPresets.REFERENCE_FLAT.name,
            DspProfiles.loadPresetName(store, OutputClass.CAR_EXTERNAL)
        )
    }

    @Test
    fun phoneSpeakerCapsSubBassByPolicy() {
        val store = MapStore()
        DspProfiles.save(store, OutputClass.PHONE_SPEAKER, "Trap-Rock / Rage", -4.5)
        val eff = DspProfiles.effectivePreset(store, OutputClass.PHONE_SPEAKER)
        for (b in eff.bands) {
            if (b.freqHz < 150.0) {
                assertTrue("capped ${b.freqHz}Hz -> ${b.gainDb}dB", b.gainDb <= 3.0 + 1e-9)
            }
        }
        // Headphones keep the full profile.
        DspProfiles.save(store, OutputClass.HEADPHONES, "Trap-Rock / Rage", -4.5)
        val full = DspProfiles.effectivePreset(store, OutputClass.HEADPHONES)
        assertTrue(full.bands[0].gainDb > 3.0)
    }

    @Test
    fun customBandsRoundTripThroughStore() {
        val store = MapStore()
        val eq = eqWith(DspPresets.FEEL_IT)
        eq.bands[1].gainDb = 7.5
        eq.bands[1].freqHz = 90.0
        eq.bands[3].bypass = true
        eq.preampDb = -2.0
        DspProfiles.saveCustom(store, OutputClass.HEADPHONES, eq)
        val eff = DspProfiles.effectivePreset(store, OutputClass.HEADPHONES)
        assertEquals(7.5, eff.bands[1].gainDb, 1e-9)
        assertEquals(90.0, eff.bands[1].freqHz, 1e-9)
        assertTrue(eff.bands[3].bypass)
        assertEquals(-2.0, eff.preampDb, 1e-9)
    }

    // ---------- resource smoke ----------

    @Test
    fun oneSecondBlockProcessesInReasonableTime() {
        val eq = eqWith(DspPresets.FEEL_IT)
        val input = FloatArray(48000) { (0.5 * sin(it * 0.01)).toFloat() }
        val t0 = System.nanoTime()
        eq.processBlock(input)
        val ms = (System.nanoTime() - t0) / 1e6
        // JVM sanity bound only — real low-end-device numbers are device-gated.
        assertTrue("48000-sample block took ${ms}ms", ms < 2000.0)
    }
}
