package com.apexforge.genesisplayer

import android.content.Context
import android.util.Log

/**
 * Golden Player Phase 1: the web deck's "AMPLIFIER — 4-stage EQ" mapped onto
 * the REAL android.media.audiofx chain in [AudioFxController] — no Web-Audio
 * imitation, no decorative sliders.
 *
 * - Bass ON/OFF + Boost -> BassBoost enabled + strength. BassBoost has no dB
 *   scale (strength is 0..1000 per-mille), so Boost reads as a percentage —
 *   never a fake dB number.
 * - Low / Mid / High (dB) -> every Equalizer band whose centre frequency falls
 *   in that stage (< 400 Hz, 400 Hz–4 kHz, >= 4 kHz), clamped to the device's
 *   real band-level range.
 * - Presets are the web player's four (Flat / Bass Heavy / Bright / Vocal).
 *
 * With no live session yet (nothing played), values are written to the same
 * prefs AudioFxController reads on attach, so they apply the moment audio
 * starts. The pure mapping functions are pinned by the API-34 tests.
 */
object FourStageEq {
    private const val TAG = "GenesisAudioFx"

    data class State(
        val bassOn: Boolean,
        val bass: Int,  // 0..12 steps -> BassBoost strength
        val low: Int,   // -12..12 dB
        val mid: Int,   // -12..12 dB
        val high: Int,  // -12..12 dB
        val preset: String
    )

    val PRESETS: LinkedHashMap<String, State> = linkedMapOf(
        "Flat" to State(false, 0, 0, 0, 0, "Flat"),
        "Bass Heavy" to State(true, 10, 5, -1, 1, "Bass Heavy"),
        "Bright" to State(false, 0, -1, 1, 7, "Bright"),
        "Vocal" to State(false, 0, -2, 6, 3, "Vocal")
    )

    /** Standard 5-band centres (Hz) used when no live Equalizer exists yet. */
    val DEFAULT_CENTERS_HZ = intArrayOf(60, 230, 910, 3600, 14000)

    /** 0 = Low, 1 = Mid, 2 = High. */
    fun stageOf(centerHz: Int): Int = when {
        centerHz < 400 -> 0
        centerHz < 4000 -> 1
        else -> 2
    }

    fun bandLevelMb(db: Int, minMb: Int, maxMb: Int): Int = (db * 100).coerceIn(minMb, maxMb)

    fun bassStrength(steps: Int): Int = (steps.coerceIn(0, 12) * 1000) / 12

    fun boostPercent(steps: Int): Int = (bassStrength(steps) + 5) / 10

    fun stageDb(s: State, stage: Int): Int = when (stage) {
        0 -> s.low
        1 -> s.mid
        else -> s.high
    }

    private fun prefs(c: Context) =
        c.getSharedPreferences("genesis_audiofx", Context.MODE_PRIVATE)

    /**
     * Current 4-stage state. Before the panel is ever touched, it is derived
     * from whatever the existing DSP prefs hold, so opening the panel never
     * silently changes the sound.
     */
    fun load(c: Context): State {
        val p = prefs(c)
        if (p.contains("g4_preset")) {
            return State(
                p.getBoolean("bass_on", true),
                p.getInt("g4_bass", 0),
                p.getInt("g4_low", 0),
                p.getInt("g4_mid", 0),
                p.getInt("g4_high", 0),
                p.getString("g4_preset", "Custom") ?: "Custom"
            )
        }
        val stages = IntArray(3)
        val counts = IntArray(3)
        DEFAULT_CENTERS_HZ.forEachIndexed { b, hz ->
            val st = stageOf(hz)
            stages[st] += p.getInt("eq_band_$b", 0) / 100
            counts[st]++
        }
        return State(
            bassOn = p.getBoolean("bass_on", true),
            bass = ((p.getInt("bass_strength", 800) * 12) + 500) / 1000,
            low = stages[0] / counts[0].coerceAtLeast(1),
            mid = stages[1] / counts[1].coerceAtLeast(1),
            high = stages[2] / counts[2].coerceAtLeast(1),
            preset = "Custom"
        )
    }

    /** Apply to the live effects when attached, else persist for attach. */
    fun apply(c: Context, fx: AudioFxController?, s: State) {
        prefs(c).edit()
            .putString("g4_preset", s.preset)
            .putInt("g4_bass", s.bass)
            .putInt("g4_low", s.low)
            .putInt("g4_mid", s.mid)
            .putInt("g4_high", s.high)
            .apply()
        val strength = bassStrength(s.bass)
        if (fx != null) {
            fx.setBassEnabled(s.bassOn)
            fx.setBassStrength(strength)
            fx.clearPreset()
            fx.setEqEnabled(true)
            val eq = fx.equalizer
            val range = try { eq?.bandLevelRange } catch (_: Exception) { null }
            val minMb = range?.getOrNull(0)?.toInt() ?: -1500
            val maxMb = range?.getOrNull(1)?.toInt() ?: 1500
            val bands = try { eq?.numberOfBands?.toInt() } catch (_: Exception) { null }
                ?: DEFAULT_CENTERS_HZ.size
            for (b in 0 until bands) {
                val hz = try { eq!!.getCenterFreq(b.toShort()) / 1000 } catch (_: Exception) {
                    DEFAULT_CENTERS_HZ.getOrElse(b) { 1000 }
                }
                fx.setBandLevel(b, bandLevelMb(stageDb(s, stageOf(hz)), minMb, maxMb))
            }
        } else {
            val e = prefs(c).edit()
                .putBoolean("bass_on", s.bassOn)
                .putInt("bass_strength", strength)
                .putBoolean("eq_on", true)
                .putInt("eq_preset", -1)
            DEFAULT_CENTERS_HZ.forEachIndexed { b, hz ->
                e.putInt("eq_band_$b", bandLevelMb(stageDb(s, stageOf(hz)), -1500, 1500))
            }
            e.apply()
        }
        Log.i(
            TAG,
            "GoldenEq: preset ${s.preset} bass=${if (s.bassOn) "on" else "off"}/${s.bass} " +
                "low=${s.low} mid=${s.mid} high=${s.high} (${if (fx != null) "live" else "saved for attach"})"
        )
    }
}
