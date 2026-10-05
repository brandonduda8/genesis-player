package com.apexforge.genesisplayer.oacp

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.FileNotFoundException

/**
 * OACP v0.3 discovery endpoint for AURUM.
 *
 * Authority: com.apexforge.genesisplayer.oacp
 *   content://com.apexforge.genesisplayer.oacp/manifest -> assets/oacp.json
 *   content://com.apexforge.genesisplayer.oacp/context  -> assets/OACP.md
 *
 * Discovered by OACP assistants (e.g. Hark) via PackageManager lookup of
 * exported providers whose authority ends in ".oacp". Only the two exact
 * paths above are served; everything else is a FileNotFoundException.
 */
class OacpMetadataProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "com.apexforge.genesisplayer.oacp"
        private const val CODE_MANIFEST = 1
        private const val CODE_CONTEXT = 2
    }

    private val matcher = UriMatcher(UriMatcher.NO_MATCH).apply {
        addURI(AUTHORITY, "manifest", CODE_MANIFEST)
        addURI(AUTHORITY, "context", CODE_CONTEXT)
    }

    override fun onCreate(): Boolean = true

    override fun openAssetFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val ctx = context ?: throw FileNotFoundException("OACP: provider has no context")
        val assetName = when (matcher.match(uri)) {
            CODE_MANIFEST -> "oacp.json"
            CODE_CONTEXT -> "OACP.md"
            else -> throw FileNotFoundException("OACP: unknown path ${uri.path}")
        }
        return ctx.assets.openFd(assetName).parcelFileDescriptor
    }

    override fun getType(uri: Uri): String? = when (matcher.match(uri)) {
        CODE_MANIFEST -> "application/json"
        CODE_CONTEXT -> "text/markdown"
        else -> null
    }

    override fun query(
        uri: Uri, projection: Array<String>?, selection: String?,
        selectionArgs: Array<String>?, sortOrder: String?
    ): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0

    override fun update(
        uri: Uri, values: ContentValues?, selection: String?,
        selectionArgs: Array<String>?
    ): Int = 0
}
