package com.arflix.tv.util

import com.arflix.tv.data.api.ArmMappingEntry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class AnimeMapperArmSeasonTest {
    @Test
    fun `arm season ids exclude specials and ova from requested tmdb season`() {
        val entries = listOf(
            ArmMappingEntry(kitsu = 41024, themoviedbSeason = 1, media = "TV"),
            ArmMappingEntry(kitsu = 42022, themoviedbSeason = 0, media = "OVA"),
            ArmMappingEntry(kitsu = 42196, themoviedbSeason = 2, media = "TV"),
            ArmMappingEntry(kitsu = 43361, themoviedbSeason = 2, media = "TV"),
            ArmMappingEntry(kitsu = 46729, themoviedbSeason = 3, media = "TV"),
            ArmMappingEntry(kitsu = 47088, themoviedbSeason = 0, media = "ONA"),
            ArmMappingEntry(kitsu = 49235, themoviedbSeason = 4, media = "TV")
        )

        assertEquals(listOf(42196, 43361), armSeasonKitsuIds(entries, season = 2))
        assertEquals(listOf(49235), armSeasonKitsuIds(entries, season = 4))
    }

    @Test
    fun `arm season ids fall back to legacy unknown season entries`() {
        val entries = listOf(
            ArmMappingEntry(kitsu = 100),
            ArmMappingEntry(kitsu = 200),
            ArmMappingEntry(kitsu = 200)
        )

        assertEquals(listOf(100, 200), armSeasonKitsuIds(entries, season = 2))
    }

    @Test
    fun `explicit arm season mappings walk cours instead of applying season index again`() = runBlocking {
        val entries = listOf(
            ArmMappingEntry(kitsu = 41024, themoviedbSeason = 1),
            ArmMappingEntry(kitsu = 42022, themoviedbSeason = 0),
            ArmMappingEntry(kitsu = 42196, themoviedbSeason = 2),
            ArmMappingEntry(kitsu = 43361, themoviedbSeason = 2),
            ArmMappingEntry(kitsu = 46729, themoviedbSeason = 3),
            ArmMappingEntry(kitsu = 49235, themoviedbSeason = 4)
        )

        val season2Query = armEpisodeQueryFromSeasonCandidates(
            candidates = armSeasonKitsuCandidates(entries, season = 2),
            season = 2,
            episode = 21,
            episodeCountProvider = { id -> mapOf(42196 to 12, 43361 to 12)[id] }
        )
        val season4Query = armEpisodeQueryFromSeasonCandidates(
            candidates = armSeasonKitsuCandidates(entries, season = 4),
            season = 4,
            episode = 9,
            episodeCountProvider = { id -> mapOf(49235 to 12)[id] }
        )

        assertEquals("kitsu:43361:9", season2Query)
        assertEquals("kitsu:49235:9", season4Query)
    }
}
