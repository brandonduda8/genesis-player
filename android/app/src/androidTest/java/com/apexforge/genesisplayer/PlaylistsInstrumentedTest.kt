package com.apexforge.genesisplayer

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.apexforge.genesisplayer.data.Library
import com.apexforge.genesisplayer.data.Track
import com.apexforge.genesisplayer.data.playlists.PlaylistPlayback
import com.apexforge.genesisplayer.data.playlists.Playlists
import com.apexforge.genesisplayer.data.vault.RoutingDataSource
import com.apexforge.genesisplayer.data.vault.Vault
import com.apexforge.genesisplayer.data.vault.VaultDataSource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Aurum 2.3 on a real device/emulator: a playlist is created, filled, survives a reload from disk (what an app
 * restart does), routes offline to the Aurum player, and its encrypted entry actually plays through ExoPlayer.
 */
@RunWith(AndroidJUnit4::class)
class PlaylistsInstrumentedTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val file get() = File(ctx.filesDir, Playlists.FILE)

    private fun wav(seconds: Int): ByteArray {
        val n = 44_100 * seconds
        val b = ByteBuffer.allocate(44 + n * 2).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + n * 2).put("WAVE".toByteArray()).put("fmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(44_100).putInt(88_200).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(n * 2)
        for (i in 0 until n) b.putShort((Math.sin(2 * Math.PI * 330 * i / 44_100.0) * 8000).toInt().toShort())
        return b.array()
    }

    private fun wipe() {
        listOf(file, File(file.path + ".bak"), File(file.path + ".tmp")).forEach { it.delete() }
        Vault.remove(ctx, "owned:it-tone"); Vault.awaitIdle()
    }

    @Before fun setUp() {
        wipe()
        assertNull(Vault.store(ctx, "owned:it-tone", "Playlist Tone", "Aurum CI", ByteArrayInputStream(wav(6))))
        val e = Vault.entry(ctx, "owned:it-tone")!!
        Library.applyRemote(listOf(
            Track("owned:it-tone", "Aurum CI", "Playlist Tone", VaultDataSource.uriFor(e.fileId).toString(), "", 6),
            Track("yt-only", "Some Artist", "Online Song", "", "", 0, youtubeId = "dQw4w9WgXcQ"),
        ), emptyList())
        Playlists.load(ctx)
    }

    @After fun tearDown() = wipe()

    @Test fun createFillReloadRouteAndPlay() {
        val p = Playlists.create(ctx, "Night Drive")
        assertEquals(2, Playlists.add(ctx, p.id, listOf("owned:it-tone", "yt-only")))
        assertEquals("re-adding is a no-op", 0, Playlists.add(ctx, p.id, listOf("owned:it-tone")))
        Playlists.move(ctx, p.id, 1, 0)
        assertTrue(file.exists())

        // "Restart": a fresh load from disk sees exactly what was saved.
        Playlists.load(ctx)
        val back = Playlists.book.get(p.id)!!
        assertEquals("Night Drive", back.name)
        assertEquals(listOf("yt-only", "owned:it-tone"), back.entries.map { it.id })
        assertTrue("previous good copy kept", File(file.path + ".bak").exists())

        // Offline: only the encrypted own file is queued, and it goes to the Aurum player.
        val plan = Playlists.plan(ctx, back, start = 1, online = false, shuffle = false)
        assertEquals(PlaylistPlayback.Route.AURUM, plan.route)
        assertEquals(listOf("owned:it-tone"), plan.ids)
        val tapYt = Playlists.plan(ctx, back, start = 0, online = false, shuffle = false)
        assertEquals(PlaylistPlayback.Route.NONE, tapYt.route) // YouTube-only song offline: explained, not faked

        // The queued entry really plays (decrypted in memory through the vault data source).
        val uri = Library.track(plan.ids.single())!!.streamUrl
        var player: ExoPlayer? = null
        val ins = InstrumentationRegistry.getInstrumentation()
        ins.runOnMainSync {
            player = ExoPlayer.Builder(ctx)
                .setMediaSourceFactory(DefaultMediaSourceFactory(RoutingDataSource.Factory(ctx))).build().apply {
                    volume = 0f; setMediaItem(MediaItem.fromUri(uri)); prepare(); play()
                }
        }
        var pos = 0L; var err: String? = null
        val end = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < end && pos <= 1500 && err == null) {
            Thread.sleep(200)
            ins.runOnMainSync { pos = player!!.currentPosition; err = player!!.playerError?.message }
        }
        ins.runOnMainSync { player!!.release() }
        assertNull(err)
        assertTrue("playlist entry must actually play (pos=$pos)", pos > 1500)

        Playlists.delete(ctx, p.id)
        Playlists.load(ctx)
        assertNull(Playlists.book.get(p.id))
    }

    @Test fun newerFileIsReadOnlyAndUntouched() {
        file.writeText("""{"v":9,"playlists":[]}""")
        Playlists.load(ctx)
        assertTrue(Playlists.readOnlyReason != null)
        try { Playlists.create(ctx, "x"); throw AssertionError("wrote over a newer file") } catch (e: IllegalStateException) {}
        assertEquals("""{"v":9,"playlists":[]}""", file.readText())
    }
}
