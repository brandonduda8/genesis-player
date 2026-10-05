package com.apexforge.genesisplayer.ui

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.graphics.Color

/** Fixed theme palette: Aurum Deep Space. Immutable by construction — the
 * remote look-and-feel mutation path no longer exists (2026-10-05). */
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

/** A Compose State that never changes. Keeps `.value` reads in existing
 * screens compiling while guaranteeing no mutation path exists. */
private class FixedState<T>(override val value: T) : State<T>

/**
 * Fixed look-and-feel state (Aurum Deep Space rebuild, 2026-10-05).
 * The remote-config application path is gone; these are plain vals.
 * `.value` reads are preserved so screens outside the theme lane keep
 * compiling (LibraryScreen, Golden), but there is no setter, no
 * MutableState, and no writer anywhere in the codebase.
 */
object RemoteTheme {
    val palette: State<ThemePalette> = FixedState(
        ThemePalette(
            background = Color(0xFF060714),
            surface = Color(0xFF1B1340),
            card = Color(0xFF241B4D),
            accent = Color(0xFFF5B942),
            gold = Color(0xFFF5B942),
            text = Color(0xFFFFFFFF),
            textDim = Color(0xB3FFFFFF),
            ember = Color(0xFFF5B942)
        )
    )

    val sections = listOf(
        Section("crate", "Crate", true),
        Section("playlists", "Playlists", true),
        Section("apollo", "Apollo", true),
        Section("nowplaying", "Now Playing", true),
        Section("eq", "EQ", true)
    )

    val labels: State<AppLabels> = FixedState(
        AppLabels(
            appName = "Aurum",
            libraryTitle = "Library",
            librarySubtitle = "{count} tracks — streamed, never downloaded",
            refreshLabel = "⟳ Refresh music"
        )
    )
}

// Legacy names kept so every screen keeps compiling; all read the FIXED
// Deep Space palette. Accent is Gold now — no EmberOrange in the UI.
val AshBlack: Color get() = RemoteTheme.palette.value.background
val SurfaceDark: Color get() = RemoteTheme.palette.value.surface
val CardDark: Color get() = RemoteTheme.palette.value.card
val EmberOrange: Color get() = RemoteTheme.palette.value.accent
val PhoenixGold: Color get() = RemoteTheme.palette.value.gold
val TextDim: Color get() = RemoteTheme.palette.value.textDim

/** Aurum root theme: fixed Deep Space dark color scheme. */
@Composable
fun AurumTheme(content: @Composable () -> Unit) {
    val scheme = darkColorScheme(
        primary = Color(0xFFF5B942),
        onPrimary = Color.Black,
        secondary = Color(0xFFF5B942),
        background = Color(0xFF060714),
        onBackground = Color(0xFFFFFFFF),
        surface = Color(0xFF1B1340),
        onSurface = Color(0xFFFFFFFF),
        surfaceVariant = Color(0xFF241B4D),
        onSurfaceVariant = Color(0xB3FFFFFF)
    )
    MaterialTheme(
        colorScheme = scheme,
        typography = Typography(),
        content = content
    )
}
