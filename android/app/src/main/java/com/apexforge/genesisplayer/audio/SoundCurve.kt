package com.apexforge.genesisplayer.audio

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Aurum 2.3 sound presets as smooth frequency curves, sampled at whatever band centres the phone's Equalizer
 * reports (5 bands on most phones, different centres per vendor). Pure math, JVM-tested.
 *
 * Honest limits: this is the device's own EQ effect; Aurum does not claim hi-res, lossless or "studio" sound.
 * A curve that boosts can push loud passages past full scale, so every boosting preset is paired with a
 * peak limiter (DynamicsProcessing, Android 9+) and the boost itself is capped.
 */
object SoundCurve {
    enum class Preset(val label: String, val blurb: String) {
        FLAT("Flat", "As recorded"),
        BASS("Bass+", "Fuller low end"),
        VOCAL("Vocal", "Clearer voices"),
        NIGHT("Night", "Gentle and even, for low volume"),
    }

    /** Never ask the EQ for more than this much boost on any band (millibels). */
    const val MAX_BOOST_MB = 600

    /** Gain in dB at frequency [hz] for [p]: a few broad, smooth shelves/bells (no sharp peaks). */
    fun gainDb(p: Preset, hz: Double): Double = when (p) {
        Preset.FLAT -> 0.0
        Preset.BASS -> 6.0 * lowShelf(hz, 150.0) - 0.5 * bell(hz, 3000.0, 1.5)
        Preset.VOCAL -> -2.0 * lowShelf(hz, 150.0) + 3.0 * bell(hz, 2500.0, 1.2) + 1.0 * bell(hz, 1000.0, 1.0)
        Preset.NIGHT -> -3.0 * lowShelf(hz, 100.0) + 1.5 * bell(hz, 2000.0, 1.3) - 2.0 * highShelf(hz, 8000.0)
    }

    // Smooth 0..1 shelves on a log-frequency axis (1 octave transition), and a log-Gaussian bell.
    private fun oct(hz: Double, ref: Double) = ln(hz / ref) / ln(2.0)
    private fun lowShelf(hz: Double, corner: Double) = 1.0 / (1.0 + Math.pow(2.0, 2.0 * oct(hz, corner)))
    private fun highShelf(hz: Double, corner: Double) = 1.0 - lowShelf(hz, corner)
    private fun bell(hz: Double, centre: Double, widthOct: Double): Double {
        val x = oct(hz, centre) / widthOct
        return Math.exp(-x * x)
    }

    /**
     * Band levels (millibels) for the device: sampled at each centre ([centresMilliHz], as Equalizer reports them),
     * rounded to whole decibels (Android EQs hold band levels in 100 mB steps: measured on the API-34 emulator,
     * which turned 107/-5/-48 mB into 100/0/0), capped at [MAX_BOOST_MB], and clamped to [minMb]..[maxMb].
     */
    fun bandLevels(p: Preset, centresMilliHz: IntArray, minMb: Int, maxMb: Int): IntArray =
        IntArray(centresMilliHz.size) { i ->
            val db = gainDb(p, max(centresMilliHz[i], 1) / 1000.0)
            val mb = db.roundToInt() * 100
            min(min(mb, MAX_BOOST_MB), maxMb).coerceAtLeast(minMb)
        }

    /** Largest boost across bands, in millibels (0 if the curve only cuts). */
    fun peakBoostMb(levels: IntArray) = max(0, levels.maxOrNull() ?: 0)

    /**
     * Limiter plan for a preset: input gain pulled down by the curve's peak boost (so a full-scale signal in the
     * boosted band stays near full scale), and a brick-wall ceiling just under 0 dBFS. Flat with no boost
     * needs no limiter at all.
     */
    data class Limiter(val enabled: Boolean, val inputGainDb: Float, val thresholdDb: Float)

    fun limiterFor(levels: IntArray, bassBoostOn: Boolean): Limiter {
        val boostDb = peakBoostMb(levels) / 100f + if (bassBoostOn) 3f else 0f
        if (boostDb <= 0f) return Limiter(false, 0f, -1f)
        return Limiter(true, -min(boostDb, 9f) / 2f, -1f) // half the boost as headroom, the limiter catches the rest
    }

    /** Max |difference| in millibels between two curves, for tests and logs. */
    fun distanceMb(a: IntArray, b: IntArray) = a.indices.maxOfOrNull { abs(a[it] - b[it]) } ?: 0
}
