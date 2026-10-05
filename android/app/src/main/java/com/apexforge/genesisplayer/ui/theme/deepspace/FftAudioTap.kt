package com.apexforge.genesisplayer.ui.theme.deepspace

import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** FFT window length in samples (power of two). */
internal const val FFT_SIZE = 2048

/**
 * RMS of [samples], clamped 0..1.
 *
 * Pure JVM math — no Android deps, directly unit-testable.
 */
internal fun computeRmsEnergy(samples: FloatArray): Float {
    if (samples.isEmpty()) return 0f
    var sum = 0.0
    for (s in samples) sum += s.toDouble() * s
    return sqrt(sum / samples.size).toFloat().coerceIn(0f, 1f)
}

/**
 * In-place radix-2 decimation-in-time FFT, [re]/[im] of equal power-of-2
 * length ≥ 2. Throws [IllegalArgumentException] on a bad length — the
 * analysis thread only ever passes [FFT_SIZE], so this never fires live.
 *
 * Pure JVM math — no Android deps, directly unit-testable.
 */
internal fun radix2Fft(re: FloatArray, im: FloatArray) {
    val n = re.size
    require(n >= 2 && n and (n - 1) == 0) {
        "FFT length must be a power of 2 >= 2, got $n"
    }
    require(im.size == n) { "re/im size mismatch: ${re.size} != ${im.size}" }

    // Bit-reversal permutation.
    var j = 0
    for (i in 1 until n) {
        var bit = n shr 1
        while (j and bit != 0) {
            j = j xor bit
            bit = bit shr 1
        }
        j = j xor bit
        if (i < j) {
            val tr = re[i]; re[i] = re[j]; re[j] = tr
            val ti = im[i]; im[i] = im[j]; im[j] = ti
        }
    }

    // Cooley–Tukey butterflies (double precision inside, float out).
    var len = 2
    while (len <= n) {
        val angle = -2.0 * PI / len
        val stepR = cos(angle)
        val stepI = sin(angle)
        var i = 0
        while (i < n) {
            var wR = 1.0
            var wI = 0.0
            val half = len / 2
            for (k in 0 until half) {
                val a = i + k
                val b = i + k + half
                val ur = re[a].toDouble()
                val ui = im[a].toDouble()
                val vr = re[b] * wR - im[b] * wI
                val vi = re[b] * wI + im[b] * wR
                re[a] = (ur + vr).toFloat()
                im[a] = (ui + vi).toFloat()
                re[b] = (ur - vr).toFloat()
                im[b] = (ui - vi).toFloat()
                val nwr = wR * stepR - wI * stepI
                wI = wR * stepI + wI * stepR
                wR = nwr
            }
            i += len
        }
        len = len shl 1
    }
}

/**
 * GOLDEN PLAYER "Deep Space" — the ONE shared audio seam (AURUM_SCAFFOLD.md §3).
 *
 * A read-only PCM tee off the ExoPlayer audio chain. Installed as a
 * [BaseAudioProcessor] in the sink's processor chain (owned by the AUDIO MAX
 * chain, per the scaffold); the theme lane only *consumes* its outputs
 * (energy / onset / spectrum). It NEVER alters playback: output audio is
 * byte-identical to input, and the tap self-bypasses on anything that isn't
 * float PCM.
 *
 * Council firewall: theme code must not touch playback, catalog, queue,
 * MediaSession, or service code — this object is the single exception, and
 * only as a passive consumer of its public outputs. If float audio never
 * flows, every output degrades silently ([available] = false, energy 0,
 * no spectrum) — never a crash, never a playback change.
 *
 * Threading:
 * - [queueInput] runs on ExoPlayer's audio thread. It does a byte-level
 *   copy to the output buffer and tees mono-mixed floats into a lock-free
 *   ring (capacity 8192 floats). It never blocks, never allocates per call.
 * - A dedicated [HandlerThread] ("FftTap", background priority, ~20 Hz)
 *   grabs the latest 2048-sample window, runs [radix2Fft], and updates the
 *   smoothed energy / onset state. Started lazily on the first float frame,
 *   stopped with quitSafely on [onReset].
 * - Public getters are volatile / atomic; spectrum snapshots are copied
 *   under a small lock (never on the audio path).
 */
object FftAudioTap : BaseAudioProcessor() {

    companion object {
        /** Lock-free ring capacity in floats. Power of two — masking, not modulo. */
        private const val RING_SIZE = 8192
        private const val RING_MASK = RING_SIZE - 1
        private const val RING_MASK_L = RING_SIZE - 1L

        /** ~20 Hz analysis cadence. */
        private const val TICK_MS = 50L

        /** Attack: one tick (~50 ms) — rises follow the music immediately. */
        /** Release: 1 − e^(−50 ms / 400 ms) ≈ 0.1175 per tick. */
        private val RELEASE_COEFF = (1.0 - exp(-0.05 / 0.4)).toFloat()

        /** Spectrum log sweep: 20 Hz → 20 kHz. */
        private const val SPEC_F_MIN = 20f
        private const val SPEC_F_MAX = 20000f

        private const val DEFAULT_SAMPLE_RATE = 44100
    }

    // ------------------------------------------------------------------
    // Public API — exact signatures consumed by theme workers (S3) and the
    // AUDIO MAX EQ screen. Never change without updating every consumer.
    // ------------------------------------------------------------------

    /** True once float frames have flowed through the tap. */
    val available: Boolean
        get() = availableBacking

    /** 0..1, RMS with fast attack (~50 ms) / slow release (~400 ms). */
    val energy: Float
        get() = energyBacking

    /**
     * Onset strength 0..1 since the last poll, then clears. Built from
     * positive energy-delta against an adaptive threshold.
     */
    fun consumeOnset(): Float = onsetAcc.getAndSet(0f)

    /**
     * Fills up to [out.size] bins 0..1, log-spaced 20 Hz → 20 kHz from the
     * latest FFT. Returns bins written; 0 if unavailable.
     */
    fun spectrum(out: FloatArray): Int {
        if (!available || out.isEmpty()) return 0
        val mags: FloatArray
        val peak: Float
        val rate: Int
        synchronized(spectrumLock) {
            mags = spectrumMags.copyOf()
            peak = spectrumPeak
            rate = spectrumRate
        }
        if (rate <= 0 || peak <= 0f) return 0
        val n = out.size
        val bins = FFT_SIZE / 2
        val logRatio = SPEC_F_MAX / SPEC_F_MIN // 1000x sweep
        for (i in 0 until n) {
            // Log-spaced frequency for this bin: 20 Hz at i=0, 20 kHz at i=n-1.
            val f = if (n == 1) {
                SPEC_F_MIN
            } else {
                SPEC_F_MIN * logRatio.pow(i.toFloat() / (n - 1))
            }
            val k = (f * bins / rate).toInt().coerceIn(0, bins - 1)
            out[i] = (mags[k] / peak).coerceIn(0f, 1f)
        }
        return n
    }

    // ------------------------------------------------------------------
    // Audio-thread state
    // ------------------------------------------------------------------

    @Volatile
    private var availableBacking = false

    @Volatile
    private var energyBacking = 0f

    @Volatile
    private var sampleRateBacking = DEFAULT_SAMPLE_RATE

    /** Accumulated onset strength; [consumeOnset] clears atomically. */
    private val onsetAcc = AtomicReference(0f)

    /** Lock-free tee ring (mono-mixed floats), written only by the audio thread. */
    private val ring = FloatArray(RING_SIZE)

    /** Monotonic mono-sample write counter (audio thread). */
    private val totalSamples = AtomicLong(0L)

    /** Total samples the analysis thread has already consumed. */
    private var lastAnalyzed = 0L

    private val writeIndex = AtomicInteger(0)

    // Analysis-thread scratch (only touched on the FftTap thread).
    private val windowTime = FloatArray(FFT_SIZE)
    private val windowRe = FloatArray(FFT_SIZE)
    private val windowIm = FloatArray(FFT_SIZE)
    private val mags = FloatArray(FFT_SIZE / 2)

    // Spectrum snapshot for consumers (written on FftTap thread, read anywhere).
    private val spectrumLock = Any()
    private val spectrumMags = FloatArray(FFT_SIZE / 2)
    private var spectrumPeak = 0f
    private var spectrumRate = DEFAULT_SAMPLE_RATE

    // Adaptive onset state (FftTap thread only).
    private var prevRms = 0f
    private var onsetThreshold = 0f

    // Lazy analysis thread.
    private val threadLock = Any()
    private var fftThread: HandlerThread? = null
    private var fftHandler: Handler? = null

    private val tickRunnable = object : Runnable {
        override fun run() {
            try {
                processTick()
            } finally {
                // Keep the cadence alive unless the thread was torn down.
                synchronized(threadLock) {
                    fftHandler?.postDelayed(this, TICK_MS)
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Media3 AudioProcessor contract
    // ------------------------------------------------------------------

    /**
     * Float PCM only — the ONE format the whole chain negotiates. Anything
     * else returns [AudioFormat.NOT_SET], which makes [isActive] false and
     * BaseAudioProcessor passes the audio through untouched.
     */
    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            return AudioFormat.NOT_SET
        }
        if (inputAudioFormat.sampleRate > 0) {
            sampleRateBacking = inputAudioFormat.sampleRate
        }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val format = inputAudioFormat
        val bytesRemaining = inputBuffer.remaining()
        if (bytesRemaining == 0 || !isActive()) {
            inputBuffer.position(inputBuffer.limit())
            return
        }

        // Duplicate BEFORE consuming: the tee reads via absolute gets while
        // the output copy advances inputBuffer's position. Byte-level copy —
        // output is bit-identical to input; the tap never alters playback.
        val teeSrc = inputBuffer.duplicate().order(ByteOrder.nativeOrder())
        val out = replaceOutputBuffer(bytesRemaining)
        out.put(inputBuffer)
        out.flip()
        inputBuffer.position(inputBuffer.limit())

        val channels = format.channelCount
        val bytesPerFrame = channels * 4
        if (bytesPerFrame <= 0) return
        val frames = bytesRemaining / bytesPerFrame
        if (frames <= 0) return

        // Tee: mono-mix each frame into the lock-free ring. No blocking, no
        // allocation, no locks on the audio thread.
        val fb = teeSrc.asFloatBuffer()
        val floatOffset = teeSrc.position() / 4 // view index 0 = byte 0, not position()
        for (f in 0 until frames) {
            var sum = 0f
            for (ch in 0 until channels) {
                sum += fb.get(floatOffset + f * channels + ch)
            }
            val idx = writeIndex.getAndIncrement()
            ring[idx and RING_MASK] = sum / channels
        }
        totalSamples.addAndGet(frames.toLong())

        if (!availableBacking) {
            availableBacking = true
            ensureThread()
        }
    }

    /**
     * Format-gated only: active whenever float PCM was negotiated. The tap
     * is read-only — there is no bypass toggle to fold in here.
     */
    override fun isActive(): Boolean = outputAudioFormat != AudioFormat.NOT_SET

    override fun onFlush() {
        // Seek: drop stale tee data so the old position can't smear into
        // the new one. The analysis thread is kept alive.
        writeIndex.set(0)
        totalSamples.set(0)
        lastAnalyzed = 0
    }

    override fun onReset() {
        synchronized(threadLock) {
            fftHandler?.removeCallbacksAndMessages(null)
            fftThread?.quitSafely()
            fftThread = null
            fftHandler = null
        }
        writeIndex.set(0)
        totalSamples.set(0)
        lastAnalyzed = 0
        availableBacking = false
        energyBacking = 0f
        onsetAcc.set(0f)
        prevRms = 0f
        onsetThreshold = 0f
        synchronized(spectrumLock) {
            spectrumMags.fill(0f)
            spectrumPeak = 0f
        }
    }

    // ------------------------------------------------------------------
    // Analysis (FftTap thread only)
    // ------------------------------------------------------------------

    private fun ensureThread() {
        synchronized(threadLock) {
            if (fftThread != null) return
            val thread = HandlerThread("FftTap", Process.THREAD_PRIORITY_BACKGROUND)
            thread.start()
            val handler = Handler(thread.looper)
            fftThread = thread
            fftHandler = handler
            handler.post(tickRunnable)
        }
    }

    private fun processTick() {
        val total = totalSamples.get()
        if (total - lastAnalyzed < FFT_SIZE) return

        // Grab the latest window (handles ring wraparound).
        for (i in 0 until FFT_SIZE) {
            val src = total - FFT_SIZE + i
            windowTime[i] = ring[(src and RING_MASK_L).toInt()]
        }
        lastAnalyzed = total

        val rms = computeRmsEnergy(windowTime)

        // Smoothed energy: instant attack, slow release.
        val e = energyBacking
        energyBacking = if (rms >= e) rms else e + (rms - e) * RELEASE_COEFF

        // Onset: positive energy-delta vs an adaptive threshold that trails
        // recent positive deltas. Accumulates until consumeOnset() clears it.
        val delta = rms - prevRms
        prevRms = rms
        val posDelta = max(delta, 0f)
        onsetThreshold += (posDelta - onsetThreshold) * 0.05f
        if (delta > onsetThreshold * 1.5f + 0.03f) {
            val strength = (delta * 3f).coerceIn(0f, 1f)
            onsetAcc.accumulateAndGet(strength) { a, b -> max(a, b) }
        }

        // Spectrum: magnitude of the half-spectrum.
        windowTime.copyInto(windowRe)
        windowIm.fill(0f)
        radix2Fft(windowRe, windowIm)
        var peak = 1e-9f
        for (k in 0 until FFT_SIZE / 2) {
            val m = sqrt(windowRe[k] * windowRe[k] + windowIm[k] * windowIm[k])
            mags[k] = m
            if (m > peak) peak = m
        }
        synchronized(spectrumLock) {
            mags.copyInto(spectrumMags)
            spectrumPeak = peak
            spectrumRate = sampleRateBacking
        }
    }
}
