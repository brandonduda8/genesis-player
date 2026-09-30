package com.apexforge.genesisplayer.dsp

import com.apexforge.genesisplayer.dsp.DspTestKit.FS
import com.apexforge.genesisplayer.dsp.DspTestKit.bands
import com.apexforge.genesisplayer.dsp.DspTestKit.highShelf
import com.apexforge.genesisplayer.dsp.DspTestKit.lowShelf
import com.apexforge.genesisplayer.dsp.DspTestKit.peak
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParametricEqTest {
    private val noLimit = EqParams(limiterEnabled = false, autoHeadroom = false)

    private fun shifted(y: FloatArray, lag: Int) = FloatArray(y.size - lag) { y[it + lag] }

    @Test fun flatParamsAreTransparentOnNoise() {
        for (lim in listOf(true, false)) for (ch in 1..2) {
            val x = DspTestKit.noise(FS * ch, 0.9, 42L)
            val eq = ParametricEq(FS, ch)
            eq.setParams(EqParams(limiterEnabled = lim))
            val y = DspTestKit.run(eq, x, 1001 * ch)
            val lag = eq.latencyFrames * ch
            var maxErr = 0.0
            for (i in lag until x.size) maxErr = maxOf(maxErr, abs((y[i] - x[i - lag]).toDouble()))
            assertTrue("flat err $maxErr", maxErr <= 1e-6)
            assertEquals(0.0, maxErr, 0.0)   // in fact bit exact
            assertFalse(eq.limiterActive)
        }
    }

    @Test fun zeroGainBandsAndDisabledBandsAreExactIdentity() {
        val x = DspTestKit.noise(FS, 0.8, 7L)
        val eq = ParametricEq(FS, 1)
        eq.setParams(EqParams(0f, bands(2 to peak(1000f, 0f, 3f), 0 to lowShelf(100f, 0f), 7 to highShelf(8000f, 12f, 1f, ).copy(enabled = false)), limiterEnabled = false))
        val y = DspTestKit.run(eq, x)
        val lag = eq.latencyFrames
        for (i in lag until x.size) assertEquals(x[i - lag], y[i], 0f)
    }

    @Test fun measuredSineGainMatchesResponseDb() {
        val p = EqParams(
            -2f,
            bands(
                0 to lowShelf(120f, 7f, 0.8f), 1 to peak(200f, -6f, 2f), 2 to peak(450f, 5f, 1.5f), 3 to peak(1000f, 9f, 4f),
                4 to peak(2400f, -8f, 0.7f), 5 to peak(5000f, 4f, 3f), 6 to peak(9000f, -3f, 1f), 7 to highShelf(12000f, 6f, 0.9f)
            ),
            limiterEnabled = false, autoHeadroom = true
        )
        val eq = ParametricEq(FS, 1)
        eq.setParams(p)
        val freqs = doubleArrayOf(50.0, 90.0, 150.0, 250.0, 500.0, 800.0, 1000.0, 1700.0, 3000.0, 5200.0, 8000.0, 11000.0, 14000.0, 18000.0)
        var worst = 0.0
        for (f in freqs) {
            val m = DspTestKit.measuredGainDb(eq, f)
            val d = abs(m - eq.responseDb(f))
            worst = maxOf(worst, d)
            assertTrue("f=$f measured=$m expected=${eq.responseDb(f)}", d <= 0.05)
        }
        println("ENGINE max |measured - responseDb| over ${freqs.size} freqs = $worst dB")
    }

    private fun smoothingScenario(from: EqParams, to: EqParams, freq: Double, amp: Double): Triple<Double, Double, Double> {
        val n = FS
        val x = DspTestKit.sine(n, freq, amp)
        val half = n / 2
        // steady reference at the final settings
        val ref = ParametricEq(FS, 1); ref.setParams(to)
        val yref = DspTestKit.run(ref, x)
        val lag = ref.latencyFrames
        val from2 = half          // steady region for reference stats
        val refJump = DspTestKit.maxJump(yref, from2)
        val refPeak = DspTestKit.maxAbs(yref, from2).toDouble()
        // live run with a switch mid-stream (inside a chunk boundary)
        val eq = ParametricEq(FS, 1); eq.setParams(from)
        val y = x.copyOf()
        var f = 0
        while (f < n) {
            val m = minOf(480, n - f)
            if (f == half) eq.setParams(to)
            eq.process(y, f, m)
            f += m
        }
        val jump = DspTestKit.maxJump(y, 2 * lag)
        val peakOut = DspTestKit.maxAbs(y, 2 * lag).toDouble()
        return Triple(jump / refJump, peakOut / refPeak, refPeak)
    }

    @Test fun lowShelfSwitchMidStreamHasNoClick() {
        val from = noLimit
        val to = noLimit.copy(bands = bands(0 to lowShelf(150f, 12f)))
        val (jumpRatio, envRatio, _) = smoothingScenario(from, to, 100.0, 0.5)
        println("SMOOTHING low-shelf +12 dB @100 Hz: max jump / steady-reference max jump = $jumpRatio ; peak / expected envelope = $envRatio")
        assertTrue("click ratio $jumpRatio", jumpRatio <= 2.0)
        assertTrue("envelope ratio $envRatio", envRatio <= 1.5)
    }

    @Test fun peakAndFrequencyAndQChangesHaveNoClick() {
        val from = noLimit.copy(bands = bands(3 to peak(1000f, -12f, 10f)))
        val to = noLimit.copy(bands = bands(3 to peak(1100f, 12f, 0.3f)))
        val (jr, er, _) = smoothingScenario(from, to, 1000.0, 0.3)
        println("SMOOTHING peak -12/Q10 -> +12/Q0.3 @1 kHz: jump ratio = $jr ; envelope ratio = $er")
        assertTrue("click ratio $jr", jr <= 2.0)
        assertTrue("envelope ratio $er", er <= 1.5)
    }

    @Test fun preampStepHasNoClick() {
        val from = noLimit.copy(preampDb = -12f)
        val to = noLimit.copy(preampDb = 6f)
        val (jr, er, _) = smoothingScenario(from, to, 300.0, 0.25)
        println("SMOOTHING preamp -12 -> +6 dB @300 Hz: jump ratio = $jr ; envelope ratio = $er")
        assertTrue("click ratio $jr", jr <= 2.0)
        assertTrue("envelope ratio $er", er <= 1.5)
        // negative control: a hard splice of the two steady outputs WOULD fail the same criterion
        val a = ParametricEq(FS, 1); a.setParams(from)
        val b = ParametricEq(FS, 1); b.setParams(to)
        val x = DspTestKit.sine(FS, 300.0, 0.25)
        val ya = DspTestKit.run(a, x); val yb = DspTestKit.run(b, x)
        val spliced = FloatArray(FS) { if (it < FS / 2) ya[it] else yb[it] }
        val ctrl = DspTestKit.maxJump(spliced, 2 * a.latencyFrames) / DspTestKit.maxJump(yb, FS / 2)
        println("SMOOTHING negative control (hard splice) jump ratio = $ctrl")
        assertTrue(ctrl > 2.0)
    }

    @Test fun bypassCrossfadesWithoutDiscontinuityAndIsBitExactWhenBypassed() {
        val wet = noLimit.copy(bands = bands(3 to peak(1000f, 12f, 1f)))
        val n = 2 * FS
        val x = DspTestKit.sine(n, 1000.0, 0.4)
        val ref = ParametricEq(FS, 1); ref.setParams(wet)
        val yref = DspTestKit.run(ref, x)
        val refJump = DspTestKit.maxJump(yref, FS / 2)
        val eq = ParametricEq(FS, 1); eq.setParams(wet)
        val y = x.copyOf()
        var f = 0
        val onAt = FS / 2; val offAt = FS + FS / 4
        while (f < n) {
            val m = minOf(500, n - f)
            if (f == onAt) eq.setParams(wet.copy(bypass = true))
            if (f == offAt) eq.setParams(wet.copy(bypass = false))
            eq.process(y, f, m)
            f += m
        }
        val lag = eq.latencyFrames
        val jump = DspTestKit.maxJump(y, 2 * lag)
        println("BYPASS toggle: max jump / wet steady reference max jump = ${jump / refJump}")
        assertTrue("bypass jump ratio ${jump / refJump}", jump <= 1.5 * refJump)
        // fully bypassed region is bit exact (ramp 20 ms + limiter delay margin)
        for (i in onAt + 2000 until offAt - 10) assertEquals("sample $i", x[i - lag], y[i], 0f)
        // back on: matches the reference after the ramp
        val lastStart = offAt + 3000
        for (i in lastStart until n) assertEquals(yref[i], y[i], 2e-5f)
    }

    @Test fun disablingABandEndsExactlyTransparent() {
        val eq = ParametricEq(FS, 1)
        val on = noLimit.copy(bands = bands(0 to lowShelf(80f, 12f, 4f), 1 to peak(120f, 12f, 10f)))
        eq.setParams(on)
        val warm = DspTestKit.noise(FS, 0.3, 4L)
        DspTestKit.run(eq, warm)
        eq.setParams(noLimit)
        val x = DspTestKit.noise(8 * FS, 0.3, 5L)
        val y = DspTestKit.run(eq, x)
        val lag = eq.latencyFrames
        for (i in 7 * FS until x.size) assertEquals("sample $i", x[i - lag], y[i], 0f)
        assertTrue(DspTestKit.allFinite(y))
    }

    @Test fun latencyIsConstantWhetherLimiterOnOffOrBypassed() {
        for (p in listOf(EqParams(limiterEnabled = true), EqParams(limiterEnabled = false), EqParams(bypass = true))) {
            val eq = ParametricEq(FS, 1)
            eq.setParams(p)
            val x = FloatArray(500); x[0] = 0.4f
            val y = DspTestKit.run(eq, x, 77)
            for (i in y.indices) assertEquals(if (i == eq.latencyFrames) 0.4f else 0f, y[i], 0f)
        }
        assertEquals(72, ParametricEq(FS, 1).latencyFrames)
    }

    @Test fun limiterLastInChainBoundsOutput() {
        val p = EqParams(0f, bands(3 to peak(1000f, 12f, 1f), 4 to peak(1200f, 12f, 1f)), limiterEnabled = true, autoHeadroom = false)
        val eq = ParametricEq(FS, 2)
        eq.setParams(p)
        val x = FloatArray(2 * FS)
        val s = DspTestKit.sine(FS, 1100.0, 0.9)
        for (i in 0 until FS) { x[2 * i] = s[i]; x[2 * i + 1] = s[i] * 0.5f }
        var sawActive = false
        val y = x.copyOf()
        var f = 0
        while (f < FS) { val m = minOf(480, FS - f); eq.process(y, 2 * f, m); if (eq.limiterActive) sawActive = true; f += m }
        assertTrue(sawActive)
        assertTrue(eq.gainReductionDb >= 0.0)
        val lim = 10.0.pow(-0.3 / 20.0)
        assertTrue(DspTestKit.maxAbs(y) <= lim)
        // quiet signal: limiter idle
        eq.reset()
        val q = DspTestKit.sine(FS, 100.0, 0.01)
        eq.process(q, 0, q.size / 2)
        assertFalse(eq.limiterActive)
        assertEquals(0.0, eq.gainReductionDb, 0.0)
    }

    private fun Double.pow(e: Double) = Math.pow(this, e)

    @Test fun garbageParamsAndInputStayFinite() {
        val nan = Float.NaN
        val p = EqParams(nan, List(8) { BandParams(BandType.PEAK, if (it == 1) nan else Float.POSITIVE_INFINITY, if (it == 2) nan else 1e30f, if (it == 3) -4f else nan) }, autoHeadroom = true)
        val eq = ParametricEq(FS, 1)
        eq.setParams(p)
        val x = DspTestKit.noise(FS / 2, 0.5, 8L)
        x[100] = nan; x[200] = Float.POSITIVE_INFINITY
        val y = DspTestKit.run(eq, x, 33)
        assertTrue(DspTestKit.allFinite(y))
        assertNotNull(eq.effectivePreampDb)
        assertTrue(!eq.responseDb(1000.0).isNaN())
        // odd tiny chunk sizes
        val z = DspTestKit.run(eq, DspTestKit.noise(1000, 0.1, 1L), 1)
        assertTrue(DspTestKit.allFinite(z))
        eq.process(FloatArray(4), 0, 0)
    }

    @Test fun setParamsFromAnotherThreadWhileProcessing() {
        val eq = ParametricEq(FS, 2)
        val stop = java.util.concurrent.atomic.AtomicBoolean(false)
        val errors = java.util.concurrent.atomic.AtomicInteger(0)
        val t = Thread {
            val r = DspTestKit.Rng(5L)
            try {
                while (!stop.get()) {
                    val b = List(8) { i -> BandParams(if (i == 0) BandType.LOW_SHELF else if (i == 7) BandType.HIGH_SHELF else BandType.PEAK, r.logUniform(20.0, 20000.0).toFloat(), r.uniform(-12.0, 12.0).toFloat(), r.logUniform(0.3, 10.0).toFloat(), r.next() > 0) }
                    eq.setParams(EqParams(r.uniform(-12.0, 6.0).toFloat(), b, bypass = r.next() > 0.7))
                    Thread.sleep(1)
                }
            } catch (e: Throwable) { errors.incrementAndGet() }
        }
        t.start()
        val x = DspTestKit.noise(2 * FS, 0.5, 3L)
        val y = DspTestKit.run(eq, x, 480)
        stop.set(true); t.join()
        assertEquals(0, errors.get())
        assertTrue(DspTestKit.allFinite(y))
    }
}
