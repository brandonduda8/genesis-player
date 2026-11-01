package com.apexforge.genesisplayer.ui.mymusic

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.apexforge.genesisplayer.data.mymusic.crate.SongStatus

fun isOnline(context: Context): Boolean {
    val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
    val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}

/** Live online/offline state, so the vault always tells the truth about what can play right now. */
@Composable
fun rememberOnline(): Boolean {
    val context = LocalContext.current
    val state = remember { mutableStateOf(isOnline(context)) }
    DisposableEffect(context) {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { state.value = isOnline(context) }
            override fun onLost(network: Network) { state.value = isOnline(context) }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { state.value = isOnline(context) }
        }
        try { cm?.registerDefaultNetworkCallback(cb) } catch (_: Exception) {}
        onDispose { try { cm?.unregisterNetworkCallback(cb) } catch (_: Exception) {} }
    }
    return state.value
}

fun SongStatus.color(): Color = when (this) {
    SongStatus.OFFLINE -> Aurum.Ok
    SongStatus.DOWNLOADABLE -> Aurum.Gold
    SongStatus.ONLINE_EXACT -> Aurum.Ember
    SongStatus.ALTERNATE -> Aurum.Celestial
    SongStatus.YOUTUBE -> Aurum.Dim
    SongStatus.NOT_CHECKED -> Aurum.Bronze
}
