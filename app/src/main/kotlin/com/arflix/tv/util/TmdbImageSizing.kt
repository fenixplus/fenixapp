package com.arflix.tv.util

import kotlin.math.ceil

/**
 * Picks the TMDB image size a load site actually needs.
 */
object TmdbImageSizing {

    /** Official TMDB widths per image type; [aspect] is width / height of the source image. */
    enum class Kind(internal val widths: IntArray, internal val aspect: Float) {
        /** Backdrops and episode stills (both 16:9). */
        BACKDROP(intArrayOf(300, 780, 1280), 16f / 9f),
        POSTER(intArrayOf(92, 154, 185, 342, 500, 780), 2f / 3f),
    }

    /**
     * Kind of the artwork a card slot shows.
     */
    fun cardArtworkKind(imageUrl: String, backdropUrl: String?, landscapeSlot: Boolean): Kind {
        if (landscapeSlot) return Kind.BACKDROP
        val isBackdropFile = backdropUrl != null &&
            imageUrl.substringAfterLast('/') == backdropUrl.substringAfterLast('/')
        return if (isBackdropFile) Kind.BACKDROP else Kind.POSTER
    }

    private val tmdbImageUrl = Regex("""^(https?://image\.tmdb\.org/t/p/)(original|w\d+)(/.+)$""")

    /**
     * Returns [url] with the smallest [kind] size that fills a [slotWidthPx] × [slotHeightPx] slot
     * drawn with a crop scale. Pass `0` for [slotHeightPx] when only the width is known.
     */
    fun forSlot(url: String, slotWidthPx: Int, slotHeightPx: Int, kind: Kind): String {
        if (slotWidthPx <= 0) return url
        val match = tmdbImageUrl.matchEntire(url) ?: return url
        val (prefix, currentSize, path) = match.destructured

        val neededWidth = maxOf(slotWidthPx, ceil(slotHeightPx.coerceAtLeast(0) * kind.aspect).toInt())
        val chosenWidth = kind.widths.firstOrNull { it >= neededWidth }
        val currentWidth = currentSize.removePrefix("w").toIntOrNull()

        if (currentWidth != null && (chosenWidth == null || chosenWidth >= currentWidth)) return url
        return if (chosenWidth == null) url else "${prefix}w$chosenWidth$path"
    }
}
