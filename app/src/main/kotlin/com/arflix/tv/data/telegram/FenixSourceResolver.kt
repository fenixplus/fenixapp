package com.arflix.tv.data.telegram

import com.arflix.tv.data.model.StreamSource
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FenixSourceResolver @Inject constructor(
    private val fenixRepository: FenixRepository
) {
    suspend fun resolveMovie(tmdbId: Int): List<StreamSource> {
        return fenixRepository.resolveMovieStreams(tmdbId)
    }

    suspend fun resolveEpisode(seriesId: Int, season: Int, episode: Int): List<StreamSource> {
        return fenixRepository.resolveEpisodeStreams(seriesId, season, episode)
    }
}
