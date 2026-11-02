package com.arflix.tv.ui.screens.home

import com.arflix.tv.data.model.MediaItem
import com.arflix.tv.data.repository.KidsContentFilter

/**
 * Paging for rows and grids a kids profile sees filtered.
 *
 * The screens report positions in the filtered list, while paging compares them with the
 * loaded (unfiltered) page. With most titles excluded, the last visible title could sit far
 * from the end of the page, so the next page was never requested.
 */
internal object KidsPaging {

    /**
     * The unfiltered position for [displayedIndex]. Once fewer than [lookAhead] visible titles
     * remain after it, nothing more can come from this page, so it maps to the page's end.
     */
    fun rawIndex(raw: List<MediaItem>, kids: KidsContentFilter.State, displayedIndex: Int, lookAhead: Int = 0): Int {
        if (!kids.isActive || displayedIndex < 0) return displayedIndex
        val shown = raw.filter(kids::allows)
        if (displayedIndex >= shown.size - 1 - lookAhead) return raw.lastIndex
        return raw.indexOf(shown[displayedIndex]).takeIf { it >= 0 } ?: raw.lastIndex
    }

    /**
     * Whether a filtered list needs another page to show at least [minVisible] titles. Titles
     * still waiting for their rating count as visible, so paging does not run ahead of the
     * lookups; once they resolve as excluded the check runs again.
     */
    fun needsMore(raw: List<MediaItem>, kids: KidsContentFilter.State, minVisible: Int): Boolean {
        if (!kids.isActive) return false
        return raw.count { !it.isPlaceholder && (kids.allows(it) || kids.isUnknown(it)) } < minVisible
    }
}
