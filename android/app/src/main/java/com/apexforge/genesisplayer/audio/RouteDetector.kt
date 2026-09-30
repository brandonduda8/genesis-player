package com.apexforge.genesisplayer.audio

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.apexforge.genesisplayer.dsp.OutputClass
import com.apexforge.genesisplayer.dsp.RouteClassifier

/**
 * Android glue for route detection (WO-AURUM-008). Pure mapping lives in
 * dsp/RouteClassifier.kt. Uses only AudioManager.getDevices (API 23) and
 * AudioDeviceCallback, so it is API 26 safe. "Connected outputs" is used as a proxy for the
 * active route; priority wired > bluetooth > car > speaker.
 * start() in service onCreate, stop() in onDestroy.
 */
class RouteDetector(context: Context) {
    private val am = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private var registered = false
    @Volatile var current: OutputClass = OutputClass.DEFAULT
        private set

    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) { refresh() }
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) { refresh() }
    }

    fun start() {
        val m = am ?: return
        try {
            if (!registered) {
                m.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
                registered = true
            }
            refresh()
        } catch (t: Throwable) {
            Log.w(TAG, "route detector start failed: ${t.message}")
        }
    }

    fun stop() {
        val m = am ?: return
        try { if (registered) m.unregisterAudioDeviceCallback(callback) } catch (_: Throwable) {}
        registered = false
    }

    fun refresh() {
        try {
            val m = am ?: return
            val devs = m.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            val types = IntArray(devs.size) { devs[it].type }
            val c = RouteClassifier.classify(types)
            current = c
            DspController.setOutputClass(c)
        } catch (t: Throwable) {
            Log.w(TAG, "route refresh failed: ${t.message}")
        }
    }

    private companion object { const val TAG = "GenesisRoute" }
}
