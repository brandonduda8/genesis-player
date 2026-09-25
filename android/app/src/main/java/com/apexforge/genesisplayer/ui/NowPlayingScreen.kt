package com.apexforge.genesisplayer.ui

import android.os.Bundle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import coil.compose.AsyncImage
import com.apexforge.genesisplayer.PlayerService
import com.apexforge.genesisplayer.sendGenesis
import kotlinx.coroutines.delay

private fun fmt(ms: Long): String {
    if (ms < 0) return "0:00"
    val s = (ms / 1000).toInt()
    return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
}

@Composable
fun NowPlayingScreen(controller: MediaController?, modifier: Modifier = Modifier) {
    var isPlaying by remember { mutableStateOf(false) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var title by remember { mutableStateOf("Nothing playing") }
    var artist by remember { mutableStateOf("Pick something from the Library") }
    var artwork by remember { mutableStateOf("") }
    var shuffle by remember { mutableStateOf(false) }
    var repeat by remember { mutableStateOf(Player.REPEAT_MODE_OFF) }
    var scrubTo by remember { mutableStateOf<Long?>(null) }

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
            }
            delay(500)
        }
    }

    Column(
        modifier = modifier.fillMaxSize().padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top
    ) {
        Spacer(Modifier.height(8.dp))
        if (artwork.isNotEmpty()) {
            AsyncImage(
                model = artwork, contentDescription = "Artwork",
                modifier = Modifier.fillMaxWidth(0.85f).aspectRatio(1f)
                    .clip(RoundedCornerShape(20.dp)),
                contentScale = ContentScale.Crop
            )
        } else {
            EmberPlaceholder(Modifier.fillMaxWidth(0.85f).aspectRatio(1f))
        }
        Spacer(Modifier.height(20.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall, color = PhoenixGold,
            maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        Text(artist, style = MaterialTheme.typography.bodyLarge, color = TextDim,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(12.dp))
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
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = {
                controller?.shuffleModeEnabled = !(controller?.shuffleModeEnabled ?: false)
            }) {
                Icon(Icons.Filled.Shuffle, "Shuffle",
                    tint = if (shuffle) EmberOrange else TextDim, modifier = Modifier.size(28.dp))
            }
            IconButton(onClick = {
                controller?.sendGenesis(PlayerService.ACTION_SKIP_PREV)
            }) {
                Icon(Icons.Filled.SkipPrevious, "Previous",
                    tint = PhoenixGold, modifier = Modifier.size(44.dp))
            }
            IconButton(
                onClick = { if (isPlaying) controller?.pause() else controller?.play() },
                modifier = Modifier.size(76.dp)
            ) {
                Icon(
                    if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    if (isPlaying) "Pause" else "Play",
                    tint = EmberOrange, modifier = Modifier.size(64.dp)
                )
            }
            IconButton(onClick = {
                controller?.sendGenesis(PlayerService.ACTION_SKIP_NEXT)
            }) {
                Icon(Icons.Filled.SkipNext, "Next",
                    tint = PhoenixGold, modifier = Modifier.size(44.dp))
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
                    modifier = Modifier.size(28.dp)
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "Always on — keeps playing with the screen off",
            color = TextDim, fontSize = 12.sp, textAlign = TextAlign.Center
        )
    }
}
