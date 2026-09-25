package com.apexforge.genesisplayer

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.apexforge.genesisplayer.data.ApolloAction
import com.apexforge.genesisplayer.data.ApolloClient
import com.apexforge.genesisplayer.data.ApolloDrops
import com.apexforge.genesisplayer.data.ApolloStore
import com.apexforge.genesisplayer.data.ApolloStore.DecisionState
import com.apexforge.genesisplayer.data.ApolloStore.PendingDecision
import com.apexforge.genesisplayer.data.BrainCtx
import com.apexforge.genesisplayer.data.DropItem
import com.apexforge.genesisplayer.data.EmberArt
import com.apexforge.genesisplayer.data.Library
import com.apexforge.genesisplayer.data.LocalBrain
import com.apexforge.genesisplayer.data.Track
import com.apexforge.genesisplayer.data.TransportCommand
import com.apexforge.genesisplayer.data.VoiceCommand
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * API-34 instrumented tests for Genesis Player Phase 1 (Apollo).
 * Pins: error recovery, tap-downward queue order, bundled genre flow,
 * the scripted intent fixtures, voice transport, drops parsing, the
 * pending-decision state machine, and ember artwork determinism.
 */
@RunWith(AndroidJUnit4::class)
class ApolloPhase1Test {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    private val fixtureTracks = listOf(
        Track("t1", "Kxllswxtch", "Witch Drums", "https://stream/t1", "", 180, genre = "phonk"),
        Track("t2", "nothing,nowhere.", "Night Drive", "https://stream/t2", "", 200, genre = "ambient"),
        Track("t3", "Pouya", "Rage Beat", "https://stream/t3", "", 190, genre = "dark phonk"),
        Track("t4", "Ghostemane", "Venom", "https://stream/t4", "", 210, genre = "metal")
    )

    private fun brainCtx(
        tracks: List<Track> = fixtureTracks,
        nowPlayingId: String? = null,
        drops: List<DropItem> = emptyList(),
        disliked: Set<String> = emptySet()
    ) = BrainCtx(
        tracks = tracks,
        byId = tracks.associateBy { it.id },
        nowPlayingId = nowPlayingId,
        queueTailIds = emptyList(),
        likedArtists = emptySet(),
        likedGenres = emptySet(),
        dislikedIds = disliked,
        ratings = emptyMap(),
        playCounts = emptyMap(),
        drops = drops,
        catalogVersion = 6,
        pendingCount = 0,
        unsyncedRatings = 0
    )

    // ---- player-error recovery (AUDIT §2.1 defect 1) ----

    @Test
    fun errorsSkipWithUserVisibleMessageThenStopHonestly() {
        val guard = PlaybackErrorGuard()
        repeat(4) { i ->
            val d = guard.onError()
            assertTrue("error ${i + 1} must skip, not stall", d is PlaybackErrorGuard.Decision.Skip)
            assertTrue("skip must carry a user-visible message",
                (d as PlaybackErrorGuard.Decision.Skip).message.isNotBlank())
        }
        val fifth = guard.onError()
        assertTrue("5th consecutive error must stop honestly",
            fifth is PlaybackErrorGuard.Decision.Stop)
        assertTrue((fifth as PlaybackErrorGuard.Decision.Stop).message.isNotBlank())
    }

    @Test
    fun successResetsTheErrorCounter() {
        val guard = PlaybackErrorGuard()
        repeat(3) { guard.onError() }
        guard.onSuccess()
        repeat(4) {
            assertTrue(guard.onError() is PlaybackErrorGuard.Decision.Skip)
        }
        assertTrue("counter reset: the 5th AFTER a success stops",
            guard.onError() is PlaybackErrorGuard.Decision.Stop)
    }

    // ---- queue order: down from the tap, then wrap (AUDIT §2.1 defect 2) ----

    private class FakeControl : PlaybackQueue.PlayerControl {
        val calls = mutableListOf<String>()
        override fun setAndPlayFirst(item: MediaItem) {
            calls.add("first:${item.mediaId}")
        }
        override fun appendItems(items: List<MediaItem>) {
            calls.add("append:${items.joinToString(",") { it.mediaId }}")
        }
    }

    private fun item(id: String) =
        MediaItem.Builder().setMediaId(id).setUri("https://stream/$id").build()

    @Test
    fun queueContinuesDownwardFromTapThenWraps() {
        val control = FakeControl()
        val q = PlaybackQueue(
            resolve = { id -> item(id) },
            control = control,
            isStale = { false }
        )
        q.start(QueuePlan(listOf("t1", "t2", "t3", "t4", "t5"), "t3", 2))
        assertEquals("tapped track plays first", "first:t3", control.calls[0])
        val deadline = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < deadline &&
            control.calls.none { it.startsWith("append:") }
        ) Thread.sleep(50)
        val appended = control.calls.filter { it.startsWith("append:") }
            .joinToString(",").removePrefix("append:")
            .split(",").filter { it.isNotEmpty() }
        assertEquals("after the tap, playback continues DOWNWARD, then wraps",
            listOf("t4", "t5", "t1", "t2"), appended)
    }

    // ---- bundled genre survives applyJson (AUDIT §2.1 defect 3) ----

    @Test
    fun bundledGenreFlowsThroughApplyJson() {
        val root = JSONObject(
            """{"tracks":[{"id":"g1","artist":"A","title":"T","stream_url":"u",
               |"artwork_url":"","duration_s":180,"genre":"phonk","soundcloud_url":""}],
               |"playlists":[],"for_you":[]}""".trimMargin()
        )
        Library.applyJson(root)
        assertEquals("phonk", Library.track("g1")?.genre)
    }

    // ---- scripted intent fixtures (ROADMAP §1.1) ----

    @Test
    fun moodFixture_queuesBangers() {
        val plan = LocalBrain.resolve("play bangers", brainCtx())
        val q = plan.actions.single() as ApolloAction.QueueUpNext
        assertTrue(q.trackIds.containsAll(listOf("t1", "t3", "t4")))
        assertFalse("ambient must not ride a bangers queue", q.trackIds.contains("t2"))
        assertTrue(plan.reply.contains("bangers", ignoreCase = true))
        assertTrue("every Apollo pick needs a why-line",
            q.trackIds.all { plan.whyLines[it]?.isNotBlank() == true })
    }

    @Test
    fun artistFixture_playsKnownArtist() {
        val plan = LocalBrain.resolve("play kxllswxtch", brainCtx())
        val play = plan.actions.single() as ApolloAction.Play
        assertEquals("t1", play.trackId)
    }

    @Test
    fun discoveryFixture_surfacesVerifiedDropsOnly() {
        val drops = listOf(
            DropItem("d1", "Sematary", "Haunted Mound", "witch-house lane", "witch", "test")
        )
        val plan = LocalBrain.resolve("what's fresh", brainCtx(drops = drops))
        val d = plan.actions.single() as ApolloAction.Discover
        assertEquals(1, d.count)
        assertTrue(plan.reply.contains("Sematary"))
    }

    @Test
    fun energyFixture_matchesNowPlayingGenre() {
        val plan = LocalBrain.resolve("continue this energy", brainCtx(nowPlayingId = "t1"))
        val q = plan.actions.single() as ApolloAction.QueueUpNext
        assertTrue("dark phonk rides the phonk lane", q.trackIds.contains("t3"))
        assertFalse("now-playing is not queued behind itself", q.trackIds.contains("t1"))
    }

    @Test
    fun gibberishFixture_isHonestNeverInvented() {
        val plan = LocalBrain.resolve("xqzwj blorp fnord", brainCtx())
        assertTrue(plan.actions.single() is ApolloAction.Clarify)
        assertFalse("fallback must not claim understanding",
            plan.reply.contains("playing", ignoreCase = true) &&
                plan.reply.contains("for you", ignoreCase = true))
    }

    // ---- voice: transcribed strings drive the shared transport path ----

    @Test
    fun voiceTranscriptions_mapToTransportCommands() {
        assertEquals(TransportCommand.PAUSE, VoiceCommand.parseTransport("hold up"))
        assertEquals(TransportCommand.PAUSE, VoiceCommand.parseTransport("stop the music"))
        assertEquals(TransportCommand.RESUME, VoiceCommand.parseTransport("play"))
        assertEquals(TransportCommand.NEXT, VoiceCommand.parseTransport("next song"))
        assertEquals(TransportCommand.PREVIOUS, VoiceCommand.parseTransport("go back"))
        assertNull(VoiceCommand.parseTransport("do a barrel roll"))
    }

    @Test
    fun voicePath_drivesPlayerStateThroughLocalBrain() {
        val plan = LocalBrain.resolve("hold up", brainCtx())
        val t = plan.actions.single() as ApolloAction.Transport
        assertEquals(TransportCommand.PAUSE, t.command)
        assertEquals("Paused.", plan.reply)
    }

    // ---- machine-response parsing: the contract (APOLLO-LIVE §2.1) ----

    @Test
    fun machineOps_unknownIgnoredIdsValidatedQueueCapped() {
        val twelve = (1..12).map { "t$it" }
        val root = JSONObject(
            """{"reply":"Here you go","intent":{"type":"queue_up_next"},"actions":[
               |{"op":"queue_up_next","track_ids":["t1","ghost-id","t2"]},
               |{"op":"launch_the_moon"},
               |{"op":"play","track_id":"ghost-id"},
               |{"op":"mood_mode","exclude_genres":["Soft","CHILL"],"window_minutes":45}],
               |"pending":[]}""".trimMargin()
        )
        val turn = ApolloClient.parseMachineResponse(root, brainCtx())!!
        assertEquals("Here you go", turn.reply)
        assertEquals("unknown op ignored, ghost play dropped: 2 actions left",
            2, turn.actions.size)
        val q = turn.actions[0] as ApolloAction.QueueUpNext
        assertEquals("ghost-id dropped, catalog ids kept", listOf("t1", "t2"), q.trackIds)
        val gate = turn.actions[1] as ApolloAction.MoodGate
        assertEquals(setOf("soft", "chill"), gate.excludeGenres)
        assertEquals(45, gate.windowMinutes)

        val big = JSONObject(
            """{"reply":"big","actions":[{"op":"queue_up_next",
               |"track_ids":${twelve.joinToString(prefix = "[", postfix = "]") { "\"$it\"" }}}]}"""
                .trimMargin()
        )
        val byId = twelve.associateWith {
            Track(it, "A", "T", "u", "", 1)
        }
        val bigCtx = brainCtx(tracks = byId.values.toList())
        val bigTurn = ApolloClient.parseMachineResponse(big, bigCtx)!!
        val bigQ = bigTurn.actions.single() as ApolloAction.QueueUpNext
        assertEquals("queue_up_next caps at 10", 10, bigQ.trackIds.size)
    }

    @Test
    fun machineResponse_emptyReplyIsNull() {
        assertNull(ApolloClient.parseMachineResponse(JSONObject("{}"), brainCtx()))
    }

    // ---- drops parsing ----

    @Test
    fun dropsPayload_parsesShapesSkipsNonPending() {
        val root = JSONObject(
            """{"pending":[{"id":"d1","artist":"A","title":"T","why":"w","status":"pending"},
               |{"id":"d2","artist":"B","title":"U","status":"done"}],
               |"suggestions":[{"id":"d1","artist":"A","title":"T","why":"w"}],
               |"hunter_fresh":[{"id":"d3","artist":"C","title":"V","why":"x"}]}""".trimMargin()
        )
        val items = ApolloDrops.parseDropsPayload(root, "test")
        assertEquals(listOf("d1", "d3"), items.map { it.id })
        assertEquals("test", items[0].source)
    }

    @Test
    fun dropsPayload_parsesDecidedStatuses() {
        // NEVER-2 pin: only a real "published" status may ever flip a
        // SYNCING decision to PUBLISHED; "approved" must not.
        val root = JSONObject(
            """{"pending":[],
               |"decided":[{"id":"a1","status":"approved"},{"id":"p1","status":"published"},
               |{"id":"r1","status":"rejected"}]}""".trimMargin()
        )
        ApolloDrops.parseDropsPayload(root, "test")
        assertEquals("approved", ApolloDrops.decidedStatuses["a1"])
        assertEquals("published", ApolloDrops.decidedStatuses["p1"])
        assertEquals("rejected", ApolloDrops.decidedStatuses["r1"])
    }

    // ---- pending-decision state machine (APOLLO-LIVE §5) ----

    @Test
    fun decisionStateMachine_persistsLegalTransitions() {
        val id = "test-dec-${System.nanoTime()}"
        ApolloStore.putDecision(
            ctx,
            PendingDecision(id, "suggest_to_library", "A", "T", "w",
                DecisionState.AWAITING_TAP, System.currentTimeMillis())
        )
        fun state() = ApolloStore.decisions(ctx).find { it.id == id }!!.state
        assertEquals(DecisionState.AWAITING_TAP, state())
        listOf(DecisionState.RECORDED, DecisionState.SYNCING, DecisionState.PUBLISHED)
            .forEach { s ->
                ApolloStore.setDecisionState(ctx, id, s)
                assertEquals(s, state())
            }
        ApolloStore.removeDecision(ctx, id)
        assertNull(ApolloStore.decisions(ctx).find { it.id == id })
    }

    // ---- cached replies: last-20, honest offline banner backing ----

    @Test
    fun cachedReplies_keepLastTwenty() {
        repeat(25) { i -> ApolloStore.cacheReply(ctx, "q$i", "r$i", false) }
        val cached = ApolloStore.cachedReplies(ctx)
        assertEquals(20, cached.size)
        assertEquals("r24", cached.last().optString("reply"))
    }

    // ---- mood gate persistence ----

    @Test
    fun moodGate_gatesByGenreAndClears() {
        ApolloStore.setMoodGate(ctx, setOf("soft"), 60)
        assertTrue(ApolloStore.isGated(ctx, Track("x", "A", "T", "u", "", 1, genre = "Soft")))
        assertFalse(ApolloStore.isGated(ctx, Track("y", "A", "T", "u", "", 1, genre = "phonk")))
        ApolloStore.clearMoodGate(ctx)
        assertFalse(ApolloStore.isGated(ctx, Track("x", "A", "T", "u", "", 1, genre = "Soft")))
    }

    @Test
    fun wifiOnlyArtwork_roundTrips() {
        ApolloStore.setWifiOnlyArtwork(ctx, true)
        assertTrue(ApolloStore.wifiOnlyArtwork(ctx))
        ApolloStore.setWifiOnlyArtwork(ctx, false)
        assertFalse(ApolloStore.wifiOnlyArtwork(ctx))
    }

    // ---- ember artwork determinism (VISION.md §5) ----

    @Test
    fun emberArt_isDeterministicSeededAndVaried() {
        assertEquals(EmberArt.checksum("track-1", 64), EmberArt.checksum("track-1", 64))
        assertNotEquals(EmberArt.checksum("track-1", 64), EmberArt.checksum("track-2", 64))
        val bmp = EmberArt.renderBitmap("track-1", 64)
        assertEquals(64, bmp.width)
        assertEquals(64, bmp.height)
        val pixels = IntArray(64 * 64)
        bmp.getPixels(pixels, 0, 64, 0, 0, 64, 64)
        assertTrue("ember render must have real variance, never a flat tile",
            pixels.toSet().size > 16)
    }
}
