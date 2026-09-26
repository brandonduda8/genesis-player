package com.apexforge.genesisplayer.data

/**
 * BRKN Vibes wave 2: pure crossfade engage math. No Android framework calls —
 * unit-testable (see BrknWavesTest). The service's watcher tick delegates to
 * [shouldEngage] so there is exactly one implementation of the rule.
 */
object CrossfadeMath {
    /**
     * @param positionMs current playback position
     * @param durationMs track duration (<=0 = unknown)
     * @param xfadeS configured crossfade seconds (0 = off)
     * @param hasNext whether a next item exists in the queue
     * @param isPlaying whether the player is currently rendering
     */
    fun shouldEngage(
        positionMs: Long,
        durationMs: Long,
        xfadeS: Float,
        hasNext: Boolean,
        isPlaying: Boolean
    ): Boolean {
        if (!isPlaying || !hasNext || xfadeS <= 0f || durationMs <= 0L) return false
        return durationMs - positionMs <= (xfadeS * 1000).toLong()
    }
}
