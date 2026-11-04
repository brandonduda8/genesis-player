package com.apexforge.genesisplayer

import android.content.Context
import android.media.audiofx.BassBoost
import android.media.audiofx.DynamicsProcessing
import android.os.Build
import com.apexforge.genesisplayer.audio.SoundCurve
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
    /** Aurum 2.3 clip guard: a peak limiter (Android 9+), on only while a boosting Aurum preset or loudness is in use. */
    var limiter: DynamicsProcessing? = null
        private set
    private val sessionId = sessionId

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
        aurumPreset()?.let { applyAurumPreset(it, persist = false) }
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
        if (aurumPreset() != null) clearAurumPreset()
        prefs.edit().putInt("eq_band_$band", levelMb).apply()
        try { equalizer?.setBandLevel(band.toShort(), levelMb.toShort()) } catch (_: Exception) {}
    }

    fun usePreset(preset: Int) {
        if (aurumPreset() != null) clearAurumPreset()
        prefs.edit().putInt("eq_preset", preset).apply()
        try { equalizer?.usePreset(preset.toShort()) } catch (_: Exception) {}
    }

    fun clearPreset() {
        prefs.edit().putInt("eq_preset", -1).apply()
    }

    fun setLoudEnabled(on: Boolean) {
        prefs.edit().putBoolean("loud_on", on).apply()
        try { loudness?.enabled = on } catch (_: Exception) {}
        updateLimiter(aurumPreset()?.let { aurumLevels(it) } ?: IntArray(0))
    }

    fun setLoudGain(gainMb: Int) {
        prefs.edit().putInt("loud_gain_mb", gainMb).apply()
        try { loudness?.setTargetGain(gainMb) } catch (_: Exception) {}
    }

    // ---------- Aurum 2.3 presets (curves sampled at this device's own band centres) ----------

    fun aurumPreset(): SoundCurve.Preset? =
        prefs.getString("aurum_preset", null)?.let { n -> SoundCurve.Preset.values().firstOrNull { it.name == n } }

    /** The band levels [p] gives on THIS device, or null without an Equalizer. */
    fun aurumLevels(p: SoundCurve.Preset): IntArray? {
        val eq = equalizer ?: return null
        return try {
            val n = eq.numberOfBands.toInt()
            val range = eq.bandLevelRange
            SoundCurve.bandLevels(p, IntArray(n) { eq.getCenterFreq(it.toShort()) }, range[0].toInt(), range[1].toInt())
        } catch (e: Exception) { Log.w(tag, "band query failed: ${e.message}"); null }
    }

    fun applyAurumPreset(p: SoundCurve.Preset, persist: Boolean = true) {
        val levels = aurumLevels(p) ?: return
        if (persist) prefs.edit().putString("aurum_preset", p.name).putInt("eq_preset", -1).apply()
        levels.forEachIndexed { b, mb ->
            prefs.edit().putInt("eq_band_$b", mb).apply()
            try { equalizer?.setBandLevel(b.toShort(), mb.toShort()) } catch (_: Exception) {}
        }
        try { equalizer?.enabled = true } catch (_: Exception) {}
        prefs.edit().putBoolean("eq_on", true).apply()
        updateLimiter(levels)
        Log.i(tag, "Aurum preset ${p.name}: bands(mB)=${levels.joinToString()} limiter=${limiter?.enabled}")
    }

    /** Leaving the Aurum presets (device preset or manual bands) also drops the clip guard unless loudness is on. */
    fun clearAurumPreset() {
        prefs.edit().remove("aurum_preset").apply()
        updateLimiter(IntArray(0))
    }

    private fun updateLimiter(levels: IntArray) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        val plan = SoundCurve.limiterFor(levels, bassBoostOn = isBassEnabled() && aurumPreset() != null)
        val need = plan.enabled || isLoudEnabled()
        try {
            if (!need) { limiter?.enabled = false; return }
            val dp = limiter ?: DynamicsProcessing(0, sessionId,
                DynamicsProcessing.Config.Builder(DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION, 2,
                    false, 0, false, 0, false, 0, true).build()).also { limiter = it }
            dp.setInputGainAllChannelsTo(if (plan.enabled) plan.inputGainDb else 0f)
            dp.setLimiterAllChannelsTo(DynamicsProcessing.Limiter(true, true, 0, 1f, 60f, 10f, plan.thresholdDb, 0f))
            dp.enabled = true
        } catch (e: Exception) {
            Log.w(tag, "clip guard unavailable: ${e.message}")
        }
    }

    fun isBassEnabled() = prefs.getBoolean("bass_on", true)
    fun isEqEnabled() = prefs.getBoolean("eq_on", true)
    fun isLoudEnabled() = prefs.getBoolean("loud_on", false)
    fun bandLevel(band: Int) = prefs.getInt("eq_band_$band", 0)

    fun release() {
        try { bassBoost?.release() } catch (_: Exception) {}
        try { equalizer?.release() } catch (_: Exception) {}
        try { loudness?.release() } catch (_: Exception) {}
        try { limiter?.release() } catch (_: Exception) {}
        bassBoost = null; equalizer = null; loudness = null; limiter = null
    }
}
