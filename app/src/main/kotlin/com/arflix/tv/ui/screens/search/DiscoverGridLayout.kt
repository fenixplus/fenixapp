package com.arflix.tv.ui.screens.search

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Gap between two cards, and the padding on either side of the grid — both from the design. */
internal val DISCOVER_GRID_GAP: Dp = 18.dp
internal val DISCOVER_GRID_SIDE_PADDING: Dp = 24.dp

/**
 * Poster width on a TV or desktop screen [screenWidth] wide.
 */
internal fun discoverGridCardWidth(screenWidth: Dp): Dp = when {
    screenWidth >= 2200.dp -> 120.dp
    screenWidth >= 1600.dp -> 112.dp
    else -> 105.dp
}

/**
 * How many cards of [cardWidth] fit across [screenWidth].
 */
internal fun discoverGridColumns(
    screenWidth: Dp,
    cardWidth: Dp,
    gap: Dp = DISCOVER_GRID_GAP,
    sidePadding: Dp = DISCOVER_GRID_SIDE_PADDING
): Int {
    val available = screenWidth - sidePadding * 2
    if (available <= 0.dp) return 1
    val perCard = cardWidth + gap
    if (perCard <= 0.dp) return 1
    return (((available + gap) / perCard).toInt()).coerceAtLeast(1)
}
