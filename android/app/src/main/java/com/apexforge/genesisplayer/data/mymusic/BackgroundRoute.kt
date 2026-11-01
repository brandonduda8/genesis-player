package com.apexforge.genesisplayer.data.mymusic

/**
 * Select only playable, user-approved non-YouTube audio for background playback.
 *
 * YouTube iframe media must remain visible. This pure routing function cannot
 * convert a YouTube video into audio or silently substitute an alternate song.
 */
data class BackgroundQueue(val trackIds: List<String>, val startIndex: Int = 0)

object BackgroundRoute {
    fun select(
        keys: List<String>,
        currentIndex: Int,
        resolveExactOrOffline: (String) -> String?,
        canPlayInAurum: (String) -> Boolean
    ): BackgroundQueue? {
        if (currentIndex !in keys.indices) return null
        fun eligible(key: String): String? =
            resolveExactOrOffline(key)?.takeIf { id ->
                (id.startsWith("offline:") || id.startsWith("crate:")) && canPlayInAurum(id)
            }

        // Do not jump to a different track when this exact song has no legal audio.
        val current = eligible(keys[currentIndex]) ?: return null
        val upcoming = keys.drop(currentIndex + 1).take(100).mapNotNull(::eligible)
        return BackgroundQueue(listOf(current) + upcoming)
    }
}
