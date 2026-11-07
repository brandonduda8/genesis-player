package com.apexforge.genesisplayer.ui.mymusic

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import com.apexforge.genesisplayer.data.Library
import com.apexforge.genesisplayer.data.mymusic.MyMusic
import com.apexforge.genesisplayer.data.playlists.PlayRouter
import com.apexforge.genesisplayer.data.playlists.PlaylistPlayback
import com.apexforge.genesisplayer.data.playlists.Playlists

/**
 * Aurum 2.4 golden, charge 2: the Android side of [PlayRouter]. EVERY play button in the app calls [play], so a song
 * with an Aurum source always plays in the Media3 engine (survives screen-off and swipe-away) and a YouTube-only song
 * is always labeled. The rules themselves live in PlayRouter and are JVM-tested.
 */
object AurumPlay {
    private const val TAG = "GenesisPlayer"

    fun aurumId(ctx: Context, key: String): String? = Playlists.aurumId(ctx, key)
    fun onYouTube(key: String): Boolean = Playlists.onYouTube(key)
    fun engineOf(ctx: Context, key: String) = PlayRouter.engineOf(key, { aurumId(ctx, it) }, ::onYouTube)

    fun coverage(ctx: Context, keys: Collection<String>) =
        PlayRouter.coverage(keys, { aurumId(ctx, it) }, Playlists::worksOffline, ::onYouTube)

    fun plan(ctx: Context, keys: List<String>, start: Int?, shuffle: Boolean, online: Boolean = isOnline(ctx)) =
        PlayRouter.plan(keys, start, online, shuffle, { aurumId(ctx, it) }, Playlists::worksOffline, ::onYouTube,
            title = { MyMusic.model?.songs?.get(it)?.title ?: Library.track(it)?.title ?: it })

    /** Route and start a play. [onPlayAurum] is MainActivity's Media3 entry point. Returns the plan (for logs/tests). */
    fun play(ctx: Context, keys: List<String>, start: Int?, shuffle: Boolean = false, from: String,
             onPlayAurum: (List<String>, Int) -> Unit): PlaylistPlayback.Plan {
        val online = isOnline(ctx)
        val p = plan(ctx, keys, start, shuffle, online)
        Log.i(TAG, "AurumRoute: from=$from route=${p.route} n=${p.ids.size} skipped=${p.skipped}")
        val notice = PlayRouter.notice(p, online)
        when (p.route) {
            PlaylistPlayback.Route.AURUM -> {
                YtQueue.stop()
                onPlayAurum(p.ids, p.index)
                notice?.let { Toast.makeText(ctx, it, Toast.LENGTH_LONG).show() }
            }
            PlaylistPlayback.Route.YOUTUBE -> {
                YtQueue.play(p.ids, p.index)
                YtQueue.notice = notice
            }
            PlaylistPlayback.Route.NONE -> Toast.makeText(ctx, p.reason, Toast.LENGTH_SHORT).show()
        }
        return p
    }

    /** "Continue in YouTube": the owner's one-tap hand-off of the rest of the YouTube queue to YouTube's own app. */
    fun continueInYouTube(ctx: Context): Boolean {
        val ids = YtQueue.keys.mapNotNull { Library.track(it)?.youtubeId?.ifEmpty { null } }
        val url = PlayRouter.handoffUrl(ids, YtQueue.index) ?: return false
        YtQueue.web?.evaluateJavascript("pause()", null)
        Log.i(TAG, "AurumRoute: hand-off to YouTube (${ids.size - YtQueue.index} songs)")
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }
}
