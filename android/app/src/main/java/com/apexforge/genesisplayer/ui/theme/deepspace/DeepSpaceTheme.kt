package com.apexforge.genesisplayer.ui.theme.deepspace

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * W2 — Deep Space palette. The observatory-dark foundation of AURUM.
 *
 * Firewall note: theme-lane only. This file touches zero playback, catalog,
 * queue, MediaSession, or service code.
 */
object DeepSpaceColors {
    val SpaceBlack: Color = Color(0xFF060714)
    val DeepIndigo: Color = Color(0xFF1B1340)
    /**
     * Brand gold — hard-coded. It must NEVER follow a remote-config palette
     * swap again (that was the firewall breach: remote config repainted the
     * app at launch).
     */
    val Gold: Color = Color(0xFFF5B942)
    val Starlight: Color = Color(0xFFFFFFFF)
    val StarlightDim: Color = Color(0xB3FFFFFF)

    /** Full-bleed background: SpaceBlack bleeding down into DeepIndigo. */
    fun backgroundBrush(): Brush = Brush.verticalGradient(listOf(SpaceBlack, DeepIndigo))
}
