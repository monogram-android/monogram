package org.monogram.network.http

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.TransferListener
import java.io.IOException

@androidx.media3.common.util.UnstableApi
internal class LeasedFileDataSource(private val cache: FileCache, private val key: String) :
    DataSource {
    private val delegate = FileDataSource()
    private var lease: FileCache.Lease? = null

    override fun addTransferListener(listener: TransferListener) =
        delegate.addTransferListener(listener)

    override fun open(dataSpec: DataSpec): Long {
        close()
        val acquired = cache.acquire(key) ?: throw IOException("Cached media unavailable")
        lease = acquired
        try {
            return delegate.open(dataSpec.buildUpon().setUri(Uri.fromFile(acquired.file)).build())
        } catch (error: Exception) {
            close()
            throw error
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        delegate.read(buffer, offset, length)

    override fun getUri(): Uri? = delegate.uri

    override fun close() {
        try {
            delegate.close()
        } finally {
            lease?.close()
            lease = null
        }
    }
}
