package com.apexforge.genesisplayer.audio

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink

/** 2.4 golden, charge 3: Aurum's renderers, whose audio sink carries the headphone-correction processor. */
@OptIn(UnstableApi::class)
fun aurumRenderers(ctx: Context): DefaultRenderersFactory = object : DefaultRenderersFactory(ctx) {
    override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
        DefaultAudioSink.Builder(context)
            .setAudioProcessors(arrayOf(AurumEqProcessor()))
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .build()
}
