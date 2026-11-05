package com.apexforge.genesisplayer.ui.mymusic

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.apexforge.genesisplayer.data.Library
import com.apexforge.genesisplayer.data.playlists.PlaylistPlayback
import com.apexforge.genesisplayer.data.playlists.Playlists
import com.apexforge.genesisplayer.data.playlists.UserPlaylist

/** Aurum 2.3: the owner's own playlists. Works offline, no AI; every action is local to this phone. */
@Composable
fun PlaylistsTab(modifier: Modifier = Modifier, onPlayAurum: (List<String>, Int) -> Unit) {
    val ctx = LocalContext.current
    val v = Playlists.version.intValue // read eagerly so this composable subscribes
    LaunchedEffect(Unit) { if (v == 0) Playlists.load(ctx) }
    var open by remember { mutableStateOf<String?>(null) }
    var naming by remember { mutableStateOf(false) }
    val exportAll = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        if (uri != null) runAction(ctx, "Playlists exported") { Playlists.exportTo(ctx, uri) }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) runAction(ctx, null) {
            val r = Playlists.importFrom(ctx, uri)
            Toast.makeText(ctx, "Imported ${r.playlists} playlist${if (r.playlists == 1) "" else "s"}: ${r.entries} songs" +
                (if (r.rematched > 0) ", ${r.rematched} matched by name" else "") +
                (if (r.unavailable > 0) ", ${r.unavailable} not in this library (kept)" else ""), Toast.LENGTH_LONG).show()
        }
    }
    open?.let { id ->
        val p = Playlists.book.get(id)
        if (p != null) { PlaylistDetail(p, modifier, onPlayAurum, onBack = { open = null }); return }
        open = null
    }
    Column(modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        BrandHeader("Playlists", "Your playlists", "Made by you. Played anywhere, even offline.", Modifier.padding(top = 12.dp))
        Playlists.readOnlyReason?.let {
            Text("Read-only: $it. Update Aurum to edit them.", color = Aurum.Warn, style = MaterialTheme.typography.bodySmall)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
            Button(onClick = { naming = true }, enabled = Playlists.readOnlyReason == null,
                colors = ButtonDefaults.buttonColors(containerColor = Aurum.Gold, contentColor = Aurum.Obsidian)) {
                Icon(Icons.Filled.Add, null); Text(" New playlist")
            }
            OutlinedButton(onClick = { importer.launch(arrayOf("application/json", "*/*")) }) { Text("Import") }
            if (Playlists.book.playlists.isNotEmpty())
                OutlinedButton(onClick = { exportAll.launch("aurum-playlists.json") }) { Text("Back up") }
        }
        val all = remember(v) { Playlists.book.playlists.sortedByDescending { it.updatedMs } }
        if (all.isEmpty()) {
            Text("No playlists yet. Tap New playlist, or long-press any song in the Archive and choose Add to playlist. " +
                "You can also save an Apollo mix as a playlist.", color = Aurum.Dim, modifier = Modifier.padding(vertical = 16.dp))
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(all, key = { it.id }) { p ->
                val offline = remember(v, p.id) { p.entries.count { e -> Playlists.aurumId(ctx, e.id)?.let(Playlists::worksOffline) == true } }
                val art = p.entries.firstNotNullOfOrNull { Library.track(it.id)?.artworkUrl?.takeIf { a -> a.isNotEmpty() } }
                Row(Modifier.fillMaxWidth().clickable { open = p.id }.padding(vertical = 10.dp)
                        .semantics { contentDescription = "${p.name}, ${p.entries.size} songs" },
                    verticalAlignment = Alignment.CenterVertically) {
                    Cover(art, 56)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.name, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${p.entries.size} songs" + if (offline > 0) " · $offline offline" else "", color = Aurum.Dim,
                            style = MaterialTheme.typography.bodySmall)
                    }
                    Icon(Icons.Filled.ChevronRight, null, tint = Aurum.Dim)
                }
            }
        }
    }
    if (naming) NameDialog("New playlist", "", onDismiss = { naming = false }) { name ->
        runAction(ctx, null) { open = Playlists.create(ctx, name).id }
        naming = false
    }
}

@Composable
private fun Cover(art: String?, size: Int) {
    Box(Modifier.size(size.dp).clip(RoundedCornerShape(10.dp)).background(Aurum.Card), contentAlignment = Alignment.Center) {
        if (art != null) AsyncImage(model = art, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Icon(Icons.Filled.QueueMusic, null, tint = Aurum.Gold)
    }
}

@Composable
private fun PlaylistDetail(p: UserPlaylist, modifier: Modifier, onPlayAurum: (List<String>, Int) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val online = rememberOnline()
    val v = Playlists.version.intValue
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val editable = Playlists.readOnlyReason == null
    val exportOne = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        if (uri != null) runAction(ctx, "\"${p.name}\" exported") { Playlists.exportTo(ctx, uri, listOf(p.id)) }
    }
    fun play(start: Int, shuffle: Boolean) {
        val plan = Playlists.plan(ctx, p, start, online, shuffle)
        when (plan.route) {
            PlaylistPlayback.Route.AURUM -> {
                onPlayAurum(plan.ids, plan.index)
                if (plan.skipped > 0) Toast.makeText(ctx, "Playing ${plan.ids.size} in Aurum; ${plan.skipped} " +
                    (if (online) "play through YouTube only" else "need the internet"), Toast.LENGTH_SHORT).show()
            }
            PlaylistPlayback.Route.YOUTUBE -> YtQueue.play(plan.ids, plan.index)
            PlaylistPlayback.Route.NONE -> Toast.makeText(ctx, plan.reason, Toast.LENGTH_SHORT).show()
        }
    }
    Column(modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "Back to playlists", tint = Aurum.Gold) }
            Column(Modifier.weight(1f)) {
                Text(p.name, color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${p.entries.size} songs", color = Aurum.Dim, style = MaterialTheme.typography.bodySmall)
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Playlist options", tint = Aurum.Dim) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Rename") }, enabled = editable, onClick = { menu = false; renaming = true })
                    DropdownMenuItem(text = { Text("Export this playlist") }, onClick = {
                        menu = false; exportOne.launch(p.name.replace(Regex("[^A-Za-z0-9 _-]"), "_") + ".aurum.json") })
                    DropdownMenuItem(text = { Text("Delete") }, enabled = editable, onClick = { menu = false; deleting = true })
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
            Button(onClick = { play(0, false) }, enabled = p.entries.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(containerColor = Aurum.Gold, contentColor = Aurum.Obsidian)) {
                Icon(Icons.Filled.PlayArrow, null); Text(" Play")
            }
            OutlinedButton(onClick = { play((p.entries.indices).random(), true) }, enabled = p.entries.isNotEmpty()) {
                Icon(Icons.Filled.Shuffle, null); Text(" Shuffle")
            }
        }
        if (p.entries.isEmpty()) Text("Empty. Long-press songs in the Archive or the Crate to add them here.", color = Aurum.Dim)
        LazyColumn(Modifier.fillMaxSize()) {
            itemsIndexed(p.entries, key = { i, e -> "$i:${e.id}" }) { i, e ->
                val t = Library.track(e.id)
                val aurum = remember(v, e.id) { Playlists.aurumId(ctx, e.id) }
                val badge = when {
                    aurum != null && Playlists.worksOffline(aurum) -> "OFFLINE"
                    aurum != null -> "IN AURUM"
                    Playlists.onYouTube(e.id) -> "YOUTUBE"
                    else -> "UNAVAILABLE"
                }
                Row(Modifier.fillMaxWidth().clickable { play(i, false) }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Cover(t?.artworkUrl?.takeIf { it.isNotEmpty() }, 44)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(t?.title ?: e.title, color = if (badge == "UNAVAILABLE") Aurum.Dim else Color.White,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${t?.artist ?: e.artist} · $badge", color = Aurum.Dim, style = MaterialTheme.typography.bodySmall,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (editable) {
                        IconButton(onClick = { runAction(ctx, null) { Playlists.move(ctx, p.id, i, i - 1) } }, enabled = i > 0) {
                            Icon(Icons.Filled.KeyboardArrowUp, "Move ${e.title} up", tint = Aurum.Dim) }
                        IconButton(onClick = { runAction(ctx, null) { Playlists.move(ctx, p.id, i, i + 1) } },
                            enabled = i < p.entries.size - 1) { Icon(Icons.Filled.KeyboardArrowDown, "Move ${e.title} down", tint = Aurum.Dim) }
                        IconButton(onClick = { runAction(ctx, null) { Playlists.removeAt(ctx, p.id, i) } }) {
                            Icon(Icons.Filled.Close, "Remove ${e.title}", tint = Aurum.Dim) }
                    }
                }
            }
        }
    }
    if (renaming) NameDialog("Rename playlist", p.name, onDismiss = { renaming = false }) { name ->
        runAction(ctx, null) { Playlists.rename(ctx, p.id, name) }; renaming = false
    }
    if (deleting) AlertDialog(
        onDismissRequest = { deleting = false },
        title = { Text("Delete \"${p.name}\"?") },
        text = { Text("The playlist goes; the songs stay in your library and Crate.") },
        confirmButton = { TextButton(onClick = { runAction(ctx, null) { Playlists.delete(ctx, p.id) }; deleting = false; onBack() }) {
            Text("Delete", color = Aurum.Ember) } },
        dismissButton = { TextButton(onClick = { deleting = false }) { Text("Cancel") } })
}

@Composable
fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) },
        text = { OutlinedTextField(value = name, onValueChange = { name = it.take(80) }, singleLine = true, label = { Text("Name") }) },
        confirmButton = { TextButton(onClick = { onDone(name) }, enabled = name.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

/** "Add to playlist" for one or more library tracks; lists playlists plus a new-playlist field. */
@Composable
fun AddToPlaylistDialog(trackIds: List<String>, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val v = Playlists.version.intValue
    LaunchedEffect(Unit) { if (v == 0) Playlists.load(ctx) }
    var naming by remember { mutableStateOf(false) }
    fun addTo(id: String, name: String) = runAction(ctx, null) {
        val n = Playlists.add(ctx, id, trackIds)
        Toast.makeText(ctx, if (n == 0) "Already in \"$name\"" else "Added $n to \"$name\"", Toast.LENGTH_SHORT).show()
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Add to playlist") },
        text = {
            Column {
                TextButton(onClick = { naming = true }, enabled = Playlists.readOnlyReason == null) {
                    Icon(Icons.Filled.Add, null); Text(" New playlist") }
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(Playlists.book.playlists.sortedByDescending { it.updatedMs }, key = { it.id }) { p ->
                        Text("${p.name}  (${p.entries.size})", color = Color.White, modifier = Modifier.fillMaxWidth()
                            .clickable { addTo(p.id, p.name); onDismiss() }.padding(vertical = 12.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } })
    if (naming) NameDialog("New playlist", "", onDismiss = { naming = false }) { name ->
        runAction(ctx, null) { val p = Playlists.create(ctx, name); addTo(p.id, p.name) }
        naming = false; onDismiss()
    }
}

/** Run a playlist action; failures (read-only file, I/O) become a short message instead of a crash. */
private inline fun runAction(ctx: android.content.Context, ok: String?, block: () -> Unit) {
    try { block(); ok?.let { Toast.makeText(ctx, it, Toast.LENGTH_SHORT).show() } }
    catch (e: Exception) { Toast.makeText(ctx, e.message ?: "Couldn't save the playlist", Toast.LENGTH_LONG).show() }
}
