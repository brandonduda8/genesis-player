package com.apexforge.genesisplayer

import com.apexforge.genesisplayer.data.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * JVM fast lane (no emulator): edge cases for the pure [QueuePlanner] and
 * [QueueDump]. The API-34 QueuePlannerTest keeps the end-to-end tap pins;
 * these cover the filter/boost/ranking corners in seconds.
 */
class QueuePlannerUnitTest {

    private fun track(id: String, artist: String = "Artist", genre: String = "") =
        Track(id, artist, "Title $id", "https://stream/$id", "", 180, genre)

    private fun plan(
        ids: List<String>,
        startIndex: Int,
        disliked: Set<String> = emptySet(),
        likedArtists: Set<String> = emptySet(),
        likedGenres: Set<String> = emptySet(),
        tracks: Map<String, Track> = ids.associateWith { track(it) }
    ) = QueuePlanner.plan(ids, startIndex, { it in disliked }, likedArtists, likedGenres) {
        tracks[it]
    }

    // ---- plan() ----

    @Test
    fun dislikedTapJumpsToNearestSurvivorAfterTap() {
        val p = plan(listOf("a", "b", "c", "d"), startIndex = 1, disliked = setOf("b"))
        assertEquals(listOf("a", "c", "d"), p.orderedIds)
        assertEquals("c", p.startId)
    }

    @Test
    fun dislikedLastTapFallsBackToQueueHead() {
        val p = plan(listOf("a", "b", "c"), startIndex = 2, disliked = setOf("c"))
        assertEquals("a", p.startId)
    }

    @Test
    fun outOfRangeTapIndexesAreSafe() {
        assertEquals("a", plan(listOf("a", "b"), startIndex = -3).startId)
        assertEquals("a", plan(listOf("a", "b"), startIndex = 99).startId)
    }

    @Test
    fun everythingDislikedYieldsEmptyPlan() {
        val p = plan(listOf("a", "b"), startIndex = 0, disliked = setOf("a", "b"))
        assertEquals(emptyList<String>(), p.orderedIds)
        assertNull(p.startId)
        assertEquals(0, p.boostedCount)
    }

    @Test
    fun likedArtistsMoveToFrontKeepingRelativeOrder() {
        val ids = listOf("a", "b", "c", "d")
        val tracks = mapOf(
            "a" to track("a"), "b" to track("b", artist = "X"),
            "c" to track("c"), "d" to track("d", artist = "X")
        )
        val p = plan(ids, startIndex = 0, likedArtists = setOf("X"), tracks = tracks)
        assertEquals(listOf("b", "d", "a", "c"), p.orderedIds)
        assertEquals(2, p.boostedCount)
        assertEquals("a", p.startId) // tap still wins over boost order
    }

    @Test
    fun emptyGenreNeverMatchesLikedGenres() {
        val p = plan(listOf("a", "b"), startIndex = 0, likedGenres = setOf(""))
        assertEquals(0, p.boostedCount)
        assertEquals(listOf("a", "b"), p.orderedIds)
    }

    @Test
    fun unknownTrackIsKeptButNeverBoosted() {
        val p = plan(
            listOf("a", "ghost"), startIndex = 0, likedArtists = setOf("Artist"),
            tracks = mapOf("a" to track("a"))
        )
        assertEquals(listOf("a", "ghost"), p.orderedIds)
        assertEquals(1, p.boostedCount)
    }

    // ---- shuffleOrder() ----

    @Test
    fun shuffleIsSeedDeterministicPermutationWithoutDislikes() {
        val ids = (1..40).map { "t$it" }
        val disliked = setOf("t3", "t17")
        val a = QueuePlanner.shuffleOrder(ids, { it in disliked }, seed = 42L)
        val b = QueuePlanner.shuffleOrder(ids, { it in disliked }, seed = 42L)
        val c = QueuePlanner.shuffleOrder(ids, { it in disliked }, seed = 43L)
        assertEquals(a, b)
        assertNotEquals(a, c)
        assertEquals((ids - disliked).toSet(), a.toSet())
        assertEquals(38, a.size)
    }

    // ---- moreLikeThis() ----

    @Test
    fun moreLikeThisRanksByScoreThenCatalogOrder() {
        val seed = track("s", artist = "A", genre = "phonk")
        val tracks = listOf(
            track("t4", artist = "D", genre = "jazz"),       // 0
            track("t1", artist = "B", genre = "dark phonk"), // token +1
            seed,
            track("t3", artist = "C", genre = "phonk"),      // genre +2
            track("t2", artist = "A", genre = "trap"),       // artist +3
            track("t5", artist = "A", genre = "phonk"),      // disliked
            track("t7", artist = "a", genre = "")            // artist +3 (case-insensitive)
        ).associateBy { it.id }
        val ids = listOf("t4", "t1", "s", "t3", "t2", "t5", "ghost", "t7")
        val p = QueuePlanner.moreLikeThis(
            seed, ids, { it == "t5" }, emptySet(), emptySet()
        ) { tracks[it] }
        assertEquals(listOf("t2", "t7", "t3", "t1", "t4"), p.orderedIds)
        assertEquals("t2", p.startId)
        assertEquals(4, p.boostedCount)
    }

    @Test
    fun moreLikeThisLikedSignalsBreakOtherwiseEqualScores() {
        val seed = track("s", artist = "A", genre = "rock")
        val tracks = listOf(
            track("x", artist = "P", genre = "jazz"),
            track("y", artist = "Q", genre = "jazz")
        ).associateBy { it.id }
        val p = QueuePlanner.moreLikeThis(
            seed, listOf("x", "y"), { false }, setOf("Q"), emptySet()
        ) { tracks[it] }
        assertEquals(listOf("y", "x"), p.orderedIds)
        assertEquals(1, p.boostedCount)
    }

    // ---- QueueDump ----

    @Test
    fun queueDumpFormatIsStable() {
        assertEquals(
            "QueueDump: order [a,b,c] (current=1)",
            QueueDump.format(listOf("a", "b", "c"), 1)
        )
    }
}
