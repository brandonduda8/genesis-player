package com.apexforge.genesisplayer.ui.theme.deepspace

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * W2 — art-reactive nebula blit.
 *
 * The bitmap is pre-rendered once per artwork by [PaletteNebula] on a
 * background thread. This function performs NO blur — it only blits the
 * bitmap full-bleed with a slow drift (~40s oscillation) and a beat
 * "breathe" (scale 1.00 → 1.04 from smoothed energy). The bitmap is
 * overdrawn so edges never show during drift or breathe.
 *
 * @param bitmap pre-rendered nebula, or null to draw nothing (caller falls
 *   back to [DeepSpaceColors.backgroundBrush]).
 * @param tSec animation clock, seconds.
 * @param energy 0..1, expected pre-smoothed (FftAudioTap); clamped —
 *   smooth swell only, never a strobe.
 * @param alpha overall opacity, 0..1.
 */
fun DrawScope.drawNebula(bitmap: ImageBitmap?, tSec: Float, energy: Float, alpha: Float = 1f) {
    val bmp = bitmap ?: return
    val w = size.width
    val h = size.height
    if (w <= 0f || h <= 0f) return

    val e = energy.coerceIn(0f, 1f)
    val a = alpha.coerceIn(0f, 1f)
    if (a <= 0f) return

    // Slow drift: ~40s oscillation, ±24px — observatory-calm.
    val wOsc = (2f * PI / 40f).toFloat()
    val ox = sin(tSec * wOsc) * 24f
    val oy = cos(tSec * wOsc) * 24f

    // Breathe: 1.00 → 1.04 from smoothed energy. Gentle, never a flash.
    val breathe = 1f + 0.04f * e
    val dw = w * breathe + 64f
    val dh = h * breathe + 64f
    val dx = ((w - dw) / 2f + ox).roundToInt()
    val dy = ((h - dh) / 2f + oy).roundToInt()

    drawImage(
        image = bmp,
        dstOffset = IntOffset(dx, dy),
        dstSize = IntSize(dw.roundToInt().coerceAtLeast(1), dh.roundToInt().coerceAtLeast(1)),
        alpha = a
    )
}
