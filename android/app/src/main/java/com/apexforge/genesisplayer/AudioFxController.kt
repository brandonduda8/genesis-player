package com.apexforge.genesisplayer

import android.content.Context
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.util.Log

/**
 * Genuine Android system audio effects attached to ExoPlayer's audio session:
 * BassBoost, Equalizer (per-band), LoudnessEnhancer. These are real DSP
 * effects in the audio pipeline — not fake sliders. Settings persist.
 */
class AudioFxController(context: Context, sessionId: Int) {
    private val tag = "GenesisAudioFx"
    private val prefs = context.getSharedPreferences("genesis_audiofx", Context.MODE_PRIVATE)

    var bassBoost: BassBoost? = null
        private set
    var equalizer: Equalizer? = null
        private set
    var loudness: LoudnessEnhancer? = null
        private set

    val bassAvailable: Boolean get() = bassBoost != null
    val eqAvailable: Boolean get() = equalizer != null
    val loudAvailable: Boolean get() = loudness != null

    init {
        try {
            bassBoost = BassBoost(0, sessionId).also {
                it.enabled = prefs.getBoolean("bass_on", true)
                it.setStrength(prefs.getInt("bass_strength", 800).toShort())
            }
            Log.i(tag, "AudioFX attached: BassBoost on session $sessionId")
        } catch (e: Exception) {
            Log.w(tag, "BassBoost unavailable: ${e.message}")
        }
        try {
            equalizer = Equalizer(0, sessionId).also { eq ->
                eq.enabled = prefs.getBoolean("eq_on", true)
                val bands = eq.numberOfBands.toInt()
                for (b in 0 until bands) {
                    val lvl = prefs.getInt("eq_band_$b", 0)
                    try { eq.setBandLevel(b.toShort(), lvl.toShort()) } catch (_: Exception) {}
                }
                val preset = prefs.getInt("eq_preset", -1)
                if (preset >= 0) {
                    try { eq.usePreset(preset.toShort()) } catch (_: Exception) {}
                }
            }
            Log.i(tag, "AudioFX attached: Equalizer (${equalizer?.numberOfBands} bands) on session $sessionId")
        } catch (e: Exception) {
            Log.w(tag, "Equalizer unavailable: ${e.message}")
        }
        try {
            loudness = LoudnessEnhancer(sessionId).also {
                it.enabled = prefs.getBoolean("loud_on", false)
                it.setTargetGain(prefs.getInt("loud_gain_mb", 0))
            }
            Log.i(tag, "AudioFX attached: LoudnessEnhancer on session $sessionId")
        } catch (e: Exception) {
            Log.w(tag, "LoudnessEnhancer unavailable: ${e.message}")
        }
    }

    fun setBassEnabled(on: Boolean) {
        prefs.edit().putBoolean("bass_on", on).apply()
        try { bassBoost?.enabled = on } catch (_: Exception) {}
    }

    fun setBassStrength(strength: Int) {
        prefs.edit().putInt("bass_strength", strength).apply()
        try { bassBoost?.setStrength(strength.toShort()) } catch (_: Exception) {}
    }

    fun setEqEnabled(on: Boolean) {
        prefs.edit().putBoolean("eq_on", on).apply()
        try { equalizer?.enabled = on } catch (_: Exception) {}
    }

    fun setBandLevel(band: Int, levelMb: Int) {
        prefs.edit().putInt("eq_band_$band", levelMb).apply()
        try { equalizer?.setBandLevel(band.toShort(), levelMb.toShort()) } catch (_: Exception) {}
    }

    fun usePreset(preset: Int) {
        prefs.edit().putInt("eq_preset", preset).apply()
        try { equalizer?.usePreset(preset.toShort()) } catch (_: Exception) {}
    }

    fun clearPreset() {
        prefs.edit().putInt("eq_preset", -1).apply()
    }

    fun setLoudEnabled(on: Boolean) {
        prefs.edit().putBoolean("loud_on", on).apply()
        try { loudness?.enabled = on } catch (_: Exception) {}
    }

    fun setLoudGain(gainMb: Int) {
        prefs.edit().putInt("loud_gain_mb", gainMb).apply()
        try { loudness?.setTargetGain(gainMb) } catch (_: Exception) {}
    }

    fun isBassEnabled() = prefs.getBoolean("bass_on", true)
    fun isEqEnabled() = prefs.getBoolean("eq_on", true)
    fun isLoudEnabled() = prefs.getBoolean("loud_on", false)
    fun bandLevel(band: Int) = prefs.getInt("eq_band_$band", 0)

    fun release() {
        try { bassBoost?.release() } catch (_: Exception) {}
        try { equalizer?.release() } catch (_: Exception) {}
        try { loudness?.release() } catch (_: Exception) {}
        bassBoost = null; equalizer = null; loudness = null
    }
}
