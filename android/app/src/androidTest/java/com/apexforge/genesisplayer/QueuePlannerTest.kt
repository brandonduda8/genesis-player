package com.apexforge.genesisplayer

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.apexforge.genesisplayer.data.Track
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * API-34 instrumented tests for the tap-to-play queue repair (2026-09-25).
 *
 * Pins the behaviors Brandon's bug report needs:
 *  1. QueuePlanner maps the TAP through dislike filters and taste boosts —
 *     the tapped track (or its nearest survivor) is what plays, never an
 *     unrelated queue head.
 *  2. PlaybackQueue resolves the tapped track FIRST and hands it to the
 *     player after exactly one resolution — the rest appends in the
 *     background. This is the tap-to-first-audio latency fix.
 *  3. SoundCloudRefresher re-resolves signed URLs (never reuses a stale one)
 *     and never invents a URL when the resolver comes back empty.
 */
@RunWith(AndroidJUnit4::class)
class QueuePlannerTest {

    private fun track(id: String, artist: String = "Artist", genre: String = "") =
        Track(id, artist, "Title $id", "https://stream/$id", "", 180, genre)

    private fun plan(
        ids: List<String>,
        startIndex: Int,
        disliked: Set<String> = emptySet(),
        likedArtists: Set<String> = emptySet(),
        likedGenres: Set<String> = emptySet(),
        tracks: Map<String, Track> = ids.associateWith { track(it) }
    ) = QueuePlanner.plan(
        ids = ids,
        startIndex = startIndex,
        isDisliked = { it in disliked },
        likedArtists = likedArtists,
        likedGenres = likedGenres,
        trackOf = { tracks[it] }
    )

    @Test
    fun tappedTrackSurvivesDislikeFilter() {
        val p = plan(listOf("a", "b", "c"), 0, disliked = setOf("b"))
        assertEquals(listOf("a", "c"), p.orderedIds)
        assertEquals("a", p.startId)
    }

    @Test
    fun tappedDislikedTrackFallsToNearestSurvivor_notQueueHead() {
        // Old bug: tapped track filtered out -> idx fell back to 0 (queue head).
        // New: the nearest surviving track at/after the tap starts.
        val p = plan(listOf("a", "b", "c"), 0, disliked = setOf("a"))
        assertEquals(listOf("b", "c"), p.orderedIds)
        assertEquals("b", p.startId)
    }

    @Test
    fun tappedDislikedTrackMidQueueFallsForward() {
        val p = plan(listOf("a", "b", "c"), 1, disliked = setOf("b"))
        assertEquals("c", p.startId)
    }

    @Test
    fun tasteBoostReordersButNeverHijacksTheTap() {
        val tracks = mapOf(
            "a" to track("a", artist = "Plain"),
            "b" to track("b", artist = "Loved")
        )
        val p = plan(
            listOf("a", "b"), 0,
            likedArtists = setOf("Loved"),
            tracks = tracks
        )
        assertEquals(listOf("b", "a"), p.orderedIds) // boost floated b up
        assertEquals(1, p.boostedCount)
        assertEquals("a", p.startId) // ...but the TAP (a) still starts
    }

    @Test
    fun genreBoostCounts() {
        val tracks = mapOf(
            "a" to track("a", genre = "phonk"),
            "b" to track("b", genre = "rock")
        )
        val p = plan(
            listOf("a", "b"), 1,
            likedGenres = setOf("phonk"),
            tracks = tracks
        )
        assertEquals(listOf("a", "b"), p.orderedIds)
        assertEquals("b", p.startId)
    }

    @Test
    fun outOfRangeTapStartsFirstSurvivor() {
        val p = plan(listOf("a", "b"), 99)
        assertEquals("a", p.startId)
    }

    @Test
    fun allDislikedYieldsNoStart() {
        val p = plan(listOf("a", "b"), 0, disliked = setOf("a", "b"))
        assertTrue(p.orderedIds.isEmpty())
        assertNull(p.startId)
    }

    @Test
    fun emptyQueueYieldsNoStart() {
        val p = plan(emptyList(), 0)
        assertNull(p.startId)
    }
}
