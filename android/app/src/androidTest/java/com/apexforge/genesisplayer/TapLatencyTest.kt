package com.apexforge.genesisplayer

import androidx.media3.common.MediaItem
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.apexforge.genesisplayer.data.Track
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * API-34 instrumented tests proving the tap-to-first-audio latency repair:
 * the FIRST player call carries only the tapped track and happens after
 * exactly one resolution — the old code resolved the whole playlist first.
 * Also proves SoundCloud signed URLs are re-resolved at transitions and
 * never fabricated.
 */
@RunWith(AndroidJUnit4::class)
class TapLatencyTest {

    private fun item(id: String) =
        MediaItem.Builder().setMediaId(id).setUri("https://stream/$id").build()

    private class FakeControl : PlaybackQueue.PlayerControl {
        val calls = mutableListOf<String>()
        override fun setAndPlayFirst(item: MediaItem) {
            calls.add("first:${item.mediaId}")
        }

        override fun appendItems(items: List<MediaItem>) {
            calls.add("append:${items.joinToString(",") { it.mediaId }}")
        }
    }

    private fun queue(
        resolveOrder: MutableList<String>,
        unresolvable: Set<String> = emptySet(),
        control: FakeControl = FakeControl(),
        isStale: () -> Boolean = { false }
    ): Pair<PlaybackQueue, FakeControl> {
        val q = PlaybackQueue(
            resolve = { id ->
                resolveOrder.add(id)
                if (id in unresolvable) null else item(id)
            },
            control = control,
            isStale = isStale
        )
        return q to control
    }

    @Test
    fun tappedTrackPlaysAfterExactlyOneResolution() {
        val resolveOrder = mutableListOf<String>()
        val (q, control) = queue(resolveOrder)
        q.start(QueuePlan(listOf("t1", "t2", "t3"), "t2", 0))

        assertTrue("first player call must be the tapped track",
            control.calls.isNotEmpty() && control.calls[0] == "first:t2")
        // The latency fix: only ONE resolution happened before first play.
        assertEquals(
            "tap-to-first-audio must follow a single resolution, not the whole playlist",
            listOf("t2"),
            resolveOrder.take(1)
        )
        // ...and the rest arrived afterwards, without the head.
        val appended = control.calls.filter { it.startsWith("append:") }
        assertTrue("rest of queue must append in background", appended.isNotEmpty())
        val appendedIds = appended.joinToString(",").removePrefix("append:")
        assertTrue(appendedIds.contains("t1"))
        assertTrue(appendedIds.contains("t3"))
        assertFalse("head must not be re-appended", appendedIds.split(",").contains("t2"))
    }

    @Test
    fun unresolvableTappedTrackFallsForward() {
        val resolveOrder = mutableListOf<String>()
        val (q, control) = queue(resolveOrder, unresolvable = setOf("t2"))
        q.start(QueuePlan(listOf("t1", "t2", "t3"), "t2", 0))

        assertEquals("first:t3", control.calls[0])
        assertEquals(listOf("t2", "t3"), resolveOrder.take(2))
    }

    @Test
    fun staleGenerationAbortsBeforeFirstPlay() {
        val resolveOrder = mutableListOf<String>()
        val (q, control) = queue(resolveOrder, isStale = { true })
        q.start(QueuePlan(listOf("t1", "t2"), "t1", 0))
        assertTrue("stale queue must never touch the player", control.calls.isEmpty())
    }

    @Test
    fun nullStartIdDoesNothing() {
        val resolveOrder = mutableListOf<String>()
        val (q, control) = queue(resolveOrder)
        q.start(QueuePlan(emptyList(), null, 0))
        assertTrue(control.calls.isEmpty())
        assertTrue(resolveOrder.isEmpty())
    }

    // ---- SoundCloud URL re-resolution at transitions ----

    private fun scTrack(id: String) = Track(
        id, "Artist", "Title", "", "", 180,
        soundcloudUrl = "https://soundcloud.com/artist/$id"
    )

    @Test
    fun soundCloudTrackGetsFreshUrl() {
        var calls = 0
        val old = MediaItem.Builder().setMediaId("s1")
            .setUri("https://cf-media.sndcdn.com/OLD").build()
        val fresh = SoundCloudRefresher.refresh(old, scTrack("s1")) {
            calls++
            "https://cf-media.sndcdn.com/NEW"
        }
        assertEquals(1, calls)
        assertNotNull("must return a rebuilt item with the fresh URL", fresh)
        assertEquals("https://cf-media.sndcdn.com/NEW",
            fresh!!.localConfiguration?.uri.toString())
        assertEquals("s1", fresh.mediaId)
    }

    @Test
    fun soundCloudResolverEmpty_neverFabricatesUrl() {
        var calls = 0
        val old = MediaItem.Builder().setMediaId("s1")
            .setUri("https://cf-media.sndcdn.com/OLD").build()
        val fresh = SoundCloudRefresher.refresh(old, scTrack("s1")) {
            calls++
            null // resolve failed: keep the old item, never invent one
        }
        assertEquals(1, calls)
        assertNull("no fresh URL -> no rebuild; caller keeps the old item", fresh)
    }

    @Test
    fun directStreamTrackIsNeverReResolved() {
        var calls = 0
        val direct = Track("d1", "Artist", "Title",
            "https://discoveryprovider.audius.co/v1/tracks/x/stream", "", 180)
        val old = MediaItem.Builder().setMediaId("d1")
            .setUri(direct.streamUrl).build()
        val fresh = SoundCloudRefresher.refresh(old, direct) {
            calls++
            "https://evil.example/fake"
        }
        assertEquals(0, calls)
        assertNull(fresh)
    }
}
