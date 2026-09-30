package com.apexforge.genesisplayer.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import com.apexforge.genesisplayer.dsp.EqParams
import com.apexforge.genesisplayer.dsp.ParametricEq
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray

/**
 * WO-AURUM-008 Media3 AudioProcessor wrapping [ParametricEq].
 *
 * Accepts 16-bit PCM and float PCM, mono or stereo; any other format returns
 * [AudioFormat.NOT_SET] from onConfigure so ExoPlayer leaves the processor inactive
 * (audio then plays unprocessed; DspController still shows the real state).
 *
 * Audio-thread rules: queueInput takes no locks, logs nothing and allocates nothing
 * after the first buffers (the scratch array is sized in onConfigure; the output
 * ByteBuffer is owned by BaseAudioProcessor.replaceOutputBuffer, which only grows).
 *
 * Pass-through: when the params are bypassed AND flat, and the engine's smoothing ramp
 * has settled, queueInput does a single bulk buffer copy: no float conversion, no DSP.
 * (A zero-copy hand-back of the caller's input buffer is not allowed by the
 * AudioProcessor contract: the caller retains ownership of the input.)
 */
class EqAudioProcessor : BaseAudioProcessor() {

    @Volatile private var params: EqParams = EqParams()
    private val paramsGeneration = AtomicInteger(0)
    /** Set if the engine ever threw on the audio thread; processor then passes audio through. */
    @Volatile var faulted: Boolean = false; private set

    // Audio-thread state (touched only from configure/flush/queueInput, which ExoPlayer
    // serialises on its playback thread).
    private var eq: ParametricEq? = null
    private var appliedGeneration = -1
    private var encoding = C.ENCODING_INVALID
    private var channels = 0
    private var sampleRate = 0
    private var scratch = FloatArray(0)
    private var rampFramesLeft = 0
    private var inPassthrough = false

    // Published engine snapshot for the UI (written on the audio thread, read anywhere).
    @Volatile var limiterActive: Boolean = false; private set
    @Volatile var gainReductionDb: Double = 0.0; private set
    @Volatile var effectivePreampDb: Double = 0.0; private set

    // ---- CPU accounting: ring of buckets, each ~BUCKET_SECONDS of audio ----
    private val bucketNanos = AtomicLongArray(BUCKETS)
    private val bucketFrames = AtomicLongArray(BUCKETS)
    private var curBucket = 0            // audio thread only
    private val totalFrames = AtomicLong()
    @Volatile private var rateForStats = 48000

    /** Latest params; safe to call from any thread. The engine ramps to them (no level jump). */
    fun setParams(p: EqParams) {
        params = p
        paramsGeneration.incrementAndGet()
    }

    fun currentParams(): EqParams = params

    /** Total audio frames processed since construction (monotonic; used to detect idle). */
    fun framesProcessed(): Long = totalFrames.get()

    /**
     * Processing time divided by the real-time duration of the audio processed, in percent
     * of one core, over the rolling window (BUCKETS * BUCKET_SECONDS of audio). 0 when idle.
     */
    fun cpuLoadPercent(): Double {
        var ns = 0L; var fr = 0L
        for (i in 0 until BUCKETS) { ns += bucketNanos.get(i); fr += bucketFrames.get(i) }
        if (fr <= 0L) return 0.0
        val audioNs = fr.toDouble() * 1.0e9 / rateForStats.coerceAtLeast(1)
        return ns.toDouble() / audioNs * 100.0
    }

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        val enc = inputAudioFormat.encoding
        val ch = inputAudioFormat.channelCount
        if ((enc != C.ENCODING_PCM_16BIT && enc != C.ENCODING_PCM_FLOAT) || ch < 1 || ch > 2 ||
            inputAudioFormat.sampleRate <= 0) {
            eq = null
            return AudioFormat.NOT_SET
        }
        encoding = enc; channels = ch; sampleRate = inputAudioFormat.sampleRate
        rateForStats = sampleRate
        scratch = FloatArray(CHUNK_FRAMES * ch)
        val e = ParametricEq(sampleRate, ch)
        e.setParams(params)
        eq = e
        appliedGeneration = paramsGeneration.get()
        rampFramesLeft = 0
        faulted = false
        resetStats()
        return inputAudioFormat
    }

    override fun onFlush() {
        eq?.reset()
        rampFramesLeft = 0
        inPassthrough = false
    }

    override fun onReset() {
        eq = null
        scratch = FloatArray(0)
        encoding = C.ENCODING_INVALID
        channels = 0
        sampleRate = 0
    }

    private fun resetStats() {
        for (i in 0 until BUCKETS) { bucketNanos.set(i, 0L); bucketFrames.set(i, 0L) }
        curBucket = 0
    }

    private fun isFlatBypass(p: EqParams): Boolean {
        if (!p.bypass) return false
        // Bypass forces identity in the engine; "flat" additionally requires that nothing
        // would change once bypass is released, so pass-through never hides a stale profile.
        if (p.preampDb != 0f) return false
        for (b in p.bands) if (b.enabled && b.gainDb != 0f) return false
        return true
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val start = System.nanoTime()
        val bytesPerSample = if (encoding == C.ENCODING_PCM_FLOAT) 4 else 2
        val frameBytes = bytesPerSample * channels
        val remaining = inputBuffer.remaining()
        val frames = if (frameBytes > 0) remaining / frameBytes else 0
        if (frames <= 0) {
            inputBuffer.position(inputBuffer.limit())
            return
        }

        // Read the generation BEFORE the params: a stale generation with newer params just re-applies
        // next buffer, whereas the reverse order could pin old params under a new generation.
        val gen = paramsGeneration.get()
        val p = params
        val engine = eq
        if (engine != null && gen != appliedGeneration) {
            appliedGeneration = gen
            engine.setParams(p)
            // Engine smoothing ramp is ParametricEq.RAMP_MS; allow 3x before treating it as settled.
            rampFramesLeft = (ParametricEq.RAMP_MS * 0.003 * sampleRate).toInt() + 1
        }

        val bytes = frames * frameBytes
        val out = replaceOutputBuffer(bytes)

        if (engine == null || faulted || (isFlatBypass(p) && rampFramesLeft <= 0)) {
            // Pass-through: one bulk copy, no DSP and no conversion. NOTE: the engine has a fixed
            // ~1.5 ms limiter latency that pass-through skips, so entering/leaving pass-through
            // can produce a ~1.5 ms discontinuity (engine is reset when leaving).
            inPassthrough = true
            val lim = inputBuffer.limit()
            inputBuffer.limit(inputBuffer.position() + bytes)
            out.put(inputBuffer)
            inputBuffer.limit(lim)
            out.flip()
            inputBuffer.position(inputBuffer.position() + (remaining - bytes).coerceAtLeast(0))
            accountAndPublish(engine, start, frames, false)
            return
        }

        if (inPassthrough) { engine.reset(); inPassthrough = false }
        val origPos = inputBuffer.position()
        var inPos = origPos
        try {
            val buf = scratch
            var left = frames
            val isFloat = encoding == C.ENCODING_PCM_FLOAT
            while (left > 0) {
                val n = if (left < CHUNK_FRAMES) left else CHUNK_FRAMES
                val samples = n * channels
                if (isFloat) {
                    for (i in 0 until samples) { buf[i] = inputBuffer.getFloat(inPos); inPos += 4 }
                } else {
                    for (i in 0 until samples) { buf[i] = inputBuffer.getShort(inPos) * INV_32768; inPos += 2 }
                }
                engine.process(buf, 0, n)
                if (isFloat) {
                    for (i in 0 until samples) {
                        val v = buf[i]
                        out.putFloat(if (v > 1f) 1f else if (v < -1f) -1f else if (v != v) 0f else v)
                    }
                } else {
                    for (i in 0 until samples) {
                        val s = buf[i] * 32768f
                        val r = if (s >= 0f) (s + 0.5f).toInt() else (s - 0.5f).toInt()
                        out.putShort((if (r > 32767) 32767 else if (r < -32768) -32768 else r).toShort())
                    }
                }
                left -= n
            }

        } catch (t: Throwable) {
            // Never break the audio path: fall back to raw pass-through of this buffer.
            faulted = true
            out.clear()
            val lim = inputBuffer.limit()
            inputBuffer.limit(origPos + bytes)
            inputBuffer.position(origPos)
            out.put(inputBuffer)
            inputBuffer.limit(lim)
            inPos = origPos + bytes
        }
        out.flip()
        inputBuffer.position(inPos + (remaining - bytes).coerceAtLeast(0))
        if (rampFramesLeft > 0) rampFramesLeft -= frames
        accountAndPublish(engine, start, frames, true)
    }

    private fun accountAndPublish(engine: ParametricEq?, startNs: Long, frames: Int, ranDsp: Boolean) {
        val spent = System.nanoTime() - startNs
        val i = curBucket
        bucketNanos.set(i, bucketNanos.get(i) + spent)
        val fr = bucketFrames.get(i) + frames
        bucketFrames.set(i, fr)
        totalFrames.addAndGet(frames.toLong())
        if (fr >= sampleRate.toLong() * BUCKET_SECONDS_NUM / BUCKET_SECONDS_DEN) {
            val next = (i + 1) % BUCKETS
            bucketNanos.set(next, 0L); bucketFrames.set(next, 0L)
            curBucket = next
        }
        if (engine != null) {
            limiterActive = ranDsp && engine.limiterActive
            gainReductionDb = if (ranDsp) engine.gainReductionDb.toDouble() else 0.0
            effectivePreampDb = engine.effectivePreampDb.toDouble()
        }
    }

    companion object {
        const val CHUNK_FRAMES = 2048
        const val BUCKETS = 10
        // Each bucket = 1/2 s of audio, so the rolling window is 5 s.
        private const val BUCKET_SECONDS_NUM = 1
        private const val BUCKET_SECONDS_DEN = 2
        private const val INV_32768 = 1f / 32768f
    }
}
