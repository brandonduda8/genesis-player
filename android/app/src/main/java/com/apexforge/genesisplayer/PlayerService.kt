package com.apexforge.genesisplayer

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.apexforge.genesisplayer.data.HistoryStore
import com.apexforge.genesisplayer.data.Library
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * Foreground playback service: ExoPlayer + MediaSession.
 * Media3 runs this as a foreground service while playing (notification +
 * lock-screen controls), keeps audio alive backgrounded / screen-off, and
 * handles audio focus (pauses on calls) via setAudioAttributes(..., true).
 */
class PlayerService : MediaSessionService() {

    private var player: ExoPlayer? = null
    private var session: MediaSession? = null

    // history bookkeeping
    private var currentId: String? = null
    private var currentPlayRecorded = false

    override fun onCreate() {
        super.onCreate()
        Library.load(this)
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
        val p = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .build()
        player = p
        p.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                if (audioSessionId != C.AUDIO_SESSION_ID_UNSET && audioSessionId != 0) {
                    fx?.release()
                    fx = AudioFxController(this@PlayerService, audioSessionId)
                }
            }

            override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                val newId = item?.mediaId
                if (newId != currentId) {
                    currentId = newId
                    currentPlayRecorded = false
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                val id = currentId ?: p.currentMediaItem?.mediaId
                if (isPlaying && id != null && !currentPlayRecorded) {
                    currentPlayRecorded = true
                    HistoryStore.recordPlay(this@PlayerService, id)
                    Log.i(TAG, "history: play $id")
                }
            }

            override fun onPlaybackStateChanged(state: Int) {
                val id = currentId ?: p.currentMediaItem?.mediaId ?: return
                if (state == Player.STATE_ENDED) {
                    HistoryStore.recordCompletion(this@PlayerService, id)
                    Log.i(TAG, "history: completion $id")
                }
            }
        })
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        session = MediaSession.Builder(this, p)
            .setSessionActivity(pi)
            .setCallback(SessionCallback())
            .build()
        Log.i(TAG, "service created, library=${Library.tracks.size} tracks")
    }

    private fun itemFor(trackId: String): MediaItem? {
        val t = Library.track(trackId) ?: return null
        val meta = MediaMetadata.Builder()
            .setTitle(t.title)
            .setArtist(t.artist)
        if (t.artworkUrl.isNotEmpty()) meta.setArtworkUri(Uri.parse(t.artworkUrl))
        return MediaItem.Builder().setMediaId(t.id).setUri(t.streamUrl).setMediaMetadata(meta.build()).build()
    }

    private fun itemForYou(itemId: String): MediaItem? {
        val t = Library.forYou.find { it.id == itemId } ?: return null
        val meta = MediaMetadata.Builder()
            .setTitle(t.title)
            .setArtist(t.artist)
        if (t.artworkUrl.isNotEmpty()) meta.setArtworkUri(Uri.parse(t.artworkUrl))
        return MediaItem.Builder().setMediaId(t.id).setUri(t.streamUrl).setMediaMetadata(meta.build()).build()
    }

    /** User pressed next/prev before half the track played: count a skip. */
    fun userSkip(direction: Int) {
        val p = player ?: return
        val id = currentId ?: p.currentMediaItem?.mediaId
        if (id != null) {
            val dur = p.duration.takeIf { it > 0 }
            if (dur != null && p.currentPosition < dur / 2) {
                HistoryStore.recordSkip(this, id)
                Log.i(TAG, "history: skip $id")
            }
        }
        if (direction > 0) p.seekToNextMediaItem() else p.seekToPreviousMediaItem()
    }

    fun playTracks(ids: List<String>, startIndex: Int = 0) {
        val p = player ?: return
        val items = ids.mapNotNull { itemFor(it) }
        if (items.isEmpty()) return
        p.setMediaItems(items, startIndex.coerceIn(items.indices), 0L)
        p.prepare()
        p.play()
    }

    fun playForYou(itemId: String) {
        val item = itemForYou(itemId) ?: return
        val p = player ?: return
        p.setMediaItem(item)
        p.prepare()
        p.play()
    }

    private inner class SessionCallback : MediaSession.Callback {
        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                ACTION_PLAY_IDS -> {
                    val ids = args.getStringArrayList("ids") ?: arrayListOf()
                    playTracks(ids, args.getInt("index", 0))
                }
                ACTION_SKIP_NEXT -> userSkip(1)
                ACTION_SKIP_PREV -> userSkip(-1)
                ACTION_PLAY_FORYOU -> playForYou(args.getString("id", ""))
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        fx?.release()
        fx = null
        session?.release()
        player?.release()
        super.onDestroy()
    }

    companion object {
        const val TAG = "GenesisPlayer"
        const val ACTION_PLAY_IDS = "GENESIS_PLAY_IDS"
        const val ACTION_SKIP_NEXT = "GENESIS_SKIP_NEXT"
        const val ACTION_SKIP_PREV = "GENESIS_SKIP_PREV"
        const val ACTION_PLAY_FORYOU = "GENESIS_PLAY_FORYOU"

        /** Live audio-effects controller, set when ExoPlayer's audio session attaches. */
        var fx: AudioFxController? = null
            private set
    }
}
