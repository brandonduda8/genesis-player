package com.apexforge.genesisplayer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apexforge.genesisplayer.audio.DspController
import com.apexforge.genesisplayer.audio.DspMode
import com.apexforge.genesisplayer.audio.DspState
import com.apexforge.genesisplayer.dsp.BandParams
import com.apexforge.genesisplayer.dsp.BandType
import com.apexforge.genesisplayer.dsp.DspPresets
import com.apexforge.genesisplayer.dsp.EqParams
import com.apexforge.genesisplayer.dsp.OutputClass
import com.apexforge.genesisplayer.dsp.RouteLimits
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

/*
 * WO-AURUM-008 Advanced EQ UI. Everything here reads DspController.state and writes through
 * DspController only. All dB values are real biquad/preamp gains from EqParams. BassBoost
 * (device effect) is never shown here, and nothing here labels a BassBoost strength as dB.
 */

private const val CURVE_POINTS = 96
private const val CURVE_DB_RANGE = 18f

// ---------------------------------------------------------------- shared small helpers

/** NaN-safe clamp: NaN becomes [fallback], everything else is coerced into [lo]..[hi]. */
private fun safeF(v: Float, lo: Float, hi: Float, fallback: Float): Float =
    if (v.isNaN()) fallback else v.coerceIn(lo, hi)

private fun fmtDb(v: Float): String {
    val r = Math.round(safeF(v, -1000f, 1000f, 0f) * 10f) / 10f
    return (if (r > 0f) "+" else "") + r.toString() + " dB"
}

private fun fmtDbD(v: Double): String {
    if (v.isNaN() || v.isInfinite()) return "n/a"
    val r = Math.round(v * 10.0) / 10.0
    return (if (r > 0.0) "+" else "") + r.toString() + " dB"
}

private fun fmtHz(f: Float): String {
    val v = safeF(f, EqParams.FREQ_MIN_HZ, EqParams.FREQ_MAX_HZ, 1000f)
    return if (v >= 1000f) (Math.round(v / 10f) / 100f).toString() + " kHz" else v.roundToInt().toString() + " Hz"
}

private fun fmtQ(q: Float): String =
    (Math.round(safeF(q, EqParams.Q_MIN, EqParams.Q_MAX, 1f) * 100f) / 100f).toString()

private fun outputClassLabel(c: OutputClass): String = when (c) {
    OutputClass.PHONE_SPEAKER -> "Phone speaker"
    OutputClass.WIRED -> "Wired"
    OutputClass.BLUETOOTH -> "Bluetooth"
    OutputClass.CAR_EXTERNAL -> "Car / external"
    OutputClass.DEFAULT -> "Default route"
}

private fun bandTypeLabel(t: BandType): String = when (t) {
    BandType.LOW_SHELF -> "Low shelf"
    BandType.PEAK -> "Peak"
    BandType.HIGH_SHELF -> "High shelf"
}

private fun withBand(p: EqParams, i: Int, change: (BandParams) -> BandParams): EqParams {
    val list = p.bands.toMutableList()
    list[i] = change(list[i])
    return p.copy(bands = list)
}

/**
 * Limits slider-driven DspController.setParams to about one call per 40 ms during a drag.
 * [submit] is for drags; [flush] sends the newest skipped value (call on drag end);
 * [commit] is for discrete edits and sends immediately, dropping any older pending value.
 */
private class ParamThrottle {
    private var lastNs = System.nanoTime() - 1_000_000_000L
    private var pending: EqParams? = null

    fun submit(p: EqParams) {
        pending = p
        val now = System.nanoTime()
        if (now - lastNs >= 40_000_000L) {
            lastNs = now
            flush()
        }
    }

    fun flush() {
        val x = pending
        pending = null
        if (x != null) DspController.setParams(x)
    }

    fun commit(p: EqParams) {
        pending = null
        lastNs = System.nanoTime()
        DspController.setParams(p)
    }
}

// ---------------------------------------------------------------- public composables

/** Easy / Advanced switch. Drives DspController.setMode. */
@Composable
fun EqModeSwitch(modifier: Modifier = Modifier) {
    val st by DspController.state.collectAsState()
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Pill("Easy", st.mode == DspMode.EASY) { DspController.setMode(DspMode.EASY) }
        Pill("Advanced", st.mode == DspMode.ADVANCED) { DspController.setMode(DspMode.ADVANCED) }
    }
}

/** Honest engine status. Renders nothing when the engine is healthy. */
@Composable
fun EngineStatusBanner(modifier: Modifier = Modifier) {
    val st by DspController.state.collectAsState()
    if (!st.failedClosed) return
    val shape = RoundedCornerShape(7.dp)
    Column(
        modifier.fillMaxWidth().clip(shape).background(Golden.surface2)
            .border(1.dp, Golden.ember, shape).padding(12.dp)
    ) {
        Text("DSP ENGINE UNAVAILABLE (failed closed)", color = Golden.ember, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.height(4.dp))
        Text(
            "The engine was bypassed to protect your audio and your device effects were restored to your saved device settings. " +
                "EQ edits are kept but will not change the sound until the engine is re-armed.",
            color = Golden.dim, fontSize = 12.sp
        )
        Spacer(Modifier.height(8.dp))
        Pill("Retry engine", false) { DspController.retryEngine() }
    }
}

@Composable
fun AdvancedEqPanel(modifier: Modifier = Modifier) {
    val st by DspController.state.collectAsState()
    val p = st.params
    val shape = RoundedCornerShape(7.dp)
    val throttle = remember { ParamThrottle() }
    val outClass = st.outputClass
    // Presets as the engine will actually hold them (route limits applied), for chip highlighting.
    val limitedPresets = remember(outClass) {
        DspPresets.names.map { n ->
            val raw = DspPresets.byName(n)
            n to (if (raw != null) RouteLimits.applyRouteLimits(raw, outClass) else null)
        }
    }

    Column(
        modifier.fillMaxWidth().clip(shape).background(Golden.surface)
            .border(1.dp, Golden.border, shape).padding(14.dp)
    ) {
        Kicker("AMPLIFIER")
        Text("Advanced EQ", color = Golden.text, style = Golden.displayStyle(26.sp))
        Spacer(Modifier.height(8.dp))

        // Engine status banner is shown once, by EqScreen.

        // ---- route badge + A/B + reset
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            RouteBadge(st.outputClass)
            Spacer(Modifier.weight(1f))
        }
        Spacer(Modifier.height(4.dp))
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Pill(if (st.abEnabled) "A: DSP on" else "B: Original", !st.abEnabled) { DspController.toggleAb() }
            Pill("Reset to flat", false) { DspController.resetToFlat() }
            Pill("Save for " + outputClassLabel(st.outputClass), false) { DspController.save() }
        }
        if (st.bypass) {
            Text(
                "Bypass is on: A/B has no effect until bypass is turned off.",
                color = Golden.ember, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp)
            )
        } else if (!st.abEnabled) {
            Text(
                "Playing the flat original, level-matched with " + fmtDbD(st.abMatchGainDb) +
                    " (level-matched, not loudness-normalised).",
                color = Golden.dim, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp)
            )
        }

        // ---- bypass
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Bypass DSP", color = Golden.text, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text("Whole engine off: output equals input", color = Golden.dim, fontSize = 12.sp)
            }
            Switch(
                st.bypass, { DspController.setBypass(it) },
                colors = SwitchDefaults.colors(checkedThumbColor = Golden.ember),
                modifier = Modifier.semantics { contentDescription = "Bypass DSP" }
            )
        }

        // ---- presets
        Spacer(Modifier.height(8.dp))
        Kicker("PRESETS")
        Spacer(Modifier.height(6.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            limitedPresets.forEach { (name, limited) ->
                Pill(name, limited != null && limited == p) { DspController.applyPreset(name) }
            }
        }

        // ---- live curve
        Spacer(Modifier.height(12.dp))
        Kicker("LIVE RESPONSE")
        Spacer(Modifier.height(6.dp))
        ResponseCurve(st)

        // ---- protection
        Spacer(Modifier.height(10.dp))
        ProtectionStatus(st)

        // ---- preamp
        Spacer(Modifier.height(12.dp))
        ParamSlider(
            label = "Preamp",
            format = { fmtDb(it) },
            value = safeF(p.preampDb, EqParams.PREAMP_MIN_DB, EqParams.PREAMP_MAX_DB, 0f),
            range = EqParams.PREAMP_MIN_DB..EqParams.PREAMP_MAX_DB,
            enabled = true,
            onFinished = { throttle.flush() }
        ) { v ->
            throttle.submit(p.copy(preampDb = safeF(v, EqParams.PREAMP_MIN_DB, EqParams.PREAMP_MAX_DB, 0f)))
        }

        // ---- bands
        p.bands.forEachIndexed { i, b ->
            Spacer(Modifier.height(10.dp))
            BandCard(
                i, b,
                onChange = { changed -> throttle.submit(withBand(p, i, changed)) },
                onCommit = { changed -> throttle.commit(withBand(p, i, changed)) },
                onFinished = { throttle.flush() }
            )
        }
    }
}

// ---------------------------------------------------------------- pieces

@Composable
private fun RouteBadge(c: OutputClass) {
    val shape = RoundedCornerShape(999.dp)
    Row(
        Modifier.clip(shape).background(Golden.surface2).border(1.dp, Golden.border, shape)
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .semantics { contentDescription = "Output route " + outputClassLabel(c) },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("OUTPUT", color = Golden.dim, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.width(6.dp))
        Text(outputClassLabel(c), color = Golden.gold, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
    }
}

@Composable
private fun ProtectionStatus(st: DspState) {
    val p = st.params
    val limiter = when {
        st.failedClosed || st.bypass -> "inactive (engine bypassed)"
        !st.abEnabled -> "on (A/B original side, fixed)"
        !p.limiterEnabled -> "off"
        st.limiterActive -> "reduced peaks in the last second"
        else -> "armed, no peak reduction in the last second"
    }
    val headroom = when {
        st.failedClosed || st.bypass -> "inactive (engine bypassed)"
        !st.abEnabled -> "off (A/B level match applied)"
        !p.autoHeadroom -> "off"
        else -> "on, effective preamp " + fmtDbD(st.effectivePreampDb)
    }
    Column {
        Kicker("PROTECTION")
        Spacer(Modifier.height(4.dp))
        Text("Limiter: $limiter", color = if (st.limiterActive && !st.bypass && !st.failedClosed) Golden.ember else Golden.dim, fontSize = 12.sp)
        Text("Auto headroom: $headroom", color = Golden.dim, fontSize = 12.sp)
        val cpu = st.cpuLoadPercent
        val cpuText = if (cpu.isNaN() || cpu.isInfinite()) "n/a" else (Math.round(cpu * 10.0) / 10.0).toString() + " % of one core"
        Text("DSP load: $cpuText", color = Golden.dim, fontSize = 12.sp)
    }
}

@Composable
private fun ResponseCurve(st: DspState) {
    val minLn = ln(EqParams.FREQ_MIN_HZ.toDouble())
    val maxLn = ln(EqParams.FREQ_MAX_HZ.toDouble())
    val params = st.params
    // responseDb excludes bypass/fail-closed, so those are forced flat here.
    val engineFlat = st.bypass || st.failedClosed
    val points = remember(params, st.bypass, st.abEnabled, st.failedClosed) {
        DoubleArray(CURVE_POINTS) { i ->
            if (engineFlat) return@DoubleArray 0.0
            val t = i.toDouble() / (CURVE_POINTS - 1).toDouble()
            val hz = exp(minLn + (maxLn - minLn) * t)
            val v = DspController.responseDb(hz)
            if (v.isNaN() || v.isInfinite()) 0.0 else v
        }
    }
    val bandDots = remember(params, st.bypass, st.abEnabled, st.failedClosed) {
        params.bands.map { b ->
            if (engineFlat) return@map Triple(b.enabled, ((ln(safeF(b.freqHz, EqParams.FREQ_MIN_HZ, EqParams.FREQ_MAX_HZ, 1000f).toDouble()) - minLn) / (maxLn - minLn)).toFloat(), 0f)
            val hz = safeF(b.freqHz, EqParams.FREQ_MIN_HZ, EqParams.FREQ_MAX_HZ, 1000f).toDouble()
            val v = DspController.responseDb(hz)
            Triple(b.enabled, ((ln(hz) - minLn) / (maxLn - minLn)).toFloat(), if (v.isNaN() || v.isInfinite()) 0f else v.toFloat())
        }
    }
    val gridColor = Golden.border
    val curveColor = Golden.ember
    val dotColor = Golden.gold

    Column(Modifier.fillMaxWidth()) {
        Canvas(
            Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(5.dp)).background(Golden.deck)
                .semantics { contentDescription = "Frequency response curve, 20 Hz to 20 kHz" }
        ) {
            val w = size.width
            val h = size.height
            fun yOf(db: Float): Float {
                val c = db.coerceIn(-CURVE_DB_RANGE, CURVE_DB_RANGE)
                return h * (1f - (c + CURVE_DB_RANGE) / (2f * CURVE_DB_RANGE))
            }
            // horizontal dB grid
            for (g in listOf(-12f, -6f, 0f, 6f, 12f)) {
                drawLine(
                    color = if (g == 0f) dotColor.copy(alpha = 0.5f) else gridColor,
                    start = Offset(0f, yOf(g)), end = Offset(w, yOf(g)),
                    strokeWidth = if (g == 0f) 2f else 1f
                )
            }
            // vertical log-frequency grid
            for (hz in listOf(100.0, 1000.0, 10000.0)) {
                val x = (((ln(hz) - minLn) / (maxLn - minLn)).toFloat()) * w
                drawLine(color = gridColor, start = Offset(x, 0f), end = Offset(x, h), strokeWidth = 1f)
            }
            // curve
            val path = Path()
            for (i in 0 until CURVE_POINTS) {
                val x = w * i.toFloat() / (CURVE_POINTS - 1).toFloat()
                val y = yOf(points[i].toFloat())
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, color = curveColor, style = Stroke(width = 4f))
            // band markers
            for ((on, fx, db) in bandDots) {
                drawCircle(
                    color = if (on) dotColor else gridColor,
                    radius = 6f,
                    center = Offset(fx.coerceIn(0f, 1f) * w, yOf(db))
                )
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth().height(16.dp)) {
            val labels = listOf(20.0 to "20", 100.0 to "100", 1000.0 to "1k", 10000.0 to "10k", 20000.0 to "20k")
            for ((hz, text) in labels) {
                val frac = ((ln(hz) - minLn) / (maxLn - minLn)).toFloat()
                Text(
                    text, color = Golden.dim, fontSize = 10.sp,
                    modifier = Modifier.offset(x = maxOf(0.dp, minOf(maxWidth * frac - 8.dp, maxWidth - 22.dp)))
                )
            }
        }
        Text(
            when {
                engineFlat -> "Flat: engine bypassed or failed closed. Scale +-" + CURVE_DB_RANGE.toInt() + " dB, log frequency."
                !st.abEnabled -> "A/B original side: flat line shifted by the level-match gain (" + fmtDbD(st.abMatchGainDb) + "). Scale +-" + CURVE_DB_RANGE.toInt() + " dB."
                else -> "Effective response, +-" + CURVE_DB_RANGE.toInt() + " dB, log frequency (Hz). Dots are the bands."
            },
            color = Golden.dim, fontSize = 11.sp
        )
    }
}

@Composable
private fun BandCard(
    index: Int,
    b: BandParams,
    onChange: ((BandParams) -> BandParams) -> Unit,
    onCommit: ((BandParams) -> BandParams) -> Unit,
    onFinished: () -> Unit
) {
    val shape = RoundedCornerShape(6.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(Golden.surface2)
            .border(1.dp, Golden.border, shape).padding(10.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Band ${index + 1}  " + bandTypeLabel(b.type), color = Golden.text, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text(
                    fmtHz(b.freqHz) + "  " + fmtDb(b.gainDb) + "  Q " + fmtQ(b.q),
                    color = Golden.dim, fontSize = 12.sp
                )
            }
            Switch(
                b.enabled, { on -> onCommit { it.copy(enabled = on) } },
                colors = SwitchDefaults.colors(checkedThumbColor = Golden.ember),
                modifier = Modifier.semantics { contentDescription = "Band ${index + 1} enabled" }
            )
        }
        // Frequency: slider works in ln(Hz) so the travel is logarithmic.
        val fHz = safeF(b.freqHz, EqParams.FREQ_MIN_HZ, EqParams.FREQ_MAX_HZ, 1000f)
        ParamSlider(
            label = "Frequency",
            format = { fmtHz(exp(it)) },
            value = ln(fHz),
            range = ln(EqParams.FREQ_MIN_HZ)..ln(EqParams.FREQ_MAX_HZ),
            enabled = b.enabled,
            onFinished = onFinished
        ) { v ->
            val hz = safeF(exp(v), EqParams.FREQ_MIN_HZ, EqParams.FREQ_MAX_HZ, fHz)
            onChange { it.copy(freqHz = hz.roundToInt().toFloat().coerceIn(EqParams.FREQ_MIN_HZ, EqParams.FREQ_MAX_HZ)) }
        }
        ParamSlider(
            label = "Gain",
            format = { fmtDb(it) },
            value = safeF(b.gainDb, EqParams.GAIN_MIN_DB, EqParams.GAIN_MAX_DB, 0f),
            range = EqParams.GAIN_MIN_DB..EqParams.GAIN_MAX_DB,
            enabled = b.enabled,
            onFinished = onFinished
        ) { v ->
            val g = Math.round(safeF(v, EqParams.GAIN_MIN_DB, EqParams.GAIN_MAX_DB, 0f) * 10f) / 10f
            onChange { it.copy(gainDb = g.coerceIn(EqParams.GAIN_MIN_DB, EqParams.GAIN_MAX_DB)) }
        }
        val q = safeF(b.q, EqParams.Q_MIN, EqParams.Q_MAX, 1f)
        ParamSlider(
            label = if (b.type == BandType.PEAK) "Q" else "Q (shelf slope)",
            format = { fmtQ(exp(it)) },
            value = ln(q),
            range = ln(EqParams.Q_MIN)..ln(EqParams.Q_MAX),
            enabled = b.enabled,
            onFinished = onFinished
        ) { v ->
            val nq = safeF(exp(v), EqParams.Q_MIN, EqParams.Q_MAX, q)
            val rounded = Math.round(nq * 100f) / 100f
            onChange { it.copy(q = rounded.coerceIn(EqParams.Q_MIN, EqParams.Q_MAX)) }
        }
    }
}

/**
 * [value] is the slider-domain value from engine state; while dragging, the local drag value is
 * shown so the thumb and readout stay smooth even when engine writes are throttled.
 * [format] turns a slider-domain value into the displayed text.
 */
@Composable
private fun ParamSlider(
    label: String,
    format: (Float) -> String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    enabled: Boolean,
    onFinished: () -> Unit,
    onValue: (Float) -> Unit
) {
    var drag by remember { mutableStateOf<Float?>(null) }
    val shown = safeF(drag ?: value, range.start, range.endInclusive, range.start)
    val valueText = format(shown)
    Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Text(label, color = Golden.text, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        Text(valueText, color = Golden.dim, fontSize = 13.sp)
    }
    Slider(
        value = shown,
        onValueChange = { v ->
            if (!v.isNaN()) {
                drag = v
                onValue(v)
            }
        },
        onValueChangeFinished = {
            onFinished()
            drag = null
        },
        valueRange = range,
        enabled = enabled,
        colors = SliderDefaults.colors(thumbColor = Golden.ember, activeTrackColor = Golden.ember),
        modifier = Modifier.semantics { contentDescription = "$label $valueText" }
    )
}
