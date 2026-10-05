package com.apexforge.genesisplayer

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Psychology
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.apexforge.genesisplayer.data.Ratings
import com.apexforge.genesisplayer.data.Library
import com.apexforge.genesisplayer.data.RemoteCatalog
import com.apexforge.genesisplayer.ui.AurumTheme
import com.apexforge.genesisplayer.ui.CardDark
import com.apexforge.genesisplayer.ui.CrateScreen
import com.apexforge.genesisplayer.ui.EqScreen
import com.apexforge.genesisplayer.ui.SurfaceDark
import com.apexforge.genesisplayer.ui.MiniPlayerBar
import com.apexforge.genesisplayer.ui.theme.deepspace.DeepSpaceColors
import com.apexforge.genesisplayer.ui.theme.deepspace.DeepSpaceNowPlaying
import com.apexforge.genesisplayer.ui.PlaylistsScreen
import com.apexforge.genesisplayer.ui.ApolloScreen
import com.apexforge.genesisplayer.ui.rememberPlayerPulse
import com.apexforge.genesisplayer.data.ApolloDrops
import com.apexforge.genesisplayer.data.SnapshotStore
import com.google.common.util.concurrent.MoreExecutors

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Library.load(this)
        // Aurum Deep Space rebuild: the remote look-and-feel path is gone.
        // One-time migration wipe — a stale remote_config.json / config_version
        // must NEVER repaint the app again. Idempotent; safe every launch.
        try {
            deleteFile("remote_config.json")
            getSharedPreferences("genesis_remote", MODE_PRIVATE).edit().remove("config_version").apply()
            Log.i("GenesisPlayer", "RemoteConfig: migration wipe done")
        } catch (_: Exception) { }
        // Music catalog cache + live fetch are untouched (playback lane).
        RemoteCatalog.applyCache(this)
        title = "Aurum"
        RemoteCatalog.checkForUpdates(this)
        ApolloDrops.poll(this) // Phase 1: Fresh Signals poll at launch (Refresh Music re-polls).
        SnapshotStore.fetch(this, "launch") // BRKN wave 3: machine-owned snapshot (graceful offline).
        handleTestPlay(intent)
        handleTestRefresh(intent)
        handleTestRate(intent)
        handleTestSleep(intent)
        handleTestXfade(intent)
        handleTestQueueDump(intent)
        setContent { AurumTheme { AurumApp() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleTestPlay(intent)
        handleTestRefresh(intent)
        handleTestRate(intent)
        handleTestSleep(intent)
        handleTestXfade(intent)
        handleTestQueueDump(intent)
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
                // Optional "playlist" extra (DEBUG gate): play a named playlist
                // instead of the whole library. Used to prove the SoundCloud
                // shelf end to end on the emulator.
                val plName = intent.getStringExtra("playlist")
                val ids = if (plName != null) {
                    Library.playlists.find { it.name == plName }?.trackIds
                        ?: Library.tracks.map { it.id }
                } else {
                    Library.tracks.map { it.id }
                }
                val args = Bundle().apply {
                    putStringArrayList("ids", ArrayList(ids))
                    putInt("index", index.coerceIn(ids.indices))
                }
                c.sendGenesis(PlayerService.ACTION_PLAY_IDS, args)
                if (intent.getBooleanExtra("shuffle", false)) {
                    c.shuffleModeEnabled = true
                }
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
        const val TEST_RATE_ACTION = "com.apexforge.genesisplayer.TEST_RATE"
        const val TEST_SLEEP_ACTION = "com.apexforge.genesisplayer.TEST_SLEEP"
        const val TEST_XFADE_ACTION = "com.apexforge.genesisplayer.TEST_XFADE"
        const val TEST_QUEUE_DUMP_ACTION = "com.apexforge.genesisplayer.TEST_QUEUE_DUMP"
    }

    /**
     * DEBUG-ONLY hook for the CI emulator gate: applies a like/dislike rating
     * through the exact same code path as the Now Playing buttons.
     * Extras: "track_id" (default: currently playing), "rating"
     * ("like" | "dislike" | "clear"). Release builds ignore it entirely.
     */
    private fun handleTestRate(intent: Intent?) {
        if (!BuildConfig.DEBUG) return
        if (intent?.action != TEST_RATE_ACTION) return
        val trackId = intent.getStringExtra("track_id")
        val rating = intent.getStringExtra("rating")
        val token = SessionToken(this, ComponentName(this, PlayerService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        future.addListener({
            try {
                val c = future.get()
                testController = c
                val ok = Ratings.apply(this, c, trackId, rating)
                Log.i("GenesisPlayer", "TEST_RATE fired track=$trackId rating=$rating ok=$ok")
            } catch (e: Exception) {
                Log.e("GenesisPlayer", "TEST_RATE failed", e)
            } finally {
                MediaController.releaseFuture(future)
            }
        }, MoreExecutors.directExecutor())
    }

    /**
     * DEBUG-ONLY hook for the CI emulator gate: forces a re-check of the
     * remote catalog (same as the "Refresh music" button). The remote
     * look-and-feel path is gone, so only the catalog refreshes here.
     * Optional intent extra "catalog_url" redirects the fetch at a test
     * server (the gate uses a local one via adb reverse) — production code
     * paths are otherwise identical. Release builds ignore it entirely.
     */
    private fun handleTestRefresh(intent: Intent?) {
        if (!BuildConfig.DEBUG) return
        if (intent?.action != TEST_REFRESH_ACTION) return
        RemoteCatalog.checkForUpdates(this, intent.getStringExtra("catalog_url"))
        SnapshotStore.fetch(this, "test-refresh")
        Log.i("GenesisPlayer", "TEST_REFRESH fired")
    }

    /**
     * DEBUG-ONLY hook for the CI emulator gate: drives the service-side sleep
     * timer through the real custom-command path. Extras: "minutes" (int;
     * 0 = cancel), "end_of_queue" (boolean). Release builds ignore it.
     */
    private fun handleTestSleep(intent: Intent?) {
        if (!BuildConfig.DEBUG) return
        if (intent?.action != TEST_SLEEP_ACTION) return
        val minutes = intent.getIntExtra("minutes", 0)
        val endOfQueue = intent.getBooleanExtra("end_of_queue", false)
        sendTestCommand(
            PlayerService.ACTION_SLEEP_SET,
            Bundle().apply {
                putInt("minutes", minutes)
                putBoolean("end_of_queue", endOfQueue)
            },
            "TEST_SLEEP fired minutes=$minutes endOfQueue=$endOfQueue"
        )
    }

    /**
     * DEBUG-ONLY hook for the CI emulator gate: sets the crossfade seconds
     * through the real custom-command path. Extras: "seconds" (int, 0-8),
     * "prove" (boolean — seek the current track near its end so the
     * production watcher engages for real). Release builds ignore it.
     */
    private fun handleTestXfade(intent: Intent?) {
        if (!BuildConfig.DEBUG) return
        if (intent?.action != TEST_XFADE_ACTION) return
        val seconds = intent.getIntExtra("seconds", 0)
        val prove = intent.getBooleanExtra("prove", false)
        sendTestCommand(
            PlayerService.ACTION_XFADE_SET,
            Bundle().apply {
                putInt("seconds", seconds)
                putBoolean("prove", prove)
            },
            "TEST_XFADE fired seconds=$seconds prove=$prove"
        )
    }

    /**
     * DEBUG-ONLY hook for the CI emulator gate: dumps the live queue order to
     * logcat through the real custom-command path. Optional extras
     * "move_from" / "move_to" (ints) reorder via player.moveMediaItem first,
     * then dump — the gate proves reorder by diffing dumps. Release builds
     * ignore it.
     */
    private fun handleTestQueueDump(intent: Intent?) {
        if (!BuildConfig.DEBUG) return
        if (intent?.action != TEST_QUEUE_DUMP_ACTION) return
        val from = intent.getIntExtra("move_from", -1)
        val to = intent.getIntExtra("move_to", -1)
        sendTestCommand(
            PlayerService.ACTION_QUEUE_DUMP,
            Bundle().apply {
                putInt("move_from", from)
                putInt("move_to", to)
            },
            "TEST_QUEUE_DUMP fired move=$from->$to"
        )
    }

    /** Shared DEBUG-hook plumbing: build a controller, send one custom command. */
    private fun sendTestCommand(action: String, args: Bundle, logLine: String) {
        val token = SessionToken(this, ComponentName(this, PlayerService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        future.addListener({
            try {
                val c = future.get()
                testController = c // held until onDestroy so the command is delivered
                c.sendGenesis(action, args)
                Log.i("GenesisPlayer", logLine)
            } catch (e: Exception) {
                Log.e("GenesisPlayer", "$action failed", e)
            } finally {
                MediaController.releaseFuture(future)
            }
        }, MoreExecutors.directExecutor())
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
fun AurumApp() {
    val controller = rememberPlayerController()
    val pulse = rememberPlayerPulse(controller)
    // Golden Phase 1: the app opens on the Crate (tab 0).
    var tab by remember { mutableStateOf(0) }
    // BRKN wave 1: full-screen Now Playing overlay (mini-player tap opens it;
    // swipe down on it collapses back). The "Now Playing" tab still exists for
    // direct access and the CI gate's visualizer proof.
    var showNowPlaying by remember { mutableStateOf(false) }
    // Aurum Deep Space: FIXED tab bar — Crate / Playlists / Apollo / Now
    // Playing / EQ, always visible, fixed labels. No remote-driven sections.
    val tabs = listOf(
        Tab("crate", "Crate", Icons.Filled.LibraryMusic),
        Tab("playlists", "Playlists", Icons.AutoMirrored.Filled.QueueMusic),
        Tab("apollo", "Apollo", Icons.Filled.Psychology),
        Tab("nowplaying", "Now Playing", Icons.Filled.PlayCircle),
        Tab("eq", "EQ", Icons.Filled.GraphicEq)
    )
    val safeTab = tab.coerceIn(tabs.indices)
    if (safeTab != tab) tab = safeTab
    Box(Modifier.fillMaxSize()) {
        // Deep Space backdrop behind everything: SpaceBlack -> DeepIndigo.
        Box(
            Modifier
                .fillMaxSize()
                .background(DeepSpaceColors.backgroundBrush())
        ) {
            Scaffold(
                containerColor = Color.Transparent,
                bottomBar = {
                    // BRKN wave 1: persistent mini-player above the nav bar on
                    // every tab. Hidden until something has actually played.
                    Column {
                        MiniPlayerBar(controller = controller, onOpen = { showNowPlaying = true })
                        NavigationBar(containerColor = SurfaceDark) {
                            tabs.forEachIndexed { i, t ->
                                NavigationBarItem(
                                    selected = tab == i,
                                    onClick = { tab = i },
                                    icon = { Icon(t.icon, contentDescription = t.name) },
                                    label = { Text(t.name) },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = DeepSpaceColors.Gold,
                                        selectedTextColor = DeepSpaceColors.Gold,
                                        indicatorColor = CardDark
                                    )
                                )
                            }
                        }
                    }
                }
            ) { pad ->
                when (tabs[tab].id) {
                    "crate" -> CrateScreen(
                        controller, pulse,
                        onAskApollo = { tab = tabs.indexOfFirst { it.id == "apollo" } },
                        modifier = Modifier.padding(pad)
                    )
                    "playlists" -> PlaylistsScreen(controller, pulse, Modifier.padding(pad))
                    "nowplaying" -> DeepSpaceNowPlaying(controller, modifier = Modifier.padding(pad))
                    "apollo" -> ApolloScreen(controller, Modifier.padding(pad))
                    "eq" -> EqScreen(Modifier.padding(pad))
                }
            }
        }
        // Full-screen overlay above everything; swipe down collapses it.
        if (showNowPlaying) {
            Box(Modifier.fillMaxSize()) {
                DeepSpaceNowPlaying(
                    controller = controller,
                    onCollapse = { showNowPlaying = false }
                )
            }
        }
    }
}
