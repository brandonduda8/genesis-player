package com.apexforge.genesisplayer.audio

import android.util.Log
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import com.apexforge.genesisplayer.DspPreset
import com.apexforge.genesisplayer.DspPresets
import com.apexforge.genesisplayer.EqBand
import com.apexforge.genesisplayer.ParametricEq
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.PI
import kotlin.math.sin

/**
 * AURUM rebuild — Phase 1 audio engine.
 *
 * Wires the pure-Kotlin parametric DSP core ([ParametricEq], WO-AURUM-008)
 * into ExoPlayer's audio sink as a Media3 [androidx.media3.common.audio.AudioProcessor].
 *
 * Contract (per ARCHITECTURE_AURUM_REBUILD.md §1.2–§1.4):
 * - Accepts ONLY ENCODING_PCM_FLOAT. Anything else → returns
 *   [AudioFormat.NOT_SET] (self-bypass; never crash the sink). The sink is
 *   configured to *request* float output (setEnableFloatOutput(true) in
 *   PlayerService), so the normal path is always float. If a device's HAL
 *   rejects float, the engine reports SYSTEM_FX_FALLBACK and PlayerService
 *   keeps the legacy AudioFxController path — surfaced in the Engine Room,
 *   never silent.
 * - One [ParametricEq] per channel (stereo = 2 instances sharing the same
 *   parameter state — filter memory must NEVER be shared across channels).
 * - All parameter changes arrive as immutable [DspCommand]s through a
 *   lock-free queue, drained at the top of each [queueInput] call on the
 *   audio thread. Zero locks, zero main-thread audio work.
 * - Coefficient smoothing lives in BiquadFilter (20 ms tau); the adapter
 *   does NOT reimplement it. flush() resets filter state (seeks).
 * - Sample-rate changes rebuild the engine (coefficients are
 *   rate-dependent), preserving the live preset/bands.
 *
 * Deliberate deviation from the architecture doc: §1.2 sketches
 * `isActive() = !bypassAll || protectionActive` for zero-CPU bypass.
 * DefaultAudioSink builds its processor chain at configure time and does not
 * reliably re-poll isActive() per buffer, so a mid-stream bypass toggle
 * could leave the chain stale. Instead, isActive() is format-gated only and
 * bypass is honored inside queueInput via the core's copy-through path
 * (one memcpy per block — trivially cheap, always correct).
 *
 * Threading: queueInput/drain run on ExoPlayer's audio thread. offerCommand
 * and the [protection] snapshot may be touched from any thread.
 */
enum class DspEngineMode {
    /** Format not yet negotiated. */
    UNKNOWN,

    /** Float PCM path active — the parametric engine owns the sound. */
    PARAMETRIC_FLOAT,

    /** Device rejected float PCM — legacy system-FX fallback owns the sound. */
    SYSTEM_FX_FALLBACK,
}

/** Immutable parameter commands, applied on the audio thread in FIFO order. */
sealed interface DspCommand {
    /** Load a preset (snaps coefficients, per the core's applyPreset). */
    data class ApplyPreset(val preset: DspPreset) : DspCommand

    /** Retarget one band (smooth, no snap). */
    data class SetBand(val index: Int, val band: EqBand) : DspCommand

    /** Set the preamp (clamped by the core). */
    data class SetPreamp(val preampDb: Double) : DspCommand

    /** True bypass of the whole chain. */
    data class SetBypass(val bypass: Boolean) : DspCommand

    /**
     * Route-profile switch: applies the preset under a 200 ms level glide
     * (dips −6 dB at the midpoint) so a route change never pops.
     */
    data class LoadRoutePreset(val preset: DspPreset) : DspCommand
}

/**
 * Volatile per-block snapshot for the UI's protection indicator.
 * Poll at ~4 Hz — never read per-frame from the UI thread.
 */
data class ProtectionSnapshot(
    val protectionActive: Boolean,
    val limiterEngaged: Boolean,
    val effectivePreampDb: Double,
)

/**
 * v1 bridge so the UI layer can reach the live processor without a
 * MediaSession custom-command round-trip (Phase 2 work). Set by
 * PlayerService.onCreate, cleared in onDestroy.
 */
object DspEngine {
    @Volatile
    var processor: ParametricDspAudioProcessor? = null
}

class ParametricDspAudioProcessor(
    initialPreset: DspPreset = DspPresets.REFERENCE_FLAT,
) : BaseAudioProcessor() {

    companion object {
        private const val TAG = "AurumDsp"
        private const val MAX_CHANNELS = 8
        private const val ROUTE_GLIDE_SEC = 0.2
    }

    /** Resolved once the sink negotiates a format. Starts UNKNOWN. */
    @Volatile
    var engineMode: DspEngineMode = DspEngineMode.UNKNOWN
        private set

    /**
     * Invoked on the audio thread when [engineMode] first resolves.
     * PlayerService hops to the main thread and parks/unparks the legacy
     * system-FX path accordingly.
     */
    var onEngineModeChanged: ((DspEngineMode) -> Unit)? = null

    @Volatile
    var protection: ProtectionSnapshot =
        ProtectionSnapshot(false, false, initialPreset.preampDb)
        private set

    private val commands = ConcurrentLinkedQueue<DspCommand>()

    private var sampleRate: Int = 0
    private var eqs: List<ParametricEq> = emptyList()
    private var lastPreset: DspPreset = initialPreset

    // Scratch, grown once per block size — never allocated per sample.
    private var scratch: Array<FloatArray> = emptyArray()
    private var glideScratch = FloatArray(0)

    private var glideTotalSamples: Long = 0
    private var glideDoneSamples: Long = 0

    @Volatile
    private var effectivePreampDbCache: Double = initialPreset.preampDb

    /** Lock-free offer from any thread (UI → parameter application). */
    fun offerCommand(cmd: DspCommand) {
        commands.offer(cmd)
    }

    // ------------------------------------------------------------------
    // Media3 AudioProcessor contract
    // ------------------------------------------------------------------

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            Log.w(
                TAG,
                "input encoding=${inputAudioFormat.encoding} is not float PCM — self-bypassing",
            )
            setEngineMode(DspEngineMode.SYSTEM_FX_FALLBACK)
            return AudioFormat.NOT_SET
        }
        val rate = inputAudioFormat.sampleRate
        val channels = inputAudioFormat.channelCount
        if (rate <= 0 || channels <= 0 || channels > MAX_CHANNELS) {
            Log.w(TAG, "unsupported format rate=$rate ch=$channels — self-bypassing")
            setEngineMode(DspEngineMode.SYSTEM_FX_FALLBACK)
            return AudioFormat.NOT_SET
        }
        if (rate != sampleRate || eqs.size != channels) {
            rebuildEqs(rate, channels)
        }
        setEngineMode(DspEngineMode.PARAMETRIC_FLOAT)
        Log.i(TAG, "float path active: ${rate}Hz x${channels}ch")
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val format = inputAudioFormat
        val bytesRemaining = inputBuffer.remaining()
        if (bytesRemaining == 0 || !isActive()) {
            inputBuffer.position(inputBuffer.limit())
            return
        }
        val channels = format.channelCount
        val bytesPerFrame = channels * 4
        val frames = bytesRemaining / bytesPerFrame
        if (frames == 0 || eqs.size != channels) {
            inputBuffer.position(inputBuffer.limit())
            return
        }

        drainCommands()
        ensureScratch(channels, frames)

        // Deinterleave → per-channel DSP (in place, allocation-free) → glide.
        val fb = inputBuffer.order(ByteOrder.nativeOrder()).asFloatBuffer()
        for (ch in 0 until channels) {
            val dst = scratch[ch]
            for (i in 0 until frames) {
                dst[i] = fb.get(i * channels + ch)
            }
            eqs[ch].processBlockInto(dst, dst)
        }
        applyGlide(frames)

        // Interleave into the output buffer (Media3 pattern: fill, flip).
        val out = replaceOutputBuffer(frames * bytesPerFrame)
        var bytePos = 0
        for (i in 0 until frames) {
            val g = glideScratch[i]
            for (ch in 0 until channels) {
                out.putFloat(bytePos, scratch[ch][i] * g)
                bytePos += 4
            }
        }
        out.position(bytePos)
        out.flip()
        inputBuffer.position(inputBuffer.limit())

        val e0 = eqs[0]
        protection = ProtectionSnapshot(
            e0.protectionActive,
            e0.limiterEngagedLastBlock,
            effectivePreampDbCache,
        )
    }

    /**
     * Format-gated only (see class doc for why bypass is NOT folded in
     * here): active whenever float PCM was negotiated.
     */
    override fun isActive(): Boolean = outputAudioFormat != AudioFormat.NOT_SET

    override fun onFlush() {
        // Seek: drop filter memory so the old position can't smear into the
        // new one. Queued parameter commands are KEPT — they belong to the
        // new position too.
        eqs.forEach { it.resetState() }
        glideTotalSamples = 0
        glideDoneSamples = 0
    }

    override fun onReset() {
        eqs.forEach { it.resetState() }
        glideTotalSamples = 0
        glideDoneSamples = 0
    }

    // ------------------------------------------------------------------
    // Internals (audio thread only, except where marked)
    // ------------------------------------------------------------------

    private fun setEngineMode(mode: DspEngineMode) {
        if (engineMode == mode) return
        engineMode = mode
        try {
            onEngineModeChanged?.invoke(mode)
        } catch (e: Exception) {
            Log.w(TAG, "engine-mode listener failed", e)
        }
    }

    /**
     * Rebuild the per-channel engines for a new sample rate / channel count.
     * Coefficients are rate-dependent, so the engines are rebuilt — never
     * retuned in place — while the live preset/bands/preamp/bypass carry over.
     */
    private fun rebuildEqs(rate: Int, channels: Int) {
        val old = eqs.firstOrNull()
        eqs = List(channels) {
            ParametricEq(rate).also { eq ->
                if (old != null) {
                    old.bands.forEachIndexed { i, b -> eq.bands[i] = b.copy() }
                    eq.preampDb = old.preampDb
                    eq.bypassAll = old.bypassAll
                } else {
                    eq.applyPreset(lastPreset)
                }
                eq.retarget(snap = true)
                eq.resetState()
            }
        }
        sampleRate = rate
        scratch = Array(channels) { FloatArray(0) }
        glideTotalSamples = 0
        glideDoneSamples = 0
        refreshPreampCache()
        Log.i(TAG, "engine rebuilt: ${rate}Hz x${channels}ch, preset='${lastPreset.name}'")
    }

    private fun ensureScratch(channels: Int, frames: Int) {
        if (scratch.size != channels) {
            scratch = Array(channels) { FloatArray(frames) }
        } else {
            for (ch in 0 until channels) {
                if (scratch[ch].size < frames) scratch[ch] = FloatArray(frames)
            }
        }
        if (glideScratch.size < frames) glideScratch = FloatArray(frames)
    }

    private fun drainCommands() {
        var cmd = commands.poll() ?: return
        var sawParams = false
        while (true) {
            // Copy to a val: the branches capture it in forEach lambdas and
            // cmd is reassigned at the loop bottom, which kills smart casts.
            val c = cmd
            when (c) {
                is DspCommand.ApplyPreset -> {
                    eqs.forEach { it.applyPreset(c.preset) }
                    lastPreset = c.preset
                    sawParams = true
                }
                is DspCommand.LoadRoutePreset -> {
                    eqs.forEach { it.applyPreset(c.preset) }
                    lastPreset = c.preset
                    sawParams = true
                    glideTotalSamples =
                        (sampleRate * ROUTE_GLIDE_SEC).toLong().coerceAtLeast(1)
                    glideDoneSamples = 0
                }
                is DspCommand.SetBand -> {
                    val i = c.index
                    if (i in 0 until ParametricEq.BAND_COUNT) {
                        eqs.forEach {
                            it.bands[i] = c.band.copy()
                            it.retarget(snap = false)
                        }
                        sawParams = true
                    } else {
                        Log.w(TAG, "SetBand: index $i out of range, ignored")
                    }
                }
                is DspCommand.SetPreamp -> {
                    eqs.forEach { it.preampDb = c.preampDb }
                    sawParams = true
                }
                is DspCommand.SetBypass -> {
                    eqs.forEach { it.bypassAll = c.bypass }
                }
            }
            cmd = commands.poll() ?: break
        }
        if (sawParams) refreshPreampCache()
    }

    private fun refreshPreampCache() {
        effectivePreampDbCache =
            eqs.firstOrNull()?.effectivePreampDb() ?: lastPreset.preampDb
    }

    /**
     * 200 ms route-switch glide: 1.0 → 0.5 (−6 dB) at the midpoint → 1.0.
     * The dip masks the preset discontinuity; the curve starts and ends at
     * unity so steady-state level is untouched.
     */
    private fun applyGlide(frames: Int) {
        val total = glideTotalSamples
        if (total <= 0) {
            glideScratch.fill(1f, 0, frames)
            return
        }
        var done = glideDoneSamples
        for (i in 0 until frames) {
            val t = (done + i).toDouble() / total.toDouble()
            glideScratch[i] =
                if (t >= 1.0) 1f else (1.0 - 0.5 * sin(PI * t)).toFloat()
        }
        done += frames
        if (done >= total) {
            glideTotalSamples = 0
            glideDoneSamples = 0
        } else {
            glideDoneSamples = done
        }
    }
}
