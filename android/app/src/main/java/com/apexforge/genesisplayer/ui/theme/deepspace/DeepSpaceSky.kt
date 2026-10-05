package com.apexforge.genesisplayer.ui.theme.deepspace

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.isActive

/**
 * Deep Space sky orchestrator (W3 — assembly).
 *
 * One Canvas. All frame state lives in remember{} holders — the 60fps
 * withFrameNanos loop NEVER recomposes; it only mutates plain Float/Long
 * fields that the Canvas draw block reads. Zero allocations inside the
 * frame loop.
 *
 * Layer order per draw: art-reactive nebula (W2 drawNebula) → starfield
 * (W1 StarfieldRenderer) → beat-reactive shockwave rings (W2 BeatReactor).
 *
 * Static discipline (Council gate 3 / QuietContexts): paused, STILL-or-
 * quieter, or reduced-motion → the fast loop is cancelled and the Canvas
 * draws ONE still frame (nebula at rest, stars dim, no rings). The frame
 * clock does not advance while static.
 */
private const val SPECTRUM_BINS = 24

/** Mutable frame state, kept out of composition. */
private class SkyFrameState {
    var tSec = 0f
    var driftX = 0f
    var driftY = 0f
    var energy = 0f
    val bins = FloatArray(SPECTRUM_BINS)
}

private fun warpCurve(p: Float): Float {
    val x = p.coerceIn(0f, 1f)
    return x * x * (3f - 2f * x) // smoothstep 0..1
}

@Composable
fun DeepSpaceSky(
    nebula: ImageBitmap?,
    transition: Transition?,
    quiet: QuietProfile,
    isPlaying: Boolean,
    reduceMotion: Boolean,
    modifier: Modifier = Modifier
) {
    val stars = remember { StarfieldRenderer() }
    val beat = remember { BeatReactor() }
    val frame = remember { SkyFrameState() }
    // Per-frame redraw signal. The withFrameNanos loop mutates the plain
    // holder fields above (zero alloc); writing this state is the ONLY thing
    // that invalidates the Canvas each frame. It is local to this composable,
    // so the 60fps recomposition never touches the controls (they are
    // siblings, not children, of the sky).
    var redrawTick by remember { mutableLongStateOf(0L) }

    val static = !isPlaying || quiet >= QuietProfile.STILL || reduceMotion

    LaunchedEffect(isPlaying, quiet, reduceMotion) {
        if (static) return@LaunchedEffect
        var prevNanos = 0L
        while (isActive) {
            withFrameNanos { nowNanos ->
                if (prevNanos == 0L) prevNanos = nowNanos
                val dt = ((nowNanos - prevNanos) / 1_000_000_000f).coerceIn(0f, 0.1f)
                prevNanos = nowNanos
                if (FftAudioTap.available) {
                    frame.energy = FftAudioTap.energy
                    val onset = FftAudioTap.consumeOnset()
                    FftAudioTap.spectrum(frame.bins)
                    beat.onFrame(frame.energy, onset, dt)
                } else {
                    // Ambient-only drift: the sky keeps moving gently;
                    // audio-reactive layers rest at zero. Never crashes.
                    frame.energy = 0f
                    beat.onFrame(0f, 0f, dt)
                }
                frame.tSec += dt
                frame.driftX += dt
                frame.driftY += dt
                redrawTick = nowNanos
            }
        }
    }

    // The beat-reactive visualizer: content-desc doubles as the gate's proof
    // that a real visualizer view is present in the hierarchy.
    Canvas(
        modifier = modifier.semantics { contentDescription = "Ember visualizer" }
    ) {
        // Observed: establishes the per-frame redraw read.
        @Suppress("UNUSED_VARIABLE")
        val tick = redrawTick
        val nowNanos = System.nanoTime()
        val w = size.width
        val h = size.height

        // Warp factor from the voyage transition (read per draw; transitions
        // change rarely, so this costs one recomposition per voyage event).
        var warp = 0f
        val tr = transition
        if (tr != null && !static) {
            val p = tr.progress(nowNanos)
            warp = when (tr.type) {
                TransitionType.WARP -> warpCurve(p)
                TransitionType.SLIPSTREAM -> (1f - p.coerceIn(0f, 1f)) * 0.7f
                TransitionType.DRIFT -> p.coerceIn(0f, 1f) * 0.25f
                TransitionType.NONE -> 0f
            }
        }

        val t = if (static) 0f else frame.tSec
        val energy = if (static) 0f else frame.energy

        // Layer 1: art-reactive nebula, drawn at rest when static.
        drawNebula(nebula, tSec = t, energy = energy, alpha = if (static) 0.75f else 1f)

        // Layer 2: parallax starfield.
        stars.draw(
            scope = this,
            w = w,
            h = h,
            tSec = t,
            energy = energy,
            warp = warp,
            driftX = if (static) 0f else frame.driftX,
            driftY = if (static) 0f else frame.driftY
        )

        // Layer 3: beat-reactive shockwave rings — suppressed entirely when static.
        if (!static) {
            with(beat) { drawRings(w * 0.5f, h * 0.5f) }
        }
    }
}
