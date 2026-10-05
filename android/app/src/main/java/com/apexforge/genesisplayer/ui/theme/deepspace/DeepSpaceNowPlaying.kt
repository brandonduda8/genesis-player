package com.apexforge.genesisplayer.ui.theme.deepspace

import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.outlined.ThumbDown
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import com.apexforge.genesisplayer.PlayerService
import com.apexforge.genesisplayer.ui.QueueSheet
import com.apexforge.genesisplayer.ui.TrackArtwork
import kotlinx.coroutines.delay

private fun fmt(ms: Long): String {
    if (ms < 0) return "0:00"
    val s = (ms / 1000).toInt()
    return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
}

/** Send a custom command to the service. Mirrors MainActivity.sendGenesis. */
private fun sendCmd(controller: MediaController?, action: String, args: Bundle = Bundle.EMPTY) {
    controller?.sendCustomCommand(SessionCommand(action, Bundle.EMPTY), args)
}

/**
 * Deep Space Now Playing screen (W3 — assembly).
 *
 * Layers: [DeepSpaceSky] full-bleed (art-reactive nebula keyed by media id) →
 * 45% black scrim → content column (planet artwork, title/artist, progress,
 * cockpit controls, like/dislike, queue/sleep/settings).
 *
 * Playback state is polled every 500ms from the MediaController (same pattern
 * as ui/NowPlayingScreen); the theme firewall holds — reads via the
 * controller and custom session commands only.
 */
@Composable
fun DeepSpaceNowPlaying(
    controller: MediaController?,
    onCollapse: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var isPlaying by remember { mutableStateOf(false) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var title by remember { mutableStateOf("Nothing playing") }
    var artist by remember { mutableStateOf("Pick something from the Library") }
    var albumTitle by remember { mutableStateOf("") }
    var artworkUri by remember { mutableStateOf("") }
    var mediaId by remember { mutableStateOf<String?>(null) }
    var shuffle by remember { mutableStateOf(false) }
    var repeat by remember { mutableStateOf(Player.REPEAT_MODE_OFF) }
    var rating by remember { mutableStateOf<String?>(null) }
    var scrubTo by remember { mutableStateOf<Long?>(null) }

    var showQueue by remember { mutableStateOf(false) }
    var showSleep by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var sleepArmed by remember { mutableStateOf(false) }
    var nebula by remember { mutableStateOf<ImageBitmap?>(null) }
    var lastTouchNanos by remember { mutableLongStateOf(System.nanoTime()) }

    val voyage = remember { VoyageController() }
    var lastAlbumKey by remember { mutableStateOf<String?>(null) }

    // 500ms state poll, copied from NowPlayingScreen. Also drives the voyage:
    // album change → Launch warp; same-album track change → drift.
    LaunchedEffect(controller) {
        while (true) {
            val c = controller
            if (c != null) {
                isPlaying = c.isPlaying
                durationMs = c.duration.coerceAtLeast(0)
                val md = c.mediaMetadata
                title = (md.title ?: "Nothing playing").toString()
                artist = (md.artist ?: "Pick something from the Library").toString()
                albumTitle = (md.albumTitle ?: "").toString()
                artworkUri = md.artworkUri?.toString() ?: ""
                shuffle = c.shuffleModeEnabled
                repeat = c.repeatMode
                if (scrubTo == null) positionMs = c.currentPosition.coerceAtLeast(0)
                val mid = c.currentMediaItem?.mediaId
                if (mid != mediaId) {
                    mediaId = mid
                    rating = mid?.let { ThemeRatings.get(ctx, it) }
                    val albumKey = "$albumTitle — $artist"
                    if (albumKey != lastAlbumKey) {
                        lastAlbumKey = albumKey
                        voyage.onAlbumStart()
                    } else {
                        voyage.onTrackChange(sameAlbum = true)
                    }
                }
            }
            delay(500)
        }
    }

    // Art-reactive nebula, refreshed when the track or its artwork changes.
    val artworkKey = mediaId ?: "none"
    LaunchedEffect(artworkKey, artworkUri) {
        PaletteNebula.nebulaFor(
            artworkKey = artworkKey,
            artworkUrl = artworkUri.ifEmpty { null },
            context = ctx,
            scope = scope
        ) { nebula = it }
    }

    val quiet = rememberQuietProfile(
        isPlaying = isPlaying,
        lastTouchNanos = lastTouchNanos,
        sleepArmed = sleepArmed
    )
    val reduceMotion = rememberReduceMotion(ctx)

    fun toggleRating(which: String) {
        val id = mediaId ?: return
        val next = if (rating == which) null else which
        if (ThemeRatings.apply(ctx, controller, id, artist, title, next)) rating = next
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                // Passive touch tracking for the 30s drive-idle rule: observes
                // every touch but consumes nothing, so all clicks still land.
                // awaitEachGesture's block runs in an AwaitPointerEventScope.
                awaitEachGesture {
                    do {
                        val event = awaitPointerEvent()
                        if (event.changes.any { it.pressed }) {
                            lastTouchNanos = System.nanoTime()
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
    ) {
        DeepSpaceSky(
            nebula = nebula,
            transition = voyage.transition,
            quiet = quiet,
            isPlaying = isPlaying,
            reduceMotion = reduceMotion,
            modifier = Modifier.fillMaxSize()
        )
        // Mandatory scrim: 45% black over the sky for 4.5:1 text contrast.
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)))

        Column(
            // Scrolls on short screens so queue/sleep/settings stay reachable.
            modifier = Modifier.fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (onCollapse != null) {
                IconButton(
                    onClick = onCollapse,
                    modifier = Modifier.align(Alignment.Start)
                ) {
                    Icon(
                        Icons.Filled.KeyboardArrowDown, "Collapse",
                        tint = DeepSpaceColors.StarlightDim, modifier = Modifier.size(28.dp)
                    )
                }
            } else {
                Spacer(Modifier.height(16.dp))
            }
            Spacer(Modifier.height(4.dp))

            // Planet artwork: circular, 2dp gold ring.
            Box(
                Modifier.fillMaxWidth(0.55f).aspectRatio(1f)
                    .border(BorderStroke(2.dp, DeepSpaceColors.Gold), CircleShape)
                    .padding(2.dp)
                    .clip(CircleShape)
            ) {
                TrackArtwork(
                    trackId = mediaId ?: "none",
                    artworkUrl = artworkUri,
                    modifier = Modifier.fillMaxSize(),
                    contentDescription = "Artwork",
                    corner = 1000.dp // huge radius ≈ circle inside the outer clip
                )
            }
            Spacer(Modifier.height(12.dp))

            Text(
                title, style = MaterialTheme.typography.headlineSmall,
                color = DeepSpaceColors.Gold,
                maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center
            )
            Text(
                artist, style = MaterialTheme.typography.bodyLarge,
                color = DeepSpaceColors.StarlightDim,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))

            // Like / dislike — exact command format from NowPlayingScreen.
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { toggleRating("dislike") }) {
                    Icon(
                        if (rating == "dislike") Icons.Filled.ThumbDown else Icons.Outlined.ThumbDown,
                        contentDescription = "Dislike",
                        tint = if (rating == "dislike") DeepSpaceColors.Gold else DeepSpaceColors.StarlightDim,
                        modifier = Modifier.size(28.dp).alpha(if (rating == "dislike") 1f else 0.55f)
                    )
                }
                Spacer(Modifier.width(40.dp))
                IconButton(onClick = { toggleRating("like") }) {
                    Icon(
                        if (rating == "like") Icons.Filled.ThumbUp else Icons.Outlined.ThumbUp,
                        contentDescription = "Like",
                        tint = if (rating == "like") DeepSpaceColors.Gold else DeepSpaceColors.StarlightDim,
                        modifier = Modifier.size(28.dp).alpha(if (rating == "like") 1f else 0.55f)
                    )
                }
            }

            // Slim gold progress slider (keeps accessibility) + time labels.
            Slider(
                value = (scrubTo ?: positionMs).toFloat(),
                onValueChange = { scrubTo = it.toLong() },
                onValueChangeFinished = {
                    scrubTo?.let { controller?.seekTo(it) }
                    scrubTo = null
                },
                valueRange = 0f..durationMs.coerceAtLeast(1).toFloat(),
                colors = SliderDefaults.colors(
                    thumbColor = DeepSpaceColors.Gold,
                    activeTrackColor = DeepSpaceColors.Gold,
                    inactiveTrackColor = DeepSpaceColors.StarlightDim.copy(alpha = 0.35f)
                )
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(fmt(scrubTo ?: positionMs), color = DeepSpaceColors.StarlightDim, fontSize = 12.sp)
                Text(fmt(durationMs), color = DeepSpaceColors.StarlightDim, fontSize = 12.sp)
            }
            Spacer(Modifier.height(8.dp))

            // Cockpit control row: one dim translucent pill, gold icons.
            Row(
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(32.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = {
                    controller?.shuffleModeEnabled = !(controller?.shuffleModeEnabled ?: false)
                }) {
                    Icon(Icons.Filled.Shuffle, "Shuffle",
                        tint = if (shuffle) DeepSpaceColors.Gold else DeepSpaceColors.StarlightDim,
                        modifier = Modifier.size(24.dp))
                }
                IconButton(onClick = {
                    voyage.onManualSkip()
                    sendCmd(controller, PlayerService.ACTION_SKIP_PREV)
                }) {
                    Icon(Icons.Filled.SkipPrevious, "Previous",
                        tint = DeepSpaceColors.Gold, modifier = Modifier.size(38.dp))
                }
                IconButton(
                    onClick = { if (isPlaying) controller?.pause() else controller?.play() },
                    modifier = Modifier.size(64.dp)
                ) {
                    Icon(
                        if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        if (isPlaying) "Pause" else "Play",
                        tint = DeepSpaceColors.Gold, modifier = Modifier.size(54.dp)
                    )
                }
                IconButton(onClick = {
                    voyage.onManualSkip()
                    sendCmd(controller, PlayerService.ACTION_SKIP_NEXT)
                }) {
                    Icon(Icons.Filled.SkipNext, "Next",
                        tint = DeepSpaceColors.Gold, modifier = Modifier.size(38.dp))
                }
                IconButton(onClick = {
                    controller?.repeatMode = when (repeat) {
                        Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                        Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                        else -> Player.REPEAT_MODE_OFF
                    }
                }) {
                    Icon(
                        if (repeat == Player.REPEAT_MODE_ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                        "Repeat",
                        tint = if (repeat == Player.REPEAT_MODE_OFF) DeepSpaceColors.StarlightDim
                        else DeepSpaceColors.Gold,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
            Spacer(Modifier.height(4.dp))

            // Queue / sleep / settings row.
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = { showQueue = true }) {
                    Icon(Icons.Filled.QueueMusic, "Queue", tint = DeepSpaceColors.Gold,
                        modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Queue", color = DeepSpaceColors.Gold, fontSize = 14.sp)
                }
                Spacer(Modifier.width(16.dp))
                TextButton(onClick = { showSleep = true }) {
                    Icon(Icons.Filled.Timer, "Sleep timer",
                        tint = if (sleepArmed) DeepSpaceColors.Gold else DeepSpaceColors.StarlightDim,
                        modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (sleepArmed) "Sleep ✓" else "Sleep",
                        color = if (sleepArmed) DeepSpaceColors.Gold else DeepSpaceColors.Starlight,
                        fontSize = 14.sp
                    )
                }
                Spacer(Modifier.width(16.dp))
                TextButton(onClick = { showSettings = true }) {
                    Icon(Icons.Filled.Settings, "Playback settings", tint = DeepSpaceColors.Gold,
                        modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Settings", color = DeepSpaceColors.Gold, fontSize = 14.sp)
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    if (showQueue) {
        QueueSheet(controller = controller, onDismiss = { showQueue = false })
    }
    if (showSleep) {
        DeepSpaceSleepDialog(
            controller = controller,
            onArmedChange = { sleepArmed = it },
            onDismiss = { showSleep = false }
        )
    }
    if (showSettings) {
        DeepSpaceSettingsDialog(controller = controller, onDismiss = { showSettings = false })
    }
}

/**
 * Compact sleep dialog: presets 5/15/30/60/off + an end-of-queue toggle,
 * all through ACTION_SLEEP_SET (minutes + end_of_queue bundle keys).
 */
@Composable
private fun DeepSpaceSleepDialog(
    controller: MediaController?,
    onArmedChange: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val ctx = LocalContext.current
    var endOfQueue by remember { mutableStateOf(false) }

    fun set(minutes: Int) {
        sendCmd(
            controller,
            PlayerService.ACTION_SLEEP_SET,
            Bundle().apply {
                putInt("minutes", minutes)
                putBoolean("end_of_queue", endOfQueue)
            }
        )
        onArmedChange(minutes > 0)
        val label = when {
            minutes == 0 && endOfQueue -> "end of queue"
            endOfQueue -> "$minutes min + end of queue"
            else -> "$minutes min"
        }
        Toast.makeText(ctx, "Sleep timer: $label", Toast.LENGTH_SHORT).show()
        onDismiss()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        containerColor = DeepSpaceColors.DeepIndigo,
        title = { Text("Sleep timer", color = DeepSpaceColors.Gold) },
        text = {
            Column {
                listOf(5, 15, 30, 60).forEach { m ->
                    TextButton(onClick = { set(m) }, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "$m minutes", color = DeepSpaceColors.Starlight, fontSize = 15.sp,
                            modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Start
                        )
                    }
                }
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "End of queue", color = DeepSpaceColors.Starlight, fontSize = 15.sp,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = endOfQueue,
                        onCheckedChange = { endOfQueue = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = DeepSpaceColors.Gold,
                            checkedTrackColor = DeepSpaceColors.Gold.copy(alpha = 0.5f)
                        )
                    )
                }
                Text(
                    "Runs out the current track list when a preset is set.",
                    color = DeepSpaceColors.StarlightDim, fontSize = 12.sp,
                    modifier = Modifier.padding(start = 12.dp, bottom = 4.dp)
                )
                TextButton(
                    onClick = {
                        sendCmd(
                            controller,
                            PlayerService.ACTION_SLEEP_SET,
                            Bundle().apply { putInt("minutes", 0) }
                        )
                        onArmedChange(false)
                        Toast.makeText(ctx, "Sleep timer off", Toast.LENGTH_SHORT).show()
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "Off", color = DeepSpaceColors.Gold, fontSize = 15.sp,
                        modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Start
                    )
                }
            }
        }
    )
}

/**
 * Compact settings dialog: crossfade 0–8s (ACTION_XFADE_SET) + the in-app
 * reduce-motion toggle (setReduceMotionPref).
 */
@Composable
private fun DeepSpaceSettingsDialog(
    controller: MediaController?,
    onDismiss: () -> Unit
) {
    val ctx = LocalContext.current
    val prefs = remember {
        ctx.getSharedPreferences("genesis_playback", Context.MODE_PRIVATE)
    }
    var xfade by remember { mutableStateOf(prefs.getFloat("xfade_s", 0f)) }
    val reduceMotionDefault = rememberReduceMotion(ctx)
    var reduceMotion by remember(reduceMotionDefault) { mutableStateOf(reduceMotionDefault) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        containerColor = DeepSpaceColors.DeepIndigo,
        title = { Text("Playback settings", color = DeepSpaceColors.Gold) },
        text = {
            Column {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Crossfade", color = DeepSpaceColors.Starlight, fontSize = 15.sp,
                        modifier = Modifier.weight(1f)
                    )
                    Text("${xfade.toInt()}s", color = DeepSpaceColors.Gold, fontSize = 15.sp)
                }
                Slider(
                    value = xfade,
                    onValueChange = { xfade = it },
                    onValueChangeFinished = {
                        sendCmd(
                            controller,
                            PlayerService.ACTION_XFADE_SET,
                            Bundle().apply { putInt("seconds", xfade.toInt()) }
                        )
                    },
                    valueRange = 0f..8f,
                    steps = 7,
                    colors = SliderDefaults.colors(
                        thumbColor = DeepSpaceColors.Gold,
                        activeTrackColor = DeepSpaceColors.Gold
                    )
                )
                Text(
                    "Overlaps the next track over the last seconds of this one.",
                    color = DeepSpaceColors.StarlightDim, fontSize = 12.sp
                )
                Spacer(Modifier.height(16.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Reduce motion", color = DeepSpaceColors.Starlight, fontSize = 15.sp)
                        Text(
                            "Still sky: no star drift, no rings, no warp.",
                            color = DeepSpaceColors.StarlightDim, fontSize = 12.sp
                        )
                    }
                    Switch(
                        checked = reduceMotion,
                        onCheckedChange = {
                            reduceMotion = it
                            setReduceMotionPref(ctx, it)
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = DeepSpaceColors.Gold,
                            checkedTrackColor = DeepSpaceColors.Gold.copy(alpha = 0.5f)
                        )
                    )
                }
            }
        }
    )
}
