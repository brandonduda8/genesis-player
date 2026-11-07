package com.apexforge.genesisplayer.data.playlists

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayRouterTest {
    // a, b: Aurum (a offline, b exact stream); y1, y2: YouTube-only; gone: nowhere.
    private val aurum = mapOf("a" to "offline:a", "b" to "crate:b")
    private val yt = setOf("a", "b", "y1", "y2")
    private fun aid(k: String) = aurum[k]
    private fun off(id: String) = id.startsWith("offline:")
    private fun onYt(k: String) = k in yt
    private val mix = listOf("y1", "a", "y2", "b", "gone")

    private fun plan(keys: List<String>, start: Int?, online: Boolean = true, shuffle: Boolean = false) =
        PlayRouter.plan(keys, start, online, shuffle, ::aid, ::off, ::onYt, random = java.util.Random(7))

    @Test fun wholeListPlayPrefersAurumAndCountsWhatItLeavesOut() {
        val p = plan(mix, null)
        assertEquals(PlaylistPlayback.Route.AURUM, p.route)
        assertEquals(listOf("offline:a", "crate:b"), p.ids)
        assertEquals(3, p.skipped)
        assertTrue(PlayRouter.notice(p, true)!!.contains("3 are YouTube-only"))
    }

    @Test fun wholeListShuffleStillStartsOnAnAurumSong() {
        repeat(20) { seed ->
            val p = PlayRouter.plan(mix, null, true, true, ::aid, ::off, ::onYt, random = java.util.Random(seed.toLong()))
            assertEquals(PlaylistPlayback.Route.AURUM, p.route)
            assertEquals(setOf("offline:a", "crate:b"), p.ids.toSet())
            assertEquals(0, p.index)
        }
    }

    @Test fun allYouTubeListIsLabeledNeverPresentedAsBackgroundSafe() {
        val p = plan(listOf("y1", "y2"), null)
        assertEquals(PlaylistPlayback.Route.YOUTUBE, p.route)
        assertEquals(listOf("y1", "y2"), p.ids)
        assertTrue(PlayRouter.notice(p, true)!!.startsWith(PlayRouter.YOUTUBE_LABEL))
    }

    @Test fun tappedSongPlaysWhereItLives() {
        assertEquals(PlaylistPlayback.Route.AURUM, plan(mix, 1).route)
        val y = plan(mix, 0)
        assertEquals(PlaylistPlayback.Route.YOUTUBE, y.route)
        assertEquals("y1", y.ids[y.index])
        assertEquals(PlaylistPlayback.Route.NONE, plan(mix, 4).route)
    }

    @Test fun offlineWholeListPlaysOnlyTrueOfflineCopies() {
        val p = plan(mix, null, online = false)
        assertEquals(PlaylistPlayback.Route.AURUM, p.route)
        assertEquals(listOf("offline:a"), p.ids)
        assertEquals(PlaylistPlayback.Route.NONE, plan(listOf("y1", "b"), null, online = false).route)
    }

    @Test fun duplicateKeysDoNotDoubleCountOrBreakTheTappedIndex() {
        val p = plan(listOf("a", "y1", "a", "b"), 2)
        assertEquals(PlaylistPlayback.Route.AURUM, p.route)
        assertEquals("offline:a", p.ids[p.index])
        assertEquals(PlayRouter.coverage(listOf("a", "a", "y1"), ::aid, ::off, ::onYt).total, 2)
    }

    @Test fun coverageIsCountedHonestly() {
        val c = PlayRouter.coverage(mix, ::aid, ::off, ::onYt)
        assertEquals(PlayRouter.Coverage(5, 2, 1, 2, 1), c)
        assertEquals(40, c.percent)
        assertTrue(c.line(), c.line().startsWith("2 of 5 songs play in Aurum's engine"))
        assertTrue(c.line().contains("2 YouTube-only"))
        assertEquals("No songs yet", PlayRouter.coverage(emptyList(), ::aid, ::off, ::onYt).line())
    }

    @Test fun engineOfPrefersAurum() {
        assertEquals(PlayRouter.Engine.AURUM, PlayRouter.engineOf("a", ::aid, ::onYt))
        assertEquals(PlayRouter.Engine.YOUTUBE_ONLY, PlayRouter.engineOf("y1", ::aid, ::onYt))
        assertEquals(PlayRouter.Engine.UNAVAILABLE, PlayRouter.engineOf("gone", ::aid, ::onYt))
    }

    @Test fun handoffUrlIsTheOfficialQueueOfValidIdsCappedAt50() {
        val ids = (0 until 60).map { "vid" + it.toString().padStart(8, '0') }
        val u = PlayRouter.handoffUrl(listOf("bad id!") + ids, from = 0)!!
        assertTrue(u.startsWith("https://www.youtube.com/watch_videos?video_ids=vid00000000,"))
        assertEquals(50, u.substringAfter("=").split(",").size)
        assertTrue(PlayRouter.handoffUrl(ids, from = 55)!!.endsWith("vid00000059"))
        assertNull(PlayRouter.handoffUrl(listOf("x")))
    }
}
