package com.apexforge.genesisplayer.ui

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.apexforge.genesisplayer.data.Library
import com.apexforge.genesisplayer.data.vault.Vault
import com.apexforge.genesisplayer.data.vault.VaultIndex

/** Bumps whenever the vault index or a download's state changes, so the UI recomposes. */
@Composable
private fun vaultVersion(): Int {
    val v = remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) {
        val main = Handler(Looper.getMainLooper())
        val l: () -> Unit = { main.post { v.intValue++ } }
        Vault.addListener(l)
        onDispose { Vault.removeListener(l) }
    }
    return v.intValue // read eagerly so this composable subscribes
}

fun formatBytes(b: Long): String = when {
    b >= 1L shl 30 -> "%.1f GB".format(b / (1L shl 30).toDouble())
    b >= 1L shl 20 -> "%.0f MB".format(b / (1L shl 20).toDouble())
    else -> "%.0f KB".format(b / 1024.0)
}

/** The offline part of the long-press sheet: download / remove / why it's streaming only. */
@Composable
fun OfflineActions(trackId: String) {
    val ctx = LocalContext.current
    @Suppress("UNUSED_VARIABLE") val version = vaultVersion()
    var message by remember(trackId) { mutableStateOf<String?>(null) }
    val track = Library.track(trackId) ?: return
    val have = remember(version, trackId) { runCatching { Vault.entry(ctx, trackId) }.getOrNull() }
    val busy = Vault.inFlight[trackId]
    when {
        busy != null -> Text("Offline: $busy…", color = TextDim, fontSize = 13.sp, modifier = Modifier.padding(8.dp))
        have != null -> TextButton(onClick = { Vault.remove(ctx, trackId); message = "Offline copy removed" },
            modifier = Modifier.fillMaxWidth()) {
            Text("Remove offline copy (${formatBytes(have.cipherBytes)})", color = EmberOrange, fontSize = 15.sp,
                modifier = Modifier.fillMaxWidth())
        }
        else -> TextButton(onClick = {
            message = null
            val main = Handler(Looper.getMainLooper())
            Vault.download(ctx, track) { err -> main.post { message = err ?: "Saved for offline (encrypted)" } }
        }, modifier = Modifier.fillMaxWidth()) {
            Text("Download for offline", color = EmberOrange, fontSize = 15.sp, modifier = Modifier.fillMaxWidth())
        }
    }
    message?.let { Text(it, color = TextDim, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 8.dp)) }
}

/** "Offline vault · 120 MB of 2.0 GB" — tap to manage. */
@Composable
fun VaultBar(onOpen: () -> Unit) {
    val ctx = LocalContext.current
    val version = vaultVersion()
    val (count, used, cap) = remember(version) {
        runCatching { Triple(Vault.all(ctx).size, Vault.usedBytes(ctx), Vault.capBytes(ctx)) }
            .getOrDefault(Triple(0, 0L, Vault.capBytes(ctx)))
    }
    Row(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Offline vault", color = PhoenixGold, fontSize = 15.sp)
            Text("$count track${if (count == 1) "" else "s"} · ${formatBytes(used)} of ${formatBytes(cap)} · encrypted",
                color = TextDim, fontSize = 12.sp)
        }
        Text("Manage", color = EmberOrange, fontSize = 14.sp)
    }
}

@Composable
fun VaultManagerDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val version = vaultVersion()
    val entries = remember(version) { runCatching { Vault.all(ctx) }.getOrDefault(emptyList()) }
    val cap = remember(version) { Vault.capBytes(ctx) }
    var confirmClear by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardDark,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done", color = PhoenixGold) } },
        title = { Text("Offline vault", color = PhoenixGold) },
        text = {
            Column {
                Text("${formatBytes(entries.sumOf { it.cipherBytes })} of ${formatBytes(cap)} used. When full, the " +
                    "oldest download is removed first.", color = Color.White, fontSize = 13.sp)
                Vault.lastEvictions.takeIf { it.isNotEmpty() }?.let { ev ->
                    Text("Removed to make room: " + ev.joinToString { "${it.artist} – ${it.title}" },
                        color = TextDim, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                }
                Spacer(Modifier.height(8.dp))
                Text("Cap", color = TextDim, fontSize = 12.sp)
                Row {
                    VaultIndex.CAP_CHOICES.forEach { c ->
                        TextButton(onClick = { Vault.setCap(ctx, c) }) {
                            Text(formatBytes(c).replace(".0 ", " "), fontSize = 12.sp,
                                color = if (c == cap) PhoenixGold else TextDim)
                        }
                    }
                }
                if (entries.isEmpty()) {
                    Text("Nothing saved yet. Long-press a track and choose Download for offline (only where the " +
                        "artist allows it).", color = TextDim, fontSize = 12.sp, modifier = Modifier.padding(vertical = 8.dp))
                } else {
                    LazyColumn(Modifier.heightIn(max = 260.dp)) {
                        items(entries, key = { it.fileId }) { e ->
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.weight(1f)) {
                                    Text(e.title, color = Color.White, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("${e.artist} · ${formatBytes(e.cipherBytes)}", color = TextDim, fontSize = 12.sp,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                TextButton(onClick = { Vault.remove(ctx, e.trackId) }) { Text("Remove", color = EmberOrange) }
                            }
                        }
                    }
                    TextButton(onClick = { confirmClear = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("Clear all offline copies", color = EmberOrange, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
    )
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            containerColor = CardDark,
            title = { Text("Clear the vault?", color = PhoenixGold) },
            text = { Text("Deletes every offline copy (${entries.size}). Your library, playlists and ratings are not touched.",
                color = Color.White, fontSize = 13.sp) },
            confirmButton = { TextButton(onClick = { Vault.clearAll(ctx); confirmClear = false }) { Text("Clear all", color = EmberOrange) } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel", color = TextDim) } }
        )
    }
}
