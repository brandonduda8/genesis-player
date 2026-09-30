package com.apexforge.genesisplayer.dsp

/**
 * Per-output-class low-frequency safety policy.
 *
 * Caps (dB) for gain below 100 Hz: PHONE_SPEAKER +3, BLUETOOTH +9, CAR_EXTERNAL +10,
 * WIRED and DEFAULT +12 (the engine maximum, i.e. no cap).
 *
 * applyRouteLimits, exactly: for every enabled-or-not band of type LOW_SHELF or PEAK whose freqHz < 100
 * and gainDb > cap, gainDb is set to cap. Additionally on PHONE_SPEAKER only, LOW_SHELF/PEAK bands with
 * 100 <= freqHz <= 200 are capped at cap + 3 (= +6 dB). Cuts are never changed, HIGH_SHELF is never touched,
 * and the sum of several overlapping low bands is NOT re-limited (per-band cap only).
 */
object RouteLimits {
    const val SUB_CUTOFF_HZ = 100f
    const val PHONE_PUNCH_MAX_HZ = 200f
    const val PHONE_PUNCH_EXTRA_DB = 3f

    fun subCapDb(cls: OutputClass): Float = when (cls) {
        OutputClass.PHONE_SPEAKER -> 3f
        OutputClass.BLUETOOTH -> 9f
        OutputClass.CAR_EXTERNAL -> 10f
        OutputClass.WIRED, OutputClass.DEFAULT -> EqParams.GAIN_MAX_DB
    }

    fun applyRouteLimits(p: EqParams, cls: OutputClass): EqParams {
        val cap = subCapDb(cls)
        val nb = p.bands.map { b ->
            val lowType = b.type == BandType.LOW_SHELF || b.type == BandType.PEAK
            when {
                !lowType -> b
                b.freqHz < SUB_CUTOFF_HZ && b.gainDb > cap -> b.copy(gainDb = cap)
                cls == OutputClass.PHONE_SPEAKER && b.freqHz >= SUB_CUTOFF_HZ && b.freqHz <= PHONE_PUNCH_MAX_HZ &&
                    b.gainDb > cap + PHONE_PUNCH_EXTRA_DB -> b.copy(gainDb = cap + PHONE_PUNCH_EXTRA_DB)
                else -> b
            }
        }
        return p.copy(bands = nb)
    }
}

/**
 * Persists one EqParams per OutputClass under key "dsp_profile_<CLASS>".
 * Format (text, version-tagged): "v1|preampHex|bypass|limiter|auto|band;band;..." where each band is
 * "TYPE,freqHex,gainHex,qHex,enabled", floats as Float.toBits() hex (bit-exact round trip), booleans 0/1.
 * Parsing never throws: corrupt, unknown-version, wrong band count or non-finite values give null.
 * Values are clamped into legal ranges on load.
 */
class RouteProfileStore(private val kv: KeyValueStore) {

    fun save(cls: OutputClass, p: EqParams) { kv.putString(key(cls), serialize(p)) }

    fun load(cls: OutputClass): EqParams? {
        val s = try { kv.getString(key(cls)) } catch (e: Exception) { null } ?: return null
        return parse(s)
    }

    fun loadOrDefault(cls: OutputClass): EqParams =
        load(cls) ?: load(OutputClass.DEFAULT) ?: EqParams()

    fun clear(cls: OutputClass) { kv.remove(key(cls)) }

    companion object {
        const val VERSION = "v1"
        fun key(cls: OutputClass) = "dsp_profile_" + cls.name

        private fun hex(f: Float) = Integer.toHexString(f.toBits())
        private fun bool(b: Boolean) = if (b) "1" else "0"

        fun serialize(p: EqParams): String {
            val sb = StringBuilder()
            sb.append(VERSION).append('|').append(hex(p.preampDb)).append('|')
                .append(bool(p.bypass)).append('|').append(bool(p.limiterEnabled)).append('|')
                .append(bool(p.autoHeadroom)).append('|')
            p.bands.forEachIndexed { i, b ->
                if (i > 0) sb.append(';')
                sb.append(b.type.name).append(',').append(hex(b.freqHz)).append(',')
                    .append(hex(b.gainDb)).append(',').append(hex(b.q)).append(',').append(bool(b.enabled))
            }
            return sb.toString()
        }

        private fun pf(s: String): Float? {
            if (s.isEmpty() || s.length > 8) return null
            val bits = s.toLong(16)
            if (bits < 0 || bits > 0xFFFFFFFFL) return null
            val f = Float.fromBits(bits.toInt())
            return if (f.isNaN() || f.isInfinite()) null else f
        }

        private fun pb(s: String): Boolean? = when (s) { "1" -> true; "0" -> false; else -> null }

        fun parse(s: String): EqParams? {
            try {
                val top = s.split('|')
                if (top.size != 6 || top[0] != VERSION) return null
                val pre = (pf(top[1]) ?: return null).coerceIn(EqParams.PREAMP_MIN_DB, EqParams.PREAMP_MAX_DB)
                val bypass = pb(top[2]) ?: return null
                val lim = pb(top[3]) ?: return null
                val auto = pb(top[4]) ?: return null
                val parts = top[5].split(';')
                if (parts.size != EqParams.BAND_COUNT) return null
                val bands = ArrayList<BandParams>()
                for (part in parts) {
                    val f = part.split(',')
                    if (f.size != 5) return null
                    val type = BandType.values().firstOrNull { it.name == f[0] } ?: return null
                    val freq = (pf(f[1]) ?: return null).coerceIn(EqParams.FREQ_MIN_HZ, EqParams.FREQ_MAX_HZ)
                    val gain = (pf(f[2]) ?: return null).coerceIn(EqParams.GAIN_MIN_DB, EqParams.GAIN_MAX_DB)
                    val q = (pf(f[3]) ?: return null).coerceIn(EqParams.Q_MIN, EqParams.Q_MAX)
                    val en = pb(f[4]) ?: return null
                    bands.add(BandParams(type, freq, gain, q, en))
                }
                return EqParams(pre, bands, bypass, lim, auto)
            } catch (e: Exception) {
                return null
            }
        }
    }
}
