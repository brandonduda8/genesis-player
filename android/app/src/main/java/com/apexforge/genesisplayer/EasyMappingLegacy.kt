package com.apexforge.genesisplayer

import com.apexforge.genesisplayer.dsp.EasyMapping
import com.apexforge.genesisplayer.dsp.EqParams

/** Bridge from the legacy 4-stage state to the rich engine. Android-compile only (FourStageEq imports android.*). */
fun EasyMapping.fromLegacyState(s: FourStageEq.State): EqParams =
    toParams(s.bassOn, s.bass, s.low, s.mid, s.high)

object EasyMappingLegacy {
    fun fromLegacyState(s: FourStageEq.State): EqParams = EasyMapping.fromLegacyState(s)
}
