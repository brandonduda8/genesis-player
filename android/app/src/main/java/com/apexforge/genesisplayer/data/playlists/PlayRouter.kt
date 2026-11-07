package com.apexforge.genesisplayer.data.playlists

/**
 * Aurum 2.4 golden, charge 2: ONE router for every play button (Apollo Play For You / Shuffle all, mix rows, the
 * Archive, playlists).
 *
 * The law (WO-AURUM-GOLDEN-MAX): lawful or labeled, no third option.
 *  - A song that has an Aurum source (an offline vault copy, an owned file, or an EXACT real-engine stream match)
 *    plays in Aurum's Media3 engine, which keeps playing with the screen off and after the app is swiped away.
 *  - A song that only exists on YouTube plays in YouTube's official embedded player. That player pauses whenever the
 *    app leaves the screen (YouTube's terms forbid background play of the embed), so it is LABELED, never presented
 *    as background-safe, and the owner can hand the queue to YouTube's own app.
 *  - Nothing is substituted: an alternate recording never stands in for the exact song.
 */
object PlayRouter {
    enum class Engine { AURUM, YOUTUBE_ONLY, UNAVAILABLE }

    const val YOUTUBE_LABEL = "YouTube-only · pauses when you leave the app"

    data class Coverage(val total: Int, val aurum: Int, val offline: Int, val youtubeOnly: Int, val unavailable: Int) {
        /** The honest, in-app coverage line. Counted, never estimated. */
        fun line(): String = if (total == 0) "No songs yet" else
            "$aurum of $total songs play in Aurum's engine (keep playing with the screen off); " +
                "$youtubeOnly YouTube-only" + (if (unavailable > 0) "; $unavailable unavailable" else "")
        val percent: Int get() = if (total == 0) 0 else (aurum * 100) / total
    }

    fun engineOf(key: String, aurumId: (String) -> String?, onYouTube: (String) -> Boolean): Engine = when {
        aurumId(key) != null -> Engine.AURUM
        onYouTube(key) -> Engine.YOUTUBE_ONLY
        else -> Engine.UNAVAILABLE
    }

    fun coverage(keys: Collection<String>, aurumId: (String) -> String?, worksOffline: (String) -> Boolean,
                 onYouTube: (String) -> Boolean): Coverage {
        var a = 0; var off = 0; var yt = 0; var none = 0
        for (k in keys.distinct()) {
            val id = aurumId(k)
            when {
                id != null -> { a++; if (worksOffline(id)) off++ }
                onYouTube(k) -> yt++
                else -> none++
            }
        }
        return Coverage(a + yt + none, a, off, yt, none)
    }

    /**
     * Plan a play.
     *  - [start] = the tapped song's index, or null for a whole-list button ("Play For You", "Shuffle all", a mix's
     *    play button). A whole-list play prefers Aurum: if ANY song in the list plays in Aurum, the queue is those
     *    songs, and the YouTube-only ones are counted in [PlaylistPlayback.Plan.skipped] and told to the owner, never
     *    silently swallowed into a player that stops in the background.
     *  - A tapped song plays where it lives: an Aurum song starts an Aurum queue; a YouTube-only song starts a
     *    (labeled) YouTube queue. That keeps the tested 2.3 playlist rules (PlaylistPlayback.plan).
     */
    fun plan(keys: List<String>, start: Int?, online: Boolean, shuffle: Boolean,
             aurumId: (String) -> String?, worksOffline: (String) -> Boolean, onYouTube: (String) -> Boolean,
             title: (String) -> String = { it },
             random: java.util.Random = java.util.Random()): PlaylistPlayback.Plan {
        val distinct = keys.distinct()
        val entries = distinct.map { Entry(it, "", title(it)) }
        if (start != null) {
            val tapped = keys.getOrNull(start) ?: return PlaylistPlayback.Plan(PlaylistPlayback.Route.NONE, emptyList(), 0, 0, "Nothing to play.")
            return PlaylistPlayback.plan(entries, distinct.indexOf(tapped), online, shuffle, aurumId, worksOffline, onYouTube, random)
        }
        if (entries.isEmpty()) return PlaylistPlayback.Plan(PlaylistPlayback.Route.NONE, emptyList(), 0, 0, "Nothing to play.")
        val aurumPlayable = distinct.indexOfFirst { k -> aurumId(k)?.let { online || worksOffline(it) } == true }
        if (aurumPlayable >= 0) {
            val first = if (shuffle) {
                val candidates = distinct.indices.filter { i -> aurumId(distinct[i])?.let { online || worksOffline(it) } == true }
                candidates[random.nextInt(candidates.size)]
            } else aurumPlayable
            return PlaylistPlayback.plan(entries, first, online, shuffle, aurumId, worksOffline, onYouTube, random)
        }
        if (!online) return PlaylistPlayback.Plan(PlaylistPlayback.Route.NONE, emptyList(), 0, entries.size,
            "Offline: none of these songs are saved in Aurum. Save songs to your Crate to hear them offline.")
        val firstYt = distinct.indexOfFirst(onYouTube)
        if (firstYt < 0) return PlaylistPlayback.Plan(PlaylistPlayback.Route.NONE, emptyList(), 0, entries.size,
            "None of these songs are available anymore.")
        val pick = if (shuffle) distinct.indices.filter { onYouTube(distinct[it]) }.let { it[random.nextInt(it.size)] } else firstYt
        return PlaylistPlayback.plan(entries, pick, online, shuffle, aurumId, worksOffline, onYouTube, random)
    }

    /** What the owner is told after a plan; null when there is nothing worth saying. */
    fun notice(plan: PlaylistPlayback.Plan, online: Boolean): String? = when (plan.route) {
        PlaylistPlayback.Route.AURUM -> if (plan.skipped == 0) null else
            "Playing ${plan.ids.size} in Aurum (keeps playing with the screen off). ${plan.skipped} " +
                (if (online) "are YouTube-only and were left out" else "need the internet")
        PlaylistPlayback.Route.YOUTUBE -> "$YOUTUBE_LABEL. To keep listening in the background, use \"Continue in YouTube\"."
        PlaylistPlayback.Route.NONE -> plan.reason
    }

    /**
     * Hand the YouTube-only queue to YouTube's own app (the owner's choice, one tap). YouTube's documented anonymous
     * queue URL takes up to 50 comma-separated video ids. Whether it then plays in the background depends on the
     * owner's YouTube plan: Aurum claims nothing about it.
     */
    fun handoffUrl(videoIds: List<String>, from: Int = 0): String? {
        val ids = videoIds.drop(from.coerceAtLeast(0)).filter { it.matches(Regex("[A-Za-z0-9_-]{11}")) }.distinct().take(50)
        if (ids.isEmpty()) return null
        return "https://www.youtube.com/watch_videos?video_ids=" + ids.joinToString(",")
    }
}
