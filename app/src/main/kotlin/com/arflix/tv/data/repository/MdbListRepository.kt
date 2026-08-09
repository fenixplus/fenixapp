package com.arflix.tv.data.repository

import com.arflix.tv.data.api.MdbListApi
import com.arflix.tv.data.api.MdbIds
import com.arflix.tv.data.api.MdbIdsItem
import com.arflix.tv.data.api.MdbPlaybackItem
import com.arflix.tv.data.api.MdbScrobbleBody
import com.arflix.tv.data.api.MdbScrobbleClearBody
import com.arflix.tv.data.api.MdbScrobbleEpisodeNumber
import com.arflix.tv.data.api.MdbScrobbleMovie
import com.arflix.tv.data.api.MdbScrobbleSeason
import com.arflix.tv.data.api.MdbScrobbleShow
import com.arflix.tv.data.api.MdbTmdbRef
import com.arflix.tv.data.api.MdbWatchedBody
import com.arflix.tv.data.api.MdbWatchedEpisodeRef
import com.arflix.tv.data.api.MdbWatchedSeasonRef
import com.arflix.tv.data.api.MdbWatchedShowRef
import com.arflix.tv.data.api.MdbWatchlistItem
import com.arflix.tv.data.api.MdbWatchlistModifyBody
import com.arflix.tv.data.model.MediaItem
import com.arflix.tv.data.model.MediaType
import com.arflix.tv.data.repository.sync.RemoteWatchlistResult
import com.arflix.tv.data.repository.sync.SyncProviderStore
import com.arflix.tv.util.AppLogger
import com.arflix.tv.util.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * MDBList data layer. Mirrors the subset of TraktRepository behavior the app
 * needs when a profile uses MDBList instead of Trakt: watchlist, scrobble,
 * watched reads, and Continue Watching (paused sessions).
 *
 * Supabase stays the source of truth for watched state; this repository only
 * talks to the MDBList remote. API contract is verified live — see the
 * project_mdblist_api memory.
 */
/**
 * A show the user is part-way through on MDBList: which episodes are watched (by season) and
 * when they last watched. The next unwatched episode is resolved against TMDB by the caller.
 */
data class MdbShowWatchedProgress(
    val showTmdbId: Int,
    val title: String,
    val year: String,
    val watchedBySeason: Map<Int, Set<Int>>,
    val lastWatchedAtMs: Long
)

@Singleton
class MdbListRepository @Inject constructor(
    private val api: MdbListApi,
    private val store: SyncProviderStore
) {
    private val TAG = "MdbListRepository"

    private suspend fun key(): String? = store.getMdbListApiKey()

    /** True when a non-empty key is stored AND it validates against /user. */
    suspend fun validateKey(apiKey: String): Boolean = withContext(Dispatchers.IO) {
        try {
            !api.getUser(apiKey.trim()).username.isNullOrBlank()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            false
        }
    }

    suspend fun isConnected(): Boolean = key() != null

    // ===== Watchlist =====

    suspend fun addToWatchlist(mediaType: MediaType, tmdbId: Int): Boolean =
        modifyWatchlist(mediaType, tmdbId, "add")

    suspend fun removeFromWatchlist(mediaType: MediaType, tmdbId: Int): Boolean =
        modifyWatchlist(mediaType, tmdbId, "remove")

    private suspend fun modifyWatchlist(
        mediaType: MediaType,
        tmdbId: Int,
        action: String
    ): Boolean = withContext(Dispatchers.IO) {
        val k = key() ?: return@withContext false
        try {
            val body = if (mediaType == MediaType.MOVIE) {
                MdbWatchlistModifyBody(movies = listOf(MdbTmdbRef(tmdbId)))
            } else {
                MdbWatchlistModifyBody(shows = listOf(MdbTmdbRef(tmdbId)))
            }
            api.modifyWatchlist(action, k, body)
            true
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            AppLogger.e(TAG, "watchlist $action failed", e)
            false
        }
    }

    suspend fun getWatchlist(): RemoteWatchlistResult = withContext(Dispatchers.IO) {
        val k = key() ?: return@withContext RemoteWatchlistResult(connected = false, items = null, rawCount = 0)
        try {
            val raw = fetchAllWatchlistItems(k)
            val items = raw
                .mapIndexedNotNull { index, item -> mapWatchlistItem(item, index) }
                .sortedWith(compareBy<MediaItem> { it.sourceOrder }.thenByDescending { it.addedAt })
            RemoteWatchlistResult(connected = true, items = items, rawCount = raw.size)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            AppLogger.e(TAG, "watchlist fetch failed", e)
            RemoteWatchlistResult(connected = true, items = null, rawCount = 0)
        }
    }

    private suspend fun fetchAllWatchlistItems(apiKey: String): List<MdbWatchlistItem> {
        val all = mutableListOf<MdbWatchlistItem>()
        val limit = 1000
        var offset = 0
        while (true) {
            val page = api.getWatchlistItems(apiKey, limit = limit, offset = offset, unified = "true")
            all.addAll(page)
            if (page.size < limit) break
            offset += limit
        }
        return all
    }

    private fun mapWatchlistItem(item: MdbWatchlistItem, sourceOrder: Int): MediaItem? {
        val tmdbId = item.ids?.tmdb ?: item.id ?: return null
        val type = if (item.mediatype.equals("show", ignoreCase = true)) MediaType.TV else MediaType.MOVIE
        val year = item.releaseYear?.toString()
            ?: item.releaseDate?.take(4).orEmpty()
        // No poster in the watchlist payload — WatchlistRepository enriches via TMDB,
        // mirroring the Trakt path (mapWatchlistItemFast).
        return MediaItem(
            id = tmdbId,
            title = item.title.orEmpty(),
            mediaType = type,
            year = year,
            releaseDate = item.releaseDate,
            addedAt = parseIsoMillis(item.watchlistAt),
            sourceOrder = sourceOrder
        )
    }

    // ===== Scrobble =====

    suspend fun scrobble(
        action: String, // start | pause | stop
        mediaType: MediaType,
        tmdbId: Int,
        progress: Float,
        season: Int?,
        episode: Int?
    ) = withContext(Dispatchers.IO) {
        val k = key() ?: return@withContext
        val prog = progress.roundToInt().coerceIn(0, 100)
        val body = if (mediaType == MediaType.MOVIE) {
            MdbScrobbleBody(progress = prog, movie = MdbScrobbleMovie(MdbIds(tmdb = tmdbId)))
        } else {
            if (season == null || episode == null) return@withContext
            MdbScrobbleBody(
                progress = prog,
                show = MdbScrobbleShow(
                    ids = MdbIds(tmdb = tmdbId),
                    season = MdbScrobbleSeason(number = season, episode = MdbScrobbleEpisodeNumber(episode))
                )
            )
        }
        try {
            api.scrobble(action, k, body)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            AppLogger.e(TAG, "scrobble $action failed", e)
        }
    }

    /** Clear a paused session so it drops out of Continue Watching. */
    suspend fun clearPlayback(mediaType: MediaType, tmdbId: Int, season: Int?, episode: Int?) =
        withContext(Dispatchers.IO) {
            val k = key() ?: return@withContext
            val body = if (mediaType == MediaType.MOVIE) {
                MdbScrobbleClearBody(movie = MdbScrobbleMovie(MdbIds(tmdb = tmdbId)))
            } else {
                if (season == null || episode == null) return@withContext
                MdbScrobbleClearBody(
                    show = MdbScrobbleShow(
                        ids = MdbIds(tmdb = tmdbId),
                        season = MdbScrobbleSeason(number = season, episode = MdbScrobbleEpisodeNumber(episode))
                    )
                )
            }
            try {
                api.scrobbleClear(k, body)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                AppLogger.e(TAG, "scrobble clear failed", e)
            }
        }

    // ===== Watched mirror (Trakt-style ids objects) =====

    suspend fun markMovieWatched(tmdbId: Int): Boolean = watchedCall {
        api.addWatched(it, MdbWatchedBody(movies = listOf(MdbIdsItem(MdbIds(tmdb = tmdbId)))))
    }

    suspend fun markMovieUnwatched(tmdbId: Int): Boolean = watchedCall {
        api.removeWatched(it, MdbWatchedBody(movies = listOf(MdbIdsItem(MdbIds(tmdb = tmdbId)))))
    }

    suspend fun markEpisodeWatched(showTmdbId: Int, season: Int, episode: Int): Boolean = watchedCall {
        api.addWatched(it, episodeBody(showTmdbId, season, episode))
    }

    suspend fun markEpisodeUnwatched(showTmdbId: Int, season: Int, episode: Int): Boolean = watchedCall {
        api.removeWatched(it, episodeBody(showTmdbId, season, episode))
    }

    /** Batch-mark a whole season's episodes watched in one /sync/watched call. */
    suspend fun markSeasonWatched(showTmdbId: Int, season: Int, episodes: List<Int>): Boolean = watchedCall {
        api.addWatched(
            it,
            MdbWatchedBody(
                shows = listOf(
                    MdbWatchedShowRef(
                        ids = MdbIds(tmdb = showTmdbId),
                        seasons = listOf(
                            MdbWatchedSeasonRef(number = season, episodes = episodes.map { n -> MdbWatchedEpisodeRef(n) })
                        )
                    )
                )
            )
        )
    }

    private fun episodeBody(showTmdbId: Int, season: Int, episode: Int) = MdbWatchedBody(
        shows = listOf(
            MdbWatchedShowRef(
                ids = MdbIds(tmdb = showTmdbId),
                seasons = listOf(
                    MdbWatchedSeasonRef(number = season, episodes = listOf(MdbWatchedEpisodeRef(episode)))
                )
            )
        )
    )

    private suspend fun watchedCall(block: suspend (String) -> Any): Boolean = withContext(Dispatchers.IO) {
        val k = key() ?: return@withContext false
        try {
            block(k)
            true
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            AppLogger.e(TAG, "watched mirror failed", e)
            false
        }
    }

    // ===== Watched reads =====

    suspend fun getWatchedMovies(): Set<Int> = withContext(Dispatchers.IO) {
        val k = key() ?: return@withContext emptySet()
        try {
            val out = mutableSetOf<Int>()
            var offset = 0
            val limit = 1000
            while (true) {
                val resp = api.getWatched(k, limit = limit, offset = offset)
                resp.movies?.forEach { row -> row.movie?.ids?.tmdb?.let { out.add(it) } }
                if (resp.pagination?.hasMore != true) break
                offset += limit
            }
            out
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            emptySet()
        }
    }

    /**
     * Per-show watched progress from /sync/watched, grouped by show tmdb id. Powers MDBList's
     * "Now Playing" (up-next) — shows with episodes watched but not finished. Unlike Trakt,
     * MDBList has no server-side "next episode" endpoint, so the caller resolves the next
     * episode from this watched set + TMDB.
     */
    suspend fun getWatchedShowsProgress(): List<MdbShowWatchedProgress> = withContext(Dispatchers.IO) {
        val k = key() ?: return@withContext emptyList()
        try {
            class Acc(var title: String, var year: String) {
                val eps = mutableMapOf<Int, MutableSet<Int>>()
                var lastMs = 0L
            }
            val byShow = mutableMapOf<Int, Acc>()
            var offset = 0
            val limit = 1000
            while (true) {
                val resp = api.getWatched(k, limit = limit, offset = offset)
                resp.episodes?.forEach { row ->
                    val ep = row.episode ?: return@forEach
                    val showTmdb = ep.show?.ids?.tmdb ?: return@forEach
                    val s = ep.season ?: return@forEach
                    val e = ep.number ?: return@forEach
                    val acc = byShow.getOrPut(showTmdb) {
                        Acc(ep.show?.title.orEmpty(), ep.show?.year?.toString().orEmpty())
                    }
                    if (acc.title.isBlank()) ep.show?.title?.let { acc.title = it }
                    acc.eps.getOrPut(s) { mutableSetOf() }.add(e)
                    val ts = parseIsoMillis(row.lastWatchedAt)
                    if (ts > acc.lastMs) acc.lastMs = ts
                }
                if (resp.pagination?.hasMore != true) break
                offset += limit
            }
            byShow.map { (id, acc) ->
                MdbShowWatchedProgress(
                    showTmdbId = id,
                    title = acc.title,
                    year = acc.year,
                    watchedBySeason = acc.eps.mapValues { it.value.toSet() },
                    lastWatchedAtMs = acc.lastMs
                )
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            AppLogger.e(TAG, "watched shows progress fetch failed", e)
            emptyList()
        }
    }

    suspend fun getWatchedEpisodes(): Set<String> = withContext(Dispatchers.IO) {
        val k = key() ?: return@withContext emptySet()
        try {
            val out = mutableSetOf<String>()
            var offset = 0
            val limit = 1000
            while (true) {
                val resp = api.getWatched(k, limit = limit, offset = offset)
                resp.episodes?.forEach { row ->
                    val ep = row.episode ?: return@forEach
                    val showTmdb = ep.show?.ids?.tmdb ?: return@forEach
                    val s = ep.season ?: return@forEach
                    val e = ep.number ?: return@forEach
                    out.add("show_tmdb:$showTmdb:$s:$e")
                }
                if (resp.pagination?.hasMore != true) break
                offset += limit
            }
            out
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            emptySet()
        }
    }

    // ===== Continue Watching (paused sessions) =====

    suspend fun getContinueWatching(): List<ContinueWatchingItem> = withContext(Dispatchers.IO) {
        val k = key() ?: return@withContext emptyList()
        try {
            api.getPlayback(k)
                .mapNotNull { mapPlaybackItem(it) }
                .sortedByDescending { it.updatedAtMs }
                .take(Constants.MAX_CONTINUE_WATCHING)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            AppLogger.e(TAG, "continue watching fetch failed", e)
            emptyList()
        }
    }

    private fun mapPlaybackItem(item: MdbPlaybackItem): ContinueWatchingItem? {
        val progress = item.progress?.toFloatOrNull()?.roundToInt() ?: return null
        // Same window Trakt uses: skip barely-started and effectively-finished items.
        if (progress < Constants.MIN_PROGRESS_THRESHOLD || progress >= Constants.WATCHED_THRESHOLD) return null
        val durationSeconds = (item.runtime ?: 0).toLong() * 60L
        val updatedMs = item.updatedAtTs?.let { it * 1000L } ?: parseIsoMillis(item.updatedAt)

        return if (item.type == "movie") {
            val movie = item.movie ?: return null
            val tmdbId = movie.ids?.tmdb ?: return null
            ContinueWatchingItem(
                id = tmdbId,
                title = movie.title.orEmpty(),
                mediaType = MediaType.MOVIE,
                progress = progress.coerceIn(0, 100),
                durationSeconds = durationSeconds,
                year = movie.year?.toString().orEmpty(),
                updatedAtMs = updatedMs
            )
        } else {
            val show = item.show ?: return null
            val ep = item.episode ?: return null
            val tmdbId = show.ids?.tmdb ?: return null
            val season = ep.season ?: return null
            val number = ep.number ?: return null
            ContinueWatchingItem(
                id = tmdbId,
                title = show.title.orEmpty(),
                mediaType = MediaType.TV,
                progress = progress.coerceIn(0, 100),
                durationSeconds = durationSeconds,
                season = season,
                episode = number,
                episodeTitle = ep.name,
                year = show.year?.toString().orEmpty(),
                updatedAtMs = updatedMs
            )
        }
    }

    private fun parseIsoMillis(iso: String?): Long {
        if (iso.isNullOrBlank()) return 0L
        return try {
            java.time.Instant.parse(iso).toEpochMilli()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            0L
        }
    }
}
