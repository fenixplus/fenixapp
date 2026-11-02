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
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.drinkless.tdlib.TdApi
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import com.google.gson.JsonParser
import com.arflix.tv.data.model.MediaType

data class FenixCatalog(
    val movies: List<Int> = emptyList(),
    val series: List<Int> = emptyList(),
    val moviesByRating: Map<String, List<Int>> = emptyMap(),
    val seriesByRating: Map<String, List<Int>> = emptyMap(),
    val movieRatingMap: Map<Int, Int> = emptyMap(),
    val seriesRatingMap: Map<Int, Int> = emptyMap(),
    val updated_at: String? = null
)

data class FenixSeason(
    val id: Int,
    val seasonNumber: Int,
    val title: String?,
    val episodeCount: Int = 0,
    val episodeNumbers: List<Int> = emptyList()
)

data class FenixEpisode(
    val id: Int,
    val episodeNumber: Int,
    val title: String?
)

data class SeriesWatchPosition(
    val seriesId: Int,
    val season: Int,
    val episode: Int
)

data class FenixNextEpisodeResult(
    val seriesId: Int,
    val nextSeason: Int,
    val nextEpisode: Int,
    val hasNext: Boolean
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
        private const val CATALOG_V2_QUERY = "catalog_v2="
        private const val CATALOG_LEGACY_QUERY = "catalog="
        private const val STREAMS_MV_QUERY = "streams_mv="
        private const val STREAMS_EP_QUERY = "streams_ep="
        private const val SERIES_BATCH_QUERY = "sb="
        
        private val LAST_CATALOG_VERSION = stringPreferencesKey("last_catalog_version")
        private val CATALOG_SCHEMA_VERSION_KEY = stringPreferencesKey("fenix_catalog_schema_version")
        private const val CURRENT_CATALOG_SCHEMA = "2"
        private val CONCLUDED_SERIES_KEY = stringPreferencesKey("fenix_concluded_series")

        fun parseCatalogJson(content: String): FenixCatalog {
            return try {
                val root = JsonParser.parseString(content).asJsonObject
                val updatedAt = root.get("updated_at")?.asString

                val moviesList = mutableListOf<Int>()
                val moviesByRating = mutableMapOf<String, List<Int>>()
                val movieRatingMap = mutableMapOf<Int, Int>()

                val moviesElement = root.get("movies")
                if (moviesElement != null) {
                    if (moviesElement.isJsonArray) {
                        for (elem in moviesElement.asJsonArray) {
                            moviesList.add(elem.asInt)
                        }
                    } else if (moviesElement.isJsonObject) {
                        val obj = moviesElement.asJsonObject
                        for ((ratingKey, idsElement) in obj.entrySet()) {
                            if (idsElement.isJsonArray) {
                                val ids = mutableListOf<Int>()
                                val age = if (ratingKey.equals("NR", ignoreCase = true)) -1 else ratingKey.toIntOrNull() ?: -1
                                for (elem in idsElement.asJsonArray) {
                                    val id = elem.asInt
                                    ids.add(id)
                                    moviesList.add(id)
                                    movieRatingMap[id] = age
                                }
                                moviesByRating[ratingKey] = ids
                            }
                        }
                    }
                }

                val seriesList = mutableListOf<Int>()
                val seriesByRating = mutableMapOf<String, List<Int>>()
                val seriesRatingMap = mutableMapOf<Int, Int>()

                val seriesElement = root.get("series")
                if (seriesElement != null) {
                    if (seriesElement.isJsonArray) {
                        for (elem in seriesElement.asJsonArray) {
                            seriesList.add(elem.asInt)
                        }
                    } else if (seriesElement.isJsonObject) {
                        val obj = seriesElement.asJsonObject
                        for ((ratingKey, idsElement) in obj.entrySet()) {
                            if (idsElement.isJsonArray) {
                                val ids = mutableListOf<Int>()
                                val age = if (ratingKey.equals("NR", ignoreCase = true)) -1 else ratingKey.toIntOrNull() ?: -1
                                for (elem in idsElement.asJsonArray) {
                                    val id = elem.asInt
                                    ids.add(id)
                                    seriesList.add(id)
                                    seriesRatingMap[id] = age
                                }
                                seriesByRating[ratingKey] = ids
                            }
                        }
                    }
                }

                FenixCatalog(
                    movies = moviesList,
                    series = seriesList,
                    moviesByRating = moviesByRating,
                    seriesByRating = seriesByRating,
                    movieRatingMap = movieRatingMap,
                    seriesRatingMap = seriesRatingMap,
                    updated_at = updatedAt
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse catalog JSON: ${e.message}")
                FenixCatalog()
            }
        }
    }

    private val gson = Gson()
    private val _catalog = MutableStateFlow(FenixCatalog())
    val catalog: StateFlow<FenixCatalog> = _catalog.asStateFlow()

    private val _isCatalogSyncing = MutableStateFlow(false)
    val isCatalogSyncing: StateFlow<Boolean> = _isCatalogSyncing.asStateFlow()

    private val syncMutex = Mutex()
    private var lastSyncCheckTime = 0L
    private val SYNC_COOLDOWN_MS = 30 * 1000L // 30 segundos de intervalo entre checagens

    @Volatile
    private var backgroundBatchJob: Job? = null

    fun cancelBackgroundBatchCheck() {
        if (backgroundBatchJob?.isActive == true) {
            Log.d(TAG, "⏹️ Cancelando checagem em lote em segundo plano para priorizar ação do usuário")
            backgroundBatchJob?.cancel()
            backgroundBatchJob = null
        }
    }

    init {
        // Carregamento inicial do cache local para memória (bloqueante mas rápido para arquivos pequenos)
        val localCatalogFile = File(context.cacheDir, "fenix_catalog.json")
        if (localCatalogFile.exists()) {
            try {
                val content = localCatalogFile.readText()
                val parsed = parseCatalogJson(content)
                _catalog.value = parsed
                Log.i(TAG, "🚀 [INIT] Catálogo carregado do cache: ${parsed.movies.size} filmes, ${parsed.series.size} séries (${parsed.movieRatingMap.size} classificados)")
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

            _isCatalogSyncing.value = true
            try {
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

                    // 2. Fetch remote version via inline query (try catalog_v2= first, fallback to catalog=)
                    var inlineResults = telegramClient.sendRequest(
                        TdApi.GetInlineQueryResults(botChatId, activeChatId, TdApi.Location(), CATALOG_V2_QUERY, "")
                    ) as? TdApi.InlineQueryResults

                    if (inlineResults == null || inlineResults.results.isNullOrEmpty()) {
                        Log.d(TAG, "catalog_v2= returned empty, trying fallback catalog=...")
                        inlineResults = telegramClient.sendRequest(
                            TdApi.GetInlineQueryResults(botChatId, activeChatId, TdApi.Location(), CATALOG_LEGACY_QUERY, "")
                        ) as? TdApi.InlineQueryResults
                    }
                    
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
                    val cachedSchema = context.telegramDataStore.data.first()[CATALOG_SCHEMA_VERSION_KEY] ?: "1"

                    Log.i(TAG, "Remote Version: $remoteVersion (Local: $cachedVersion, Schema: $cachedSchema)")
                    
                    // Marcar sucesso na checagem apenas se chegamos até aqui
                    lastSyncCheckTime = System.currentTimeMillis()

                    // 3. Compare versions AND ensure schema is current
                    if (hasLocalFile && remoteVersion == cachedVersion && cachedSchema == CURRENT_CATALOG_SCHEMA) {
                        Log.i(TAG, "✅ Catalog version and schema match. Loading from local cache.")
                        loadLocalCatalog(localCatalogFile)
                        return@withContext
                    }

                    // 4. Download new version with Nudge logic
                    Log.i(TAG, "📥 New version or schema upgrade detected ($remoteVersion, schema=$CURRENT_CATALOG_SCHEMA). Starting download...")
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
                                    Log.i(TAG, "✅ Download complete. New version saved: $remoteVersion (schema $CURRENT_CATALOG_SCHEMA)")
                                    
                                    // Update DataStore version and schema
                                    context.telegramDataStore.edit {
                                        it[LAST_CATALOG_VERSION] = remoteVersion
                                        it[CATALOG_SCHEMA_VERSION_KEY] = CURRENT_CATALOG_SCHEMA
                                    }
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
            } finally {
                _isCatalogSyncing.value = false
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
            val parsed = parseCatalogJson(content)
            _catalog.value = parsed
            Log.i(TAG, "📖 Loaded ${parsed.movies.size} movies and ${parsed.series.size} series (${parsed.movieRatingMap.size} rated) from local cache")
        } catch (e: Exception) {
            Log.e(TAG, "Error reading local catalog: ${e.message}")
        }
    }

    fun isMovieAvailable(tmdbId: Int): Boolean = _catalog.value.movies.contains(tmdbId)
    fun isSeriesAvailable(tmdbId: Int): Boolean = _catalog.value.series.contains(tmdbId)

    fun getRating(mediaType: MediaType, tmdbId: Int): Int? {
        val cat = _catalog.value
        return when (mediaType) {
            MediaType.MOVIE -> cat.movieRatingMap[tmdbId]
            MediaType.TV -> cat.seriesRatingMap[tmdbId]
        }
    }

    fun isAllowedForKids(mediaType: MediaType, tmdbId: Int, maxAge: Int): Boolean {
        val rating = getRating(mediaType, tmdbId) ?: return false
        return rating != -1 && rating <= maxAge
    }

    fun getKidsMovies(maxAge: Int): List<Int> {
        val cat = _catalog.value
        val allowedKeys = when {
            maxAge <= 7 -> listOf("0")
            maxAge <= 10 -> listOf("0", "10")
            maxAge <= 12 -> listOf("0", "10", "12")
            maxAge <= 14 -> listOf("0", "10", "12", "14")
            else -> listOf("0", "10", "12", "14", "16")
        }
        return allowedKeys.flatMap { cat.moviesByRating[it].orEmpty() }
    }

    fun getKidsSeries(maxAge: Int): List<Int> {
        val cat = _catalog.value
        val allowedKeys = when {
            maxAge <= 7 -> listOf("0")
            maxAge <= 10 -> listOf("0", "10")
            maxAge <= 12 -> listOf("0", "10", "12")
            maxAge <= 14 -> listOf("0", "10", "12", "14")
            else -> listOf("0", "10", "12", "14", "16")
        }
        return allowedKeys.flatMap { cat.seriesByRating[it].orEmpty() }
    }

    suspend fun resolveMovieStreams(tmdbId: Int): List<StreamSource> {
        cancelBackgroundBatchCheck()
        val timestamp = System.currentTimeMillis() / 30000
        Log.i(TAG, "🎬 Resolving Fenix streams for TMDB: $tmdbId (ts=$timestamp)")
        return resolveStreamsInternal("$STREAMS_MV_QUERY$tmdbId&t=$timestamp")
    }

    suspend fun resolveEpisodeStreams(seriesId: Int, season: Int, episode: Int): List<StreamSource> {
        cancelBackgroundBatchCheck()
        val timestamp = System.currentTimeMillis() / 30000
        Log.i(TAG, "📺 Resolving Fenix streams for Series: $seriesId S$season E$episode (ts=$timestamp)")
        return resolveStreamsInternal("$STREAMS_EP_QUERY$seriesId&s=$season&e=$episode&t=$timestamp")
    }

    fun parseEpisodesNotation(desc: String): List<Int> {
        val bracketContent = desc.substringAfter("[", "").substringBefore("]", "").trim()
        if (bracketContent.isBlank()) {
            val count = desc.substringBefore(" ").toIntOrNull() ?: 0
            return if (count > 0) (1..count).toList() else emptyList()
        }

        val parts = bracketContent.split('!')
        val rangePart = parts[0].trim()
        val missingPart = parts.getOrNull(1)?.trim()

        val allNumbers = linkedSetOf<Int>()
        if (rangePart.contains("-")) {
            val start = rangePart.substringBefore("-").trim().toIntOrNull() ?: 1
            val end = rangePart.substringAfter("-").trim().toIntOrNull() ?: start
            if (start <= end) {
                for (i in start..end) {
                    allNumbers.add(i)
                }
            }
        } else {
            rangePart.toIntOrNull()?.let { allNumbers.add(it) }
        }

        if (!missingPart.isNullOrBlank()) {
            val missingItems = missingPart.split(',')
            for (item in missingItems) {
                val trimmed = item.trim()
                if (trimmed.contains("-")) {
                    val mStart = trimmed.substringBefore("-").trim().toIntOrNull()
                    val mEnd = trimmed.substringAfter("-").trim().toIntOrNull()
                    if (mStart != null && mEnd != null && mStart <= mEnd) {
                        for (m in mStart..mEnd) {
                            allNumbers.remove(m)
                        }
                    }
                } else {
                    trimmed.toIntOrNull()?.let { allNumbers.remove(it) }
                }
            }
        }

        val result = allNumbers.toList().sorted()
        if (result.isEmpty()) {
            val count = desc.substringBefore(" ").toIntOrNull() ?: 0
            return if (count > 0) (1..count).toList() else emptyList()
        }
        return result
    }

    suspend fun getSeasons(seriesId: Int): List<FenixSeason> {
        cancelBackgroundBatchCheck()
        if (!telegramRepository.isAuthenticated()) return emptyList()
        val timestamp = System.currentTimeMillis() / 30000 // New cache every 30s
        Log.d(TAG, "📺 Fetching Fenix seasons for series: $seriesId (ts=$timestamp)")
        
        // 1. Tenta a nova rota Turbo V2 (at2=) com suporte a gaps e ranges
        var results = performTurboQuery("at2=$seriesId&t=$timestamp")
        
        // 2. Se vazio, recorre à rota anterior (at=) para manter retrocompatibilidade
        if (results.isEmpty()) {
            Log.d(TAG, "Turbo V2 (at2=) retornou vazio para $seriesId. Tentando fallback at=")
            results = performTurboQuery("at=$seriesId&t=$timestamp")
        }

        return results.mapNotNull { res ->
            if (res is TdApi.InlineQueryResultArticle) {
                val id = res.id.removePrefix("fs_").toIntOrNull() ?: return@mapNotNull null
                val title = res.title ?: ""
                val number = title.filter { it.isDigit() }.toIntOrNull() ?: 0
                
                // Extrair contagem e lista de episódios da descrição
                val desc = res.description ?: ""
                val epNumbers = parseEpisodesNotation(desc)
                val epCount = if (epNumbers.isNotEmpty()) epNumbers.size else (desc.substringBefore(" ").toIntOrNull() ?: 0)
                
                FenixSeason(id, number, desc, epCount, epNumbers)
            } else null
        }
    }

    suspend fun getEpisodes(seasonId: Int, offset: Int = 0): List<FenixEpisode> {
        cancelBackgroundBatchCheck()
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

    suspend fun getConcludedSeries(): List<SeriesWatchPosition> {
        return withContext(Dispatchers.IO) {
            val json = context.telegramDataStore.data.first()[CONCLUDED_SERIES_KEY] ?: return@withContext emptyList()
            try {
                val type = object : com.google.gson.reflect.TypeToken<List<SeriesWatchPosition>>() {}.type
                gson.fromJson<List<SeriesWatchPosition>>(json, type).orEmpty()
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    suspend fun recordConcludedSeries(seriesId: Int, season: Int, episode: Int) {
        withContext(Dispatchers.IO) {
            context.telegramDataStore.edit { prefs ->
                val current = try {
                    val json = prefs[CONCLUDED_SERIES_KEY]
                    if (!json.isNullOrBlank()) {
                        val type = object : com.google.gson.reflect.TypeToken<List<SeriesWatchPosition>>() {}.type
                        gson.fromJson<List<SeriesWatchPosition>>(json, type).orEmpty().toMutableList()
                    } else mutableListOf()
                } catch (_: Exception) {
                    mutableListOf()
                }

                current.removeAll { it.seriesId == seriesId }
                current.add(SeriesWatchPosition(seriesId, season, episode))
                prefs[CONCLUDED_SERIES_KEY] = gson.toJson(current.takeLast(50))
                Log.d(TAG, "📌 Série $seriesId registrada como concluída em S$season E$episode (total: ${current.size})")
            }
        }
    }

    suspend fun removeConcludedSeries(seriesId: Int) {
        withContext(Dispatchers.IO) {
            context.telegramDataStore.edit { prefs ->
                val json = prefs[CONCLUDED_SERIES_KEY] ?: return@edit
                val current = try {
                    val type = object : com.google.gson.reflect.TypeToken<List<SeriesWatchPosition>>() {}.type
                    gson.fromJson<List<SeriesWatchPosition>>(json, type).orEmpty().toMutableList()
                } catch (_: Exception) {
                    mutableListOf()
                }
                if (current.removeAll { it.seriesId == seriesId }) {
                    prefs[CONCLUDED_SERIES_KEY] = gson.toJson(current)
                    Log.d(TAG, "🗑️ Série $seriesId removida da lista de concluídas")
                }
            }
        }
    }

    suspend fun checkNextEpisodesBatch(seriesList: List<SeriesWatchPosition>): List<FenixNextEpisodeResult> {
        if (seriesList.isEmpty() || !telegramRepository.isAuthenticated()) return emptyList()

        return coroutineScope {
            val job = coroutineContext[Job]
            backgroundBatchJob = job

            try {
                // Divide em pedaços garantindo que a query inteira fique <= 200 caracteres (bem abaixo do teto de 256)
                val chunks = mutableListOf<List<SeriesWatchPosition>>()
                var currentChunk = mutableListOf<SeriesWatchPosition>()
                var currentLen = 0
                val maxLen = 200 // Limite de segurança rigoroso

                for (item in seriesList) {
                    val itemStr = "${item.seriesId}:${item.season}:${item.episode},"
                    if (currentLen + itemStr.length > maxLen && currentChunk.isNotEmpty()) {
                        chunks.add(currentChunk)
                        currentChunk = mutableListOf()
                        currentLen = 0
                    }
                    currentChunk.add(item)
                    currentLen += itemStr.length
                }
                if (currentChunk.isNotEmpty()) {
                    chunks.add(currentChunk)
                }

                val results = mutableListOf<FenixNextEpisodeResult>()
                val timestamp = System.currentTimeMillis() / 30000

                for (chunk in chunks) {
                    coroutineContext.ensureActive()

                    val payload = chunk.joinToString(",") { "${it.seriesId}:${it.season}:${it.episode}" }
                    val query = "$SERIES_BATCH_QUERY$payload&t=$timestamp"
                    Log.d(TAG, "🔎 Executando busca em lote: '$query' (${query.length} chars)")

                    val queryResults = performTurboQuery(query)
                    for (res in queryResults) {
                        if (res is TdApi.InlineQueryResultArticle) {
                            val seriesId = res.id.removePrefix("sb_").toIntOrNull() ?: continue
                            val desc = res.description ?: "none"
                            if (desc != "none" && desc.contains(":")) {
                                val sNum = desc.substringBefore(":").toIntOrNull() ?: 0
                                val eNum = desc.substringAfter(":").toIntOrNull() ?: 0
                                if (sNum > 0 && eNum > 0) {
                                    results.add(FenixNextEpisodeResult(seriesId, sNum, eNum, true))
                                    continue
                                }
                            }
                            results.add(FenixNextEpisodeResult(seriesId, 0, 0, false))
                        }
                    }
                }
                results
            } finally {
                if (backgroundBatchJob === job) {
                    backgroundBatchJob = null
                }
            }
        }
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
            else -> ""
        }
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes <= 0 -> ""
        bytes >= 1_000_000_000 -> "%.2f GB".format(bytes / 1_000_000_000.0)
        bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
        else -> "%.0f KB".format(bytes / 1_000.0)
    }
}
