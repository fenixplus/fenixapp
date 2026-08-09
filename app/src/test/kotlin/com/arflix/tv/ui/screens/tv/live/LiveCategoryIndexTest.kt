package com.arflix.tv.ui.screens.tv.live

import com.arflix.tv.data.model.IptvChannel
import com.arflix.tv.data.repository.IptvConfig
import com.arflix.tv.data.repository.IptvPlaylistEntry
import com.arflix.tv.data.repository.orderXtreamChannelsByProviderCategories
import com.arflix.tv.ui.screens.tv.syncSignature
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LiveCategoryIndexTest {

    @Test
    fun channelsForKeepsFavoriteOrderAndUsesStaticBuckets() {
        val channels = listOf(
            channel("1", "NL News HD", "NL | News"),
            channel("2", "US Sports 4K", "US | Sports"),
            channel("3", "Kids SD", "Kids"),
        ).mapIndexed { index, channel -> channel.enrich(index + 100) }

        val index = buildCategoryIndex(channels)

        assertThat(index.channelsFor("fav", favorites = listOf("2", "1"), recents = emptyList()).map { it.id })
            .containsExactly("2", "1")
            .inOrder()
        assertThat(index.channelsFor("g-sports", favorites = emptyList(), recents = emptyList()).map { it.id })
            .containsExactly("2")
        assertThat(index.channelsFor("NL-news", favorites = emptyList(), recents = emptyList()).map { it.id })
            .containsExactly("1")
    }

    @Test
    fun channelsForReturnsNewestRecentFirst() {
        val channels = listOf(
            channel("1", "One", "General"),
            channel("2", "Two", "General"),
            channel("3", "Three", "General"),
        ).mapIndexed { index, channel -> channel.enrich(index + 100) }
        val recents = linkedSetOf("1", "3", "2")

        val index = buildCategoryIndex(channels)

        assertThat(index.channelsFor("recent", favorites = emptyList(), recents = recents).map { it.id })
            .containsExactly("2", "3", "1")
            .inOrder()
    }

    @Test
    fun normalPagedCategoriesNeverMoveFavoritesAheadOfProviderOrder() {
        val providerWindow = listOf(
            channel("list:1", "Provider First", "News"),
            channel("list:2", "Provider Second", "News"),
            channel("list:3", "Provider Third", "News"),
        )
        val favorites = listOf(providerWindow[2])

        val allChannels = selectPagedChannelsInProviderOrder(
            categoryId = "all",
            providerWindow = providerWindow,
            favoriteChannels = favorites,
            recentChannels = emptyList(),
            limit = 100,
        )
        val providerGroup = selectPagedChannelsInProviderOrder(
            categoryId = "grp:list:news",
            providerWindow = providerWindow,
            favoriteChannels = favorites,
            recentChannels = emptyList(),
            limit = 100,
        )

        assertThat(allChannels.map { it.id }).containsExactly("list:1", "list:2", "list:3").inOrder()
        assertThat(providerGroup.map { it.id }).containsExactly("list:1", "list:2", "list:3").inOrder()
    }

    @Test
    fun favoritesCategoryStillUsesSavedFavoriteOrder() {
        val providerWindow = listOf(
            channel("list:1", "Provider First", "News"),
            channel("list:2", "Provider Second", "News"),
            channel("list:3", "Provider Third", "News"),
        )

        val result = selectPagedChannelsInProviderOrder(
            categoryId = "fav",
            providerWindow = providerWindow,
            favoriteChannels = listOf(providerWindow[2], providerWindow[0]),
            recentChannels = emptyList(),
            limit = 100,
        )

        assertThat(result.map { it.id }).containsExactly("list:3", "list:1").inOrder()
    }

    @Test
    fun pagedChannelSelectionIsDetachedFromMutableBackingList() {
        val providerWindow = MutableList(100) { index ->
            channel("list:$index", "Channel $index", "General")
        }

        val result = selectPagedChannelsInProviderOrder(
            categoryId = "all",
            providerWindow = providerWindow,
            favoriteChannels = emptyList(),
            recentChannels = emptyList(),
            limit = 48,
        )
        providerWindow.clear()

        assertThat(result).hasSize(48)
        assertThat(result.first().id).isEqualTo("list:0")
        assertThat(result.last().id).isEqualTo("list:47")
    }

    @Test
    fun categoryTreeKeepsProviderFirstOccurrenceOrder() {
        val channels = listOf(
            channel("list:9", "Nine", "Z Last alphabetically"),
            channel("list:2", "Two", "A First alphabetically"),
            channel("list:7", "Seven", "Middle"),
            channel("list:8", "Eight", "Z Last alphabetically"),
        )

        val state = buildFastStartupChannelState(
            channels = channels,
            favorites = emptySet(),
            recents = emptySet(),
        )

        assertThat(state.tree.global.categories.map { it.label })
            .containsExactly("Z Last alphabetically", "A First alphabetically", "Middle")
            .inOrder()
    }

    @Test
    fun configSignatureChangesWhenPlaylistOrderChanges() {
        val first = IptvPlaylistEntry("first", "First", "https://example.test/first.m3u")
        val second = IptvPlaylistEntry("second", "Second", "https://example.test/second.m3u")

        val original = IptvConfig(playlists = listOf(first, second)).syncSignature()
        val reordered = IptvConfig(playlists = listOf(second, first)).syncSignature()

        assertThat(reordered).isNotEqualTo(original)
    }

    @Test
    fun xtreamCategoryOrderSurvivesTvIndexing() {
        val globalStreamResponse = listOf(
            "news" to channel("xtream:20", "News Twenty", "News").copy(xtreamStreamId = 20),
            "sports" to channel("xtream:30", "Sports Thirty", "Sports").copy(xtreamStreamId = 30),
            "sports" to channel("xtream:10", "Sports Ten", "Sports").copy(xtreamStreamId = 10),
        )
        val merged = orderXtreamChannelsByProviderCategories(
            categoryIdsInProviderOrder = listOf("sports", "news"),
            categorizedChannels = globalStreamResponse,
        )
            .map { it.copy(id = "list_1:${it.id}") }

        val state = buildFastStartupChannelState(
            channels = merged,
            favorites = emptySet(),
            recents = emptySet(),
        )
        val sportsCategory = state.tree.global.categories.single { it.label == "Sports" }

        assertThat(state.all.map { it.id })
            .containsExactly("list_1:xtream:30", "list_1:xtream:10", "list_1:xtream:20")
            .inOrder()
        assertThat(state.tree.global.categories.map { it.label })
            .containsExactly("Sports", "News")
            .inOrder()
        assertThat(state.index.channelsFor(sportsCategory.id, emptyList(), emptyList()).map { it.id })
            .containsExactly("list_1:xtream:30", "list_1:xtream:10")
            .inOrder()
    }

    private fun channel(id: String, name: String, group: String): IptvChannel =
        IptvChannel(
            id = id,
            name = name,
            streamUrl = "https://example.test/$id.m3u8",
            group = group,
        )
}
