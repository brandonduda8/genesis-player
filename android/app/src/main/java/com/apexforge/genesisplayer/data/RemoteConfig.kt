package com.apexforge.genesisplayer.data

import android.content.Context
import android.util.Log

private const val TAG = "GenesisPlayer"

/**
 * Remote look-and-feel: PERMANENTLY DISABLED (Aurum Deep Space rebuild,
 * 2026-10-05).
 *
 * The remote theme/sections/labels application path was the fraud vector: a
 * stale config.json repainted the app with the old Ember look at launch, and
 * its on-device cache re-applied that look even offline. Both public entry
 * points below are kept ONLY so existing call sites keep compiling:
 * - applyCache() is now a one-time MIGRATION WIPE: it deletes any leftover
 *   "remote_config.json" cache file and the "config_version" preference so
 *   a stale v1 cache can NEVER repaint the app again.
 * - checkForUpdates() is a no-op that logs and ignores the request.
 * No theme/sections/labels application path may remain in this file.
 */
object RemoteConfig {
    /**
     * One-time migration wipe: remove any stale remote look-and-feel cache.
     * Safe to call on every launch (idempotent).
     */
    fun applyCache(context: Context) {
        try {
            context.deleteFile("remote_config.json")
            prefs(context).edit().remove("config_version").apply()
        } catch (e: Exception) {
            Log.w(TAG, "RemoteConfig: migration wipe failed (${e.message})")
        }
        Log.i(TAG, "RemoteConfig: migration wipe done")
    }

    /** Remote look-and-feel is disabled; the request is ignored. */
    fun checkForUpdates(context: Context, urlOverride: String? = null) {
        Log.i(TAG, "RemoteConfig: remote look-and-feel disabled; ignoring")
    }
}
