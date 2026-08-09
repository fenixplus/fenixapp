package com.arflix.tv.data.repository.sync

import com.arflix.tv.data.model.MediaItem
import com.arflix.tv.data.model.MediaType
import com.arflix.tv.data.repository.ContinueWatchingItem

/**
 * A remote sync backend (Trakt or MDBList) that a profile can connect to.
 *
 * Supabase remains the source of truth for watched state and playback progress;
 * a provider is the *remote mirror* plus the source of truth for the watchlist
 * and remote paused-playback (Continue Watching) when connected.
 *
 * Trakt profiles are served by [TraktRemoteProvider], which delegates to the
 * existing TraktRepository so behavior is unchanged. MDBList is served by
 * MdbListRemoteProvider.
 */
interface RemoteSyncProvider {

    val provider: SyncProvider

    /** True when this profile is authenticated against this provider. */
    suspend fun isConnected(): Boolean

    // ===== Watchlist (remote is source of truth while connected) =====

    suspend fun addToWatchlist(mediaType: MediaType, tmdbId: Int): Boolean

    suspend fun removeFromWatchlist(mediaType: MediaType, tmdbId: Int): Boolean

    /** Pull the remote watchlist. Returns null when the fetch could not run. */
    suspend fun getWatchlist(): RemoteWatchlistResult?

    // ===== Scrobble =====

    suspend fun scrobbleStart(
        mediaType: MediaType,
        tmdbId: Int,
        progress: Float,
        season: Int? = null,
        episode: Int? = null
    )

    suspend fun scrobblePause(
        mediaType: MediaType,
        tmdbId: Int,
        progress: Float,
        season: Int? = null,
        episode: Int? = null
    )

    suspend fun scrobbleStop(
        mediaType: MediaType,
        tmdbId: Int,
        progress: Float,
        season: Int? = null,
        episode: Int? = null
    )

    // ===== Watched reads =====

    suspend fun getWatchedMovies(): Set<Int>

    suspend fun getWatchedEpisodes(): Set<String>

    // ===== Continue Watching (remote paused sessions) =====

    suspend fun getContinueWatching(forceRefresh: Boolean = false): List<ContinueWatchingItem>
}

/**
 * Result of pulling a remote watchlist.
 * @param connected whether the provider was authenticated for the fetch
 * @param items mapped items, or null when the fetch failed
 */
data class RemoteWatchlistResult(
    val connected: Boolean,
    val items: List<MediaItem>?,
    val rawCount: Int
)
