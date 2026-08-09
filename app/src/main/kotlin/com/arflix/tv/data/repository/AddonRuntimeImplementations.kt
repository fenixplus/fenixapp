package com.arflix.tv.data.repository

import com.arflix.tv.data.model.Addon
import com.arflix.tv.data.model.RuntimeKind
import com.arflix.tv.data.model.StreamSource
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

class StremioAddonRuntime(
    private val movieResolver: suspend (Addon, MovieRuntimeRequest) -> List<StreamSource>,
    private val episodeResolver: suspend (Addon, EpisodeRuntimeRequest) -> List<StreamSource>
) : AddonRuntime {
    override val kind: RuntimeKind = RuntimeKind.STREMIO

    override suspend fun resolveMovieStreams(
        addons: List<Addon>,
        request: MovieRuntimeRequest
    ): List<StreamSource> = coroutineScope {
        addons.map { addon -> async { movieResolver(addon, request) } }
            .awaitAll()
            .flatten()
    }

    override suspend fun resolveEpisodeStreams(
        addons: List<Addon>,
        request: EpisodeRuntimeRequest
    ): List<StreamSource> = coroutineScope {
        addons.map { addon -> async { episodeResolver(addon, request) } }
            .awaitAll()
            .flatten()
    }
}
