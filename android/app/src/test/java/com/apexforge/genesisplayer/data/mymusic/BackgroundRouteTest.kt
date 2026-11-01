package com.apexforge.genesisplayer.data.mymusic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BackgroundRouteTest {
    private val exact = mapOf(
        "youtube-a" to "offline:youtube-a",
        "youtube-b" to "crate:youtube-b",
        "youtube-c" to "alt:youtube-c",
        "youtube-d" to "offline:youtube-d"
    )

    private fun choose(keys: List<String>, index: Int) =
        BackgroundRoute.select(keys, index, { exact[it] }, {
            it == "offline:youtube-a" || it == "crate:youtube-b" || it == "offline:youtube-d"
        })

    @Test fun currentOfflineSongKeepsPlaying() {
        assertEquals(
            BackgroundQueue(listOf("offline:youtube-a", "crate:youtube-b", "offline:youtube-d")),
            choose(listOf("youtube-a", "youtube-b", "youtube-c", "youtube-d"), 0)
        )
    }

    @Test fun currentLicensedOnlineStreamWorks() {
        assertEquals(
            BackgroundQueue(listOf("crate:youtube-b", "offline:youtube-d")),
            choose(listOf("youtube-a", "youtube-b", "youtube-d"), 1)
        )
    }

    @Test fun unavailableCurrentVideoDoesNotSwitchToDifferentSong() {
        assertNull(choose(listOf("not-matched", "youtube-a"), 0))
    }

    @Test fun alternateAndUnplayableSourcesCannotSilentlyReplaceVideo() {
        assertNull(choose(listOf("youtube-c", "youtube-a"), 0))
    }

    @Test fun emptyOrBadIndexHasNoBackgroundRoute() {
        assertNull(choose(emptyList(), 0))
        assertNull(choose(listOf("youtube-a"), -1))
        assertNull(choose(listOf("youtube-a"), 4))
    }

    @Test fun currentTrackNeverResolvesToYouTubeEmbed() {
        val r = BackgroundRoute.select(listOf("youtube-a"), 0, { "youtube:$it" }, { true })
        assertNull(r)
    }
}
