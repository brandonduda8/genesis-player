package com.apexforge.genesisplayer

import androidx.media3.common.MediaItem
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.apexforge.genesisplayer.data.Track
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase-1 scope addition: SHUFFLE EVERYTHING + MORE LIKE THIS.
 *
 * Pins: the full-catalog shuffle queue (dislikes excluded, first audio on
 * the tap-to-play path after ONE resolution, ≤10s) and the in-lane
 * more-like-this ranking (same artist/genre affinity, no dislikes, catalog
 * ids only). Predictive next-up is DEFERRED to Phase 2c — nothing here
 * blocks it: QueuePlanner stays the queue-order seam.
 */
@RunWith(AndroidJUnit4::class)
class ShuffleMoreLikeTest {

    private fun t(id: String, artist: String, genre: String) =
        Track(id, artist, "Title $id", "https://stream/$id", "", 180, genre = genre)

    private fun item(id: String) =
        MediaItem.Builder().setMediaId(id).setUri("https://stream/$id").build()

    private class FakeControl : PlaybackQueue.PlayerControl {
        val calls = mutableListOf<String>()
        override fun setAndPlayFirst(item: MediaItem) {
            calls.add("first:${item.mediaId}")
        }
        override fun appendItems(items: List<MediaItem>) {
            calls.add("append:${items.joinToString(",") { it.mediaId }}")
        }
    }

    // ---- SHUFFLE EVERYTHING ----

    @Test
    fun shuffleExcludesDislikesAndIsPermutation() {
        val ids = (1..10).map { "s$it" }
        val disliked = setOf("s3", "s7")
        val a = QueuePlanner.shuffleOrder(ids, { it in disliked }, seed = 42L)
        val b = QueuePlanner.shuffleOrder(ids, { it in disliked }, seed = 42L)
        assertEquals("same seed is deterministic", a, b)
        assertEquals("full non-disliked catalog queued", 8, a.size)
        assertTrue("no disliked track survives", a.none { it in disliked })
        assertEquals(
            "a permutation of the kept ids",
            (ids - disliked).toSet(), a.toSet()
        )
        assertTrue(
            "empty input stays empty",
            QueuePlanner.shuffleOrder(emptyList(), { false }, 1L).isEmpty()
        )
    }

    @Test
    fun shuffleAllCatalogQueue_firstAudioAfterOneResolution() {
        // 494-track catalog, 10 disliked — the Phase-1 catalog size.
        val ids = (1..494).map { "c$it" }
        val disliked = (1..494 step 50).map { "c$it" }.toSet()
        val shuffled = QueuePlanner.shuffleOrder(ids, { it in disliked }, seed = 7L)
        assertEquals("full catalog minus dislikes", 484, shuffled.size)
        val plan = QueuePlan(shuffled, shuffled.first(), 0)

        var resolveCount = 0
        var resolutionsAtFirstCall = -1
        val inner = FakeControl()
        val timed = object : PlaybackQueue.PlayerControl {
            override fun setAndPlayFirst(item: MediaItem) {
                resolutionsAtFirstCall = resolveCount
                inner.setAndPlayFirst(item)
            }
            override fun appendItems(items: List<MediaItem>) =
                inner.appendItems(items)
        }
        val q = PlaybackQueue(
            resolve = { id -> resolveCount++; item(id) },
            control = timed,
            isStale = { false }
        )
        val t0 = System.currentTimeMillis()
        q.start(plan)
        val elapsed = System.currentTimeMillis() - t0

        assertEquals(
            "tap-to-play: first audio after ONE resolution, not 494",
            1, resolutionsAtFirstCall
        )
        assertTrue("first-audio budget <= 10s", elapsed < 10_000)
        assertEquals(
            "first call is the shuffled head",
            "first:${shuffled.first()}", inner.calls[0]
        )
        val queued = inner.calls.filter { it.startsWith("append:") }
            .flatMap { it.removePrefix("append:").split(",") }
            .filter { it.isNotEmpty() }
        assertEquals("whole queue appended in shuffled order", shuffled.drop(1), queued)
        assertTrue("no disliked track in the live queue", queued.none { it in disliked })
    }

    // ---- MORE LIKE THIS ----

    @Test
    fun moreLikeThisBuildsInLaneQueue() {
        val tracks = listOf(
            t("seed", "Kxllswxtch", "phonk"),       // the playing track
            t("a1", "Kxllswxtch", "trap"),          // same artist +3
            t("a2", "Pouya", "phonk"),              // same genre +2
            t("a3", "Sematary", "dark phonk"),      // shared genre token +1
            t("a4", "Ghostemane", "ambient"),       // liked artist +1
            t("a5", "nothing,nowhere.", "ambient"), // no signal 0
            t("a6", "Kxllswxtch", "phonk")          // disliked — excluded
        )
        val byId = tracks.associateBy { it.id }
        val plan = QueuePlanner.moreLikeThis(
            seed = byId["seed"]!!,
            ids = tracks.map { it.id },
            isDisliked = { it == "a6" },
            likedArtists = setOf("Ghostemane"),
            likedGenres = emptySet(),
            trackOf = { byId[it] }
        )
        assertEquals(
            "same artist > same genre > token/liked > no signal",
            listOf("a1", "a2", "a3", "a4", "a5"), plan.orderedIds
        )
        assertEquals("first in-lane track leads", "a1", plan.startId)
        assertEquals("four tracks scored above zero", 4, plan.boostedCount)
        assertTrue("seed never queues itself", "seed" !in plan.orderedIds)
        assertTrue("dislikes never queue", "a6" !in plan.orderedIds)
    }

    @Test
    fun moreLikeThisOnlyUsesCatalogIds() {
        // trackOf returns null for unknown ids — they never enter the queue.
        val seed = t("seed", "Kxllswxtch", "phonk")
        val known = t("k1", "Kxllswxtch", "phonk")
        val plan = QueuePlanner.moreLikeThis(
            seed = seed,
            ids = listOf("k1", "ghost-id"),
            isDisliked = { false },
            likedArtists = emptySet(),
            likedGenres = emptySet(),
            trackOf = { if (it == "k1") known else null }
        )
        assertEquals(listOf("k1"), plan.orderedIds)
    }

    @Test
    fun moreLikeThisEmptyPoolIsHonest() {
        val seed = t("seed", "Kxllswxtch", "phonk")
        val byId = mapOf(seed.id to seed)
        val plan = QueuePlanner.moreLikeThis(
            seed = seed,
            ids = listOf(seed.id),
            isDisliked = { false },
            likedArtists = emptySet(),
            likedGenres = emptySet(),
            trackOf = { byId[it] }
        )
        assertTrue(plan.orderedIds.isEmpty())
        assertNull("no fake first track when the pool is empty", plan.startId)
    }
}
