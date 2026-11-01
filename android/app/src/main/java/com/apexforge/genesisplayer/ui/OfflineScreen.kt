package com.apexforge.genesisplayer.ui

import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.media3.session.MediaController
import com.apexforge.genesisplayer.PlayerService
import com.apexforge.genesisplayer.data.LocalAudioLibrary
import com.apexforge.genesisplayer.sendGenesis

/**
 * Offline shelf: actual audio files already on the listener's device.
 * Uses Android's file picker instead of requesting access to the whole phone.
 * These files are not copied, downloaded from YouTube, or sent to Genesis.
 */
@Composable
fun OfflineScreen(controller: MediaController?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var revision by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    val pickAudio = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            val added = LocalAudioLibrary.add(context, uris)
            revision++
            Toast.makeText(context, "Added $added offline songs", Toast.LENGTH_SHORT).show()
        }
    }
    val tracks = remember(revision) { LocalAudioLibrary.tracks(context) }
    val visible = remember(tracks, query) {
        tracks.filter { it.title.contains(query.trim(), ignoreCase = true) }
    }

    LazyColumn(modifier.fillMaxSize().background(Golden.bg).padding(horizontal = 16.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(top = 22.dp, bottom = 20.dp)) {
                Text("YOUR OFFLINE CRATE", color = Golden.ember, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Text("Your music. Anywhere.", color = Golden.text, fontWeight = FontWeight.Bold, fontSize = 29.sp)
                Text(
                    "Play files already saved on your phone without internet. " +
                        "Same Aurum playback engine, EQ, visualizer and lock-screen controls.",
                    color = Golden.dim, fontSize = 13.sp
                )
                Button(
                    onClick = { pickAudio.launch(arrayOf("audio/*")) },
                    colors = ButtonDefaults.buttonColors(containerColor = Golden.ember)
                ) {
                    Icon(Icons.Filled.LibraryMusic, contentDescription = null)
                    Text("  Add music from phone", color = Golden.bg)
                }
                Text(
                    "${tracks.size} local audio files. Files stay on your phone; " +
                        "removing or moving them may make them unavailable.",
                    color = Golden.dim, fontSize = 12.sp
                )
                if (tracks.isNotEmpty()) {
                    OutlinedButton(onClick = {
                        controller?.sendGenesis(
                            PlayerService.ACTION_PLAY_OFFLINE,
                            Bundle().apply {
                                putStringArrayList("uris", ArrayList(tracks.map { it.uri }))
                                putInt("index", 0)
                            }
                        )
                    }, enabled = controller != null) {
                        Text("Play offline crate", color = Golden.text)
                    }
                }
                TextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search offline files") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        if (visible.isEmpty()) {
            item {
                Text(
                    if (tracks.isEmpty()) "No local songs yet. Tap Add music from phone."
                    else "No offline matches.",
                    color = Golden.dim, modifier = Modifier.padding(vertical = 22.dp)
                )
            }
        }
        itemsIndexed(visible, key = { _, track -> track.uri }) { index, track ->
            Row(
                modifier = Modifier.fillMaxWidth()
                    .clickable(enabled = controller != null) {
                        controller?.sendGenesis(
                            PlayerService.ACTION_PLAY_OFFLINE,
                            Bundle().apply {
                                val start = (index - 100).coerceAtLeast(0)
                                putStringArrayList("uris", ArrayList(visible.drop(start).take(500).map { it.uri }))
                                putInt("index", index - start)
                            }
                        )
                    }
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = "Play", tint = Golden.ember)
                Column(Modifier.weight(1f).padding(start = 9.dp)) {
                    Text(track.title, color = Color.White, fontSize = 15.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("ON DEVICE · OFFLINE", color = Golden.dim, fontSize = 10.sp)
                }
                TextButton(onClick = {
                    LocalAudioLibrary.remove(context, track.uri)
                    revision++
                }) {
                    Text("Remove", color = Golden.dim, fontSize = 12.sp)
                }
            }
        }
        item {
            Spacer(Modifier.padding(bottom = 36.dp))
            Text(
                "Your YouTube playlists and history guide discovery when connected. " +
                    "YouTube videos are not stored for offline playback by Aurum.",
                color = Golden.dim, fontSize = 12.sp,
                modifier = Modifier.padding(bottom = 24.dp)
            )
        }
    }
}
