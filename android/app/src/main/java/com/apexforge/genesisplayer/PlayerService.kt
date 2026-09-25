package com.apexforge.genesisplayer

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import com.apexforge.genesisplayer.data.RatingsStore
import com.apexforge.genesisplayer.data.SoundCloudResolver
import com.apexforge.genesisplayer.data.TasteSync
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.atomic.AtomicInteger

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
                Log.i(TAG, "audioSessionId changed -> $audioSessionId")
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
                    Log.i(TAG, "now playing id=$newId")
                    // SoundCloud signed URLs expire within minutes: re-resolve
                    // at every transition (just-started item + upcoming one).
                    if (item != null) refreshSoundCloudAtTransition(item)
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying && fx == null) {
                    // Fallback: if the session-id callback never fired with a valid
                    // id (e.g. odd emulator audio paths), try a lazy attach.
                    val sid = p.audioSessionId
                    Log.i(TAG, "lazy FX attach attempt, sessionId=$sid")
                    if (sid != C.AUDIO_SESSION_ID_UNSET && sid != 0) {
                        fx = AudioFxController(this@PlayerService, sid)
                    }
                }
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
        val (nRatings, nUnsynced) = RatingsStore.loadSummary(this)
        Log.i(TAG, "RatingsStore: loaded $nRatings ratings ($nUnsynced unsynced)")
        TasteSync.syncNow(this)
    }

    /**
     * Build a playable MediaItem for a track id. SoundCloud tracks resolve
     * their signed stream URL here, at play time — never from storage.
     * BLOCKING for SoundCloud tracks: call off the main thread.
     * Returns null when the track is missing or unresolvable (caller skips).
     */
    private fun itemFor(trackId: String): MediaItem? {
        val t = Library.track(trackId) ?: return null
        val uri = if (t.soundcloudUrl.isNotEmpty()) {
            val r = SoundCloudResolver.resolve(this@PlayerService, t.soundcloudUrl)
            if (r == null) {
                Log.w(TAG, "SoundCloud unresolvable, skipping: ${t.artist} - ${t.title}")
                return null
            }
            r.streamUrl
        } else {
            t.streamUrl
        }
        if (uri.isEmpty()) return null
        val meta = MediaMetadata.Builder()
            .setTitle(t.title)
            .setArtist(t.artist)
        if (t.artworkUrl.isNotEmpty()) meta.setArtworkUri(Uri.parse(t.artworkUrl))
        return MediaItem.Builder().setMediaId(t.id).setUri(uri).setMediaMetadata(meta.build()).build()
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

    /**
     * Tap-to-play (2026-09-25 repair): the tapped track resolves and plays
     * IMMEDIATELY; the rest of the queue resolves in the background and is
     * appended in batches. The old code resolved the entire playlist serially
     * before any audio started — a tap could wait on 50-494 resolutions.
     *
     * Queue planning (dislike filter + taste boost + tap-index mapping) is
     * pure and instant via [QueuePlanner]; only SoundCloud URL resolution
     * touches the network, off the main thread. The generation counter drops
     * stale queues when the user taps a new queue before the old one
     * finished resolving.
     */
    private val playGen = AtomicInteger(0)

    fun playTracks(ids: List<String>, startIndex: Int = 0) {
        val p = player ?: return
        val ctx = this@PlayerService
        val plan = QueuePlanner.plan(
            ids = ids,
            startIndex = startIndex,
            isDisliked = { RatingsStore.isDisliked(ctx, it) },
            likedArtists = RatingsStore.likedArtists(ctx),
            likedGenres = RatingsStore.likedGenres(ctx),
            trackOf = { Library.track(it) }
        )
        val ordered = plan.orderedIds
        val excluded = ids.filter { RatingsStore.isDisliked(ctx, it) }
        // Log format is load-bearing: the CI gate greps
        // "playTracks: queue N tracks (X excluded by dislike [...], Y boosted by taste)".
        Log.i(
            TAG,
            "playTracks: queue ${ordered.size} tracks " +
                "(${excluded.size} excluded by dislike [${excluded.take(5).joinToString(",")}], " +
                "${plan.boostedCount} boosted by taste)"
        )
        if (plan.startId == null) {
            Log.w(TAG, "playTracks: no playable tracks after filtering")
            return
        }
        val gen = playGen.incrementAndGet()
        val main = Handler(Looper.getMainLooper())
        val control = object : PlaybackQueue.PlayerControl {
            override fun setAndPlayFirst(item: MediaItem) {
                main.post {
                    if (playGen.get() != gen) return@post
                    p.setMediaItems(listOf(item), 0, 0L)
                    p.prepare()
                    p.play()
                    Log.i(TAG, "playTracks: tapped track playing (id=${item.mediaId})")
                }
            }

            override fun appendItems(items: List<MediaItem>) {
                main.post {
                    if (playGen.get() != gen) return@post
                    p.addMediaItems(items)
                    Log.i(TAG, "playTracks: appended ${items.size} queued tracks")
                }
            }
        }
        Thread {
            PlaybackQueue(
                resolve = { id -> itemFor(id) },
                control = control,
                isStale = { playGen.get() != gen }
            ).start(plan)
        }.apply { isDaemon = true; name = "genesis-play" }.start()
    }

    /**
     * Re-resolve SoundCloud signed URLs at a track transition: the
     * just-started item plus the upcoming one. Runs off the main thread;
     * swaps the playlist entry (position preserved for the current item) only
     * when the resolver returns a URL, and only while this queue generation
     * is still current and the entry still holds the same track.
     */
    private fun refreshSoundCloudAtTransition(item: MediaItem) {
        val p = player ?: return
        val index = p.currentMediaItemIndex
        val count = p.mediaItemCount
        val targets = listOf(index, index + 1).filter { it in 0 until count }
        if (targets.isEmpty()) return
        val gen = playGen.get()
        for (i in targets) {
            val target = p.getMediaItemAt(i)
            val t = Library.track(target.mediaId) ?: continue
            if (t.soundcloudUrl.isEmpty()) continue
            Thread {
                val freshItem = SoundCloudRefresher.refresh(target, t) { permalink ->
                    SoundCloudResolver.resolve(this@PlayerService, permalink)?.streamUrl
                } ?: return@Thread
                Handler(Looper.getMainLooper()).post {
                    val cur = player ?: return@post
                    if (playGen.get() != gen || i >= cur.mediaItemCount) return@post
                    if (cur.getMediaItemAt(i).mediaId != target.mediaId) return@post
                    val isCurrent = i == cur.currentMediaItemIndex
                    val pos = if (isCurrent) cur.currentPosition else C.TIME_UNSET
                    cur.replaceMediaItem(i, freshItem)
                    if (isCurrent && pos != C.TIME_UNSET) cur.seekTo(i, pos)
                    Log.i(TAG, "SoundCloud URL refreshed at transition: ${t.artist} - ${t.title}")
                }
            }.apply { isDaemon = true; name = "genesis-sc-refresh" }.start()
        }
    }

    fun playForYou(itemId: String) {
        val item = itemForYou(itemId) ?: return
        val p = player ?: return
        p.setMediaItem(item)
        p.prepare()
        p.play()
    }

    private inner class SessionCallback : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            // Custom commands are dropped by default — explicitly accept ours.
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(SessionCommand(ACTION_PLAY_IDS, Bundle.EMPTY))
                .add(SessionCommand(ACTION_SKIP_NEXT, Bundle.EMPTY))
                .add(SessionCommand(ACTION_SKIP_PREV, Bundle.EMPTY))
                .add(SessionCommand(ACTION_PLAY_FORYOU, Bundle.EMPTY))
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(commands)
                .build()
        }

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
