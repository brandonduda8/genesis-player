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
    val addedAtMs: Long
)

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

    fun encode(entries: List<VaultEntry>): ByteArray = JSONObject().put("v", 1).put("entries", JSONArray().also { a ->
        entries.forEach { e ->
            a.put(JSONObject().put("t", e.trackId).put("f", e.fileId).put("ti", e.title).put("ar", e.artist)
                .put("p", e.plainBytes).put("c", e.cipherBytes).put("at", e.addedAtMs))
        }
    }).toString().toByteArray(Charsets.UTF_8)

    fun decode(bytes: ByteArray): List<VaultEntry> {
        val a = JSONObject(String(bytes, Charsets.UTF_8)).getJSONArray("entries")
        return List(a.length()) { i ->
            val o = a.getJSONObject(i)
            VaultEntry(o.getString("t"), o.getString("f"), o.optString("ti"), o.optString("ar"), o.getLong("p"),
                o.getLong("c"), o.getLong("at"))
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
