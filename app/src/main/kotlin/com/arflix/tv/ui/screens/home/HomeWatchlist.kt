package com.arflix.tv.ui.screens.home

import com.arflix.tv.data.model.Category
import com.arflix.tv.data.model.MediaItem

const val HOME_WATCHLIST_ROW_ID = "home_watchlist"

internal fun homeWatchlistDisplayItems(items: List<MediaItem>): List<MediaItem> = items
    .filterNot { it.isPlaceholder || it.title.isBlank() }
    .distinctBy { it.mediaType to it.id }
    .sortedWith(compareBy<MediaItem> { it.sourceOrder }.thenByDescending { it.addedAt })

/** A presentation row, not a saved catalog: catalog refreshes cannot delete or duplicate it. */
internal fun withHomeWatchlistRow(
    categories: List<Category>,
    items: List<MediaItem>,
    enabled: Boolean
): List<Category> {
    val base = if (categories.any { it.id == HOME_WATCHLIST_ROW_ID }) {
        categories.filterNot { it.id == HOME_WATCHLIST_ROW_ID }
    } else categories
    if (!enabled || items.isEmpty()) return base
    val insertion = (base.indexOfFirst { it.id == "continue_watching" } + 1).coerceAtLeast(0)
    return base.toMutableList().apply {
        add(insertion, Category(HOME_WATCHLIST_ROW_ID, "Watchlist", items))
    }
}
