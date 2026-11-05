package com.apexforge.genesisplayer.data.vault

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener
import java.io.IOException

/**
 * Plays `aurumvault://<fileId>` by decrypting the vault file chunk by chunk in memory. Seeks map straight onto
 * [VaultCrypto.Reader]; no decrypted byte is ever written to disk or to a cache.
 */
class VaultDataSource(private val ctx: Context) : BaseDataSource(/* isNetwork = */ false) {
    private var reader: VaultCrypto.Reader? = null
    private var uri: Uri? = null
    private var pos = 0L
    private var remaining = 0L

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        val fileId = dataSpec.uri.host ?: throw IOException("vault uri without id")
        val r = VaultCrypto.Reader(Vault.key(ctx), Vault.fileFor(ctx, fileId))
        reader = r
        uri = dataSpec.uri
        pos = dataSpec.position
        if (pos > r.plainLength) throw IOException("seek past end")
        remaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else r.plainLength - pos
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val n = reader!!.read(pos, buffer, offset, minOf(length.toLong(), remaining).toInt())
        if (n < 0) return C.RESULT_END_OF_INPUT
        pos += n; remaining -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        reader?.let { runCatching { it.close() }; reader = null; transferEnded() }
    }

    companion object {
        const val SCHEME = "aurumvault"
        fun uriFor(fileId: String): Uri = Uri.parse("$SCHEME://$fileId")
    }
}

/** Routes vault URIs to [VaultDataSource] and everything else to the normal http/file/asset stack. */
class RoutingDataSource(private val vault: DataSource, private val upstream: DataSource) : DataSource {
    private var active: DataSource? = null
    override fun addTransferListener(transferListener: TransferListener) {
        vault.addTransferListener(transferListener); upstream.addTransferListener(transferListener)
    }
    override fun open(dataSpec: DataSpec): Long {
        val ds = if (dataSpec.uri.scheme == VaultDataSource.SCHEME) vault else upstream
        active = ds
        return ds.open(dataSpec)
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int) = active!!.read(buffer, offset, length)
    override fun getUri(): Uri? = active?.uri
    override fun getResponseHeaders(): Map<String, List<String>> = active?.responseHeaders ?: emptyMap()
    override fun close() { try { active?.close() } finally { active = null } }

    class Factory(private val ctx: Context) : DataSource.Factory {
        private val upstream = DefaultDataSource.Factory(ctx)
        override fun createDataSource(): DataSource = RoutingDataSource(VaultDataSource(ctx), upstream.createDataSource())
    }
}
