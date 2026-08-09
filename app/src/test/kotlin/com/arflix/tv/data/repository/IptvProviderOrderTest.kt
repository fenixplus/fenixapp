package com.arflix.tv.data.repository

import com.arflix.tv.data.model.IptvChannel
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class IptvProviderOrderTest {

    @Test
    fun xtreamCategoryEndpointDefinesGroupOrder() {
        val categorizedChannels = listOf(
            "sports" to apiChannel(301, "Sports One", "Sports"),
            "kids" to apiChannel(201, "Kids One", "Kids"),
            "entertainment" to apiChannel(101, "Entertainment One", "Entertainment"),
            "sports" to apiChannel(302, "Sports Two", "Sports", catchupDays = 7),
            "entertainment" to apiChannel(102, "Entertainment Two", "Entertainment"),
        )

        val ordered = orderXtreamChannelsByProviderCategories(
            categoryIdsInProviderOrder = listOf("entertainment", "kids", "sports"),
            categorizedChannels = categorizedChannels,
        )

        assertThat(ordered.map { it.id })
            .containsExactly("xtream:101", "xtream:102", "xtream:201", "xtream:301", "xtream:302")
            .inOrder()
        assertThat(ordered.map { it.group })
            .containsExactly("Entertainment", "Entertainment", "Kids", "Sports", "Sports")
            .inOrder()
        assertThat(ordered.last().catchupDays).isEqualTo(7)
    }

    @Test
    fun channelsWithinCategoryKeepProviderStreamSequence() {
        val categorizedChannels = listOf(
            "sports" to apiChannel(30, "Provider Thirty", "Sports"),
            "news" to apiChannel(20, "Provider Twenty", "News"),
            "sports" to apiChannel(10, "Provider Ten", "Sports"),
        )

        val ordered = orderXtreamChannelsByProviderCategories(
            categoryIdsInProviderOrder = listOf("sports", "news"),
            categorizedChannels = categorizedChannels,
        )

        assertThat(ordered.map { it.id }).containsExactly("xtream:30", "xtream:10", "xtream:20").inOrder()
    }

    @Test
    fun unknownCategoriesAppendAfterKnownCategoriesInFirstSeenOrder() {
        val categorizedChannels = listOf(
            "unknown-b" to apiChannel(401, "Unknown B One", "Unknown B"),
            "known" to apiChannel(101, "Known", "Known"),
            "unknown-a" to apiChannel(301, "Unknown A", "Unknown A"),
            "unknown-b" to apiChannel(402, "Unknown B Two", "Unknown B"),
        )

        val ordered = orderXtreamChannelsByProviderCategories(
            categoryIdsInProviderOrder = listOf("known"),
            categorizedChannels = categorizedChannels,
        )

        assertThat(ordered.map { it.id })
            .containsExactly("xtream:101", "xtream:401", "xtream:402", "xtream:301")
            .inOrder()
    }

    @Test
    fun replacingOrRemovingPlaylistDropsOnlyItsSavedGroupOrder() {
        val previous = listOf(
            playlist("one", "https://old.example/one.m3u"),
            playlist("two", "https://same.example/two.m3u"),
        )
        val current = listOf(
            playlist("one", "https://new.example/one.m3u"),
            playlist("two", "https://same.example/two.m3u"),
        )

        val changed = changedPlaylistSourceIds(previous, current)
        val retained = retainGroupOrderForUnchangedSources(
            savedOrder = listOf("one|Sports", "two|News", "one|Movies"),
            changedPlaylistIds = changed,
        )

        assertThat(changed).containsExactly("one")
        assertThat(retained).containsExactly("two|News")
    }

    @Test
    fun readdingPlaylistIdAfterEmptyStateDropsStaleSavedOrder() {
        val current = listOf(playlist("one", "https://new.example/one.m3u"))

        val changed = changedPlaylistSourceIds(emptyList(), current)
        val retained = retainGroupOrderForUnchangedSources(
            savedOrder = listOf("one|Movies", "one|Sports"),
            changedPlaylistIds = changed,
        )

        assertThat(changed).containsExactly("one")
        assertThat(retained).isEmpty()
    }

    private fun apiChannel(
        streamId: Int,
        name: String,
        group: String,
        catchupDays: Int = 0,
    ): IptvChannel = IptvChannel(
        id = "xtream:$streamId",
        name = name,
        streamUrl = "https://provider.test/live/user/pass/$streamId.ts",
        group = group,
        xtreamStreamId = streamId,
        catchupDays = catchupDays,
        catchupType = if (catchupDays > 0) "xtream" else null,
    )

    private fun playlist(id: String, url: String): IptvPlaylistEntry = IptvPlaylistEntry(
        id = id,
        name = id,
        m3uUrl = url,
    )
}
