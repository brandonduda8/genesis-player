package com.apexforge.genesisplayer.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaController
import com.apexforge.genesisplayer.data.ApolloClient
import com.apexforge.genesisplayer.data.ApolloDrops
import com.apexforge.genesisplayer.data.ApolloNet
import com.apexforge.genesisplayer.data.ApolloStore
import com.apexforge.genesisplayer.data.ApolloStore.PendingDecision
import com.apexforge.genesisplayer.data.NowPlayingSnapshot
import com.apexforge.genesisplayer.data.SnapshotStore
import com.apexforge.genesisplayer.data.VoiceInput
import kotlinx.coroutines.delay

private sealed class ChatMsg {
    data class User(val text: String) : ChatMsg()
    data class Apollo(val text: String, val localNote: String?) : ChatMsg()
    data class Pending(val decision: PendingDecision) : ChatMsg()
}

/**
 * The Apollo tab — the conversational surface (APOLLO-LIVE.md §1.1).
 * BRKN Vibes branding; Apollo is Brandon's curator, gentle and genuine.
 *
 * - Prompt chips, chat bubbles, "Reading the signal…" state, fixed input bar.
 * - Voice is push-to-talk, Apollo-tab-only, input-only (no TTS).
 * - Mood-gate chip with live timer, dismissible.
 * - Pending decisions render as cards (awaiting_tap → recorded → syncing →
 *   published | failed). The app never claims "added".
 * - Offline: last-20 cached replies + the calm honest banner.
 */
@Composable
fun ApolloScreen(controller: MediaController?, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext
    val messages = remember { mutableStateListOf<ChatMsg>() }
    var input by remember { mutableStateOf("") }
    var thinking by remember { mutableStateOf(false) }
    var gateTick by remember { mutableStateOf(0) }
    var openDecisions by remember {
        mutableStateOf(ApolloStore.decisions(ctx).filter {
            it.state == ApolloStore.DecisionState.AWAITING_TAP
        })
    }
    var online by remember { mutableStateOf(true) }
    var micHeld by remember { mutableStateOf(false) }
    var micMsg by remember { mutableStateOf<String?>(null) }
    var micAllowed by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var wifiOnly by remember { mutableStateOf(ApolloStore.wifiOnlyArtwork(ctx)) }
    val listState = rememberLazyListState()
    val main = remember { Handler(Looper.getMainLooper()) }
    var voiceSession by remember { mutableStateOf<VoiceInput.Session?>(null) }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        micAllowed = granted
        micMsg = if (!granted) "Voice needs the mic — tap to allow, or just type." else null
    }

    // Offline seed: last-20 cached replies, so the tab is never a dead end.
    LaunchedEffect(Unit) {
        online = ApolloNet.isInternetUp(app)
        if (!online && messages.isEmpty()) {
            ApolloStore.cachedReplies(app).forEach { o ->
                messages.add(ChatMsg.Apollo(o.optString("reply"), "earlier"))
            }
        }
    }

    // Mood-gate timer tick (30s) so "N min left" stays honest.
    val gate = ApolloStore.moodGate(ctx)
    LaunchedEffect(gateTick) {
        if (gate.isActive()) {
            delay(30_000)
            gateTick++
        }
    }

    // Auto-scroll to the latest message.
    LaunchedEffect(messages.size, thinking) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    fun refreshDecisions() {
        openDecisions = ApolloStore.decisions(ctx).filter {
            it.state == ApolloStore.DecisionState.AWAITING_TAP
        }
        gateTick++ // re-read the mood gate too
    }

    fun send(text: String, via: String) {
        val msg = text.trim()
        if (msg.isEmpty() || thinking) return
        online = ApolloNet.isInternetUp(app)
        messages.add(ChatMsg.User(msg))
        if (via == "text") input = ""
        thinking = true
        micMsg = null
        // Snapshot player state on the main thread; the chat itself runs off it.
        val c = controller
        val now: NowPlayingSnapshot? = c?.currentMediaItem?.let { item ->
            NowPlayingSnapshot(
                trackId = item.mediaId,
                artist = (item.mediaMetadata.artist ?: "").toString(),
                title = (item.mediaMetadata.title ?: "").toString(),
                positionS = (c.currentPosition / 1000).coerceAtLeast(0)
            )
        }
        val tail: List<String> = c?.let { ctl ->
            val n = ctl.mediaItemCount
            ((n - 3).coerceAtLeast(0) until n).map { ctl.getMediaItemAt(it).mediaId }
        } ?: emptyList()
        Thread {
            val turn = ApolloClient.chat(app, msg, via, now, tail)
            ApolloClient.execute(app, c, turn)
            main.post {
                messages.add(ChatMsg.Apollo(turn.reply, turn.localNote))
                turn.pending.forEach { messages.add(ChatMsg.Pending(it)) }
                thinking = false
                refreshDecisions()
            }
        }.apply { isDaemon = true; name = "apollo-chat" }.start()
    }

    fun micDown() {
        if (!micAllowed) {
            // First mic use: the permission tap is HIS.
            permLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        micMsg = null
        micHeld = true
        val s = VoiceInput.Session(
            app,
            onResult = { heard ->
                main.post {
                    micHeld = false
                    voiceSession?.destroy()
                    voiceSession = null
                    if (heard.isNotEmpty()) send(heard, "voice")
                    else micMsg = "Didn't catch that — try again, or just type it."
                }
            },
            onError = { err ->
                main.post {
                    micHeld = false
                    voiceSession?.destroy()
                    voiceSession = null
                    micMsg = err
                }
            }
        )
        voiceSession = s
        s.start()
    }

    fun micUp() {
        if (micHeld) {
            voiceSession?.stop()
            // onResult/onError will fire and clean up; safety net:
            main.postDelayed({
                if (micHeld) {
                    micHeld = false
                    voiceSession?.destroy()
                    voiceSession = null
                }
            }, 4000)
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        // ---- header: BRKN Vibes branding ----
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text("Apollo", style = MaterialTheme.typography.headlineMedium, color = PhoenixGold)
            Text("BRKN Vibes · your curator", color = TextDim, fontSize = 13.sp)
            if (!online) {
                Spacer(Modifier.height(8.dp))
                Card(
                    colors = CardDefaults.cardColors(containerColor = CardDark),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "Apollo is offline — your music still plays.\nI don't wear my scars. I mastered them.",
                        color = PhoenixGold, fontSize = 13.sp,
                        modifier = Modifier.padding(12.dp), textAlign = TextAlign.Center
                    )
                }
            }
            // Mood-gate chip: always visible + dismissible while active.
            if (gate.isActive()) {
                Spacer(Modifier.height(8.dp))
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = EmberOrange.copy(alpha = 0.16f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "🚫 ${gate.excludeGenres.joinToString(" + ")} gated · ${gate.minutesLeft()} min left",
                            color = EmberOrange, fontSize = 13.sp, modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = {
                            ApolloStore.clearMoodGate(app)
                            gateTick++
                        }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Filled.Close, "Drop the gate", tint = TextDim,
                                modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }

        // ---- pending decisions awaiting his tap ----
        // BRKN wave 4: machine-snapshot suggestions ride the SAME outbox.
        // ApolloDrops.approve/reject record any id through DecideSync (the one
        // and only sync path) — the artist/title/why shown here come straight
        // from the snapshot, nothing invented.
        val decidedIds = remember(gateTick) {
            ApolloStore.decisions(ctx).map { it.id }.toSet()
        }
        val snapSuggestions = SnapshotStore.current()?.apolloSuggestions
            ?.filter { it.id !in decidedIds } ?: emptyList()
        if (snapSuggestions.isNotEmpty()) {
            Text(
                "From the machine snapshot",
                color = TextDim, fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }
        if (openDecisions.isNotEmpty()) {
            Text(
                "Waiting on you",
                color = TextDim, fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
            state = listState
        ) {
            items(snapSuggestions, key = { "snap-sug:${it.id}" }) { s ->
                PendingCard(
                    PendingDecision(
                        id = s.id,
                        kind = "suggest_to_library",
                        artist = s.artist,
                        title = s.title,
                        why = s.why,
                        state = ApolloStore.DecisionState.AWAITING_TAP,
                        decidedAt = 0L
                    ),
                    onApprove = {
                        ApolloDrops.approve(app, s.id)
                        refreshDecisions()
                    },
                    onReject = {
                        ApolloDrops.reject(app, s.id)
                        refreshDecisions()
                    }
                )
            }
            items(openDecisions, key = { "dec:${it.id}:${it.decidedAt}" }) { d ->
                PendingCard(d,
                    onApprove = {
                        ApolloDrops.approve(app, d.id)
                        refreshDecisions()
                    },
                    onReject = {
                        ApolloDrops.reject(app, d.id)
                        refreshDecisions()
                    })
            }
            if (messages.isEmpty() && openDecisions.isEmpty()) {
                item {
                    Column(
                        Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            "Ask for a mood, an artist, or what's fresh —\nI'll build the queue, you press play.",
                            color = TextDim, fontSize = 14.sp, textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "I don't wear my scars. I mastered them.",
                            color = PhoenixGold, fontSize = 13.sp, textAlign = TextAlign.Center
                        )
                    }
                }
            }
            itemsIndexed(messages, key = { i, _ -> "msg:$i" }) { _, m ->
                when (m) {
                    is ChatMsg.User -> Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.End
                    ) {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = EmberOrange.copy(alpha = 0.22f)
                            ),
                            shape = RoundedCornerShape(16.dp, 4.dp, 16.dp, 16.dp)
                        ) {
                            Text(m.text, color = Color.White, fontSize = 14.sp,
                                modifier = Modifier.padding(12.dp))
                        }
                    }
                    is ChatMsg.Apollo -> Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.Start
                    ) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = CardDark),
                            shape = RoundedCornerShape(4.dp, 16.dp, 16.dp, 16.dp),
                            modifier = Modifier.fillMaxWidth(0.88f)
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Text(m.text, color = Color.White, fontSize = 14.sp)
                                m.localNote?.let {
                                    Spacer(Modifier.height(4.dp))
                                    Text(it, color = TextDim, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                    is ChatMsg.Pending -> PendingCard(m.decision,
                        onApprove = {
                            ApolloDrops.approve(app, m.decision.id)
                            refreshDecisions()
                        },
                        onReject = {
                            ApolloDrops.reject(app, m.decision.id)
                            refreshDecisions()
                        })
                }
            }
            if (thinking) {
                item {
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Text("Reading the signal…", color = EmberOrange, fontSize = 13.sp,
                            modifier = Modifier.padding(8.dp))
                    }
                }
            }
            item { Spacer(Modifier.height(8.dp)) }
        }

        // ---- prompt chips ----
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Chip("Native bangers") { send("play bangers", "text") }
            Chip("Continue this energy") { send("continue this energy", "text") }
            Chip("Surprise me") { send("surprise me", "text") }
        }

        micMsg?.let {
            Text(it, color = EmberOrange, fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                textAlign = TextAlign.Center)
        }

        // ---- fixed input bar ----
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text("Ask Apollo…", color = TextDim, fontSize = 14.sp) },
                modifier = Modifier.weight(1f).clip(RoundedCornerShape(20.dp)),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = CardDark,
                    unfocusedContainerColor = CardDark,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
                ),
                singleLine = false,
                maxLines = 3
            )
            Spacer(Modifier.width(8.dp))
            // Push-to-talk mic: press-and-hold. Apollo-tab-only, input-only.
            IconButton(
                onClick = { /* press-and-hold handles it; tap = permission nudge */ },
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(
                        if (micHeld) EmberOrange.copy(alpha = 0.35f)
                        else CardDark
                    )
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onPress = {
                                micDown()
                                tryAwaitRelease()
                                micUp()
                            }
                        )
                    }
            ) {
                Icon(
                    Icons.Filled.Mic,
                    contentDescription = "Hold to talk to Apollo",
                    tint = if (micHeld) EmberOrange else TextDim,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(Modifier.width(4.dp))
            IconButton(
                onClick = { send(input, "text") },
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(EmberOrange.copy(alpha = 0.25f))
            ) {
                Icon(Icons.Filled.Send, "Send", tint = EmberOrange,
                    modifier = Modifier.size(24.dp))
            }
        }

        // ---- settings footer ----
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("Wi-Fi-only artwork", color = Color.White, fontSize = 13.sp)
                Text("Apollo learns on this device — ratings you tap are the only thing that leaves it.",
                    color = TextDim, fontSize = 11.sp)
            }
            Switch(
                checked = wifiOnly,
                onCheckedChange = {
                    wifiOnly = it
                    ApolloStore.setWifiOnlyArtwork(app, it)
                },
                colors = SwitchDefaults.colors(checkedThumbColor = EmberOrange)
            )
        }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun Chip(label: String, onTap: () -> Unit) {
    TextButton(onClick = onTap) {
        Text(label, color = PhoenixGold, fontSize = 13.sp)
    }
}

/**
 * A pending-decision card. States (APOLLO-LIVE §5): awaiting_tap → recorded →
 * syncing → published | failed. Nothing here ever claims "added" — only the
 * version-gated "Catalog updated — N tracks (vN)" note may claim an update.
 */
@Composable
private fun PendingCard(
    d: PendingDecision,
    onApprove: () -> Unit,
    onReject: () -> Unit
) {
    val stateLine = when (d.state) {
        ApolloStore.DecisionState.AWAITING_TAP -> "Awaiting your tap"
        ApolloStore.DecisionState.RECORDED -> "Recorded — syncing…"
        ApolloStore.DecisionState.SYNCING -> "Awaiting publish…"
        ApolloStore.DecisionState.PUBLISHED -> "Published ✓"
        ApolloStore.DecisionState.FAILED -> "Didn't land — tap approve to retry"
        else -> d.state
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = CardDark),
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
    ) {
        Column(Modifier.padding(12.dp)) {
            Text("✦ ${d.artist} — ${d.title}",
                color = Color.White, fontSize = 14.sp)
            if (d.why.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(d.why, color = PhoenixGold, fontSize = 12.sp, maxLines = 3)
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stateLine, color = TextDim, fontSize = 12.sp,
                    modifier = Modifier.weight(1f))
                if (d.state == ApolloStore.DecisionState.AWAITING_TAP ||
                    d.state == ApolloStore.DecisionState.FAILED
                ) {
                    TextButton(onClick = onReject) {
                        Text("Skip", color = TextDim, fontSize = 13.sp)
                    }
                    TextButton(onClick = onApprove) {
                        Text("Keep", color = EmberOrange, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}
