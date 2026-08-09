package com.arflix.tv.data.repository

import com.arflix.tv.data.model.IptvChannel
import com.arflix.tv.data.model.PlaylistGroupKey

/**
 * Xtream providers expose their canonical group sequence through
 * `get_live_categories`. `get_live_streams` is frequently global-number ordered,
 * so flattening it directly makes groups appear in the wrong order. Bucket the
 * streams by category while retaining each bucket's provider response sequence.
 */
internal fun orderXtreamChannelsByProviderCategories(
    categoryIdsInProviderOrder: List<String>,
    categorizedChannels: List<Pair<String, IptvChannel>>,
): List<IptvChannel> {
    if (categorizedChannels.isEmpty()) return emptyList()

    val channelsByCategory = LinkedHashMap<String, MutableList<IptvChannel>>()
    categorizedChannels.forEach { (rawCategoryId, channel) ->
        val categoryId = rawCategoryId.trim()
        channelsByCategory.getOrPut(categoryId) { ArrayList() }.add(channel)
    }

    val ordered = ArrayList<IptvChannel>(categorizedChannels.size)
    categoryIdsInProviderOrder.asSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()
        .forEach { categoryId ->
            channelsByCategory.remove(categoryId)?.let(ordered::addAll)
        }
    channelsByCategory.values.forEach(ordered::addAll)
    return ordered
}

/**
 * A saved group order belongs to the exact playlist source it was created for.
 * Reusing a playlist id for a different URL must not carry the old custom order
 * into the replacement provider.
 */
internal fun changedPlaylistSourceIds(
    previous: List<IptvPlaylistEntry>,
    current: List<IptvPlaylistEntry>,
): Set<String> {
    val previousById = previous.associateBy { it.id.trim() }
    val currentById = current.associateBy { it.id.trim() }
    return (previousById.keys + currentById.keys).asSequence()
        .filter { it.isNotBlank() }
        .filter { id ->
            previousById[id]?.m3uUrl?.trim() != currentById[id]?.m3uUrl?.trim()
        }
        .toSet()
}

internal fun retainGroupOrderForUnchangedSources(
    savedOrder: List<String>,
    changedPlaylistIds: Set<String>,
): List<String> {
    if (savedOrder.isEmpty() || changedPlaylistIds.isEmpty()) return savedOrder
    return savedOrder.filterNot { raw ->
        PlaylistGroupKey(raw.trim()).playlistId in changedPlaylistIds
    }
}
