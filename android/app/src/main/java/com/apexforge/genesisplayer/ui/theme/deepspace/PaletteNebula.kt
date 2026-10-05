package com.apexforge.genesisplayer.ui.theme.deepspace

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.palette.graphics.Palette
import coil.Coil
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.max
import kotlin.random.Random

/**
 * W2 — art-reactive nebula builder.
 *
 * Two-tier cache: memory [LruCache] (~8 bitmaps) + disk PNGs under
 * `<cacheDir>/nebula_cache`, keyed by sha1(artworkKey). The miss path runs
 * entirely on [Dispatchers.Default]: Coil loads the artwork, Palette
 * extracts 3–5 swatches, and a 720×1280 nebula is rendered once (radial
 * gradients in the swatch colors over deep indigo, plus faint seeded star
 * dust) and written to the disk tier.
 *
 * [onResult] is ALWAYS invoked on Main with a non-null bitmap — when the
 * artwork is missing or the load fails, the default indigo/gold nebula
 * (built from [DeepSpaceColors]) is used. Never blocks the UI thread.
 * Concurrent requests for the same key are coalesced: every caller gets
 * the callback.
 */
object PaletteNebula {

    private const val MEM_CACHE_SIZE = 8
    private const val DISK_DIR = "nebula_cache"
    private const val RENDER_W = 720
    private const val RENDER_H = 1280
    private const val PALETTE_MAX_DIM = 256

    private val memoryCache = LruCache<String, ImageBitmap>(MEM_CACHE_SIZE)
    private val pendingCallbacks =
        ConcurrentHashMap<String, CopyOnWriteArrayList<(ImageBitmap?) -> Unit>>()

    fun nebulaFor(
        artworkKey: String,
        artworkUrl: String?,
        context: Context,
        scope: CoroutineScope,
        onResult: (ImageBitmap?) -> Unit
    ) {
        val key = artworkKey.ifEmpty { "unknown" }
        val appContext = context.applicationContext

        // Fast path: memory hit → callback on Main, no background work.
        memoryCache.get(key)?.let { hit ->
            scope.launch(Dispatchers.Main) { onResult(hit) }
            return
        }

        // Coalesce concurrent callers for the same key.
        val isFirst = pendingCallbacks.putIfAbsent(key, CopyOnWriteArrayList()) == null
        pendingCallbacks[key]?.add(onResult)
        if (!isFirst) return

        scope.launch(Dispatchers.Default) {
            val disk = loadFromDisk(appContext, key)?.also { memoryCache.put(key, it) }
            val built = disk ?: runCatching {
                val bmp = buildAndStore(appContext, key, artworkUrl)
                memoryCache.put(key, bmp)
                bmp
            }.getOrNull()
            // Fallback guarantee: a bitmap is always delivered.
            val result = built
                ?: renderNebula(defaultSwatches(), key.hashCode().toLong()).asImageBitmap()
            val callbacks = pendingCallbacks.remove(key) ?: emptyList()
            withContext(Dispatchers.Main) {
                callbacks.forEach { it(result) }
            }
        }
    }

    /** Clears the memory tier. The disk tier survives (bounded by artwork count). */
    fun clearMemoryCache() {
        memoryCache.evictAll()
    }

    // ---------- disk tier ----------

    private fun cacheFile(context: Context, key: String): File {
        val dir = File(context.cacheDir, DISK_DIR).apply { mkdirs() }
        return File(dir, sha1(key) + ".png")
    }

    private fun loadFromDisk(context: Context, key: String): ImageBitmap? {
        return try {
            val f = cacheFile(context, key)
            if (!f.isFile) return null
            BitmapFactory.decodeFile(f.absolutePath)?.asImageBitmap()
        } catch (_: Exception) {
            null
        }
    }

    // ---------- miss path (Dispatchers.Default only) ----------

    private suspend fun buildAndStore(
        context: Context,
        key: String,
        artworkUrl: String?
    ): ImageBitmap {
        val swatches = extractSwatches(context, artworkUrl) ?: defaultSwatches()
        val bmp = renderNebula(swatches, key.hashCode().toLong())
        try {
            cacheFile(context, key).outputStream().use { out ->
                bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
        } catch (_: Exception) {
            // Disk tier is best-effort; the memory tier still holds the bitmap.
        }
        return bmp.asImageBitmap()
    }

    /**
     * Loads the artwork via Coil and extracts 3–5 dominant swatches with
     * AndroidX Palette. Returns null when the artwork is missing or the
     * load fails — the caller then uses [defaultSwatches].
     */
    private suspend fun extractSwatches(context: Context, artworkUrl: String?): List<Int>? {
        if (artworkUrl.isNullOrEmpty()) return null
        return try {
            val result = Coil.imageLoader(context)
                .execute(ImageRequest.Builder(context).data(artworkUrl).build())
            val drawable = (result as? SuccessResult)?.drawable ?: return null
            val small = downscale(drawableToBitmap(drawable), PALETTE_MAX_DIM)
            val picked = Palette.from(small).generate().swatches
                .sortedByDescending { it.population }
                .take(5)
                .map { it.rgb }
            if (picked.size < 3) null else picked
        } catch (_: Exception) {
            null
        }
    }

    private fun defaultSwatches(): List<Int> = listOf(
        DeepSpaceColors.DeepIndigo.toArgb(),
        DeepSpaceColors.Gold.toArgb(),
        DeepSpaceColors.SpaceBlack.toArgb(),
        0xFF3B2A6E.toInt() // violet lift so the fallback isn't flat
    )

    // ---------- bitmap helpers (pure framework, zero NDK) ----------

    private fun drawableToBitmap(d: Drawable): Bitmap {
        val w = d.intrinsicWidth.coerceAtLeast(1)
        val h = d.intrinsicHeight.coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        d.setBounds(0, 0, w, h)
        d.draw(canvas)
        return bmp
    }

    private fun downscale(src: Bitmap, maxDim: Int): Bitmap {
        val m = max(src.width, src.height)
        if (m <= maxDim) return src
        val s = maxDim.toFloat() / m
        return Bitmap.createScaledBitmap(
            src,
            (src.width * s).toInt().coerceAtLeast(1),
            (src.height * s).toInt().coerceAtLeast(1),
            true
        )
    }

    /**
     * Renders the nebula ONCE per artwork: radial gradients in the swatch
     * colors over deep indigo, plus faint seeded star dust. Per-frame code
     * ([drawNebula]) only blits this bitmap — no per-frame blur, ever.
     */
    private fun renderNebula(swatches: List<Int>, seed: Long): Bitmap {
        val bmp = Bitmap.createBitmap(RENDER_W, RENDER_H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val rnd = Random(seed)
        canvas.drawColor(DeepSpaceColors.DeepIndigo.toArgb())

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val big = max(RENDER_W, RENDER_H).toFloat()
        for (color in swatches) {
            val cx = rnd.nextFloat() * RENDER_W
            val cy = rnd.nextFloat() * RENDER_H
            val radius = (0.35f + rnd.nextFloat() * 0.45f) * big
            val transparent = color and 0x00FFFFFF
            paint.shader = RadialGradient(cx, cy, radius, color, transparent, Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, RENDER_W.toFloat(), RENDER_H.toFloat(), paint)
        }
        paint.shader = null

        // Faint star dust.
        val dust = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        repeat(140) {
            val a = ((0.10f + rnd.nextFloat() * 0.35f) * 255).toInt()
            dust.color = 0x00FFFFFF or (a shl 24)
            val r = 0.6f + rnd.nextFloat() * 1.6f
            canvas.drawCircle(rnd.nextFloat() * RENDER_W, rnd.nextFloat() * RENDER_H, r, dust)
        }
        return bmp
    }

    private fun sha1(s: String): String {
        val md = MessageDigest.getInstance("SHA-1")
        return md.digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }
}
