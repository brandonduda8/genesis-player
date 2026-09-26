package com.apexforge.genesisplayer

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.apexforge.genesisplayer.data.CrossfadeMath
import com.apexforge.genesisplayer.data.HistoryStore
import com.apexforge.genesisplayer.data.SnapshotStore
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * BRKN Vibes waves 2–3: instrumented tests for the pure helpers. No
 * invented data — every assertion pins a real rule the service/UI relies on.
 */
@RunWith(AndroidJUnit4::class)
class BrknWavesTest {

    // ---- CrossfadeMath ----

    @Test
    fun crossfadeEngagesNearEnd() {
        assertTrue(CrossfadeMath.shouldEngage(
            positionMs = 175_000, durationMs = 180_000,
            xfadeS = 6f, hasNext = true, isPlaying = true
        ))
    }

    @Test
    fun crossfadeOffWhenZero() {
        assertFalse(CrossfadeMath.shouldEngage(
            positionMs = 179_000, durationMs = 180_000,
            xfadeS = 0f, hasNext = true, isPlaying = true
        ))
    }

    @Test
    fun crossfadeOffWhenNoNext() {
        assertFalse(CrossfadeMath.shouldEngage(
            positionMs = 179_000, durationMs = 180_000,
            xfadeS = 6f, hasNext = false, isPlaying = true
        ))
    }

    @Test
    fun crossfadeOffWhenPaused() {
        assertFalse(CrossfadeMath.shouldEngage(
            positionMs = 179_000, durationMs = 180_000,
            xfadeS = 6f, hasNext = true, isPlaying = false
        ))
    }

    @Test
    fun crossfadeOffWhenDurationUnknown() {
        assertFalse(CrossfadeMath.shouldEngage(
            positionMs = 10_000, durationMs = 0,
            xfadeS = 6f, hasNext = true, isPlaying = true
        ))
    }

    @Test
    fun crossfadeNotYetWhenFarFromEnd() {
        assertFalse(CrossfadeMath.shouldEngage(
            positionMs = 60_000, durationMs = 180_000,
            xfadeS = 6f, hasNext = true, isPlaying = true
        ))
    }

    // ---- QueueDump.format ----

    @Test
    fun queueDumpFormat() {
        assertEquals(
            "QueueDump: order [a,b,c] (current=1)",
            QueueDump.format(listOf("a", "b", "c"), 1)
        )
    }

    @Test
    fun queueDumpFormatEmpty() {
        assertEquals("QueueDump: order [] (current=-1)", QueueDump.format(emptyList(), -1))
    }

    // ---- SnapshotStore.parseSnapshot ----

    private fun snap(vararg pairs: Pair<String, Any?>): JSONObject {
        val o = JSONObject()
        pairs.forEach { (k, v) -> o.put(k, v) }
        return o
    }

    @Test
    fun snapshotParsesExactFields() {
        val root = snap(
            "version" to 3,
            "playlists" to org.json.JSONArray().put(
                snap(
                    "name" to "Machine Mix",
                    "tracks" to org.json.JSONArray().put(
                        snap(
                            "id" to "t1", "artist" to "A", "title" to "T",
                            "stream_url" to "https://x/1", "artwork_url" to "https://x/a",
                            "genre" to "dark trap"
                        )
                    )
                )
            ),
            "favorites" to org.json.JSONArray(),
            "recent_plays" to org.json.JSONArray().put(
                snap("track_id" to "t1", "artist" to "A", "title" to "T",
                    "played_at" to "2026-09-26T00:00:00Z")
            ),
            "apollo_suggestions" to org.json.JSONArray().put(
                snap("id" to "s1", "artist" to "B", "title" to "U",
                    "stream_url" to "", "artwork_url" to "", "why" to "fresh")
            )
        )
        val s = SnapshotStore.parseSnapshot(root)
        assertEquals(3, s.version)
        assertEquals(1, s.playlists.size)
        assertEquals("Machine Mix", s.playlists[0].name)
        assertEquals("dark trap", s.playlists[0].tracks[0].genre)
        assertTrue(s.favorites.isEmpty())
        assertEquals(1, s.recentPlays.size)
        assertEquals("t1", s.recentPlays[0].trackId)
        assertEquals(1, s.apolloSuggestions.size)
        assertEquals("fresh", s.apolloSuggestions[0].why)
    }

    @Test
    fun snapshotMissingArraysBecomeEmpty() {
        val s = SnapshotStore.parseSnapshot(snap("version" to 1))
        assertTrue(s.playlists.isEmpty())
        assertTrue(s.favorites.isEmpty())
        assertTrue(s.recentPlays.isEmpty())
        assertTrue(s.apolloSuggestions.isEmpty())
    }

    @Test
    fun snapshotTrackMissingFieldsUseHonestDefaults() {
        val root = snap(
            "version" to 1,
            "favorites" to org.json.JSONArray().put(snap("id" to "t9", "title" to "T9"))
        )
        val s = SnapshotStore.parseSnapshot(root)
        assertEquals("", s.favorites[0].streamUrl)
        assertEquals("Unknown", s.favorites[0].artist) // parser default, never invented content
    }

    // ---- HistoryStore recents: dedup + cap (pure helper; no I/O, no sync) ----

    @Test
    fun recentsDedupeAndCap() {
        var recents = emptyList<HistoryStore.RecentPlay>()
        // 25 distinct tracks: cap is 20, oldest 5 drop off.
        repeat(25) { recents = HistoryStore.pushRecent(recents, "track-$it", now = it.toLong()) }
        assertEquals(20, recents.size)
        assertEquals("track-24", recents.first().trackId)
        assertFalse(recents.any { it.trackId == "track-0" })
        // Re-play an old track: it moves to the head, no duplicate.
        recents = HistoryStore.pushRecent(recents, "track-10", now = 100L)
        assertEquals(20, recents.size)
        assertEquals("track-10", recents.first().trackId)
        assertEquals(1, recents.count { it.trackId == "track-10" })
    }
}
