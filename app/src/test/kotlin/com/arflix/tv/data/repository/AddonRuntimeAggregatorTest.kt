package com.arflix.tv.data.repository

import com.arflix.tv.data.model.Addon
import com.arflix.tv.data.model.AddonType
import com.arflix.tv.data.model.RuntimeKind
import com.arflix.tv.data.model.StreamSource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class AddonRuntimeAggregatorTest {
    @Test
    fun `resolveMovieStreams uses installed addon runtime output`() = runTest {
        val aggregator = AddonRuntimeAggregator(
            addonRuntimes = mapOf(
                RuntimeKind.STREMIO to FakeRuntime(
                    movieStreams = listOf(testStream("stremio.stream", "stremio.addon"))
                )
            )
        )

        val streams = aggregator.resolveMovieStreams(
            stremioAddons = listOf(testAddon("stremio.addon", RuntimeKind.STREMIO)),
            request = MovieRuntimeRequest(
                imdbId = "tt1234567",
                title = "Movie",
                year = 2024
            )
        )

        assertEquals(1, streams.size)
        assertEquals(listOf("stremio.stream"), streams.map { it.source })
    }

    private class FakeRuntime(
        private val movieStreams: List<StreamSource> = emptyList(),
        private val episodeStreams: List<StreamSource> = emptyList()
    ) : AddonRuntime {
        override val kind: RuntimeKind = RuntimeKind.STREMIO

        override suspend fun resolveMovieStreams(
            addons: List<Addon>,
            request: MovieRuntimeRequest
        ): List<StreamSource> = movieStreams

        override suspend fun resolveEpisodeStreams(
            addons: List<Addon>,
            request: EpisodeRuntimeRequest
        ): List<StreamSource> = episodeStreams
    }

    private fun testAddon(id: String, runtimeKind: RuntimeKind): Addon {
        return Addon(
            id = id,
            name = id,
            version = "1.0.0",
            description = "",
            isInstalled = true,
            type = AddonType.CUSTOM,
            runtimeKind = runtimeKind
        )
    }

    private fun testStream(source: String, addonId: String): StreamSource {
        return StreamSource(
            source = source,
            addonName = addonId,
            addonId = addonId,
            quality = "1080p",
            size = "1 GB",
            url = "https://example.com/$source.m3u8"
        )
    }
}
