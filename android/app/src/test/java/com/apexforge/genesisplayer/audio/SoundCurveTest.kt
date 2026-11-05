package com.apexforge.genesisplayer.audio

import com.apexforge.genesisplayer.audio.SoundCurve.Preset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SoundCurveTest {
    // A typical 5-band Android EQ: 60 Hz, 230 Hz, 910 Hz, 3.6 kHz, 14 kHz (in milliHz), range ±15 dB.
    private val centres = intArrayOf(60_000, 230_000, 910_000, 3_600_000, 14_000_000)
    private fun lv(p: Preset, min: Int = -1500, max: Int = 1500) = SoundCurve.bandLevels(p, centres, min, max)

    @Test fun flatIsExactlyFlatAndNeedsNoLimiter() {
        assertTrue(lv(Preset.FLAT).all { it == 0 })
        assertFalse(SoundCurve.limiterFor(lv(Preset.FLAT), bassBoostOn = false).enabled)
    }

    @Test fun eachPresetDoesWhatItsNameSays() {
        val bass = lv(Preset.BASS)
        assertTrue("bass: 60 Hz up", bass[0] >= 400)
        assertTrue("bass: lows above mids", bass[0] > bass[2])
        val vocal = lv(Preset.VOCAL)
        assertTrue("vocal: presence band above the lows", vocal[3] > vocal[0])
        assertTrue("vocal: lows cut", vocal[0] < 0)
        val night = lv(Preset.NIGHT)
        assertTrue("night: deep bass cut", night[0] < 0)
        assertTrue("night: top end cut", night[4] < 0)
        assertTrue("presets differ", SoundCurve.distanceMb(bass, vocal) > 300)
    }

    @Test fun levelsComeInWholeDecibelsLikeTheDeviceHoldsThem() {
        for (p in Preset.values()) assertTrue(p.name, lv(p).all { it % 100 == 0 })
    }

    @Test fun boostIsCappedAndDeviceRangeRespected() {
        for (p in Preset.values()) assertTrue(lv(p).all { it <= SoundCurve.MAX_BOOST_MB })
        val narrow = lv(Preset.BASS, min = -300, max = 300)
        assertTrue(narrow.all { it in -300..300 })
        assertEquals(300, narrow[0])
    }

    @Test fun differentDeviceBandsStillGetASensibleCurve() {
        val tenBand = intArrayOf(31_000, 62_000, 125_000, 250_000, 500_000, 1_000_000, 2_000_000, 4_000_000, 8_000_000, 16_000_000)
        val b = SoundCurve.bandLevels(Preset.BASS, tenBand, -1200, 1200)
        assertEquals(10, b.size)
        assertTrue("monotone-ish fall from the low end", b[0] >= b[4] && b[4] >= b[6])
    }

    @Test fun boostingPresetsGetHeadroomAndALimiterUnderFullScale() {
        val l = SoundCurve.limiterFor(lv(Preset.BASS), bassBoostOn = true)
        assertTrue(l.enabled)
        assertTrue("pre-gain reduces level", l.inputGainDb < 0f)
        assertTrue("ceiling under 0 dBFS", l.thresholdDb < 0f)
        assertTrue("headroom bounded", l.inputGainDb >= -4.5f)
        assertTrue("bass boost alone still gets protection", SoundCurve.limiterFor(lv(Preset.FLAT), bassBoostOn = true).enabled)
    }
}
