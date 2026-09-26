package com.apexforge.genesisplayer

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.apexforge.genesisplayer.data.Energy
import com.apexforge.genesisplayer.data.EnergyRules
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * API-34 instrumented tests for Golden Player Phase 1's pure mappings — the
 * pins that FourStageEq.kt and Energy.kt document:
 *  1. FourStageEq maps Boost to real BassBoost strength (0..1000 per-mille,
 *     shown as %, never dB) and Low/Mid/High to Equalizer bands by centre
 *     frequency, clamped to the device's real band-level range.
 *  2. EnergyRules only votes from catalog tags; untagged/tied tracks are Middle.
 */
@RunWith(AndroidJUnit4::class)
class GoldenPhase1Test {

    @Test
    fun bassStrengthIsPerMilleAndClamped() {
        assertEquals(0, FourStageEq.bassStrength(0))
        assertEquals(1000, FourStageEq.bassStrength(12))
        assertEquals(500, FourStageEq.bassStrength(6))
        assertEquals(1000, FourStageEq.bassStrength(99))
        assertEquals(0, FourStageEq.bassStrength(-3))
    }

    @Test
    fun boostReadsAsPercentOfStrength() {
        assertEquals(0, FourStageEq.boostPercent(0))
        assertEquals(50, FourStageEq.boostPercent(6))
        assertEquals(100, FourStageEq.boostPercent(12))
        assertEquals(83, FourStageEq.boostPercent(10)) // 833 per-mille
    }

    @Test
    fun bandsMapToStagesByCentreFrequency() {
        // Standard 5-band centres: 60, 230 Hz -> Low; 910, 3600 Hz -> Mid; 14 kHz -> High.
        val stages = FourStageEq.DEFAULT_CENTERS_HZ.map { FourStageEq.stageOf(it) }
        assertEquals(listOf(0, 0, 1, 1, 2), stages)
        assertEquals(0, FourStageEq.stageOf(399))
        assertEquals(1, FourStageEq.stageOf(400))
        assertEquals(2, FourStageEq.stageOf(4000))
    }

    @Test
    fun bandLevelIsMillibelsClampedToDeviceRange() {
        assertEquals(500, FourStageEq.bandLevelMb(5, -1500, 1500))
        assertEquals(-1200, FourStageEq.bandLevelMb(-12, -1500, 1500))
        assertEquals(900, FourStageEq.bandLevelMb(12, -900, 900))
        assertEquals(-900, FourStageEq.bandLevelMb(-12, -900, 900))
    }

    @Test
    fun presetsAreTheWebFourAndSelfNamed() {
        assertEquals(listOf("Flat", "Bass Heavy", "Bright", "Vocal"), FourStageEq.PRESETS.keys.toList())
        FourStageEq.PRESETS.forEach { (name, s) -> assertEquals(name, s.preset) }
        val flat = FourStageEq.PRESETS.getValue("Flat")
        assertFalse(flat.bassOn)
        assertEquals(0, FourStageEq.stageDb(flat, 0) + FourStageEq.stageDb(flat, 1) + FourStageEq.stageDb(flat, 2))
    }

    @Test
    fun energyVotesFromTagsOnly() {
        assertEquals(Energy.BANGER, EnergyRules.infer("Trap", "", emptyList()))
        assertEquals(Energy.SOFT, EnergyRules.infer("", "Melancholy", emptyList()))
        assertEquals(Energy.SOFT, EnergyRules.infer("", "", listOf("Cloud Nine")))
        // Untagged and tied tracks are honestly "in between".
        assertEquals(Energy.MID, EnergyRules.infer("", "", emptyList()))
        assertEquals(Energy.MID, EnergyRules.infer("Trap", "Melancholy", emptyList()))
        // Two banger votes beat one soft vote.
        assertEquals(Energy.BANGER, EnergyRules.infer("Phonk", "Melancholy", listOf("Rap Rotation")))
    }
}
