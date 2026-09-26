package com.apexforge.genesisplayer.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.session.MediaController
import com.apexforge.genesisplayer.PlayerService
import com.apexforge.genesisplayer.sendGenesis
import kotlinx.coroutines.delay

/**
 * BRKN Vibes wave 1: the persistent mini-player bar. Sits above the bottom
 * nav on every tab: artwork thumb, title/artist, play/pause, tap to open the
 * full Now Playing screen. Hidden until something has actually played (no
 * fake "now playing" before the first track).
 */
@Composable
fun MiniPlayerBar(controller: MediaController?, onOpen: () -> Unit) {
    var everPlayed by remember { mutableStateOf(false) }
    var isPlaying by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf("") }
    var artist by remember { mutableStateOf("") }
    var artwork by remember { mutableStateOf("") }
    var mediaId by remember { mutableStateOf("none") }

    LaunchedEffect(controller) {
        while (true) {
            val c = controller
            if (c != null) {
                val mid = c.currentMediaItem?.mediaId
                if (mid != null) {
                    everPlayed = true
                    mediaId = mid
                    val md = c.mediaMetadata
                    title = (md.title ?: "").toString()
                    artist = (md.artist ?: "").toString()
                    artwork = md.artworkUri?.toString() ?: ""
                }
                isPlaying = c.isPlaying
            }
            delay(500)
        }
    }

    if (!everPlayed) return

    Surface(
        color = CardDark,
        tonalElevation = 4.dp,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TrackArtwork(mediaId, artwork, Modifier.size(44.dp), "Artwork", 8.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title.ifEmpty { "Nothing playing" },
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Golden Phase 1: NATIVE / SOUNDCLOUD badge (hidden for
                    // snapshot ids, whose source the catalog can't vouch for).
                    SourceBadgeFor(mediaId.takeIf { it != "none" })
                    Spacer(Modifier.width(6.dp))
                    Text(
                        artist, color = TextDim,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp
                    )
                }
            }
            IconButton(onClick = { if (isPlaying) controller?.pause() else controller?.play() }) {
                Icon(
                    if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    if (isPlaying) "Pause" else "Play",
                    tint = EmberOrange, modifier = Modifier.size(30.dp)
                )
            }
            // Golden Phase 1: next (the web's × close is deliberately omitted —
            // it would stop playback, which this bar never does).
            IconButton(onClick = { controller?.sendGenesis(PlayerService.ACTION_SKIP_NEXT) }) {
                Icon(Icons.Filled.SkipNext, "Next track", tint = TextDim, modifier = Modifier.size(26.dp))
            }
        }
    }
}
