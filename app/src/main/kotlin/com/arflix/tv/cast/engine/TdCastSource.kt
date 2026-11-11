package com.arflix.tv.cast.engine

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.io.IOException

/**
 * Wraps a Media3 [DataSource.Factory] (such as ARVIO's fileCapableDataSourceFactory or TdDataSource)
 * into TMPlayer's [CastSource] interface, allowing the cast server, MkvHls, and MediaProbe
 * to read random offsets from Telegram or HTTP sources.
 */
@UnstableApi
class TdCastSource(
    private val factory: DataSource.Factory,
    inputUri: Uri,
    initialLength: Long = -1L
) : CastSource {

    val uri: Uri = run {
        val path = inputUri.path
        if (inputUri.scheme?.startsWith("http", ignoreCase = true) == true && path?.contains("/file/") == true) {
            val fileId = path.substringAfter("/file/").substringBefore("?").substringBefore("/")
            if (fileId.isNotBlank()) Uri.parse("tdfile://$fileId") else inputUri
        } else {
            inputUri
        }
    }

    private var _length: Long = initialLength

    override val length: Long
        get() {
            if (_length > 0L) return _length
            runCatching {
                val ds = factory.createDataSource()
                try {
                    val rem = ds.open(DataSpec.Builder().setUri(uri).setPosition(0L).build())
                    if (rem > 0L) _length = rem
                } finally {
                    runCatching { ds.close() }
                }
            }
            return _length.coerceAtLeast(0L)
        }

    override fun open(position: Long): CastReader {
        val dataSource = factory.createDataSource()
        val dataSpec = DataSpec.Builder()
            .setUri(uri)
            .setPosition(position)
            .setLength(C.LENGTH_UNSET.toLong())
            .build()
        try {
            dataSource.open(dataSpec)
        } catch (e: Exception) {
            runCatching { dataSource.close() }
            throw IOException("Failed to open DataSource for $uri at $position: ${e.message}", e)
        }
        return object : CastReader {
            private var closed = false

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (closed) throw IOException("Reader closed")
                val n = dataSource.read(buffer, offset, length)
                return if (n == C.RESULT_END_OF_INPUT) -1 else n
            }

            override fun close() {
                if (!closed) {
                    closed = true
                    runCatching { dataSource.close() }
                }
            }
        }
    }
}
