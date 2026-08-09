package com.arflix.tv.data.api

import com.google.gson.annotations.SerializedName
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * MDBList API (https://api.mdblist.com). Per-profile alternative to Trakt.
 *
 * Auth is a static user API key passed as the `apikey` query param on every
 * call. Shapes below are verified against the live API (see the
 * project_mdblist_api memory). Progress is 0-100 and comes back as a String
 * ("30.00") — parse tolerantly.
 */
interface MdbListApi {

    @GET("user")
    suspend fun getUser(@Query("apikey") apiKey: String): MdbUser

    @GET("sync/last_activities")
    suspend fun getLastActivities(@Query("apikey") apiKey: String): MdbLastActivities

    // ===== Watchlist =====

    /** Unified flat array of movies + shows. */
    @GET("watchlist/items")
    suspend fun getWatchlistItems(
        @Query("apikey") apiKey: String,
        @Query("limit") limit: Int = 1000,
        @Query("offset") offset: Int = 0,
        @Query("unified") unified: String = "true"
    ): List<MdbWatchlistItem>

    /** action = "add" | "remove". Body uses flat tmdb refs. */
    @POST("watchlist/items/{action}")
    @Headers("Content-Type: application/json")
    suspend fun modifyWatchlist(
        @Path("action") action: String,
        @Query("apikey") apiKey: String,
        @Body body: MdbWatchlistModifyBody
    ): MdbCountResponse

    // ===== Watched (Trakt-style ids objects) =====

    @GET("sync/watched")
    suspend fun getWatched(
        @Query("apikey") apiKey: String,
        @Query("limit") limit: Int = 1000,
        @Query("offset") offset: Int = 0
    ): MdbWatchedResponse

    @POST("sync/watched")
    @Headers("Content-Type: application/json")
    suspend fun addWatched(
        @Query("apikey") apiKey: String,
        @Body body: MdbWatchedBody
    ): MdbCountResponse

    @POST("sync/watched/remove")
    @Headers("Content-Type: application/json")
    suspend fun removeWatched(
        @Query("apikey") apiKey: String,
        @Body body: MdbWatchedBody
    ): MdbCountResponse

    // ===== Scrobble / Continue Watching =====

    /** Paused sessions that power Continue Watching. */
    @GET("sync/playback")
    suspend fun getPlayback(@Query("apikey") apiKey: String): List<MdbPlaybackItem>

    /** action = "start" | "pause" | "stop". */
    @POST("scrobble/{action}")
    @Headers("Content-Type: application/json")
    suspend fun scrobble(
        @Path("action") action: String,
        @Query("apikey") apiKey: String,
        @Body body: MdbScrobbleBody
    ): MdbScrobbleResponse

    /** Clear a paused session (by playback id or by media ids). */
    @POST("scrobble/clear")
    @Headers("Content-Type: application/json")
    suspend fun scrobbleClear(
        @Query("apikey") apiKey: String,
        @Body body: MdbScrobbleClearBody
    ): MdbScrobbleClearResponse
}

// ========== Shared ids ==========

data class MdbIds(
    val tmdb: Int? = null,
    val imdb: String? = null,
    val trakt: Int? = null,
    val tvdb: Int? = null,
    val mdblist: String? = null
)

// ========== User / activities ==========

data class MdbUser(
    val username: String? = null,
    @SerializedName("user_id") val userId: Long? = null,
    val name: String? = null
)

data class MdbLastActivities(
    @SerializedName("watchlisted_at") val watchlistedAt: String? = null,
    @SerializedName("watched_at") val watchedAt: String? = null,
    @SerializedName("season_watched_at") val seasonWatchedAt: String? = null,
    @SerializedName("episode_watched_at") val episodeWatchedAt: String? = null,
    @SerializedName("paused_at") val pausedAt: String? = null,
    @SerializedName("episode_paused_at") val episodePausedAt: String? = null,
    @SerializedName("rated_at") val ratedAt: String? = null
)

// ========== Watchlist ==========

data class MdbWatchlistItem(
    val id: Int? = null,
    val mediatype: String? = null, // "movie" | "show"
    val ids: MdbIds? = null,
    val title: String? = null,
    @SerializedName("release_year") val releaseYear: Int? = null,
    @SerializedName("release_date") val releaseDate: String? = null,
    @SerializedName("watchlist_at") val watchlistAt: String? = null
)

data class MdbWatchlistModifyBody(
    val movies: List<MdbTmdbRef>? = null,
    val shows: List<MdbTmdbRef>? = null
)

data class MdbTmdbRef(val tmdb: Int)

// ========== Watched ==========

data class MdbWatchedBody(
    val movies: List<MdbIdsItem>? = null,
    val shows: List<MdbWatchedShowRef>? = null
)

data class MdbIdsItem(val ids: MdbIds)

data class MdbWatchedShowRef(
    val ids: MdbIds,
    val seasons: List<MdbWatchedSeasonRef>? = null
)

data class MdbWatchedSeasonRef(
    val number: Int,
    val episodes: List<MdbWatchedEpisodeRef>? = null
)

data class MdbWatchedEpisodeRef(val number: Int)

data class MdbWatchedResponse(
    val movies: List<MdbWatchedMovieRow>? = null,
    val episodes: List<MdbWatchedEpisodeRow>? = null,
    val pagination: MdbPagination? = null
)

data class MdbWatchedMovieRow(
    @SerializedName("last_watched_at") val lastWatchedAt: String? = null,
    val movie: MdbMovieInfo? = null
)

data class MdbWatchedEpisodeRow(
    @SerializedName("last_watched_at") val lastWatchedAt: String? = null,
    val episode: MdbEpisodeInfo? = null
)

data class MdbPagination(
    val offset: Int = 0,
    val limit: Int = 0,
    @SerializedName("has_more") val hasMore: Boolean = false
)

// ========== Playback / scrobble ==========

data class MdbMovieInfo(
    val title: String? = null,
    val year: Int? = null,
    val ids: MdbIds? = null
)

data class MdbShowInfo(
    val title: String? = null,
    val year: Int? = null,
    val ids: MdbIds? = null
)

data class MdbEpisodeInfo(
    val season: Int? = null,
    val number: Int? = null,
    val name: String? = null,
    val ids: MdbIds? = null,
    val show: MdbShowInfo? = null
)

data class MdbPlaybackItem(
    val id: Long? = null,
    val progress: String? = null, // "30.00"
    @SerializedName("updated_at") val updatedAt: String? = null,
    @SerializedName("updated_at_ts") val updatedAtTs: Long? = null,
    @SerializedName("paused_at") val pausedAt: String? = null,
    val runtime: Int? = null, // minutes
    val type: String? = null, // "movie" | "episode"
    val movie: MdbMovieInfo? = null,
    val show: MdbShowInfo? = null,
    val episode: MdbEpisodeInfo? = null
)

data class MdbScrobbleBody(
    val progress: Int,
    val movie: MdbScrobbleMovie? = null,
    val show: MdbScrobbleShow? = null
)

data class MdbScrobbleMovie(val ids: MdbIds)

data class MdbScrobbleShow(
    val ids: MdbIds,
    val season: MdbScrobbleSeason
)

data class MdbScrobbleSeason(
    val number: Int,
    val episode: MdbScrobbleEpisodeNumber
)

data class MdbScrobbleEpisodeNumber(val number: Int)

data class MdbScrobbleResponse(
    val action: String? = null,
    val progress: String? = null
)

data class MdbScrobbleClearBody(
    val id: Long? = null,
    val movie: MdbScrobbleMovie? = null,
    val show: MdbScrobbleShow? = null
)

data class MdbScrobbleClearResponse(
    val action: String? = null,
    val deleted: Boolean? = null
)

// ========== Generic count response ==========

data class MdbCountResponse(
    val added: MdbCounts? = null,
    val removed: MdbCounts? = null,
    val updated: MdbCounts? = null,
    val existing: MdbCounts? = null
)

data class MdbCounts(
    val movies: Int = 0,
    val shows: Int = 0,
    val seasons: Int = 0,
    val episodes: Int = 0
)
