package com.apexforge.genesisplayer

import android.content.Context

/**
 * WO-AURUM-008 — [DspPrefStore] backed by SharedPreferences.
 * The pure profile logic lives in [DspProfiles] and is JVM-tested; this is
 * just the Android storage adapter.
 */
class DspAndroidStore(context: Context) : DspPrefStore {
    private val prefs =
        context.getSharedPreferences("genesis_dsp", Context.MODE_PRIVATE)

    override fun getString(key: String, default: String): String =
        prefs.getString(key, default) ?: default

    override fun putString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    override fun getDouble(key: String, default: Double): Double =
        prefs.getString(key, null)?.toDoubleOrNull() ?: default

    override fun putDouble(key: String, value: Double) {
        prefs.edit().putString(key, value.toString()).apply()
    }
}
