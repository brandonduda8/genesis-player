package com.apexforge.genesisplayer.data.vault

import org.json.JSONArray
import org.json.JSONObject

/** One encrypted offline copy. [fileId] is a random opaque name: the file system never reveals the library. */
data class VaultEntry(
    val trackId: String,
    val fileId: String,
    val title: String,
    val artist: String,
    val plainBytes: Long,
    val cipherBytes: Long,
    val addedAtMs: Long,
    /** 2.4 golden, charge 3b: LOSSLESS (FLAC/ALAC/WAV) or BULK (lossy). Entries from older builds read as BULK. */
    val tier: Tier = Tier.BULK
)

enum class Tier { LOSSLESS, BULK }

object VaultIndex {
    const val DEFAULT_CAP_BYTES = 2L * 1024 * 1024 * 1024 // 2 GiB, the shipped default
    val CAP_CHOICES = listOf(512L shl 20, 1L shl 30, 2L shl 30, 4L shl 30, 8L shl 30)

    /**
     * Which entries to evict so that [entries] fit under [capBytes]: oldest-downloaded first, never [keep] (the
     * track just added). Returns null when [keep] alone exceeds the cap (it can't be kept, so the caller drops it).
     */
    fun evictionPlan(entries: List<VaultEntry>, capBytes: Long, keep: String? = null): List<VaultEntry>? {
        var total = entries.sumOf { it.cipherBytes }
        val keeper = entries.firstOrNull { it.trackId == keep }
        if (keeper != null && keeper.cipherBytes > capBytes) return null
        val victims = ArrayList<VaultEntry>()
        for (e in entries.sortedBy { it.addedAtMs }) {
            if (total <= capBytes) break
            if (e.trackId == keep) continue
            victims += e
            total -= e.cipherBytes
        }
        return victims
    }

    /** The lossless tier's own ceiling: 2 GiB, or the overall cap if the owner chose a smaller one. */
    const val LOSSLESS_CAP_BYTES = 2L * 1024 * 1024 * 1024

    /**
     * What kind of audio a file is, from its first bytes (never from a file name or a claim):
     * FLAC ("fLaC"), WAV ("RIFF....WAVE"), and ALAC (an MP4 "ftyp" file whose sample description says "alac") are
     * LOSSLESS; everything else (MP3, AAC, Opus, Vorbis, unknown) is BULK.
     */
    fun tierOf(head: ByteArray, n: Int = head.size): Tier {
        fun at(i: Int, s: String) = i + s.length <= n && s.indices.all { head[i + it] == s[it].code.toByte() }
        if (at(0, "fLaC")) return Tier.LOSSLESS
        if (at(0, "RIFF") && at(8, "WAVE")) return Tier.LOSSLESS
        if (at(4, "ftyp")) { for (i in 8 until n - 4) if (at(i, "alac")) return Tier.LOSSLESS }
        return Tier.BULK
    }

    /**
     * Tiered eviction (oldest first, never [keep]):
     *  1. while the lossless tier exceeds min([losslessCap], [capBytes]), evict its oldest entries;
     *  2. then, while everything exceeds [capBytes], evict the oldest of what is left (either tier).
     * Returns null when [keep] alone can't fit its own limit.
     */
    fun tieredEvictionPlan(entries: List<VaultEntry>, capBytes: Long, losslessCap: Long = LOSSLESS_CAP_BYTES,
                           keep: String? = null): List<VaultEntry>? {
        val lCap = minOf(losslessCap, capBytes)
        val keeper = entries.firstOrNull { it.trackId == keep }
        if (keeper != null && keeper.cipherBytes > (if (keeper.tier == Tier.LOSSLESS) lCap else capBytes)) return null
        val victims = ArrayList<VaultEntry>()
        var lossless = entries.filter { it.tier == Tier.LOSSLESS }.sumOf { it.cipherBytes }
        for (e in entries.filter { it.tier == Tier.LOSSLESS }.sortedBy { it.addedAtMs }) {
            if (lossless <= lCap) break
            if (e.trackId == keep) continue
            victims += e; lossless -= e.cipherBytes
        }
        val rest = entries - victims.toSet()
        return victims + (evictionPlan(rest, capBytes, keep) ?: return null)
    }

    data class TierUse(val losslessBytes: Long, val bulkBytes: Long, val losslessCap: Long, val capBytes: Long)
    fun use(entries: List<VaultEntry>, capBytes: Long) = TierUse(
        entries.filter { it.tier == Tier.LOSSLESS }.sumOf { it.cipherBytes },
        entries.filter { it.tier == Tier.BULK }.sumOf { it.cipherBytes }, minOf(LOSSLESS_CAP_BYTES, capBytes), capBytes)

    fun encode(entries: List<VaultEntry>): ByteArray = JSONObject().put("v", 1).put("entries", JSONArray().also { a ->
        entries.forEach { e ->
            a.put(JSONObject().put("t", e.trackId).put("f", e.fileId).put("ti", e.title).put("ar", e.artist)
                .put("p", e.plainBytes).put("c", e.cipherBytes).put("at", e.addedAtMs).put("tr", e.tier.name))
        }
    }).toString().toByteArray(Charsets.UTF_8)

    fun decode(bytes: ByteArray): List<VaultEntry> {
        val a = JSONObject(String(bytes, Charsets.UTF_8)).getJSONArray("entries")
        return List(a.length()) { i ->
            val o = a.getJSONObject(i)
            VaultEntry(o.getString("t"), o.getString("f"), o.optString("ti"), o.optString("ar"), o.getLong("p"),
                o.getLong("c"), o.getLong("at"),
                if (o.optString("tr") == Tier.LOSSLESS.name) Tier.LOSSLESS else Tier.BULK)
        }
    }

    /** The one place that decides whether a source allows a local copy. Streaming-only stays streaming-only. */
    data class Permission(val allowed: Boolean, val reason: String)

    fun audiusTrackId(streamUrl: String): String? =
        Regex("^https://(?:[a-z0-9.-]+\\.)?audius\\.co/v1/tracks/([A-Za-z0-9]+)/stream").find(streamUrl)?.groupValues?.get(1)

    /** Read Audius's own track record: the artist must have enabled downloads, and the download must not be gated. */
    fun audiusPermission(trackJson: String): Permission {
        val d = JSONObject(trackJson).optJSONObject("data") ?: return Permission(false, "Audius returned no track record")
        val downloadable = d.optBoolean("is_downloadable", false) || d.optBoolean("downloadable", false) ||
            d.optJSONObject("download")?.optBoolean("is_downloadable", false) == true
        return when {
            d.optBoolean("is_download_gated", false) -> Permission(false, "the artist gates downloads")
            !downloadable -> Permission(false, "streaming only: the artist hasn't enabled downloads")
            else -> Permission(true, "the artist enabled downloads on Audius")
        }
    }
}
