package com.apexforge.genesisplayer.ui.theme.deepspace

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke

/**
 * W2 — beat-reactive bloom rings.
 *
 * A hard kick = a bloom, never a flash: rings expand over ~1.2s with a
 * smooth quadratic alpha decay. Ring slots are pooled (max 4 live); when
 * all are live the oldest is recycled. draw() allocates nothing.
 *
 * Feed [onFrame] once per frame with the FFT tap's energy/onset values
 * (0..1 scale); call [DrawScope.drawRings] inside the Canvas.
 */
class BeatReactor {

    // Ring ages in seconds; -1f = slot free. Pooled — zero alloc per frame.
    private val ringAge = FloatArray(MAX_RINGS) { -1f }

    /**
     * @param energy 0..1 smoothed energy (currently unused by the rings;
     *   kept so W3 can drive nebula-breath coupling from one call site).
     * @param onset 0..1 onset strength; above [ONSET_THRESHOLD] spawns a ring.
     * @param dtSec frame delta, seconds (clamped to 0.25 to survive hitches).
     */
    fun onFrame(energy: Float, onset: Float, dtSec: Float) {
        val dt = dtSec.coerceIn(0f, 0.25f)
        for (i in 0 until MAX_RINGS) {
            if (ringAge[i] >= 0f) {
                ringAge[i] += dt
                if (ringAge[i] > RING_LIFE_SEC) ringAge[i] = -1f
            }
        }
        if (onset > ONSET_THRESHOLD) {
            var free = -1
            var oldestAge = -1f
            var oldestIdx = 0
            for (i in 0 until MAX_RINGS) {
                if (ringAge[i] < 0f) {
                    free = i
                    break
                }
                if (ringAge[i] > oldestAge) {
                    oldestAge = ringAge[i]
                    oldestIdx = i
                }
            }
            ringAge[if (free >= 0) free else oldestIdx] = 0f
        }
    }

    /**
     * Expanding gold rings centered at (cx, cy). Radius reaches ~95% of the
     * canvas half-min-dimension; alpha decays quadratically — a bloom,
     * never a flash.
     */
    fun DrawScope.drawRings(cx: Float, cy: Float) {
        val maxR = size.minDimension * 0.5f * 0.95f
        if (maxR <= 0f) return
        for (i in 0 until MAX_RINGS) {
            val age = ringAge[i]
            if (age < 0f) continue
            val p = (age / RING_LIFE_SEC).coerceIn(0f, 1f)
            val radius = p * maxR
            val alpha = (1f - p) * (1f - p) * 0.75f
            if (alpha <= 0.005f || radius <= 0f) continue
            val width = 8f * (1f - p) + 1.5f
            drawCircle(
                color = DeepSpaceColors.Gold.copy(alpha = alpha),
                radius = radius,
                center = Offset(cx, cy),
                style = Stroke(width = width)
            )
        }
    }

    companion object {
        const val MAX_RINGS = 4
        const val RING_LIFE_SEC = 1.2f

        /** Onset above this spawns a ring. Tuned for the FFT tap's 0..1 onset scale. */
        const val ONSET_THRESHOLD = 0.6f
    }
}
