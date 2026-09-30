package com.apexforge.genesisplayer.dsp

import com.apexforge.genesisplayer.dsp.DspTestKit.FS
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LimiterTest {
    private fun runLim(l: Limiter, x: FloatArray, chunk: Int = 777): FloatArray {
        val y = x.copyOf()
        val ch = l.channels
        var f = 0
        val frames = y.size / ch
        while (f < frames) { val n = minOf(chunk, frames - f); l.process(y, f * ch, n); f += n }
        return y
    }

    private fun fixtures(): List<Pair<String, FloatArray>> {
        val n = 2 * FS
        val list = ArrayList<Pair<String, FloatArray>>()
        for (f in doubleArrayOf(40.0, 1000.0, 8000.0)) list.add("sine$f" to DspTestKit.sine(n, f, 1.0))
        list.add("square" to FloatArray(n) { if ((it / 50) % 2 == 0) 1f else -1f })
        list.add("square-fast" to FloatArray(n) { if ((it / 3) % 2 == 0) 1f else -1f })
        list.add("noise4" to DspTestKit.noise(n, 4.0, 5L))
        val bursts = DspTestKit.sine(n, 440.0, 0.25)
        val g = 10.0.pow(12.0 / 20.0).toFloat()
        var s = 3000
        while (s + 500 < n) { for (i in s until s + 500) bursts[i] = (sin(2 * PI * 2000 * i / FS) * g).toFloat(); s += 7000 }
        list.add("bursts+12dB" to bursts)
        list.add("dc-over" to FloatArray(n) { 3.0f })
        return list
    }

    @Test fun neverExceedsCeilingOnStressFixtures() {
        var worst = -1.0
        for ((name, x) in fixtures()) {
            val l = Limiter(FS, 1)
            val y = runLim(l, x)
            val m = DspTestKit.maxAbs(y)
            worst = maxOf(worst, m - l.ceilingLinear.toDouble())
            assertTrue("$name exceeds: $m > ${l.ceilingLinear}", m <= l.ceilingLinear)
            assertTrue(name, m <= 10.0.pow(-0.3 / 20.0))
            assertTrue(DspTestKit.allFinite(y))
        }
        println("LIMITER max overshoot above ceiling over fixtures (negative = under) = $worst")
    }

    @Test fun ceilingHoldsForNanInfAndStereoLinked() {
        val n = FS
        val x = DspTestKit.noise(2 * n, 2.0, 11L)
        x[1000] = Float.NaN; x[2001] = Float.POSITIVE_INFINITY; x[3000] = Float.NEGATIVE_INFINITY; x[5001] = Float.NaN
        val l = Limiter(FS, 2)
        val y = runLim(l, x, 513)
        assertTrue(DspTestKit.allFinite(y))
        assertTrue(DspTestKit.maxAbs(y) <= l.ceilingLinear)
        // chunk-size invariance
        val l2 = Limiter(FS, 2)
        val y2 = runLim(l2, x, 4096)
        for (i in y.indices) assertEquals(y[i], y2[i], 0f)
    }

    @Test fun linkedChannelsGetIdenticalGain() {
        val n = FS / 2
        val x = FloatArray(2 * n) { if (it % 2 == 0) (3.0 * sin(2 * PI * 200 * (it / 2) / FS)).toFloat() else (0.1 * sin(2 * PI * 200 * (it / 2) / FS)).toFloat() }
        val l = Limiter(FS, 2)
        val y = runLim(l, x)
        val lag = l.latencyFrames
        var checked = 0
        for (f in lag until n) {
            val xr = x[2 * (f - lag) + 1]
            if (abs(xr) > 0.05f) {
                val gr = y[2 * f + 1].toDouble() / xr
                val xl = x[2 * (f - lag)]
                if (abs(xl) > 0.5f) {
                    val gl = y[2 * f].toDouble() / xl
                    assertEquals(gl, gr, 1e-5)
                    checked++
                }
            }
        }
        assertTrue(checked > 1000)
    }

    @Test fun transparentExactlyBelowCeilingAfterLatencyAlignment() {
        val n = 2 * FS
        val base = DspTestKit.sine(n, 997.0, 0.5)              // -6 dBFS
        val nz = DspTestKit.noise(n, 0.25, 3L)
        val x = FloatArray(n) { base[it] + nz[it] * 0.5f }
        assertTrue(DspTestKit.maxAbs(x) < 0.966f)
        val l = Limiter(FS, 1)
        val y = runLim(l, x)
        val lag = l.latencyFrames
        for (i in 0 until lag) assertEquals(0f, y[i], 0f)
        for (i in lag until n) assertEquals("sample $i", x[i - lag], y[i], 0f)
        assertEquals(0.0, l.gainReductionDb, 0.0)
    }

    @Test fun latencyIsFixedAndAboutOnePointFiveMs() {
        val l = Limiter(FS, 1)
        assertEquals(72, l.latencyFrames)
        val x = FloatArray(1000); x[0] = 0.5f
        val y = runLim(l, x, 100)
        for (i in y.indices) assertEquals(if (i == l.latencyFrames) 0.5f else 0f, y[i], 0f)
        assertEquals(1, Limiter(100, 1).latencyFrames)
    }

    @Test fun releasesBackToExactUnityAfterOvers() {
        val n = 3 * FS
        val x = DspTestKit.sine(n, 500.0, 0.3)
        for (i in 10000 until 10400) x[i] = (3.0 * sin(2 * PI * 500 * i / FS)).toFloat()
        val l = Limiter(FS, 1)
        val y = runLim(l, x)
        assertTrue(DspTestKit.maxAbs(y) <= l.ceilingLinear)
        val lag = l.latencyFrames
        // by 2.5 s the release is complete and the path is exact again
        for (i in 2 * FS + 500 until n) assertEquals("sample $i", x[i - lag], y[i], 0f)
        // reduction was reported for the burst
        val l2 = Limiter(FS, 1)
        val first = x.copyOfRange(0, 12000)
        l2.process(first, 0, first.size)
        assertTrue(l2.gainReductionDb > 9.0)
    }

    @Test fun disabledKeepsDelayAndDoesNotLimit() {
        val l = Limiter(FS, 1); l.enabled = false
        val x = DspTestKit.noise(5000, 3.0, 9L)
        val y = runLim(l, x)
        for (i in l.latencyFrames until x.size) assertEquals(x[i - l.latencyFrames], y[i], 0f)
    }

    @Test fun resetClearsState() {
        val l = Limiter(FS, 1)
        runLim(l, DspTestKit.noise(5000, 4.0, 2L))
        l.reset()
        assertEquals(0.0, l.gainReductionDb, 0.0)
        val x = FloatArray(300); x[0] = 0.25f
        val y = runLim(l, x)
        assertEquals(0.25f, y[l.latencyFrames], 0f)
        assertFalse(y.any { it.isNaN() })
    }
}
