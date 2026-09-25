package com.apexforge.genesisplayer

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.PlayCircle
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.apexforge.genesisplayer.data.Library
import com.apexforge.genesisplayer.data.RemoteCatalog
import com.apexforge.genesisplayer.data.RemoteConfig
import com.apexforge.genesisplayer.ui.EqScreen
import com.apexforge.genesisplayer.ui.ForYouScreen
import com.apexforge.genesisplayer.ui.GenesisTheme
import com.apexforge.genesisplayer.ui.LibraryScreen
import com.apexforge.genesisplayer.ui.NowPlayingScreen
import com.google.common.util.concurrent.MoreExecutors

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Library.load(this)
        // Re-apply persisted remote state first (instant, offline-safe), then
        // fetch live in the background. Neither fetch ever blocks launch.
        RemoteCatalog.applyCache(this)
        RemoteConfig.applyCache(this)
        title = com.apexforge.genesisplayer.ui.RemoteTheme.labels.value.appName
        RemoteCatalog.checkForUpdates(this)
        RemoteConfig.checkForUpdates(this)
        handleTestPlay(intent)
        handleTestRefresh(intent)
        setContent { GenesisTheme { GenesisApp() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleTestPlay(intent)
        handleTestRefresh(intent)
    }

    private var testController: MediaController? = null

    /**
     * DEBUG-ONLY headless playback hook for the CI emulator gate. The gate
     * launches this activity with action TEST_PLAY; the activity is visible,
     * so starting the media foreground service is exemption-safe on API 34.
     * Release builds ignore it entirely.
     */
    private fun handleTestPlay(intent: Intent?) {
        if (!BuildConfig.DEBUG) return
        if (intent?.action != TEST_PLAY_ACTION) return
        val index = intent.getIntExtra("index", 0)
        val token = SessionToken(this, ComponentName(this, PlayerService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        future.addListener({
            try {
                val c = future.get()
                testController = c // held until onDestroy so the command is delivered
                val ids = Library.tracks.map { it.id }
                val args = Bundle().apply {
                    putStringArrayList("ids", ArrayList(ids))
                    putInt("index", index.coerceIn(ids.indices))
                }
                c.sendGenesis(PlayerService.ACTION_PLAY_IDS, args)
                Log.i("GenesisPlayer", "TEST_PLAY fired index=$index")
            } catch (e: Exception) {
                Log.e("GenesisPlayer", "TEST_PLAY failed", e)
            } finally {
                MediaController.releaseFuture(future)
            }
        }, MoreExecutors.directExecutor())
    }

    override fun onDestroy() {
        testController?.release()
        testController = null
        super.onDestroy()
    }

    companion object {
        const val TEST_PLAY_ACTION = "com.apexforge.genesisplayer.TEST_PLAY"
        const val TEST_REFRESH_ACTION = "com.apexforge.genesisplayer.TEST_REFRESH"
    }

    /**
     * DEBUG-ONLY hook for the CI emulator gate: forces a re-check of BOTH the
     * remote catalog and the remote config (same as the "Refresh music" button).
     * Optional intent extras "catalog_url" / "config_url" redirect the fetch at
     * a test server (the gate uses a local one via adb reverse) — production
     * code paths are otherwise identical. Release builds ignore it entirely.
     */
    private fun handleTestRefresh(intent: Intent?) {
        if (!BuildConfig.DEBUG) return
        if (intent?.action != TEST_REFRESH_ACTION) return
        RemoteCatalog.checkForUpdates(this, intent.getStringExtra("catalog_url"))
        RemoteConfig.checkForUpdates(this, intent.getStringExtra("config_url"))
        Log.i("GenesisPlayer", "TEST_REFRESH fired")
    }
}

@Composable
fun rememberPlayerController(): MediaController? {
    val context = LocalContext.current
    var controller by remember { mutableStateOf<MediaController?>(null) }
    DisposableEffect(context) {
        val token = SessionToken(context, ComponentName(context, PlayerService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({ controller = future.get() }, MoreExecutors.directExecutor())
        onDispose {
            controller?.release()
            MediaController.releaseFuture(future)
        }
    }
    return controller
}

/** Send a custom command to the service (playlists, skips, for-you). */
fun MediaController.sendGenesis(action: String, args: Bundle = Bundle()) {
    sendCustomCommand(SessionCommand(action, Bundle.EMPTY), args)
}

private data class Tab(val id: String, val name: String, val icon: ImageVector)

@Composable
fun GenesisApp() {
    val controller = rememberPlayerController()
    var tab by remember { mutableStateOf(0) }
    // Tabs are driven by the remote config's sections; hidden sections vanish.
    val sections = com.apexforge.genesisplayer.ui.RemoteTheme.sections.value.filter { it.visible }
    val icons = mapOf(
        "nowplaying" to Icons.Filled.PlayCircle,
        "library" to Icons.Filled.LibraryMusic,
        "foryou" to Icons.Filled.AutoAwesome,
        "eq" to Icons.Filled.GraphicEq
    )
    val tabs = sections.mapNotNull { s ->
        icons[s.id]?.let { Tab(s.id, s.label, it) }
    }.ifEmpty {
        listOf(
            Tab("nowplaying", "Now Playing", Icons.Filled.PlayCircle),
            Tab("library", "Library", Icons.Filled.LibraryMusic),
            Tab("foryou", "For You", Icons.Filled.AutoAwesome),
            Tab("eq", "EQ", Icons.Filled.GraphicEq)
        )
    }
    val safeTab = tab.coerceIn(tabs.indices)
    if (safeTab != tab) tab = safeTab
    Scaffold(
        bottomBar = {
            NavigationBar(containerColor = com.apexforge.genesisplayer.ui.SurfaceDark) {
                tabs.forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = { Icon(t.icon, contentDescription = t.name) },
                        label = { Text(t.name) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = com.apexforge.genesisplayer.ui.EmberOrange,
                            selectedTextColor = com.apexforge.genesisplayer.ui.EmberOrange,
                            indicatorColor = com.apexforge.genesisplayer.ui.CardDark
                        )
                    )
                }
            }
        }
    ) { pad ->
        Modifier.padding(pad)
        when (tabs[tab].id) {
            "nowplaying" -> NowPlayingScreen(controller, Modifier.padding(pad))
            "library" -> LibraryScreen(controller, Modifier.padding(pad))
            "foryou" -> ForYouScreen(controller, Modifier.padding(pad))
            "eq" -> EqScreen(Modifier.padding(pad))
        }
    }
}
