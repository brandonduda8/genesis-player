package com.apexforge.genesisplayer.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM fast lane (no emulator): boundary pins for [CrossfadeMath.shouldEngage].
 * BrknWavesTest covers the happy/off paths on API-34; these pin the exact
 * millisecond edges so an off-by-one in the watcher tick fails in seconds.
 */
class CrossfadeMathTest {

    private fun engage(pos: Long, dur: Long = 200_000L, xfade: Float = 6f,
                       hasNext: Boolean = true, playing: Boolean = true) =
        CrossfadeMath.shouldEngage(pos, dur, xfade, hasNext, playing)

    @Test
    fun engagesExactlyAtThreshold() {
        assertTrue(engage(pos = 194_000L))
    }

    @Test
    fun doesNotEngageOneMsBeforeThreshold() {
        assertFalse(engage(pos = 193_999L))
    }

    @Test
    fun fractionalSecondsAreHonoured() {
        assertTrue(engage(pos = 200_000L - 2_500L, xfade = 2.5f))
        assertFalse(engage(pos = 200_000L - 2_501L, xfade = 2.5f))
    }

    @Test
    fun positionPastReportedDurationStillEngages() {
        assertTrue(engage(pos = 201_000L))
    }

    @Test
    fun negativeCrossfadeIsOff() {
        assertFalse(engage(pos = 199_000L, xfade = -1f))
    }

    @Test
    fun everyGuardIndependentlyBlocks() {
        assertFalse(engage(pos = 199_000L, hasNext = false))
        assertFalse(engage(pos = 199_000L, playing = false))
        assertFalse(engage(pos = 199_000L, xfade = 0f))
        assertFalse(engage(pos = 0L, dur = 0L))
        assertFalse(engage(pos = 0L, dur = -1L))
    }
}
