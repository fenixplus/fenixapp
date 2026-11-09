package com.arflix.tv.player.telegram

import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.FileTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DataSourceException
import com.arflix.tv.data.telegram.TelegramClient
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException

/**
 * URI builder and parser for Telegram streams (`tdfile://<fileId>?type=video%2Fx-matroska&remote=...`).
 */
fun tdFileUri(fileId: Int, type: String? = null, remoteId: String? = null): Uri {
    val builder = Uri.Builder().scheme("tdfile").authority(fileId.toString())
    if (type != null) builder.appendQueryParameter(TYPE_PARAM, type)
    if (!remoteId.isNullOrBlank()) builder.appendQueryParameter(REMOTE_PARAM, remoteId)
    return builder.build()
}

private const val TYPE_PARAM = "type"
private const val REMOTE_PARAM = "remote"

@androidx.annotation.OptIn(UnstableApi::class)
fun streamType(mimeType: String, fileName: String): String? {
    if (mimeType.isNotBlank() && FileTypes.inferFileTypeFromMimeType(mimeType) != FileTypes.UNKNOWN) return mimeType
    return when (fileName.substringAfterLast('.', "").lowercase()) {
        "mkv", "mka", "mk3d" -> "video/x-matroska"
        "webm" -> "video/webm"
        "mp4", "m4v", "mov", "3gp" -> "video/mp4"
        "ts", "m2ts", "mts" -> "video/mp2t"
        "avi" -> "video/x-msvideo"
        "flv" -> "video/x-flv"
        else -> null
    }
}

/**
 * Native Media3 [DataSource] that streams a Telegram file directly from TDLib and disk (NIO)
 * without an intermediate HTTP proxy server.
 */
@UnstableApi
class TdDataSource(
    client: TelegramClient,
    isDownloadingCheck: ((Int) -> Boolean)? = null,
    private val onFileOpened: ((Int) -> Unit)? = null
) : BaseDataSource(true) {

    private val bytes = TdByteWindow(client, isDownloadingCheck)

    private var uri: Uri? = null
    private var headers: Map<String, List<String>> = emptyMap()
    private var position = 0L
    private var bytesRemaining = 0L
    private var opened = false

    private val ahead = ByteArray(AHEAD_BYTES)
    private var aheadAt = -1L
    private var aheadLength = 0

    override fun getUri(): Uri? = uri

    override fun getResponseHeaders(): Map<String, List<String>> = headers

    override fun open(dataSpec: DataSpec): Long {
        val startedAt = SystemClock.elapsedRealtime()
        transferInitializing(dataSpec)
        uri = dataSpec.uri
        val fileId = dataSpec.uri.authority?.toIntOrNull()
            ?: dataSpec.uri.lastPathSegment?.toIntOrNull()
            ?: throw IOException("Not a Telegram file URI: ${dataSpec.uri}")
        onFileOpened?.invoke(fileId)
        val remoteId = dataSpec.uri.getQueryParameter(REMOTE_PARAM)
        headers = dataSpec.uri.getQueryParameter(TYPE_PARAM)?.let { mapOf("Content-Type" to listOf(it)) } ?: emptyMap()

        position = dataSpec.position
        aheadAt = -1L

        bytes.watch(fileId, remoteId)

        val size = blocking(OPEN_TIMEOUT_MS) { bytes.lookUp() }
        if (position > size) throw DataSourceException(C.RESULT_END_OF_INPUT)

        bytesRemaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) {
            size - position
        } else {
            minOf(dataSpec.length, size - position)
        }

        if (!bytes.completed) blocking(OPEN_TIMEOUT_MS) { bytes.requestUnlessOnDisk(position) }

        opened = true
        transferStarted(dataSpec)
        val took = SystemClock.elapsedRealtime() - startedAt
        val isNearEnd = size > 0 && position > size - 16 * 1024 * 1024
        val purpose = when {
            position == 0L -> "início do arquivo"
            isNearEnd -> "metadados/índice no fim do arquivo"
            else -> "reprodução / seek"
        }
        Log.d(LOG_TAG, "📥 ExoPlayer pediu: offset=$position tamanho=${if (dataSpec.length == C.LENGTH_UNSET.toLong()) "${bytesRemaining / 1024 / 1024}MB" else "${bytesRemaining / 1024}KB"} ($purpose) em ${took}ms [fileId=$fileId]")
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT

        val read = readOnce(buffer, offset, length)

        position += read
        bytesRemaining -= read
        bytesTransferred(read)
        return read
    }

    private fun readOnce(buffer: ByteArray, offset: Int, length: Int): Int {
        if (aheadAt >= 0 && position >= aheadAt && position < aheadAt + aheadLength) {
            val from = (position - aheadAt).toInt()
            val count = minOf(length.toLong(), (aheadLength - from).toLong(), bytesRemaining).toInt()
            System.arraycopy(ahead, from, buffer, offset, count)
            return count
        }
        if (length < SMALL_READ_BYTES) {
            val filled = readThrough(ahead, 0, AHEAD_BYTES)
            aheadAt = position
            aheadLength = filled
            val count = minOf(length, filled)
            System.arraycopy(ahead, 0, buffer, offset, count)
            return count
        }
        return readThrough(buffer, offset, length)
    }

    private fun readThrough(buffer: ByteArray, offset: Int, length: Int): Int {
        var attempt = 0
        while (true) {
            val available = bytes.cachedAvailable(position) ?: awaitLogged()
            val wanted = minOf(length.toLong(), bytesRemaining, available).toInt()
            try {
                return bytes.read(position, buffer, offset, wanted)
            } catch (e: EOFException) {
                if (++attempt >= SHORT_READ_RETRIES) throw e
            }
        }
    }

    private fun awaitLogged(): Long {
        val startedAt = SystemClock.elapsedRealtime()
        val available = blocking(READ_TIMEOUT_MS) { bytes.awaitBytesAt(position) }
        val took = SystemClock.elapsedRealtime() - startedAt
        if (took >= SLOW_LOG_MS) Log.i(LOG_TAG, "waited $took ms for bytes at $position of file ${bytes.fileId}")
        return available
    }

    override fun close() {
        bytes.close()
        aheadAt = -1L
        uri = null
        headers = emptyMap()
        if (opened) {
            opened = false
            transferEnded()
        }
    }

    private fun <T> blocking(timeoutMs: Long, block: suspend () -> T): T = try {
        runBlocking { withTimeout(timeoutMs) { block() } }
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        throw InterruptedIOException("Cancelled")
    } catch (e: TimeoutCancellationException) {
        throw IOException("Telegram did not respond in time", e)
    }

    class Factory(
        private val client: TelegramClient,
        private val isDownloadingCheck: ((Int) -> Boolean)? = null,
        private val onFileOpened: ((Int) -> Unit)? = null
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = TdDataSource(client, isDownloadingCheck, onFileOpened)
    }

    private companion object {
        const val OPEN_TIMEOUT_MS = 120_000L
        const val SHORT_READ_RETRIES = 3
        const val AHEAD_BYTES = 64 * 1024
        const val SMALL_READ_BYTES = 4 * 1024
        const val SLOW_LOG_MS = 250L
        const val LOG_TAG = "TdDataSource"
        const val READ_TIMEOUT_MS = TdByteWindow.STALL_TIMEOUT_MS + 10_000L
    }
}
