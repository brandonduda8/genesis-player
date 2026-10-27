package com.apexforge.genesisplayer.data

import org.junit.Assert.*
import org.junit.Test

/** Pure JVM regression tests: catalog IDs do not define song identity. */
class SongIdentityTest {
    @Test fun caseAndPunctuationAreStable() {
        assertEquals(
            SongIdentity.key("Mac Miller", "  Good News! "),
            SongIdentity.key("MAC   MILLER", "good--news")
        )
    }

    @Test fun sameSongKeepsIdentityAcrossCatalogRenames() {
        // Track IDs can change with provider migration, but title + artist do not.
        val oldCatalog = Track("old-1", "Doobie", "Test Song", "", "", 0)
        val newCatalog = Track("new-987", "Doobie", "Test Song", "", "", 0)
        assertNotEquals(oldCatalog.id, newCatalog.id)
        assertEquals(
            SongIdentity.key(oldCatalog.artist, oldCatalog.title),
            SongIdentity.key(newCatalog.artist, newCatalog.title)
        )
    }

    @Test fun absentMetadataNeverCreatesGlobalBlock() {
        assertNull(SongIdentity.key("", "Unknown"))
        assertNull(SongIdentity.key("Artist", ""))
        assertNull(SongIdentity.key("!!!", "???"))
    }

    @Test fun differentArtistsRemainDistinct() {
        assertNotEquals(
            SongIdentity.key("Doobie", "Good News"),
            SongIdentity.key("Mac Miller", "Good News")
        )
    }

    @Test fun unicodeNormalizationWorks() {
        assertEquals(
            SongIdentity.key("Cafe\u0301", "Night"),
            SongIdentity.key("Café", "night")
        )
    }
}
