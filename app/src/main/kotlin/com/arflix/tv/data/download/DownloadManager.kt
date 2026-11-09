package com.arflix.tv.data.download

import android.content.Context
import android.util.Log
import com.arflix.tv.data.model.MediaType
import com.arflix.tv.data.telegram.TelegramAuthState
import com.arflix.tv.data.telegram.TelegramClient
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.drinkless.tdlib.TdApi
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloadManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val telegramClient: TelegramClient
) {
    companion object {
        private const val TAG = "DownloadManager"
        private const val METADATA_FILE = "downloads_v1.json"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val gson = GsonBuilder().setPrettyPrinting().create()

    private val downloadsDir: File by lazy {
        File(context.filesDir, "downloads").apply { if (!exists()) mkdirs() }
    }
    private val metadataFile: File by lazy {
        File(downloadsDir, METADATA_FILE)
    }

    private val _downloads = MutableStateFlow<List<DownloadItem>>(emptyList())
    val downloads: StateFlow<List<DownloadItem>> = _downloads.asStateFlow()

    private val notificationHelper by lazy { DownloadNotificationHelper(context) }

    private var activeJob: Job? = null
    @Volatile
    private var activeDownloadId: String? = null

    init {
        loadFromDisk()
    }

    private fun loadFromDisk() {
        scope.launch {
            mutex.withLock {
                try {
                    if (metadataFile.exists()) {
                        val json = metadataFile.readText()
                        val type = object : TypeToken<List<DownloadItem>>() {}.type
                        val list: List<DownloadItem> = gson.fromJson(json, type) ?: emptyList()

                        // Verify physical files for completed downloads (and recover completed files)
                        val validatedList = list.map { item ->
                            val localFile = item.localPath?.let { File(it) }
                            val uniqueFile = item.fileUniqueId?.takeIf { it.isNotBlank() }?.let { File(downloadsDir, "${it}.mp4") }
                            val fallbackFile = File(downloadsDir, "${item.id}_${item.fileId}.mp4")
                            val actualFile = when {
                                localFile != null && localFile.exists() && localFile.length() > 0L -> localFile
                                uniqueFile != null && uniqueFile.exists() && uniqueFile.length() > 0L -> uniqueFile
                                fallbackFile.exists() && fallbackFile.length() > 0L -> fallbackFile
                                else -> null
                            }

                            if (item.status == DownloadStatus.COMPLETED) {
                                val expectedTotal = item.totalBytes
                                // Um vídeo de filme ou série nunca tem menos de 500KB (arquivos de 30-100KB são avatares/miniaturas)
                                val isTinyCorrupt = actualFile != null && actualFile.length() < 500_000L
                                val isTrulyCompleted = actualFile != null && !isTinyCorrupt && actualFile.length() > 0L &&
                                    (expectedTotal <= 0L || actualFile.length() >= (expectedTotal * 0.95).toLong())

                                if (isTrulyCompleted && actualFile != null) {
                                    item.copy(
                                        localPath = actualFile.absolutePath,
                                        downloadedBytes = actualFile.length(),
                                        totalBytes = if (expectedTotal > 0L) expectedTotal else actualFile.length(),
                                        speedBytesPerSec = 0L,
                                        errorMessage = null
                                    )
                                } else {
                                    // Arquivo físico sumiu, está incompleto ou é uma miniatura minúscula corrompida
                                    if (isTinyCorrupt) {
                                        try { actualFile?.delete() } catch (_: Exception) {}
                                    }
                                    item.copy(
                                        status = DownloadStatus.FAILED,
                                        errorMessage = "Arquivo corrompido ou incompleto",
                                        downloadedBytes = 0L,
                                        speedBytesPerSec = 0L
                                    )
                                }
                            } else if (item.status == DownloadStatus.DOWNLOADING) {
                                // App foi encerrado durante o download: restaura como PAUSED sem perda de progresso
                                item.copy(status = DownloadStatus.PAUSED, speedBytesPerSec = 0L)
                            } else {
                                item.copy(speedBytesPerSec = 0L)
                            }
                        }
                        _downloads.value = validatedList

                        // Clean any duplicate files left behind in tdlib_files for completed items
                        try {
                            val tdlibFilesDir = File(context.filesDir, "tdlib_files")
                            if (tdlibFilesDir.exists()) {
                                validatedList.filter { it.status == DownloadStatus.COMPLETED }.forEach { completedItem ->
                                    val localPath = completedItem.localPath ?: return@forEach
                                    val localFile = File(localPath)
                                    if (!localFile.exists()) return@forEach
                                    val size = localFile.length()
                                    if (size > 0L) {
                                        tdlibFilesDir.walkBottomUp().filter { it.isFile }.forEach { cacheFile ->
                                            if (cacheFile.length() == size && cacheFile.absolutePath != localFile.absolutePath) {
                                                Log.i(TAG, "Purging duplicate file from tdlib_files: ${cacheFile.name} (${size / 1024 / 1024}MB)")
                                                cacheFile.delete()
                                            }
                                        }
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error cleaning tdlib duplicates", e)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error loading downloads metadata", e)
                }
            }
            processNextInQueue()
        }
    }

    private fun saveToDisk(list: List<DownloadItem>) {
        try {
            val json = gson.toJson(list)
            metadataFile.writeText(json)
        } catch (e: Exception) {
            Log.e(TAG, "Error saving downloads metadata", e)
        }
    }

    /**
     * Internal helper to pause any currently running download.
     * Ensures strict single-download concurrency ("ao startar um parar o outro").
     * When [requeue] is true, the active download is moved back to QUEUED status so it resumes
     * automatically once the newly prioritized download completes.
     */
    suspend fun pauseActiveDownload(exceptId: String? = null, requeue: Boolean = false) {
        val currentActiveId = activeDownloadId ?: return
        if (currentActiveId == exceptId) return

        var fileIdToCancel: Int? = null
        mutex.withLock {
            val current = _downloads.value.toMutableList()
            val idx = current.indexOfFirst { it.id == currentActiveId }
            if (idx >= 0) {
                val item = current[idx]
                fileIdToCancel = item.fileId
                val newStatus = if (requeue) DownloadStatus.QUEUED else DownloadStatus.PAUSED
                current[idx] = item.copy(status = newStatus, speedBytesPerSec = 0L)
                _downloads.value = current
                saveToDisk(current)
            }
        }

        activeJob?.cancel()
        activeJob = null
        activeDownloadId = null
        fileIdToCancel?.let { telegramClient.cancelDownloadFile(it) }
        notificationHelper.dismissProgress()
    }

    fun enqueueDownload(
        fileId: Int,
        mediaType: MediaType,
        mediaId: Int,
        title: String,
        subtitle: String? = null,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null,
        posterUrl: String? = null,
        quality: String = "",
        streamUrl: String = "",
        totalBytes: Long = 0L,
        fileUniqueId: String? = null,
        remoteFileId: String? = null,
        sourceTitle: String? = null,
        audioLabel: String? = null
    ): Boolean {
        val itemId = "tg_${fileId}"
        val existing = _downloads.value.find { it.id == itemId }
        if (existing != null && existing.status == DownloadStatus.COMPLETED) {
            return false // Already downloaded
        }

        val resolvedAudio = audioLabel ?: DownloadItem.parseAudioLabel(sourceTitle ?: subtitle ?: title)
        val resolvedQuality = DownloadItem.parseQualityLabel(quality, sourceTitle)
        val resolvedRemoteId = remoteFileId
            ?: runCatching { android.net.Uri.parse(streamUrl).getQueryParameter("remote") }.getOrNull()?.takeIf { it.isNotBlank() }

        // Checagem de recuperação instantânea se o arquivo físico já existe completo em downloadsDir
        val physicalFile = fileUniqueId?.takeIf { it.isNotBlank() }?.let { File(downloadsDir, "${it}.mp4") }
            ?: File(downloadsDir, "${itemId}_${fileId}.mp4")
        val isAlreadyCompletedOnDisk = physicalFile.exists() && physicalFile.length() > 500_000L &&
            (totalBytes <= 0L || physicalFile.length() >= (totalBytes * 0.95).toLong())

        scope.launch {
            mutex.withLock {
                val current = _downloads.value.toMutableList()
                val idx = current.indexOfFirst { it.id == itemId }
                val newItem = DownloadItem(
                    id = itemId,
                    fileId = fileId,
                    fileUniqueId = fileUniqueId,
                    remoteFileId = resolvedRemoteId,
                    mediaType = mediaType,
                    mediaId = mediaId,
                    title = title,
                    subtitle = subtitle,
                    seasonNumber = seasonNumber,
                    episodeNumber = episodeNumber,
                    posterUrl = posterUrl,
                    quality = resolvedQuality,
                    streamUrl = streamUrl,
                    totalBytes = if (isAlreadyCompletedOnDisk) physicalFile.length() else totalBytes,
                    downloadedBytes = if (isAlreadyCompletedOnDisk) physicalFile.length() else (if (idx >= 0) current[idx].downloadedBytes else 0L),
                    localPath = if (isAlreadyCompletedOnDisk) physicalFile.absolutePath else (if (idx >= 0) current[idx].localPath else null),
                    status = if (isAlreadyCompletedOnDisk) DownloadStatus.COMPLETED else DownloadStatus.QUEUED,
                    createdAt = if (idx >= 0) current[idx].createdAt else System.currentTimeMillis(),
                    sourceTitle = sourceTitle,
                    audioLabel = resolvedAudio,
                    speedBytesPerSec = 0L
                )
                if (idx >= 0) {
                    current[idx] = newItem
                } else {
                    current.add(newItem)
                }
                _downloads.value = current
                saveToDisk(current)
            }
            if (!isAlreadyCompletedOnDisk) {
                processNextInQueue()
            }
        }
        return true
    }

    fun pauseDownload(id: String) {
        scope.launch {
            var fileIdToCancel: Int? = null
            mutex.withLock {
                val current = _downloads.value.toMutableList()
                val idx = current.indexOfFirst { it.id == id }
                if (idx >= 0) {
                    val item = current[idx]
                    if (item.status == DownloadStatus.DOWNLOADING || item.status == DownloadStatus.QUEUED) {
                        fileIdToCancel = item.fileId
                        current[idx] = item.copy(status = DownloadStatus.PAUSED, speedBytesPerSec = 0L)
                        _downloads.value = current
                        saveToDisk(current)
                    }
                }
            }
            if (activeDownloadId == id) {
                activeJob?.cancel()
                activeJob = null
                activeDownloadId = null
                fileIdToCancel?.let { telegramClient.cancelDownloadFile(it) }
                notificationHelper.dismissProgress()
                if (_downloads.value.none { it.status == DownloadStatus.QUEUED }) {
                    DownloadService.stop(context)
                }
            }
        }
    }

    fun resumeDownload(id: String) {
        scope.launch {
            // Strict single-active-download: if another download is running, requeue it so it resumes after this one
            if (activeDownloadId != null && activeDownloadId != id) {
                pauseActiveDownload(exceptId = id, requeue = true)
            }

            mutex.withLock {
                val current = _downloads.value.toMutableList()
                val idx = current.indexOfFirst { it.id == id }
                if (idx >= 0) {
                    current[idx] = current[idx].copy(
                        status = DownloadStatus.QUEUED,
                        speedBytesPerSec = 0L,
                        errorMessage = null
                    )
                    _downloads.value = current
                    saveToDisk(current)
                }
            }
            processNextInQueue()
        }
    }

    fun deleteDownload(id: String) {
        scope.launch {
            var fileToDelete: File? = null
            var fileIdToCancel: Int? = null
            mutex.withLock {
                val current = _downloads.value.toMutableList()
                val idx = current.indexOfFirst { it.id == id }
                if (idx >= 0) {
                    val item = current[idx]
                    fileIdToCancel = item.fileId
                    item.localPath?.let { fileToDelete = File(it) }
                    current.removeAt(idx)
                    _downloads.value = current
                    saveToDisk(current)
                }
            }
            if (activeDownloadId == id) {
                activeJob?.cancel()
                activeJob = null
                activeDownloadId = null
                notificationHelper.dismissProgress()
                if (_downloads.value.none { it.status == DownloadStatus.QUEUED }) {
                    DownloadService.stop(context)
                }
            }
            fileIdToCancel?.let { telegramClient.cancelDownloadFile(it) }
            fileToDelete?.let {
                if (it.exists()) it.delete()
            }
            processNextInQueue()
        }
    }

    private fun processNextInQueue() {
        scope.launch {
            mutex.withLock {
                if (activeDownloadId != null) return@launch // Strict: only one download at a time
                val nextItem = _downloads.value
                    .filter { it.status == DownloadStatus.QUEUED }
                    .minByOrNull { it.createdAt } ?: return@launch
                startDownload(nextItem)
            }
        }
    }

    private fun startDownload(item: DownloadItem) {
        activeDownloadId = item.id
        DownloadService.start(context, item.title)

        // Set status to DOWNLOADING with speed reset to 0L immediately
        val initialDownloadingItem = item.copy(status = DownloadStatus.DOWNLOADING, speedBytesPerSec = 0L)
        val updated = _downloads.value.map {
            if (it.id == item.id) initialDownloadingItem else it
        }
        _downloads.value = updated
        saveToDisk(updated)
        notificationHelper.updateProgress(initialDownloadingItem)

        activeJob = scope.launch {
            try {
                // 1. Ensure TelegramClient is initialized and authenticated
                val currentAuth = telegramClient.authState.value
                if (currentAuth is TelegramAuthState.Idle) {
                    telegramClient.initialize()
                }
                if (telegramClient.authState.value !is TelegramAuthState.Ready) {
                    Log.i(TAG, "Waiting for Telegram client to reach Ready state for ${item.title}...")
                    val ready = withTimeoutOrNull(25_000L) {
                        telegramClient.authState.first { it is TelegramAuthState.Ready }
                    }
                    if (ready == null) {
                        Log.w(TAG, "Telegram client not ready in time. Requeuing download.")
                        mutex.withLock {
                            _downloads.value = _downloads.value.map {
                                if (it.id == item.id) it.copy(status = DownloadStatus.QUEUED, speedBytesPerSec = 0L) else it
                            }
                            saveToDisk(_downloads.value)
                        }
                        activeDownloadId = null
                        notificationHelper.dismissProgress()
                        DownloadService.stop(context)
                        return@launch
                    }
                }

                // 2. Validate and resolve TDLib file ID across process restart
                // Os IDs numéricos de arquivo no TDLib são efêmeros por sessão.
                // O canonicalRemoteId (iniciado por BAAC para vídeos ou BQAC para documentos) é a única identidade persistente.
                val canonicalRemoteId = runCatching {
                    android.net.Uri.parse(item.streamUrl).getQueryParameter("remote")
                }.getOrNull()?.takeIf { it.isNotBlank() && (it.startsWith("BAAC") || it.startsWith("BQAC")) }
                    ?: item.remoteFileId?.takeIf { it.isNotBlank() && (it.startsWith("BAAC") || it.startsWith("BQAC")) }
                    ?: item.remoteFileId?.takeIf { it.isNotBlank() }

                var currentFileId = item.fileId
                var resolvedFile: TdApi.File? = try {
                    telegramClient.getFile(currentFileId)
                } catch (_: Exception) {
                    null
                }

                // Se o arquivo resolvido não bater com o canonicalRemoteId, o número de fileId foi
                // reciclado pelo TDLib na nova sessão para outro arquivo (ex: avatar/miniatura de 30KB)!
                val isMismatched = resolvedFile != null && !canonicalRemoteId.isNullOrBlank() &&
                    resolvedFile.remote?.id != canonicalRemoteId

                if (resolvedFile == null || resolvedFile.id == 0 || isMismatched) {
                    if (!canonicalRemoteId.isNullOrBlank()) {
                        Log.i(TAG, "Resolvendo stale/mismatched fileId $currentFileId via canonical remoteId $canonicalRemoteId...")
                        val rFile = try {
                            telegramClient.getRemoteFile(canonicalRemoteId)
                        } catch (e: Exception) {
                            Log.w(TAG, "Falha ao buscar remoteId $canonicalRemoteId: ${e.message}")
                            null
                        }
                        if (rFile != null && rFile.id != 0) {
                            Log.i(TAG, "Sucesso: remoteId $canonicalRemoteId resolvido para o fileId real ${rFile.id} (era $currentFileId, tamanho=${rFile.size}B)")
                            currentFileId = rFile.id
                            resolvedFile = rFile
                            mutex.withLock {
                                _downloads.value = _downloads.value.map {
                                    if (it.id == item.id) it.copy(
                                        fileId = rFile.id,
                                        remoteFileId = canonicalRemoteId,
                                        fileUniqueId = rFile.remote?.uniqueId ?: it.fileUniqueId,
                                        totalBytes = if (rFile.size > 0L) rFile.size else it.totalBytes
                                    ) else it
                                }
                                saveToDisk(_downloads.value)
                            }
                        }
                    }
                }

                if (resolvedFile == null && currentFileId <= 0) {
                    throw IllegalStateException("Arquivo do Telegram não disponível após reinício")
                }

                val targetFileId = currentFileId
                Log.i(TAG, "Starting download for ${item.title} (fileId=$targetFileId)")
                telegramClient.downloadFile(
                    fileId = targetFileId,
                    priority = 32,
                    offset = 0L,
                    limit = 0L,
                    synchronous = false
                )

                var lastEmitTime = 0L
                var lastBytes = resolvedFile?.local?.downloadedSize ?: item.downloadedBytes
                var lastTime = System.currentTimeMillis()
                var currentSpeed = 0L
                var isCompleted = false

                telegramClient.fileUpdates
                    .takeWhile { !isCompleted }
                    .collect { update ->
                        if (isCompleted || update.file.id != targetFileId) return@collect

                        val file = update.file
                        val downloadedBytes = file.local.downloadedSize
                        val totalBytes = if (file.size > 0L) file.size else file.expectedSize
                        val remoteUniqueId = file.remote?.uniqueId?.takeIf { it.isNotBlank() } ?: item.fileUniqueId
                        val newRemoteId = file.remote?.id?.takeIf { it.isNotBlank() } ?: item.remoteFileId

                        if (file.local.isDownloadingCompleted) {
                            isCompleted = true
                            Log.i(TAG, "Download completed for ${item.title}. Relocating file to isolated downloads directory...")
                            val srcFile = File(file.local.path)
                            val safeFileName = if (remoteUniqueId != null) "${remoteUniqueId}.mp4" else "${item.id}_${item.fileId}.mp4"
                            val destFile = File(downloadsDir, safeFileName)

                            // Proteção contra falso positivo de conclusão imediata em arquivos minúsculos / avatares
                            val srcLength = srcFile.takeIf { it.exists() }?.length() ?: 0L
                            val destLength = destFile.takeIf { it.exists() }?.length() ?: 0L
                            val maxFoundLength = maxOf(srcLength, destLength)
                            if (maxFoundLength < 500_000L && (totalBytes > 1_000_000L || item.totalBytes > 1_000_000L)) {
                                Log.w(TAG, "Ignorando falso evento de conclusão para ${item.title}: tamanho encontrado $maxFoundLength B muito menor que o esperado ($totalBytes B).")
                                isCompleted = false
                                return@collect
                            }

                            try {
                                if (srcFile.exists() && srcFile.length() > 0L) {
                                    var moved = false
                                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                                        try {
                                            java.nio.file.Files.move(
                                                srcFile.toPath(),
                                                destFile.toPath(),
                                                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                                                java.nio.file.StandardCopyOption.REPLACE_EXISTING
                                            )
                                            moved = true
                                        } catch (_: Throwable) {
                                            moved = false
                                        }
                                    }
                                    if (!moved) {
                                        moved = srcFile.renameTo(destFile)
                                    }
                                    if (!moved && srcFile.exists()) {
                                        srcFile.copyTo(destFile, overwrite = true)
                                        srcFile.delete()
                                    }
                                }
                                // SEGURANÇA MÁXIMA: Só avisa o TDLib para apagar o arquivo do banco temporário
                                // se o arquivo de destino no disco existir e tiver conteúdo de verdade (> 0 bytes)
                                if (destFile.exists() && destFile.length() > 0L) {
                                    telegramClient.deleteFile(file.id)
                                } else {
                                    Log.e(TAG, "Falha na realocação: arquivo destino não tem bytes válidos (${destFile.length()}B). Mantendo TDLib intacto.")
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Error moving downloaded file", e)
                            }

                            val actualFinalSize = destFile.takeIf { it.exists() }?.length() ?: 0L
                            val isFinalValid = actualFinalSize > 0L && (totalBytes <= 0L || actualFinalSize >= (totalBytes * 0.99).toLong())

                            if (!isFinalValid) {
                                Log.e(TAG, "Download final inválido para ${item.title} (size=$actualFinalSize, esperado=$totalBytes). Marcando como FAILED.")
                                mutex.withLock {
                                    val list = _downloads.value.map {
                                        if (it.id == item.id) it.copy(
                                            status = DownloadStatus.FAILED,
                                            errorMessage = "Arquivo corrompido ou vazio na gravação",
                                            downloadedBytes = 0L,
                                            speedBytesPerSec = 0L
                                        ) else it
                                    }
                                    _downloads.value = list
                                    saveToDisk(list)
                                }
                                activeDownloadId = null
                                notificationHelper.dismissProgress()
                                notificationHelper.showFailed(item, "Arquivo final vazio ou corrompido")
                                processNextInQueue()
                                return@collect
                            }

                            val finalPath = destFile.absolutePath
                            var completedItem: DownloadItem? = null

                            mutex.withLock {
                                val list = _downloads.value.map {
                                    if (it.id == item.id) {
                                        val comp = it.copy(
                                            status = DownloadStatus.COMPLETED,
                                            fileUniqueId = remoteUniqueId,
                                            remoteFileId = newRemoteId,
                                            downloadedBytes = actualFinalSize,
                                            totalBytes = actualFinalSize,
                                            localPath = finalPath,
                                            speedBytesPerSec = 0L
                                        )
                                        completedItem = comp
                                        comp
                                    } else it
                                }
                                _downloads.value = list
                                saveToDisk(list)
                            }

                            activeDownloadId = null
                            notificationHelper.dismissProgress()
                            completedItem?.let { notificationHelper.showCompleted(it) }
                            if (_downloads.value.none { it.status == DownloadStatus.QUEUED }) {
                                DownloadService.stop(context)
                            }
                            processNextInQueue()
                            return@collect
                        }

                        // Progress update (throttled to calculate accurate speed and update notification & UI state)
                        if (isCompleted) return@collect
                        val now = System.currentTimeMillis()
                        val timeDelta = now - lastTime
                        if (lastEmitTime == 0L || (timeDelta >= 500L && now - lastEmitTime >= 300L)) {
                            val bytesDelta = (downloadedBytes - lastBytes).coerceAtLeast(0L)
                            if (timeDelta > 0 && lastEmitTime > 0L) {
                                currentSpeed = (bytesDelta * 1000L) / timeDelta
                            }
                            lastBytes = downloadedBytes
                            lastTime = now
                            lastEmitTime = now

                            val currentItem = item.copy(
                                fileId = targetFileId,
                                remoteFileId = newRemoteId,
                                fileUniqueId = remoteUniqueId,
                                status = DownloadStatus.DOWNLOADING,
                                downloadedBytes = downloadedBytes,
                                totalBytes = if (totalBytes > 0L) totalBytes else item.totalBytes,
                                speedBytesPerSec = currentSpeed
                            )

                            _downloads.value = _downloads.value.map {
                                if (it.id == item.id && it.status != DownloadStatus.COMPLETED) currentItem else it
                            }
                            notificationHelper.updateProgress(currentItem)
                        }
                    }
            } catch (e: kotlinx.coroutines.CancellationException) {
                activeDownloadId = null
                notificationHelper.dismissProgress()
                if (_downloads.value.none { it.status == DownloadStatus.QUEUED }) {
                    DownloadService.stop(context)
                }
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Error during download of ${item.title}", e)
                var failedItem: DownloadItem? = null
                mutex.withLock {
                    val list = _downloads.value.map {
                        if (it.id == item.id) {
                            val f = it.copy(status = DownloadStatus.FAILED, errorMessage = e.message, speedBytesPerSec = 0L)
                            failedItem = f
                            f
                        } else it
                    }
                    _downloads.value = list
                    saveToDisk(list)
                }
                activeDownloadId = null
                notificationHelper.dismissProgress()
                failedItem?.let { notificationHelper.showFailed(it, e.message) }
                if (_downloads.value.none { it.status == DownloadStatus.QUEUED }) {
                    DownloadService.stop(context)
                }
                processNextInQueue()
            }
        }
    }

    fun isDownloaded(fileId: Int): Boolean {
        return _downloads.value.any { it.fileId == fileId && it.status == DownloadStatus.COMPLETED }
    }

    fun isDownloading(fileId: Int): Boolean {
        return _downloads.value.any { 
            it.fileId == fileId && (it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.QUEUED) 
        }
    }

    /** Returns all fileIds currently managed in the download queue (active, queued or paused). */
    fun getManagedDownloadFileIds(): Set<Int> {
        return _downloads.value.mapNotNull { it.fileId.takeIf { id -> id > 0 } }.toSet()
    }

    fun getDownloadedFile(mediaId: Int, season: Int? = null, episode: Int? = null): File? {
        val item = _downloads.value.firstOrNull {
            it.mediaId == mediaId &&
            it.seasonNumber == season &&
            it.episodeNumber == episode &&
            it.status == DownloadStatus.COMPLETED
        } ?: return null
        val localFile = item.localPath?.let { File(it) }
        return if (localFile != null && localFile.exists() && localFile.isFile && localFile.length() > 0L) {
            localFile
        } else null
    }

    fun getDownloadedFileByFileId(fileId: Int): File? {
        val item = _downloads.value.firstOrNull {
            it.fileId == fileId && it.status == DownloadStatus.COMPLETED
        } ?: return null
        val localFile = item.localPath?.let { File(it) }
        return if (localFile != null && localFile.exists() && localFile.isFile && localFile.length() > 0L) {
            localFile
        } else null
    }

    fun getDownloadedFileByUniqueId(fileUniqueId: String): File? {
        if (fileUniqueId.isBlank()) return null
        val item = _downloads.value.firstOrNull {
            it.fileUniqueId == fileUniqueId && it.status == DownloadStatus.COMPLETED
        }
        if (item?.localPath != null) {
            val f = File(item.localPath)
            if (f.exists() && f.isFile && f.length() > 0L) return f
        }
        val namedFile = File(downloadsDir, "${fileUniqueId}.mp4")
        if (namedFile.exists() && namedFile.isFile && namedFile.length() > 0L) return namedFile
        return null
    }

    fun getDownloadItem(fileId: Int): DownloadItem? {
        return _downloads.value.find { it.fileId == fileId }
    }

    fun getTotalDownloadsBytes(): Long {
        return _downloads.value
            .filter { it.status == DownloadStatus.COMPLETED }
            .sumOf { it.downloadedBytes.takeIf { b -> b > 0 } ?: it.totalBytes }
    }

    fun getAvailableStorageBytes(): Long {
        return try {
            downloadsDir.usableSpace
        } catch (_: Exception) {
            0L
        }
    }

    fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return "0 MB"
        val mb = bytes.toDouble() / (1024.0 * 1024.0)
        return if (mb >= 1024.0) {
            val gb = mb / 1024.0
            String.format(java.util.Locale.US, "%.1f GB", gb)
        } else {
            String.format(java.util.Locale.US, "%.0f MB", mb)
        }
    }
}
