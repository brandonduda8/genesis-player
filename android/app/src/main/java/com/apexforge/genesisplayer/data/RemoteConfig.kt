package com.apexforge.genesisplayer.data

import android.content.Context
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import com.apexforge.genesisplayer.ui.AppLabels
import com.apexforge.genesisplayer.ui.RemoteTheme
import com.apexforge.genesisplayer.ui.Section
import com.apexforge.genesisplayer.ui.ThemePalette
import org.json.JSONObject

private const val TAG = "GenesisPlayer"

/**
 * Remote look-and-feel: https://raw.githubusercontent.com/brandonduda8/genesis-catalog/master/config.json
 *
 * - Fetched on launch on a background thread; NEVER blocks launch.
 * - Applied live (theme/sections/labels) only when remote version > active version.
 * - Applied config is cached to internal storage and re-applied on next launch.
 * - On failure (or older/equal version) the app keeps bundled/cached values.
 * - "Updated" is only claimed when a version actually advanced.
 */
object RemoteConfig {
    const val CONFIG_URL =
        "https://raw.githubusercontent.com/brandonduda8/genesis-catalog/master/config.json"
    const val BUNDLED_VERSION = 0

    fun activeVersion(context: Context): Int =
        prefs(context).getInt("config_version", BUNDLED_VERSION)

    fun checkForUpdates(context: Context) {
        val app = context.applicationContext
        runBackground {
            try {
                val root = JSONObject(fetchJson(CONFIG_URL))
                val v = root.optInt("version", 0)
                val cur = activeVersion(app)
                if (v > cur) {
                    apply(root)
                    app.openFileOutput("remote_config.json", Context.MODE_PRIVATE).use {
                        it.write(root.toString().toByteArray())
                    }
                    prefs(app).edit().putInt("config_version", v).apply()
                    val accent = root.optJSONObject("theme")?.optString("accent", "?") ?: "?"
                    Log.i(TAG, "RemoteConfig: applied version $v accent=$accent")
                    postMain { RemoteCatalog.note.value = "Look updated (v$v)" }
                } else {
                    Log.i(TAG, "RemoteConfig: up to date (remote v$v, active v$cur)")
                }
            } catch (e: Exception) {
                Log.i(TAG, "RemoteConfig: fetch failed (${e.message}); using bundled/cached")
            }
        }
    }

    /** Parse + apply theme/sections/labels live on the main thread. */
    internal fun apply(root: JSONObject) {
        val t = root.optJSONObject("theme")
        val palette = if (t != null) ThemePalette(
            background = parseColor(t.optString("background", "#0A0A0C")),
            surface = parseColor(t.optString("surface", "#141417")),
            card = parseColor(t.optString("card", "#1B1B1F")),
            accent = parseColor(t.optString("accent", "#FF6A00")),
            gold = parseColor(t.optString("gold", "#F5B942")),
            text = parseColor(t.optString("text", "#FFFFFF")),
            textDim = parseColor(t.optString("text_dim", "#9A9AA0")),
            ember = parseColor(t.optString("ember", "#FF6A00"))
        ) else RemoteTheme.defaultPalette()
        val sections = root.optJSONArray("sections")?.let { arr ->
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                Section(o.getString("id"), o.optString("label", o.getString("id")), o.optBoolean("visible", true))
            }
        } ?: RemoteTheme.defaultSections()
        val l = root.optJSONObject("labels")
        val labels = if (l != null) AppLabels(
            appName = l.optString("app_name", "Genesis Player"),
            libraryTitle = l.optString("library_title", "Library"),
            librarySubtitle = l.optString("library_subtitle", "{count} tracks — streamed, never downloaded"),
            refreshLabel = l.optString("refresh_button", "⟳ Refresh music")
        ) else RemoteTheme.defaultLabels()
        postMain {
            RemoteTheme.palette.value = palette
            RemoteTheme.sections.value = sections
            RemoteTheme.labels.value = labels
        }
    }

    private fun parseColor(hex: String): Color {
        val h = hex.trim().removePrefix("#")
        val argb = when (h.length) {
            6 -> 0xFF000000.toInt() or h.toInt(16)
            8 -> h.toLong(16).toInt()
            else -> 0xFFFF6A00.toInt()
        }
        return Color(argb)
    }

    /** Re-apply the persisted cache on launch (before any network completes). */
    fun applyCache(context: Context) {
        val v = prefs(context).getInt("config_version", BUNDLED_VERSION)
        if (v <= BUNDLED_VERSION) return
        try {
            val json = context.openFileInput("remote_config.json").bufferedReader().use { it.readText() }
            apply(JSONObject(json))
            Log.i(TAG, "RemoteConfig: applied cached version $v")
        } catch (e: Exception) {
            Log.i(TAG, "RemoteConfig: cache unreadable (${e.message}); using bundled")
            prefs(context).edit().remove("config_version").apply()
        }
    }

}
