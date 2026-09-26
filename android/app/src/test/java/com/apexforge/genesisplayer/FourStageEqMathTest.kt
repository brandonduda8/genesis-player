package com.apexforge.genesisplayer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM fast lane (no emulator): the pure mappings in [FourStageEq]. Only the
 * Context-free functions are exercised; load()/apply() stay on the emulator.
 */
class FourStageEqMathTest {

    @Test
    fun stageBoundariesAre400HzAnd4kHz() {
        assertEquals(0, FourStageEq.stageOf(0))
        assertEquals(0, FourStageEq.stageOf(399))
        assertEquals(1, FourStageEq.stageOf(400))
        assertEquals(1, FourStageEq.stageOf(3999))
        assertEquals(2, FourStageEq.stageOf(4000))
    }

    @Test
    fun defaultFiveBandsSplitTwoTwoOne() {
        val stages = FourStageEq.DEFAULT_CENTERS_HZ.map { FourStageEq.stageOf(it) }.toIntArray()
        assertArrayEquals(intArrayOf(0, 0, 1, 1, 2), stages)
    }

    @Test
    fun boostPercentRoundsToNearest() {
        assertEquals(0, FourStageEq.boostPercent(0))
        assertEquals(8, FourStageEq.boostPercent(1))    // 83 per-mille
        assertEquals(50, FourStageEq.boostPercent(6))   // 500
        assertEquals(58, FourStageEq.boostPercent(7))   // 583
        assertEquals(100, FourStageEq.boostPercent(12)) // 1000
        assertEquals(100, FourStageEq.boostPercent(40)) // clamped
    }

    @Test
    fun bandLevelClampsToAnyDeviceRange() {
        assertEquals(500, FourStageEq.bandLevelMb(5, -1500, 1500))
        assertEquals(1500, FourStageEq.bandLevelMb(20, -1500, 1500))
        assertEquals(-1500, FourStageEq.bandLevelMb(-20, -1500, 1500))
        assertEquals(1200, FourStageEq.bandLevelMb(12, -1200, 1200))
        assertEquals(-1200, FourStageEq.bandLevelMb(-12, -1200, 1200))
    }

    @Test
    fun stageDbPicksTheMatchingSlider() {
        val s = FourStageEq.State(true, 4, low = 1, mid = 2, high = 3, preset = "Custom")
        assertEquals(1, FourStageEq.stageDb(s, 0))
        assertEquals(2, FourStageEq.stageDb(s, 1))
        assertEquals(3, FourStageEq.stageDb(s, 2))
    }

    @Test
    fun presetsAreOrderedSelfNamedAndInRange() {
        assertEquals(
            listOf("Flat", "Bass Heavy", "Bright", "Vocal"),
            FourStageEq.PRESETS.keys.toList()
        )
        FourStageEq.PRESETS.forEach { (name, s) ->
            assertEquals(name, s.preset)
            assertTrue("$name bass", s.bass in 0..12)
            listOf(s.low, s.mid, s.high).forEach { db ->
                assertTrue("$name dB $db", db in -12..12)
            }
        }
    }
}
