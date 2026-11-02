package com.arflix.tv.ui.screens.home

import com.arflix.tv.data.model.MediaItem

internal fun MediaItem?.isSameHomeHero(other: MediaItem?): Boolean =
    this != null && other != null && id == other.id && mediaType == other.mediaType &&
        homeHeroSourceKey() == other.homeHeroSourceKey()

private fun MediaItem.homeHeroSourceKey(): String? = status?.takeIf { it.contains(':') }

// Background loads may seed an empty hero or decorate the same item, never change selection.
internal fun HomeUiState.withBackgroundHero(item: MediaItem?, logoUrl: String?): HomeUiState {
    if (item == null || item.isPlaceholder) return this
    return when {
        heroItem == null -> copy(heroItem = item, heroLogoUrl = logoUrl)
        heroItem.isSameHomeHero(item) && heroLogoUrl == null && logoUrl != null ->
            copy(heroLogoUrl = logoUrl)
        else -> this
    }
}

internal fun homeHeroLogo(
    item: MediaItem?,
    logoUrl: String?,
    preloadedItem: MediaItem?,
    preloadedLogoUrl: String?
): String? = logoUrl ?: preloadedLogoUrl?.takeIf { item.isSameHomeHero(preloadedItem) }
