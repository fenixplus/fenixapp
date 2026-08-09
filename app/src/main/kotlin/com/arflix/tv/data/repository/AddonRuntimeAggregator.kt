package com.arflix.tv.data.repository

import com.arflix.tv.data.model.Addon
import com.arflix.tv.data.model.RuntimeKind
import com.arflix.tv.data.model.StreamSource

class AddonRuntimeAggregator(
    private val addonRuntimes: Map<RuntimeKind, AddonRuntime>
) {
    suspend fun resolveMovieStreams(
        stremioAddons: List<Addon>,
        request: MovieRuntimeRequest
    ): List<StreamSource> {
        if (stremioAddons.isEmpty()) return emptyList()
        return addonRuntimes[RuntimeKind.STREMIO]
            ?.resolveMovieStreams(stremioAddons, request)
            .orEmpty()
    }

    suspend fun resolveEpisodeStreams(
        stremioAddons: List<Addon>,
        request: EpisodeRuntimeRequest
    ): List<StreamSource> {
        if (stremioAddons.isEmpty()) return emptyList()
        return addonRuntimes[RuntimeKind.STREMIO]
            ?.resolveEpisodeStreams(stremioAddons, request)
            .orEmpty()
    }
}
