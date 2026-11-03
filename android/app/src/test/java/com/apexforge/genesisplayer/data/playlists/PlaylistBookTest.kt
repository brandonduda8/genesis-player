package com.apexforge.genesisplayer.data.playlists

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files

class PlaylistBookTest {
    private var n = 0
    private fun id() = "pl-${++n}"
    private fun e(k: String) = Entry(k, "Artist $k", "Title $k")

    private fun book(): Pair<PlaylistBook, String> {
        val (b, p) = PlaylistBook().create("Late Night", 1, id())
        return b.add(p.id, listOf(e("a"), e("b"), e("c")), 2).first to p.id
    }

    @Test fun createRenameDelete() {
        var (b, p) = PlaylistBook().create("  Gym   Mix ", 10, id())
        assertEquals("Gym Mix", p.name)
        b = b.create("gym mix", 11, id()).first
        assertEquals(listOf("Gym Mix", "gym mix (2)"), b.playlists.map { it.name })
        b = b.rename(p.id, "Road", 12)
        assertEquals("Road", b.get(p.id)!!.name)
        assertEquals(12, b.get(p.id)!!.updatedMs)
        assertEquals("ROAD", b.rename(p.id, "ROAD", 13).get(p.id)!!.name) // its own old name doesn't clash
        b = b.delete(p.id)
        assertNull(b.get(p.id))
        assertEquals(1, b.playlists.size)
        try { PlaylistBook().create("   ", 1, id()); fail("blank name accepted") } catch (x: IllegalArgumentException) {}
        assertEquals(PlaylistBook.MAX_NAME, PlaylistBook().create("x".repeat(200), 1, id()).second.name.length)
    }

    @Test fun addSkipsDuplicatesRemoveAndReorder() {
        var (b, pid) = book()
        val (b2, added) = b.add(pid, listOf(e("b"), e("d"), e("d")), 3)
        assertEquals(1, added)
        b = b2
        assertEquals(listOf("a", "b", "c", "d"), b.get(pid)!!.entries.map { it.id })
        b = b.move(pid, 3, 0, 4)
        assertEquals(listOf("d", "a", "b", "c"), b.get(pid)!!.entries.map { it.id })
        b = b.move(pid, 0, 3, 5)
        assertEquals(listOf("a", "b", "c", "d"), b.get(pid)!!.entries.map { it.id })
        b = b.removeAt(pid, 1, 6)
        assertEquals(listOf("a", "c", "d"), b.get(pid)!!.entries.map { it.id })
        try { b.removeAt(pid, 9, 7); fail() } catch (x: IllegalArgumentException) {}
        try { b.move(pid, 0, 9, 7); fail() } catch (x: IllegalArgumentException) {}
    }

    @Test fun codecRoundTripsAndRefusesNewerFiles() {
        val (b, _) = book()
        assertEquals(b, PlaylistBook.decode(PlaylistBook.encode(b)))
        try { PlaylistBook.decode("""{"v":99,"playlists":[]}"""); fail() } catch (x: UnsupportedVersion) {}
        // a v1 file without optional fields still loads
        val lean = PlaylistBook.decode("""{"playlists":[{"id":"x","name":"Old","entries":[{"id":"k"}]}]}""")
        assertEquals("Old", lean.playlists.single().name)
        assertEquals("k", lean.playlists.single().entries.single().id)
    }

    @Test fun storeIsAtomicKeepsBackupAndSurvivesCorruption() {
        val dir = Files.createTempDirectory("pl").toFile()
        val f = File(dir, "aurum_playlists.json")
        val s = PlaylistStore(f)
        assertEquals(PlaylistBook(), s.load())
        val (b1, pid) = book()
        s.save(b1)
        val b2 = b1.rename(pid, "Second", 9)
        s.save(b2)
        assertEquals(b2, PlaylistStore(f).load())
        assertTrue(File(f.path + ".bak").exists())
        assertTrue(dir.list()!!.none { it.endsWith(".tmp") })
        f.writeText("{ this is not json")            // a torn / corrupt main file
        val recovered = PlaylistStore(f).load(now = 42)
        assertEquals(b1, recovered)                    // the previous good copy
        assertTrue(File(f.path + ".corrupt-42").exists()) // the bad file is kept, not destroyed
    }

    @Test fun newerFileIsReadOnlyNeverOverwritten() {
        val dir = Files.createTempDirectory("pl").toFile()
        val f = File(dir, "aurum_playlists.json")
        f.writeText("""{"v":7,"playlists":[]}""")
        val s = PlaylistStore(f)
        s.load()
        assertNotNull(s.readOnlyReason)
        try { s.save(PlaylistBook()); fail("overwrote a newer file") } catch (x: IllegalStateException) {}
        assertEquals("""{"v":7,"playlists":[]}""", f.readText())
    }

    @Test fun exportImportRematchesByArtistAndTitleAndDropsNothing() {
        val (b, pid) = book()
        val file = PlaylistBook.export(b, now = 5)
        // the other install has "a" by id, "b" under a different id but same artist/title, and no "c"
        val other = mapOf(PlaylistBook.matchKey("Artist b", "Title b (Official Video)") to "b2")
        val (merged, rep) = PlaylistImport.merge(PlaylistBook(), file, 6, ::id,
            exists = { it == "a" }, byMatchKey = { other[it] })
        val p = merged.playlists.single()
        assertEquals("Late Night", p.name)
        assertEquals(listOf("a", "b2", "c"), p.entries.map { it.id })
        assertEquals(ImportReport(playlists = 1, entries = 3, rematched = 1, unavailable = 1), rep)
        // importing into a book that already has the name: new id, suffixed name, original untouched
        val (twice, _) = PlaylistImport.merge(b, file, 7, ::id, exists = { true }, byMatchKey = { null })
        assertEquals(listOf("Late Night", "Late Night (2)"), twice.playlists.map { it.name })
        assertEquals(b.get(pid), twice.get(pid))
        try { PlaylistImport.merge(b, """{"kind":"other"}""", 8, ::id, { true }, { null }); fail() } catch (x: IllegalArgumentException) {}
    }

    @Test fun matchKeyIgnoresCaseDecorationAndPunctuation() {
        assertEquals(PlaylistBook.matchKey("Fat Nick", "Take Off"), PlaylistBook.matchKey("FAT NICK", "Take Off (Official Audio) [HD]"))
        assertTrue(PlaylistBook.matchKey("A", "B") != PlaylistBook.matchKey("A", "C"))
    }
}
