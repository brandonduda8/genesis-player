package com.apexforge.genesisplayer

import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.apexforge.genesisplayer.data.Track
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * WO-CLOUD-002 (emulator queue): the tap-to-play pipeline end to end on a
 * REAL ExoPlayer with real MediaItems — QueuePlanner.plan -> PlaybackQueue
 * .start -> the player's actual playlist — plus the SoundCloud refresh swap
 * (SoundCloudRefresher.applyAt) the service runs at track transitions.
 *
 * The control applies each call to the player synchronously on the main
 * thread (the service posts to main; same calls, same order). The player is
 * never prepared, so nothing touches the network.
 *
 * Groups: (1) disliked-tap fallback + re-plan, (2) out-of-range taps,
 * (3) boost ordering + deterministic tie-breaks, (4) refresh keeps queue
 * position with no duplicates. Each test is shown to fail under a planted
 * mutation (see the PR description).
 */
@RunWith(AndroidJUnit4::class)
class QueueTapPipelineTest {

    private val inst = InstrumentationRegistry.getInstrumentation()
    private lateinit var player: ExoPlayer

    @Before
    fun setUp() {
        inst.runOnMainSync {
            player = ExoPlayer.Builder(ApplicationProvider.getApplicationContext()).build()
        }
    }

    @After
    fun tearDown() {
        inst.runOnMainSync { player.release() }
    }

    // ---------------------------------------------------------------- helpers

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        inst.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private fun track(id: String, artist: String = "Artist", genre: String = "") =
        Track(id, artist, "Title $id", "https://stream/$id", "", 180, genre)

    private fun scTrack(id: String) = Track(
        id, "Tha Aadity", "SC $id", "", "", 180,
        soundcloudUrl = "https://soundcloud.com/tha-aadity/$id"
    )

    private fun item(id: String) =
        MediaItem.Builder().setMediaId(id).setUri("https://stream/$id").build()

    private fun queueIds(): List<String> =
        onMain { (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId } }

    private fun uriAt(i: Int): String =
        onMain { player.getMediaItemAt(i).localConfiguration?.uri.toString() }

    private fun currentIndex(): Int = onMain { player.currentMediaItemIndex }
    private fun position(): Long = onMain { player.currentPosition }

    /** Mirrors PlayerService.launchQueue's control, minus prepare/play. */
    private inner class PlayerControl : PlaybackQueue.PlayerControl {
        override fun setAndPlayFirst(item: MediaItem) =
            inst.runOnMainSync { player.setMediaItems(listOf(item), 0, 0L) }

        override fun appendItems(items: List<MediaItem>) =
            inst.runOnMainSync { player.addMediaItems(items) }
    }

    private fun plan(
        ids: List<String>,
        startIndex: Int,
        disliked: Set<String> = emptySet(),
        likedArtists: Set<String> = emptySet(),
        likedGenres: Set<String> = emptySet(),
        tracks: Map<String, Track> = ids.associateWith { track(it) }
    ): QueuePlan = QueuePlanner.plan(
        ids, startIndex, { it in disliked }, likedArtists, likedGenres
    ) { tracks[it] }

    /** Runs the queue into the real player; batch size 2 exercises multi-batch appends. */
    private fun launch(
        plan: QueuePlan,
        unresolvable: Set<String> = emptySet(),
        isStale: () -> Boolean = { false }
    ) = PlaybackQueue(
        resolve = { id -> if (id in unresolvable) null else item(id) },
        control = PlayerControl(),
        isStale = isStale
    ).start(plan, batchSize = 2)

    private fun assertNoDuplicates(ids: List<String>) =
        assertEquals("queue has duplicates: $ids", ids.size, ids.toSet().size)

    // ------------------------------------- (1) disliked-tap fallback, re-plan

    @Test
    fun dislikedTap_playsNearestSurvivor_everySurvivorQueuedOnce() {
        val ids = listOf("a", "b", "c", "d", "e", "f")
        val p = plan(ids, startIndex = 2, disliked = setOf("c", "e"))
        launch(p)
        assertEquals(listOf("d", "f", "a", "b"), queueIds())
        assertEquals(0, currentIndex())
        assertTrue(queueIds().none { it == "c" || it == "e" })
        assertNoDuplicates(queueIds())
    }

    @Test
    fun dislikedTap_nextSurvivorUnresolvable_walksForwardWithoutGaps() {
        val ids = listOf("a", "b", "c", "d", "e")
        val p = plan(ids, startIndex = 2, disliked = setOf("c"))
        launch(p, unresolvable = setOf("d"))
        // c is disliked, d can't resolve -> e plays; then wrap to the head.
        assertEquals(listOf("e", "a", "b"), queueIds())
        assertNoDuplicates(queueIds())
    }

    @Test
    fun retapWhileQueueing_staleQueueStopsAndNewQueueReplacesIt() {
        var resolved = 0
        val first = plan(listOf("q1", "q2", "q3", "q4", "q5", "q6"), startIndex = 0)
        PlaybackQueue(
            resolve = { id -> resolved++; item(id) },
            control = PlayerControl(),
            isStale = { resolved >= 3 } // user re-taps after 3 resolutions
        ).start(first, batchSize = 2)
        assertEquals("stale queue must stop appending",
            listOf("q1", "q2", "q3"), queueIds())

        launch(plan(listOf("n1", "n2", "n3"), startIndex = 1))
        assertEquals(listOf("n2", "n3", "n1"), queueIds())
        assertEquals(0, currentIndex())
    }

    // ------------------------------------------------- (2) out-of-range taps

    @Test
    fun tapPastEnd_startsAtFirstSurvivor() {
        val p = plan(listOf("a", "b", "c"), startIndex = 99, disliked = setOf("a"))
        assertEquals("b", p.startId)
        launch(p)
        assertEquals(listOf("b", "c"), queueIds())
    }

    @Test
    fun negativeTap_doesNotCrash_startsAtHead() {
        val p = plan(listOf("a", "b", "c"), startIndex = -5)
        assertEquals("a", p.startId)
        launch(p)
        assertEquals(listOf("a", "b", "c"), queueIds())
    }

    @Test
    fun planStartNotInOrder_playsFromHead() {
        launch(QueuePlan(listOf("a", "b", "c"), startId = "zzz", boostedCount = 0))
        assertEquals(listOf("a", "b", "c"), queueIds())
    }

    @Test
    fun emptyOrAllDislikedTap_leavesPlayerUntouched() {
        onMain { player.setMediaItems(listOf(item("x")), 0, 0L) }

        val empty = plan(emptyList(), startIndex = 3)
        assertNull(empty.startId)
        launch(empty)
        assertEquals(listOf("x"), queueIds())

        val allOut = plan(listOf("a", "b"), startIndex = 7, disliked = setOf("a", "b"))
        assertNull(allOut.startId)
        launch(allOut)
        assertEquals(listOf("x"), queueIds())
    }

    // ------------------------------- (3) boost ordering, deterministic ties

    @Test
    fun boostedTracksKeepCatalogOrder_andQueueRotatesFromTap() {
        val ids = listOf("t1", "t2", "t3", "t4", "t5", "t6")
        val tracks = mapOf(
            "t1" to track("t1"),
            "t2" to track("t2", artist = "X"),
            "t3" to track("t3"),
            "t4" to track("t4", genre = "phonk"),
            "t5" to track("t5", artist = "X"),
            "t6" to track("t6")
        )
        val p = plan(ids, startIndex = 2, likedArtists = setOf("X"),
            likedGenres = setOf("phonk"), tracks = tracks)
        assertEquals(listOf("t2", "t4", "t5", "t1", "t3", "t6"), p.orderedIds)
        assertEquals(3, p.boostedCount)
        assertEquals("t3", p.startId)
        launch(p)
        assertEquals(listOf("t3", "t6", "t2", "t4", "t5", "t1"), queueIds())
        assertNoDuplicates(queueIds())
    }

    @Test
    fun moreLikeThisTiesBreakByCatalogPosition_identicalOnEveryRun() {
        val seed = track("s", artist = "A", genre = "phonk")
        val tracks = listOf(
            seed,
            track("c1", artist = "B", genre = "jazz"),
            track("c2", artist = "C", genre = "trap"),
            track("c3", artist = "A", genre = "rock"),
            track("c4", artist = "D", genre = "rock"),
            track("c5", artist = "A", genre = "folk")
        ).associateBy { it.id }
        val ids = listOf("s", "c1", "c2", "c3", "c4", "c5")
        fun run() = QueuePlanner.moreLikeThis(
            seed, ids, { false }, emptySet(), emptySet()
        ) { tracks[it] }

        val p = run()
        assertEquals(listOf("c3", "c5", "c1", "c2", "c4"), p.orderedIds)
        repeat(5) { assertEquals(p, run()) }
        launch(p)
        assertEquals(listOf("c3", "c5", "c1", "c2", "c4"), queueIds())
    }

    // ------------------------- (4) SoundCloud refresh keeps queue position

    private fun freshFor(id: String): MediaItem {
        val current = MediaItem.Builder().setMediaId(id)
            .setUri("https://cf-media.sndcdn.com/old-$id").build()
        return SoundCloudRefresher.refresh(current, scTrack(id)) {
            "https://cf-media.sndcdn.com/fresh-$id"
        }!!
    }

    private fun load(ids: List<String>, index: Int, positionMs: Long) = onMain {
        player.setMediaItems(ids.map {
            MediaItem.Builder().setMediaId(it).setUri("https://cf-media.sndcdn.com/old-$it").build()
        }, index, positionMs)
    }

    @Test
    fun refreshCurrent_keepsIndexPositionAndLength() {
        load(listOf("a", "sc", "b"), index = 1, positionMs = 42_000L)
        assertTrue(onMain { SoundCloudRefresher.applyAt(player, 1, "sc", freshFor("sc")) })
        assertEquals(listOf("a", "sc", "b"), queueIds())
        assertEquals("https://cf-media.sndcdn.com/fresh-sc", uriAt(1))
        assertEquals(1, currentIndex())
        assertEquals(42_000L, position())
    }

    @Test
    fun refreshUpcoming_leavesCurrentItemAndPositionAlone() {
        load(listOf("a", "b", "sc"), index = 1, positionMs = 7_000L)
        assertTrue(onMain { SoundCloudRefresher.applyAt(player, 2, "sc", freshFor("sc")) })
        assertEquals(listOf("a", "b", "sc"), queueIds())
        assertEquals("https://cf-media.sndcdn.com/fresh-sc", uriAt(2))
        assertEquals(1, currentIndex())
        assertEquals(7_000L, position())
    }

    @Test
    fun refreshBothCurrentAndNext_noDuplicatesPositionKept() {
        load(listOf("sc1", "sc2", "a"), index = 0, positionMs = 5_000L)
        assertTrue(onMain { SoundCloudRefresher.applyAt(player, 0, "sc1", freshFor("sc1")) })
        assertTrue(onMain { SoundCloudRefresher.applyAt(player, 1, "sc2", freshFor("sc2")) })
        assertEquals(listOf("sc1", "sc2", "a"), queueIds())
        assertNoDuplicates(queueIds())
        assertEquals(0, currentIndex())
        assertEquals(5_000L, position())
    }

    @Test
    fun slotNoLongerHoldsTrack_noSwap() {
        load(listOf("a", "b", "c"), index = 0, positionMs = 0L)
        assertFalse(onMain { SoundCloudRefresher.applyAt(player, 1, "sc", freshFor("sc")) })
        assertEquals(listOf("a", "b", "c"), queueIds())
        assertEquals("https://cf-media.sndcdn.com/old-b", uriAt(1))
    }

    @Test
    fun queueShrankBelowIndex_noSwapNoCrash() {
        load(listOf("a"), index = 0, positionMs = 0L)
        assertFalse(onMain { SoundCloudRefresher.applyAt(player, 3, "sc", freshFor("sc")) })
        assertFalse(onMain { SoundCloudRefresher.applyAt(player, -1, "sc", freshFor("sc")) })
        assertEquals(listOf("a"), queueIds())
    }
}
