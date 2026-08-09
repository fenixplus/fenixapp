package com.arflix.tv.core.tmdb

import com.arflix.tv.domain.model.ContentType
import javax.inject.Inject
import javax.inject.Singleton

data class TmdbEnrichment(
    val localizedTitle: String?,
    val releaseInfo: String?,
    val originalTitle: String?,
    val alternativeTitles: List<String>
)

@Singleton
class TmdbMetadataService @Inject constructor() {
    suspend fun fetchEnrichment(tmdbId: String, contentType: ContentType): TmdbEnrichment? {
        return null
    }
}
