package com.apexforge.genesisplayer.data.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultTierTest {
    private fun bytes(vararg parts: Any): ByteArray = parts.flatMap { p ->
        when (p) { is String -> p.toByteArray(Charsets.ISO_8859_1).toList(); is Int -> List(p) { 0.toByte() }; else -> error("") }
    }.toByteArray()

    @Test fun tierComesFromTheAudiosOwnBytes() {
        assertEquals(Tier.LOSSLESS, VaultIndex.tierOf(bytes("fLaC", 60)))
        assertEquals(Tier.LOSSLESS, VaultIndex.tierOf(bytes("RIFF", 4, "WAVEfmt ", 40)))
        assertEquals(Tier.LOSSLESS, VaultIndex.tierOf(bytes(4, "ftypM4A ", 200, "stsd", 12, "alac", 50)))
        assertEquals("AAC in MP4 is lossy", Tier.BULK, VaultIndex.tierOf(bytes(4, "ftypM4A ", 200, "stsd", 12, "mp4a", 50)))
        assertEquals(Tier.BULK, VaultIndex.tierOf(bytes("ID3", 100)))
        assertEquals(Tier.BULK, VaultIndex.tierOf(bytes("OggS", 24, "OpusHead", 20)))
        assertEquals(Tier.BULK, VaultIndex.tierOf(ByteArray(0)))
        assertEquals("only the bytes actually read count", Tier.BULK, VaultIndex.tierOf(bytes("fLaC", 60), 3))
    }

    private fun e(id: String, mb: Long, at: Long, t: Tier) = VaultEntry(id, id, "", "", mb shl 20, mb shl 20, at, t)

    @Test fun losslessTierHasItsOwnCeilingInsideTheOverallCap() {
        val list = listOf(e("l1", 900, 1, Tier.LOSSLESS), e("b1", 500, 2, Tier.BULK), e("l2", 900, 3, Tier.LOSSLESS),
            e("l3", 600, 4, Tier.LOSSLESS))
        // lossless = 2400 MB > 2048 MB: evict the oldest lossless (l1) only; bulk untouched; total 2000 MB < 8 GB
        val p = VaultIndex.tieredEvictionPlan(list, 8L shl 30, keep = "l3")!!
        assertEquals(listOf("l1"), p.map { it.trackId })
    }

    @Test fun overallCapStillAppliesAcrossTiers() {
        val list = listOf(e("b1", 400, 1, Tier.BULK), e("l1", 300, 2, Tier.LOSSLESS), e("b2", 400, 3, Tier.BULK))
        val p = VaultIndex.tieredEvictionPlan(list, 1L shl 30, keep = "b2")!!
        assertEquals(listOf("b1"), p.map { it.trackId })
    }

    @Test fun smallerOverallCapAlsoBoundsTheLosslessTier() {
        val list = listOf(e("l1", 400, 1, Tier.LOSSLESS), e("l2", 400, 2, Tier.LOSSLESS))
        assertEquals(listOf("l1"), VaultIndex.tieredEvictionPlan(list, 512L shl 20, keep = "l2")!!.map { it.trackId })
        assertNull("a lossless file bigger than its tier can't be kept",
            VaultIndex.tieredEvictionPlan(listOf(e("big", 2100, 1, Tier.LOSSLESS)), 8L shl 30, keep = "big"))
        assertTrue(VaultIndex.tieredEvictionPlan(listOf(e("big", 2100, 1, Tier.BULK)), 8L shl 30, keep = "big")!!.isEmpty())
    }

    @Test fun tierSurvivesTheIndexAndOldIndexesReadAsBulk() {
        val list = listOf(e("l", 1, 1, Tier.LOSSLESS), e("b", 1, 2, Tier.BULK))
        assertEquals(list, VaultIndex.decode(VaultIndex.encode(list)))
        val old = """{"v":1,"entries":[{"t":"x","f":"y","ti":"","ar":"","p":1,"c":2,"at":3}]}""".toByteArray()
        assertEquals(Tier.BULK, VaultIndex.decode(old).single().tier)
        val u = VaultIndex.use(list, 1L shl 30)
        assertEquals(1L shl 20, u.losslessBytes); assertEquals(1L shl 30, u.losslessCap)
    }
}
