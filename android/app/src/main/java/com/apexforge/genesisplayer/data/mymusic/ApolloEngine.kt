package com.apexforge.genesisplayer.data.mymusic

import kotlin.math.pow

/** A song as the owner knows it: one artist + one title, across every video id it was played from. */
data class Song(
    val key: String,
    val artist: String,
    val title: String,
    /** The video id to play: the variant the owner played most. */
    val videoId: String,
    val variants: Set<String>,
    val plays: Int,
    val firstMs: Long,
    val lastMs: Long,
    /** Recency-weighted plays: each play counts 0.5^(age / half-life). */
    val heat: Double,
    val hours: IntArray
)

data class Artist(val key: String, val name: String, val plays: Int, val heat: Double, val songKeys: List<String>)

/** A queue Apollo built, with the reason, in words, that it can prove from the owner's own data. */
data class Mix(val id: String, val name: String, val why: String, val songKeys: List<String>)

data class Pick(val songKey: String, val why: String)

/** What the owner told Apollo inside the app. Dislikes are never played again by Apollo. */
data class Feedback(
    val likes: Set<String> = emptySet(),
    val dislikes: Set<String> = emptySet(),
    val skips: Map<String, Int> = emptyMap(),
    val completes: Map<String, Int> = emptyMap(),
    val artistLikes: Set<String> = emptySet()
) {
    fun weight(song: Song): Double {
        if (song.key in dislikes) return 0.0
        var w = 1.0
        if (song.key in likes) w *= 1.6
        if (song.key.substringBefore('|') in artistLikes) w *= 1.2
        w *= 0.85.pow((skips[song.key] ?: 0).coerceAtMost(6))
        w *= 1.0 + 0.05 * (completes[song.key] ?: 0).coerceAtMost(10)
        return w
    }
}

class ApolloModel(
    val songs: Map<String, Song>,
    val artists: List<Artist>,
    /** songKey -> (neighbour songKey -> co-play strength), from the owner's own listening sessions. */
    val coPlay: Map<String, Map<String, Double>>,
    val searchedNeverPlayed: List<String>,
    val playlists: List<Mix>,
    val builtAtMs: Long
) {
    val byVideo: Map<String, String> = songs.values.flatMap { s -> s.variants.map { it to s.key } }.toMap()
}

/**
 * Apollo v2: the recommendation engine, built ONLY from the owner's imported plays and in-app feedback.
 * Its signals:
 *  - heat: recency-weighted play counts (half-life 90 days);
 *  - sessions: plays less than 30 min apart form a session, and songs near each other in a session become
 *    neighbours (this is "more like this");
 *  - hour of day;
 *  - rediscovery: loved, then gone quiet;
 *  - in-app likes, dislikes, skips and completed plays.
 * It never adds a song the owner hasn't played. Discovery beyond the library is labelled and comes from the owner's
 * own searches.
 */
object ApolloEngine {
    const val DAY = 86_400_000L
    private const val SESSION_GAP = 30 * 60_000L

    fun localHour(ms: Long, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): Int =
        java.time.Instant.ofEpochMilli(ms).atZone(zone).hour

    fun build(events: List<PlayEvent>, searches: List<SearchEvent>, playlists: List<ImportedPlaylist>, nowMs: Long,
              halfLifeDays: Double = 90.0, hourOf: (Long) -> Int = { localHour(it) }): ApolloModel {
        data class Acc(var plays: Int = 0, var first: Long = Long.MAX_VALUE, var last: Long = 0, var heat: Double = 0.0,
                       val hours: IntArray = IntArray(24), val vids: MutableMap<String, Int> = HashMap(),
                       val titles: MutableMap<String, Int> = HashMap(), val artists: MutableMap<String, Int> = HashMap())
        val acc = LinkedHashMap<String, Acc>()
        val keyOfEvent = ArrayList<String>(events.size)
        // One video id = one song: a re-upload channel never splits a song. Each video's identity is the key most of
        // its plays carry, and plays from the YouTube Music app break ties.
        val votes = HashMap<String, HashMap<String, Double>>()
        val rawKey = events.map { e ->
            val aDisp = Normalize.artistDisplay(e.rawArtist)
            Normalize.songKey(Normalize.artistKey(e.rawArtist), Normalize.cleanTitle(e.rawTitle, aDisp))
        }
        events.forEachIndexed { i, e ->
            votes.getOrPut(e.videoId) { HashMap() }.merge(rawKey[i], if (e.viaMusicApp) 1.5 else 1.0, Double::plus)
        }
        val canonical = votes.mapValues { (_, v) -> v.maxByOrNull { it.value }!!.key }
        // One display name per artist, across all of that artist's songs; names from VEVO channels weigh little.
        val artistNames = HashMap<String, HashMap<String, Double>>()
        for ((i, e) in events.withIndex()) {
            val key = canonical.getValue(e.videoId)
            val own = rawKey[i] == key
            val aDisp = Normalize.artistDisplay(e.rawArtist)
            val title = Normalize.cleanTitle(e.rawTitle, aDisp)
            keyOfEvent.add(key)
            val a = acc.getOrPut(key) { Acc() }
            a.plays++
            a.first = minOf(a.first, e.timeMs)
            a.last = maxOf(a.last, e.timeMs)
            val ageDays = (nowMs - e.timeMs).coerceAtLeast(0) / DAY.toDouble()
            a.heat += 0.5.pow(ageDays / halfLifeDays)
            a.hours[hourOf(e.timeMs)]++
            a.vids.merge(e.videoId, 1, Int::plus)
            if (own) { // names come only from plays that carry the song's own identity
                a.titles.merge(title, 1, Int::plus)
                val vevo = e.rawArtist.trim().endsWith("vevo", ignoreCase = true)
                artistNames.getOrPut(key.substringBefore('|')) { HashMap() }.merge(aDisp, if (vevo) 0.2 else 1.0, Double::plus)
            }
        }
        val songs = acc.mapValues { (k, a) ->
            Song(k, artistNames[k.substringBefore('|')]!!.maxByOrNull { it.value }!!.key,
                a.titles.maxByOrNull { it.value }!!.key, a.vids.maxByOrNull { it.value }!!.key, a.vids.keys.toSet(),
                a.plays, a.first, a.last, a.heat, a.hours)
        }
        val coPlay = HashMap<String, HashMap<String, Double>>()
        var sessionStart = 0
        for (i in events.indices) {
            val newSession = i == events.size - 1 || events[i + 1].timeMs - events[i].timeMs > SESSION_GAP
            if (newSession) {
                val seq = keyOfEvent.subList(sessionStart, i + 1).fold(ArrayList<String>()) { l, k ->
                    if (l.lastOrNull() != k) l.add(k); l }
                for (x in seq.indices) for (y in x + 1..minOf(x + 3, seq.size - 1)) {
                    val (p, q) = seq[x] to seq[y]
                    if (p == q) continue
                    val w = 1.0 / (y - x)
                    coPlay.getOrPut(p) { HashMap() }.merge(q, w, Double::plus)
                    coPlay.getOrPut(q) { HashMap() }.merge(p, w, Double::plus)
                }
                sessionStart = i + 1
            }
        }
        val trimmed = coPlay.mapValues { (_, m) -> m.entries.sortedByDescending { it.value }.take(50).associate { it.toPair() } }
        val artists = songs.values.groupBy { it.key.substringBefore('|') }.map { (k, list) ->
            Artist(k, list.first().artist, list.sumOf { it.plays },
                list.sumOf { it.heat }, list.sortedByDescending { it.heat }.map { it.key })
        }.sortedByDescending { it.heat }
        val known = (songs.values.map { Normalize.alnum(it.title) } + artists.map { it.key }).filter { it.length > 2 }.toSet()
        val searchedNeverPlayed = searches.filter { it.inMusicApp }.map { it.query.trim() }
            .filter { q -> val n = Normalize.alnum(q); n.length > 2 && known.none { it.contains(n) || n.contains(it) } }
            .distinctBy { it.lowercase() }.take(40)
        val byVideo = songs.values.flatMap { s -> s.variants.map { it to s.key } }.toMap()
        val imported = playlists.mapNotNull { p ->
            val keys = p.videoIds.mapNotNull { byVideo[it] }.distinct()
            if (keys.isEmpty()) null else Mix("pl:${p.name}", p.name,
                "Your playlist from YouTube (${keys.size} of ${p.videoIds.size} songs also in your history)", keys)
        }
        return ApolloModel(songs, artists, trimmed, searchedNeverPlayed, imported, nowMs)
    }

    private fun ranked(m: ApolloModel, fb: Feedback, keys: Collection<String>, score: (Song) -> Double) =
        keys.mapNotNull { m.songs[it] }.map { it to score(it) * fb.weight(it) }.filter { it.second > 0 }
            .sortedByDescending { it.second }.map { it.first.key }

    fun mixes(m: ApolloModel, fb: Feedback, nowMs: Long, hour: Int): List<Mix> {
        val all = m.songs.keys
        val out = ArrayList<Mix>()
        out += Mix("foryou", "Apollo For You", "Your heat, what you play together, and what you used to love",
            forYou(m, fb, emptyList(), nowMs, hour, 60).map { it.songKey })
        val recent = all.filter { m.songs[it]!!.lastMs >= nowMs - 30 * DAY }
        out += Mix("repeat", "On Repeat", "Most played in your last 30 days",
            ranked(m, fb, recent) { s -> s.plays * 0.5.pow((nowMs - s.lastMs) / (30.0 * DAY)) }.take(50))
        out += Mix("heavy", "Heavy Rotation", "Your all-time heat, recent plays weighted higher",
            ranked(m, fb, all) { it.heat }.take(100))
        val h1 = (hour + 23) % 24
        val h2 = (hour + 1) % 24
        out += Mix("now", "Right Now (${"%02d".format(hour)}:00)", "What you play at this hour",
            ranked(m, fb, all.filter { m.songs[it]!!.plays >= 2 }) { s ->
                (s.hours[h1] + s.hours[hour] + s.hours[h2]).toDouble() / s.plays * s.heat }
                .filter { m.songs[it]!!.let { s -> s.hours[h1] + s.hours[hour] + s.hours[h2] } > 0 }.take(50))
        out += Mix("rediscover", "Rediscover", "Played 3+ times, quiet for 45+ days",
            ranked(m, fb, all.filter { m.songs[it]!!.let { s -> s.plays >= 3 && s.lastMs < nowMs - 45 * DAY } }) {
                it.plays.toDouble() }.take(50))
        m.artists.take(8).forEach { a ->
            out += Mix("artist:${a.key}", "${a.name} Essentials", "${a.plays} plays of ${a.name}",
                ranked(m, fb, a.songKeys) { it.heat }.take(40))
        }
        out += m.playlists
        return out.filter { it.songKeys.isNotEmpty() }
    }

    /** "More like this": the songs you play around it, then the same artist. */
    fun moreLike(m: ApolloModel, fb: Feedback, seed: String, n: Int = 40): List<Pick> {
        val s = m.songs[seed] ?: return emptyList()
        val near = (m.coPlay[seed] ?: emptyMap()).entries.sortedByDescending { it.value * (m.songs[it.key]?.let(fb::weight) ?: 0.0) }
            .filter { (m.songs[it.key]?.let(fb::weight) ?: 0.0) > 0 }
            .map { Pick(it.key, "You often play it around ${s.title}") }
        val sameArtist = m.artists.firstOrNull { it.key == seed.substringBefore('|') }?.songKeys.orEmpty()
            .filter { it != seed && m.songs[it]!!.let(fb::weight) > 0 }.map { Pick(it, "More ${s.artist}") }
        return (listOf(Pick(seed, "Your pick")) + near + sameArtist).distinctBy { it.songKey }.take(n)
    }

    /** The blend: favourites by heat, neighbours of what you just played, rediscoveries; interleaved, deduped. */
    fun forYou(m: ApolloModel, fb: Feedback, recentKeys: List<String>, nowMs: Long, hour: Int, n: Int): List<Pick> {
        val hot = ranked(m, fb, m.songs.keys) { it.heat }.map { Pick(it, "In your heavy rotation") }
        val seeds = recentKeys.ifEmpty { m.songs.values.sortedByDescending { it.lastMs }.take(5).map { it.key } }
        val near = seeds.flatMap { s -> moreLike(m, fb, s, 12).drop(1).filter { it.why.startsWith("You often") } }
        val old = ranked(m, fb, m.songs.keys.filter { m.songs[it]!!.let { s -> s.plays >= 3 && s.lastMs < nowMs - 45 * DAY } }) {
            it.plays.toDouble() }.map { Pick(it, "You loved it; it's been a while") }
        val out = LinkedHashMap<String, Pick>()
        var i = 0
        while (out.size < n && (i < hot.size || i < near.size || i < old.size)) {
            listOfNotNull(hot.getOrNull(i * 2), near.getOrNull(i), hot.getOrNull(i * 2 + 1), old.getOrNull(i))
                .forEach { p -> if (p.songKey !in out) out[p.songKey] = p }
            i++
        }
        return out.values.take(n)
    }

    fun search(m: ApolloModel, q: String): List<String> {
        val needle = q.trim().lowercase()
        if (needle.isEmpty()) return emptyList()
        return m.songs.values.filter { it.title.lowercase().contains(needle) || it.artist.lowercase().contains(needle) }
            .sortedByDescending { it.heat }.map { it.key }
    }
}
