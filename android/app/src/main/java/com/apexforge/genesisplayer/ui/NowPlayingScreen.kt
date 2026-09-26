package com.apexforge.genesisplayer.ui

import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import com.apexforge.genesisplayer.PlayerService
import com.apexforge.genesisplayer.data.ApolloDrops
import com.apexforge.genesisplayer.data.Library
import com.apexforge.genesisplayer.data.Ratings
import com.apexforge.genesisplayer.data.RatingsStore
import com.apexforge.genesisplayer.sendGenesis
import kotlinx.coroutines.delay

private fun fmt(ms: Long): String {
    if (ms < 0) return "0:00"
    val s = (ms / 1000).toInt()
    return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
}

/**
 * BRKN Vibes wave 1: the immersive Now Playing screen.
 *
 * - Full-screen blurred artwork backdrop (RenderEffect blur on API 31+,
 *   dimmed gradient below).
 * - Audio-reactive ember visualizer strip (real Visualizer data; dim static
 *   art when paused or unavailable — never fake motion).
 * - Tap the artwork to flip to the vibe panel: REAL genre + the Apollo "why"
 *   note when the track id matches a suggestion; "No vibe notes yet."
 *   otherwise. Never invented lyrics or facts.
 * - Horizontal swipe on the artwork = next/previous (this REPLACES the old
 *   swipe = like/dislike; the Like/Dislike buttons stay and the gate checks
 *   they render).
 * - Swipe down = collapse to the mini-player (via [onCollapse]; null when
 *   rendered as the Now Playing tab, where there is nothing to collapse to).
 * - Queue button opens the queue sheet; timer button opens the sleep picker.
 */
@Composable
fun NowPlayingScreen(
    controller: MediaController?,
    modifier: Modifier = Modifier,
    onCollapse: (() -> Unit)? = null
) {
    var isPlaying by remember { mutableStateOf(false) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var title by remember { mutableStateOf("Nothing playing") }
    var artist by remember { mutableStateOf("Pick something from the Library") }
    var artwork by remember { mutableStateOf("") }
    var shuffle by remember { mutableStateOf(false) }
    var repeat by remember { mutableStateOf(Player.REPEAT_MODE_OFF) }
    var scrubTo by remember { mutableStateOf<Long?>(null) }
    var mediaId by remember { mutableStateOf<String?>(null) }
    var rating by remember { mutableStateOf<String?>(null) }
    var showVibe by remember(mediaId) { mutableStateOf(false) }
    var showQueue by remember { mutableStateOf(false) }
    var showSleep by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    val ctx = LocalContext.current

    LaunchedEffect(controller) {
        while (true) {
            val c = controller
            if (c != null) {
                isPlaying = c.isPlaying
                duration = c.duration.coerceAtLeast(0)
                val md = c.mediaMetadata
                title = (md.title ?: "Nothing playing").toString()
                artist = (md.artist ?: "Pick something from the Library").toString()
                artwork = md.artworkUri?.toString() ?: ""
                shuffle = c.shuffleModeEnabled
                repeat = c.repeatMode
                if (scrubTo == null) position = c.currentPosition.coerceAtLeast(0)
                val mid = c.currentMediaItem?.mediaId
                if (mid != mediaId) {
                    mediaId = mid
                    rating = mid?.let { RatingsStore.get(ctx, it) }
                }
            }
            delay(500)
        }
    }

    fun toggleRating(which: String) {
        val id = mediaId ?: return
        val next = if (rating == which) null else which
        if (Ratings.apply(ctx, controller, id, next)) rating = next
    }

    // Vibe data: REAL only. Genre from the catalog; the Apollo "why" when the
    // track id matches a live suggestion or a For You pick.
    val genre = mediaId?.let { Library.track(it)?.genre } ?: ""
    val whyNote = mediaId?.let { id ->
        ApolloDrops.current().find { it.id == id }?.why
            ?: Library.forYou.find { it.id == id }?.why
    }

    Box(
        modifier = modifier.fillMaxSize()
            .pointerInput(onCollapse) {
                var acc = 0f
                detectVerticalDragGestures(
                    onDragStart = { acc = 0f },
                    onVerticalDrag = { _, d -> acc += d },
                    onDragEnd = { if (acc > 200) onCollapse?.invoke() }
                )
            }
    ) {
        BlurredBackdrop(artworkUrl = artwork, trackId = mediaId ?: "none")
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top
        ) {
            // Collapse handle (also the swipe-down target hint).
            Text(
                "⌄",
                color = TextDim, fontSize = 18.sp,
                modifier = Modifier.padding(top = 2.dp)
            )
            Spacer(Modifier.height(2.dp))
            // Artwork / vibe flip. Tap flips; horizontal swipe skips.
            Box(
                modifier = Modifier.fillMaxWidth(0.68f).aspectRatio(1f)
                    .pointerInput(mediaId) {
                        detectTapGestures(onTap = { showVibe = !showVibe })
                    }
                    .pointerInput(mediaId) {
                        var acc = 0f
                        detectHorizontalDragGestures(
                            onDragStart = { acc = 0f },
                            onHorizontalDrag = { _, d -> acc += d },
                            onDragEnd = {
                                if (acc > 140) controller?.sendGenesis(PlayerService.ACTION_SKIP_PREV)
                                else if (acc < -140) controller?.sendGenesis(PlayerService.ACTION_SKIP_NEXT)
                            }
                        )
                    }
            ) {
                if (showVibe) {
                    VibePanel(
                        genre = genre,
                        why = whyNote,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    TrackArtwork(
                        trackId = mediaId ?: "none",
                        artworkUrl = artwork,
                        modifier = Modifier.fillMaxSize(),
                        contentDescription = "Artwork",
                        corner = 20.dp
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            // Audio-reactive ember visualizer: real FFT data while playing,
            // dim static art when paused/unavailable. Never fake motion.
            EmberVisualizer(
                isPlaying = isPlaying,
                modifier = Modifier.fillMaxWidth(0.9f).height(84.dp)
            )
            Spacer(Modifier.height(6.dp))
            Text(
                title, style = MaterialTheme.typography.headlineSmall, color = PhoenixGold,
                maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center
            )
            Text(
                artist, style = MaterialTheme.typography.bodyLarge, color = TextDim,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            // Apollo taste: like / dislike. Tapping toggles; filled = rated.
            // (The gate asserts these two buttons render.)
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { toggleRating("dislike") }) {
                    Icon(
                        if (rating == "dislike") Icons.Filled.ThumbDown else Icons.Outlined.ThumbDown,
                        contentDescription = "Dislike",
                        tint = if (rating == "dislike") EmberOrange else TextDim,
                        modifier = Modifier.size(30.dp).alpha(if (rating == "dislike") 1f else 0.55f)
                    )
                }
                Spacer(Modifier.width(40.dp))
                IconButton(onClick = { toggleRating("like") }) {
                    Icon(
                        if (rating == "like") Icons.Filled.ThumbUp else Icons.Outlined.ThumbUp,
                        contentDescription = "Like",
                        tint = if (rating == "like") EmberOrange else TextDim,
                        modifier = Modifier.size(30.dp).alpha(if (rating == "like") 1f else 0.55f)
                    )
                }
            }
            // MORE LIKE THIS (Phase 1): builds a queue from the current track's
            // artist/genre lane + on-device taste vectors. Catalog ids only,
            // never dislikes — QueuePlanner.moreLikeThis.
            TextButton(onClick = {
                val id = mediaId ?: return@TextButton
                controller?.sendGenesis(
                    PlayerService.ACTION_MORE_LIKE_THIS,
                    Bundle().apply { putString("id", id) }
                )
            }) {
                Text("✦ More like this", color = PhoenixGold, fontSize = 14.sp)
            }
            Slider(
                value = (scrubTo ?: position).toFloat(),
                onValueChange = { scrubTo = it.toLong() },
                onValueChangeFinished = {
                    scrubTo?.let { controller?.seekTo(it) }
                    scrubTo = null
                },
                valueRange = 0f..duration.coerceAtLeast(1).toFloat(),
                colors = SliderDefaults.colors(thumbColor = EmberOrange, activeTrackColor = EmberOrange)
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(fmt(position), color = TextDim, fontSize = 12.sp)
                Text(fmt(duration), color = TextDim, fontSize = 12.sp)
            }
            Spacer(Modifier.height(4.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = {
                    controller?.shuffleModeEnabled = !(controller?.shuffleModeEnabled ?: false)
                }) {
                    Icon(Icons.Filled.Shuffle, "Shuffle",
                        tint = if (shuffle) EmberOrange else TextDim, modifier = Modifier.size(26.dp))
                }
                IconButton(onClick = {
                    controller?.sendGenesis(PlayerService.ACTION_SKIP_PREV)
                }) {
                    Icon(Icons.Filled.SkipPrevious, "Previous",
                        tint = PhoenixGold, modifier = Modifier.size(40.dp))
                }
                IconButton(
                    onClick = { if (isPlaying) controller?.pause() else controller?.play() },
                    modifier = Modifier.size(68.dp)
                ) {
                    Icon(
                        if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        if (isPlaying) "Pause" else "Play",
                        tint = EmberOrange, modifier = Modifier.size(58.dp)
                    )
                }
                IconButton(onClick = {
                    controller?.sendGenesis(PlayerService.ACTION_SKIP_NEXT)
                }) {
                    Icon(Icons.Filled.SkipNext, "Next",
                        tint = PhoenixGold, modifier = Modifier.size(40.dp))
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
                        tint = if (repeat == Player.REPEAT_MODE_OFF) TextDim else EmberOrange,
                        modifier = Modifier.size(26.dp)
                    )
                }
            }
            // Wave 2: queue sheet + sleep timer + playback settings.
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = { showQueue = true }) {
                    Icon(Icons.Filled.QueueMusic, "Queue", tint = PhoenixGold,
                        modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Queue", color = PhoenixGold, fontSize = 14.sp)
                }
                Spacer(Modifier.width(16.dp))
                TextButton(onClick = { showSleep = true }) {
                    Icon(Icons.Filled.Timer, "Sleep timer", tint = PhoenixGold,
                        modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Sleep", color = PhoenixGold, fontSize = 14.sp)
                }
                Spacer(Modifier.width(16.dp))
                TextButton(onClick = { showSettings = true }) {
                    Icon(Icons.Filled.Settings, "Playback settings", tint = PhoenixGold,
                        modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Settings", color = PhoenixGold, fontSize = 14.sp)
                }
            }
        }
    }

    if (showQueue) {
        QueueSheet(controller = controller, onDismiss = { showQueue = false })
    }
    if (showSleep) {
        SleepPickerDialog(controller = controller, onDismiss = { showSleep = false })
    }
    if (showSettings) {
        PlaybackSettingsDialog(controller = controller, onDismiss = { showSettings = false })
    }
}

/**
 * Full-screen artwork backdrop: blurred on API 31+ via RenderEffect, a
 * dimmed (unblurred) layer below that, always finished with a dark gradient
 * so foreground text stays legible on the Ember palette.
 */
@Composable
private fun BlurredBackdrop(artworkUrl: String, trackId: String) {
    Box(Modifier.fillMaxSize()) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            TrackArtwork(
                trackId = trackId,
                artworkUrl = artworkUrl,
                modifier = Modifier.fillMaxSize()
                    .graphicsLayer {
                        renderEffect = RenderEffect.createBlurEffect(
                            56f, 56f, Shader.TileMode.CLAMP
                        )
                    }
                    .alpha(0.5f),
                contentDescription = null,
                corner = 0.dp
            )
        } else {
            // Graceful fallback below API 31: dimmed artwork, no blur.
            TrackArtwork(
                trackId = trackId,
                artworkUrl = artworkUrl,
                modifier = Modifier.fillMaxSize().alpha(0.35f),
                contentDescription = null,
                corner = 0.dp
            )
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    listOf(Color(0x660A0A0C), Color(0xCC0A0A0C), Color(0xF20A0A0C))
                )
            )
        )
    }
}

/**
 * The vibe panel: REAL data only — the catalog genre and Apollo's "why" note
 * when this track id matches a live suggestion or a For You pick. Otherwise
 * the honest "No vibe notes yet." Never invented lyrics or facts.
 */
@Composable
private fun VibePanel(genre: String, why: String?, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(Color(0xD9141417), RoundedCornerShape(20.dp))
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("VIBE", color = EmberOrange, fontSize = 12.sp)
        Spacer(Modifier.height(8.dp))
        Text(
            if (genre.isNotEmpty()) genre else "Unknown genre",
            color = PhoenixGold, fontSize = 18.sp, textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(10.dp))
        Text(
            if (!why.isNullOrEmpty()) "✦ $why" else "No vibe notes yet.",
            color = if (!why.isNullOrEmpty()) Color(0xFFF2EFE9) else TextDim,
            fontSize = 14.sp, textAlign = TextAlign.Center,
            maxLines = 6, overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(14.dp))
        Text("Tap to flip back", color = TextDim, fontSize = 11.sp)
    }
}

@Composable
private fun SleepPickerDialog(controller: MediaController?, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    fun set(minutes: Int, endOfQueue: Boolean = false) {
        controller?.sendGenesis(
            PlayerService.ACTION_SLEEP_SET,
            Bundle().apply {
                putInt("minutes", minutes)
                putBoolean("end_of_queue", endOfQueue)
            }
        )
        val label = if (endOfQueue) "end of queue" else "$minutes min"
        Toast.makeText(ctx, "Sleep timer: $label", Toast.LENGTH_SHORT).show()
        onDismiss()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        containerColor = SurfaceDark,
        title = { Text("Sleep timer", color = PhoenixGold) },
        text = {
            Column {
                listOf(15, 30, 45, 60).forEach { m ->
                    TextButton(onClick = { set(m) }, modifier = Modifier.fillMaxWidth()) {
                        Text("$m minutes", color = Color(0xFFF2EFE9), fontSize = 15.sp,
                            modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Start)
                    }
                }
                TextButton(onClick = { set(0, endOfQueue = true) }, modifier = Modifier.fillMaxWidth()) {
                    Text("End of queue", color = Color(0xFFF2EFE9), fontSize = 15.sp,
                        modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Start)
                }
                TextButton(
                    onClick = {
                        controller?.sendGenesis(
                            PlayerService.ACTION_SLEEP_SET,
                            Bundle().apply { putInt("minutes", 0) }
                        )
                        Toast.makeText(ctx, "Sleep timer off", Toast.LENGTH_SHORT).show()
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Cancel timer", color = EmberOrange, fontSize = 15.sp,
                        modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Start)
                }
            }
        }
    )
}

/**
 * BRKN Vibes wave 2: playback settings. Crossfade 0–8s and the autoplay
 * switch both persist to the same `genesis_playback` prefs the service reads
 * live (xfade_s / autoplay), via the production custom commands — the same
 * commands the DEBUG test intents drive. Nothing here is DEBUG-gated: these
 * are legitimate in-app settings and release builds must honor them.
 */
@Composable
private fun PlaybackSettingsDialog(controller: MediaController?, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember {
        ctx.getSharedPreferences("genesis_playback", android.content.Context.MODE_PRIVATE)
    }
    var xfade by remember { mutableStateOf(prefs.getFloat("xfade_s", 0f)) }
    var autoplay by remember { mutableStateOf(prefs.getBoolean("autoplay", true)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        containerColor = SurfaceDark,
        title = { Text("Playback settings", color = PhoenixGold) },
        text = {
            Column {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Crossfade", color = Color(0xFFF2EFE9), fontSize = 15.sp,
                        modifier = Modifier.weight(1f))
                    Text("${xfade.toInt()}s", color = EmberOrange, fontSize = 15.sp)
                }
                Slider(
                    value = xfade,
                    onValueChange = { xfade = it },
                    onValueChangeFinished = {
                        controller?.sendGenesis(
                            PlayerService.ACTION_XFADE_SET,
                            Bundle().apply { putInt("seconds", xfade.toInt()) }
                        )
                    },
                    valueRange = 0f..8f,
                    steps = 7,
                    colors = SliderDefaults.colors(
                        thumbColor = EmberOrange, activeTrackColor = EmberOrange
                    )
                )
                Text(
                    "Overlaps the next track over the last seconds of this one.",
                    color = TextDim, fontSize = 12.sp
                )
                Spacer(Modifier.height(16.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Autoplay", color = Color(0xFFF2EFE9), fontSize = 15.sp)
                        Text(
                            "When the queue ends, build more from your taste.",
                            color = TextDim, fontSize = 12.sp
                        )
                    }
                    Switch(
                        checked = autoplay,
                        onCheckedChange = {
                            autoplay = it
                            controller?.sendGenesis(
                                PlayerService.ACTION_AUTOPLAY_SET,
                                Bundle().apply { putBoolean("enabled", it) }
                            )
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = EmberOrange,
                            checkedTrackColor = EmberOrange.copy(alpha = 0.5f)
                        )
                    )
                }
            }
        }
    )
}
