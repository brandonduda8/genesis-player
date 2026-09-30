package com.apexforge.genesisplayer.audio

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.apexforge.genesisplayer.AudioFxController
import com.apexforge.genesisplayer.dsp.DspPresets
import com.apexforge.genesisplayer.dsp.EasyMapping
import com.apexforge.genesisplayer.dsp.EqParams
import com.apexforge.genesisplayer.dsp.KeyValueStore
import com.apexforge.genesisplayer.dsp.Loudness
import com.apexforge.genesisplayer.dsp.OutputClass
import com.apexforge.genesisplayer.dsp.ParametricEq
import com.apexforge.genesisplayer.dsp.RouteLimits
import com.apexforge.genesisplayer.dsp.RouteProfileStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit

enum class DspMode { EASY, ADVANCED }

/**
 * Observable DSP state for the UI.
 * [bypass] = whole-DSP bit-exact bypass. [abEnabled] = false means the A/B "original" side is
 * playing (flat, [abMatchGainDb] applied so it is level-matched to the processed side).
 */
data class DspState(
    val params: EqParams = EqParams(),
    val outputClass: OutputClass = OutputClass.DEFAULT,
    val mode: DspMode = DspMode.EASY,
    val bypass: Boolean = false,
    val limiterActive: Boolean = false,
    val effectivePreampDb: Double = 0.0,
    val cpuLoadPercent: Double = 0.0,
    val failedClosed: Boolean = false,
    val abEnabled: Boolean = true,
    val abMatchGainDb: Double = 0.0
)

/**
 * WO-AURUM-008 process-wide DSP owner.
 *
 * Single shared processor: [processor] is the ONE EqAudioProcessor given to the main
 * ExoPlayer's DefaultAudioSink (one engine state, one limiter, one CPU meter).
 * Crossfade: player2 gets its own instance from [newSecondaryProcessor] which mirrors the
 * same effective params, so the tonal balance and level do not jump during the fade
 * (a processor instance cannot be shared by two sinks at once).
 *
 * FAIL-CLOSED: a 1 Hz watchdog compares the processor's rolling cpuLoadPercent with
 * [cpuBudgetPercent] (default 35 % of one core). If it stays above budget for
 * [cpuBudgetSeconds] consecutive ticks (default 5) while audio is actually flowing, or the
 * engine faults on the audio thread, the controller sets failedClosed, forces a flat
 * bit-exact bypass and restores the legacy audiofx chain as the simpler DSP. Logged once.
 * Not persisted: a fresh process tries the engine again; [retryEngine] re-arms it.
 */
object DspController {
    private const val TAG = "GenesisDsp"
    private const val PREFS = "genesis_dsp"
    private const val KEY_MODE = "dsp_mode"
    private const val ANALYSIS_FS = 48000
    private const val MATCH_FS = 48000.0

    /** Documented fail-closed budget. */
    @Volatile var cpuBudgetPercent: Double = 35.0
    @Volatile var cpuBudgetSeconds: Int = 5

    /** The shared main-player processor. */
    val processor = EqAudioProcessor()

    private val lock = Any()
    private val secondaries = CopyOnWriteArrayList<EqAudioProcessor>()
    private var store: RouteProfileStore? = null
    private var kv: KeyValueStore? = null
    private var legacyFx: AudioFxController? = null
    private var executor: ScheduledExecutorService? = null

    // Control-plane state, guarded by [lock].
    private var userParams: EqParams = EqParams()     // bypass flag inside is always false
    private var outputClass: OutputClass = OutputClass.DEFAULT
    private var mode: DspMode = DspMode.EASY
    private var bypass = false
    private var abEnabled = true
    private var abMatchGainDb = 0.0
    private var failedClosed = false
    private var overBudgetTicks = 0
    private var lastFrames = 0L
    private var failLogged = false
    private var lastLegacyActive: Boolean? = null

    // UI-only analysis engine: same ParametricEq class, never fed audio. Used for responseDb.
    private var analysis: ParametricEq = ParametricEq(ANALYSIS_FS, 2)

    private val _state = MutableStateFlow(DspState())
    val state: StateFlow<DspState> get() = _state

    // ------------------------------------------------------------------ lifecycle

    fun init(context: Context) {
        try {
            synchronized(lock) {
                if (store == null) {
                    val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    val k = PrefsKv(prefs)
                    kv = k
                    store = RouteProfileStore(k)
                    mode = try { DspMode.valueOf(k.getString(KEY_MODE) ?: "EASY") } catch (_: Exception) { DspMode.EASY }
                    userParams = loadForRoute(outputClass)
                    pushLocked()
                }
            }
            startWatchdog()
        } catch (t: Throwable) {
            Log.w(TAG, "init failed, DSP bypassed: ${t.message}")
            safeFailClosed("init: ${t.message}")
        }
    }

    /** Stops the watchdog thread (service onDestroy). State is kept; init() restarts it. */
    fun shutdown() {
        try { executor?.shutdownNow() } catch (_: Exception) {}
        executor = null
    }

    private fun startWatchdog() {
        synchronized(lock) {
            if (executor != null) return
            val ex = Executors.newSingleThreadScheduledExecutor(ThreadFactory { r ->
                Thread(r, "genesis-dsp-watchdog").also { it.isDaemon = true }
            })
            executor = ex
            ex.scheduleWithFixedDelay({ tick() }, 1, 1, TimeUnit.SECONDS)
        }
    }

    // ------------------------------------------------------------------ public controls

    fun setParams(p: EqParams) = guarded {
        synchronized(lock) {
            userParams = RouteLimits.applyRouteLimits(p.copy(bypass = false), outputClass)
            pushLocked()
        }
    }

    fun setOutputClass(c: OutputClass) = guarded {
        synchronized(lock) {
            if (c == outputClass && store != null && _state.value.outputClass == c) return@guarded
            outputClass = c
            userParams = loadForRoute(c)
            pushLocked()
        }
    }

    /** Persists the profile for the current route. */
    fun save() = guarded {
        synchronized(lock) { store?.save(outputClass, userParams.copy(bypass = false)) }
    }

    fun resetToFlat() = guarded {
        synchronized(lock) {
            userParams = EqParams()
            abEnabled = true
            pushLocked()
        }
    }

    /** Whole-DSP bit-exact bypass (no level match). */
    fun setBypass(on: Boolean) = guarded {
        synchronized(lock) { bypass = on; pushLocked() }
    }

    /**
     * One-tap A/B. dspOn=false plays the flat "original" with Loudness.matchedBypassGainDb
     * applied so both sides are level-matched (level-matched, not loudness-normalised).
     */
    fun setAb(dspOn: Boolean) = guarded {
        synchronized(lock) { abEnabled = dspOn; pushLocked() }
    }

    fun toggleAb() = setAb(!_state.value.abEnabled)

    fun applyPreset(name: String) = guarded {
        val p = DspPresets.byName(name) ?: return@guarded
        setParams(p)
    }

    fun applyEasy(bassOn: Boolean, bassSteps: Int, lowDb: Int, midDb: Int, highDb: Int) = guarded {
        setParams(EasyMapping.toParams(bassOn, bassSteps, lowDb, midDb, highDb))
    }

    fun setMode(m: DspMode) = guarded {
        synchronized(lock) {
            mode = m
            try { kv?.putString(KEY_MODE, m.name) } catch (_: Exception) {}
            publishLocked()
        }
    }

    /** Re-arm the engine after a fail-closed event (user-initiated). */
    fun retryEngine() = guarded {
        synchronized(lock) {
            failedClosed = false; overBudgetTicks = 0; failLogged = false
            pushLocked()
        }
    }

    /** Response of the current effective processing in dB at [freqHz] (UI thread). */
    fun responseDb(freqHz: Double): Double = synchronized(lock) {
        try { analysis.responseDb(freqHz).toDouble() } catch (_: Throwable) { 0.0 }
    }

    /** Params of the profile being edited (user params, before A/B or bypass). */
    fun currentParams(): EqParams = synchronized(lock) { userParams }

    /**
     * Second processor for the crossfade player (player2). Mirrors the main params. Only the
     * most recent secondary is kept registered (bounded; older crossfade players are gone).
     */
    fun newSecondaryProcessor(): EqAudioProcessor {
        val sp = EqAudioProcessor()
        try {
            synchronized(lock) {
                secondaries.clear()
                secondaries.add(sp)
                sp.setParams(effectiveLocked())
            }
        } catch (t: Throwable) { Log.w(TAG, "secondary processor init: ${t.message}") }
        return sp
    }

    // ------------------------------------------------------------------ legacy fx gate

    /** Called by PlayerService whenever AudioFxController is created (or null on release). */
    fun onLegacyFxAttached(fx: AudioFxController?) {
        synchronized(lock) {
            legacyFx = fx
            lastLegacyActive = null
        }
        enforceLegacyGate()
    }

    /**
     * Idempotent. Engine active => device BassBoost/Equalizer disabled (not released; the user's
     * saved enabled flags in AudioFxController prefs are untouched). Bypassed or failed closed
     * => saved enabled state restored. Also run each watchdog tick so legacy UI writes that
     * re-enable an effect are corrected within 1 s.
     */
    fun enforceLegacyGate() {
        try {
            val fx: AudioFxController?
            val engineActive: Boolean
            synchronized(lock) { fx = legacyFx; engineActive = !failedClosed && !bypass }
            if (fx == null) return
            if (engineActive) {
                try { if (fx.bassBoost?.enabled == true) fx.bassBoost?.enabled = false } catch (_: Exception) {}
                try { if (fx.equalizer?.enabled == true) fx.equalizer?.enabled = false } catch (_: Exception) {}
            } else {
                try { fx.bassBoost?.enabled = fx.isBassEnabled() } catch (_: Exception) {}
                try { fx.equalizer?.enabled = fx.isEqEnabled() } catch (_: Exception) {}
            }
            lastLegacyActive = engineActive
        } catch (t: Throwable) {
            Log.w(TAG, "legacy gate: ${t.message}")
        }
    }

    // ------------------------------------------------------------------ internals

    private fun loadForRoute(c: OutputClass): EqParams {
        val loaded = try { store?.loadOrDefault(c) } catch (_: Exception) { null } ?: EqParams()
        return RouteLimits.applyRouteLimits(loaded.copy(bypass = false), c)
    }

    /** Effective params handed to the engine(s). Caller holds [lock]. */
    private fun effectiveLocked(): EqParams {
        if (failedClosed) return EqParams(bypass = true)
        if (bypass) return userParams.copy(bypass = true)
        if (!abEnabled) {
            val g = abMatchGainDb.toFloat().coerceIn(EqParams.PREAMP_MIN_DB, EqParams.PREAMP_MAX_DB)
            // Flat bands + matched gain; limiter stays on, auto-headroom off so the gain is not undone.
            return EqParams(preampDb = g, bypass = false, limiterEnabled = true, autoHeadroom = false)
        }
        return userParams
    }

    private fun pushLocked() {
        abMatchGainDb = try { Loudness.matchedBypassGainDb(userParams, MATCH_FS) } catch (_: Throwable) { 0.0 }
        val eff = effectiveLocked()
        processor.setParams(eff)
        for (s in secondaries) s.setParams(eff)
        try { analysis.setParams(eff) } catch (_: Throwable) {}
        publishLocked()
        // The legacy gate is applied outside the lock by callers/tick; run it on next tick at latest.
    }

    private fun publishLocked() {
        _state.value = DspState(
            params = userParams,
            outputClass = outputClass,
            mode = mode,
            bypass = bypass,
            limiterActive = processor.limiterActive,
            effectivePreampDb = processor.effectivePreampDb,
            cpuLoadPercent = processor.cpuLoadPercent(),
            failedClosed = failedClosed,
            abEnabled = abEnabled,
            abMatchGainDb = abMatchGainDb
        )
    }

    /** 1 Hz: CPU watchdog + stats publish + legacy gate. Runs off the audio thread. */
    private fun tick() {
        try {
            val cpu = processor.cpuLoadPercent()
            val frames = processor.framesProcessed()
            var failReason: String? = null
            synchronized(lock) {
                val flowing = frames != lastFrames
                lastFrames = frames
                val engineActive = !failedClosed && !bypass
                if (engineActive && flowing && cpu > cpuBudgetPercent) overBudgetTicks++ else overBudgetTicks = 0
                if (engineActive && processor.faulted) failReason = "engine fault on audio thread"
                else if (engineActive && overBudgetTicks >= cpuBudgetSeconds)
                    failReason = "cpu %.1f%% > budget %.0f%% for %ds".format(cpu, cpuBudgetPercent, cpuBudgetSeconds)
                if (failReason == null) publishLocked()
            }
            failReason?.let { failClosed(it) }
            enforceLegacyGate()
        } catch (t: Throwable) {
            safeFailClosed("watchdog: ${t.message}")
        }
    }

    private fun failClosed(reason: String) {
        synchronized(lock) {
            failedClosed = true
            overBudgetTicks = 0
            pushLocked()
            if (!failLogged) {
                failLogged = true
                Log.w(TAG, "DSP FAILED CLOSED ($reason): engine bypassed, legacy audiofx restored")
            }
        }
        enforceLegacyGate()
    }

    private fun safeFailClosed(reason: String) {
        try { failClosed(reason) } catch (_: Throwable) {
            try { processor.setParams(EqParams(bypass = true)) } catch (_: Throwable) {}
        }
    }

    private inline fun guarded(block: () -> Unit) {
        try { block() } catch (t: Throwable) { safeFailClosed("control: ${t.message}") }
    }

    private class PrefsKv(private val p: SharedPreferences) : KeyValueStore {
        override fun getString(key: String): String? = p.getString(key, null)
        override fun putString(key: String, value: String) { p.edit().putString(key, value).apply() }
        override fun remove(key: String) { p.edit().remove(key).apply() }
    }
}
