package com.apexforge.genesisplayer.ui.theme.deepspace

import android.content.Context
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * W2 — quiet contexts: when the sky must calm down or stop.
 *
 * Firewall note: theme-lane only. This file only READS system state
 * (battery-saver, animator scale) and caller-supplied playback hints; it
 * touches zero playback, catalog, queue, MediaSession, or service code.
 */
enum class QuietProfile { FULL, CALM, MINIMAL, STILL, BLACK }

private const val PREFS_NAME = "deepspace"
private const val KEY_REDUCE_MOTION = "reduce_motion"
private const val DRIVE_IDLE_NANOS = 30_000_000_000L // 30s

/**
 * Resolves the animation profile at composition time. Time is read, not
 * observed — callers should recompose on a tick or on state change.
 *
 * Priority order:
 * 1. lyricsOpen → STILL (the stars stop completely)
 * 2. sleepArmed → CALM
 * 3. 30s without touch while paused (drive-idle) → STILL
 * 4. PowerManager.isPowerSaveMode → MINIMAL
 * 5. else FULL
 *
 * Degrade (documented, per spec): PlayerService exposes NO public
 * sleep-remaining accessor — only setSleepTimer / setSleepEndOfQueue /
 * cancelSleepTimer (verified 2026-10-04 in PlayerService.kt). An armed
 * timer therefore maps flat to CALM; the proposal's 5-minute fade-to-black
 * cannot be wired until a remaining-time accessor exists. [QuietProfile.BLACK]
 * is reserved for that future fade — nothing produces it yet.
 */
@Composable
fun rememberQuietProfile(
    isPlaying: Boolean,
    lastTouchNanos: Long,
    sleepArmed: Boolean,
    lyricsOpen: Boolean = false
): QuietProfile {
    val context = LocalContext.current
    val powerManager = remember {
        context.getSystemService(Context.POWER_SERVICE) as PowerManager
    }
    val powerSave = powerManager.isPowerSaveMode
    val driveIdle = !isPlaying && (System.nanoTime() - lastTouchNanos) > DRIVE_IDLE_NANOS
    return when {
        lyricsOpen -> QuietProfile.STILL
        sleepArmed -> QuietProfile.CALM
        driveIdle -> QuietProfile.STILL
        powerSave -> QuietProfile.MINIMAL
        else -> QuietProfile.FULL
    }
}

/**
 * True when the system animator duration scale is 0 (system
 * remove-animations) OR the in-app reduce-motion toggle is set.
 */
@Composable
fun rememberReduceMotion(context: Context): Boolean {
    return remember(context) {
        val animatorOff = try {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f
            ) == 0f
        } catch (_: Exception) {
            false
        }
        val toggled = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_REDUCE_MOTION, false)
        animatorOff || toggled
    }
}

/** Persists the in-app reduce-motion toggle (read by [rememberReduceMotion]). */
fun setReduceMotionPref(context: Context, value: Boolean) {
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(KEY_REDUCE_MOTION, value)
        .apply()
}
