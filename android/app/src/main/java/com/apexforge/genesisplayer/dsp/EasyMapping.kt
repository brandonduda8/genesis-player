package com.apexforge.genesisplayer.dsp

/**
 * Maps the legacy 4-stage Easy UI (Bass on/off + steps, Low/Mid/High in dB) onto the 8-band engine.
 *
 * Mapping (all gains are real dB on real biquads):
 *  - Bass stage: band 0 low shelf at [BASS_SHELF_HZ], gain = bassSteps/12 * [BASS_MAX_DB] (+9 dB at 12 steps),
 *    only when bassOn. This is a real shelf gain in dB, NOT the old BassBoost per-mille strength.
 *  - Low (dB): bands 1 (160 Hz) and 2 (320 Hz) peaks, each gain = lowDb * [LOW_K], Q [WIDE_Q].
 *  - Mid (dB): bands 3 (640 Hz), 4 (1250 Hz), 5 (2500 Hz) peaks, each gain = midDb * [MID_K], Q [WIDE_Q].
 *  - High (dB): band 6 peak at 5 kHz (gain = highDb * [HIGH_PEAK_K]) and band 7 high shelf at 10 kHz
 *    (gain = highDb * [HIGH_SHELF_K]).
 * Gains are clamped to the legal EqParams range. All-zero with bassOn=false equals EqParams() exactly.
 */
object EasyMapping {
    const val BASS_SHELF_HZ = 60f
    const val BASS_MAX_DB = 9f
    const val LOW_K = 0.85f
    const val MID_K = 0.5f
    const val HIGH_PEAK_K = 0.8f
    const val HIGH_SHELF_K = 1.0f
    const val WIDE_Q = 0.5f
    private const val REF_FS = 48000.0
    private const val REF_LOW_HZ = 80.0
    private const val REF_MID_HZ = 1000.0
    private const val REF_HIGH_HZ = 8000.0
    private const val REFINE_ITERATIONS = 6
    private const val STAGE_LIMIT = 24f

    private fun clampDb(v: Float) = v.coerceIn(EqParams.GAIN_MIN_DB, EqParams.GAIN_MAX_DB)

    fun toParams(bassOn: Boolean, bassSteps: Int, lowDb: Int, midDb: Int, highDb: Int): EqParams {
        val flat = EqParams.flatBands()
        val steps = bassSteps.coerceIn(0, 12)
        val bassDb = if (bassOn) steps.toFloat() / 12f * BASS_MAX_DB else 0f
        val lo = lowDb.coerceIn(-12, 12).toFloat()
        val mi = midDb.coerceIn(-12, 12).toFloat()
        val hi = highDb.coerceIn(-12, 12).toFloat()
        fun pk(i: Int, f: Float, g: Float) = BandParams(BandType.PEAK, f, clampDb(g), WIDE_Q)
        fun build(l: Float, m: Float, h: Float) = listOf(
            BandParams(BandType.LOW_SHELF, BASS_SHELF_HZ, clampDb(bassDb), 0.707f),
            pk(1, 160f, l * LOW_K),
            pk(2, 320f, l * LOW_K),
            pk(3, 640f, m * MID_K),
            pk(4, 1250f, m * MID_K),
            pk(5, 2500f, m * MID_K),
            pk(6, 5000f, h * HIGH_PEAK_K),
            BandParams(BandType.HIGH_SHELF, 10000f, clampDb(h * HIGH_SHELF_K), 0.707f)
        )
        // Wide stage filters overlap, so the stages bleed into each other (measured: Bass Heavy's Mid -1 dB
        // read +0.85 dB at 1 kHz). Refine the three stage values so each stage reads true at its reference
        // frequency; the bass shelf is a separate stage and is left out of this measurement.
        var sl = lo; var sm = mi; var sh = hi
        if (lo != 0f || mi != 0f || hi != 0f) {
            for (iter in 0 until REFINE_ITERATIONS) {
                val cur = build(sl, sm, sh).drop(1)
                fun at(hz: Double): Double = cur.sumOf {
                    val c = BiquadMath.coefficients(it.type, REF_FS, it.freqHz.toDouble(), it.gainDb.toDouble(), it.q.toDouble())
                    BiquadMath.magnitudeDb(c, REF_FS, hz)
                }
                sl = (sl + (lo - at(REF_LOW_HZ)).toFloat()).coerceIn(-STAGE_LIMIT, STAGE_LIMIT)
                sm = (sm + (mi - at(REF_MID_HZ)).toFloat()).coerceIn(-STAGE_LIMIT, STAGE_LIMIT)
                sh = (sh + (hi - at(REF_HIGH_HZ)).toFloat()).coerceIn(-STAGE_LIMIT, STAGE_LIMIT)
            }
        }
        val bands = build(sl, sm, sh)
        // Exactly flat when everything is zero: return the canonical default object contents.
        if (bands.all { it.gainDb == 0f }) return EqParams(bands = flat)
        return EqParams(bands = bands)
    }

    /** Short human-readable description of what the mapping will do. */
    fun describe(bassOn: Boolean, bassSteps: Int, lowDb: Int, midDb: Int, highDb: Int): String {
        val p = toParams(bassOn, bassSteps, lowDb, midDb, highDb)
        val sub = p.bands[0].gainDb
        return "Sub shelf ${fmt(sub)} dB @ ${BASS_SHELF_HZ.toInt()} Hz; low ${lowDb} dB, mid ${midDb} dB, high ${highDb} dB (stage settings)"
    }

    private fun fmt(v: Float): String = (Math.round(v * 10f) / 10f).toString()
}
