package com.apexforge.genesisplayer.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM fast lane (no emulator): [EnergyRules.infer] voting. Energy is only
 * ever read from catalog tags — ties and untagged tracks are honestly MID.
 */
class EnergyRulesTest {

    @Test
    fun untaggedTrackIsMiddle() {
        assertEquals(Energy.MID, EnergyRules.infer("", "", emptyList()))
    }

    @Test
    fun unknownGenreIsMiddle() {
        assertEquals(Energy.MID, EnergyRules.infer("polka", "", emptyList()))
    }

    @Test
    fun matchingIsCaseAndWhitespaceInsensitive() {
        assertEquals(Energy.BANGER, EnergyRules.infer("  TRAP ", "Defiant", emptyList()))
        assertEquals(Energy.SOFT, EnergyRules.infer("Lo-Fi", "  CALM", emptyList()))
    }

    @Test
    fun opposingVotesTieToMiddle() {
        assertEquals(Energy.MID, EnergyRules.infer("trap", "calm", emptyList()))
        assertEquals(Energy.MID, EnergyRules.infer("jazz", "", listOf("alt bangers")))
    }

    @Test
    fun shelvesVote() {
        assertEquals(Energy.SOFT, EnergyRules.infer("", "", listOf("Cloud Nine")))
        assertEquals(Energy.BANGER, EnergyRules.infer("", "", listOf(" Rap Rotation ")))
    }

    @Test
    fun multipleMatchingShelvesCountAsOneVote() {
        // Two banger shelves = one up vote; soft genre + soft mood = two down.
        assertEquals(
            Energy.SOFT,
            EnergyRules.infer("ambient", "peaceful", listOf("alt bangers", "rap rotation"))
        )
    }

    @Test
    fun majorityWins() {
        assertEquals(Energy.BANGER, EnergyRules.infer("trap", "calm", listOf("Alt Bangers")))
    }
}
