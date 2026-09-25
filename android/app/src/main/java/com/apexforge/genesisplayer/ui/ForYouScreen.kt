package com.apexforge.genesisplayer.ui

import android.os.Bundle
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.session.MediaController
import com.apexforge.genesisplayer.PlayerService
import com.apexforge.genesisplayer.data.ApolloDrops
import com.apexforge.genesisplayer.data.HistoryStore
import com.apexforge.genesisplayer.data.Library
import com.apexforge.genesisplayer.sendGenesis

/**
 * Apollo v1: a bundled For You shelf. Every suggestion carries a plain-language
 * why-reason tied to Brandon's taste anchors and library. On-device
 * play/skip/completion counters are recorded (HistoryStore); the full
 * learning re-rank loop is v2.
 */
@Composable
fun ForYouScreen(controller: MediaController?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val app = context.applicationContext
    val drops = ApolloDrops.drops.value
    val limited = ApolloDrops.limited.value
    LazyColumn(modifier = modifier.fillMaxSize().padding(16.dp)) {
        item {
            Text("For You", style = MaterialTheme.typography.headlineMedium, color = PhoenixGold)
            Text("Apollo's picks — unheard sounds in your lane",
                color = TextDim, fontSize = 13.sp)
            Spacer(Modifier.height(12.dp))
        }
        // Fresh Signals (APOLLO-LIVE Flow A): the drop card shows ONLY when
        // there are drops. Empty means no card, no noise. Limited mode (public
        // fallback feed) says so honestly.
        if (drops.isNotEmpty()) {
            item {
                Text("Fresh Signals", color = EmberOrange, fontSize = 15.sp)
                if (limited) {
                    Text("Limited — from the public suggestions feed",
                        color = TextDim, fontSize = 12.sp)
                }
                Spacer(Modifier.height(6.dp))
            }
            items(drops, key = { it.id }) { d ->
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    colors = CardDefaults.cardColors(containerColor = CardDark)
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text("✦ ${d.artist} — ${d.title}",
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 15.sp, maxLines = 2,
                            overflow = TextOverflow.Ellipsis)
                        if (d.why.isNotEmpty()) {
                            Spacer(Modifier.height(4.dp))
                            Text("✦ ${d.why}", color = PhoenixGold, fontSize = 12.sp,
                                maxLines = 3, overflow = TextOverflow.Ellipsis)
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(onClick = {
                                ApolloDrops.reject(app, d.id)
                                android.widget.Toast.makeText(app, "Passed — not for you.",
                                    android.widget.Toast.LENGTH_SHORT).show()
                            }) {
                                Text("Reject", color = TextDim, fontSize = 13.sp)
                            }
                            TextButton(onClick = {
                                ApolloDrops.approve(app, d.id)
                                android.widget.Toast.makeText(app, "Awaiting publish…",
                                    android.widget.Toast.LENGTH_SHORT).show()
                            }) {
                                Text("Approve", color = EmberOrange, fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(8.dp)) }
        }
        items(Library.forYou) { item ->
            val plays = HistoryStore.plays(context, item.id)
            Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
                    .clickable {
                        val args = Bundle().apply { putString("id", item.id) }
                        controller?.sendGenesis(PlayerService.ACTION_PLAY_FORYOU, args)
                    },
                colors = CardDefaults.cardColors(containerColor = CardDark)
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    TrackArtwork(item.id, item.artworkUrl, Modifier.size(64.dp), "Art", 10.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(item.title, color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 15.sp)
                        Text(item.artist, color = TextDim, fontSize = 13.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(4.dp))
                        Text("✦ ${item.why}", color = PhoenixGold, fontSize = 12.sp,
                            maxLines = 3, overflow = TextOverflow.Ellipsis)
                        if (plays > 0) {
                            Text("Played $plays×", color = EmberOrange, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
        item {
            Spacer(Modifier.height(8.dp))
            Text("Apollo learns from every play, skip and repeat — on this device only.",
                color = TextDim, fontSize = 12.sp)
        }
    }
}
