package com.apexforge.genesisplayer.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apexforge.genesisplayer.AudioFxController
import com.apexforge.genesisplayer.PlayerService

/**
 * Real system audio effects — BassBoost, multi-band Equalizer with presets,
 * LoudnessEnhancer — attached to the player's audio session. Every control
 * drives a genuine android.media.audiofx effect; nothing here is decorative.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EqScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // The live FX controller attaches when playback starts (audio session id
    // arrives), which may be after this screen first composes — poll for it.
    var fx by remember { mutableStateOf(PlayerService.fx) }
    LaunchedEffect(Unit) {
        while (true) {
            val live = PlayerService.fx
            if (live != null && live !== fx) fx = live
            kotlinx.coroutines.delay(1000)
        }
    }

    // Probe band metadata once from the live EQ, else fall back to a standard 5-band layout.
    val probe = remember(fx) { fx?.let { Probe(it) } }

    LazyColumn(modifier = modifier.fillMaxSize().padding(16.dp)) {
        item {
            Text("Amplify", style = MaterialTheme.typography.headlineMedium, color = PhoenixGold)
            Text("Real system DSP — bass, EQ and loudness on the live audio session",
                color = TextDim, fontSize = 13.sp)
            Spacer(Modifier.height(12.dp))
        }

        // ---- Bass boost ----
        item {
            var on by remember(fx) { mutableStateOf(fx?.isBassEnabled() ?: true) }
            var strength by remember(fx) { mutableIntStateOf(savedBass(context)) }
            SettingRow("Bass Boost", if (fx?.bassAvailable == false) "unavailable on this device" else "low-end punch",
                on, { on = it; fx?.setBassEnabled(it); saveBassOn(context, it) })
            if (fx?.bassAvailable != false) {
                Text("Strength ${(strength / 10)}%", color = TextDim, fontSize = 12.sp)
                Slider(strength.toFloat(), { strength = it.toInt(); fx?.setBassStrength(strength); saveBass(context, strength) },
                    valueRange = 0f..1000f, colors = emberSlider())
            }
            Spacer(Modifier.height(8.dp))
        }

        // ---- Equalizer ----
        item {
            var on by remember(fx) { mutableStateOf(fx?.isEqEnabled() ?: true) }
            SettingRow("Equalizer", bandSummary(probe), on,
                { on = it; fx?.setEqEnabled(it); saveEqOn(context, it) })
            if (fx?.eqAvailable == false) {
                Text("Equalizer unavailable on this device", color = TextDim, fontSize = 12.sp)
            } else {
                PresetPicker(fx)
                val bands = probe?.bandCount ?: 5
                for (b in 0 until bands) {
                    val label = probe?.bandLabel(b) ?: "${60 * (1 shl b)} Hz"
                    var lvl by remember(fx, b) { mutableIntStateOf(fx?.bandLevel(b) ?: savedBand(context, b)) }
                    Text("$label  ${lvl / 100} dB", color = TextDim, fontSize = 12.sp)
                    Slider(lvl.toFloat(), {
                        lvl = it.toInt()
                        val f = fx
                        if (f != null) { f.clearPreset(); f.setBandLevel(b, lvl) } else saveBand(context, b, lvl)
                    }, valueRange = -1500f..1500f, colors = emberSlider())
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        // ---- Loudness ----
        item {
            var on by remember(fx) { mutableStateOf(fx?.isLoudEnabled() ?: false) }
            var gain by remember(fx) { mutableIntStateOf(savedLoudGain(context)) }
            SettingRow("Loudness Enhancer", "perceived volume lift", on,
                { on = it; fx?.setLoudEnabled(it); saveLoudOn(context, it) })
            if (fx?.loudAvailable != false) {
                Text("Target gain ${gain / 100} dB", color = TextDim, fontSize = 12.sp)
                Slider(gain.toFloat(), { gain = it.toInt(); fx?.setLoudGain(gain); saveLoudGain(context, gain) },
                    valueRange = 0f..2000f, colors = emberSlider())
            } else if (fx != null) {
                Text("Loudness enhancer unavailable on this device", color = TextDim, fontSize = 12.sp)
            }
            Spacer(Modifier.height(16.dp))
            Text("Effects attach to the live audio session when playback starts.",
                color = TextDim, fontSize = 12.sp)
        }
    }
}

// ---------- helpers ----------

@Composable
private fun emberSlider() = SliderDefaults.colors(
    thumbColor = EmberOrange, activeTrackColor = EmberOrange
)

@Composable
private fun SettingRow(title: String, sub: String, on: Boolean, onToggle: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = PhoenixGold, fontSize = 16.sp)
            Text(sub, color = TextDim, fontSize = 12.sp)
        }
        Switch(on, onToggle, colors = SwitchDefaults.colors(checkedThumbColor = EmberOrange))
    }
}

/** Band metadata probed from the live Equalizer (null until playback starts). */
private class Probe(fx: AudioFxController) {
    val bandCount: Int
    private val labels: List<String>
    private val names: List<String>
    val presetCount: Int
    init {
        val eq = fx.equalizer
        bandCount = eq?.numberOfBands?.toInt() ?: 5
        labels = List(bandCount) { b ->
            try {
                val hz = eq!!.getCenterFreq(b.toShort()) / 1000
                if (hz >= 1000) "${hz / 1000} kHz" else "$hz Hz"
            } catch (_: Exception) { "Band $b" }
        }
        presetCount = try { eq?.numberOfPresets?.toInt() ?: 0 } catch (_: Exception) { 0 }
        names = List(presetCount) { i ->
            try { eq!!.getPresetName(i.toShort()) } catch (_: Exception) { "Preset $i" }
        }
    }
    fun bandLabel(b: Int) = labels.getOrElse(b) { "Band $b" }
    fun presetName(i: Int) = names.getOrElse(i) { "Preset $i" }
}

private fun bandSummary(probe: Probe?): String =
    if (probe == null) "per-band EQ (attaches when playback starts)"
    else "${probe.bandCount}-band EQ"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PresetPicker(fx: AudioFxController?) {
    var expanded by remember { mutableStateOf(false) }
    var current by remember { mutableStateOf("Custom") }
    val probe = remember(fx) { fx?.let { Probe(it) } }
    ExposedDropdownMenuBox(expanded, { expanded = !expanded }, Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        TextField(current, {}, readOnly = true, label = { Text("Preset", color = TextDim) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth())
        ExposedDropdownMenu(expanded, { expanded = false }) {
            DropdownMenuItem({ Text("Custom") }, {
                expanded = false; current = "Custom"; fx?.clearPreset()
            })
            val n = probe?.presetCount ?: 0
            for (i in 0 until n) {
                val name = probe!!.presetName(i)
                DropdownMenuItem({ Text(name) }, {
                    expanded = false; current = name; fx?.usePreset(i)
                })
            }
        }
    }
}

// ---------- prefs passthrough (mirror of AudioFxController keys) ----------
private fun prefs(c: android.content.Context) =
    c.getSharedPreferences("genesis_audiofx", android.content.Context.MODE_PRIVATE)

private fun savedBass(c: android.content.Context) = prefs(c).getInt("bass_strength", 800)
private fun saveBass(c: android.content.Context, v: Int) = prefs(c).edit().putInt("bass_strength", v).apply()
private fun saveBassOn(c: android.content.Context, v: Boolean) = prefs(c).edit().putBoolean("bass_on", v).apply()
private fun savedBand(c: android.content.Context, b: Int) = prefs(c).getInt("eq_band_$b", 0)
private fun saveBand(c: android.content.Context, b: Int, v: Int) = prefs(c).edit().putInt("eq_band_$b", v).apply()
private fun saveEqOn(c: android.content.Context, v: Boolean) = prefs(c).edit().putBoolean("eq_on", v).apply()
private fun savedLoudGain(c: android.content.Context) = prefs(c).getInt("loud_gain_mb", 0)
private fun saveLoudGain(c: android.content.Context, v: Int) = prefs(c).edit().putInt("loud_gain_mb", v).apply()
private fun saveLoudOn(c: android.content.Context, v: Boolean) = prefs(c).edit().putBoolean("loud_on", v).apply()
