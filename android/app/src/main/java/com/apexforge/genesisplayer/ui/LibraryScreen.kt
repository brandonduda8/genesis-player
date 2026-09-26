package com.apexforge.genesisplayer.ui

import android.os.Bundle
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.session.MediaController
import com.apexforge.genesisplayer.PlayerService
import com.apexforge.genesisplayer.data.ApolloDrops
import com.apexforge.genesisplayer.data.HistoryStore
import com.apexforge.genesisplayer.data.Library
import com.apexforge.genesisplayer.data.RemoteCatalog
import com.apexforge.genesisplayer.data.RemoteConfig
import com.apexforge.genesisplayer.data.SnapshotStore
import com.apexforge.genesisplayer.data.SnapshotTrack
import com.apexforge.genesisplayer.sendGenesis

/**
 * BRKN Vibes wave 3: the library gains
 * - real search over the catalog AND the machine snapshot,
 * - the BrknVibes snapshot section (playlists are read-only),
 * - Recently Played (device history + snapshot recent_plays),
 * - long-press any catalog track -> "Start radio" (ACTION_MORE_LIKE_THIS).
 *
 * Snapshot radio is honestly unavailable: moreLikeThis needs a catalog seed,
 * so snapshot tracks get a toast instead of a fake radio. Empty states are
 * honest — no cache + no route = "no snapshot yet", never invented content.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(controller: MediaController?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val labels = RemoteTheme.labels.value
    val note by RemoteCatalog.note
    var query by remember { mutableStateOf("") }
    var openPlaylist by remember { mutableStateOf<String?>(null) }
    var openSnapPlaylist by remember { mutableStateOf<String?>(null) }
    var radioFor by remember { mutableStateOf<Pair<String, String>?>(null) } // (id, title)
    val snapshot = SnapshotStore.current() // Compose state: re-renders on fetch

    val q = query.trim().lowercase()
    val catalogHits = remember(q) {
        if (q.isEmpty()) emptyList()
        else Library.tracks.filter {
            it.title.lowercase().contains(q) || it.artist.lowercase().contains(q)
        }
    }
    val snapTracks = remember(snapshot) {
        snapshot?.let { s -> s.playlists.flatMap { it.tracks } + s.favorites } ?: emptyList()
    }
    val snapHits = remember(q, snapshot) {
        if (q.isEmpty()) emptyList()
        else snapTracks.filter {
            it.title.lowercase().contains(q) || it.artist.lowercase().contains(q)
        }
    }
    val recents = remember { HistoryStore.recentPlays(context) }
    val snapRecents = snapshot?.recentPlays ?: emptyList()

    fun playSnapshot(list: List<SnapshotTrack>, index: Int) {
        controller?.sendGenesis(
            PlayerService.ACTION_PLAY_SNAPSHOT,
            Bundle().apply {
                putStringArrayList("ids", ArrayList(list.map { it.id }))
                putStringArrayList("titles", ArrayList(list.map { it.title }))
                putStringArrayList("artists", ArrayList(list.map { it.artist }))
                putStringArrayList("stream_urls", ArrayList(list.map { it.streamUrl }))
                putStringArrayList("artwork_urls", ArrayList(list.map { it.artworkUrl }))
                putStringArrayList("genres", ArrayList(list.map { it.genre }))
                putInt("index", index)
            }
        )
    }

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
                    // Force re-check: catalog, look-and-feel config, drops,
                    // AND the machine snapshot.
                    RemoteCatalog.checkForUpdates(context)
                    RemoteConfig.checkForUpdates(context)
                    ApolloDrops.poll(context)
                    SnapshotStore.fetch(context, "manual")
                }) {
                    Text(labels.refreshLabel, color = EmberOrange, fontSize = 13.sp)
                }
            }
            note?.let {
                Text(it, color = PhoenixGold, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
            }
            Spacer(Modifier.height(10.dp))
            TextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search tracks and artists", color = TextDim) },
                singleLine = true,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = CardDark,
                    unfocusedContainerColor = CardDark,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedIndicatorColor = EmberOrange,
                    unfocusedIndicatorColor = Color.Transparent
                ),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            if (q.isEmpty()) {
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
        }

        // ---- Search results ----
        if (q.isNotEmpty()) {
            if (catalogHits.isEmpty() && snapHits.isEmpty()) {
                item {
                    Text("No matches for \"$query\".", color = TextDim, fontSize = 14.sp,
                        modifier = Modifier.padding(vertical = 16.dp))
                }
            }
            if (catalogHits.isNotEmpty()) {
                item {
                    Text("Catalog", color = PhoenixGold, fontSize = 15.sp,
                        modifier = Modifier.padding(vertical = 8.dp))
                }
                items(catalogHits, key = { it.id }) { t ->
                    CatalogRow(
                        id = t.id, title = t.title, artist = t.artist, art = t.artworkUrl,
                        onTap = {
                            val ids = ArrayList(catalogHits.map { it.id })
                            controller?.sendGenesis(
                                PlayerService.ACTION_PLAY_IDS,
                                Bundle().apply {
                                    putStringArrayList("ids", ids)
                                    putInt("index", catalogHits.indexOf(t))
                                }
                            )
                        },
                        onLongPress = { radioFor = t.id to t.title }
                    )
                }
            }
            if (snapHits.isNotEmpty()) {
                item {
                    Text("BrknVibes snapshot", color = PhoenixGold, fontSize = 15.sp,
                        modifier = Modifier.padding(vertical = 8.dp))
                }
                items(snapHits, key = { "snap:${it.id}" }) { t ->
                    SnapshotRow(title = t.title, artist = t.artist, art = t.artworkUrl) {
                        playSnapshot(snapHits, snapHits.indexOf(t))
                    }
                }
            }
        }

        // ---- Recently played ----
        if (q.isEmpty() && openPlaylist == null && openSnapPlaylist == null) {
            val localRecents = recents.mapNotNull { r ->
                Library.track(r.trackId)?.let { t -> Triple(t.id, t.title, t.artist) to t.artworkUrl }
            }
            if (localRecents.isNotEmpty() || snapRecents.isNotEmpty()) {
                item {
                    Text("Recently played", color = PhoenixGold, fontSize = 16.sp,
                        modifier = Modifier.padding(vertical = 8.dp))
                }
                items(localRecents, key = { "r:${it.first.first}" }) { (idTitle, art) ->
                    val (id, title, artist) = idTitle
                    CatalogRow(id = id, title = title, artist = artist, art = art,
                        onTap = {
                            controller?.sendGenesis(
                                PlayerService.ACTION_PLAY_IDS,
                                Bundle().apply {
                                    putStringArrayList("ids", arrayListOf(id))
                                    putInt("index", 0)
                                }
                            )
                        },
                        onLongPress = { radioFor = id to title }
                    )
                }
                items(snapRecents, key = { "sr:${it.trackId}:${it.playedAt}" }) { r ->
                    SnapshotRow(title = r.title, artist = r.artist, art = "") {
                        // Snapshot recent plays may not be in the local list;
                        // play them as a one-off snapshot track when they carry
                        // an id, otherwise stay honest.
                        val match = snapTracks.find { it.id == r.trackId }
                        if (match != null && match.streamUrl.isNotEmpty()) {
                            playSnapshot(listOf(match), 0)
                        } else {
                            Toast.makeText(
                                context,
                                "That snapshot entry has no playable stream",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            }
        }

        // ---- BrknVibes snapshot section ----
        if (q.isEmpty() && openPlaylist == null && openSnapPlaylist == null) {
            item {
                Text("BrknVibes", color = PhoenixGold, fontSize = 16.sp,
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                if (snapshot == null) {
                    Text(
                        "No machine snapshot yet — connect to the tailnet and hit Refresh.",
                        color = TextDim, fontSize = 13.sp,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
            }
            snapshot?.let { snap ->
                if (snap.playlists.isEmpty() && snap.favorites.isEmpty()) {
                    item {
                        Text("Snapshot is empty.", color = TextDim, fontSize = 13.sp,
                            modifier = Modifier.padding(vertical = 8.dp))
                    }
                }
                items(snap.playlists, key = { "pl:${it.name}" }) { pl ->
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
                            .clickable { openSnapPlaylist = pl.name },
                        colors = CardDefaults.cardColors(containerColor = CardDark)
                    ) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(pl.name, color = PhoenixGold, fontSize = 17.sp)
                                Text("${pl.tracks.size} tracks · read-only",
                                    color = TextDim, fontSize = 13.sp)
                            }
                            Text("▶", color = EmberOrange, fontSize = 20.sp)
                        }
                    }
                }
                if (snap.favorites.isNotEmpty()) {
                    item {
                        Text("Snapshot favorites", color = TextDim, fontSize = 14.sp,
                            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
                    }
                    items(snap.favorites, key = { "fav:${it.id}" }) { t ->
                        SnapshotRow(title = t.title, artist = t.artist, art = t.artworkUrl) {
                            playSnapshot(snap.favorites, snap.favorites.indexOf(t))
                        }
                    }
                }
            }
        }

        // ---- Catalog playlists ----
        if (q.isEmpty() && openPlaylist == null && openSnapPlaylist == null) {
            item {
                Text("Playlists", color = PhoenixGold, fontSize = 16.sp,
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
            }
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
        }

        // ---- Open catalog playlist ----
        if (openPlaylist != null) {
            val pl = Library.playlists.find { it.name == openPlaylist }!!
            item {
                Text("‹ ${pl.name}", color = EmberOrange, fontSize = 15.sp,
                    modifier = Modifier.clickable { openPlaylist = null }.padding(vertical = 4.dp))
                Spacer(Modifier.height(8.dp))
            }
            items(pl.trackIds) { id ->
                val t = Library.track(id) ?: return@items
                CatalogRow(
                    id = t.id, title = t.title, artist = t.artist, art = t.artworkUrl,
                    onTap = {
                        val args = Bundle().apply {
                            putStringArrayList("ids", ArrayList(pl.trackIds))
                            putInt("index", pl.trackIds.indexOf(id))
                        }
                        controller?.sendGenesis(PlayerService.ACTION_PLAY_IDS, args)
                    },
                    onLongPress = { radioFor = t.id to t.title }
                )
            }
        }

        // ---- Open snapshot playlist (read-only) ----
        if (openSnapPlaylist != null) {
            val pl = snapshot?.playlists?.find { it.name == openSnapPlaylist }
            item {
                Text("‹ ${openSnapPlaylist}", color = EmberOrange, fontSize = 15.sp,
                    modifier = Modifier.clickable { openSnapPlaylist = null }.padding(vertical = 4.dp))
                Text("Read-only — curated on the machine.", color = TextDim, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
            }
            if (pl != null) {
                items(pl.tracks, key = { "spt:${it.id}" }) { t ->
                    SnapshotRow(title = t.title, artist = t.artist, art = t.artworkUrl) {
                        playSnapshot(pl.tracks, pl.tracks.indexOf(t))
                    }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }

    // Long-press -> Start radio (catalog ids only).
    radioFor?.let { (id, title) ->
        AlertDialog(
            onDismissRequest = { radioFor = null },
            confirmButton = {},
            containerColor = CardDark,
            title = { Text("Start radio", color = PhoenixGold) },
            text = {
                Column {
                    Text("Build a queue like \"$title\"?", color = Color.White, fontSize = 14.sp)
                    Spacer(Modifier.height(12.dp))
                    TextButton(
                        onClick = {
                            controller?.sendGenesis(
                                PlayerService.ACTION_MORE_LIKE_THIS,
                                Bundle().apply { putString("id", id) }
                            )
                            radioFor = null
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Start radio", color = EmberOrange, fontSize = 15.sp,
                            modifier = Modifier.fillMaxWidth())
                    }
                    TextButton(
                        onClick = { radioFor = null },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Cancel", color = TextDim, fontSize = 15.sp,
                            modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CatalogRow(
    id: String,
    title: String,
    artist: String,
    art: String,
    onTap: () -> Unit,
    onLongPress: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp)
            .combinedClickable(onClick = onTap, onLongClick = onLongPress),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TrackArtwork(id, art, Modifier.size(52.dp), "Art", 8.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(artist, color = TextDim, fontSize = 13.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun SnapshotRow(title: String, artist: String, art: String, onTap: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp).clickable(onClick = onTap),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TrackArtwork("snap:$title", art, Modifier.size(52.dp), "Art", 8.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(artist, color = TextDim, fontSize = 13.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text("⇄", color = TextDim, fontSize = 16.sp) // snapshot marker
    }
}
