package com.apexforge.genesisplayer.ui

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color

/** Live theme palette. Defaults = the bundled Ember / Genesis quantum-fire look. */
data class ThemePalette(
    val background: Color,
    val surface: Color,
    val card: Color,
    val accent: Color,
    val gold: Color,
    val text: Color,
    val textDim: Color,
    val ember: Color
)

data class Section(val id: String, val label: String, val visible: Boolean)

data class AppLabels(
    val appName: String,
    val libraryTitle: String,
    val librarySubtitle: String,
    val refreshLabel: String
)

/**
 * Remote look-and-feel state. Updated live by RemoteConfig when a newer
 * config.json is fetched; every read below happens inside @Composable
 * functions, so a palette swap recomposes the whole UI instantly.
 */
object RemoteTheme {
    fun defaultPalette() = ThemePalette(
        background = Color(0xFF0A0A0C),
        surface = Color(0xFF141417),
        card = Color(0xFF1B1B1F),
        accent = Color(0xFFFF6A00),
        gold = Color(0xFFF5B942),
        text = Color(0xFFFFFFFF),
        textDim = Color(0xFF9A9AA0),
        ember = Color(0xFFFF6A00)
    )

    fun defaultSections() = listOf(
        Section("nowplaying", "Now Playing", true),
        Section("library", "Library", true),
        Section("foryou", "For You", true),
        Section("eq", "EQ", true)
    )

    fun defaultLabels() = AppLabels(
        appName = "BRKN Vibes",
        libraryTitle = "Library",
        librarySubtitle = "{count} tracks — streamed, never downloaded",
        refreshLabel = "⟳ Refresh music"
    )

    val palette = mutableStateOf(defaultPalette())
    val sections = mutableStateOf(defaultSections())
    val labels = mutableStateOf(defaultLabels())
}

// Legacy names kept so every screen keeps compiling; all read the live palette.
val AshBlack: Color get() = RemoteTheme.palette.value.background
val SurfaceDark: Color get() = RemoteTheme.palette.value.surface
val CardDark: Color get() = RemoteTheme.palette.value.card
val EmberOrange: Color get() = RemoteTheme.palette.value.accent
val PhoenixGold: Color get() = RemoteTheme.palette.value.gold
val TextDim: Color get() = RemoteTheme.palette.value.textDim

@Composable
fun GenesisTheme(content: @Composable () -> Unit) {
    val p = RemoteTheme.palette.value
    val scheme = darkColorScheme(
        primary = p.accent,
        onPrimary = Color.Black,
        secondary = p.gold,
        onSecondary = Color.Black,
        background = p.background,
        onBackground = p.text,
        surface = p.surface,
        onSurface = p.text,
        surfaceVariant = p.card,
        onSurfaceVariant = p.textDim
    )
    MaterialTheme(
        colorScheme = scheme,
        typography = Typography(),
        content = content
    )
}
