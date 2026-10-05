package com.apexforge.genesisplayer.ui.theme.deepspace

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * W2 — Deep Space ambient starfield.
 *
 * 3 depth layers with per-layer parallax drift, slow twinkle, and an
 * energy-driven brightness ceiling. All particle state is pooled in
 * FloatArrays allocated once in init; [draw] allocates nothing per frame
 * (positions are normalized 0..1 so resizes never re-seed).
 *
 * Honesty rules (Council gate 3):
 * - Twinkle frequency is capped at 0.50 Hz — never anywhere near the 3 Hz
 *   flash floor. This is verified in [freq] at construction.
 * - [energy] only raises the brightness ceiling smoothly (clamped 0..1).
 *   Nothing strobes, nothing pulses on a hard edge.
 * - warp > 0 stretches stars into streaks along the drift direction
 *   (WARP/SLIPSTREAM transitions); with ~zero drift it degrades to points.
 *
 * @param seedStars star count, default 220 (budget: 200–400 + dust).
 */
class StarfieldRenderer(seedStars: Int = 220) {

    private val count: Int = seedStars.coerceIn(1, 1200)

    // Pooled state — allocated once. draw() must not allocate.
    private val nx = FloatArray(count)     // normalized x 0..1
    private val ny = FloatArray(count)     // normalized y 0..1
    private val layer = IntArray(count)    // 0 far · 1 mid · 2 near
    private val phase = FloatArray(count)  // twinkle phase, radians
    private val freq = FloatArray(count)   // twinkle Hz — ALWAYS ≤ 0.50
    private val radius = FloatArray(count) // base radius px @ ~900px min-dimension
    private val bright = FloatArray(count) // base brightness 0..1

    init {
        val rnd = Random(seedStars.toLong())
        for (i in 0 until count) {
            nx[i] = rnd.nextFloat()
            ny[i] = rnd.nextFloat()
            val l = (rnd.nextFloat() * 3f).toInt().coerceIn(0, 2)
            layer[i] = l
            phase[i] = rnd.nextFloat() * TWO_PI
            freq[i] = 0.08f + rnd.nextFloat() * 0.42f // 0.08–0.50 Hz, never ≥3Hz
            radius[i] = when (l) {
                0 -> 0.8f + rnd.nextFloat() * 0.6f
                1 -> 1.2f + rnd.nextFloat() * 0.8f
                else -> 1.8f + rnd.nextFloat() * 1.2f
            }
            bright[i] = 0.35f + rnd.nextFloat() * 0.65f
        }
    }

    /**
     * @param w,h canvas size in px
     * @param tSec animation clock, seconds
     * @param energy 0..1, expected pre-smoothed (FftAudioTap) — raises the
     *   brightness ceiling smoothly; clamped, never a strobe
     * @param warp 0..1 streak intensity for voyage transitions
     * @param driftX,driftY parallax drift in px, scaled per layer
     */
    fun draw(
        scope: DrawScope,
        w: Float,
        h: Float,
        tSec: Float,
        energy: Float,
        warp: Float,
        driftX: Float,
        driftY: Float
    ) {
        if (w <= 0f || h <= 0f) return
        val e = energy.coerceIn(0f, 1f)
        val ceiling = 0.45f + 0.55f * e
        val warpAmt = warp.coerceIn(0f, 1f)
        val rScale = (minOf(w, h) / 900f).coerceIn(0.6f, 1.6f)

        // Streak direction for warp>0: along the drift vector. Falls back
        // to points when drift is ~zero (no fabricated direction).
        val driftLen = sqrt(driftX * driftX + driftY * driftY)
        val streaking = warpAmt > 0.001f && driftLen > 1e-3f
        val sdx = if (streaking) driftX / driftLen else 0f
        val sdy = if (streaking) driftY / driftLen else 0f

        for (i in 0 until count) {
            val lf = LAYER_PARALLAX[layer[i]]
            val x = posMod(nx[i] * w + driftX * lf, w)
            val y = posMod(ny[i] * h + driftY * lf, h)

            // Slow twinkle: 0.08–0.50 Hz by construction. No ≥3Hz flashing, ever.
            val tw = 0.6f + 0.4f * sin(TWO_PI * freq[i] * tSec + phase[i])
            val a = (bright[i] * tw * ceiling).coerceIn(0f, 1f)
            if (a < 0.01f) continue
            // Color is an inline value class: copy() is primitive ops, no heap alloc.
            val color = DeepSpaceColors.Starlight.copy(alpha = a)
            val r = radius[i] * rScale

            if (streaking) {
                val len = warpAmt * (36f + 140f * lf) * rScale
                scope.drawLine(
                    color = color,
                    start = Offset(x, y),
                    end = Offset(x + sdx * len, y + sdy * len),
                    strokeWidth = r * 1.3f,
                    cap = StrokeCap.Round
                )
            } else {
                scope.drawCircle(color = color, radius = r, center = Offset(x, y))
            }
        }
    }

    companion object {
        private val TWO_PI = (2f * PI).toFloat()
        private val LAYER_PARALLAX = floatArrayOf(0.2f, 0.5f, 1f)

        private fun posMod(v: Float, m: Float): Float {
            val r = v % m
            return if (r < 0f) r + m else r
        }
    }
}
