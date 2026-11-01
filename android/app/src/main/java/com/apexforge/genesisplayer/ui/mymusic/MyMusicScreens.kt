package com.apexforge.genesisplayer.ui.mymusic

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.apexforge.genesisplayer.data.Library
import com.apexforge.genesisplayer.data.mymusic.ApolloEngine
import com.apexforge.genesisplayer.data.mymusic.ImportAudit
import com.apexforge.genesisplayer.data.mymusic.MyMusic
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

private val Gold = Color(0xFFFFB067)
private val Dim = Color(0xFF8A8AA0)
private val CardBg = Color(0xFF17171F)
private val io = Executors.newSingleThreadExecutor()

private fun day(ms: Long) = if (ms <= 0) "?" else SimpleDateFormat("MMM d, yyyy", Locale.US).format(Date(ms))

/** Import (first run and "update"): the owner picks the Takeout .zip or watch-history.json; the audit is shown back. */
@Composable
fun ImportScreen(modifier: Modifier = Modifier, onDone: () -> Unit = {}) {
    val context = LocalContext.current
    val main = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    var busy by remember { mutableStateOf(false) }
    var audit by remember { mutableStateOf<ImportAudit?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true; error = null
        io.execute {
            try {
                val a = MyMusic.importUri(context, uri)
                main.post { audit = a; busy = false }
            } catch (e: Exception) {
                main.post { error = e.message ?: e.toString(); busy = false }
            }
        }
    }
    LazyColumn(modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Text("Aurum Pulse", style = MaterialTheme.typography.headlineLarge, color = Gold, fontWeight = FontWeight.Bold)
            Text("Built from YOUR YouTube listening. No catalog, no filler: every song here is one you actually played.",
                color = Color.White, style = MaterialTheme.typography.bodyLarge)
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = CardBg)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Bring your data", color = Color.White, fontWeight = FontWeight.Bold)
                    Text("1. takeout.google.com → select \"YouTube and YouTube Music\" → All YouTube data included: keep " +
                        "history and playlists → History format: JSON.", color = Dim)
                    Text("2. Download the .zip to this phone.", color = Dim)
                    Text("3. Tap below and pick the .zip (or just watch-history.json). It's read on this phone only and " +
                        "never uploaded.", color = Dim)
                    Button(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !busy, modifier = Modifier.padding(top = 8.dp)) {
                        Icon(Icons.Filled.FileOpen, null); Spacer(Modifier.width(8.dp))
                        Text(if (busy) "Reading your history…" else "Pick my YouTube Takeout")
                    }
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        }
        error?.let { item { Text(it, color = Color(0xFFFF6B6B)) } }
        audit?.let { a -> item { AuditCard(a) } ; item { Button(onClick = onDone) { Text("Open my music") } } }
    }
}

@Composable
fun AuditCard(a: ImportAudit) {
    Card(colors = CardDefaults.cardColors(containerColor = CardBg)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Exactly what was imported", color = Gold, fontWeight = FontWeight.Bold)
            Text("Files: ${a.files.joinToString { it.substringAfterLast('/') }}", color = Dim)
            Text("History entries read: ${a.watchEntries} (${a.musicAppEntries} from YouTube Music)", color = Color.White)
            Text("Music plays kept: ${a.keptPlays}, from ${day(a.firstMs)} to ${day(a.lastMs)}", color = Color.White)
            Text("  ${a.keptViaMusicApp} from the YouTube Music app, ${a.keptPlays - a.keptViaMusicApp} music videos on YouTube", color = Dim)
            a.skipped.forEach { (why, n) -> Text("Skipped $n: $why", color = Dim) }
            Text("Searches: ${a.searches} (${a.musicSearches} in YouTube Music)", color = Color.White)
            Text(if (a.playlists > 0) "Playlists: ${a.playlists}" else "Playlists: none in this export (tick \"playlists\" in Takeout to add them)",
                color = if (a.playlists > 0) Color.White else Dim)
        }
    }
}

@Composable
fun ApolloHome(modifier: Modifier = Modifier, onImport: () -> Unit) {
    val context = LocalContext.current
    val version = MyMusic.version.intValue // read eagerly: subscribes before any early return
    val model = MyMusic.model
    if (model == null) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    val mixes = remember(version) { MyMusic.mixes() }
    val recent = MyMusic.recentKeys()
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Text("Apollo", style = MaterialTheme.typography.headlineLarge, color = Gold, fontWeight = FontWeight.Bold)
            Text("${model.songs.size} of your songs · ${model.artists.size} artists · learning from every like, skip and full play",
                color = Dim, style = MaterialTheme.typography.bodySmall)
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { mixes.firstOrNull()?.let { YtQueue.play(it.songKeys) } }) {
                    Icon(Icons.Filled.AutoAwesome, null); Spacer(Modifier.width(6.dp)); Text("Play For You")
                }
                OutlinedButton(onClick = { YtQueue.play(model.songs.keys.toList(), 0, shuffled = true) }) {
                    Icon(Icons.Filled.Shuffle, null); Spacer(Modifier.width(6.dp)); Text("Shuffle all")
                }
            }
        }
        if (recent.isNotEmpty()) item {
            val seed = recent.first()
            val picks = remember(version, seed) { ApolloEngine.moreLike(model, MyMusic.feedback, seed, 30) }
            MixRow("Because you played ${model.songs[seed]?.title ?: ""}", "What you play around it", picks.map { it.songKey })
        }
        items(mixes, key = { it.id }) { mix -> MixRow(mix.name, mix.why, mix.songKeys) }
        if (model.searchedNeverPlayed.isNotEmpty()) item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("You searched, never played", color = Color.White, fontWeight = FontWeight.Bold)
                Text("From your own YouTube Music searches. Tap to look them up in YouTube Music.", color = Dim,
                    style = MaterialTheme.typography.bodySmall)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(model.searchedNeverPlayed) { q ->
                        AssistChip(onClick = {
                            context.startActivity(Intent(Intent.ACTION_VIEW,
                                Uri.parse("https://music.youtube.com/search?q=" + Uri.encode(q))))
                        }, label = { Text(q) })
                    }
                }
            }
        }
        item {
            MyMusic.imported?.audit?.let { AuditCard(it) }
            TextButton(onClick = onImport) { Text("Import a newer Takeout") }
        }
    }
}

@Composable
private fun MixRow(name: String, why: String, keys: List<String>) {
    if (keys.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(name, color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Text("$why · ${keys.size} songs", color = Dim, style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = { YtQueue.play(keys) }) { Icon(Icons.Filled.PlayCircle, "Play $name", tint = Gold) }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            itemsIndexed(keys.take(20)) { i, k ->
                val t = Library.track(k) ?: return@itemsIndexed
                Column(Modifier.width(136.dp).clickable { YtQueue.play(keys, i) }) {
                    AsyncImage(model = t.artworkUrl, contentDescription = null, contentScale = ContentScale.Crop,
                        modifier = Modifier.size(136.dp).clip(RoundedCornerShape(12.dp)).background(CardBg))
                    Text(t.title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium)
                    Text(t.artist, color = Dim, maxLines = 1, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
fun LibraryTab(modifier: Modifier = Modifier) {
    val version = MyMusic.version.intValue // read eagerly: subscribes before any early return
    val model = MyMusic.model ?: run { Box(modifier.fillMaxSize()) { Text("Import your history first", color = Dim) }; return }
    var query by remember { mutableStateOf("") }
    var mode by remember { mutableIntStateOf(0) }
    var artist by remember { mutableStateOf<String?>(null) }
    Column(modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        OutlinedTextField(value = query, onValueChange = { query = it; artist = null }, singleLine = true,
            leadingIcon = { Icon(Icons.Filled.Search, null) }, placeholder = { Text("Search your songs and artists") },
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
        TabRow(selectedTabIndex = mode, containerColor = Color.Transparent) {
            listOf("Songs", "Artists").forEachIndexed { i, l -> Tab(selected = mode == i, onClick = { mode = i; artist = null }, text = { Text(l) }) }
        }
        val songKeys = remember(version, query, artist) {
            when {
                artist != null -> model.artists.first { it.key == artist }.songKeys
                query.isNotBlank() -> ApolloEngine.search(model, query)
                else -> model.songs.values.sortedByDescending { it.plays }.map { it.key }
            }
        }
        if (mode == 0 || artist != null) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 6.dp)) {
                Text(artist?.let { a -> model.artists.first { it.key == a }.name } ?: "${songKeys.size} songs",
                    color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = { YtQueue.play(songKeys, 0, shuffled = true) }) { Icon(Icons.Filled.Shuffle, null); Text(" Shuffle") }
            }
            LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(songKeys, key = { _, k -> k }) { i, k ->
                    val s = model.songs[k] ?: return@itemsIndexed
                    SongRow(Library.track(k)?.artworkUrl ?: "", s.title, "${s.artist} · ${s.plays} plays") { YtQueue.play(songKeys, i) }
                }
            }
        } else {
            val artists = remember(version, query) {
                model.artists.filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(artists, key = { it.key }) { a ->
                    Row(Modifier.fillMaxWidth().clickable { artist = a.key }.padding(vertical = 12.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(a.name, color = Color.White, fontWeight = FontWeight.Bold)
                            Text("${a.plays} plays · ${a.songKeys.size} songs", color = Dim, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SongRow(art: String, title: String, sub: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (art.isNotEmpty()) AsyncImage(model = art, contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier.size(52.dp).clip(RoundedCornerShape(8.dp)).background(CardBg))
        else Box(Modifier.size(52.dp).clip(RoundedCornerShape(8.dp)).background(Brush.linearGradient(listOf(Gold, Color(0xFF7A3CFF)))))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sub, color = Dim, maxLines = 1, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** The phone's own audio files: these play through the Media3 engine (background audio, EQ, bass, visualizer). */
@Composable
fun PhoneTab(onPlay: (List<String>, Int) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val version = MyMusic.version.intValue // read eagerly: subscribes before any early return
    val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) MyMusic.scanLocal(context) }
    LaunchedEffect(Unit) { MyMusic.scanLocal(context) }
    val local = remember(version) { MyMusic.localTracks }
    Column(modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Text("On this phone", style = MaterialTheme.typography.headlineSmall, color = Gold, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 12.dp))
        Text("Your own audio files play in the Aurum engine: background audio, the EQ and bass, the Ember visualizer.",
            color = Dim, style = MaterialTheme.typography.bodySmall)
        if (local.isEmpty()) {
            Button(onClick = { ask.launch(perm) }, modifier = Modifier.padding(vertical = 12.dp)) { Text("Find music files on this phone") }
            Text("No audio files found yet.", color = Dim)
        } else {
            TextButton(onClick = { onPlay(local.shuffled().map { it.id }, 0) }) { Icon(Icons.Filled.Shuffle, null); Text(" Shuffle ${local.size} files") }
            LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(local, key = { _, t -> t.id }) { i, t ->
                    SongRow("", t.title, t.artist) { onPlay(local.map { it.id }, i) }
                }
            }
        }
    }
}
