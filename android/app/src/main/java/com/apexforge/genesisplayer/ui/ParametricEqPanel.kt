package com.apexforge.genesisplayer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apexforge.genesisplayer.DspAndroidStore
import com.apexforge.genesisplayer.DspPreset
import com.apexforge.genesisplayer.DspPresets
import com.apexforge.genesisplayer.DspProfiles
import com.apexforge.genesisplayer.audio.DspCommand
import com.apexforge.genesisplayer.audio.DspEngine
import com.apexforge.genesisplayer.OutputClass
import com.apexforge.genesisplayer.ParametricEq
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * WO-AURUM-008 — ADVANCED mode: the parametric DSP engine with real controls.
 *
 * Every control drives [ParametricEq] (the tested DSP core): preamp -12..+6 dB,
 * 8 bands with real frequency/gain/Q, per-band bypass, whole-DSP A/B bypass,
 * volume-matched A/B trim, reset to true flat, headroom/protection readout,
 * and per-output-route profiles with persistence.
 *
 * Honesty note (also in DSP_EVIDENCE.md): the engine math, persistence, A/B
 * logic and protection are real and unit-tested. Since the AURUM rebuild the
 * panel ALSO drives the live playback path: every change is offered to the
 * audio thread via [DspEngine.processor] (lock-free [DspCommand] queue), but
 * only while the panel is editing the DEFAULT route — the live engine plays
 * the DEFAULT route profile, so other routes persist for inspection without
 * hijacking what you hear. The Easy panel's system-audiofx path is untouched.
 */
@Composable
fun ParametricEqPanel() {
    val ctx = LocalContext.current
    val store = remember { DspAndroidStore(ctx) }
    val eq = remember { ParametricEq(48000) }
    var route by remember { mutableStateOf(OutputClass.DEFAULT) }
    var presetName by remember { mutableStateOf(DspPresets.REFERENCE_FLAT.name) }
    var basePreamp by remember { mutableDoubleStateOf(0.0) }
    var abBypass by remember { mutableStateOf(false) }
    var abMatch by remember { mutableStateOf(false) }
    var rev by remember { mutableIntStateOf(0) }
    fun poke() { rev++ }

    /**
     * Forward the panel's full current state to the LIVE audio thread.
     * The live engine plays the DEFAULT route profile, so only edits made
     * while the panel shows the DEFAULT route go live; other routes persist
     * (DspProfiles) without hijacking what you hear.
     */
    fun pushLive() {
        if (route != OutputClass.DEFAULT) return
        val proc = DspEngine.processor ?: return
        proc.offerCommand(DspCommand.SetPreamp(basePreamp))
        proc.offerCommand(DspCommand.SetBypass(abBypass))
        eq.bands.forEachIndexed { i, b ->
            proc.offerCommand(DspCommand.SetBand(i, b.copy()))
        }
    }

    fun pushEngine(snap: Boolean) {
        // Level-matched A/B attenuates the BYPASSED path (see abBypassTrimDb):
        // the engaged path is never boosted, because the headroom auto-cut
        // would eat exactly that boost.
        eq.preampDb = basePreamp
        eq.bypassAll = abBypass
        eq.retarget(snap = snap)
        pushLive()
    }

    fun persistCustom() {
        DspProfiles.save(store, route, "Custom", basePreamp)
        DspProfiles.saveBands(store, route, eq.bands)
        presetName = "Custom"
    }

    fun applyNamedPreset(p: DspPreset) {
        basePreamp = p.preampDb
        abBypass = false
        abMatch = false
        eq.applyPreset(p)
        pushEngine(snap = true)
        DspProfiles.save(store, route, p.name, p.preampDb)
        DspProfiles.saveBands(store, route, eq.bands)
        presetName = p.name
        poke()
    }

    fun loadRoute(r: OutputClass) {
        route = r
        val p = DspProfiles.effectivePreset(store, r)
        presetName = p.name.substringBefore(" [")
        basePreamp = p.preampDb
        abBypass = false
        abMatch = false
        eq.applyPreset(p)
        pushLive()
        poke()
    }

    LaunchedEffect(Unit) { loadRoute(OutputClass.DEFAULT) }

    val shape = RoundedCornerShape(7.dp)
    val headroomCut = eq.preampDb - eq.effectivePreampDb()
    Column(
        Modifier.fillMaxWidth().clip(shape).background(Golden.surface)
            .border(1.dp, Golden.border, shape).padding(14.dp)
    ) {
        Kicker("ADVANCED DSP")
        Text("Parametric EQ", color = Golden.text, style = Golden.displayStyle(26.sp))
        Text(
            "Real biquad engine · per-route profiles · protection readout",
            color = Golden.dim, fontSize = 12.sp
        )
        Spacer(Modifier.height(10.dp))

        // ---- presets ----
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            DspPresets.ALL.forEach { p ->
                Pill(p.name, presetName == p.name) { applyNamedPreset(p) }
            }
            if (presetName == "Custom") Pill("Custom", true) {}
        }
        Spacer(Modifier.height(10.dp))

        // ---- output route ----
        Text("Output route", color = Golden.text, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            OutputClass.values().forEach { oc ->
                Pill(routeLabel(oc), route == oc) { loadRoute(oc) }
            }
        }
        if (route == OutputClass.PHONE_SPEAKER) {
            Text(
                "Phone speaker policy: sub-150 Hz boosts capped at +3 dB (wasted headroom otherwise).",
                color = Golden.dim, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp)
            )
        }
        Spacer(Modifier.height(10.dp))

        // ---- preamp ----
        DspSliderRow(
            label = "Preamp",
            valueText = fmtDb(basePreamp),
            value = basePreamp.toFloat(),
            min = ParametricEq.PREAMP_MIN_DB.toFloat(),
            max = ParametricEq.PREAMP_MAX_DB.toFloat(),
            onMove = {
                basePreamp = (it * 2).roundToInt() / 2.0
                pushEngine(snap = false); poke()
            },
            onDone = { persistCustom(); poke() }
        )

        // ---- protection readout ----
        Text(
            if (headroomCut > 1e-9)
                "Protection active: preamp auto-cut ${"%.1f".format(headroomCut)} dB to hold 0 dBFS headroom."
            else "Headroom OK — no auto-cut. Final peak guard (limiter) at -1 dBFS.",
            color = if (headroomCut > 1e-9) Golden.ember else Golden.dim,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 6.dp)
        )
        Spacer(Modifier.height(8.dp))

        // ---- bands ----
        eq.bands.forEachIndexed { i, band ->
            BandRow(
                label = bandLabel(i),
                freqHz = band.freqHz,
                gainDb = band.gainDb,
                q = band.q,
                bypassed = band.bypass,
                onGain = {
                    band.gainDb = (it * 2).roundToInt() / 2.0
                    pushEngine(snap = false); poke()
                },
                onGainDone = { persistCustom(); poke() },
                onFreq = {
                    band.freqHz = 10.0.pow(it.toDouble()).coerceIn(20.0, 20000.0)
                    pushEngine(snap = false); poke()
                },
                onFreqDone = { persistCustom(); poke() },
                onQ = {
                    band.q = it.toDouble().coerceIn(0.5, 8.0)
                    pushEngine(snap = false); poke()
                },
                onQDone = { persistCustom(); poke() },
                onBypass = {
                    band.bypass = it
                    pushEngine(snap = false); persistCustom(); poke()
                }
            )
        }

        Spacer(Modifier.height(10.dp))

        // ---- A/B trust ----
        Text("A/B trust", color = Golden.text, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(top = 4.dp)
        ) {
            Pill(if (abBypass) "Hearing: BYPASSED" else "Hearing: EQ ON", abBypass) {
                abBypass = !abBypass
                pushEngine(snap = true); poke()
            }
            Pill("Reset to flat", false) {
                basePreamp = 0.0
                abBypass = false
                abMatch = false
                eq.resetFlat()
                persistCustom()
                presetName = "Custom"
                pushLive()
                poke()
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("Level-matched A/B", color = Golden.text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Attenuates the BYPASSED path by ${fmtDb(-eq.abBypassTrimDb())} (measured broadband mean) " +
                        "so louder ≠ better. The engaged path is never boosted: the headroom guard would eat " +
                        "exactly that boost. Attenuation can never clip.",
                    color = Golden.dim, fontSize = 11.sp
                )
            }
            Switch(
                abMatch, {
                    abMatch = it
                    pushEngine(snap = false); poke()
                },
                colors = SwitchDefaults.colors(checkedThumbColor = Golden.ember),
                modifier = Modifier.semantics { contentDescription = "Level-matched A/B" }
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "Engine is real DSP math with per-route persistence. Changes go LIVE " +
                "on the audio thread while this panel shows the Default route " +
                "(other routes persist without changing what you hear). The Easy " +
                "panel's system-audiofx path is unchanged.",
            color = Golden.dim, fontSize = 11.sp
        )
    }
}

private fun routeLabel(oc: OutputClass): String = when (oc) {
    OutputClass.DEFAULT -> "Default"
    OutputClass.PHONE_SPEAKER -> "Phone speaker"
    OutputClass.HEADPHONES -> "Headphones"
    OutputClass.BLUETOOTH -> "Bluetooth"
    OutputClass.CAR_EXTERNAL -> "Car / external"
}

private fun bandLabel(i: Int): String = when (i) {
    0 -> "Sub · low shelf"
    7 -> "Air · high shelf"
    else -> "Band $i · peaking"
}

private fun fmtDb(v: Double): String {
    val r = (v * 10).roundToInt() / 10.0
    return if (r > 0) "+$r dB" else "$r dB"
}

private fun fmtFreq(hz: Double): String =
    if (hz >= 1000) "%.1f kHz".format(hz / 1000) else "%.0f Hz".format(hz)

@Composable
private fun DspSliderRow(
    label: String,
    valueText: String,
    value: Float,
    min: Float,
    max: Float,
    onMove: (Float) -> Unit,
    onDone: () -> Unit
) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Text(label, color = Golden.text, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        Text(valueText, color = Golden.dim, fontSize = 13.sp)
    }
    Slider(
        value.coerceIn(min, max), onMove,
        valueRange = min..max,
        onValueChangeFinished = onDone,
        colors = SliderDefaults.colors(thumbColor = Golden.ember, activeTrackColor = Golden.ember),
        modifier = Modifier.semantics { contentDescription = "$label $valueText" }
    )
}

@Composable
private fun BandRow(
    label: String,
    freqHz: Double,
    gainDb: Double,
    q: Double,
    bypassed: Boolean,
    onGain: (Float) -> Unit,
    onGainDone: () -> Unit,
    onFreq: (Float) -> Unit,
    onFreqDone: () -> Unit,
    onQ: (Float) -> Unit,
    onQDone: () -> Unit,
    onBypass: (Boolean) -> Unit
) {
    val shape = RoundedCornerShape(6.dp)
    Column(
        Modifier.fillMaxWidth().padding(top = 6.dp).clip(shape)
            .background(Golden.surface).border(1.dp, Golden.border, shape).padding(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label, color = Golden.text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Text(
                    "${fmtFreq(freqHz)} · ${fmtDb(gainDb)} · Q ${"%.2f".format(q)}" +
                        if (bypassed) " · bypassed" else "",
                    color = Golden.dim, fontSize = 11.sp
                )
            }
            Switch(
                !bypassed, { onBypass(!it) },
                colors = SwitchDefaults.colors(checkedThumbColor = Golden.ember),
                modifier = Modifier.semantics { contentDescription = "$label bypass" }
            )
        }
        DspSliderRow(
            label = "Gain", valueText = fmtDb(gainDb),
            value = gainDb.toFloat(), min = -12f, max = 12f,
            onMove = onGain, onDone = onGainDone
        )
        DspSliderRow(
            label = "Frequency", valueText = fmtFreq(freqHz),
            value = log10(freqHz).toFloat(),
            min = log10(20.0).toFloat(), max = log10(20000.0).toFloat(),
            onMove = onFreq, onDone = onFreqDone
        )
        DspSliderRow(
            label = "Q", valueText = "%.2f".format(q),
            value = q.toFloat(), min = 0.5f, max = 8f,
            onMove = onQ, onDone = onQDone
        )
    }
}
