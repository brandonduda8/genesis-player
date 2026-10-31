package com.apexforge.genesisplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** JVM fast lane for the Hi-Fi P1 EQ engine. Expected values were cross-checked against an independent Python port. */
class ParametricEqTest {
    private val fs = 48_000.0

    private fun asset(name: String): String {
        val candidates = listOf("src/main/assets/autoeq/$name", "app/src/main/assets/autoeq/$name",
            "android/app/src/main/assets/autoeq/$name")
        return candidates.map { File(it) }.first { it.exists() }.readText()
    }

    @Test
    fun peakingFilterHitsItsGainAtCentreFrequency() {
        val c = ParametricEq.coeffs(ParametricEq.Filter(ParametricEq.Type.PK, 1000.0, 6.0, 1.41), fs)
        assertEquals(6.0, ParametricEq.magnitudeDb(c, 1000.0, fs), 1e-6)
        assertEquals(0.0, ParametricEq.magnitudeDb(c, 20.0, fs), 0.05) // far from fc it is transparent
    }

    @Test
    fun shelvesReachTheirGainAtTheBandEdges() {
        val low = ParametricEq.coeffs(ParametricEq.Filter(ParametricEq.Type.LSC, 105.0, 7.7, 0.7), fs)
        val high = ParametricEq.coeffs(ParametricEq.Filter(ParametricEq.Type.HSC, 10_000.0, -2.5, 0.7), fs)
        assertEquals(7.68, ParametricEq.magnitudeDb(low, 20.0, fs), 0.02)
        assertEquals(-2.49, ParametricEq.magnitudeDb(high, 20_000.0, fs), 0.02)
    }

    @Test
    fun parsesAutoEqProfilesExactly() {
        val p = ParametricEq.parseAutoEq("Beats Studio3 Wireless", asset("beats_studio3_wireless.txt"))
        assertEquals(-6.9, p.preampDb, 1e-9)
        assertEquals(10, p.filters.size)
        assertEquals(ParametricEq.Filter(ParametricEq.Type.LSC, 105.0, 7.7, 0.70), p.filters[0])
        val ignored = ParametricEq.parseAutoEq("x", "Preamp: -1 dB\nFilter 1: OFF PK Fc 100 Hz Gain 3 dB Q 1\ngarbage")
        assertTrue(ignored.filters.isEmpty())
    }

    @Test
    fun computedHeadroomMatchesAutoEqsPublishedPreamp() {
        for ((file, published) in listOf("beats_studio3_wireless.txt" to -6.9, "beats_studio_buds.txt" to -4.3)) {
            val p = ParametricEq.parseAutoEq(file, asset(file))
            val safe = ParametricEq.safePreampDb(p.filters, fs)
            assertEquals("$file headroom", published, safe, 0.15)
            assertTrue(safe <= 0.0)
        }
    }

    @Test
    fun chainMatchesTheAnalyticResponseOnARealSineAndNeverClips() {
        val p = ParametricEq.parseAutoEq("s3", asset("beats_studio3_wireless.txt"))
        val pre = ParametricEq.safePreampDb(p.filters, fs)
        for (freq in listOf(60.0, 1000.0, 5209.0)) {
            val n = 48_000
            val buf = FloatArray(n * 2) { i -> sin(2 * PI * freq * (i / 2) / fs).toFloat() } // full-scale stereo
            ParametricEq.Chain(p.filters, fs, 2, pre).process(buf)
            var sumSq = 0.0; var peak = 0.0
            for (i in (n / 2) * 2 until n * 2 step 2) { // skip the settling half second
                sumSq += buf[i] * buf[i]; peak = max(peak, abs(buf[i].toDouble()))
            }
            val measuredDb = 20 * log10(sqrt(sumSq / (n / 2)) * sqrt(2.0))
            val expectedDb = ParametricEq.responseDb(p.filters, freq, fs) + pre
            assertEquals("gain at $freq Hz", expectedDb, measuredDb, 0.1)
            assertTrue("no clipping at $freq Hz (peak $peak)", peak <= 1.0 + 1e-3)
        }
    }

    @Test
    fun flatProfileIsBitTransparent() {
        val buf = FloatArray(1024) { i -> sin(i * 0.1).toFloat() * 0.5f }
        val copy = buf.copyOf()
        ParametricEq.Chain(emptyList(), fs, 2, 0.0).process(buf)
        assertTrue(buf.contentEquals(copy))
    }
}
