package com.arflix.tv.data.telegram

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.arflix.tv.R
import com.arflix.tv.data.model.StreamSource
import com.arflix.tv.util.telegramDataStore
import com.google.gson.Gson
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.drinkless.tdlib.TdApi
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class FenixCatalog(
    val movies: List<Int> = emptyList(),
    val series: List<Int> = emptyList(),
    val updated_at: String? = null
)

data class FenixSeason(
    val id: Int,
    val seasonNumber: Int,
    val title: String?,
    val episodeCount: Int = 0
)

data class FenixEpisode(
    val id: Int,
    val episodeNumber: Int,
    val title: String?
)

@Singleton
class FenixRepository @Inject constructor(
    private val telegramClient: TelegramClient,
    private val telegramRepository: TelegramRepository,
    private val telegramProxy: TelegramStreamingProxy,
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "FenixRepository"
        private const val BOT_USERNAME = "fenixplayerbot"
        private const val CATALOG_QUERY = "catalog="
        private const val STREAMS_MV_QUERY = "streams_mv="
        private const val STREAMS_EP_QUERY = "streams_ep="
        
        private val LAST_CATALOG_VERSION = stringPreferencesKey("last_catalog_version")
    }

    private val gson = Gson()
    private val _catalog = MutableStateFlow(FenixCatalog())
    val catalog: StateFlow<FenixCatalog> = _catalog.asStateFlow()

    private val syncMutex = Mutex()
    private var lastSyncCheckTime = 0L
    private val SYNC_COOLDOWN_MS = 30 * 1000L // 30 segundos de intervalo entre checagens

    init {
        // Carregamento inicial do cache local para memória (bloqueante mas rápido para arquivos pequenos)
        val localCatalogFile = File(context.cacheDir, "fenix_catalog.json")
        if (localCatalogFile.exists()) {
            try {
                val content = localCatalogFile.readText()
                val parsed = gson.fromJson(content, FenixCatalog::class.java)
                if (parsed != null) {
                    _catalog.value = parsed
                    Log.i(TAG, "🚀 [INIT] Catálogo carregado do cache: ${parsed.movies.size} filmes, ${parsed.series.size} séries")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao carregar cache inicial: ${e.message}")
            }
        }
    }

    suspend fun syncCatalog(force: Boolean = false) {
        syncMutex.withLock {
            val now = System.currentTimeMillis()
            if (!force && now - lastSyncCheckTime < SYNC_COOLDOWN_MS && _catalog.value.movies.isNotEmpty()) {
                Log.d(TAG, "Sync skipped: Cooldown ativo (intervalo de ${SYNC_COOLDOWN_MS / 1000}s)")
                return@withLock
            }

            // Realizamos a operação de rede em um contexto Não Cancelável para evitar JobCancellationException
            // quando a HomeViewModel reinicia o carregamento.
            withContext(NonCancellable + Dispatchers.IO) {
                if (!telegramRepository.isAuthenticated()) {
                    Log.d(TAG, "Sync skipped: Telegram not authenticated")
                    loadLocalCatalog(File(context.cacheDir, "fenix_catalog.json"))
                    return@withContext
                }

                Log.i(TAG, "🚀 Iniciando sincronização inteligente do catálogo...")
                try {
                    // 1. Get cached version and check local file
                    val cachedVersion = context.telegramDataStore.data.first()[LAST_CATALOG_VERSION] ?: "0"
                    val localCatalogFile = File(context.cacheDir, "fenix_catalog.json")
                    val hasLocalFile = localCatalogFile.exists()

                    Log.d(TAG, "Local state: version=$cachedVersion, exists=$hasLocalFile")

                    // 2. Fetch remote version via inline query (with retry)
                    var searchBot: TdApi.Object? = null
                    var retryCount = 0
                    val maxRetries = 2
                    
                    while (retryCount <= maxRetries) {
                        try {
                            searchBot = telegramClient.sendRequest(TdApi.SearchPublicChat(BOT_USERNAME))
                            if (searchBot is TdApi.Chat) break
                        } catch (e: Exception) {
                            Log.w(TAG, "Attempt ${retryCount + 1} to search bot failed: ${e.message}")
                        }
                        retryCount++
                        if (retryCount <= maxRetries) delay(2000L * retryCount)
                    }

                    if (searchBot !is TdApi.Chat) {
                        Log.e(TAG, "❌ Bot not found after $retryCount attempts. Result: $searchBot")
                        loadLocalCatalog(localCatalogFile)
                        return@withContext
                    }
                    
                    val botChatId = searchBot.id
                    val privateChat = telegramClient.sendRequest(TdApi.CreatePrivateChat(botChatId, false))
                    val activeChatId = if (privateChat is TdApi.Chat) privateChat.id else botChatId

                    val inlineResults = telegramClient.sendRequest(
                        TdApi.GetInlineQueryResults(botChatId, activeChatId, TdApi.Location(), CATALOG_QUERY, "")
                    ) as? TdApi.InlineQueryResults
                    
                    if (inlineResults == null || inlineResults.results.isNullOrEmpty()) {
                        Log.w(TAG, "⚠️ Inline query failed, falling back to local...")
                        loadLocalCatalog(localCatalogFile)
                        return@withContext
                    }

                    val result = inlineResults.results.firstOrNull() as? TdApi.InlineQueryResultDocument
                    if (result == null) {
                        Log.e(TAG, "❌ No catalog document in results")
                        loadLocalCatalog(localCatalogFile)
                        return@withContext
                    }

                    val remoteVersion = result.description ?: "unknown"
                    val fileId = result.document.document.id

                    Log.i(TAG, "Remote Version: $remoteVersion (Local: $cachedVersion)")
                    
                    // Marcar sucesso na checagem apenas se chegamos até aqui
                    lastSyncCheckTime = System.currentTimeMillis()

                    // 3. Compare versions
                    if (hasLocalFile && remoteVersion == cachedVersion) {
                        Log.i(TAG, "✅ Catalog version matches. Loading from local cache.")
                        loadLocalCatalog(localCatalogFile)
                        return@withContext
                    }

                    // 4. Download new version with Nudge logic
                    Log.i(TAG, "📥 New version detected ($remoteVersion). Starting download...")
                    telegramClient.sendRequest(TdApi.DownloadFile(fileId, 32, 0, 0, true))
                    
                    var lastDownloadedSize = -1L
                    var stagnationStartTime = System.currentTimeMillis()
                    val totalTimeoutMs = 45_000L
                    val nudgeIntervalMs = 5_000L
                    val startSyncTime = System.currentTimeMillis()

                    while (System.currentTimeMillis() - startSyncTime < totalTimeoutMs) {
                        val file = telegramClient.sendRequest(TdApi.GetFile(fileId)) as? TdApi.File
                        if (file != null) {
                            if (file.local?.isDownloadingCompleted == true) {
                                val downloadedPath = file.local.path
                                if (!downloadedPath.isNullOrEmpty()) {
                                    // Copy to a stable location in cache
                                    File(downloadedPath).copyTo(localCatalogFile, overwrite = true)
                                    Log.i(TAG, "✅ Download complete. New version saved: $remoteVersion")
                                    
                                    // Update DataStore version
                                    context.telegramDataStore.edit { it[LAST_CATALOG_VERSION] = remoteVersion }
                                    loadLocalCatalog(localCatalogFile)
                                    return@withContext
                                }
                            }

                            val currentSize = file.local?.downloadedSize ?: 0L
                            if (currentSize > lastDownloadedSize) {
                                lastDownloadedSize = currentSize
                                stagnationStartTime = System.currentTimeMillis()
                            } else if (System.currentTimeMillis() - stagnationStartTime > nudgeIntervalMs) {
                                Log.w(TAG, "⏳ Stagnation detected at ${currentSize} bytes. Sending NUDGE...")
                                telegramClient.sendRequest(TdApi.DownloadFile(fileId, 32, 0, 0, true))
                                stagnationStartTime = System.currentTimeMillis()
                            }
                        }
                        delay(800)
                    }

                    Log.e(TAG, "❌ Download timed out after ${totalTimeoutMs/1000}s. Falling back to old local...")
                    loadLocalCatalog(localCatalogFile)

                } catch (e: Exception) {
                    Log.e(TAG, "❌ Sync error: ${e.message}", e)
                    val localCatalogFile = File(context.cacheDir, "fenix_catalog.json")
                    loadLocalCatalog(localCatalogFile)
                }
            }
        }
    }

    private suspend fun loadLocalCatalog(file: File) {
        if (!file.exists()) {
            Log.w(TAG, "loadLocalCatalog: File does not exist")
            return
        }
        try {
            val content = withContext(Dispatchers.IO) { file.readText() }
            val parsed = gson.fromJson(content, FenixCatalog::class.java)
            if (parsed != null) {
                _catalog.value = parsed
                Log.i(TAG, "📖 Loaded ${parsed.movies.size} movies and ${parsed.series.size} series from local cache")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading local catalog: ${e.message}")
        }
    }

    fun isMovieAvailable(tmdbId: Int): Boolean = _catalog.value.movies.contains(tmdbId)
    fun isSeriesAvailable(tmdbId: Int): Boolean = _catalog.value.series.contains(tmdbId)

    suspend fun resolveMovieStreams(tmdbId: Int): List<StreamSource> {
        val timestamp = System.currentTimeMillis() / 30000
        Log.i(TAG, "🎬 Resolving Fenix streams for TMDB: $tmdbId (ts=$timestamp)")
        return resolveStreamsInternal("$STREAMS_MV_QUERY$tmdbId&t=$timestamp")
    }

    suspend fun resolveEpisodeStreams(seriesId: Int, season: Int, episode: Int): List<StreamSource> {
        val timestamp = System.currentTimeMillis() / 30000
        Log.i(TAG, "📺 Resolving Fenix streams for Series: $seriesId S$season E$episode (ts=$timestamp)")
        return resolveStreamsInternal("$STREAMS_EP_QUERY$seriesId&s=$season&e=$episode&t=$timestamp")
    }

    suspend fun getSeasons(seriesId: Int): List<FenixSeason> {
        if (!telegramRepository.isAuthenticated()) return emptyList()
        val timestamp = System.currentTimeMillis() / 30000 // New cache every 30s
        Log.d(TAG, "📺 Fetching Fenix seasons for series: $seriesId (ts=$timestamp)")
        
        val results = performTurboQuery("at=$seriesId&t=$timestamp")
        return results.mapNotNull { res ->
            if (res is TdApi.InlineQueryResultArticle) {
                val id = res.id.removePrefix("fs_").toIntOrNull() ?: return@mapNotNull null
                val title = res.title ?: ""
                val number = title.filter { it.isDigit() }.toIntOrNull() ?: 0
                
                // Extrair contagem de episódios da descrição (ex: "1050 Episódios")
                val desc = res.description ?: ""
                val epCount = desc.substringBefore(" ").toIntOrNull() ?: 0
                
                FenixSeason(id, number, desc, epCount)
            } else null
        }
    }

    suspend fun getEpisodes(seasonId: Int, offset: Int = 0): List<FenixEpisode> {
        if (!telegramRepository.isAuthenticated()) return emptyList()
        val timestamp = System.currentTimeMillis() / 30000 // New cache every 30s
        Log.d(TAG, "🎞️ Fetching Fenix episodes batch for season: $seasonId (offset=$offset, ts=$timestamp)")
        
        val results = performTurboQuery("ae=$seasonId&t=$timestamp", offset.toString())
        return results.mapNotNull { res ->
            if (res is TdApi.InlineQueryResultArticle) {
                val id = res.id.removePrefix("fe_").toIntOrNull() ?: return@mapNotNull null
                val title = res.title ?: ""
                val titlePart = title.substringAfter(". ").trim()
                val number = title.substringBefore(".").toIntOrNull() ?: 0
                FenixEpisode(id, number, titlePart)
            } else null
        }
    }
    
    // Legacy support for backward compatibility if needed elsewhere
    suspend fun getAllEpisodes(seasonId: Int): List<FenixEpisode> {
        val allEpisodes = mutableListOf<FenixEpisode>()
        var offset = 0
        while (true) {
            val batch = getEpisodes(seasonId, offset)
            if (batch.isEmpty()) break
            allEpisodes.addAll(batch)
            if (batch.size < 50) break
            offset += 50
        }
        return allEpisodes
    }

    private suspend fun performTurboQuery(query: String, offset: String = ""): List<TdApi.InlineQueryResult> {
        var attempts = 0
        while (attempts < 5) {
            try {
                val searchBot = telegramClient.sendRequest(TdApi.SearchPublicChat(BOT_USERNAME))
                if (searchBot !is TdApi.Chat) {
                    attempts++
                    delay(800)
                    continue
                }
                
                val botChatId = searchBot.id
                val privateChat = telegramClient.sendRequest(TdApi.CreatePrivateChat(botChatId, false))
                val activeChatId = if (privateChat is TdApi.Chat) privateChat.id else botChatId

                val response = telegramClient.sendRequest(
                    TdApi.GetInlineQueryResults(botChatId, activeChatId, TdApi.Location(), query, offset)
                )
                
                if (response is TdApi.InlineQueryResults) {
                    val results = response.results?.toList() ?: emptyList()
                    if (results.isNotEmpty()) return results
                    
                    // If results are empty, it might be a temporary Telegram glitch, retry
                    Log.d(TAG, "Turbo query '$query' returned 0 results, retrying (${attempts + 1}/5)...")
                } else {
                    Log.w(TAG, "Turbo query '$query' failed with response: $response, retrying (${attempts + 1}/5)...")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Turbo query error: $query", e)
            }
            attempts++
            delay(800)
        }
        return emptyList()
    }

    private suspend fun resolveStreamsInternal(query: String): List<StreamSource> {
        if (!telegramRepository.isAuthenticated()) return emptyList()
        
        var attempts = 0
        while (attempts < 4) {
            try {
                val searchBot = telegramClient.sendRequest(TdApi.SearchPublicChat(BOT_USERNAME))
                if (searchBot !is TdApi.Chat) {
                    attempts++
                    delay(1000)
                    continue
                }
                val botChatId = searchBot.id
                val privateChat = telegramClient.sendRequest(TdApi.CreatePrivateChat(botChatId, false))
                val activeChatId = if (privateChat is TdApi.Chat) privateChat.id else botChatId

                val response = telegramClient.sendRequest(
                    TdApi.GetInlineQueryResults(botChatId, activeChatId, TdApi.Location(), query, "")
                )
                
                if (response is TdApi.InlineQueryResults) {
                    val results = response.results ?: emptyArray()
                    if (results.isNotEmpty()) {
                        Log.d(TAG, "🔎 Received ${results.size} results from bot. Parsing...")
                        
                        val streams = mutableListOf<StreamSource>()
                        for (res in results) {
                            val file: TdApi.File
                            val fileName: String
                            val resTitle: String?
                            val resDesc: String?

                            when (res) {
                                is TdApi.InlineQueryResultDocument -> {
                                    file = res.document.document
                                    fileName = res.document.fileName
                                    resTitle = res.title
                                    resDesc = res.description
                                }
                                is TdApi.InlineQueryResultVideo -> {
                                    file = res.video.video
                                    fileName = res.video.fileName
                                    resTitle = res.title
                                    resDesc = res.description
                                }
                                else -> continue
                            }

                            val title = resTitle ?: fileName
                            val streamUrl = telegramProxy.getUrl(file.id)
                            streams.add(
                                StreamSource(
                                    source = title,
                                    addonName = context.getString(R.string.fenix_bot_source),
                                    addonId = "fenix_bot_native",
                                    quality = parseQuality(title),
                                    size = formatBytes(file.size),
                                    sizeBytes = file.size,
                                    url = streamUrl,
                                    behaviorHints = com.arflix.tv.data.model.StreamBehaviorHints(
                                        notWebReady = false,
                                        filename = title,
                                        videoSize = file.size,
                                        bingeGroup = "fenix-bot-native"
                                    ),
                                    description = resDesc
                                )
                            )
                        }
                        Log.i(TAG, "✅ Successfully parsed ${streams.size} Fenix streams")
                        return streams
                    }
                    Log.d(TAG, "Streams query '$query' returned 0 results, retrying (${attempts + 1}/4)...")
                } else {
                    Log.w(TAG, "Streams query '$query' failed with response: $response, retrying (${attempts + 1}/4)...")
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error in resolveStreamsInternal", e)
            }
            attempts++
            delay(1000)
        }
        return emptyList()
    }

    private fun parseQuality(name: String): String {
        val t = name.lowercase()
        return when {
            t.contains("4k") || t.contains("2160") -> "4K"
            t.contains("1080") -> "1080p"
            t.contains("720") -> "720p"
            t.contains("480") -> "480p"
            else -> "Unknown"
        }
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes <= 0 -> ""
        bytes >= 1_000_000_000 -> "%.2f GB".format(bytes / 1_000_000_000.0)
        bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
        else -> "%.0f KB".format(bytes / 1_000.0)
    }
}
