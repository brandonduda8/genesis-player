package com.apexforge.genesisplayer.dsp

/*
 * WO-AURUM-008 shared DSP types. Pure Kotlin, NO Android imports, so the whole
 * DSP core compiles and tests on a plain JVM. Units are explicit in every name.
 */

enum class BandType { LOW_SHELF, PEAK, HIGH_SHELF }

/** One biquad band. Gain in dB, frequency in Hz, Q dimensionless (shelf Q = shelf slope shape). */
data class BandParams(
    val type: BandType,
    val freqHz: Float,
    val gainDb: Float,
    val q: Float,
    val enabled: Boolean = true
)

/**
 * Whole-engine parameters. Fixed layout: [bands] has exactly [BAND_COUNT]
 * entries: index 0 = low shelf, 1..6 = peaking, 7 = high shelf.
 */
data class EqParams(
    val preampDb: Float = 0f,
    val bands: List<BandParams> = flatBands(),
    /** Whole-DSP bypass: output equals input bit-for-bit (after smoothing ramp). */
    val bypass: Boolean = false,
    val limiterEnabled: Boolean = true,
    /** When true the engine lowers preamp by the conservative worst-case boost so nothing clips. */
    val autoHeadroom: Boolean = true
) {
    init { require(bands.size == BAND_COUNT) { "EqParams needs exactly $BAND_COUNT bands" } }

    companion object {
        const val BAND_COUNT = 8
        const val PREAMP_MIN_DB = -12f
        const val PREAMP_MAX_DB = 6f
        const val GAIN_MIN_DB = -12f
        const val GAIN_MAX_DB = 12f
        const val Q_MIN = 0.3f
        const val Q_MAX = 10f
        const val FREQ_MIN_HZ = 20f
        const val FREQ_MAX_HZ = 20000f

        val DEFAULT_FREQS_HZ = floatArrayOf(80f, 160f, 320f, 640f, 1250f, 2500f, 5000f, 10000f)

        fun flatBands(): List<BandParams> = List(BAND_COUNT) { i ->
            val t = when (i) { 0 -> BandType.LOW_SHELF; BAND_COUNT - 1 -> BandType.HIGH_SHELF; else -> BandType.PEAK }
            BandParams(t, DEFAULT_FREQS_HZ[i], 0f, if (t == BandType.PEAK) 1.0f else 0.707f)
        }
    }
}

/** Output route class. Profiles are persisted per class. */
enum class OutputClass { PHONE_SPEAKER, WIRED, BLUETOOTH, CAR_EXTERNAL, DEFAULT }

/** Minimal persistence port so route-profile logic is testable without Android. */
interface KeyValueStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
    fun remove(key: String)
}
