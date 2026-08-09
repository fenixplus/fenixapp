package com.arflix.tv.core.tmdb

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TmdbService @Inject constructor() {
    suspend fun tmdbToImdb(tmdbId: Int, mediaType: String): String? {
        return null
    }
}
