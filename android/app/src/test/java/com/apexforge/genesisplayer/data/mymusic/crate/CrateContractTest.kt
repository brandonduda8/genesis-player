package com.apexforge.genesisplayer.data.mymusic.crate

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Live contract test against the REAL Audius and Internet Archive APIs, with neutral queries only (no listener data).
 * Runs only when CRATE_CONTRACT=1 (CI sets it); normal unit runs never touch the network.
 */
class CrateContractTest {
    private fun on() = assumeTrue("set CRATE_CONTRACT=1 to run", System.getenv("CRATE_CONTRACT") == "1")

    @Test fun audiusTrendingParsesIntoStreamableCandidatesWithArtists() {
        on()
        val c = CrateNet.audiusTrending()
        println("AUDIUS trending parsed: ${c.size}; first=${c.firstOrNull()?.let { "${it.artistName} — ${it.title} dl=${it.downloadUrl.isNotEmpty()} verified=${it.verified}" }}")
        assertTrue(c.isNotEmpty())
        assertTrue(c.all { it.id.isNotEmpty() && it.title.isNotEmpty() && it.artistName.isNotEmpty() && it.streamUrl.contains("/stream") })
    }

    @Test fun audiusUserSearchAndTracksRoundTrip() {
        on()
        val anyArtist = CrateNet.audiusTrending().first().artistName
        val users = CrateNet.audiusUsers(anyArtist)
        val acct = CrateMatcher.pickArtistAccount(anyArtist, users)
        println("AUDIUS users for a trending artist: ${users.size}; picked=${acct?.let { "${it.name} verified=${it.verified}" }}")
        assertTrue(acct != null)
        val tracks = CrateNet.audiusTracksOf(acct!!, maxPages = 1)
        println("AUDIUS tracks for that account: ${tracks.size}")
        assertTrue(tracks.isNotEmpty())
    }

    @Test fun archiveLicensedSearchAndFiles() {
        on()
        val docs = CrateMatcher.parseArchiveSearch(CrateNet.get("https://archive.org/advancedsearch.php?q=" +
            CrateNet.enc("mediatype:(audio) AND licenseurl:(*creativecommons.org/licenses/by*) AND format:(VBR MP3)") +
            "&fl%5B%5D=identifier&fl%5B%5D=creator&fl%5B%5D=licenseurl&rows=3&output=json"))
        println("ARCHIVE licensed items: ${docs.map { it.identifier }}")
        assertTrue(docs.isNotEmpty())
        val files = docs.asSequence().map { CrateNet.archiveFiles(it) }.firstOrNull { it.isNotEmpty() }.orEmpty()
        println("ARCHIVE mp3 files in first item with audio: ${files.size}; first=${files.firstOrNull()?.downloadUrl}")
        assertTrue(files.isNotEmpty())
    }
}
