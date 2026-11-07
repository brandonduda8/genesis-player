package com.apexforge.genesisplayer.audio

/**
 * Aurum 2.4 golden, charge 3: the format line on Now Playing ("FLAC · 24-bit · 96 kHz · Lossless").
 * Only what the stream actually reports is shown: an unknown bit depth or bitrate is left out, never guessed.
 */
object FormatLabel {
    data class Info(val codec: String, val lossless: Boolean, val sampleRateHz: Int, val channels: Int,
                    val bitsPerSample: Int?, val bitrateBps: Int?)

    fun codecOf(mime: String?): Pair<String, Boolean> = when (mime?.lowercase()) {
        "audio/flac" -> "FLAC" to true
        "audio/alac" -> "ALAC" to true
        "audio/raw", "audio/wav", "audio/x-wav" -> "PCM" to true
        "audio/mpeg" -> "MP3" to false
        "audio/mp4a-latm", "audio/aac" -> "AAC" to false
        "audio/opus" -> "Opus" to false
        "audio/vorbis" -> "Vorbis" to false
        null, "" -> "Unknown" to false
        else -> mime.substringAfter('/').uppercase() to false
    }

    /** [pcmBits] is the stream's sample depth when the container reports it (FLAC, ALAC, WAV); null otherwise. */
    fun info(mime: String?, sampleRateHz: Int, channels: Int, pcmBits: Int?, bitrateBps: Int?): Info {
        val (codec, lossless) = codecOf(mime)
        return Info(codec, lossless, sampleRateHz, channels,
            pcmBits?.takeIf { lossless && it in 8..32 }, bitrateBps?.takeIf { !lossless && it > 0 })
    }

    fun rate(hz: Int): String = when {
        hz <= 0 -> ""
        hz % 1000 == 0 -> "${hz / 1000} kHz"
        else -> "%.1f kHz".format(java.util.Locale.US, hz / 1000.0)
    }

    fun label(i: Info): String = listOfNotNull(
        i.codec,
        i.bitsPerSample?.let { "$it-bit" },
        rate(i.sampleRateHz).ifEmpty { null },
        when (i.channels) { 1 -> "Mono"; 2 -> null; in 3..32 -> "${i.channels} ch"; else -> null },
        i.bitrateBps?.let { "${(it + 500) / 1000} kbps" },
        if (i.lossless) "Lossless" else null,
    ).joinToString(" · ")

    /** Media3's C.ENCODING_PCM_* constants, mapped to bits (8/16/24/32-bit int, 32-bit float). */
    fun bitsForPcmEncoding(encoding: Int): Int? = when (encoding) {
        3 -> 8        // C.ENCODING_PCM_8BIT
        2 -> 16       // C.ENCODING_PCM_16BIT
        0x15 -> 24    // C.ENCODING_PCM_24BIT
        0x16 -> 32    // C.ENCODING_PCM_32BIT
        4 -> 32       // C.ENCODING_PCM_FLOAT
        else -> null
    }
}
