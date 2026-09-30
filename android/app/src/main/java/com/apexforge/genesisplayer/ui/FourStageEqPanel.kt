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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import com.apexforge.genesisplayer.AudioFxController
import com.apexforge.genesisplayer.FourStageEq
import com.apexforge.genesisplayer.audio.DspController
import com.apexforge.genesisplayer.dsp.EasyMapping
import com.apexforge.genesisplayer.dsp.RouteLimits
import kotlin.math.roundToInt

/**
 * Golden Player Phase 1 — the web deck's "AMPLIFIER · 4-stage EQ", Easy mode of the
 * DSP engine: each commit calls DspController.applyEasy (real biquads) and also keeps the
 * legacy audiofx chain in sync via [FourStageEq] as the fail-closed fallback. Boost shows
 * the engine's real low-shelf gain in dB (after route limits); the legacy BassBoost strength
 * (a percentage, no dB scale) is never shown as dB. Low / Mid / High slider range is clamped
 * to what the live device EQ supports (±12 dB at most).
 *
 * [onApplied] fires after each write so sibling raw controls can re-read.
 */
@Composable
fun FourStageEqPanel(fx: AudioFxController?, onApplied: () -> Unit = {}) {
    val ctx = LocalContext.current
    var s by remember(fx) { mutableStateOf(FourStageEq.load(ctx)) }
    val maxDb = remember(fx) {
        val range = try { fx?.equalizer?.bandLevelRange } catch (_: Exception) { null }
        val lo = range?.getOrNull(0)?.toInt()?.let { -it / 100 } ?: 12
        val hi = range?.getOrNull(1)?.toInt()?.let { it / 100 } ?: 12
        minOf(12, lo, hi).coerceAtLeast(1)
    }

    val dsp by DspController.state.collectAsState()
    // Real engine values for the current Easy settings (shelf gain is real dB on a real biquad).
    val easyParams = remember(s.bassOn, s.bass, s.low, s.mid, s.high) {
        EasyMapping.toParams(s.bassOn, s.bass, s.low, s.mid, s.high)
    }
    // What the engine will actually hold for this route (per-route sub-bass caps applied).
    val routeParams = remember(easyParams, dsp.outputClass) {
        RouteLimits.applyRouteLimits(easyParams, dsp.outputClass)
    }
    val shelfDb = routeParams.bands[0].gainDb
    val differs = routeParams != dsp.params

    fun commit(next: State4) {
        s = next
        // Legacy device effects are still written (saved prefs + fail-closed fallback), then the
        // engine is driven, then the gate is enforced at once so both never run together.
        FourStageEq.apply(ctx, fx, next)
        DspController.applyEasy(next.bassOn, next.bass, next.low, next.mid, next.high)
        DspController.enforceLegacyGate()
        onApplied()
    }

    val shape = RoundedCornerShape(7.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(Golden.surface)
            .border(1.dp, Golden.border, shape).padding(14.dp)
    ) {
        Kicker("AMPLIFIER")
        Text("4-stage EQ", color = Golden.text, style = Golden.displayStyle(26.sp))
        Spacer(Modifier.height(10.dp))
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            FourStageEq.PRESETS.forEach { (name, preset) ->
                Pill(name, s.preset == name) { commit(preset) }
            }
            if (s.preset !in FourStageEq.PRESETS) Pill("Custom", true) {}
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Bass", color = Golden.text, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text(
                    "DSP low shelf at ${EasyMapping.BASS_SHELF_HZ.toInt()} Hz (device BassBoost is only the fallback)",
                    color = Golden.dim, fontSize = 12.sp
                )
            }
            Switch(
                s.bassOn, { commit(s.copy(bassOn = it, preset = "Custom")) },
                colors = SwitchDefaults.colors(checkedThumbColor = Golden.ember),
                modifier = Modifier.semantics { contentDescription = "Bass on/off" }
            )
        }
        StageSlider("Boost", if (s.bassOn) shelfDbText(shelfDb) + " shelf" else "off", s.bass, 0, 12,
            onMove = { s = s.copy(bass = it, preset = "Custom") }, onDone = { commit(s) })
        StageSlider("Low", db(s.low), s.low, -maxDb, maxDb,
            onMove = { s = s.copy(low = it, preset = "Custom") }, onDone = { commit(s) })
        StageSlider("Mid", db(s.mid), s.mid, -maxDb, maxDb,
            onMove = { s = s.copy(mid = it, preset = "Custom") }, onDone = { commit(s) })
        StageSlider("High", db(s.high), s.high, -maxDb, maxDb,
            onMove = { s = s.copy(high = it, preset = "Custom") }, onDone = { commit(s) })
        Text(
            EasyMapping.describe(s.bassOn, s.bass, s.low, s.mid, s.high),
            color = Golden.dim, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp)
        )
        if (dsp.failedClosed) {
            Text(
                "DSP engine unavailable (failed closed): these settings are saved but the legacy device effects are playing.",
                color = Golden.ember, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp)
            )
        } else if (dsp.bypass) {
            Text("DSP bypass is on: Easy settings are saved but not applied.",
                color = Golden.ember, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
        } else if (differs) {
            Text(
                "The engine is currently using a different curve (Advanced edit, preset, or route limit). Moving a slider replaces it.",
                color = Golden.dim, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp)
            )
        }
        Text(
            if (fx != null) "Legacy device effects attached." else "Saved — applies when playback starts.",
            color = Golden.dim, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp)
        )
    }
}

private typealias State4 = FourStageEq.State

private fun shelfDbText(v: Float): String {
    val r = Math.round((if (v.isNaN()) 0f else v) * 10f) / 10f
    return (if (r > 0f) "+" else "") + r.toString() + " dB"
}

private fun db(v: Int) = if (v > 0) "+$v dB" else "$v dB"

@Composable
private fun StageSlider(
    label: String,
    value: String,
    current: Int,
    min: Int,
    max: Int,
    onMove: (Int) -> Unit,
    onDone: () -> Unit
) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Text(label, color = Golden.text, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        Text(value, color = Golden.dim, fontSize = 13.sp)
    }
    Slider(
        current.coerceIn(min, max).toFloat(),
        { onMove(it.roundToInt()) },
        valueRange = min.toFloat()..max.toFloat(),
        steps = (max - min - 1).coerceAtLeast(0),
        onValueChangeFinished = onDone,
        colors = SliderDefaults.colors(thumbColor = Golden.ember, activeTrackColor = Golden.ember),
        modifier = Modifier.semantics { contentDescription = "$label $value" }
    )
}
