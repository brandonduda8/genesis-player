package com.apexforge.genesisplayer.data

/** The web player's three energy lanes (All is the absence of a filter). */
enum class Energy { BANGER, SOFT, MID }

/**
 * Golden Player Phase 1: the crate's energy lanes (Bangers / Soft / Middle).
 *
 * The remote catalog carries NO energy field, so energy is INFERRED from the
 * tags it does carry — genre, mood, and shelf membership — and the UI says so
 * ("energy read from catalog tags"). Each matching signal votes; the side
 * with more votes wins, ties and untagged tracks land in Middle. Nothing is
 * invented: a track with no tags is honestly "in between".
 *
 * Pure (no Android calls) so the API-34 instrumented tests pin it.
 */
object EnergyRules {
    private val bangerGenres = setOf(
        "hip-hop/rap", "trap", "dubstep", "metal", "punk", "electronic",
        "tech house", "house", "future bass", "drum & bass", "phonk",
        "dark phonk", "hardstyle", "rock", "hardcore"
    )
    private val softGenres = setOf(
        "r&b/soul", "soundtrack", "ambient", "lo-fi", "acoustic", "folk",
        "singer-songwriter", "classical", "jazz", "soft"
    )
    private val bangerMoods = setOf("defiant", "aggressive", "energizing", "fiery")
    private val softMoods = setOf(
        "emotional", "melancholy", "yearning", "heartbroken", "sentimental",
        "peaceful", "tender", "romantic", "calm"
    )
    private val bangerShelves = setOf("alt bangers", "rap rotation")
    private val softShelves = setOf("cloud nine")

    fun infer(genre: String, mood: String, shelves: Collection<String>): Energy {
        val g = genre.trim().lowercase()
        val m = mood.trim().lowercase()
        val s = shelves.map { it.trim().lowercase() }
        var up = 0
        var down = 0
        if (g in bangerGenres) up++
        if (g in softGenres) down++
        if (m in bangerMoods) up++
        if (m in softMoods) down++
        if (s.any { it in bangerShelves }) up++
        if (s.any { it in softShelves }) down++
        return when {
            up > down -> Energy.BANGER
            down > up -> Energy.SOFT
            else -> Energy.MID
        }
    }

    /** Energy for every track in the active library (one pass over playlists). */
    fun forLibrary(): Map<String, Energy> {
        val shelvesById = mutableMapOf<String, MutableList<String>>()
        Library.playlists.forEach { pl ->
            pl.trackIds.forEach { shelvesById.getOrPut(it) { mutableListOf() }.add(pl.name) }
        }
        return Library.tracks.associate { t ->
            t.id to infer(t.genre, t.mood, shelvesById[t.id].orEmpty())
        }
    }
}
