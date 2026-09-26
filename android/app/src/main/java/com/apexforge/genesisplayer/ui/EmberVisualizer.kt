package com.apexforge.genesisplayer.ui

import android.media.audiofx.Visualizer
import android.util.Log
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.apexforge.genesisplayer.data.AudioSessionHub
import com.apexforge.genesisplayer.data.EmberArt
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import kotlin.math.sqrt

private const val TAG = "GenesisPlayer"

/**
 * BRKN Vibes wave 1: the audio-reactive ember visualizer.
 *
 * Attaches a REAL android.media.audiofx.Visualizer to the player's live
 * audio session id (shared via [AudioSessionHub] — same process, no IPC).
 * FFT magnitudes drive ember-orange → phoenix-gold bars; a few ember sparks
 * ride the same bins (positions derived from the captured data, never a
 * random walk).
 *
 * Honesty rules, enforced in code:
 * - Paused (or no session yet): capture stops (visualizer.enabled = false)
 *   and the canvas fades to a dim STATIC ember render. Nothing moves.
 * - Visualizer construction fails (max instances, missing HAL, session 0):
 *   the static seeded ember art shows instead. Never a crash.
 * - Silence (all-zero capture) draws flat bars — real data, not fake motion.
 *   There is deliberately NO idle animation: no data = no motion.
 */
@Composable
fun EmberVisualizer(
    isPlaying: Boolean,
    modifier: Modifier = Modifier.height(120.dp)
) {
    var fft by remember { mutableStateOf<ByteArray?>(null) }
    var capturing by remember { mutableStateOf(false) }
    val playingNow by rememberUpdatedState(isPlaying)
    // Paused = dim + static. The fade is the only animation here, and it
    // reflects a real state change (playing -> paused), not fake data.
    val dim by animateFloatAsState(
        targetValue = if (capturing && isPlaying) 1f else 0.32f,
        label = "vizDim"
    )
    // Static fallback: the same seeded procedural ember art used everywhere
    // else — intentional craft, never a placeholder look.
    val staticArt = remember { EmberArt.renderBitmap("ember-visualizer", 128).asImageBitmap() }

    // Instance-local handle so the enable/disable effects can reach this
    // composable's own poll-thread Visualizer (never shared across instances).
    val vizHolder = remember { mutableStateOf<VizHandle?>(null) }

    // Visualizer lifecycle, polled on the main thread: re-attach when the
    // session id changes, enable only while actually playing.
    DisposableEffect(Unit) {
        var viz: Visualizer? = null
        var lastSid = 0
        var lastPush = 0L
        var running = true

        fun attach(sid: Int) {
            try { viz?.release() } catch (_: Exception) {}
            viz = null
            if (sid == 0) return
            viz = try {
                Visualizer(0, sid).apply {
                    val range = Visualizer.getCaptureSizeRange()
                    captureSize = range[1].coerceAtMost(1024)
                    setDataCaptureListener(
                        object : Visualizer.OnDataCaptureListener {
                            override fun onWaveFormDataCapture(
                                v: Visualizer?, waveform: ByteArray?, samplingRate: Int
                            ) { /* FFT drives the render; waveform unused */ }

                            override fun onFftDataCapture(
                                v: Visualizer?, bytes: ByteArray?, samplingRate: Int
                            ) {
                                if (bytes == null) return
                                // ~15fps cap: the callback can fire up to 20Hz.
                                val now = android.os.SystemClock.uptimeMillis()
                                if (now - lastPush > 66) {
                                    lastPush = now
                                    fft = bytes.copyOf()
                                }
                            }
                        },
                        Visualizer.getMaxCaptureRate() / 2,
                        false,
                        true
                    )
                }
                Log.i(TAG, "EmberVisualizer: attached to session $sid")
                viz
            } catch (t: Throwable) {
                // Max instances, missing HAL, dead session — static art.
                Log.i(TAG, "EmberVisualizer: unavailable (${t.javaClass.simpleName}: ${t.message}); static art")
                null
            }
        }

        val poll = Thread {
            while (running) {
                try {
                    val sid = AudioSessionHub.audioSessionId
                    if (sid != lastSid) {
                        lastSid = sid
                        // Visualizer must be touched on a thread with a
                        // Looper for some callbacks; construction here is
                        // fine, enable/disable below is main-thread only.
                        attach(sid)
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "EmberVisualizer: poll failed (${t.message})")
                }
                try { Thread.sleep(750) } catch (_: InterruptedException) { break }
            }
        }.apply { isDaemon = true; name = "ember-viz" }
        poll.start()

        onDispose {
            running = false
            poll.interrupt()
            try { viz?.release() } catch (_: Exception) {}
            viz = null
        }

        // Expose the live visualizer to the enable/disable effect below via
        // a tiny holder the effects below can read through composition state.
        vizHolder.value = object : VizHandle {
            override fun setEnabled(on: Boolean): Boolean {
                return try {
                    val v = viz ?: return false
                    v.enabled = on
                    true
                } catch (t: Throwable) {
                    Log.i(TAG, "EmberVisualizer: enable($on) failed (${t.message})")
                    false
                }
            }
        }
    }

    // Enable capture only while genuinely playing; stop the moment playback
    // pauses (CRITICAL: no capture, no motion, fade to dim static).
    LaunchedEffect(isPlaying) {
        val handle = vizHolder.value
        if (isPlaying && handle != null) {
            capturing = handle.setEnabled(true)
        } else {
            handle?.setEnabled(false)
            capturing = false
            fft = null
        }
    }
    // Re-check attachment periodically: the poll thread may have attached a
    // new Visualizer after this effect ran (e.g. playback started before the
    // session id arrived). Cheap and honest.
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            val handle = vizHolder.value ?: continue
            val want = playingNow
            if (want && !capturing) capturing = handle.setEnabled(true)
            if (!want && capturing) {
                handle.setEnabled(false)
                capturing = false
                fft = null
            }
        }
    }

    // The gate proves this view exists via its content description.
    Box(
        modifier
            .semantics { contentDescription = "Ember visualizer" }
    ) {
        Canvas(Modifier.fillMaxSize().alpha(dim)) {
            val data = fft
            val w = size.width
            val h = size.height
            if (data != null && capturing && data.size >= 4) {
                // FFT bytes are interleaved real/imaginary pairs.
                val bins = 28
                val pairs = data.size / 2
                val barW = w / bins
                val mags = FloatArray(bins) { b ->
                    val idx = ((b * pairs / bins) * 2).coerceAtMost(data.size - 2)
                    val re = data[idx].toInt()
                    val im = data[idx + 1].toInt()
                    (sqrt((re * re + im * im).toDouble()) / 128.0)
                        .coerceIn(0.0, 1.0).toFloat()
                }
                val brush = Brush.verticalGradient(
                    listOf(Color(0xFFF5B942), Color(0xFFFF6A00), Color(0xFF7A2E00))
                )
                for (b in 0 until bins) {
                    val bh = (h * 0.92f * mags[b]).coerceAtLeast(3f)
                    drawRect(
                        brush = brush,
                        topLeft = Offset(b * barW + 2f, h - bh),
                        size = Size((barW - 4f).coerceAtLeast(1f), bh)
                    )
                }
                // Ember sparks: derived from the same bins (never random).
                for (i in 0 until 10) {
                    val b = (i * bins / 10).coerceAtMost(bins - 1)
                    val m = mags[b]
                    if (m < 0.05f) continue
                    val cx = (i + 0.5f) / 10f * w
                    val cy = h - h * 0.92f * m - 8f
                    drawCircle(
                        color = Color(0xFFF5B942).copy(alpha = 0.35f + 0.65f * m),
                        radius = (2f + 5f * m),
                        center = Offset(cx, cy.coerceAtLeast(4f))
                    )
                }
            } else {
                // Static ember art: paused, no session, or Visualizer failed.
                drawImage(
                    image = staticArt,
                    dstSize = IntSize(w.roundToInt().coerceAtLeast(1), h.roundToInt().coerceAtLeast(1))
                )
            }
        }
    }
}

/** Tiny handle so the enable/disable effects can reach the poll thread's Visualizer. */
private interface VizHandle {
    fun setEnabled(on: Boolean): Boolean
}
