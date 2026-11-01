package com.apexforge.genesisplayer.ui.mymusic

import android.Manifest
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
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
import com.apexforge.genesisplayer.data.Track
import com.apexforge.genesisplayer.data.mymusic.MyMusic
import com.apexforge.genesisplayer.data.mymusic.crate.MatchKind
import com.apexforge.genesisplayer.data.mymusic.crate.OfflineCrate
import com.apexforge.genesisplayer.data.mymusic.crate.SongStatus
import java.util.concurrent.Executors

private val importer = Executors.newSingleThreadExecutor()

/**
 * The Crate: the owner's matched music vault. The Offline Sanctuary comes first (everything that plays with no internet),
 * then what can be saved, what streams online, other versions, and what only YouTube has. Each list is kept separate.
 */
@Composable
fun CrateScreen(onPlayAurum: (List<String>, Int) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val v1 = MyMusic.version.intValue
    val v2 = OfflineCrate.version.intValue
    val progress by OfflineCrate.progress
    val busy by OfflineCrate.downloading
    val online = rememberOnline()
    val model = MyMusic.model ?: run { Box(modifier.fillMaxSize()) { Text("Import your history first", color = Aurum.Dim) }; return }
    var section by remember { mutableIntStateOf(0) }
    var vaultOpen by remember { mutableStateOf(false) }
    val main = remember { Handler(Looper.getMainLooper()) }
    val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) MyMusic.scanLocal(context) }
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        importer.execute {
            val n = MyMusic.importAudio(context, uris)
            main.post { Toast.makeText(context, "$n file${if (n == 1) "" else "s"} added to your sanctuary", Toast.LENGTH_SHORT).show() }
        }
    }
    LaunchedEffect(Unit) { MyMusic.scanLocal(context) }

    val ranked = remember(v1, v2) { model.songs.values.sortedByDescending { it.heat }.map { it.key } }
    val status = remember(v1, v2) { ranked.associateWith { OfflineCrate.status(context, it) } }
    fun inStatus(s: SongStatus) = ranked.filter { status[it] == s }
    val saved = remember(v1, v2) { inStatus(SongStatus.OFFLINE) }
    val canSave = remember(v1, v2) { inStatus(SongStatus.DOWNLOADABLE) }
    val exactOnline = remember(v1, v2) { inStatus(SongStatus.ONLINE_EXACT) + canSave }
    val alt = remember(v1, v2) { inStatus(SongStatus.ALTERNATE) }
    val yt = remember(v1, v2) { inStatus(SongStatus.YOUTUBE) }
    val owned = remember(v1, v2) { MyMusic.ownedTracks(context) }
    val phone = MyMusic.localTracks
    val sanctuaryCount = saved.size + owned.size + phone.size

    Column(modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            BrandHeader("Offline Crate", "Your vault", Aurum.CRATE_LINE, Modifier.weight(1f))
            Pill(if (online) "ONLINE" else "OFFLINE", if (online) Aurum.Ok else Aurum.Warn)
        }
        if (!online) Text("${Aurum.OFFLINE_LINE} Your sanctuary still plays: $sanctuaryCount tracks.", color = Aurum.Warn,
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
        GoldCard(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
            Text("Checked ${OfflineCrate.checkedArtists.size} of ${model.artists.size} artists · ${OfflineCrate.matches.size} of ${ranked.size} songs",
                color = Aurum.Text, fontWeight = FontWeight.SemiBold)
            Text("Sanctuary $sanctuaryCount · Can save ${canSave.size} · Exact online ${exactOnline.size} · Alternate ${alt.size} · YouTube only ${yt.size}",
                color = Aurum.Dim, style = MaterialTheme.typography.bodySmall)
            progress?.let { Text(it, color = Aurum.Gold, style = MaterialTheme.typography.bodySmall) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                Button(onClick = { OfflineCrate.matchLibrary(context) }, enabled = online && (progress == null || progress!!.startsWith("Stopped")),
                    colors = ButtonDefaults.buttonColors(containerColor = Aurum.Gold, contentColor = Aurum.Obsidian)) {
                    Text(if (OfflineCrate.checkedArtists.isEmpty()) "Match my library" else "Continue matching")
                }
                OutlinedButton(onClick = { OfflineCrate.download(context, canSave) }, enabled = online && canSave.isNotEmpty() && busy.isEmpty()) {
                    Text("Save all (${canSave.size})")
                }
            }
        }
        com.apexforge.genesisplayer.ui.VaultBar { vaultOpen = true }
        if (vaultOpen) com.apexforge.genesisplayer.ui.VaultManagerDialog(onChanged = { OfflineCrate.publish() }) { vaultOpen = false }
        val labels = listOf("Sanctuary", "Can save", "Exact · online", "Alternate · online", "YouTube only")
        ScrollableTabRow(selectedTabIndex = section, edgePadding = 0.dp, containerColor = Color.Transparent, contentColor = Aurum.Gold) {
            labels.forEachIndexed { i, l -> Tab(selected = section == i, onClick = { section = i }, text = { Text(l) }) }
        }
        when (section) {
            0 -> LazyColumn(Modifier.fillMaxSize()) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                        AssistChip(onClick = { pickFiles.launch(arrayOf("audio/*")) }, label = { Text("Add music files") },
                            leadingIcon = { Icon(Icons.Filled.LibraryAdd, null, tint = Aurum.Gold) })
                        if (phone.isEmpty()) AssistChip(onClick = { ask.launch(perm) }, label = { Text("Show phone music") },
                            leadingIcon = { Icon(Icons.Filled.PhoneAndroid, null, tint = Aurum.Gold) })
                    }
                    Text("Everything here plays with no internet: music you own or were allowed to save.",
                        color = Aurum.Dim, style = MaterialTheme.typography.bodySmall)
                }
                if (saved.isNotEmpty()) item { Overline("Saved from your matches") }
                itemsIndexed(saved, key = { _, k -> "s$k" }) { i, k ->
                    SongVaultRow(k, SongStatus.OFFLINE, OfflineCrate.matches[k]?.note ?: "Saved offline", action = {
                        IconButton(onClick = { OfflineCrate.removeOffline(context, k) }) { Icon(Icons.Filled.Delete, "Remove", tint = Aurum.Dim) }
                    }) { onPlayAurum(saved.map { "offline:$it" }, i) }
                }
                if (owned.isNotEmpty()) item { Overline("Your imported files") }
                itemsIndexed(owned, key = { _, t -> t.id }) { i, t ->
                    FileRow(t, "Imported into Aurum", action = {
                        IconButton(onClick = { MyMusic.removeOwned(context, t.id) }) { Icon(Icons.Filled.Delete, "Remove", tint = Aurum.Dim) }
                    }) { onPlayAurum(owned.map { it.id }, i) }
                }
                if (phone.isNotEmpty()) item { Overline("Already on this phone") }
                itemsIndexed(phone, key = { _, t -> t.id }) { i, t -> FileRow(t, "File on this phone", action = null) { onPlayAurum(phone.map { it.id }, i) } }
                if (sanctuaryCount == 0) item {
                    Text("Your sanctuary is empty. Add music files you own, or save permitted tracks from \"Can save\".",
                        color = Aurum.Dim, modifier = Modifier.padding(vertical = 16.dp))
                }
            }
            1 -> VaultList(canSave, SongStatus.DOWNLOADABLE, "Nothing can be saved yet: artists choose whether to allow downloads.",
                action = { k ->
                    when {
                        k in busy -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = Aurum.Gold)
                        else -> IconButton(onClick = { OfflineCrate.download(context, listOf(k)) }, enabled = online) {
                            Icon(Icons.Filled.Download, "Save offline", tint = Aurum.Gold) }
                    }
                }) { list, i -> onPlayAurum(list.map { "crate:$it" }, i) }
            2 -> VaultList(exactOnline, SongStatus.ONLINE_EXACT, "No exact recordings found yet.", action = null) { list, i ->
                if (online) onPlayAurum(list.map { "crate:$it" }, i)
                else Toast.makeText(context, "Offline: exact streams need the internet. Your sanctuary still plays.", Toast.LENGTH_SHORT).show()
            }
            3 -> VaultList(alt, SongStatus.ALTERNATE, "No alternate versions found.", action = null) { list, i ->
                if (online) onPlayAurum(list.map { "alt:$it" }, i)
                else Toast.makeText(context, "Offline: alternate versions stream online only.", Toast.LENGTH_SHORT).show()
            }
            4 -> VaultList(yt, SongStatus.YOUTUBE, "Nothing is YouTube-only yet.", action = null) { list, i ->
                if (online) YtQueue.play(list, i)
                else Toast.makeText(context, "Offline: YouTube needs the internet.", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

@Composable
private fun VaultList(keys: List<String>, st: SongStatus, empty: String, action: (@Composable (String) -> Unit)?,
                      onPlay: (List<String>, Int) -> Unit) {
    if (keys.isEmpty()) { Text(empty, color = Aurum.Dim, modifier = Modifier.padding(12.dp)); return }
    LazyColumn(Modifier.fillMaxSize()) {
        itemsIndexed(keys, key = { _, k -> k }) { i, k ->
            val rowAction: (@Composable () -> Unit)? = if (action != null) { { action(k) } } else null
            SongVaultRow(k, st, OfflineCrate.matches[k]?.note ?: "", action = rowAction) { onPlay(keys, i) }
        }
    }
}

@Composable
private fun SongVaultRow(songKey: String, st: SongStatus, note: String, action: (@Composable () -> Unit)?, onClick: () -> Unit) {
    val s = MyMusic.model?.songs?.get(songKey) ?: return
    val c = OfflineCrate.matches[songKey]?.candidate
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        AsyncImage(model = c?.artworkUrl?.ifEmpty { null } ?: Library.track(songKey)?.artworkUrl, contentDescription = null,
            contentScale = ContentScale.Crop, modifier = Modifier.size(50.dp).clip(RoundedCornerShape(10.dp)).background(Aurum.Card))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(s.title, color = Aurum.Text, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
            Text("${s.artist} · ${s.plays} plays", color = Aurum.Dim, style = MaterialTheme.typography.bodySmall, maxLines = 1)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                Pill(st.label, st.color())
                if (note.isNotEmpty()) Text("  $note", color = Aurum.Bronze, style = MaterialTheme.typography.labelSmall, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
            }
        }
        action?.invoke()
    }
}

@Composable
private fun FileRow(t: Track, note: String, action: (@Composable () -> Unit)?, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(50.dp).clip(RoundedCornerShape(10.dp)).background(Aurum.goldBrush), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.MusicNote, null, tint = Aurum.Obsidian)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(t.title, color = Aurum.Text, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
            Text(t.artist, color = Aurum.Dim, style = MaterialTheme.typography.bodySmall, maxLines = 1)
            Row(Modifier.padding(top = 2.dp)) { Pill("OFFLINE", Aurum.Ok); Text("  $note", color = Aurum.Bronze, style = MaterialTheme.typography.labelSmall) }
        }
        action?.invoke()
    }
}
