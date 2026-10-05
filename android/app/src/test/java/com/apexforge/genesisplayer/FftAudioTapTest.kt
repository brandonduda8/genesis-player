package com.apexforge.genesisplayer

import com.apexforge.genesisplayer.ui.theme.deepspace.FFT_SIZE
import com.apexforge.genesisplayer.ui.theme.deepspace.computeRmsEnergy
import com.apexforge.genesisplayer.ui.theme.deepspace.radix2Fft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure-JVM tests for the FFT tap's internal math (no Android, no media3
 * classes touched: only the top-level [computeRmsEnergy] and [radix2Fft]
 * from the deepspace package are exercised here — the [FftAudioTap] object
 * itself is never initialized).
 */
class FftAudioTapTest {

    private val sampleRate = 44100

    /** Tiny sine generator: [freq] Hz, [amplitude] peak, [n] samples. */
    private fun sine(freq: Double, amplitude: Float, n: Int): FloatArray =
        FloatArray(n) { i ->
            (amplitude * sin(2.0 * PI * freq * i / sampleRate)).toFloat()
        }

    // ---------- computeRmsEnergy ----------

    @Test
    fun `silence has near-zero energy`() {
        val e = computeRmsEnergy(FloatArray(2048))
        assertEquals(0f, e, 1e-6f)
    }

    @Test
    fun `440Hz sine has positive energy`() {
        // RMS of a 0.5-peak sine is 0.5/sqrt(2) ≈ 0.354.
        val e = computeRmsEnergy(sine(440.0, 0.5f, 2048))
        assertTrue("expected energy > 0.2, got $e", e > 0.2f)
        assertEquals(0.5f / sqrt(2f), e, 0.01f)
    }

    @Test
    fun `sudden amplitude step produces a detectable energy delta`() {
        // Quiet half, then a 4x amplitude jump — the onset-style delta the
        // analysis thread keys off is simply rms(loud) - rms(quiet).
        val quiet = sine(440.0, 0.1f, 2048)
        val loud = sine(440.0, 0.4f, 2048)
        val eQuiet = computeRmsEnergy(quiet)
        val eLoud = computeRmsEnergy(loud)
        assertTrue("loud ($eLoud) must exceed quiet ($eQuiet)", eLoud > eQuiet)
        val delta = eLoud - eQuiet
        assertTrue("delta $delta must be clearly detectable", delta > 0.15f)
    }

    // ---------- radix2Fft ----------

    @Test
    fun `fft of 440Hz sine peaks at the expected bin`() {
        val n = FFT_SIZE
        val re = sine(440.0, 0.5f, n)
        val im = FloatArray(n)
        radix2Fft(re, im)

        // Expected bin: f * N / sampleRate = 440 * 2048 / 44100 ≈ 20.4.
        val expected = 440.0 * n / sampleRate

        var peakBin = 0
        var peakMag = 0f
        for (k in 1 until n / 2) {
            val m = sqrt(re[k] * re[k] + im[k] * im[k])
            if (m > peakMag) {
                peakMag = m
                peakBin = k
            }
        }
        assertTrue(
            "peak bin $peakBin not near expected $expected",
            abs(peakBin - expected) <= 1.0,
        )
    }

    @Test
    fun `fft of dc has peak at bin 0`() {
        val n = 1024
        val re = FloatArray(n) { 1f }
        val im = FloatArray(n)
        radix2Fft(re, im)
        val dc = sqrt(re[0] * re[0] + im[0] * im[0])
        assertEquals(n.toFloat(), dc, n * 1e-4f)
        for (k in 1 until n / 2) {
            val m = sqrt(re[k] * re[k] + im[k] * im[k])
            assertTrue("bin $k should be ~0, got $m", m < n * 1e-4f)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `fft rejects non-power-of-two length`() {
        radix2Fft(FloatArray(1000), FloatArray(1000))
    }
}
