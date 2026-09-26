package com.apexforge.genesisplayer.ui

import android.content.Context
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.media3.session.MediaController
import com.apexforge.genesisplayer.PlayerService
import com.apexforge.genesisplayer.data.ApolloDrops
import com.apexforge.genesisplayer.data.Energy
import com.apexforge.genesisplayer.data.EnergyRules
import com.apexforge.genesisplayer.data.ForYouItem
import com.apexforge.genesisplayer.data.Library
import com.apexforge.genesisplayer.data.Ratings
import com.apexforge.genesisplayer.data.RatingsStore
import com.apexforge.genesisplayer.data.RemoteCatalog
import com.apexforge.genesisplayer.data.RemoteConfig
import com.apexforge.genesisplayer.data.SnapshotStore
import com.apexforge.genesisplayer.data.SnapshotTrack
import com.apexforge.genesisplayer.sendGenesis
import kotlinx.coroutines.launch

private fun uiPrefs(c: Context) = c.getSharedPreferences("golden_ui", Context.MODE_PRIVATE)

/** Play a list of catalog ids from [index] (the web's playFromContext). */
internal fun MediaController?.playIds(ids: List<String>, index: Int) {
    this?.sendGenesis(
        PlayerService.ACTION_PLAY_IDS,
        Bundle().apply {
            putStringArrayList("ids", ArrayList(ids))
            putInt("index", index.coerceIn(0, (ids.size - 1).coerceAtLeast(0)))
        }
    )
}

/** Play a read-only machine-snapshot list (BRKN wave 3 path, unchanged). */
internal fun MediaController?.playSnapshot(list: List<SnapshotTrack>, index: Int) {
    this?.sendGenesis(
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

/** Tap on a row: toggles when it is the current track, else plays from context. */
internal fun MediaController?.tapTrack(pulse: PlayerPulse, ids: List<String>, id: String) {
    val c = this ?: return
    if (pulse.currentId == id) {
        if (c.isPlaying) c.pause() else c.play()
    } else {
        c.playIds(ids, ids.indexOf(id))
    }
}

internal fun MediaController?.playNext(ctx: Context, id: String, title: String) {
    this?.sendGenesis(
        PlayerService.ACTION_QUEUE_UP_NEXT,
        Bundle().apply { putStringArrayList("ids", arrayListOf(id)) }
    )
    Toast.makeText(ctx, "“$title” plays next.", Toast.LENGTH_SHORT).show()
}

private fun energyTitle(e: Energy?) = when (e) {
    null -> "Everything"
    Energy.BANGER -> "Bangers"
    Energy.SOFT -> "Soft hours"
    Energy.MID -> "In between"
}

/**
 * Golden Player Phase 1 — the Crate (web "library" tab), mirrored:
 * live-dot status line, "Your sound. / Turned alive." hero, the BANGERS /
 * SOFT / ASK APOLLO mood grid, the APOLLO / FOR YOU "Fresh signals" cards
 * (Play / Add / dismiss), then YOUR CRATE with search + energy pills.
 *
 * Energy is inferred from catalog tags ([EnergyRules]) and labelled as such.
 * Search also covers the read-only machine snapshot (BRKN wave 3).
 */
@Composable
fun CrateScreen(
    controller: MediaController?,
    pulse: PlayerPulse,
    onAskApollo: () -> Unit,
    modifier: Modifier = Modifier
) {
    val ctx = LocalContext.current
    val note by RemoteCatalog.note // recomposes (and re-derives) when a catalog lands
    val snapshot = SnapshotStore.current()
    var energy by remember { mutableStateOf<Energy?>(null) }
    var query by remember { mutableStateOf("") }
    var ratingsTick by remember { mutableIntStateOf(0) }
    var dismissTick by remember { mutableIntStateOf(0) }
    var radioFor by remember { mutableStateOf<Pair<String, String>?>(null) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    val energies = remember(note) { EnergyRules.forLibrary() }
    val tracks = remember(note) { Library.tracks }
    val nativeCount = remember(note) { tracks.count { it.soundcloudUrl.isEmpty() } }
    val liked = remember(ratingsTick) { RatingsStore.likedIds(ctx) }
    val dismissed = remember(dismissTick) {
        uiPrefs(ctx).getStringSet("fy_dismissed", emptySet()) ?: emptySet()
    }
    val suggestions = Library.forYou.filter { it.id !in dismissed }
    val q = query.trim().lowercase()
    val visible = remember(note, energy, q) {
        tracks.filter { t ->
            (energy == null || energies[t.id] == energy) &&
                (q.isEmpty() || "${t.title} ${t.artist}".lowercase().contains(q))
        }
    }
    val visibleIds = remember(visible) { visible.map { it.id } }
    val snapHits = remember(q, snapshot) {
        if (q.isEmpty() || snapshot == null) emptyList()
        else (snapshot.playlists.flatMap { it.tracks } + snapshot.favorites)
            .distinctBy { it.id }
            .filter { "${it.title} ${it.artist}".lowercase().contains(q) }
    }

    LaunchedEffect(energy, q, visible.size) {
        Log.i("GenesisPlayer", "GoldenCrate: filter=${energy?.name?.lowercase() ?: "all"} query=${q.length}ch visible=${visible.size}")
    }

    fun pickMood(e: Energy) {
        energy = if (energy == e) null else e
        scope.launch { listState.animateScrollToItem(2) }
    }

    fun toggleLove(id: String) {
        val next = if (id in liked) null else "like"
        Ratings.apply(ctx, controller, id, next)
        ratingsTick++
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize().drawBehind {
            // web .app-shell: faint ember radial glow top-right
            drawRect(Golden.bg)
            val glow = Offset(size.width * 0.8f, -size.height * 0.05f)
            drawCircle(
                Brush.radialGradient(
                    listOf(Golden.ember.copy(alpha = 0.11f), Color.Transparent),
                    center = glow, radius = size.width * 0.75f
                ),
                radius = size.width * 0.75f,
                center = glow
            )
        }.padding(horizontal = 14.dp)
    ) {
        // ---- 0: mood hero ----
        item(key = "hero") {
            Column(Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 30.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(8.dp).drawBehind {
                            drawCircle(Golden.ember.copy(alpha = 0.35f), radius = size.minDimension * 1.3f)
                            drawCircle(Golden.ember)
                        }
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "$nativeCount NATIVE · ${tracks.size} SAVED", color = Golden.dim,
                        fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.1.em
                    )
                }
                Text(
                    "Your sound.\nTurned alive.", color = Golden.text,
                    style = Golden.displayStyle(54.sp),
                    modifier = Modifier.padding(top = 15.dp, bottom = 24.dp)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    MoodButton(
                        "BANGERS", "Turn it up", Golden.ember, energy == Energy.BANGER,
                        Modifier.weight(1f), icon = {
                            Icon(Icons.Outlined.LocalFireDepartment, null, modifier = Modifier.size(22.dp),
                                tint = if (energy == Energy.BANGER) Golden.bg else Golden.ember)
                        }
                    ) { pickMood(Energy.BANGER) }
                    MoodButton(
                        "SOFT", "Take it down", Golden.gold, energy == Energy.SOFT,
                        Modifier.weight(1f), icon = {
                            Icon(Icons.Outlined.DarkMode, null, modifier = Modifier.size(22.dp),
                                tint = if (energy == Energy.SOFT) Golden.bg else Golden.gold)
                        }
                    ) { pickMood(Energy.SOFT) }
                }
                Spacer(Modifier.height(9.dp))
                MoodButton(
                    "ASK APOLLO", "Reason through the next move", Golden.text, false,
                    Modifier.fillMaxWidth(), minHeight = 68, icon = { ApolloOrb() }, onClick = onAskApollo
                )
            }
            GoldenDivider()
        }

        // ---- 1: APOLLO / FOR YOU — Fresh signals ----
        item(key = "foryou") {
            Column(Modifier.fillMaxWidth().padding(top = 27.dp, bottom = 29.dp)) {
                SectionHeading("APOLLO / FOR YOU", "Fresh signals") {
                    EmberTextButton("Refresh drops") {
                        RemoteCatalog.checkForUpdates(ctx)
                        RemoteConfig.checkForUpdates(ctx)
                        ApolloDrops.poll(ctx)
                        SnapshotStore.fetch(ctx, "manual")
                        Toast.makeText(ctx, "Checking for new drops…", Toast.LENGTH_SHORT).show()
                    }
                }
                note?.let {
                    Text(it, color = Golden.gold, fontSize = 12.sp, modifier = Modifier.padding(bottom = 10.dp))
                }
                if (suggestions.isEmpty()) {
                    ApolloEmptyCard(onReset = {
                        uiPrefs(ctx).edit().remove("fy_dismissed").apply()
                        dismissTick++
                    })
                } else {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        items(suggestions, key = { it.id }) { item ->
                            SuggestionCard(
                                item,
                                onPlay = {
                                    controller?.sendGenesis(
                                        PlayerService.ACTION_PLAY_FORYOU,
                                        Bundle().apply { putString("id", item.id) }
                                    )
                                },
                                onAdd = {
                                    // No catalog publish from here (that needs his tap on the
                                    // machine side) — Add saves the pick to on-device taste.
                                    Ratings.apply(ctx, controller, item.id, "like")
                                    ratingsTick++
                                    Toast.makeText(ctx, "Saved to your taste — on this device.", Toast.LENGTH_SHORT).show()
                                },
                                onDismiss = {
                                    val p = uiPrefs(ctx)
                                    val cur = p.getStringSet("fy_dismissed", emptySet()) ?: emptySet()
                                    p.edit().putStringSet("fy_dismissed", cur + item.id).apply()
                                    dismissTick++
                                }
                            )
                        }
                    }
                }
            }
            GoldenDivider()
        }

        // ---- 2: YOUR CRATE heading, search, energy pills ----
        item(key = "crate-head") {
            Column(Modifier.fillMaxWidth().padding(top = 27.dp)) {
                SectionHeading("YOUR CRATE", energyTitle(energy)) {
                    EmberButton("Shuffle all") {
                        controller?.sendGenesis(PlayerService.ACTION_SHUFFLE_ALL)
                    }
                }
                SearchField(query, "Search songs or artists") { query = it }
                Spacer(Modifier.height(11.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Pill("All", energy == null) { energy = null }
                    Pill("Bangers", energy == Energy.BANGER) { energy = Energy.BANGER }
                    Pill("Soft", energy == Energy.SOFT) { energy = Energy.SOFT }
                    Pill("Middle", energy == Energy.MID) { energy = Energy.MID }
                }
                Text(
                    "Energy read from catalog tags · ${visible.size} tracks",
                    color = Golden.dim, fontSize = 11.sp,
                    modifier = Modifier.padding(top = 8.dp, bottom = 12.dp)
                )
                GoldenDivider()
            }
        }

        if (visible.isEmpty() && snapHits.isEmpty()) {
            item(key = "empty") {
                Column(Modifier.padding(vertical = 32.dp, horizontal = 8.dp)) {
                    Text("No tracks in this lane.", color = Golden.text, fontWeight = FontWeight.Bold)
                    Text("Try another mood or search.", color = Golden.dim, fontSize = 13.sp)
                }
            }
        }
        items(visible, key = { "t:${it.id}" }) { t ->
            TrackRow(
                title = t.title, artist = t.artist,
                native = t.soundcloudUrl.isEmpty(),
                energy = energies[t.id],
                playing = pulse.currentId == t.id && pulse.isPlaying,
                loved = t.id in liked,
                onPlay = { controller.tapTrack(pulse, visibleIds, t.id) },
                onLongPress = { radioFor = t.id to t.title },
                onLove = { toggleLove(t.id) },
                onPlayNext = { controller.playNext(ctx, t.id, t.title) }
            )
        }
        if (snapHits.isNotEmpty()) {
            item(key = "snap-head") {
                Kicker("BRKNVIBES SNAPSHOT · READ-ONLY", modifier = Modifier.padding(top = 18.dp, bottom = 6.dp))
            }
            items(snapHits, key = { "s:${it.id}" }) { t ->
                TrackRow(
                    title = t.title, artist = t.artist, native = null, energy = null,
                    playing = pulse.currentId == t.id && pulse.isPlaying, loved = null,
                    onPlay = { controller.playSnapshot(snapHits, snapHits.indexOf(t)) }
                )
            }
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

@Composable
private fun MoodButton(
    title: String,
    sub: String,
    tint: Color,
    active: Boolean,
    modifier: Modifier,
    minHeight: Int = 92,
    icon: @Composable () -> Unit,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(7.dp)
    Row(
        modifier.heightIn(min = minHeight.dp).clip(shape)
            .background(if (active) Golden.text else Golden.surface.copy(alpha = 0.88f))
            .border(1.dp, if (active) Golden.text else Golden.border, shape)
            .clickable(onClick = onClick)
            .padding(15.dp),
        verticalAlignment = if (minHeight < 92) Alignment.CenterVertically else Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(11.dp)
    ) {
        icon()
        Column {
            Text(title, color = if (active) Golden.bg else tint, style = Golden.displayStyle(20.sp))
            Text(sub, color = if (active) Golden.bg else Golden.dim, fontSize = 12.sp)
        }
    }
}

@Composable
private fun SuggestionCard(
    item: ForYouItem,
    onPlay: () -> Unit,
    onAdd: () -> Unit,
    onDismiss: () -> Unit
) {
    val shape = RoundedCornerShape(7.dp)
    Column(
        Modifier.width(290.dp).clip(shape).background(Golden.surface)
            .border(1.dp, Golden.border, shape).padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SourceBadge(native = true)
            Spacer(Modifier.weight(1f))
            Kicker("APOLLO PICK", modifier = Modifier)
        }
        Column(Modifier.fillMaxWidth().clickable(onClick = onPlay).padding(top = 12.dp, bottom = 4.dp)) {
            Text(item.title, color = Golden.text, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(item.artist, color = Golden.gold, fontSize = 13.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(
            item.why, color = Golden.dim, fontSize = 12.sp, lineHeight = 17.sp,
            maxLines = 3, minLines = 3, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp, bottom = 13.dp)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            CardAction("Play", filled = true, modifier = Modifier.weight(1f), onClick = onPlay) {
                Icon(Icons.Filled.PlayArrow, null, tint = Golden.bg, modifier = Modifier.size(16.dp))
            }
            CardAction("Add", filled = false, modifier = Modifier.weight(1f), onClick = onAdd) {
                Icon(Icons.Filled.Add, null, tint = Golden.text, modifier = Modifier.size(16.dp))
            }
            Box(
                Modifier.size(38.dp).clip(RoundedCornerShape(5.dp))
                    .border(1.dp, Golden.border, RoundedCornerShape(5.dp))
                    .clickable(onClick = onDismiss)
                    .semantics { contentDescription = "Dismiss ${item.title}" },
                contentAlignment = Alignment.Center
            ) {
                Text("×", color = Golden.text, fontSize = 20.sp)
            }
        }
    }
}

@Composable
private fun CardAction(
    label: String,
    filled: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
    icon: @Composable () -> Unit
) {
    val shape = RoundedCornerShape(5.dp)
    Row(
        modifier.height(38.dp).clip(shape)
            .background(if (filled) Golden.text else Color.Transparent)
            .border(1.dp, if (filled) Golden.text else Golden.border, shape)
            .clickable(onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        icon()
        Spacer(Modifier.width(5.dp))
        Text(label, color = if (filled) Golden.bg else Golden.text, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp)
    }
}

@Composable
private fun ApolloEmptyCard(onReset: () -> Unit) {
    val shape = RoundedCornerShape(7.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(Golden.surface)
            .border(1.dp, Golden.border, shape).padding(17.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ApolloOrb(44.dp)
            Spacer(Modifier.width(13.dp))
            Column {
                Text("Apollo is ready to dig.", color = Golden.text, fontWeight = FontWeight.Bold)
                Text(
                    "You've cleared every pick. Bring them back, or ask Apollo for a direction.",
                    color = Golden.dim, fontSize = 12.sp, lineHeight = 17.sp
                )
            }
        }
        Spacer(Modifier.height(13.dp))
        EmberButton("Bring back dismissed picks", Modifier.fillMaxWidth(), onReset)
    }
}

/** Web .search-field: bordered surface input, ember focus ring. */
@Composable
internal fun SearchField(value: String, placeholder: String, onChange: (String) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(5.dp)
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        textStyle = TextStyle(color = Golden.text, fontSize = 15.sp),
        cursorBrush = SolidColor(Golden.ember),
        modifier = Modifier.fillMaxWidth()
            .clip(shape).background(Golden.surface)
            .border(1.dp, if (focused) Golden.ember else Golden.border, shape)
            .onFocusChanged { focused = it.isFocused }
            .semantics { contentDescription = placeholder },
        decorationBox = { inner ->
            Box(Modifier.padding(horizontal = 14.dp, vertical = 13.dp)) {
                if (value.isEmpty()) Text(placeholder, color = Golden.dim, fontSize = 15.sp)
                inner()
            }
        }
    )
}

/** Long-press -> Start radio (catalog ids only; ACTION_MORE_LIKE_THIS). */
@Composable
internal fun RadioDialog(title: String, onStart: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        containerColor = Golden.surface,
        title = { Text("Start radio", color = Golden.gold) },
        text = {
            Column {
                Text("Build a queue like “$title”?", color = Golden.text, fontSize = 14.sp)
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
                    Text("Start radio", color = Golden.ember, fontSize = 15.sp, modifier = Modifier.fillMaxWidth())
                }
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text("Cancel", color = Golden.dim, fontSize = 15.sp, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    )
}
