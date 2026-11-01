package com.apexforge.genesisplayer.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject

/**
 * Files explicitly selected by the listener with Android's Storage Access Framework.
 *
 * No blanket storage permission, server upload, file copy, or YouTube extraction.
 * We retain only display names and persisted content:// read grants, so songs
 * already on the phone remain available with airplane mode enabled.
 */
data class LocalAudioTrack(val uri: String, val title: String)

object LocalAudioLibrary {
    private const val PREFS = "aurum_local_audio"
    private const val KEY = "selected_files"
    private const val MAX_TRACKS = 2000

    private fun preferences(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun saved(context: Context): JSONArray {
        val raw = preferences(context).getString(KEY, "[]") ?: "[]"
        return try { JSONArray(raw) } catch (_: Exception) { JSONArray() }
    }

    private fun persistedGrants(context: Context): Set<String> =
        context.contentResolver.persistedUriPermissions
            .filter { it.isReadPermission }
            .map { it.uri.toString() }
            .toSet()

    /** Only surface URIs whose Android read permission still exists. */
    @Synchronized
    fun tracks(context: Context): List<LocalAudioTrack> {
        val granted = persistedGrants(context)
        val data = saved(context)
        val found = LinkedHashMap<String, LocalAudioTrack>()
        for (i in 0 until data.length()) {
            val item = data.optJSONObject(i) ?: continue
            val uri = item.optString("uri")
            if (uri.isBlank() || uri !in granted || Uri.parse(uri).scheme != "content") continue
            val title = item.optString("title").ifBlank { "Local audio" }
            found[uri] = LocalAudioTrack(uri, title)
        }
        return found.values.toList()
    }

    /**
     * Store user-chosen audio files. OpenMultipleDocuments returns persistable
     * SAF URIs. A refused grant is ignored, never treated as an offline file.
     */
    @Synchronized
    fun add(context: Context, selected: List<Uri>): Int {
        val records = LinkedHashMap<String, String>()
        val old = saved(context)
        for (i in 0 until old.length()) {
            val item = old.optJSONObject(i) ?: continue
            val uri = item.optString("uri")
            if (uri.isNotBlank()) records[uri] = item.optString("title", "Local audio")
        }
        val before = records.size
        for (uri in selected) {
            if (records.size >= MAX_TRACKS) break
            if (uri.scheme != "content") continue
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                val mime = context.contentResolver.getType(uri)
                if (mime != null && !mime.startsWith("audio/") && mime != "application/octet-stream") {
                    continue
                }
                records[uri.toString()] = displayTitle(context, uri)
            } catch (_: Exception) {
                // Revoked, unsupported, or inaccessible provider: do not record.
            }
        }
        write(context, records)
        return (records.size - before).coerceAtLeast(0)
    }

    @Synchronized
    fun remove(context: Context, uri: String) {
        val records = LinkedHashMap<String, String>()
        val old = saved(context)
        for (i in 0 until old.length()) {
            val item = old.optJSONObject(i) ?: continue
            val stored = item.optString("uri")
            if (stored.isNotBlank() && stored != uri) {
                records[stored] = item.optString("title", "Local audio")
            }
        }
        write(context, records)
        try {
            context.contentResolver.releasePersistableUriPermission(
                Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: Exception) {
            // Grant may already have been revoked by the provider.
        }
    }

    private fun write(context: Context, records: Map<String, String>) {
        val arr = JSONArray()
        records.forEach { (uri, title) ->
            arr.put(JSONObject().put("uri", uri).put("title", title))
        }
        preferences(context).edit().putString(KEY, arr.toString()).apply()
    }

    private fun displayTitle(context: Context, uri: Uri): String {
        val name = try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME),
                null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } catch (_: Exception) {
            null
        }
        val label = (name ?: uri.lastPathSegment ?: "Local audio").substringAfterLast('/')
        return label.substringBeforeLast('.', label).ifBlank { "Local audio" }
    }
}
