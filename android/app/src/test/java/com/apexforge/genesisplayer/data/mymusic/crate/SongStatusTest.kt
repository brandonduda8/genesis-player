package com.apexforge.genesisplayer.data.mymusic.crate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SongStatusTest {
    private fun cand(verified: Boolean = true, dl: Boolean = false) =
        Candidate(Source.AUDIUS, "1", "Song", "Artist", verified, "https://s", if (dl) "https://d" else "", "x")

    @Test fun anOfflineFileAlwaysWins() {
        assertEquals(SongStatus.OFFLINE, SongStatus.of(true, null))
        assertEquals(SongStatus.OFFLINE, SongStatus.of(true, CrateMatch("k", MatchKind.UNAVAILABLE, null, "")))
        assertTrue(SongStatus.OFFLINE.worksOffline)
    }

    @Test fun onlyTheExactRecordingWithPermissionCanBeSaved() {
        assertEquals(SongStatus.DOWNLOADABLE, SongStatus.of(false, CrateMatch("k", MatchKind.EXACT, cand(dl = true), "")))
        assertEquals(SongStatus.ONLINE_EXACT, SongStatus.of(false, CrateMatch("k", MatchKind.EXACT, cand(verified = false, dl = true), "")))
        // an alternate version is never offered for the offline vault, even when its uploader allows downloads
        assertEquals(SongStatus.ALTERNATE, SongStatus.of(false, CrateMatch("k", MatchKind.ALTERNATE, cand(dl = true), "")))
    }

    @Test fun statusesAreHonestAboutTheInternet() {
        assertEquals(SongStatus.YOUTUBE, SongStatus.of(false, CrateMatch("k", MatchKind.UNAVAILABLE, null, "")))
        assertEquals(SongStatus.NOT_CHECKED, SongStatus.of(false, null))
        listOf(SongStatus.DOWNLOADABLE, SongStatus.ONLINE_EXACT, SongStatus.ALTERNATE, SongStatus.YOUTUBE, SongStatus.NOT_CHECKED)
            .forEach { assertFalse(it.name, it.worksOffline) }
        assertFalse(SongStatus.ALTERNATE.playsInAurum)
        assertFalse(SongStatus.YOUTUBE.playsInAurum)
    }
}
