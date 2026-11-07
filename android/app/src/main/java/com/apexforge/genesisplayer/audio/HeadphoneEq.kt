package com.apexforge.genesisplayer.audio

import com.apexforge.genesisplayer.data.ParametricEq

/**
 * Aurum 2.4 golden, charge 3: the headphone-correction state that the Media3 audio processor reads.
 *
 * Measured AutoEq profiles (MIT) run as exact parametric biquads in software, in Aurum's own engine, after decoding
 * and before the device's effects. The processor reads [config] on the playback thread and rebuilds its chain when
 * [generation] moves, so a change from the UI takes effect within one buffer, with no restart.
 *
 * Pure Kotlin (no Android): pinned by HeadphoneEqTest.
 */
object HeadphoneEq {
    enum class Status { MEASURED, ESTIMATED }

    data class Profile(val id: String, val label: String, val status: Status, val defaultStrength: Double,
                       val source: String, val filters: List<ParametricEq.Filter>)

    data class Config(val enabled: Boolean = false, val profile: Profile? = null, val strength: Double = 1.0) {
        val active: Boolean get() = enabled && profile != null && profile.filters.isNotEmpty() && strength > 0.0
        fun label(): String = when {
            profile == null || !enabled -> "Headphone correction off"
            else -> "${profile.label} · ${(strength * 100).toInt()}%" +
                (if (profile.status == Status.ESTIMATED) " · estimated" else " · measured")
        }
    }

    /** What the processor actually runs at a sample rate: the scaled filters and a clip-safe preamp (never > 0 dB). */
    data class Plan(val filters: List<ParametricEq.Filter>, val preampDb: Double)

    @Volatile var config: Config = Config(); private set
    @Volatile var generation: Int = 0; private set

    @Synchronized fun set(c: Config) { config = c.copy(strength = c.strength.coerceIn(0.0, 1.0)); generation++ }

    fun plan(c: Config, sampleRate: Double): Plan? {
        if (!c.active) return null
        val f = ParametricEq.scaled(c.profile!!.filters, c.strength)
        return Plan(f, ParametricEq.safePreampDb(f, sampleRate))
    }

    /** The default for a headphone the owner named: its own profile, at that profile's default strength, ON. */
    fun defaultFor(profiles: List<Profile>, defaultId: String?): Config {
        val p = profiles.firstOrNull { it.id == defaultId } ?: return Config()
        return Config(enabled = true, profile = p, strength = p.defaultStrength)
    }

    /** 16-bit PCM in, 16-bit PCM out, through a chain, with saturation (the preamp makes clipping a non-event). */
    fun process16(samples: ShortArray, count: Int, chain: ParametricEq.Chain, scratch: FloatArray) {
        require(count % chain.channelCount == 0) { "partial frame" }
        for (i in 0 until count) scratch[i] = samples[i] / 32768f
        chain.process(scratch, count / chain.channelCount)
        for (i in 0 until count) samples[i] = (scratch[i] * 32768f).coerceIn(-32768f, 32767f).toInt().toShort()
    }
}
