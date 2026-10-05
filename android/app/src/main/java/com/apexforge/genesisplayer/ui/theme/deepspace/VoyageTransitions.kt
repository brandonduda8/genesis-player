package com.apexforge.genesisplayer.ui.theme.deepspace

/**
 * W2 — album voyage transitions.
 *
 * Timings are contractual (design §2): DRIFT 2s (track change inside an
 * album), WARP 3s (the Launch — pressing play on an album), SLIPSTREAM 0.8s
 * (manual skip). W3 drives the starfield warp/streak intensity from
 * [Transition.progress].
 */
enum class TransitionType { NONE, DRIFT, WARP, SLIPSTREAM }

data class Transition(val type: TransitionType, val startNanos: Long) {

    /** 0..1 progress of the transition at [nowNanos]. NONE reports complete. */
    fun progress(nowNanos: Long): Float {
        val dur = durationSec(type)
        if (dur <= 0f) return 1f
        val elapsedSec = (nowNanos - startNanos) / 1_000_000_000.0
        return (elapsedSec / dur).toFloat().coerceIn(0f, 1f)
    }

    companion object {
        /** Contractual durations in seconds. */
        fun durationSec(type: TransitionType): Float = when (type) {
            TransitionType.NONE -> 0f
            TransitionType.DRIFT -> 2f
            TransitionType.WARP -> 3f
            TransitionType.SLIPSTREAM -> 0.8f
        }
    }
}

/**
 * W2 — owns the current voyage transition.
 *
 * - [onAlbumStart]: THE LAUNCH — pressing play on an album → WARP.
 * - [onTrackChange]: same album → DRIFT; different album → treated as
 *   [onAlbumStart] (a new voyage).
 * - [onManualSkip]: → SLIPSTREAM.
 */
class VoyageController {
    private var current: Transition? = null

    /** THE LAUNCH — pressing play on an album. */
    fun onAlbumStart() {
        current = Transition(TransitionType.WARP, System.nanoTime())
    }

    /** Same album → DRIFT; different album → treated as [onAlbumStart]. */
    fun onTrackChange(sameAlbum: Boolean) {
        if (sameAlbum) current = Transition(TransitionType.DRIFT, System.nanoTime())
        else onAlbumStart()
    }

    /** Manual skip → SLIPSTREAM. */
    fun onManualSkip() {
        current = Transition(TransitionType.SLIPSTREAM, System.nanoTime())
    }

    /** The live transition, or null when NONE/expired. */
    val transition: Transition?
        get() {
            val t = current ?: return null
            if (t.type == TransitionType.NONE || t.progress(System.nanoTime()) >= 1f) {
                current = null
                return null
            }
            return t
        }
}
