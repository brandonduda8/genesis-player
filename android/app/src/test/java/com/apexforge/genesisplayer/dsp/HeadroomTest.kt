package com.apexforge.genesisplayer.dsp

import com.apexforge.genesisplayer.dsp.DspTestKit.FS
import kotlin.math.log10
import kotlin.math.max
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeadroomTest {
    private fun flat(pre: Float = 0f) = EqParams(preampDb = pre)

    /** 12 parameter sets: hand-made extremes first, then seeded random. */
    private fun paramSets(): List<EqParams> {
        val sets = ArrayList<EqParams>()
        // 1: everything +12 dB, Q 10, shelves included
        sets.add(EqParams(6f, List(8) { i ->
            val t = when (i) { 0 -> BandType.LOW_SHELF; 7 -> BandType.HIGH_SHELF; else -> BandType.PEAK }
            BandParams(t, EqParams.DEFAULT_FREQS_HZ[i], 12f, 10f)
        }, limiterEnabled = false))
        // 2: all peaks stacked on 1 kHz
        sets.add(EqParams(0f, List(8) { i -> BandParams(if (i == 0) BandType.LOW_SHELF else if (i == 7) BandType.HIGH_SHELF else BandType.PEAK, 1000f, 12f, 0.3f) }, limiterEnabled = false))
        // 3: narrow stacked peaks
        sets.add(EqParams(3f, List(8) { i -> BandParams(BandType.PEAK, 1000f * (1f + 0.01f * i), 12f, 10f) }, limiterEnabled = false))
        // 4: high-Q shelves overshoot, extreme edges
        sets.add(EqParams(0f, DspTestKit.bands(0 to DspTestKit.lowShelf(20f, 12f, 10f), 7 to DspTestKit.highShelf(20000f, 12f, 10f)), limiterEnabled = false))
        // 5: cuts only with positive preamp
        sets.add(EqParams(6f, List(8) { i -> BandParams(BandType.PEAK, EqParams.DEFAULT_FREQS_HZ[i], -12f, 1f) }, limiterEnabled = false))
        // 6: boosts with disabled bands
        sets.add(EqParams(0f, List(8) { i -> BandParams(BandType.PEAK, EqParams.DEFAULT_FREQS_HZ[i], 12f, 1f, enabled = i % 2 == 0) }, limiterEnabled = false))
        val r = DspTestKit.Rng(20260930L)
        for (k in 0 until 6) {
            val bl = List(8) { i ->
                val t = when (i) { 0 -> BandType.LOW_SHELF; 7 -> BandType.HIGH_SHELF; else -> BandType.PEAK }
                BandParams(t, r.logUniform(20.0, 20000.0).toFloat(), r.uniform(-12.0, 12.0).toFloat(), r.logUniform(0.3, 10.0).toFloat(), r.uniform(0.0, 1.0) > 0.15)
            }
            sets.add(EqParams(r.uniform(-12.0, 6.0).toFloat(), bl, limiterEnabled = false))
        }
        return sets
    }

    @Test fun flatHasNoBoostAndNoMargin() {
        assertEquals(0.0, Headroom.worstCaseBoostDb(flat(), 48000.0), 0.0)
        assertEquals(0.0, Headroom.sumOfPositiveGainsDb(flat()), 0.0)
        assertEquals(0.0, Headroom.worstCaseBoostDb(flat(-6f), 48000.0), 0.0)
    }

    @Test fun singlePeakMatchesItsGainPlusMargin() {
        val p = EqParams(0f, DspTestKit.bands(3 to DspTestKit.peak(1000f, 6f, 1f)))
        assertEquals(6.3, Headroom.worstCaseBoostDb(p, 48000.0), 1e-6)
        assertEquals(6.0, Headroom.sumOfPositiveGainsDb(p), 1e-12)
    }

    @Test fun preampCountsAndBypassAndDisabledAreHandled() {
        val p = EqParams(3f, DspTestKit.bands(3 to DspTestKit.peak(1000f, 6f, 1f)))
        assertEquals(9.3, Headroom.worstCaseBoostDb(p, 48000.0), 1e-6)
        assertEquals(0.0, Headroom.worstCaseBoostDb(p.copy(bypass = true), 48000.0), 0.0)
        val d = EqParams(0f, DspTestKit.bands(3 to DspTestKit.peak(1000f, 6f, 1f, enabled = false)))
        assertEquals(0.0, Headroom.worstCaseBoostDb(d, 48000.0), 0.0)
        assertEquals(0.0, Headroom.sumOfPositiveGainsDb(d), 0.0)
    }

    @Test fun gridIsDenseAndNeverNegative() {
        assertTrue(Headroom.GRID_POINTS >= 2000)
        for (p in paramSets()) assertTrue(Headroom.worstCaseBoostDb(p, 48000.0) >= 0.0)
    }

    @Test fun worstCaseIsAtLeastMeasuredMaxSineGain() {
        var minSlack = 1e9
        for ((k, p) in paramSets().withIndex()) {
            val eq = ParametricEq(FS, 1)
            eq.setParams(p.copy(autoHeadroom = false, limiterEnabled = false))
            val freqs = ArrayList<Double>()
            var f = 30.0
            while (f < 0.45 * FS) { freqs.add(f); f *= 1.25 }
            for (b in p.bands) { freqs.add(b.freqHz.toDouble().coerceIn(30.0, 0.45 * FS)) }
            var measuredMax = -1e9
            for (fr in freqs) measuredMax = max(measuredMax, DspTestKit.measuredGainDb(eq, fr, settleSec = 0.6, anaSec = 0.3))
            val worst = Headroom.worstCaseBoostDb(p, FS.toDouble())
            val slack = worst - max(0.0, measuredMax)
            if (slack < minSlack) minSlack = slack
            assertTrue("set $k: worst=$worst < measured=$measuredMax", worst >= measuredMax)
        }
        println("HEADROOM minimum slack (worstCase - measured max sine gain) = $minSlack dB")
    }

    @Test fun autoHeadroomNeverClipsOnStressSignals() {
        val sweep = DspTestKit.sweptSum(2 * FS)
        val pinks = DspTestKit.normalizeToPeak(DspTestKit.pink(3 * FS, 99L), 1.0f)
        var worstPeak = 0f
        for ((k, p0) in paramSets().withIndex()) {
            val p = p0.copy(autoHeadroom = true, limiterEnabled = false)
            val eq = ParametricEq(FS, 1)
            eq.setParams(p)
            assertTrue("effective preamp never above user", eq.effectivePreampDb <= p.preampDb + 1e-9)
            for (sig in listOf(sweep, pinks)) {
                eq.reset()
                val y = DspTestKit.run(eq, sig)
                val m = DspTestKit.maxAbs(y)
                if (m > worstPeak) worstPeak = m
                assertTrue("set $k clips: peak=$m", m <= 1.0f)
            }
            // full-scale sine at the frequency of peak response (slow fade-in avoids onset transient)
            var bestF = 100.0; var bestG = -1e9
            var f = 20.0
            while (f < 0.49 * FS) { val g = eq.responseDb(f); if (g > bestG) { bestG = g; bestF = f }; f *= 1.01 }
            val n = FS
            val s = DspTestKit.sine(n, bestF, 1.0)
            for (i in 0 until 4800) s[i] = (s[i] * (0.5 - 0.5 * kotlin.math.cos(kotlin.math.PI * i / 4800.0))).toFloat()
            eq.reset()
            val y = DspTestKit.run(eq, s)
            val m = DspTestKit.maxAbs(y)
            if (m > worstPeak) worstPeak = m
            assertTrue("set $k sine@${bestF}Hz clips: peak=$m", m <= 1.0f)
        }
        println("HEADROOM worst output peak over all stress runs = $worstPeak (limit 1.0; margin ${Headroom.SAFETY_MARGIN_DB} dB)")
    }

    @Test fun effectivePreampRules() {
        val p = EqParams(2f, DspTestKit.bands(3 to DspTestKit.peak(1000f, 6f, 1f)))
        val eq = ParametricEq(FS, 1)
        eq.setParams(p)
        assertEquals(2.0 - 8.3, eq.effectivePreampDb, 1e-6)
        eq.setParams(p.copy(autoHeadroom = false))
        assertEquals(2.0, eq.effectivePreampDb, 1e-9)
        eq.setParams(EqParams(-6f, DspTestKit.bands(3 to DspTestKit.peak(1000f, 3f, 1f))))
        assertEquals(-6.0, eq.effectivePreampDb, 1e-9)
        assertFalse(eq.effectivePreampDb > 6.0)
        // peak response with auto headroom is exactly -0.3 dB at the band centre
        eq.setParams(EqParams(0f, DspTestKit.bands(3 to DspTestKit.peak(1000f, 6f, 1f))))
        assertEquals(-0.3, eq.responseDb(640.0 * 0 + 640.0 * 1.5625), 1e-6)
    }
}
