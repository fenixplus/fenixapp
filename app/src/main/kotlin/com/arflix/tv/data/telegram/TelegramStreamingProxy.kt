package com.arflix.tv.data.telegram

import android.util.Log
import io.ktor.http.ContentRange
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
import io.ktor.server.routing.routing
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.drinkless.tdlib.TdApi
import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs a local Ktor HTTP server on a random port.
 * ExoPlayer streams from http://localhost:PORT/file/{fileId}
 * with full Range request support.
 *
 * TDLib downloads the requested byte range on demand via downloadFile
 * with offset + limit parameters, enabling seek without full download.
 */
@Singleton
class TelegramStreamingProxy @Inject constructor(
    private val client: TelegramClient
) {
    companion object {
        private const val TAG = "TelegramProxy"
        private const val CHUNK_SIZE = 1 * 1024 * 1024       // 1 MB served per ExoPlayer request (reduced from 2MB for faster start)
        private const val PREFETCH_SIZE = 20 * 1024 * 1024L  // 20 MB prefetch window sent to TDLib
        private const val DOWNLOAD_PRIORITY = 32              // max TDLib priority
        private const val POLL_INTERVAL_MS = 100L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var port: Int = 0
    private var server: io.ktor.server.engine.ApplicationEngine? = null
    
    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    private val _errorState = MutableStateFlow<String?>(null)
    val errorState: StateFlow<String?> = _errorState.asStateFlow()

    @Volatile private var lastStreamedFileId: Int? = null
    
    // Cache for metadata chunks (end of file) to avoid redundant Telegram API calls
    private val metadataCache = ConcurrentHashMap<String, ByteArray>()
    
    // Tracks ongoing metadata prefetch tasks to avoid "request storms" on the same file
    private val activePrefetches = ConcurrentHashMap.newKeySet<Int>()
    
    // Tracks calculated warmup size for each fileId (1MB to 5MB based on file size)
    private val metadataWarmupSizes = ConcurrentHashMap<Int, Long>()
    
    // Tracks if the metadata (moov atom) is fully cached and ready for a fileId
    private val metadataReady = ConcurrentHashMap.newKeySet<Int>()
    
    // Tracks if the first data request for a fileId has been delayed by the gate
    private val gateHandled = ConcurrentHashMap.newKeySet<Int>()
    
    // Mutex map to ensure only one thread interacts with TDLib per fileId at a time
    private val fileMutexes = ConcurrentHashMap<Int, Mutex>()
    
    // Tracks the last offset requested to TDLib for prefetch to avoid command flood
    private val lastRequestedOffsets = ConcurrentHashMap<Int, Long>()

    init {
        // Alocar a porta imediatamente para que getUrl retorne um valor válido
        // mesmo antes do servidor estar rodando.
        port = findFreePort()
        Log.d(TAG, "Initialized with port $port")
    }

    fun isRunning(): Boolean = server != null

    fun getPort(): Int = port

    fun start() {
        if (server != null) return
        
        _isReady.value = false
        _errorState.value = null
        // Garantir que temos uma porta válida (caso o findFreePort no init tenha falhado ou a porta tenha sido liberada)
        if (port == 0) port = findFreePort()
        
        server = embeddedServer(CIO, port = port, host = "0.0.0.0") {
            routing {
                get("/file/{fileId}") {
                    val fileId = call.parameters["fileId"]?.toIntOrNull()
                    Log.d(TAG, "Request: fileId=$fileId range=${call.request.headers[HttpHeaders.Range]}")
                    if (fileId == null) {
                        call.respond(HttpStatusCode.BadRequest)
                        return@get
                    }

                    // When a new file starts streaming, clean up the previous one so
                    // TDLib's downloaded chunks don't accumulate in tdlib_files/ indefinitely.
                    val prev = lastStreamedFileId
                    if (prev != null && prev != fileId) {
                        scope.launch { deleteFile(prev) }
                    }
                    lastStreamedFileId = fileId

                    val rangeHeader = call.request.headers[HttpHeaders.Range]
                    val (rangeStart, rangeEnd) = parseRange(rangeHeader)

                    // Get file info to know total size
                    val fileInfo = getFileInfo(fileId)
                    val totalSize = fileInfo?.second ?: 0L
                    val localPath = fileInfo?.first

                    if (totalSize <= 0L) {
                        call.respond(HttpStatusCode.NotFound)
                        return@get
                    }

                    // Start async metadata prefetch on first request for this file
                    prefetchMetadata(fileId, totalSize)

                    val start = rangeStart ?: 0L
                    val end = rangeEnd ?: (totalSize - 1L)
                    val length = end - start + 1

                    // Logging for metadata/index detection (ExoPlayer typical behavior)
                    val isMetadata = start > totalSize * 0.9 && totalSize > 10 * 1024 * 1024

                    call.response.header(HttpHeaders.ContentLength, length.toString())
                    call.response.header(HttpHeaders.AcceptRanges, "bytes")
                    call.response.header(
                        HttpHeaders.ContentRange,
                        "bytes $start-$end/$totalSize"
                    )

                    val status = HttpStatusCode.PartialContent

                    call.respondBytesWriter(
                        contentType = ContentType.Video.Any,
                        status = status
                    ) {
                        var offset = start
                        while (offset <= end) {
                            val chunkSize = minOf(CHUNK_SIZE.toLong(), end - offset + 1).toInt()
                            
                            val bytes = downloadChunk(fileId, localPath, offset, chunkSize, totalSize)
                            if (bytes == null || bytes.isEmpty()) break
                            
                            if (isMetadata && offset == start) {
                                Log.i(TAG, "✅ [METADADOS] Índice entregue ao player!")
                            }

                            writeFully(bytes)
                            offset += bytes.size
                        }
                    }
                }
            }
        }
        server!!.start(wait = false)
        _isReady.value = true
        Log.d(TAG, "Streaming proxy started on port $port")
    }

    fun stop() {
        _isReady.value = false
        _errorState.value = null
        lastStreamedFileId?.let { scope.launch { deleteFile(it) } }
        lastStreamedFileId = null
        server?.stop(0, 0)
        server = null
        Log.d(TAG, "Streaming proxy stopped")
    }

    private suspend fun deleteFile(fileId: Int) {
        metadataCache.clear() // Clear metadata cache when switching files
        activePrefetches.remove(fileId)
        metadataWarmupSizes.remove(fileId)
        metadataReady.remove(fileId)
        gateHandled.remove(fileId)
        fileMutexes.remove(fileId)
        lastRequestedOffsets.remove(fileId)
        runCatching {
            client.sendRequest(TdApi.CancelDownloadFile().also { req ->
                req.fileId = fileId
                req.onlyIfPending = false
            })
        }
        runCatching {
            client.sendRequest(TdApi.DeleteFile().also { it.fileId = fileId })
            Log.d(TAG, "Deleted cached file $fileId")
        }
    }

    fun getUrl(fileId: Int): String {
        val url = "http://127.0.0.1:$port/file/$fileId"
        return url
    }

    /**
     * Internal helper to pre-fetch metadata (the last part of the file)
     * as soon as a streaming request starts. Deduplicated by fileId.
     * Scale: 1MB for small files, 2MB for medium, 3MB for large (>1GB).
     */
    private fun prefetchMetadata(fileId: Int, totalSize: Long) {
        val warmupSize = when {
            totalSize > 1000 * 1024 * 1024L -> 3 * 1024 * 1024L // 3MB for >1GB
            totalSize > 200 * 1024 * 1024L -> 2 * 1024 * 1024L  // 2MB for >200MB
            else -> 1 * 1024 * 1024L                            // 1MB for smaller
        }
        metadataWarmupSizes[fileId] = warmupSize
        
        if (totalSize <= warmupSize) return
        val cacheKey = "$fileId-metadata"
        if (metadataCache.containsKey(cacheKey)) return
        
        // If already prefetching this file, don't start another one
        if (!activePrefetches.add(fileId)) return

        scope.launch {
            try {
                Log.i(TAG, "📂 [METADADOS] Iniciando pré-busca assíncrona do índice para fileId=$fileId ($warmupSize bytes)...")
                val offset = (totalSize - warmupSize).coerceAtLeast(0)
                val bytes = downloadChunkInternal(fileId, offset, warmupSize.toInt(), isMetadata = true)
                if (bytes != null) {
                    metadataCache[cacheKey] = bytes
                    metadataReady.add(fileId)
                    Log.i(TAG, "✅ [METADADOS] Índice capturado com sucesso para fileId=$fileId")
                }
            } finally {
                activePrefetches.remove(fileId)
            }
        }
    }

    private suspend fun downloadChunk(
        fileId: Int,
        @Suppress("UNUSED_PARAMETER") localPath: String?,
        offset: Long,
        limit: Int,
        totalSize: Long = 0
    ): ByteArray? {
        val warmupSize = metadataWarmupSizes[fileId] ?: 2 * 1024 * 1024L
        val isMetadata = totalSize > 0 && offset > (totalSize - warmupSize - 1024 * 1024)
        
        // --- METADATA GATE ---
        // If this is a normal data request (not metadata) and metadata isn't ready yet,
        // we block the request until the metadata prefetch (index) is fully cached.
        // This ensures the Telegram connection is dedicated to the vital index first.
        if (!isMetadata && totalSize > warmupSize && !metadataReady.contains(fileId)) {
            Log.d(TAG, "🛑 [PROXY] Prioritizing metadata search. Waiting for index (offset=$offset)...")
            val gateTimeoutMs = 45_000L // 45s safety timeout
            val gateStartTime = System.currentTimeMillis()
            
            while (System.currentTimeMillis() - gateStartTime < gateTimeoutMs) {
                if (metadataReady.contains(fileId)) {
                    Log.d(TAG, "🟢 [PROXY] Metadata ready, releasing request for offset=$offset")
                    break
                }
                
                // If the user switched to another movie while we were waiting, abort immediately
                if (lastStreamedFileId != fileId) {
                    Log.d(TAG, "🛑 [PROXY] Aborting wait: file changed (fileId=$fileId)")
                    return null
                }
                
                delay(200)
            }
            
            if (!metadataReady.contains(fileId)) {
                Log.w(TAG, "⚠️ [PROXY] Metadata gate timed out after ${gateTimeoutMs/1000}s. Aborting.")
                _errorState.value = "Conexão com o Telegram está lenta ou instável. Tente novamente ou escolha outra fonte."
                return null
            }
        }

        // If it's a metadata request and we have a pre-fetched full metadata block,
        // extract the requested part from it.
        if (isMetadata) {
            val fullMetadata = metadataCache["$fileId-metadata"]
            if (fullMetadata != null) {
                val metadataOffset = (totalSize - fullMetadata.size).coerceAtLeast(0)
                if (offset >= metadataOffset) {
                    val startInMetadata = (offset - metadataOffset).toInt()
                    val endInMetadata = minOf(startInMetadata + limit, fullMetadata.size)
                    if (startInMetadata < fullMetadata.size) {
                        return fullMetadata.copyOfRange(startInMetadata, endInMetadata)
                    }
                }
            }
        }

        return downloadChunkInternal(fileId, offset, limit, isMetadata)
    }

    private suspend fun downloadChunkInternal(
        fileId: Int,
        offset: Long,
        limit: Int,
        isMetadata: Boolean
    ): ByteArray? {
        val mutex = fileMutexes.getOrPut(fileId) { Mutex() }
        val internalTimeoutMs = 60_000L // Overall timeout for the chunk
        val nudgeIntervalMs = 5_000L    // Re-send DownloadFile if no bytes received for 5s

        // Ask TDLib to prefetch a window (non-blocking) - DISPATCH ONCE
        // Only if we haven't requested this position recently
        val lastOffset = lastRequestedOffsets[fileId] ?: -1L
        val needsNewDownload = lastOffset == -1L || Math.abs(offset - lastOffset) > (PREFETCH_SIZE / 2)

        if (needsNewDownload) {
            mutex.withLock {
                val warmupSize = metadataWarmupSizes[fileId] ?: (2 * 1024 * 1024L)
                val prefetchSize = if (isMetadata) warmupSize else PREFETCH_SIZE
                Log.d(TAG, "[TELEGRAM] 🚀 Solicitando novo range no TDLib (offset=$offset, meta=$isMetadata)")
                runCatching {
                    client.sendRequest(TdApi.DownloadFile().also { req ->
                        req.fileId = fileId
                        req.priority = DOWNLOAD_PRIORITY
                        req.offset = offset
                        req.limit = prefetchSize
                        req.synchronous = false
                    })
                }
                lastRequestedOffsets[fileId] = offset
            }
        }

        // Poll using ReadFilePart directly — only one attempt loop, no command flood.
        val pollStartTime = System.currentTimeMillis()
        var lastNudgeTime = pollStartTime
        
        while (System.currentTimeMillis() - pollStartTime < internalTimeoutMs) {
            // ANTI-ZUMBI: Se o arquivo mudou, pare tudo imediatamente
            if (lastStreamedFileId != fileId) {
                Log.d(TAG, "[PROXY] 🛑 Abortando download obsoleto (fileId=$fileId, atual=$lastStreamedFileId)")
                return null
            }

            try {
                // We use a small mutex lock only for the actual request to TDLib
                val response = mutex.withLock {
                    client.sendRequest(TdApi.ReadFilePart(fileId, offset, limit.toLong()))
                }
                if (response is TdApi.Data) {
                    val bytes: ByteArray = response.data
                    if (bytes.isNotEmpty()) {
                        if (isMetadata) {
                            Log.d(TAG, "[PROXY] ✅ Chunk de metadados recebido (fileId=$fileId, offset=$offset)")
                        }
                        return bytes
                    }
                }
            } catch (e: Exception) {
                // Ignore "not enough bytes" errors from Telegram, as we are polling.
                val msg = e.message ?: ""
                if (!msg.contains("enough downloaded bytes", ignoreCase = true)) {
                    Log.w(TAG, "⚠️ Erro ao ler parte do arquivo (offset=$offset): ${e.message}")
                }
            }
            
            // SMART RETRY: Se não recebemos nada nos últimos 5 segundos, damos um "nudge" no TDLib
            val now = System.currentTimeMillis()
            if (now - lastNudgeTime > nudgeIntervalMs) {
                lastNudgeTime = now
                val file = client.sendRequest(TdApi.GetFile(fileId)) as? TdApi.File
                val total = file?.size?.takeIf { it > 0 } ?: file?.expectedSize ?: 0L
                val downloaded = file?.local?.downloadedSize ?: 0L
                val progress = "Progresso total: ${formatSize(downloaded)}/${formatSize(total.toLong())}"
                
                Log.d(TAG, "[PROXY] ⏳ Estagnação detectada no offset=$offset. $progress. Re-solicitando download...")
                scope.launch {
                    val warmupSize = metadataWarmupSizes[fileId] ?: 2 * 1024 * 1024L
                    mutex.withLock {
                        runCatching {
                            client.sendRequest(TdApi.DownloadFile().also { req ->
                                req.fileId = fileId
                                req.priority = DOWNLOAD_PRIORITY
                                req.offset = offset
                                req.limit = if (isMetadata) warmupSize else PREFETCH_SIZE
                                req.synchronous = false
                            })
                        }
                    }
                }
            }

            delay(POLL_INTERVAL_MS)
        }
        
        Log.w(TAG, "❌ Timeout ao baixar chunk no offset=$offset (fileId=$fileId)")
        return null
    }

    /** Returns (localPath, totalSize) for the file, or null if unavailable. */
    private suspend fun getFileInfo(fileId: Int): Pair<String?, Long>? {
        val file = client.sendRequest(TdApi.GetFile(fileId)) as? TdApi.File ?: return null
        val totalSize = file.size.takeIf { it > 0 } ?: file.expectedSize
        val localPath = file.local?.path?.takeIf { it.isNotBlank() }
        return Pair(localPath, totalSize)
    }

    private fun parseRange(header: String?): Pair<Long?, Long?> {
        if (header == null) return Pair(null, null)
        return try {
            val range = header.removePrefix("bytes=")
            val parts = range.split("-")
            val start = parts.getOrNull(0)?.toLongOrNull()
            val end = parts.getOrNull(1)?.toLongOrNull()
            Pair(start, end)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e

            Pair(null, null)
        }
    }

    private fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
        return String.format(java.util.Locale.US, "%.1f %s", bytes / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
    }

    private fun findFreePort(): Int {
        ServerSocket(0).use { return it.localPort }
    }
}
