package com.arflix.tv.data.telegram

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.arflix.tv.util.DeviceType
import com.arflix.tv.util.detectDeviceType
import com.arflix.tv.util.telegramDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import com.arflix.tv.player.telegram.StreamIndex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.drinkless.tdlib.TdApi
import java.io.File
import java.io.FileInputStream
import javax.inject.Inject
import javax.inject.Singleton

data class TelegramChat(
    val id: Long,
    val title: String,
    val type: String,
    val memberCount: Int,
    val isChannel: Boolean
)

data class TelegramVideoMessage(
    val messageId: Long,
    val chatId: Long,
    val fileName: String,
    val fileId: Int,
    val fileSize: Long,
    val duration: Int,
    val mimeType: String,
    val caption: String,
    val remoteFileId: String? = null
)

@Singleton
class TelegramRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val client: TelegramClient,
    private val proxy: TelegramStreamingProxy,
    private val downloadManager: com.arflix.tv.data.download.DownloadManager
) {
    companion object {
        private const val TAG = "TelegramRepository"
        private val KEY_EXCLUDED_CHATS = stringPreferencesKey("excluded_chat_ids")
        val KEY_CACHE_LIMIT_MB = longPreferencesKey("telegram_cache_limit_mb")
        const val DEFAULT_CACHE_LIMIT_MB_TV = 500L
        const val DEFAULT_CACHE_LIMIT_MB_MOBILE = 1024L

        fun sessionMarker(context: Context) = File(context.filesDir, "tdlib_session_ok")

        fun wipeTdlibFiles(context: Context) {
            sessionMarker(context).delete()
            File(context.filesDir, "tdlib").deleteRecursively()
            File(context.filesDir, "tdlib_files").deleteRecursively()
            Log.d(TAG, "TDLib database wiped")
        }
    }

    val authState = client.authState
    val isProxyReady = proxy.isReady
    val proxyError = proxy.errorState

    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lastPlayedFileId: Int = -1
    private var warmupJob: Job? = null
    private val pathToFileId = java.util.concurrent.ConcurrentHashMap<String, Int>()

    init {
        val hasValidSession = sessionMarker(context).exists()
        if (!hasValidSession) {
            // Wipe any partial database left by a previous crashed session
            // so TDLib doesn't try to open a corrupted database on next Connect.
            File(context.filesDir, "tdlib").deleteRecursively()
            File(context.filesDir, "tdlib_files").deleteRecursively()
        } else {
            client.initialize()
            repositoryScope.launch {
                val limitMb = getCacheLimitMb().first()
                client.setStorageLimit(limitMb * 1024L * 1024L)
                trimStorageIfNeeded()
            }
        }

        // Mapeia em tempo real caminho físico do arquivo temporário -> TDLib fileId
        repositoryScope.launch {
            client.fileUpdates.collect { update ->
                val p = update.file.local?.path
                if (!p.isNullOrBlank()) {
                    try {
                        pathToFileId[File(p).canonicalPath] = update.file.id
                    } catch (_: Throwable) {}
                }
            }
        }
    }

    fun startProxyService() {
        val intent = Intent(context, TelegramProxyService::class.java).apply {
            action = TelegramProxyService.ACTION_START
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    fun stopProxyService() {
        val intent = Intent(context, TelegramProxyService::class.java).apply {
            action = TelegramProxyService.ACTION_STOP
        }
        context.startService(intent)
    }

    fun isAuthenticated(): Boolean = client.authState.value is TelegramAuthState.Ready

    fun startAuth() = client.initialize()
    fun requestQrCode() = client.requestQrCode()
    fun submitPhone(phone: String) = client.submitPhone(phone)
    fun submitCode(code: String) = client.submitCode(code)
    fun submitPassword(password: String) = client.submitPassword(password)

    /**
     * Signs out of Telegram, revoking the session on Telegram's servers when they can
     * be reached, then wipes the local TDLib data.
     */
    suspend fun disconnect() {
        client.logOut()
        withContext(Dispatchers.IO) { wipeTdlibFiles(context) }
    }

    fun getDefaultCacheLimitMb(): Long =
        if (detectDeviceType(context) == DeviceType.TV) DEFAULT_CACHE_LIMIT_MB_TV else DEFAULT_CACHE_LIMIT_MB_MOBILE

    fun getCacheLimitMb(): Flow<Long> =
        context.telegramDataStore.data.map { prefs ->
            prefs[KEY_CACHE_LIMIT_MB] ?: getDefaultCacheLimitMb()
        }

    suspend fun setCacheLimitMb(mb: Long) {
        context.telegramDataStore.edit { prefs ->
            prefs[KEY_CACHE_LIMIT_MB] = mb
        }
        val limitBytes = mb * 1024L * 1024L
        client.setStorageLimit(limitBytes)
        trimStorageIfNeeded(limitBytes)
    }

    private suspend fun getProtectedPaths(): Set<String> {
        val paths = mutableSetOf<String>()
        // 1. Currently playing video
        val currentPlayingId = lastPlayedFileId
        if (currentPlayingId > 0) {
            val tdPath = runCatching { client.getFile(currentPlayingId)?.local?.path }.getOrNull()
            if (!tdPath.isNullOrBlank()) {
                try { paths.add(File(tdPath).canonicalPath) } catch (_: Throwable) {}
            }
        }
        // 2. Download items (active, queued, paused, completed)
        downloadManager.downloads.value.forEach { item ->
            item.localPath?.takeIf { it.isNotBlank() }?.let {
                try { paths.add(File(it).canonicalPath) } catch (_: Throwable) {}
            }
            if (item.fileId > 0) {
                val tdPath = runCatching { client.getFile(item.fileId)?.local?.path }.getOrNull()
                if (!tdPath.isNullOrBlank()) {
                    try { paths.add(File(tdPath).canonicalPath) } catch (_: Throwable) {}
                }
            }
        }
        return paths
    }

    suspend fun getCacheSize(): Long = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, "tdlib_files")
        if (!dir.exists()) return@withContext 0L
        val protectedPaths = getProtectedPaths()
        dir.walkBottomUp().filter { it.isFile }.sumOf { file ->
            val canonical = try { file.canonicalPath } catch (_: Throwable) { file.absolutePath }
            if (protectedPaths.contains(canonical)) {
                0L
            } else {
                try {
                    val stat = android.system.Os.stat(file.absolutePath)
                    val allocated = stat.st_blocks * 512L
                    allocated.coerceAtMost(file.length())
                } catch (_: Throwable) {
                    file.length()
                }
            }
        }
    }

    suspend fun clearCache() = withContext(Dispatchers.IO) {
        // 1. Tell TDLib to optimize/purge cached files down to 0
        client.optimizeStorage(sizeLimitBytes = 0L)
        // 2. Also wipe disk files in tdlib_files subfolders safely (preserving active downloads)
        val protectedPaths = getProtectedPaths()
        val filesDir = File(context.filesDir, "tdlib_files")
        if (filesDir.exists()) {
            filesDir.walkBottomUp().filter { it.isFile }.forEach { file ->
                val canonical = try { file.canonicalPath } catch (_: Throwable) { file.absolutePath }
                if (!protectedPaths.contains(canonical)) {
                    file.delete()
                }
            }
        }
    }

    /**
     * Chamado ao carregar qualquer vídeo. Se for um vídeo diferente do último reproduzido,
     * para o download do anterior e pré-aquece o início e o índice no final (Cues/moov),
     * garantindo que o ExoPlayer encontre ambos em disco local de imediato sem conflito de rede.
     */
    fun onVideoLoading(activeFileId: Int) {
        if (activeFileId <= 0) return
        val prev = lastPlayedFileId
        if (prev == activeFileId) {
            Log.d(TAG, "Mesmo vídeo sendo carregado (fileId=$activeFileId). Mantendo cache intacto.")
            return
        }
        Log.i(TAG, "🎬 Iniciando carregamento do novo vídeo $activeFileId (anterior=$prev)")
        lastPlayedFileId = activeFileId
        warmupJob?.cancel()
        warmupJob = repositoryScope.launch {
            // Pausa qualquer download ativo da fila para priorizar 100% da banda no player
            downloadManager.pauseActiveDownload(exceptId = "tg_$activeFileId")

            if (prev > 0 && !downloadManager.isDownloading(prev)) {
                Log.d(TAG, "Parando download em background do vídeo anterior: $prev")
                client.cancelDownloadFile(prev, onlyIfPending = false)
            }
            if (!downloadManager.isDownloading(activeFileId)) {
                warmupVideo(activeFileId)
            }
            cleanStreamingCacheExcept(activeFileId)
        }
    }

    private suspend fun warmupVideo(fileId: Int) = withContext(Dispatchers.IO) {
        try {
            val file = client.getFile(fileId) ?: return@withContext
            if (file.local?.isDownloadingCompleted == true || downloadManager.isDownloading(fileId)) return@withContext
            val totalSize = if (file.size > 0) file.size else file.expectedSize
            if (totalSize <= 0) return@withContext

            val headLimit = minOf(2L * 1024L * 1024L, totalSize)
            Log.d(TAG, "⚡ [Warmup] Baixando início do vídeo $fileId (${headLimit / 1024}KB)")
            client.downloadFile(fileId = fileId, priority = 32, offset = 0L, limit = headLimit, synchronous = false)

            // Aguarda via Flow em tempo real (acorda no mesmo milissegundo em que os bytes chegam)
            var headBytesOnDisk = client.getFileDownloadedPrefixSize(fileId, 0L)
            if (headBytesOnDisk < minOf(64L * 1024L, headLimit)) {
                withTimeoutOrNull(3_000L) {
                    client.fileUpdates
                        .filter { it.file.id == fileId }
                        .first {
                            headBytesOnDisk = client.getFileDownloadedPrefixSize(fileId, 0L)
                            headBytesOnDisk >= minOf(64L * 1024L, headLimit)
                        }
                }
            }

            if (headBytesOnDisk <= 0) {
                Log.d(TAG, "⚡ [Warmup] Sem bytes imediatos para indexação do vídeo $fileId; reprodução prosseguirá sob demanda")
                return@withContext
            }

            val path = client.getFile(fileId)?.local?.path
            val diskFile = path?.takeIf { it.isNotBlank() }?.let { File(it) }
            if (diskFile == null || !diskFile.exists() || diskFile.length() < headBytesOnDisk) return@withContext

            val headBuffer = ByteArray(minOf(headBytesOnDisk, 512L * 1024L).toInt())
            FileInputStream(diskFile).use { fis ->
                fis.read(headBuffer)
            }

            val indexRange = StreamIndex.locate(headBuffer, totalSize)
            if (indexRange != null) {
                val currentFile = client.getFile(fileId)
                val alreadyAtIndex = currentFile?.local?.let {
                    it.isDownloadingActive && kotlin.math.abs(it.downloadOffset - indexRange.start) < 1024 * 1024
                } == true
                val indexAlreadyOnDisk = client.getFileDownloadedPrefixSize(fileId, indexRange.start) >= indexRange.length

                if (!alreadyAtIndex && !indexAlreadyOnDisk) {
                    Log.d(TAG, "⚡ [Warmup] Índice detectado no arquivo $fileId: offset=${indexRange.start} tamanho=${indexRange.length / 1024}KB. Pré-aquecendo...")
                    client.downloadFile(
                        fileId = fileId,
                        priority = 32,
                        offset = indexRange.start,
                        limit = indexRange.length,
                        synchronous = false
                    )
                }
            } else {
                Log.d(TAG, "⚡ [Warmup] Nenhum índice distante detectado para arquivo $fileId")
            }
        } catch (e: Exception) {
            Log.w(TAG, "⚡ [Warmup] Pré-aquecimento do vídeo $fileId: ${e.message}")
        }
    }

    suspend fun cleanStreamingCacheExcept(activeFileId: Int) = withContext(Dispatchers.IO) {
        if (activeFileId <= 0) return@withContext
        val limitMb = getCacheLimitMb().first()
        val limitBytes = limitMb * 1024L * 1024L
        val currentSize = getCacheSize()
        if (currentSize > limitBytes) {
            trimStorageIfNeeded()
        }
        Log.i(TAG, "🧹 Verificação de cache concluída para novo vídeo ($activeFileId)")
    }

    suspend fun onPlaybackStopped(fileId: Int = -1) {
        warmupJob?.cancel()
        if (fileId > 0 && !downloadManager.isDownloading(fileId)) {
            client.cancelDownloadFile(fileId, onlyIfPending = false)
        }
        trimStorageIfNeeded()
    }

    suspend fun trimStorageIfNeeded(customLimitBytes: Long? = null) = withContext(Dispatchers.IO) {
        val limitMb = customLimitBytes?.let { it / (1024L * 1024L) } ?: getCacheLimitMb().first()
        val limitBytes = limitMb * 1024L * 1024L
        val currentSize = getCacheSize()
        if (currentSize <= limitBytes) return@withContext

        Log.i(TAG, "Trimming Telegram streaming cache: current=${currentSize / 1024 / 1024}MB > limit=${limitMb}MB")

        val protectedPaths = getProtectedPaths()
        val now = System.currentTimeMillis()
        val dir = File(context.filesDir, "tdlib_files")
        if (dir.exists()) {
            val candidateFiles = dir.walkBottomUp()
                .filter { it.isFile && !it.name.startsWith(".") && !it.name.endsWith(".json", ignoreCase = true) }
                .sortedBy { it.lastModified() } // Mais antigos primeiro (LRU real)
                .toList()

            var freedBytes = 0L
            val targetToFree = currentSize - limitBytes

            for (file in candidateFiles) {
                if (freedBytes >= targetToFree) break
                val canonical = try { file.canonicalPath } catch (_: Throwable) { file.absolutePath }
                val isProtected = protectedPaths.contains(canonical)
                // Protege arquivos que foram modificados nos últimos 20 segundos (buffer ativo do player atual)
                val isRecentlyModified = (now - file.lastModified()) < 20_000L

                if (!isProtected && !isRecentlyModified) {
                    val allocatedSize = try {
                        val stat = android.system.Os.stat(file.absolutePath)
                        (stat.st_blocks * 512L).coerceAtMost(file.length())
                    } catch (_: Throwable) {
                        file.length()
                    }

                    // Notifica a TDLib se o fileId for conhecido para este arquivo
                    val knownFileId = pathToFileId.remove(canonical)
                    if (knownFileId != null && knownFileId > 0) {
                        try {
                            client.deleteFile(knownFileId)
                        } catch (_: Throwable) {}
                    }

                    // Se a TDLib já apagou o arquivo do disco ou se o file.delete() apagou agora
                    val deleted = !file.exists() || file.delete()
                    if (deleted) {
                        freedBytes += allocatedSize
                        Log.d(TAG, "🧹 Removido cache antigo de streaming: ${file.name} (fileId=$knownFileId, ${allocatedSize / 1024 / 1024}MB)")
                    } else {
                        Log.w(TAG, "Não foi possível deletar arquivo de streaming: ${file.name}")
                    }
                }
            }
            Log.i(TAG, "🧹 Limpeza seletiva concluída: liberados ${freedBytes / 1024 / 1024}MB sem afetar downloads")
        }
    }

    suspend fun getChats(limit: Int = 200): List<TelegramChat> {
        val result = client.sendRequest(TdApi.GetChats().also { it.limit = limit })
        val chats = (result as? TdApi.Chats) ?: return emptyList()
        val out = mutableListOf<TelegramChat>()

        for (chatId in chats.chatIds) {
            val chat = client.sendRequest(TdApi.GetChat(chatId)) as? TdApi.Chat ?: continue
            when (val type = chat.type) {
                is TdApi.ChatTypePrivate, is TdApi.ChatTypeSecret -> continue
                is TdApi.ChatTypeSupergroup -> out.add(
                    TelegramChat(
                        id = chatId,
                        title = chat.title,
                        type = "chatTypeSupergroup",
                        memberCount = 0,
                        isChannel = type.isChannel
                    )
                )
                is TdApi.ChatTypeBasicGroup -> out.add(
                    TelegramChat(
                        id = chatId,
                        title = chat.title,
                        type = "chatTypeBasicGroup",
                        memberCount = 0,
                        isChannel = false
                    )
                )
            }
        }

        Log.d(TAG, "Loaded ${out.size} chats")
        return out
    }

    /** Search a single chat for video files matching the query. */
    suspend fun searchVideoMessagesInChat(
        chatId: Long,
        query: String,
        limit: Int = 20
    ): List<TelegramVideoMessage> {
        val filters = listOf(
            TdApi.SearchMessagesFilterDocument(),
            TdApi.SearchMessagesFilterVideo()
        )
        val seen = mutableSetOf<Pair<String, Long>>()
        val results = mutableListOf<TelegramVideoMessage>()

        for (filter in filters) {
            val result = client.sendRequest(TdApi.SearchChatMessages().also { req ->
                req.chatId = chatId
                req.query = query
                req.fromMessageId = 0
                req.offset = 0
                req.limit = limit
                req.filter = filter
            })
            val found = (result as? TdApi.FoundChatMessages) ?: continue

            for (msg in found.messages) {
                when (val content = msg.content) {
                    is TdApi.MessageDocument -> {
                        val mime = content.document.mimeType
                        if (!mime.startsWith("video/") && mime != "application/x-matroska") continue
                        val key = content.document.fileName to content.document.document.size
                        if (seen.add(key)) results.add(TelegramVideoMessage(
                            messageId = msg.id, chatId = msg.chatId,
                            fileName = content.document.fileName, fileId = content.document.document.id,
                            fileSize = content.document.document.size, duration = 0,
                            mimeType = mime, caption = content.caption.text,
                            remoteFileId = content.document.document.remote?.id
                        ))
                    }
                    is TdApi.MessageVideo -> {
                        val key = content.video.fileName to content.video.video.size
                        if (seen.add(key)) results.add(TelegramVideoMessage(
                            messageId = msg.id, chatId = msg.chatId,
                            fileName = content.video.fileName, fileId = content.video.video.id,
                            fileSize = content.video.video.size, duration = content.video.duration,
                            mimeType = content.video.mimeType, caption = content.caption.text,
                            remoteFileId = content.video.video.remote?.id
                        ))
                    }
                    else -> continue
                }
            }
        }
        return results
    }

    /**
     * Searches globally across all chats (equivalent to Telethon's iter_messages(None, ...)).
     * Runs two parallel searches — Document filter and Video filter — then merges results.
     */
    suspend fun searchVideoMessages(
        query: String,
        limit: Int = 50
    ): List<TelegramVideoMessage> {
        val filters = listOf(
            TdApi.SearchMessagesFilterDocument(),
            TdApi.SearchMessagesFilterVideo()
        )
        val seen = mutableSetOf<Pair<String, Long>>() // dedupe by (fileName, fileSize)
        val results = mutableListOf<TelegramVideoMessage>()

        for (filter in filters) {
            val result = client.sendRequest(TdApi.SearchMessages().also { req ->
                req.chatList = null  // null = search all chats (like Telethon's iter_messages(None))
                req.query = query
                req.offset = ""
                req.limit = limit
                req.filter = filter
            })
            val found = (result as? TdApi.FoundMessages) ?: continue

            for (msg in found.messages) {
                when (val content = msg.content) {
                    is TdApi.MessageDocument -> {
                        val mime = content.document.mimeType
                        if (!mime.startsWith("video/") && mime != "application/x-matroska") continue
                        val key = content.document.fileName to content.document.document.size
                        if (seen.add(key)) {
                            results.add(TelegramVideoMessage(
                                messageId = msg.id,
                                chatId = msg.chatId,
                                fileName = content.document.fileName,
                                fileId = content.document.document.id,
                                fileSize = content.document.document.size,
                                duration = 0,
                                mimeType = mime,
                                caption = content.caption.text,
                                remoteFileId = content.document.document.remote?.id
                            ))
                        }
                    }
                    is TdApi.MessageVideo -> {
                        val key = content.video.fileName to content.video.video.size
                        if (seen.add(key)) {
                            results.add(TelegramVideoMessage(
                                messageId = msg.id,
                                chatId = msg.chatId,
                                fileName = content.video.fileName,
                                fileId = content.video.video.id,
                                fileSize = content.video.video.size,
                                duration = content.video.duration,
                                mimeType = content.video.mimeType,
                                caption = content.caption.text,
                                remoteFileId = content.video.video.remote?.id
                            ))
                        }
                    }
                    else -> continue
                }
            }
        }

        return results
    }

    fun getStreamUrl(fileId: Int, mimeType: String = "", fileName: String = "", remoteId: String? = null): String {
        val type = com.arflix.tv.player.telegram.streamType(mimeType, fileName)
        return com.arflix.tv.player.telegram.tdFileUri(fileId, type, remoteId).toString()
    }

    fun getProxyStreamUrl(fileId: Int): String = proxy.getUrl(fileId)

    fun getLocalFileStreamUrl(file: java.io.File): String = proxy.getLocalUrl(file)

    fun getExcludedChatIds(): Flow<Set<Long>> =
        context.telegramDataStore.data.map { prefs ->
            prefs[KEY_EXCLUDED_CHATS]
                ?.split(",")
                ?.mapNotNull { it.toLongOrNull() }
                ?.toSet()
                ?: emptySet()
        }

    suspend fun setExcludedChatIds(ids: Set<Long>) {
        context.telegramDataStore.edit { prefs ->
            prefs[KEY_EXCLUDED_CHATS] = ids.joinToString(",")
        }
    }

    suspend fun toggleChatExclusion(chatId: Long, exclude: Boolean) {
        val current = getExcludedChatIds().first().toMutableSet()
        if (exclude) current.add(chatId) else current.remove(chatId)
        setExcludedChatIds(current)
    }
}
