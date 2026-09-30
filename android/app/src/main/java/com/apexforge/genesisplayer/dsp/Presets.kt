package com.apexforge.genesisplayer.dsp

/**
 * WO-AURUM-008 named sound presets. Every number below is the literal value in code.
 * Band layout is fixed by [EqParams]: 0 = low shelf, 1..6 = peaking, 7 = high shelf.
 * Format per band: freq Hz / gain dB / Q. "-" = neutral band (0 dB, kept at that frequency).
 * All presets: preampDb = 0, limiter on, autoHeadroom on (engine lowers level by worst-case boost).
 *
 * | Preset         | b0 LS          | b1 PK          | b2 PK          | b3 PK           | b4 PK           | b5 PK           | b6 PK           | b7 HS            |
 * |----------------|----------------|----------------|----------------|-----------------|-----------------|-----------------|-----------------|------------------|
 * | Reference      | 80/0/.707      | 160/0/1        | 320/0/1        | 640/0/1         | 1250/0/1        | 2500/0/1        | 5000/0/1        | 10000/0/.707     |
 * | FEEL IT        | 60/+6/.707     | 120/+3.5/1.0   | 250/-3/1.0     | 800/-         | 1600/-          | 3000/+1.5/1.0   | 5500/-          | 12000/+2/.707    |
 * | Night Drive    | 70/+4/.707     | 140/+1.5/1.0   | 300/-2/1.0     | 1000/+1/0.8     | 2500/+1.5/1.0   | 4500/-1.5/1.0   | 7000/-2/1.2     | 10000/-1.5/.707  |
 * | Emo/Vocal      | 80/+1.5/.707   | 160/+1/1.0     | 300/-2/1.0     | 1000/-0.5/1.0   | 2200/+3/1.0     | 3500/+2/1.2     | 7000/-3/2.0     | 12000/+1/.707    |
 * | Trap-Rock/Rage | 55/+9/.707     | 100/+4/1.0     | 300/-2/1.0     | 900/-2/0.8      | 1800/-1/1.0     | 4000/+3/1.0     | 7000/+2/1.0     | 11000/+3/.707    |
 * | Dark Cinematic | 50/+7/.707     | 100/+2.5/1.0   | 250/-1.5/1.0   | 1000/-1/0.8     | 2500/-1/1.0     | 5000/-2.5/1.0   | 8000/-3/1.0     | 10000/-4/.707    |
 *
 * All gains are within +-8 dB except the sub shelves (max +9, Trap-Rock/Rage). The worst-case
 * boost actually reached is measured in PresetsTest (not claimed here).
 */
object DspPresets {

    private fun band(i: Int, f: Float, g: Float, q: Float? = null): BandParams {
        val t = when (i) { 0 -> BandType.LOW_SHELF; EqParams.BAND_COUNT - 1 -> BandType.HIGH_SHELF; else -> BandType.PEAK }
        return BandParams(t, f, g, q ?: if (t == BandType.PEAK) 1.0f else 0.707f)
    }

    private fun mk(vararg b: Triple<Float, Float, Float?>): EqParams =
        EqParams(bands = b.mapIndexed { i, t -> band(i, t.first, t.second, t.third) })

    private fun n(f: Float) = Triple<Float, Float, Float?>(f, 0f, null)
    private fun b(f: Float, g: Float, q: Float? = null) = Triple(f, g, q)

    private val table: LinkedHashMap<String, Pair<EqParams, String>> = linkedMapOf(
        "Reference" to Pair(
            EqParams(),
            "Flat: no change to the sound, the honest starting point."
        ),
        "FEEL IT" to Pair(
            mk(b(60f, 6f), b(120f, 3.5f), b(250f, -3f), n(800f), n(1600f), b(3000f, 1.5f), n(5500f), b(12000f, 2f)),
            "Tight sub-bass and punchy mid-bass, less boxy mud, a little presence and air."
        ),
        "Night Drive" to Pair(
            mk(b(70f, 4f), b(140f, 1.5f), b(300f, -2f), b(1000f, 1f, 0.8f), b(2500f, 1.5f),
                b(4500f, -1.5f), b(7000f, -2f, 1.2f), b(10000f, -1.5f)),
            "Controlled bass with lifted mids to cut through road noise, and a smoother, less tiring top."
        ),
        "Emo/Vocal" to Pair(
            mk(b(80f, 1.5f), b(160f, 1f), b(300f, -2f), b(1000f, -0.5f), b(2200f, 3f),
                b(3500f, 2f, 1.2f), b(7000f, -3f, 2.0f), b(12000f, 1f)),
            "Forward vocals and guitars, restrained low end, softened 6-8 kHz harshness."
        ),
        "Trap-Rock/Rage" to Pair(
            mk(b(55f, 9f), b(100f, 4f), b(300f, -2f), b(900f, -2f, 0.8f), b(1800f, -1f),
                b(4000f, 3f), b(7000f, 2f), b(11000f, 3f)),
            "Heavy sub and kick punch, slightly scooped mids, bright aggressive attack."
        ),
        "Dark Cinematic" to Pair(
            mk(b(50f, 7f), b(100f, 2.5f), b(250f, -1.5f), b(1000f, -1f, 0.8f), b(2500f, -1f),
                b(5000f, -2.5f), b(8000f, -3f), b(10000f, -4f)),
            "Deep low end with a softened treble and slightly hollowed mids for a wide, dark feel."
        )
    )

    /** Preset names in display order. */
    val names: List<String> = table.keys.toList()

    fun byName(n: String): EqParams? = table[n]?.first

    fun descriptionOf(n: String): String? = table[n]?.second
}
