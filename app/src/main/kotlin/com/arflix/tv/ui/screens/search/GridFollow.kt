package com.arflix.tv.ui.screens.search

import kotlin.math.abs

/** Where a fully visible selection must stay still, or scroll by the difference. */
internal fun libraryRevealScrollDelta(itemStart: Int, itemSize: Int, viewportStart: Int, viewportEnd: Int): Int {
    val viewportSize = viewportEnd - viewportStart
    return when {
        viewportSize <= 0 -> 0
        itemSize > viewportSize -> itemStart - viewportStart
        itemStart < viewportStart -> itemStart - viewportStart
        itemStart + itemSize > viewportEnd -> itemStart + itemSize - viewportEnd
        else -> 0
    }
}

/** What the discover grid has to do so the focused card ends up fully in view. */
internal sealed class GridFollow {
    /** Already fully in view — move nothing. */
    object Stay : GridFollow()

    /** Reveal only the clipped part and leave the other cards where they are. */
    data class ScrollBy(val delta: Int) : GridFollow()

    /** The card is outside the window: jump to it, animated for short distances. */
    data class ScrollTo(val index: Int, val animate: Boolean) : GridFollow()
}

/**
 * The decision behind `FollowFocusedGridItem`, kept as a plain function on purpose.
 */
internal fun gridFollowFor(
    targetIndex: Int,
    firstVisibleIndex: Int,
    targetOffsetY: Int?,
    targetHeight: Int,
    viewportStart: Int,
    viewportEnd: Int,
    jumpThreshold: Int = GRID_FOLLOW_JUMP_THRESHOLD
): GridFollow {
    if (targetOffsetY == null) {
        return GridFollow.ScrollTo(targetIndex, animate = abs(targetIndex - firstVisibleIndex) <= jumpThreshold)
    }
    val delta = libraryRevealScrollDelta(targetOffsetY, targetHeight, viewportStart, viewportEnd)
    return if (delta == 0) GridFollow.Stay else GridFollow.ScrollBy(delta)
}

/** Beyond this many cards a jump is no longer animated — it would only blur past everything. */
internal const val GRID_FOLLOW_JUMP_THRESHOLD = 24
