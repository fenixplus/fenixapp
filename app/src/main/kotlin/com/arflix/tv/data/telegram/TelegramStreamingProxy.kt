package com.arflix.tv.data.telegram

import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import com.arflix.tv.player.telegram.TdDataSource
import com.arflix.tv.player.telegram.tdFileUri
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.routing.get
import io.ktor.server.routing.head
import io.ktor.server.routing.options
import io.ktor.server.routing.routing
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.drinkless.tdlib.TdApi
import java.net.ServerSocket
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs a local Ktor HTTP server on a random port.
 * ExoPlayer, Chromecast, and External Players (VLC, MX Player, etc.) stream from
 * http://<IP>:PORT/{token}/file/{fileId} (or /local/{id}) with byte-range support.
 *
 * This proxy delegates TDLib chunk reading directly to [TdDataSource], ensuring:
 * 1. 15-second stall detection with automatic CancelDownloadFile and backoff recovery.
 * 2. Dedicated end-of-file metadata probing (Cues / moov atom) without stalling playback.
 * 3. Exact byte verification (preventing reading unwritten sparse file zeros).
 * 4. High-performance NIO FileChannel disk reading.
 */
@OptIn(UnstableApi::class)
@Singleton
class TelegramStreamingProxy @Inject constructor(
    private val client: TelegramClient,
    private val downloadManager: com.arflix.tv.data.download.DownloadManager
) {
    companion object {
        private const val TAG = "TelegramProxy"
        private const val BUFFER_SIZE = 64 * 1024 // 64 KB buffer for streaming
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val streamAccess = TelegramStreamAccess()
    private var port: Int = 0
    private var server: io.ktor.server.engine.ApplicationEngine? = null

    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    private val _errorState = MutableStateFlow<String?>(null)
    val errorState: StateFlow<String?> = _errorState.asStateFlow()

    @Volatile private var lastStreamedFileId: Int? = null
    private val activeStreams = java.util.concurrent.ConcurrentHashMap<Int, java.util.concurrent.CopyOnWriteArrayList<Job>>()

    private val activeConnectionCount = java.util.concurrent.atomic.AtomicInteger(0)
    @Volatile private var lastActivityTimeMs = System.currentTimeMillis()
    var onIdleTimeout: (() -> Unit)? = null
    private var idleCheckJob: Job? = null

    init {
        port = findFreePort()
        Log.d(TAG, "Initialized with port $port")
    }

    fun isRunning(): Boolean = server != null

    fun getPort(): Int = port

    fun start() {
        if (server != null) return

        _isReady.value = false
        _errorState.value = null
        // Always allocate a fresh free port to prevent Linux TIME_WAIT conflicts
        port = findFreePort()

        server = embeddedServer(CIO, port = port, host = "0.0.0.0") {
            routing {
                // Preflight OPTIONS for Chromecast and Web Video Caster
                options("/{token}/file/{fileId}") {
                    applyCorsHeaders(call.response)
                    call.respond(HttpStatusCode.OK)
                }

                head("/{token}/file/{fileId}") {
                    val fileId = call.parameters["fileId"]?.toIntOrNull()
                    if (fileId == null || !streamAccess.isAllowed(call.parameters["token"], fileId)) {
                        call.respond(HttpStatusCode.NotFound)
                        return@head
                    }
                    val fileInfo = getFileInfo(fileId)
                    val totalSize = fileInfo?.second ?: 0L
                    if (totalSize <= 0L) {
                        call.respond(HttpStatusCode.NotFound)
                        return@head
                    }
                    val localPath = fileInfo?.first
                    val queryMime = call.request.queryParameters["mime"]
                    val resolvedMime = queryMime?.takeIf { it.isNotBlank() }
                        ?: when {
                            localPath?.endsWith(".mkv", ignoreCase = true) == true -> "video/x-matroska"
                            localPath?.endsWith(".webm", ignoreCase = true) == true -> "video/webm"
                            localPath?.endsWith(".mp4", ignoreCase = true) == true -> "video/mp4"
                            else -> "video/mp4"
                        }
                    applyCorsHeaders(call.response)
                    call.response.header(HttpHeaders.ContentType, resolvedMime)
                    call.response.header(HttpHeaders.ContentLength, totalSize.toString())
                    call.response.header(HttpHeaders.AcceptRanges, "bytes")
                    call.respond(HttpStatusCode.OK)
                }

                get("/{token}/file/{fileId}") {
                    val fileId = call.parameters["fileId"]?.toIntOrNull()
                    if (fileId == null || !streamAccess.isAllowed(call.parameters["token"], fileId)) {
                        call.respond(HttpStatusCode.NotFound)
                        return@get
                    }
                    val rangeHeader = call.request.headers[HttpHeaders.Range]
                    Log.d(TAG, "Request: fileId=$fileId range=$rangeHeader")

                    // Clean up previously streamed file if switching to another video
                    val prev = lastStreamedFileId
                    if (prev != null && prev != fileId) {
                        val staleJobs = activeStreams.remove(prev)
                        staleJobs?.forEach { job ->
                            if (job.isActive) {
                                Log.i(TAG, "Canceling stale streaming job for previous fileId=$prev")
                                job.cancel()
                            }
                        }
                        scope.launch { deleteFile(prev) }
                    }
                    lastStreamedFileId = fileId

                    val fileInfo = getFileInfo(fileId)
                    val totalSize = fileInfo?.second ?: 0L
                    if (totalSize <= 0L) {
                        call.respond(HttpStatusCode.NotFound)
                        return@get
                    }

                    val range = resolveByteRange(rangeHeader, totalSize)
                    if (range == null) {
                        call.response.header(HttpHeaders.ContentRange, "bytes */$totalSize")
                        call.respond(HttpStatusCode.RequestedRangeNotSatisfiable)
                        return@get
                    }

                    val start = range.start
                    val end = range.end
                    val length = range.length

                    val localPath = fileInfo?.first
                    val queryMime = call.request.queryParameters["mime"]
                    val resolvedMime = queryMime?.takeIf { it.isNotBlank() }
                        ?: when {
                            localPath?.endsWith(".mkv", ignoreCase = true) == true -> "video/x-matroska"
                            localPath?.endsWith(".webm", ignoreCase = true) == true -> "video/webm"
                            localPath?.endsWith(".mp4", ignoreCase = true) == true -> "video/mp4"
                            else -> "video/mp4"
                        }
                    val contentType = runCatching { ContentType.parse(resolvedMime) }.getOrDefault(ContentType.Video.MP4)

                    applyCorsHeaders(call.response)
                    call.response.header(HttpHeaders.ContentLength, length.toString())
                    call.response.header(HttpHeaders.AcceptRanges, "bytes")
                    if (rangeHeader != null) {
                        call.response.header(
                            HttpHeaders.ContentRange,
                            "bytes $start-$end/$totalSize"
                        )
                    }

                    val status = if (rangeHeader != null) HttpStatusCode.PartialContent else HttpStatusCode.OK

                    activeConnectionCount.incrementAndGet()
                    lastActivityTimeMs = System.currentTimeMillis()
                    try {
                        call.respondBytesWriter(
                            contentType = contentType,
                            status = status
                        ) {
                            val currentJob = coroutineContext[Job]
                            if (currentJob != null) {
                                val list = activeStreams.getOrPut(fileId) { java.util.concurrent.CopyOnWriteArrayList() }
                                list.add(currentJob)
                                while (list.size > 2) {
                                    val oldest = list.removeAt(0)
                                    if (oldest.isActive) {
                                        Log.i(TAG, "Canceling oldest stream for fileId=$fileId (max 2 concurrent)")
                                        oldest.cancel()
                                    }
                                }
                            }

                            val dataSource = TdDataSource(
                                client = client,
                                isDownloadingCheck = { downloadManager.isDownloading(it) }
                            )
                            try {
                                val dataSpec = DataSpec.Builder()
                                    .setUri(tdFileUri(fileId))
                                    .setPosition(start)
                                    .setLength(length)
                                    .build()

                                withContext(Dispatchers.IO) {
                                    dataSource.open(dataSpec)
                                }

                                val buffer = ByteArray(BUFFER_SIZE)
                                var remaining = length
                                while (remaining > 0) {
                                    val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                                    val read = withContext(Dispatchers.IO) {
                                        dataSource.read(buffer, 0, toRead)
                                    }
                                    if (read < 0 || read == C.RESULT_END_OF_INPUT) break
                                    writeFully(buffer, 0, read)
                                    remaining -= read
                                }
                            } catch (e: Exception) {
                                Log.d(TAG, "Streaming connection closed for fileId=$fileId at offset=$start (${e.message})")
                            } finally {
                                if (currentJob != null) {
                                    activeStreams[fileId]?.remove(currentJob)
                                }
                                withContext(Dispatchers.IO) {
                                    runCatching { dataSource.close() }
                                }
                            }
                        }
                    } finally {
                        activeConnectionCount.decrementAndGet().coerceAtLeast(0)
                        lastActivityTimeMs = System.currentTimeMillis()
                    }
                }

                // Local disk files support (for downloaded content or external players)
                options("/{token}/local/{id}") {
                    applyCorsHeaders(call.response)
                    call.respond(HttpStatusCode.OK)
                }

                head("/{token}/local/{id}") {
                    val file = streamAccess.getLocalFile(call.parameters["token"], call.parameters["id"].orEmpty())
                    if (file == null || !file.exists() || file.length() <= 0L) {
                        call.respond(HttpStatusCode.NotFound)
                        return@head
                    }
                    val totalSize = file.length()
                    applyCorsHeaders(call.response)
                    call.response.header(HttpHeaders.ContentLength, totalSize.toString())
                    call.response.header(HttpHeaders.AcceptRanges, "bytes")
                    call.respond(HttpStatusCode.OK)
                }

                get("/{token}/local/{id}") {
                    val file = streamAccess.getLocalFile(call.parameters["token"], call.parameters["id"].orEmpty())
                    if (file == null || !file.exists() || file.length() <= 0L) {
                        call.respond(HttpStatusCode.NotFound)
                        return@get
                    }
                    val totalSize = file.length()
                    val rangeHeader = call.request.headers[HttpHeaders.Range]
                    val range = resolveByteRange(rangeHeader, totalSize)
                    if (range == null) {
                        call.response.header(HttpHeaders.ContentRange, "bytes */$totalSize")
                        call.respond(HttpStatusCode.RequestedRangeNotSatisfiable)
                        return@get
                    }
                    val start = range.start
                    val end = range.end
                    val length = range.length

                    applyCorsHeaders(call.response)
                    call.response.header(HttpHeaders.ContentLength, length.toString())
                    call.response.header(HttpHeaders.AcceptRanges, "bytes")
                    if (rangeHeader != null) {
                        call.response.header(
                            HttpHeaders.ContentRange,
                            "bytes $start-$end/$totalSize"
                        )
                    }

                    val status = if (rangeHeader != null) HttpStatusCode.PartialContent else HttpStatusCode.OK

                    call.respondBytesWriter(
                        contentType = ContentType.Video.Any,
                        status = status
                    ) {
                        activeConnectionCount.incrementAndGet()
                        lastActivityTimeMs = System.currentTimeMillis()
                        try {
                            java.io.RandomAccessFile(file, "r").use { raf ->
                                raf.seek(start)
                                val buffer = ByteArray(BUFFER_SIZE)
                                var remaining = length
                                while (remaining > 0) {
                                    val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                                    val read = raf.read(buffer, 0, toRead)
                                    if (read <= 0) break
                                    writeFully(buffer, 0, read)
                                    remaining -= read
                                }
                            }
                        } finally {
                            activeConnectionCount.decrementAndGet().coerceAtLeast(0)
                            lastActivityTimeMs = System.currentTimeMillis()
                        }
                    }
                }
            }
        }
        server!!.start(wait = false)
        _isReady.value = true
        lastActivityTimeMs = System.currentTimeMillis()
        activeConnectionCount.set(0)
        idleCheckJob?.cancel()
        idleCheckJob = scope.launch {
            while (isRunning()) {
                kotlinx.coroutines.delay(10_000L)
                val now = System.currentTimeMillis()
                val idleMs = now - lastActivityTimeMs
                if (activeConnectionCount.get() == 0 && idleMs >= 45_000L) {
                    Log.i(TAG, "Proxy idle for ${idleMs}ms (external player disconnected). Auto-stopping.")
                    onIdleTimeout?.invoke()
                    break
                }
            }
        }
        Log.d(TAG, "Streaming proxy started on port $port")
    }

    fun stop() {
        idleCheckJob?.cancel()
        idleCheckJob = null
        activeConnectionCount.set(0)
        _isReady.value = false
        _errorState.value = null
        lastStreamedFileId?.let { scope.launch { deleteFile(it) } }
        lastStreamedFileId = null
        server?.stop(0, 0)
        server = null
        Log.d(TAG, "Streaming proxy stopped")
    }

    private suspend fun deleteFile(fileId: Int) {
        if (downloadManager.isDownloading(fileId)) {
            Log.d(TAG, "File $fileId is downloading in background, skipping proxy cancellation/deletion")
            return
        }

        runCatching {
            client.sendRequest(TdApi.CancelDownloadFile().also { req ->
                req.fileId = fileId
                req.onlyIfPending = false
            })
        }
        runCatching {
            client.sendRequest(TdApi.DeleteFile().also { it.fileId = fileId })
            Log.d(TAG, "Deleted cached streaming file $fileId")
        }
    }

    private fun applyCorsHeaders(response: io.ktor.server.response.ApplicationResponse) {
        response.header("Access-Control-Allow-Origin", "*")
        response.header("Access-Control-Allow-Methods", "GET, HEAD, OPTIONS")
        response.header("Access-Control-Allow-Headers", "Range, Content-Type, Accept-Encoding")
        response.header("Access-Control-Expose-Headers", "Content-Range, Content-Length, Accept-Ranges")
    }

    /** Stream URL for [fileId]; only file IDs passed through here are served. */
    fun getUrl(fileId: Int): String = "http://127.0.0.1:$port${streamAccess.pathFor(fileId)}"

    /** Stream URL for a local disk [file]; only files passed through here are served. */
    fun getLocalUrl(file: java.io.File): String = "http://127.0.0.1:$port${streamAccess.pathForLocal(file)}"

    private suspend fun getFileInfo(fileId: Int): Pair<String?, Long>? {
        val file = client.sendRequest(TdApi.GetFile(fileId)) as? TdApi.File ?: return null
        val totalSize = file.size.takeIf { it > 0 } ?: file.expectedSize
        val localPath = file.local?.path?.takeIf { it.isNotBlank() }
        return Pair(localPath, totalSize)
    }

    private fun findFreePort(): Int {
        ServerSocket(0).use { return it.localPort }
    }
}
