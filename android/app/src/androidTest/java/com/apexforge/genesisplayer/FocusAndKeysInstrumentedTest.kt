package com.apexforge.genesisplayer

import android.content.ComponentName
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.apexforge.genesisplayer.data.vault.Vault
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * WO-AURUM-2.2 §3/§7 audio focus, against the REAL PlayerService over its MediaSession. The track is the
 * encrypted vault test tone, so the test needs no network.
 *  - transient-may-duck (navigation prompt): keeps playing (ducked)
 *  - transient (a call ringing / voice call): pauses, and does not play while the other app holds focus
 *  - permanent loss (another music app): pauses and STAYS paused after the other app lets go
 */
@RunWith(AndroidJUnit4::class)
class FocusAndKeysInstrumentedTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val ins = InstrumentationRegistry.getInstrumentation()
    private lateinit var controller: MediaController
    private val am = ctx.getSystemService(AudioManager::class.java)

    private fun <T> main(f: () -> T): T { var r: Any? = null; ins.runOnMainSync { r = f() }; @Suppress("UNCHECKED_CAST") return r as T }
    private fun waitFor(ms: Long, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { if (main(cond)) return true; Thread.sleep(200) }
        return main(cond)
    }
    private fun req(gain: Int) = AudioFocusRequest.Builder(gain)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(
            if (gain == AudioManager.AUDIOFOCUS_GAIN_TRANSIENT) AudioAttributes.USAGE_VOICE_COMMUNICATION
            else if (gain == AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK) AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE
            else AudioAttributes.USAGE_MEDIA).build())
        .setOnAudioFocusChangeListener { }
        .build()

    @Before fun start() {
        val token = SessionToken(ctx, ComponentName(ctx, PlayerService::class.java))
        val f = main { MediaController.Builder(ctx, token).buildAsync() }
        controller = f.get()
        main { controller.sendGenesis(PlayerService.ACTION_VAULT_TEST, Bundle().apply { putString("mode", "seed_play"); putInt("seconds", 120) }) }
        assertTrue("vault tone should start playing", waitFor(20_000) { controller.isPlaying })
    }

    @After fun stop() {
        main { controller.pause(); controller.release() }
        Vault.clearAll(ctx); Vault.awaitIdle()
    }

    @Test fun duckKeepsPlaying_callPauses_permanentLossStaysPaused() {
        // 1. Navigation prompt: duck, keep playing.
        val duck = req(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        assertTrue(am.requestAudioFocus(duck) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        Thread.sleep(1500)
        assertTrue("must keep playing (ducked) under a may-duck request", main { controller.isPlaying })
        am.abandonAudioFocusRequest(duck)
        Thread.sleep(800)

        // 2. Call-like transient focus: pause, and stay silent for as long as the call holds focus.
        val call = req(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        assertTrue(am.requestAudioFocus(call) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        assertTrue("must pause for a call", waitFor(3000) { !controller.isPlaying })
        Thread.sleep(3000)
        assertFalse("must not resume over the call", main { controller.isPlaying })
        am.abandonAudioFocusRequest(call)
        waitFor(3000) { controller.isPlaying } // resuming AFTER the call ends is allowed (platform behaviour)

        // 3. Another music app takes focus for good: pause and do not come back on our own.
        main { controller.play() }
        assertTrue(waitFor(5000) { controller.isPlaying })
        val other = req(AudioManager.AUDIOFOCUS_GAIN)
        assertTrue(am.requestAudioFocus(other) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        assertTrue("must pause on permanent focus loss", waitFor(3000) { !controller.isPlaying })
        am.abandonAudioFocusRequest(other)
        Thread.sleep(3000)
        assertFalse("must not auto-resume after a permanent loss", main { controller.isPlaying })
    }
}
