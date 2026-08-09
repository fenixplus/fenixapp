package com.arflix.tv.data.telegram

import android.content.Context
import android.util.Log
import com.arflix.tv.R
import com.arflix.tv.data.model.StreamSource
import com.google.gson.Gson
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
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
    }

    private val gson = Gson()
    private val _catalog = MutableStateFlow(FenixCatalog())
    val catalog: StateFlow<FenixCatalog> = _catalog.asStateFlow()

    private var isSyncing = false

    suspend fun syncCatalog() {
        if (isSyncing || !telegramRepository.isAuthenticated()) {
            Log.d(TAG, "Sync skipped: syncing=$isSyncing auth=${telegramRepository.isAuthenticated()}")
            return
        }
        isSyncing = true
        Log.i(TAG, "🚀 Syncing catalog...")

        try {
            Log.i(TAG, "STEP 1: Searching for bot @$BOT_USERNAME...")
            val searchBot = telegramClient.sendRequest(TdApi.SearchPublicChat(BOT_USERNAME))
            if (searchBot !is TdApi.Chat) {
                Log.e(TAG, "❌ Bot not found or error: $searchBot")
                return
            }
            val botChatId = searchBot.id
            Log.i(TAG, "STEP 2: Creating private chat with bot (ID: $botChatId)...")
            val privateChat = telegramClient.sendRequest(TdApi.CreatePrivateChat(botChatId, false))
            val activeChatId = if (privateChat is TdApi.Chat) privateChat.id else botChatId
            Log.i(TAG, "STEP 3: Sending inline query '$CATALOG_QUERY'...")

            val inlineResults = telegramClient.sendRequest(
                TdApi.GetInlineQueryResults(botChatId, activeChatId, TdApi.Location(), CATALOG_QUERY, "")
            ) as? TdApi.InlineQueryResults
            
            if (inlineResults == null) {
                Log.e(TAG, "❌ Inline query failed or timed out")
                return
            }

            val results = inlineResults.results ?: emptyArray()
            Log.i(TAG, "STEP 4: Received ${results.size} inline results")
            var fileId = 0
            for (res in results) {
                if (res is TdApi.InlineQueryResultDocument) {
                    fileId = res.document.document.id
                    Log.i(TAG, "Found catalog document: fileId=$fileId")
                    break
                }
            }

            if (fileId != 0) {
                Log.i(TAG, "STEP 5: Downloading catalog file...")
                telegramClient.sendRequest(TdApi.DownloadFile(fileId, 32, 0, 0, true))
                var localPath: String? = null
                var attempts = 0
                while (attempts < 30) {
                    val file = telegramClient.sendRequest(TdApi.GetFile(fileId))
                    if (file is TdApi.File && file.local?.isDownloadingCompleted == true) {
                        localPath = file.local.path
                        break
                    }
                    if (attempts % 5 == 0) Log.i(TAG, "⏳ Waiting for download (attempt $attempts/30)...")
                    delay(1000)
                    attempts++
                }
                if (!localPath.isNullOrEmpty()) {
                    Log.i(TAG, "STEP 6: Reading catalog file from $localPath")
                    val content = withContext(Dispatchers.IO) { File(localPath).readText() }
                    val parsed = gson.fromJson(content, FenixCatalog::class.java)
                    if (parsed != null) {
                        _catalog.value = parsed
                        Log.i(TAG, "✅ Catalog synced successfully: ${parsed.movies.size} movies, ${parsed.series.size} series")
                    }
                } else {
                    Log.e(TAG, "❌ Catalog download failed after 30s")
                }
            } else {
                Log.e(TAG, "❌ No catalog document found in inline results")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Sync error: ${e.message}", e)
        } finally {
            isSyncing = false
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
