package com.apexforge.genesisplayer

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.apexforge.genesisplayer.data.ApolloStore
import com.apexforge.genesisplayer.data.AudioSessionHub
import com.apexforge.genesisplayer.data.CrossfadeMath
import com.apexforge.genesisplayer.data.HistoryStore
import com.apexforge.genesisplayer.data.HistorySync
import com.apexforge.genesisplayer.data.Library
import com.apexforge.genesisplayer.data.RatingsStore
import com.apexforge.genesisplayer.data.SnapshotTrack
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

    /** AUDIT §2.1 defect 1: dead/expired URLs skip + tell the user. Never stall silently. */
    private val errorGuard = PlaybackErrorGuard()

    /** Consecutive mood-gate skips; bursts auto-clear the gate instead of spinning. */
    private var gatedSkips = 0

    private val mainHandler = Handler(Looper.getMainLooper())

    private fun toast(msg: String) {
        mainHandler.post {
            try {
                Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.w(TAG, "toast failed (${e.message})")
            }
        }
    }

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
                // BRKN wave 1: publish to the shared hub so the UI's ember
                // visualizer can attach a Visualizer to the real session.
                AudioSessionHub.audioSessionId = audioSessionId
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
                    // Apollo mood gate (client-side, APOLLO-LIVE Flow D): a
                    // gated genre skips automatically at the transition, with
                    // a burst guard so an all-gated queue can't spin forever.
                    val gated = ApolloStore.isGated(this@PlayerService, newId?.let { Library.track(it) })
                    if (gated && item != null) {
                        gatedSkips++
                        if (gatedSkips > 30) {
                            gatedSkips = 0
                            ApolloStore.clearMoodGate(this@PlayerService)
                            toast("Gate cleared — everything was getting skipped.")
                            Log.i(TAG, "mood gate auto-cleared after skip burst")
                        } else {
                            toast("Gated lane — skipping ahead.")
                            Log.i(TAG, "mood gate: skipping $newId")
                            mainHandler.post { player?.seekToNext() }
                        }
                        return
                    }
                    gatedSkips = 0
                    // SoundCloud signed URLs expire within minutes: re-resolve
                    // at every transition (just-started item + upcoming one).
                    if (item != null) refreshSoundCloudAtTransition(item)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                // AUDIT §2.1 defect 1: a dead/expired URL used to stall the
                // player in an error state with no skip, no retry, no message.
                // Now: skip to the next playable + tell the user. A whole
                // queue of dead URLs stops honestly instead of spinning.
                Log.w(TAG, "onPlayerError: ${error.errorCodeName} (${error.message})")
                when (val d = errorGuard.onError()) {
                    is PlaybackErrorGuard.Decision.Skip -> {
                        toast(d.message)
                        mainHandler.post { player?.seekToNext() }
                    }
                    is PlaybackErrorGuard.Decision.Stop -> {
                        toast(d.message)
                        mainHandler.post {
                            player?.stop()
                            player?.clearMediaItems()
                        }
                    }
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) errorGuard.onSuccess()
                // BRKN wave 2: a pause mid-crossfade aborts it — the fading
                // players must never keep rendering under a paused session.
                if (!isPlaying) abortCrossfade("paused")
                if (isPlaying && fx == null) {
                    // Fallback: if the session-id callback never fired with a valid
                    // id (e.g. odd emulator audio paths), try a lazy attach.
                    val sid = p.audioSessionId
                    Log.i(TAG, "lazy FX attach attempt, sessionId=$sid")
                    AudioSessionHub.audioSessionId = sid
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
                    // BRKN wave 2: end-of-queue sleep timer wins over autoplay.
                    if (sleepEndOfQueue) {
                        sleepEndOfQueue = false
                        toast("Sleep timer — end of queue.")
                        Log.i(TAG, "SleepTimer: fired at end of queue, stopping")
                    } else if (autoplayEnabled() && p.repeatMode == Player.REPEAT_MODE_OFF) {
                        // BRKN wave 4: autoplay — the queue's last item ended;
                        // seed moreLikeThis from it and keep playing.
                        seedAutoplay(id)
                    }
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
        HistorySync.flush(this)
        // BRKN wave 2: the crossfade watcher ticks every 500ms on the main
        // thread; it no-ops unless crossfade seconds > 0 are configured.
        mainHandler.post(xfadeTick)
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
        launchQueue(plan, "play")
    }

    /**
     * Shared tap-to-play launch: generation-guarded background queue on the
     * tap-to-play path (first audio after one resolution). [tag] names the
     * worker thread and log lines.
     */
    private fun launchQueue(plan: QueuePlan, tag: String) {
        val p = player ?: return
        val gen = playGen.incrementAndGet()
        val main = Handler(Looper.getMainLooper())
        val control = object : PlaybackQueue.PlayerControl {
            override fun setAndPlayFirst(item: MediaItem) {
                main.post {
                    if (playGen.get() != gen) return@post
                    p.setMediaItems(listOf(item), 0, 0L)
                    p.prepare()
                    p.play()
                    Log.i(TAG, "$tag: first track playing (id=${item.mediaId})")
                }
            }

            override fun appendItems(items: List<MediaItem>) {
                main.post {
                    if (playGen.get() != gen) return@post
                    p.addMediaItems(items)
                    Log.i(TAG, "$tag: appended ${items.size} queued tracks")
                }
            }
        }
        Thread {
            PlaybackQueue(
                resolve = { id -> itemFor(id) },
                control = control,
                isStale = { playGen.get() != gen }
            ).start(plan)
        }.apply { isDaemon = true; name = "genesis-$tag" }.start()
    }

    /**
     * SHUFFLE EVERYTHING (Phase 1): one-tap global shuffle across the whole
     * catalog. Dislikes excluded; first audio stays fast via the tap-to-play
     * path (only the first shuffled track resolves before playback starts).
     */
    fun shuffleAll() {
        val ctx = this@PlayerService
        val ids = Library.tracks.map { it.id }
        if (ids.isEmpty()) {
            toast("Nothing to shuffle yet — hit Refresh music first.")
            Log.w(TAG, "shuffleAll: empty catalog")
            return
        }
        val shuffled = QueuePlanner.shuffleOrder(
            ids,
            { RatingsStore.isDisliked(ctx, it) },
            System.currentTimeMillis()
        )
        if (shuffled.isEmpty()) {
            toast("Everything's disliked — undislike something to shuffle.")
            Log.w(TAG, "shuffleAll: all ${ids.size} tracks disliked")
            return
        }
        Log.i(
            TAG,
            "shuffleAll: queue ${shuffled.size} tracks " +
                "(${ids.size - shuffled.size} excluded by dislike)"
        )
        launchQueue(QueuePlan(shuffled, shuffled.first(), 0), "shuffleAll")
    }

    /**
     * MORE LIKE THIS (Phase 1): a queue seeded from the given track's
     * artist/genre/mood, ranked by the on-device taste vectors
     * (QueuePlanner.moreLikeThis). Deterministic, catalog ids only, never
     * the seed itself, never dislikes.
     */
    fun moreLikeThis(seedId: String) {
        val ctx = this@PlayerService
        val seed = Library.track(seedId)
        if (seed == null) {
            toast("Couldn't find that track.")
            Log.w(TAG, "moreLikeThis: unknown seed id=$seedId")
            return
        }
        val plan = QueuePlanner.moreLikeThis(
            seed = seed,
            ids = Library.tracks.map { it.id },
            isDisliked = { RatingsStore.isDisliked(ctx, it) },
            likedArtists = RatingsStore.likedArtists(ctx),
            likedGenres = RatingsStore.likedGenres(ctx),
            trackOf = { Library.track(it) }
        )
        if (plan.startId == null) {
            toast("Nothing else in that lane yet.")
            Log.w(TAG, "moreLikeThis: empty pool for seed id=$seedId")
            return
        }
        Log.i(
            TAG,
            "moreLikeThis: queue ${plan.orderedIds.size} tracks like " +
                "${seed.artist} — ${seed.title} (${plan.boostedCount} in-lane)"
        )
        launchQueue(plan, "moreLikeThis")
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

    // ---- BRKN wave 2: playback prefs (crossfade seconds, autoplay) ----
    private fun playbackPrefs() = getSharedPreferences("genesis_playback", MODE_PRIVATE)
    private fun xfadeSeconds(): Float = playbackPrefs().getFloat("xfade_s", 0f)
    private fun autoplayEnabled(): Boolean = playbackPrefs().getBoolean("autoplay", true)

    // ---- BRKN wave 2: sleep timer (service-side countdown) ----
    private var sleepRunnable: Runnable? = null
    private var sleepEndOfQueue = false

    fun setSleepTimer(minutes: Int) {
        cancelSleepTimer(silent = true)
        if (minutes <= 0) return
        val r = Runnable {
            sleepRunnable = null
            abortCrossfade("sleep timer")
            player?.pause()
            toast("Sleep timer — playback stopped.")
            Log.i(TAG, "SleepTimer: fired after $minutes min, stopping playback")
        }
        sleepRunnable = r
        mainHandler.postDelayed(r, minutes * 60_000L)
        toast("Sleep timer: $minutes min.")
        // Log format is load-bearing: the CI gate greps "SleepTimer: set N min".
        Log.i(TAG, "SleepTimer: set $minutes min")
    }

    fun setSleepEndOfQueue() {
        cancelSleepTimer(silent = true)
        sleepEndOfQueue = true
        toast("Sleep timer: end of queue.")
        Log.i(TAG, "SleepTimer: set end of queue")
    }

    fun cancelSleepTimer(silent: Boolean = false) {
        sleepRunnable?.let { mainHandler.removeCallbacks(it) }
        sleepRunnable = null
        sleepEndOfQueue = false
        if (!silent) {
            toast("Sleep timer off.")
            // Load-bearing for the gate: "SleepTimer: cancelled".
            Log.i(TAG, "SleepTimer: cancelled")
        }
    }

    // ---- BRKN wave 2: TRUE overlapping crossfade (second ExoPlayer) ----
    //
    // When the current track is within X s of its end, the next queue item is
    // prepared on player2 at volume 0; player1 ramps 1->0 while player2 ramps
    // 0->1 over X s. Both decoders render into the mixer simultaneously — a
    // genuine overlap, not a chained fade.
    //
    // The MediaSession deliberately stays pinned to player1: rebuilding it
    // mid-playback would drop every controller (destabilizing). The "handoff"
    // is player1 seekTo(next, player2.position) with volume restored — the
    // audible player after the overlap, same session throughout.
    private var player2: ExoPlayer? = null
    private var xfading = false
    private var xfadeFromIdx = -1
    private var xfadeFromPos = 0L

    private val xfadeTick = object : Runnable {
        override fun run() {
            try {
                xfadeCheck()
            } catch (t: Throwable) {
                Log.w(TAG, "Crossfade: tick failed (${t.message})")
            }
            mainHandler.postDelayed(this, 500)
        }
    }

    private fun xfadeCheck() {
        val p = player ?: return
        val s = xfadeSeconds()
        val nextIdx = p.currentMediaItemIndex + 1
        if (!CrossfadeMath.shouldEngage(
                positionMs = p.currentPosition,
                durationMs = p.duration,
                xfadeS = s,
                hasNext = nextIdx in 0 until p.mediaItemCount,
                isPlaying = p.isPlaying && !xfading
            )
        ) return
        engageCrossfade(nextIdx, s)
    }

    private fun engageCrossfade(nextIdx: Int, s: Float) {
        val p = player ?: return
        xfading = true
        xfadeFromIdx = p.currentMediaItemIndex
        xfadeFromPos = p.currentPosition
        try {
            val item = p.getMediaItemAt(nextIdx)
            val attrs = AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build()
            val p2 = ExoPlayer.Builder(this)
                .setAudioAttributes(attrs, false) // no audio-focus fights with player1
                .setHandleAudioBecomingNoisy(false)
                .build()
            player2 = p2
            p2.setMediaItem(item)
            p2.volume = 0f
            p2.prepare()
            p2.play()
            // Log format is load-bearing: the CI gate greps "Crossfade: engaged Ns".
            Log.i(TAG, "Crossfade: engaged ${s.toInt()}s")
            val steps = 12
            val stepMs = (s * 1000 / steps).toLong().coerceAtLeast(50)
            var step = 0
            val ramp = object : Runnable {
                override fun run() {
                    val cur2 = player2
                    if (!xfading || cur2 !== p2) return
                    // Abort when the world moved: the track already advanced
                    // naturally, or the user seeked backwards mid-ramp.
                    if (p.currentMediaItemIndex != xfadeFromIdx ||
                        p.currentPosition < xfadeFromPos - 2000
                    ) {
                        abortCrossfade("track advanced/seeked")
                        return
                    }
                    step++
                    val t = (step.toFloat() / steps).coerceIn(0f, 1f)
                    try {
                        p.volume = 1f - t
                        p2.volume = t
                    } catch (_: Exception) { /* torn down mid-ramp */ }
                    if (step < steps) {
                        mainHandler.postDelayed(this, stepMs)
                    } else {
                        xfadeHandoff(p, p2, nextIdx)
                    }
                }
            }
            mainHandler.postDelayed(ramp, stepMs)
        } catch (t: Throwable) {
            // Honest fallback: the overlap failed — keep the natural gap.
            Log.w(TAG, "Crossfade: engage failed (${t.message}); keeping natural gap")
            abortCrossfade("engage failed")
        }
    }

    private fun xfadeHandoff(p: ExoPlayer, p2: ExoPlayer, nextIdx: Int) {
        try {
            val pos = p2.currentPosition
            if (nextIdx in 0 until p.mediaItemCount) {
                p.seekTo(nextIdx, pos)
            }
            p.volume = 1f
            if (!p.isPlaying) p.play()
            Log.i(TAG, "Crossfade: handoff complete")
        } catch (t: Throwable) {
            Log.w(TAG, "Crossfade: handoff issue (${t.message})")
        } finally {
            try { p2.stop() } catch (_: Exception) {}
            try { p2.release() } catch (_: Exception) {}
            if (player2 === p2) player2 = null
            xfading = false
        }
    }

    private fun abortCrossfade(reason: String) {
        if (!xfading && player2 == null) return
        xfading = false
        try { player2?.stop() } catch (_: Exception) {}
        try { player2?.release() } catch (_: Exception) {}
        player2 = null
        try { player?.volume = 1f } catch (_: Exception) {}
        Log.i(TAG, "Crossfade: aborted ($reason)")
    }

    // ---- BRKN wave 4: autoplay ----
    private fun seedAutoplay(lastId: String) {
        val ctx = this@PlayerService
        val seed = Library.track(lastId)
        if (seed == null) {
            Log.i(TAG, "Autoplay: last track $lastId not in catalog; stopping")
            return
        }
        val plan = QueuePlanner.moreLikeThis(
            seed = seed,
            ids = Library.tracks.map { it.id },
            isDisliked = { RatingsStore.isDisliked(ctx, it) },
            likedArtists = RatingsStore.likedArtists(ctx),
            likedGenres = RatingsStore.likedGenres(ctx),
            trackOf = { Library.track(it) }
        )
        if (plan.startId == null) {
            Log.i(TAG, "Autoplay: no candidates after $lastId; stopping")
            return
        }
        // Log format is load-bearing: the gate greps "Autoplay: seeded from <id>".
        Log.i(TAG, "Autoplay: seeded from $lastId")
        launchQueue(plan, "autoplay")
    }

    // ---- BRKN wave 3: play snapshot tracks (direct stream_urls) ----
    //
    // Snapshot playlists are machine-owned and read-only; the service plays
    // their direct stream URLs with no resolution step.
    fun playSnapshotTracks(tracks: List<SnapshotTrack>, startIndex: Int = 0) {
        val p = player ?: return
        playGen.incrementAndGet() // invalidate any stale tap-to-play appends
        val items = tracks.mapNotNull { t ->
            if (t.streamUrl.isEmpty()) {
                Log.w(TAG, "playSnapshot: no stream_url for ${t.id}; skipping")
                return@mapNotNull null
            }
            val meta = MediaMetadata.Builder()
                .setTitle(t.title)
                .setArtist(t.artist)
            if (t.artworkUrl.isNotEmpty()) meta.setArtworkUri(Uri.parse(t.artworkUrl))
            MediaItem.Builder().setMediaId(t.id).setUri(t.streamUrl)
                .setMediaMetadata(meta.build()).build()
        }
        if (items.isEmpty()) {
            toast("That snapshot list has nothing playable.")
            Log.w(TAG, "playSnapshot: no playable items")
            return
        }
        val idx = startIndex.coerceIn(items.indices)
        mainHandler.post {
            val cur = player ?: return@post
            cur.setMediaItems(items, idx, 0L)
            cur.prepare()
            cur.play()
            Log.i(TAG, "playSnapshot: queue ${items.size} tracks (start=$idx)")
        }
    }

    // ---- Playback control commands (production). ----
    //
    // The CI test *intents* (TEST_SLEEP / TEST_XFADE / TEST_QUEUE_DUMP) are
    // DEBUG-gated in MainActivity; the commands below are the normal
    // production path also used by the in-app settings UI, so they must NOT
    // be DEBUG-gated — otherwise release builds would ignore legitimate
    // settings. Release ignores only the special intent actions.
    private fun cmdSetSleep(minutes: Int, endOfQueue: Boolean) {
        when {
            endOfQueue -> setSleepEndOfQueue()
            minutes <= 0 -> cancelSleepTimer()
            else -> setSleepTimer(minutes)
        }
        Log.i(TAG, "Sleep: set minutes=$minutes endOfQueue=$endOfQueue")
    }

    private fun cmdSetXfade(seconds: Int, prove: Boolean) {
        val s = seconds.coerceIn(0, 8)
        playbackPrefs().edit().putFloat("xfade_s", s.toFloat()).apply()
        Log.i(TAG, "Crossfade: set ${s}s")
        if (prove && s > 0) {
            // Honest proof path: seek the CURRENT track near its end so the
            // production watcher engages for real. Same code, no shortcuts.
            mainHandler.post {
                val p = player ?: return@post
                val dur = p.duration
                val hasNext = p.currentMediaItemIndex + 1 < p.mediaItemCount
                if (dur > (s + 3) * 1000 && hasNext) {
                    val to = (dur - (s + 2) * 1000).coerceAtLeast(0)
                    p.seekTo(to)
                    Log.i(TAG, "Crossfade: prove seek to ${to}ms (dur=${dur}ms)")
                } else {
                    Log.w(TAG, "Crossfade: prove skipped (duration=$dur, hasNext=$hasNext)")
                }
            }
        }
    }

    private fun cmdSetAutoplay(enabled: Boolean) {
        playbackPrefs().edit().putBoolean("autoplay", enabled).apply()
        Log.i(TAG, "Autoplay: ${if (enabled) "on" else "off"}")
    }

    private fun cmdQueueDump(moveFrom: Int, moveTo: Int) {
        val p = player ?: return
        val n = p.mediaItemCount
        if (moveFrom in 0 until n && moveTo in 0 until n && moveFrom != moveTo) {
            p.moveMediaItem(moveFrom, moveTo)
            Log.i(TAG, "QueueDump: moved $moveFrom -> $moveTo")
            // The timeline applies async; dump after it settles.
            mainHandler.postDelayed({ dumpQueueOrder() }, 800)
        } else {
            dumpQueueOrder()
        }
    }

    private fun dumpQueueOrder() {
        val p = player ?: return
        val ids = (0 until p.mediaItemCount).map { p.getMediaItemAt(it).mediaId }
        // Log format is load-bearing: the CI gate greps "QueueDump: order [...]".
        Log.i(TAG, QueueDump.format(ids, p.currentMediaItemIndex))
    }

    /**
     * Apollo `queue_up_next` (APOLLO-LIVE §2.1): insert up to 10 catalog
     * tracks directly after the current one. Resolved off the main thread;
     * unknown ids are dropped, never played. The rest of the queue stays
     * as it was.
     */
    fun queueUpNext(ids: List<String>) {
        val p = player ?: return
        val wanted = ids.filter { Library.track(it) != null }.take(10)
        if (wanted.isEmpty()) {
            Log.w(TAG, "queueUpNext: no known ids, ignoring")
            return
        }
        Thread {
            val items = wanted.mapNotNull { itemFor(it) }
            if (items.isEmpty()) {
                mainHandler.post { toast("Those tracks wouldn't play — nothing queued.") }
                return@Thread
            }
            mainHandler.post {
                val cur = player ?: return@post
                if (cur.mediaItemCount == 0) {
                    cur.setMediaItems(items, 0, 0L)
                    cur.prepare()
                    cur.play()
                } else {
                    val at = (cur.currentMediaItemIndex + 1).coerceIn(0, cur.mediaItemCount)
                    cur.addMediaItems(at, items)
                }
                toast("Queued ${items.size} up next.")
                Log.i(TAG, "queueUpNext: inserted ${items.size} after current")
            }
        }.apply { isDaemon = true; name = "genesis-queue-up-next" }.start()
    }

    private inner class SessionCallback : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            // Custom commands are dropped by default — explicitly accept ours.
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(SessionCommand(ACTION_PLAY_IDS, Bundle.EMPTY))
                .add(SessionCommand(ACTION_SHUFFLE_ALL, Bundle.EMPTY))
                .add(SessionCommand(ACTION_MORE_LIKE_THIS, Bundle.EMPTY))
                .add(SessionCommand(ACTION_QUEUE_UP_NEXT, Bundle.EMPTY))
                .add(SessionCommand(ACTION_SKIP_NEXT, Bundle.EMPTY))
                .add(SessionCommand(ACTION_SKIP_PREV, Bundle.EMPTY))
                .add(SessionCommand(ACTION_PLAY_FORYOU, Bundle.EMPTY))
                .add(SessionCommand(ACTION_PLAY_SNAPSHOT, Bundle.EMPTY))
                .add(SessionCommand(ACTION_SLEEP_SET, Bundle.EMPTY))
                .add(SessionCommand(ACTION_XFADE_SET, Bundle.EMPTY))
                .add(SessionCommand(ACTION_AUTOPLAY_SET, Bundle.EMPTY))
                .add(SessionCommand(ACTION_QUEUE_DUMP, Bundle.EMPTY))
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
                ACTION_SHUFFLE_ALL -> shuffleAll()
                ACTION_MORE_LIKE_THIS -> moreLikeThis(args.getString("id", ""))
                ACTION_QUEUE_UP_NEXT -> {
                    val ids = args.getStringArrayList("ids") ?: arrayListOf()
                    queueUpNext(ids)
                }
                ACTION_SKIP_NEXT -> userSkip(1)
                ACTION_SKIP_PREV -> userSkip(-1)
                ACTION_PLAY_FORYOU -> playForYou(args.getString("id", ""))
                ACTION_PLAY_SNAPSHOT -> {
                    val ids = args.getStringArrayList("ids") ?: arrayListOf()
                    val artists = args.getStringArrayList("artists") ?: arrayListOf()
                    val titles = args.getStringArrayList("titles") ?: arrayListOf()
                    val urls = args.getStringArrayList("stream_urls") ?: arrayListOf()
                    val arts = args.getStringArrayList("artwork_urls") ?: arrayListOf()
                    val genres = args.getStringArrayList("genres") ?: arrayListOf()
                    playSnapshotTracks(
                        ids.mapIndexed { i, id ->
                            SnapshotTrack(
                                id = id,
                                artist = artists.getOrElse(i) { "" },
                                title = titles.getOrElse(i) { "" },
                                streamUrl = urls.getOrElse(i) { "" },
                                artworkUrl = arts.getOrElse(i) { "" },
                                genre = genres.getOrElse(i) { "" }
                            )
                        },
                        args.getInt("index", 0)
                    )
                }
                // Playback control commands: production (in-app settings UI)
                // and the DEBUG test intents both drive these.
                ACTION_SLEEP_SET -> cmdSetSleep(
                    args.getInt("minutes", 0),
                    args.getBoolean("end_of_queue", false)
                )
                ACTION_XFADE_SET -> cmdSetXfade(
                    args.getInt("seconds", 0),
                    args.getBoolean("prove", false)
                )
                ACTION_AUTOPLAY_SET -> cmdSetAutoplay(args.getBoolean("enabled", true))
                ACTION_QUEUE_DUMP -> cmdQueueDump(
                    args.getInt("move_from", -1),
                    args.getInt("move_to", -1)
                )
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        mainHandler.removeCallbacks(xfadeTick)
        abortCrossfade("destroy")
        try { player2?.release() } catch (_: Exception) {}
        player2 = null
        cancelSleepTimer(silent = true)
        fx?.release()
        fx = null
        session?.release()
        player?.release()
        super.onDestroy()
    }

    companion object {
        const val TAG = "GenesisPlayer"
        const val ACTION_PLAY_IDS = "GENESIS_PLAY_IDS"
        const val ACTION_SHUFFLE_ALL = "GENESIS_SHUFFLE_ALL"
        const val ACTION_MORE_LIKE_THIS = "GENESIS_MORE_LIKE_THIS"
        const val ACTION_QUEUE_UP_NEXT = "GENESIS_QUEUE_UP_NEXT"
        const val ACTION_SKIP_NEXT = "GENESIS_SKIP_NEXT"
        const val ACTION_SKIP_PREV = "GENESIS_SKIP_PREV"
        const val ACTION_PLAY_FORYOU = "GENESIS_PLAY_FORYOU"
        /** BRKN wave 3: play machine-owned snapshot tracks (direct stream URLs). */
        const val ACTION_PLAY_SNAPSHOT = "GENESIS_PLAY_SNAPSHOT"
        /** BRKN wave 2: playback controls — production commands (in-app
         * settings UI + the DEBUG test intents, which MainActivity gates). */
        const val ACTION_SLEEP_SET = "GENESIS_SLEEP_SET"
        const val ACTION_XFADE_SET = "GENESIS_XFADE_SET"
        const val ACTION_AUTOPLAY_SET = "GENESIS_AUTOPLAY_SET"
        const val ACTION_QUEUE_DUMP = "GENESIS_QUEUE_DUMP"

        /** Live audio-effects controller, set when ExoPlayer's audio session attaches. */
        var fx: AudioFxController? = null
            private set
    }
}
