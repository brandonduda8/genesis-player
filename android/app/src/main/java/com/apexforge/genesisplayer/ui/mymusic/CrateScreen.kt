package com.apexforge.genesisplayer.ui.mymusic

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.apexforge.genesisplayer.data.Library
import com.apexforge.genesisplayer.data.mymusic.MyMusic
import com.apexforge.genesisplayer.data.mymusic.crate.MatchKind
import com.apexforge.genesisplayer.data.mymusic.crate.OfflineCrate

private val CGold = Color(0xFFFFB067)
private val CDim = Color(0xFF8A8AA0)
private val CCard = Color(0xFF17171F)
private val Ok = Color(0xFF5BD18A)

/**
 * The Offline Crate. Every row is a song from the owner's own history. Sections stay separate so nothing is passed off
 * as something else: exact recordings, alternate versions, downloadable, offline, unavailable.
 */
@Composable
fun CrateScreen(onPlayAurum: (List<String>, Int) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val v1 = MyMusic.version.intValue
    val v2 = OfflineCrate.version.intValue
    val progress by OfflineCrate.progress
    val busy by OfflineCrate.downloading
    val model = MyMusic.model ?: run { Box(modifier.fillMaxSize()) { Text("Import your history first", color = CDim) }; return }
    var section by remember { mutableIntStateOf(0) }
    val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) MyMusic.scanLocal(context) }
    LaunchedEffect(Unit) { MyMusic.scanLocal(context) }

    val ranked = remember(v1, v2) { model.songs.values.sortedByDescending { it.heat }.map { it.key } }
    val m = OfflineCrate.matches
    val exact = remember(v1, v2) { ranked.filter { m[it]?.kind == MatchKind.EXACT } }
    val alt = remember(v1, v2) { ranked.filter { m[it]?.kind == MatchKind.ALTERNATE } }
    val unavailable = remember(v1, v2) { ranked.filter { m[it]?.kind == MatchKind.UNAVAILABLE } }
    val downloadable = remember(v1, v2) { ranked.filter { m[it]?.downloadable == true } }
    val offline = remember(v1, v2) { ranked.filter { OfflineCrate.isOffline(context, it) } }
    val phone = MyMusic.localTracks
    val unchecked = ranked.size - m.size

    Column(modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Text("Offline Crate", style = MaterialTheme.typography.headlineSmall, color = CGold, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 12.dp))
        Text("Your songs, matched only to the same artist on Audius and the Internet Archive. Offline holds only music " +
            "you own or are allowed to download.", color = CDim, style = MaterialTheme.typography.bodySmall)
        Card(colors = CardDefaults.cardColors(containerColor = CCard), modifier = Modifier.padding(vertical = 10.dp)) {
            Column(Modifier.padding(12.dp)) {
                Text("Checked ${OfflineCrate.checkedArtists.size} of ${model.artists.size} artists · ${m.size} of ${ranked.size} songs",
                    color = Color.White)
                Text("Exact ${exact.size} · Alternate ${alt.size} · Downloadable ${downloadable.size} · Offline ${offline.size + phone.size} · Unavailable ${unavailable.size}" +
                    if (unchecked > 0) " · Not checked yet $unchecked" else "", color = CDim, style = MaterialTheme.typography.bodySmall)
                progress?.let { Text(it, color = CGold, style = MaterialTheme.typography.bodySmall) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                    Button(onClick = { OfflineCrate.matchLibrary(context) }, enabled = progress == null || progress!!.startsWith("Stopped")) {
                        Text(if (OfflineCrate.checkedArtists.isEmpty()) "Match my library" else "Continue matching")
                    }
                    OutlinedButton(onClick = { OfflineCrate.download(context, downloadable) },
                        enabled = downloadable.any { !OfflineCrate.isOffline(context, it) } && busy.isEmpty()) {
                        Text("Download permitted (${downloadable.count { !OfflineCrate.isOffline(context, it) }})")
                    }
                }
            }
        }
        val labels = listOf("Exact", "Alternate", "Downloadable", "Offline", "Unavailable")
        ScrollableTabRow(selectedTabIndex = section, edgePadding = 0.dp, containerColor = Color.Transparent) {
            labels.forEachIndexed { i, l -> Tab(selected = section == i, onClick = { section = i }, text = { Text(l) }) }
        }
        when (section) {
            0 -> CrateList(exact, "No exact recordings found yet.", action = null) { list, i ->
                onPlayAurum(list.map { "crate:$it" }, i) }
            1 -> CrateList(alt, "No alternate versions found.", action = null) { list, i -> onPlayAurum(list.map { "alt:$it" }, i) }
            2 -> CrateList(downloadable, "Nothing downloadable yet: artists choose whether to allow downloads.",
                action = { k ->
                    when {
                        OfflineCrate.isOffline(context, k) -> Icon(Icons.Filled.CheckCircle, "Saved offline", tint = Ok)
                        k in busy -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        else -> IconButton(onClick = { OfflineCrate.download(context, listOf(k)) }) {
                            Icon(Icons.Filled.Download, "Download", tint = CGold) }
                    }
                }) { list, i -> onPlayAurum(list.map { OfflineCrate.aurumTrackId(context, it) ?: "crate:$it" }, i) }
            3 -> Column(Modifier.fillMaxSize()) {
                if (phone.isEmpty()) TextButton(onClick = { ask.launch(perm) }) { Text("Also show music files already on this phone") }
                LazyColumn(Modifier.fillMaxSize()) {
                    itemsIndexed(offline, key = { _, k -> "o$k" }) { i, k ->
                        CrateRow(k, "Saved offline", action = {
                            IconButton(onClick = { OfflineCrate.removeOffline(context, k) }) { Icon(Icons.Filled.Delete, "Remove", tint = CDim) }
                        }) { onPlayAurum(offline.map { "offline:$it" }, i) }
                    }
                    itemsIndexed(phone, key = { _, t -> t.id }) { i, t ->
                        Row(Modifier.fillMaxWidth().clickable { onPlayAurum(phone.map { it.id }, i) }.padding(vertical = 8.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(t.title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${t.artist} · file on this phone", color = CDim, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    if (offline.isEmpty() && phone.isEmpty()) item { Text("Nothing offline yet.", color = CDim, modifier = Modifier.padding(8.dp)) }
                }
            }
            4 -> CrateList(unavailable, "Nothing marked unavailable yet.", action = null) { list, i -> YtQueue.play(list, i) }
        }
    }
}

@Composable
private fun CrateList(keys: List<String>, empty: String, action: (@Composable (String) -> Unit)?, onPlay: (List<String>, Int) -> Unit) {
    if (keys.isEmpty()) { Text(empty, color = CDim, modifier = Modifier.padding(12.dp)); return }
    LazyColumn(Modifier.fillMaxSize()) {
        itemsIndexed(keys, key = { _, k -> k }) { i, k ->
            val rowAction: (@Composable () -> Unit)? = if (action != null) { { action(k) } } else null
            CrateRow(k, OfflineCrate.matches[k]?.note ?: "", action = rowAction) { onPlay(keys, i) }
        }
    }
}

@Composable
private fun CrateRow(songKey: String, note: String, action: (@Composable () -> Unit)?, onClick: () -> Unit) {
    val s = MyMusic.model?.songs?.get(songKey) ?: return
    val c = OfflineCrate.matches[songKey]?.candidate
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        AsyncImage(model = c?.artworkUrl?.ifEmpty { null } ?: Library.track(songKey)?.artworkUrl, contentDescription = null,
            contentScale = ContentScale.Crop, modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)).background(CCard))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(s.title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${s.artist} · ${s.plays} plays", color = CDim, style = MaterialTheme.typography.bodySmall, maxLines = 1)
            if (note.isNotEmpty()) Text(note, color = CGold, style = MaterialTheme.typography.labelSmall, maxLines = 2)
        }
        action?.invoke()
    }
}
