package com.apexforge.genesisplayer.ui

import android.os.Bundle
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.session.MediaController
import com.apexforge.genesisplayer.PlayerService
import com.apexforge.genesisplayer.data.EnergyRules
import com.apexforge.genesisplayer.data.Library
import com.apexforge.genesisplayer.data.Ratings
import com.apexforge.genesisplayer.data.RatingsStore
import com.apexforge.genesisplayer.data.RemoteCatalog
import com.apexforge.genesisplayer.data.SnapshotStore
import com.apexforge.genesisplayer.sendGenesis

/** Which world is open: a catalog playlist, a snapshot playlist, or a legacy screen. */
private sealed interface World {
    data class Catalog(val name: String) : World
    data class Snapshot(val name: String) : World
    data object FullLibrary : World
    data object Drops : World
}

/**
 * Golden Player Phase 1 — Playlists (web "Pick a world"): numbered catalog
 * playlists, the read-only BRKN Vibes snapshot playlists, and a drill-in
 * detail per world. The legacy Library (recently played, snapshot favorites,
 * catalog search) and the Apollo drops screen stay reachable from here so
 * the new nav drops nothing the old tabs showed. User-made playlists are
 * Phase 2 — nothing here pretends otherwise.
 */
@Composable
fun PlaylistsScreen(controller: MediaController?, pulse: PlayerPulse, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf<World?>(null) }
    BackHandler(enabled = open != null) { open = null }
    LaunchedEffect(open) {
        Log.i("GenesisPlayer", "GoldenPlaylists: ${open?.let { describe(it) } ?: "index"}")
    }
    Box(modifier.fillMaxSize().background(Golden.bg)) {
        when (val w = open) {
            null -> PlaylistIndex { open = it }
            is World.Catalog -> CatalogDetail(controller, pulse, w.name) { open = null }
            is World.Snapshot -> SnapshotDetail(controller, pulse, w.name) { open = null }
            World.FullLibrary -> LegacyFrame("Full library", { open = null }) { m ->
                LibraryScreen(controller, m)
            }
            World.Drops -> LegacyFrame("Apollo drops", { open = null }) { m ->
                ForYouScreen(controller, m)
            }
        }
    }
}

private fun describe(w: World) = when (w) {
    is World.Catalog -> "open catalog '${w.name}'"
    is World.Snapshot -> "open snapshot '${w.name}'"
    World.FullLibrary -> "open full library"
    World.Drops -> "open drops"
}

@Composable
private fun PlaylistIndex(onOpen: (World) -> Unit) {
    val note by RemoteCatalog.note // recomposes when a catalog lands
    val playlists = remember(note) { Library.playlists }
    val snapshot = SnapshotStore.current()
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
        item(key = "head") {
            SectionHeading(
                "PLAYLISTS", "Pick a world",
                modifier = Modifier.padding(top = 24.dp), size = 42.sp
            )
            GoldenDivider()
        }
        itemsIndexed(playlists, key = { _, p -> "p:${p.name}" }) { i, p ->
            WorldRow(i + 1, p.name, "${p.trackIds.size} tracks") { onOpen(World.Catalog(p.name)) }
        }
        val snaps = snapshot?.playlists.orEmpty()
        if (snaps.isNotEmpty()) {
            item(key = "snap-head") {
                Kicker("BRKNVIBES SNAPSHOT · READ-ONLY", modifier = Modifier.padding(top = 24.dp, bottom = 6.dp))
                GoldenDivider()
            }
            itemsIndexed(snaps, key = { _, p -> "s:${p.name}" }) { i, p ->
                WorldRow(playlists.size + i + 1, p.name, "${p.tracks.size} tracks · snapshot") {
                    onOpen(World.Snapshot(p.name))
                }
            }
        }
        item(key = "more-head") {
            Kicker("MORE", modifier = Modifier.padding(top = 24.dp, bottom = 6.dp))
            GoldenDivider()
        }
        item(key = "more") {
            LinkRow("Full library", "Recently played, snapshot favorites, search") { onOpen(World.FullLibrary) }
            LinkRow("Apollo drops", "Fresh signals from the machine") { onOpen(World.Drops) }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun WorldRow(n: Int, name: String, sub: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 72.dp).clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            n.toString().padStart(2, '0'), color = Golden.ember,
            style = Golden.displayStyle(22.sp), modifier = Modifier.width(44.dp)
        )
        Column(Modifier.weight(1f)) {
            Text(name, color = Golden.text, style = Golden.displayStyle(24.sp),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sub, color = Golden.dim, fontSize = 12.sp)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Golden.dim)
    }
    GoldenDivider()
}

@Composable
private fun LinkRow(title: String, sub: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Golden.text, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Text(sub, color = Golden.dim, fontSize = 12.sp)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Golden.dim)
    }
    GoldenDivider()
}

@Composable
private fun DetailHeader(kicker: String, title: String, sub: String, onBack: () -> Unit, actions: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to playlists", tint = Golden.text)
        }
        SectionHeading(kicker, title, size = 36.sp)
        Text(sub, color = Golden.dim, fontSize = 12.sp, modifier = Modifier.padding(bottom = 12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 14.dp)) {
            actions()
        }
        GoldenDivider()
    }
}

@Composable
private fun CatalogDetail(controller: MediaController?, pulse: PlayerPulse, name: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val note by RemoteCatalog.note
    val ids = remember(note, name) {
        Library.playlists.find { it.name == name }?.trackIds.orEmpty().filter { Library.track(it) != null }
    }
    val energies = remember(note) { EnergyRules.forLibrary() }
    var ratingsTick by remember { mutableIntStateOf(0) }
    val liked = remember(ratingsTick) { RatingsStore.likedIds(ctx) }
    var radioFor by remember { mutableStateOf<Pair<String, String>?>(null) }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
        item(key = "head") {
            DetailHeader("PLAYLIST", name, "${ids.size} tracks · energy read from catalog tags", onBack) {
                EmberButton("Play") { controller.playIds(ids, 0) }
                EmberButton("Shuffle") { shufflePlay(controller, ids) }
            }
        }
        if (ids.isEmpty()) {
            item(key = "empty") { EmptyWorld() }
        }
        items(ids, key = { "t:$it" }) { id ->
            val t = Library.track(id) ?: return@items
            TrackRow(
                title = t.title, artist = t.artist,
                native = t.soundcloudUrl.isEmpty(),
                energy = energies[id],
                playing = pulse.currentId == id && pulse.isPlaying,
                loved = id in liked,
                onPlay = { controller.tapTrack(pulse, ids, id) },
                onLongPress = { radioFor = id to t.title },
                onLove = {
                    Ratings.apply(ctx, controller, id, if (id in liked) null else "like")
                    ratingsTick++
                },
                onPlayNext = { controller.playNext(ctx, id, t.title) }
            )
        }
        item(key = "tail") { Spacer(Modifier.height(24.dp)) }
    }
    radioFor?.let { (id, title) ->
        RadioDialog(title, onStart = {
            controller?.sendGenesis(PlayerService.ACTION_MORE_LIKE_THIS, Bundle().apply { putString("id", id) })
            radioFor = null
        }, onDismiss = { radioFor = null })
    }
}

/** Shuffle a catalog playlist through the production PLAY_IDS path + Media3 shuffle mode. */
private fun shufflePlay(controller: MediaController?, ids: List<String>) {
    val c = controller ?: return
    if (ids.isEmpty()) return
    c.playIds(ids, ids.indices.random())
    c.shuffleModeEnabled = true
}

@Composable
private fun SnapshotDetail(controller: MediaController?, pulse: PlayerPulse, name: String, onBack: () -> Unit) {
    val snapshot = SnapshotStore.current()
    val list = snapshot?.playlists?.find { it.name == name }?.tracks.orEmpty()
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
        item(key = "head") {
            DetailHeader("BRKNVIBES SNAPSHOT · READ-ONLY", name, "${list.size} tracks · from the machine", onBack) {
                EmberButton("Play") { if (list.isNotEmpty()) controller.playSnapshot(list, 0) }
            }
        }
        if (list.isEmpty()) {
            item(key = "empty") { EmptyWorld() }
        }
        itemsIndexed(list, key = { i, t -> "s:$i:${t.id}" }) { i, t ->
            TrackRow(
                title = t.title, artist = t.artist, native = null, energy = null,
                playing = pulse.currentId == t.id && pulse.isPlaying, loved = null,
                onPlay = { controller.playSnapshot(list, i) }
            )
        }
        item(key = "tail") { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun EmptyWorld() {
    Column(Modifier.padding(vertical = 32.dp, horizontal = 8.dp)) {
        Text("Nothing in this world yet.", color = Golden.text, fontWeight = FontWeight.Bold)
        Text("Refresh drops from the Crate to re-check the catalog.", color = Golden.dim, fontSize = 13.sp)
    }
}

/** Back-bar wrapper for the legacy screens reachable from Playlists. */
@Composable
private fun LegacyFrame(title: String, onBack: () -> Unit, content: @Composable (Modifier) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to playlists", tint = Golden.text)
            }
            Text(title, color = Golden.text, style = Golden.displayStyle(22.sp))
        }
        content(Modifier.weight(1f))
    }
}
