package com.apexforge.genesisplayer.audio

import android.content.Context
import android.util.Log
import com.apexforge.genesisplayer.data.ParametricEq
import org.json.JSONObject

/**
 * Aurum 2.4 golden, charge 3: the bundled AutoEq catalog (assets/autoeq, MIT, pinned commit; see SOURCE.md) and the
 * owner's saved choice. With no saved choice, the catalog's named default (Brandon's JBL Tune 530BT, ESTIMATED from
 * the measured Tune 520BT at 50% strength, because AutoEq has no 530BT measurement) is applied ON.
 */
object HeadphoneProfiles {
    private const val PREFS = "aurum_headphone_eq"
    @Volatile var catalog: List<HeadphoneEq.Profile> = emptyList(); private set
    @Volatile var defaultId: String? = null; private set

    @Synchronized fun load(ctx: Context) {
        if (catalog.isNotEmpty()) return
        try {
            val root = JSONObject(ctx.assets.open("autoeq/profiles.json").bufferedReader().use { it.readText() })
            defaultId = root.optString("default_for_brandon").ifEmpty { null }
            val arr = root.getJSONArray("profiles")
            catalog = (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val text = ctx.assets.open("autoeq/" + o.getString("file")).bufferedReader().use { it.readText() }
                val parsed = ParametricEq.parseAutoEq(o.getString("label"), text)
                HeadphoneEq.Profile(o.getString("id"), o.getString("label"),
                    if (o.optString("status") == "ESTIMATED") HeadphoneEq.Status.ESTIMATED else HeadphoneEq.Status.MEASURED,
                    o.optDouble("default_strength", 1.0), o.optString("source"), parsed.filters)
            }
        } catch (e: Exception) {
            Log.e("GenesisPlayer", "[HIFI] could not load the AutoEq catalog: ${e.message}")
            catalog = emptyList()
        }
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val cfg = if (!p.contains("enabled")) HeadphoneEq.defaultFor(catalog, defaultId)
        else HeadphoneEq.Config(p.getBoolean("enabled", false), catalog.firstOrNull { it.id == p.getString("profile", null) },
            p.getFloat("strength", 1f).toDouble())
        HeadphoneEq.set(cfg)
        Log.i("GenesisPlayer", "[HIFI] headphone correction: ${cfg.label()}")
    }

    fun choose(ctx: Context, cfg: HeadphoneEq.Config) {
        HeadphoneEq.set(cfg)
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("enabled", cfg.enabled).putString("profile", cfg.profile?.id)
            .putFloat("strength", cfg.strength.toFloat()).apply()
    }
}
