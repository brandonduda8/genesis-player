package com.apexforge.genesisplayer.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Deterministic seeded procedural artwork — the fallback when a track has no
 * `artwork_url` (VISION.md §5). Keyed by hash(track id): the same track always
 * renders the same art; different tracks render different art.
 *
 * The look is deliberate craft, never a placeholder: charcoal-ash background
 * with a vignette, layered flame tongues rising ember-orange → phoenix-gold,
 * and drifting ember particles. Quantum-fire, the BRKNHARTED register.
 *
 * Rendering is pure android.graphics (no Compose), so instrumented tests can
 * assert pixel-determinism directly.
 */
object EmberArt {

    private const val CHARCOAL = 0xFF0B0B0D.toInt()
    private const val ASH = 0xFF1A1A1E.toInt()
    private const val EMBER = 0xFFFF6A00.toInt()
    private const val GOLD = 0xFFF5B942.toInt()

    private data class Flame(
        val xFrac: Float,      // horizontal center, 0..1
        val baseFrac: Float,   // base y, 0..1 (from top)
        val heightFrac: Float, // flame height, 0..1
        val widthFrac: Float,  // flame width, 0..1
        val lean: Float,       // horizontal lean, -1..1
        val hueMix: Float,     // 0 = ember, 1 = gold
        val alpha: Int
    )

    private data class EmberParams(
        val flames: List<Flame>,
        val particles: List<Triple<Float, Float, Float>>, // x, y, radius frac
        val glowX: Float,
        val glowY: Float
    )

    private fun paramsFor(trackId: String): EmberParams {
        // Seed from the track id hash — stable across processes and launches.
        val seed = (trackId.hashCode().toLong() shl 32) xor (trackId.hashCode().toLong() and 0xffffffffL)
        val r = Random(seed)
        val nFlames = 5 + r.nextInt(4)
        val flames = List(nFlames) {
            Flame(
                xFrac = 0.15f + r.nextFloat() * 0.7f,
                baseFrac = 0.95f + r.nextFloat() * 0.05f,
                heightFrac = 0.35f + r.nextFloat() * 0.45f,
                widthFrac = 0.10f + r.nextFloat() * 0.14f,
                lean = (r.nextFloat() - 0.5f) * 0.6f,
                hueMix = r.nextFloat(),
                alpha = 150 + r.nextInt(80)
            )
        }.sortedBy { it.hueMix } // gold in front of ember
        val nParticles = 24 + r.nextInt(20)
        val particles = List(nParticles) {
            Triple(r.nextFloat(), r.nextFloat() * 0.8f, 0.004f + r.nextFloat() * 0.012f)
        }
        return EmberParams(flames, particles, 0.5f, 0.85f)
    }

    private fun mix(a: Int, b: Int, t: Float): Int {
        val ia = (a shr 24) and 0xff; val ra = (a shr 16) and 0xff
        val ga = (a shr 8) and 0xff; val ba = a and 0xff
        val ib = (b shr 24) and 0xff; val rb = (b shr 16) and 0xff
        val gb = (b shr 8) and 0xff; val bb = b and 0xff
        return ((ia + (ib - ia) * t).toInt() shl 24) or
            ((ra + (rb - ra) * t).toInt() shl 16) or
            ((ga + (gb - ga) * t).toInt() shl 8) or
            (ba + (bb - ba) * t).toInt()
    }

    /**
     * Render the artwork for a track id at [sizePx] square. Deterministic:
     * same id + size -> pixel-identical bitmap.
     */
    fun renderBitmap(trackId: String, sizePx: Int): Bitmap {
        val size = sizePx.coerceIn(32, 1024)
        val p = paramsFor(trackId.ifEmpty { "brkn-vibes-brand" })
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val s = size.toFloat()

        // 1. Charcoal-ash background.
        val bg = Paint()
        bg.shader = LinearGradient(0f, 0f, 0f, s, ASH, CHARCOAL, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, s, s, bg)

        // 2. Deep ember glow behind the flames.
        val glow = Paint()
        glow.shader = RadialGradient(
            p.glowX * s, p.glowY * s, s * 0.75f,
            intArrayOf(mix(EMBER, Color.BLACK, 0.72f), Color.TRANSPARENT),
            floatArrayOf(0f, 1f), Shader.TileMode.CLAMP
        )
        c.drawRect(0f, 0f, s, s, glow)

        // 3. Flame tongues: layered teardrop paths, ember -> gold.
        for (f in p.flames) {
            val cx = f.xFrac * s
            val baseY = f.baseFrac * s
            val h = f.heightFrac * s
            val w = f.widthFrac * s
            val tipX = cx + f.lean * w * 2f
            val tipY = baseY - h
            // 3 stacked layers: wide dim base, mid body, bright core.
            val layers = listOf(1.0f to 90, 0.62f to 150, 0.34f to 210)
            for ((wf, baseAlpha) in layers) {
                val path = Path()
                val lw = w * wf
                path.moveTo(cx - lw, baseY)
                // left edge curving up to the tip
                path.cubicTo(
                    cx - lw * 0.9f, baseY - h * 0.35f,
                    cx - lw * 0.25f + f.lean * w, baseY - h * 0.75f,
                    tipX, tipY
                )
                // right edge back down
                path.cubicTo(
                    cx + lw * 0.25f + f.lean * w, baseY - h * 0.75f,
                    cx + lw * 0.9f, baseY - h * 0.35f,
                    cx + lw, baseY
                )
                path.close()
                val paint = Paint(Paint.ANTI_ALIAS_FLAG)
                val col = mix(EMBER, GOLD, f.hueMix)
                paint.shader = LinearGradient(
                    0f, baseY, 0f, tipY,
                    intArrayOf(
                        Color.argb((baseAlpha * 0.55f).toInt().coerceIn(0, 255), Color.red(col), Color.green(col), Color.blue(col)),
                        Color.argb(baseAlpha.coerceIn(0, 255), Color.red(col), Color.green(col), Color.blue(col)),
                        Color.argb((baseAlpha * 0.9f).toInt().coerceIn(0, 255), 255, 240, 200)
                    ),
                    floatArrayOf(0f, 0.55f, 1f),
                    Shader.TileMode.CLAMP
                )
                c.drawPath(path, paint)
            }
            // flame core flicker: small bright ellipse near the tip
            val core = Paint(Paint.ANTI_ALIAS_FLAG)
            core.color = Color.argb(140, 255, 220, 160)
            c.drawOval(
                tipX - w * 0.10f, tipY - h * 0.02f,
                tipX + w * 0.10f, tipY + h * 0.10f, core
            )
        }

        // 4. Drifting ember particles.
        val pp = Paint(Paint.ANTI_ALIAS_FLAG)
        for ((x, y, rf) in p.particles) {
            val rr = rf * s
            pp.color = Color.argb(110, 255, 150, 60)
            c.drawCircle(x * s, y * s, rr, pp)
            pp.color = Color.argb(60, 245, 185, 66)
            c.drawCircle(x * s, y * s, rr * 2.2f, pp)
        }

        // 5. Vignette: darkened corners so it reads as artwork, not a swatch.
        val vig = Paint()
        vig.shader = RadialGradient(
            s / 2f, s / 2f, s * 0.72f,
            intArrayOf(Color.TRANSPARENT, Color.argb(150, 0, 0, 0)),
            floatArrayOf(0.55f, 1f), Shader.TileMode.CLAMP
        )
        c.drawRect(0f, 0f, s, s, vig)

        return bmp
    }

    /** Pixel checksum for the determinism test (stable, cheap). */
    fun checksum(trackId: String, sizePx: Int): Long {
        val bmp = renderBitmap(trackId, sizePx)
        var h = 17L
        val row = IntArray(bmp.width)
        var y = 0
        while (y < bmp.height) {
            bmp.getPixels(row, 0, bmp.width, 0, y, bmp.width, 1)
            for (px in row) h = h * 31 + (px.toLong() and 0xffffffffL)
            y += 4 // sample every 4th row: fast, still discriminating
        }
        bmp.recycle()
        return h
    }
}
