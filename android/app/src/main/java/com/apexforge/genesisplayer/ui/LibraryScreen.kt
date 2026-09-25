package com.apexforge.genesisplayer.ui

import android.os.Bundle
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.session.MediaController
import com.apexforge.genesisplayer.PlayerService
import com.apexforge.genesisplayer.data.ApolloDrops
import com.apexforge.genesisplayer.data.Library
import com.apexforge.genesisplayer.data.RemoteCatalog
import com.apexforge.genesisplayer.data.RemoteConfig
import com.apexforge.genesisplayer.sendGenesis

@Composable
fun LibraryScreen(controller: MediaController?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val labels = RemoteTheme.labels.value
    val note by RemoteCatalog.note
    var openPlaylist by remember { mutableStateOf<String?>(null) }
    LazyColumn(modifier = modifier.fillMaxSize().padding(16.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text(labels.libraryTitle, style = MaterialTheme.typography.headlineMedium, color = PhoenixGold)
                    Text(
                        labels.librarySubtitle.replace("{count}", Library.tracks.size.toString()),
                        color = TextDim, fontSize = 13.sp
                    )
                }
                TextButton(onClick = {
                    // Force re-check of the music catalog, the look-and-feel
                    // config, AND the Fresh Signals drops.
                    RemoteCatalog.checkForUpdates(context)
                    RemoteConfig.checkForUpdates(context)
                    ApolloDrops.poll(context)
                }) {
                    Text(labels.refreshLabel, color = EmberOrange, fontSize = 13.sp)
                }
            }
            // Subtle sync note; only ever set when a version actually advanced.
            note?.let {
                Text(it, color = PhoenixGold, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
            }
            Spacer(Modifier.height(12.dp))
            // SHUFFLE EVERYTHING (Phase 1): one-tap global shuffle across the
            // whole catalog. Dislikes excluded; first audio stays fast.
            Card(
                modifier = Modifier.fillMaxWidth()
                    .clickable {
                        controller?.sendGenesis(PlayerService.ACTION_SHUFFLE_ALL)
                    },
                colors = CardDefaults.cardColors(
                    containerColor = EmberOrange.copy(alpha = 0.16f)
                )
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("🔀 Shuffle everything", color = PhoenixGold, fontSize = 17.sp)
                        Text(
                            "${Library.tracks.size} tracks — dislikes skipped",
                            color = TextDim, fontSize = 13.sp
                        )
                    }
                    Text("▶", color = EmberOrange, fontSize = 20.sp)
                }
            }
            Spacer(Modifier.height(12.dp))
        }
        if (openPlaylist == null) {
            items(Library.playlists) { pl ->
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
                        .clickable { openPlaylist = pl.name },
                    colors = CardDefaults.cardColors(containerColor = CardDark)
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(pl.name, color = PhoenixGold, fontSize = 17.sp)
                            Text("${pl.trackIds.size} tracks", color = TextDim, fontSize = 13.sp)
                        }
                        Text("▶", color = EmberOrange, fontSize = 20.sp)
                    }
                }
            }
        } else {
            val pl = Library.playlists.find { it.name == openPlaylist }!!
            item {
                Text("‹ ${pl.name}", color = EmberOrange, fontSize = 15.sp,
                    modifier = Modifier.clickable { openPlaylist = null }.padding(vertical = 4.dp))
                Spacer(Modifier.height(8.dp))
            }
            items(pl.trackIds) { id ->
                val t = Library.track(id) ?: return@items
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 8.dp)
                        .clickable {
                            val args = Bundle().apply {
                                putStringArrayList("ids", ArrayList(pl.trackIds))
                                putInt("index", pl.trackIds.indexOf(id))
                            }
                            controller?.sendGenesis(PlayerService.ACTION_PLAY_IDS, args)
                        },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TrackArtwork(t.id, t.artworkUrl, Modifier.size(52.dp), "Art", 8.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(t.title, color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(t.artist, color = TextDim, fontSize = 13.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}
