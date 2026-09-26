package com.apexforge.genesisplayer.data

/**
 * BRKN Vibes wave 1: the service and the UI share one process, so the live
 * audio session id is shared through this tiny @Volatile singleton. The
 * service writes it in ExoPlayer's onAudioSessionIdChanged (and the lazy
 * FX-attach fallback); the ember visualizer reads it to attach an
 * android.media.audiofx.Visualizer to the real output session.
 *
 * 0 = no session known yet (visualizer shows static ember art).
 */
object AudioSessionHub {
    @Volatile
    var audioSessionId: Int = 0
}
