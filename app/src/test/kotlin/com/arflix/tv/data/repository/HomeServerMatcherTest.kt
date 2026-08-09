package com.arflix.tv.data.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeServerMatcherTest {
    @Test
    fun `external ids beat older title-only remakes`() {
        val correct = HomeServerCandidateInfo(
            title = "Suits",
            productionYear = 2011,
            providerIds = mapOf("imdb" to "tt1632701", "tmdb" to "37680")
        )
        val wrong = HomeServerCandidateInfo(
            title = "Suits",
            productionYear = 1990,
            providerIds = emptyMap()
        )

        val correctScore = HomeServerMatcher.score(
            requestedTitle = "Suits",
            requestedYear = 2011,
            imdbId = "tt1632701",
            tmdbId = 37680,
            tvdbId = null,
            candidate = correct
        )
        val wrongScore = HomeServerMatcher.score(
            requestedTitle = "Suits",
            requestedYear = 2011,
            imdbId = "tt1632701",
            tmdbId = 37680,
            tvdbId = null,
            candidate = wrong
        )

        assertTrue(correctScore > wrongScore)
        assertTrue(HomeServerMatcher.isAcceptable(correctScore))
        assertFalse(HomeServerMatcher.isAcceptable(wrongScore))
    }

    @Test
    fun `title and year fallback allows local items without ids`() {
        val candidate = HomeServerCandidateInfo(
            title = "The Pitt",
            productionYear = 2025,
            providerIds = emptyMap()
        )

        val score = HomeServerMatcher.score(
            requestedTitle = "The Pitt",
            requestedYear = 2025,
            imdbId = null,
            tmdbId = null,
            tvdbId = null,
            candidate = candidate
        )

        assertTrue(HomeServerMatcher.isAcceptable(score))
    }
}
