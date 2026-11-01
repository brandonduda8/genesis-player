package com.apexforge.genesisplayer.ui.mymusic

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Aurum Pulse brand kit: "your living music vault".
 * Molten gold + ember on black obsidian, bronze for quiet labels, celestial violet reserved for special modes.
 */
object Aurum {
    val Gold = Color(0xFFF5B942)
    val Ember = Color(0xFFFF7A2F)
    val Obsidian = Color(0xFF0B0A0D)
    val Charcoal = Color(0xFF151318)
    val Card = Color(0xFF1C1820)
    val Bronze = Color(0xFFB08A55)
    val Celestial = Color(0xFF8F7BFF)
    val Text = Color(0xFFF7F1E8)
    val Dim = Color(0xFF9C9488)
    val Ok = Color(0xFF6FD69B)
    val Warn = Color(0xFFFFC266)

    const val TAGLINE = "Your sound. Turned alive."
    const val READY = "Your music vault is ready."
    const val OFFLINE_LINE = "Offline when the world isn't."
    const val SIGNAL_LINE = "Built from your signal."
    const val MEMORY_LINE = "Music that remembers you."
    const val CRATE_LINE = "Your crate. Your pulse. Your world."

    val goldBrush = Brush.linearGradient(listOf(Gold, Ember))
    val glow = Brush.radialGradient(listOf(Color(0x33F5B942), Color(0x00F5B942)))
}

/** Uppercase, letter-spaced bronze overline: the quiet name of a place in the vault. */
@Composable
fun Overline(text: String, color: Color = Aurum.Bronze) {
    Text(text.uppercase(), color = color, fontSize = 11.sp, letterSpacing = 2.4.sp, fontWeight = FontWeight.SemiBold)
}

/** Brand section header: overline, title, then one emotional line. */
@Composable
fun BrandHeader(overline: String, title: String, line: String? = null, modifier: Modifier = Modifier) {
    Column(modifier) {
        Overline(overline)
        Text(title, style = MaterialTheme.typography.headlineMedium, color = Aurum.Text, fontWeight = FontWeight.Black)
        line?.let { Text(it, color = Aurum.Gold, style = MaterialTheme.typography.bodyMedium) }
    }
}

/** A premium card: obsidian glass with a molten-gold hairline. */
@Composable
fun GoldCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(18.dp))
            .background(Brush.verticalGradient(listOf(Aurum.Card, Aurum.Charcoal)))
            .border(1.dp, Brush.linearGradient(listOf(Color(0x99F5B942), Color(0x33FF7A2F))), RoundedCornerShape(18.dp))
            .padding(16.dp),
        content = content
    )
}

/** A status pill: OFFLINE, ONLINE ONLY, YOUTUBE and so on. Colour carries meaning, text carries the truth. */
@Composable
fun Pill(text: String, color: Color) {
    Text(text, color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp,
        modifier = Modifier.border(1.dp, color.copy(alpha = 0.7f), RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 2.dp))
}

/** The pulse mark: concentric golden rings breathing outward from an ember core. */
@Composable
fun PulseMark(size: Dp = 120.dp, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "pulse")
    val phase by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2600, easing = LinearEasing), RepeatMode.Restart), label = "phase")
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val c = Offset(this.size.width / 2, this.size.height / 2)
            val maxR = this.size.minDimension / 2
            drawCircle(Aurum.glow, maxR, c)
            for (i in 0 until 3) {
                val p = (phase + i / 3f) % 1f
                drawCircle(Aurum.Gold.copy(alpha = (1f - p) * 0.8f), radius = maxR * (0.25f + 0.75f * p), center = c,
                    style = Stroke(width = 2.dp.toPx()))
            }
            drawCircle(Brush.radialGradient(listOf(Aurum.Gold, Aurum.Ember), c, maxR * 0.22f), maxR * 0.2f, c)
        }
    }
}
