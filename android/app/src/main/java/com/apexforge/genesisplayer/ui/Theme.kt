package com.apexforge.genesisplayer.ui

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Ember / Genesis quantum-fire palette
val AshBlack = Color(0xFF0A0A0C)
val SurfaceDark = Color(0xFF141417)
val CardDark = Color(0xFF1B1B1F)
val EmberOrange = Color(0xFFFF6A00)
val PhoenixGold = Color(0xFFF5B942)
val TextDim = Color(0xFF9A9AA0)

private val GenesisScheme = darkColorScheme(
    primary = EmberOrange,
    onPrimary = Color.Black,
    secondary = PhoenixGold,
    onSecondary = Color.Black,
    background = AshBlack,
    onBackground = Color.White,
    surface = SurfaceDark,
    onSurface = Color.White,
    surfaceVariant = CardDark,
    onSurfaceVariant = TextDim
)

@Composable
fun GenesisTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = GenesisScheme,
        typography = Typography(),
        content = content
    )
}
