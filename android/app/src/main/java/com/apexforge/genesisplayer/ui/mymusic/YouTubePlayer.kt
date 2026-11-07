package com.apexforge.genesisplayer.ui.mymusic

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.apexforge.genesisplayer.data.Library
import com.apexforge.genesisplayer.data.mymusic.ApolloEngine
import com.apexforge.genesisplayer.data.mymusic.MyMusic

/**
 * The YouTube queue. Songs from the owner's history play through YouTube's official IFrame Player API, visible on
 * screen as YouTube's terms require. Collapsing the player pauses it. Background listening is the YouTube Music app's
 * job ("Open in YouTube Music"); EQ and the visualizer apply to the phone's own audio files (the Media3 engine).
 */
object YtQueue {
    val keys = mutableStateListOf<String>()
    var index by mutableIntStateOf(0)
    var shuffle by mutableStateOf(false)
    /** Aurum 2.3: 0 = off, 1 = repeat the queue, 2 = repeat this song. Survives queue changes, like Aurum's player. */
    var repeat by mutableIntStateOf(0)
    fun cycleRepeat() { repeat = (repeat + 1) % 3 }
    var open by mutableStateOf(false)
    var playing by mutableStateOf(false)
    var notice by mutableStateOf<String?>(null)
    var embedFailures by mutableIntStateOf(0)
    internal var startedAtMs = 0L
    internal var web: WebView? = null

    fun play(newKeys: List<String>, start: Int = 0, shuffled: Boolean = false) {
        val playable = newKeys.filter { Library.track(it)?.youtubeId?.isNotEmpty() == true }
        if (playable.isEmpty()) return
        val first = playable[start.coerceIn(playable.indices)]
        original = playable
        keys.clear()
        keys.addAll(if (shuffled) listOf(first) + (playable - first).shuffled() else playable)
        index = if (shuffled) 0 else keys.indexOf(first)
        shuffle = shuffled
        notice = null
        open = true
        loadCurrent()
    }

    fun current() = keys.getOrNull(index)?.let { Library.track(it) }

    /** 2.4 golden: an Aurum play takes over; the embed is paused and closed so the two never play at once. */
    fun stop() {
        web?.evaluateJavascript("pause()", null)
        playing = false
        open = false
    }

    fun loadCurrent() {
        val vid = current()?.youtubeId ?: return
        startedAtMs = 0L
        web?.evaluateJavascript("load('$vid')", null)
    }

    fun next(context: Context, userSkip: Boolean) {
        val key = keys.getOrNull(index)
        if (userSkip && key != null && (startedAtMs == 0L || System.currentTimeMillis() - startedAtMs < 30_000)) {
            MyMusic.skipped(context, key)
        }
        when {
            !userSkip && repeat == 2 -> loadCurrent()                       // song ended: play it again
            index < keys.size - 1 -> { index++; loadCurrent() }
            repeat == 1 && keys.isNotEmpty() -> { index = 0; loadCurrent() } // queue ended: from the top
            else -> { playing = false; web?.evaluateJavascript("pause()", null) }
        }
    }

    fun prev() { if (index > 0) { index--; loadCurrent() } }

    private var original: List<String> = emptyList()

    /** Shuffle the rest of the queue; turning it off restores the rest in its original order. */
    fun toggleShuffle() {
        val cur = keys.getOrNull(index) ?: return
        val head = keys.take(index + 1)
        val restSet = keys.drop(index + 1).toSet()
        val rest = if (!shuffle) restSet.shuffled() else original.filter { it in restSet }
        keys.clear()
        keys.addAll(head + rest)
        index = keys.indexOf(cur)
        shuffle = !shuffle
    }

    fun moreLikeThis() {
        val key = keys.getOrNull(index) ?: return
        val m = MyMusic.model ?: return
        val picks = ApolloEngine.moreLike(m, MyMusic.feedback, key, 40).map { it.songKey }
        keys.clear(); keys.addAll(picks); original = picks; index = 0; shuffle = false
        notice = "Queue: more like this, from what you play around it"
    }

    fun openInYouTubeMusic(context: Context) {
        val vid = current()?.youtubeId ?: return
        web?.evaluateJavascript("pause()", null)
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://music.youtube.com/watch?v=$vid"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

private const val ORIGIN = "https://com.apexforge.genesisplayer"

private fun playerHtml(firstVid: String) = """<!doctype html><html><head>
<meta name="viewport" content="width=device-width,initial-scale=1"><meta name="referrer" content="strict-origin-when-cross-origin">
<style>html,body{margin:0;height:100%;background:#000;overflow:hidden}#p{width:100%;height:100%}</style></head>
<body><div id="p"></div><script src="https://www.youtube.com/iframe_api"></script><script>
var player=null,pending='$firstVid';
function onYouTubeIframeAPIReady(){player=new YT.Player('p',{width:'100%',height:'100%',videoId:pending,
 playerVars:{autoplay:1,playsinline:1,rel:0,modestbranding:1,origin:'$ORIGIN',widget_referrer:'$ORIGIN'},
 events:{onReady:function(e){e.target.playVideo();Aurum.onReady();},
  onStateChange:function(e){Aurum.onState(e.data);},onError:function(e){Aurum.onError(e.data);}}});}
function load(v){if(player&&player.loadVideoById){player.loadVideoById(v);}else{pending=v;}}
function pause(){if(player&&player.pauseVideo)player.pauseVideo();}
function play(){if(player&&player.playVideo)player.playVideo();}
</script></body></html>"""

private fun errorText(code: Int) = when (code) {
    2 -> "YouTube rejected the video id"
    5 -> "the embedded player can't play this format"
    100 -> "this video was removed or made private"
    101, 150 -> "the owner doesn't allow this video to play inside apps"
    else -> "YouTube player error $code"
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun YouTubePlayerScreen(onCollapse: () -> Unit) {
    val context = LocalContext.current
    val main = remember { Handler(Looper.getMainLooper()) }
    val track = YtQueue.current()
    val version = MyMusic.version.intValue // read eagerly: subscribes before any early return
    Column(Modifier.fillMaxSize().background(Color(0xFF0B0B10)).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { YtQueue.web?.evaluateJavascript("pause()", null); onCollapse() }) {
                Icon(Icons.Filled.KeyboardArrowDown, "Collapse (pauses)", tint = Color.White)
            }
            Column {
                Overline("Pulse Chamber · YouTube", Aurum.Gold)
                // 2.4 golden: the honest label. This player stops when the app leaves the screen (YouTube's terms).
                Text(com.apexforge.genesisplayer.data.playlists.PlayRouter.YOUTUBE_LABEL + " · needs the internet",
                    color = Color(0xFFFFCC66), style = MaterialTheme.typography.labelSmall)
            }
        }
        AndroidView(
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.mediaPlaybackRequiresUserGesture = false
                    keepScreenOn = true
                    webChromeClient = WebChromeClient()
                    addJavascriptInterface(object {
                        @JavascriptInterface fun onReady() { main.post { YtQueue.playing = true } }
                        @JavascriptInterface fun onState(s: Int) {
                            main.post {
                                when (s) {
                                    1 -> { YtQueue.playing = true; if (YtQueue.startedAtMs == 0L) YtQueue.startedAtMs = System.currentTimeMillis(); YtQueue.notice = null }
                                    2 -> YtQueue.playing = false
                                    0 -> { YtQueue.keys.getOrNull(YtQueue.index)?.let { MyMusic.completed(ctx, it) }; YtQueue.next(ctx, userSkip = false) }
                                }
                            }
                        }
                        @JavascriptInterface fun onError(code: Int) {
                            main.post {
                                YtQueue.embedFailures++
                                YtQueue.notice = "Skipped: ${errorText(code)}. Tap \"Open in YouTube Music\" to hear it there."
                                main.postDelayed({ YtQueue.next(ctx, userSkip = false) }, 2500)
                            }
                        }
                    }, "Aurum")
                    loadDataWithBaseURL(ORIGIN, playerHtml(YtQueue.current()?.youtubeId ?: ""), "text/html", "utf-8", null)
                    YtQueue.web = this
                }
            },
            onRelease = { it.destroy(); if (YtQueue.web === it) YtQueue.web = null }
        )
        Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
            Text(track?.title ?: "", color = Color.White, style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(track?.artist ?: "", color = Color(0xFFFFB067), style = MaterialTheme.typography.titleMedium)
            val key = YtQueue.keys.getOrNull(YtQueue.index)
            key?.let { k ->
                val s = MyMusic.model?.songs?.get(k)
                if (s != null) Text("${s.plays} plays in your history", color = Color(0xFF8A8AA0), style = MaterialTheme.typography.bodySmall)
            }
            YtQueue.notice?.let { Text(it, color = Color(0xFFFFCC66), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp)) }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically) {
                val liked = key != null && key in MyMusic.feedback.likes && version >= 0
                val disliked = key != null && key in MyMusic.feedback.dislikes
                IconButton(onClick = { key?.let { if (disliked) MyMusic.clearRating(context, it) else { MyMusic.dislike(context, it); YtQueue.next(context, userSkip = false) } } }) {
                    Icon(Icons.Filled.ThumbDown, "Not for me", tint = if (disliked) Color(0xFFFF6B6B) else Color.White)
                }
                IconButton(onClick = { YtQueue.prev() }) { Icon(Icons.Filled.SkipPrevious, "Previous", tint = Color.White) }
                FilledIconButton(onClick = { YtQueue.web?.evaluateJavascript(if (YtQueue.playing) "pause()" else "play()", null) },
                    modifier = Modifier.size(64.dp)) {
                    Icon(if (YtQueue.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, "Play/pause")
                }
                IconButton(onClick = { YtQueue.next(context, userSkip = true) }) { Icon(Icons.Filled.SkipNext, "Next", tint = Color.White) }
                IconButton(onClick = { key?.let { if (liked) MyMusic.clearRating(context, it) else MyMusic.like(context, it) } }) {
                    Icon(Icons.Filled.Favorite, "Love it", tint = if (liked) Color(0xFFFF5C8A) else Color.White)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = YtQueue.shuffle, onClick = { YtQueue.toggleShuffle() }, label = { Text("Shuffle") },
                    leadingIcon = { Icon(Icons.Filled.Shuffle, null) })
                FilterChip(selected = YtQueue.repeat != 0, onClick = { YtQueue.cycleRepeat() },
                    label = { Text(when (YtQueue.repeat) { 1 -> "Repeat all"; 2 -> "Repeat one"; else -> "Repeat" }) },
                    leadingIcon = { Icon(if (YtQueue.repeat == 2) Icons.Filled.RepeatOne else Icons.Filled.Repeat, null) })
                AssistChip(onClick = { YtQueue.moreLikeThis() }, label = { Text("More like this") },
                    leadingIcon = { Icon(Icons.Filled.AutoAwesome, null) })
                AssistChip(onClick = { YtQueue.openInYouTubeMusic(context) }, label = { Text("YouTube Music") },
                    leadingIcon = { Icon(Icons.Filled.OpenInNew, null) })
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // 2.4 golden: hand the rest of this queue to YouTube's own app (background play there depends on the
                // owner's YouTube plan; Aurum promises nothing about it).
                AssistChip(onClick = { AurumPlay.continueInYouTube(context) }, label = { Text("Continue in YouTube") },
                    leadingIcon = { Icon(Icons.Filled.OpenInNew, null) })
            }
        }
        Text("Up next", color = Color(0xFFB0B0C0), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 20.dp))
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            itemsIndexed(YtQueue.keys.drop(YtQueue.index + 1).take(50)) { i, k ->
                val t = Library.track(k) ?: return@itemsIndexed
                Row(Modifier.fillMaxWidth().clickable { YtQueue.index = YtQueue.index + 1 + i; YtQueue.loadCurrent() }
                    .padding(horizontal = 20.dp, vertical = 8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(t.title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(t.artist, color = Color(0xFF8A8AA0), style = MaterialTheme.typography.bodySmall, maxLines = 1)
                    }
                }
            }
        }
    }
}
