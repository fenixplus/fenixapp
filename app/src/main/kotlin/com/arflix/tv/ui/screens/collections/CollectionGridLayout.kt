package com.arflix.tv.ui.screens.collections

internal const val HOME_TV_LANDSCAPE_CARD_WIDTH_DP = 210
internal const val HOME_MOBILE_LANDSCAPE_CARD_WIDTH_DP = 200
internal const val HOME_TV_POSTER_CARD_WIDTH_DP = 105
internal const val HOME_MOBILE_POSTER_CARD_WIDTH_DP = 120

internal data class CollectionGridLayout(val columns: Int, val cardWidthDp: Int)

internal fun collectionGridLayout(containerWidthDp: Int, mobile: Boolean, posters: Boolean): CollectionGridLayout {
    val available = (containerWidthDp - if (mobile) 40 else 84).coerceAtLeast(1)
    val width = when {
        posters && mobile -> HOME_MOBILE_POSTER_CARD_WIDTH_DP
        posters -> HOME_TV_POSTER_CARD_WIDTH_DP
        mobile -> HOME_MOBILE_LANDSCAPE_CARD_WIDTH_DP
        else -> HOME_TV_LANDSCAPE_CARD_WIDTH_DP
    }.coerceAtMost(available)
    if (mobile) {
        val minimumWidth = if (posters) 110 else 150
        val columns = ((available + 12) / (minimumWidth + 12)).coerceAtLeast(1)
        return CollectionGridLayout(columns, (available - (columns - 1) * 12) / columns)
    }
    return CollectionGridLayout(((available + 12) / (width + 12)).coerceAtLeast(1), width)
}
