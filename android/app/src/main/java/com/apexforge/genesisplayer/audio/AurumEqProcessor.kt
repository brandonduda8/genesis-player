package com.apexforge.genesisplayer.audio

import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.apexforge.genesisplayer.data.ParametricEq
import java.nio.ByteBuffer

/**
 * Aurum 2.4 golden, charge 3: headphone correction inside Aurum's engine (Media3 audio pipeline, after decoding).
 * 16-bit PCM and float PCM run through [HeadphoneEq]'s parametric chain; anything else passes untouched. When the
 * correction is off, audio is copied through bit-for-bit. Changes from the UI apply within one buffer.
 */
@OptIn(UnstableApi::class)
class AurumEqProcessor : BaseAudioProcessor() {
    private var chain: ParametricEq.Chain? = null
    private var builtGen = -1
    private var shorts = ShortArray(0)
    private var floats = FloatArray(0)

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT && inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            Log.i(TAG, "[HIFI] passthrough: encoding ${inputAudioFormat.encoding} not processed")
            return AudioProcessor.AudioFormat.NOT_SET
        }
        builtGen = -1
        return inputAudioFormat
    }

    private fun rebuild() {
        val c = HeadphoneEq.config
        builtGen = HeadphoneEq.generation
        val fmt = inputAudioFormat
        val plan = HeadphoneEq.plan(c, fmt.sampleRate.toDouble())
        chain = plan?.let { ParametricEq.Chain(it.filters, fmt.sampleRate.toDouble(), fmt.channelCount, it.preampDb) }
        if (plan != null) Log.i(TAG, "[HIFI] chain active profile=${c.profile?.id} strength=${c.strength} " +
            "filters=${plan.filters.size} preamp=${"%.2f".format(plan.preampDb)}dB rate=${fmt.sampleRate} ch=${fmt.channelCount}")
        else Log.i(TAG, "[HIFI] chain off (bit-transparent)")
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val bytes = inputBuffer.remaining()
        if (bytes == 0) return
        inputBuffer.order(java.nio.ByteOrder.nativeOrder()) // PCM in the pipeline is native-endian
        if (builtGen != HeadphoneEq.generation) rebuild()
        val out = replaceOutputBuffer(bytes)
        val ch = chain
        if (ch == null) { out.put(inputBuffer); out.flip(); return }
        correctedFrames += bytes / inputAudioFormat.bytesPerFrame
        if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT) {
            val n = bytes / 2
            if (shorts.size < n) { shorts = ShortArray(n); floats = FloatArray(n) }
            val count = n - n % inputAudioFormat.channelCount
            for (i in 0 until n) shorts[i] = inputBuffer.getShort()
            HeadphoneEq.process16(shorts, count, ch, floats)
            for (i in 0 until n) out.putShort(shorts[i])
        } else {
            val n = bytes / 4
            if (floats.size < n) floats = FloatArray(n)
            for (i in 0 until n) floats[i] = inputBuffer.getFloat()
            ch.process(floats, (n - n % inputAudioFormat.channelCount) / inputAudioFormat.channelCount)
            for (i in 0 until n) out.putFloat(floats[i])
        }
        out.flip()
    }

    override fun onFlush() { chain?.reset() }
    override fun onReset() { chain = null; builtGen = -1 }

    companion object {
        private const val TAG = "GenesisPlayer"
        /** Frames that went through an active correction chain (evidence for tests and the log; process-wide). */
        @Volatile @JvmStatic var correctedFrames: Long = 0
    }
}
