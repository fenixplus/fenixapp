package com.arflix.tv.data.repository.sync

import com.arflix.tv.data.model.MediaType
import com.arflix.tv.data.repository.ContinueWatchingItem
import com.arflix.tv.data.repository.TraktRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Trakt implementation of [RemoteSyncProvider]. A thin adapter over the existing
 * [TraktRepository] so Trakt behavior is byte-for-byte unchanged when a profile
 * is routed through the provider seam.
 */
@Singleton
class TraktRemoteProvider @Inject constructor(
    private val traktRepository: TraktRepository
) : RemoteSyncProvider {

    override val provider: SyncProvider = SyncProvider.TRAKT

    override suspend fun isConnected(): Boolean = traktRepository.hasTrakt()

    override suspend fun addToWatchlist(mediaType: MediaType, tmdbId: Int): Boolean =
        traktRepository.addToWatchlist(mediaType, tmdbId)

    override suspend fun removeFromWatchlist(mediaType: MediaType, tmdbId: Int): Boolean =
        traktRepository.removeFromWatchlist(mediaType, tmdbId)

    override suspend fun getWatchlist(): RemoteWatchlistResult {
        val (connected, result) = traktRepository.getWatchlistSyncResultWithAuthState()
        return RemoteWatchlistResult(
            connected = connected,
            items = result?.items,
            rawCount = result?.rawCount ?: 0
        )
    }

    override suspend fun scrobbleStart(
        mediaType: MediaType,
        tmdbId: Int,
        progress: Float,
        season: Int?,
        episode: Int?
    ) {
        traktRepository.scrobbleStart(mediaType, tmdbId, progress, season, episode)
    }

    override suspend fun scrobblePause(
        mediaType: MediaType,
        tmdbId: Int,
        progress: Float,
        season: Int?,
        episode: Int?
    ) {
        // Player uses the immediate (non-debounced) pause; preserve that.
        traktRepository.scrobblePauseImmediate(mediaType, tmdbId, progress, season, episode)
    }

    override suspend fun scrobbleStop(
        mediaType: MediaType,
        tmdbId: Int,
        progress: Float,
        season: Int?,
        episode: Int?
    ) {
        traktRepository.scrobbleStop(mediaType, tmdbId, progress, season, episode)
    }

    override suspend fun getWatchedMovies(): Set<Int> = traktRepository.getWatchedMovies()

    override suspend fun getWatchedEpisodes(): Set<String> = traktRepository.getWatchedEpisodes()

    override suspend fun getContinueWatching(forceRefresh: Boolean): List<ContinueWatchingItem> =
        traktRepository.getContinueWatching(forceRefresh)
}
