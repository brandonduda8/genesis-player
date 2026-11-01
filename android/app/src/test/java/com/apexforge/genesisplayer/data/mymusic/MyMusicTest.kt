package com.apexforge.genesisplayer.data.mymusic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.ZoneOffset
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Synthetic Takeout fixtures with neutral names. The owner's real history never enters the repo. */
class MyMusicTest {
    private fun entry(header: String, title: String, vid: String?, channel: String?, time: String, ad: Boolean = false) =
        """{"header":"$header","title":"Watched $title",""" +
            (vid?.let { "\"titleUrl\":\"https://music.youtube.com/watch?v=$it\"," } ?: "") +
            (channel?.let { "\"subtitles\":[{\"name\":\"$it\"}]," } ?: "") +
            (if (ad) "\"details\":[{\"name\":\"From Google Ads\"}]," else "") +
            "\"time\":\"$time\",\"products\":[\"YouTube\"]}"

    private val watch = "[" + listOf(
        entry("YouTube Music", "Song A", "aaaaaaaaaaa", "Artist One - Topic", "2026-10-30T20:00:00Z"),
        entry("YouTube Music", "Song B", "bbbbbbbbbbb", "Artist One - Topic", "2026-10-30T20:03:00Z"),
        entry("YouTube", "Artist One - Song A (Official Music Video)", "aaaaaaaa2aa", "ArtistOneVEVO", "2026-10-30T20:06:00Z"),
        entry("YouTube", "How to fix a bike", "ccccccccccc", "Bike Channel", "2026-10-30T21:00:00Z"),
        entry("YouTube", "Ad", "ddddddddddd", "Sponsor", "2026-10-30T21:01:00Z", ad = true),
        entry("YouTube Music", "Removed", null, null, "2026-10-30T21:02:00Z"),
        entry("YouTube Music", "Private", "eeeeeeeeeee", null, "2026-10-30T21:03:00Z"),
        entry("YouTube", "Song B", "bbbbbbbbbbb", "Some Reupload", "2026-07-01T20:00:00Z"),
        entry("YouTube Music", "Song C", "fffffffffff", "Artist Two - Topic", "2026-06-01T09:00:00Z"),
        entry("YouTube Music", "Song C", "fffffffffff", "Artist Two - Topic", "2026-06-02T09:00:00Z"),
        entry("YouTube Music", "Song C", "fffffffffff", "Artist Two - Topic", "2026-06-03T09:00:00Z")
    ).joinToString(",") + "]"

    private val search = """[{"header":"YouTube Music","title":"Searched for artist one","time":"2026-10-30T19:59:00Z"},
        {"header":"YouTube Music","title":"Searched for a band never played","time":"2026-10-29T10:00:00Z"},
        {"header":"YouTube","title":"Searched for bike repair","time":"2026-10-29T10:00:00Z"}]"""

    private val now = java.time.Instant.parse("2026-10-31T12:00:00Z").toEpochMilli()

    private fun build(): Pair<ImportResult, ApolloModel> {
        val r = Takeout.assemble(linkedMapOf("Takeout/history/watch-history.json" to watch,
            "Takeout/history/search-history.json" to search))
        return r to ApolloEngine.build(r.events, r.searches, r.playlists, now, hourOf = { ApolloEngine.localHour(it, ZoneOffset.UTC) })
    }

    @Test fun auditCountsEveryEntryAndEverySkipReason() {
        val a = build().first.audit
        assertEquals(11, a.watchEntries)
        assertEquals(7, a.keptPlays)
        assertEquals(mapOf("ad" to 1, "no video id (visit or removed)" to 1, "no channel (deleted or private video)" to 1,
            "regular YouTube, not music" to 1), a.skipped)
        assertEquals(3, a.searches)
        assertEquals(2, a.musicSearches)
    }

    @Test fun vevoTopicAndPlainChannelsAreOneArtistAndTitlesLoseVideoDressing() {
        val m = build().second
        val songA = m.songs.values.single { it.title == "Song A" }
        assertEquals("Artist One", songA.artist)
        assertEquals(2, songA.plays) // the Topic play and the VEVO music video are the same song
        assertEquals(setOf("aaaaaaaaaaa", "aaaaaaaa2aa"), songA.variants)
        assertEquals(1, m.artists.count { it.name == "Artist One" })
    }

    @Test fun nothingIsInventedEverySongWasPlayed() {
        val (r, m) = build()
        val played = r.events.map { it.videoId }.toSet()
        assertTrue(m.songs.values.all { s -> s.variants.all { it in played } })
        ApolloEngine.mixes(m, Feedback(), now, 20).forEach { mix -> assertTrue(mix.songKeys.all { it in m.songs }) }
    }

    @Test fun sessionsMakeNeighboursAndSearchesFindTheUnplayed() {
        val m = build().second
        val a = m.songs.values.single { it.title == "Song A" }.key
        val b = m.songs.values.single { it.title == "Song B" }.key
        assertTrue((m.coPlay[a] ?: emptyMap()).containsKey(b))
        assertEquals("You often play it around Song A", ApolloEngine.moreLike(m, Feedback(), a)[1].why)
        assertEquals(listOf("a band never played"), m.searchedNeverPlayed)
    }

    @Test fun rightNowRediscoverAndFeedback() {
        val m = build().second
        val mixes = ApolloEngine.mixes(m, Feedback(), now, 9).associateBy { it.id }
        val c = m.songs.values.single { it.title == "Song C" }.key
        assertEquals(listOf(c), mixes["now"]!!.songKeys)
        assertEquals(listOf(c), mixes["rediscover"]!!.songKeys)
        val disliked = ApolloEngine.mixes(m, Feedback(dislikes = setOf(c)), now, 9)
        assertFalse(disliked.any { c in it.songKeys })
        val skipped = Feedback(skips = mapOf(c to 3))
        assertTrue(skipped.weight(m.songs[c]!!) < Feedback().weight(m.songs[c]!!))
    }

    @Test fun readsTheTakeoutZipAndPlaylistCsv() {
        val bytes = ByteArrayOutputStream().also { bo ->
            ZipOutputStream(bo).use { z ->
                z.putNextEntry(ZipEntry("Takeout/YouTube and YouTube Music/history/watch-history.json"))
                z.write(watch.toByteArray()); z.closeEntry()
                z.putNextEntry(ZipEntry("Takeout/YouTube and YouTube Music/playlists/Late Night-videos.csv"))
                z.write("Video ID,Playlist Video Creation Timestamp\naaaaaaaaaaa,2026-10-01T00:00:00+00:00\nzzzzzzzzzzz,x\n".toByteArray())
                z.closeEntry()
                z.putNextEntry(ZipEntry("Takeout/YouTube and YouTube Music/videos/huge.mp4"))
                z.write(ByteArray(10)); z.closeEntry()
            }
        }.toByteArray()
        val r = Takeout.read("takeout.zip", ByteArrayInputStream(bytes))
        assertEquals(7, r.events.size)
        assertEquals(listOf(ImportedPlaylist("Late Night", listOf("aaaaaaaaaaa", "zzzzzzzzzzz"))), r.playlists)
        val m = ApolloEngine.build(r.events, r.searches, r.playlists, now)
        val pl = m.playlists.single()
        assertEquals("Late Night", pl.name)
        assertEquals(1, pl.songKeys.size) // only songs that exist in the owner's history are queued
    }

    @Test fun storeRoundTripIsExact() {
        val r = build().first
        val back = MyMusicCodec.decode(MyMusicCodec.encode(r))
        assertEquals(r.events, back.events)
        assertEquals(r.searches, back.searches)
        assertEquals(r.audit, back.audit)
    }
}
