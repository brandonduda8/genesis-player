package com.apexforge.genesisplayer.data.mymusic.crate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic names only. The rules: same artist or nothing; exact vs alternate is explicit; download needs permission. */
class CrateMatcherTest {
    private fun aud(title: String, artist: String, verified: Boolean = true, dl: Boolean = false) =
        Candidate(Source.AUDIUS, title.hashCode().toString(), title, artist, verified, "https://s/$title",
            if (dl) "https://d/$title" else "", if (dl) "artist-enabled download" else "stream only")

    @Test fun exactMatchOnVerifiedAccountIgnoresVideoDressingAndFeatures() {
        val m = CrateMatcher.classify("k", "Sample Artist - Night Drive (Official Video)", "Sample Artist",
            listOf(aud("Night Drive (feat. Guest)", "Sample Artist", dl = true)))
        assertEquals(MatchKind.EXACT, m.kind)
        assertEquals("Official Audius account", m.note)
        assertTrue(m.downloadable)
    }

    @Test fun aSlowedOrRemixedUploadIsAnAlternateNeverAnExact() {
        val m = CrateMatcher.classify("k", "Night Drive", "Sample Artist",
            listOf(aud("Night Drive (Slowed + Reverb)", "Sample Artist")))
        assertEquals(MatchKind.ALTERNATE, m.kind)
        assertTrue(m.note, m.note.contains("Slowed") && m.note.contains("not the recording you play"))
        val back = CrateMatcher.classify("k", "Night Drive (Remix)", "Sample Artist", listOf(aud("Night Drive", "Sample Artist")))
        assertEquals(MatchKind.ALTERNATE, back.kind)
    }

    @Test fun sameTitleByAnotherArtistIsNeverOffered() {
        val m = CrateMatcher.classify("k", "Night Drive", "Sample Artist", listOf(aud("Night Drive", "Somebody Else")))
        assertEquals(MatchKind.UNAVAILABLE, m.kind)
        assertNull(m.candidate)
    }

    @Test fun unverifiedSameNameAccountStreamsButNeverDownloads() {
        val m = CrateMatcher.classify("k", "Night Drive", "Sample Artist", listOf(aud("Night Drive", "Sample Artist", verified = false, dl = true)))
        assertEquals(MatchKind.EXACT, m.kind)
        assertFalse(m.downloadable)
        assertTrue(m.note.contains("unverified"))
        val pick = CrateMatcher.classify("k", "Night Drive", "Sample Artist",
            listOf(aud("Night Drive", "Sample Artist", verified = false), aud("Night Drive", "Sample Artist", verified = true)))
        assertTrue(pick.candidate!!.verified)
    }

    @Test fun vevoAndTopicSpellingsAreTheSameArtist() {
        assertTrue(CrateMatcher.sameArtist("SampleArtistVEVO", "Sample Artist"))
        assertTrue(CrateMatcher.sameArtist("Sample Artist - Topic", "sample artist"))
        assertFalse(CrateMatcher.sameArtist("Sample Artist Two", "Sample Artist"))
    }

    @Test fun audiusParserReadsBothDownloadFlagSpellingsAndDropsGatedTracks() {
        val json = """{"data":[
          {"id":"A1","title":"One","duration":200,"downloadable":true,"user":{"name":"Sample Artist","is_verified":true},"artwork":{"480x480":"https://art/1"}},
          {"id":"A2","title":"Two","is_downloadable":true,"user":{"name":"Sample Artist","is_verified":true}},
          {"id":"A3","title":"Three","download":{"is_downloadable":true},"is_download_gated":true,"user":{"name":"Sample Artist"}},
          {"id":"A4","title":"Four","is_stream_gated":true,"user":{"name":"Sample Artist"}},
          {"id":"A5","title":"Five","is_streamable":false,"user":{"name":"Sample Artist"}}]}"""
        val c = CrateMatcher.parseAudiusTracks(json, "https://api.example", "aurum", null)
        assertEquals(listOf("One", "Two", "Three"), c.map { it.title })
        assertEquals(listOf(true, true, false), c.map { it.downloadUrl.isNotEmpty() })
        assertEquals("https://api.example/v1/tracks/A1/stream?app_name=aurum", c[0].streamUrl)
        assertEquals("https://art/1", c[0].artworkUrl)
    }

    @Test fun accountPickRequiresTheArtistsOwnName() {
        val users = CrateMatcher.parseAudiusUsers("""{"data":[{"id":"u1","name":"Sample Artist Fan Page","handle":"fan","is_verified":false},
            {"id":"u2","name":"Sample Artist","handle":"sampleartist","is_verified":true,"follower_count":10},
            {"id":"u3","name":"sample artist","handle":"sa2","is_verified":false,"follower_count":99}]}""")
        assertEquals("u2", CrateMatcher.pickArtistAccount("Sample Artist", users)!!.id)
        assertNull(CrateMatcher.pickArtistAccount("Nobody Here", users))
    }

    @Test fun archiveKeepsOnlyCreativeCommonsOrPublicDomain() {
        val search = """{"response":{"docs":[
          {"identifier":"cc-item","creator":"Sample Artist","licenseurl":"https://creativecommons.org/licenses/by/4.0/"},
          {"identifier":"pd-item","creator":["Sample Artist"],"licenseurl":"http://creativecommons.org/publicdomain/zero/1.0/"},
          {"identifier":"no-licence","creator":"Sample Artist"},
          {"identifier":"other","creator":"Sample Artist","licenseurl":"https://example.com/all-rights-reserved"}]}}"""
        val items = CrateMatcher.parseArchiveSearch(search)
        assertEquals(listOf("cc-item", "pd-item"), items.map { it.identifier })
        val files = """{"metadata":{"creator":"Sample Artist"},"files":[
          {"name":"01 Night Drive.flac","format":"Flac","title":"Night Drive"},
          {"name":"01 Night Drive.mp3","format":"VBR MP3","title":"Night Drive","length":"201.5"},
          {"name":"cover.jpg","format":"JPEG"}]}"""
        val c = CrateMatcher.parseArchiveFiles(files, items[0])
        assertEquals(1, c.size)
        assertEquals("https://archive.org/download/cc-item/01%20Night%20Drive.mp3", c[0].downloadUrl)
        val m = CrateMatcher.classify("k", "Night Drive", "Sample Artist", c)
        assertEquals(MatchKind.EXACT, m.kind)
        assertTrue(m.downloadable)
    }

    @Test fun cratePersistsExactly() {
        val ms = listOf(CrateMatcher.classify("a|b", "Night Drive", "Sample Artist", listOf(aud("Night Drive", "Sample Artist", dl = true))),
            CrateMatch("c|d", MatchKind.UNAVAILABLE, null, "Not found"))
        val (back, at) = CrateMatcher.decode(CrateMatcher.encode(ms, 42L))
        assertEquals(ms, back)
        assertEquals(42L, at)
    }
}
