package com.arflix.tv.ui.screens.search

import com.arflix.tv.util.ContentRating

/**
 * The controls of the discover row, in the order the approved design shows them.
 *
 * [CLEAR] is the only one that is not always there: it joins the row once something is set and
 * leaves it again as soon as it has done its job.
 */
enum class DiscoverFilterId {
    TYPE, GENRE, SORT, RATING, YEAR, CERTIFICATION, LANGUAGE, HIDE_WATCHED, CLEAR
}

/**
 * The original languages the row offers, in the order the panel shows them.
 *
 * Exactly the three that used to sit in the row as buttons of their own and were lost when the
 * row was rebuilt. They are deliberately not "every language TMDB knows": the yardstick is what
 * was there before, so this is a restored control rather than a new one. Anyone widening the
 * list asks first.
 */
val DISCOVER_LANGUAGES: List<String> = listOf("ja", "ko", "hi")

/**
 * Rating range plus the vote floor, the three values that share one panel.
 *
 * The floor defaults to "any": a high floor is the usual way a discover page quietly loses
 * every new release, because a title released last week has not collected the votes yet.
 */
data class RatingFilter(
    val min: Double? = null,
    val max: Double? = null,
    val minVotes: Int? = null
) {
    val isSet: Boolean get() = min != null || max != null || minVotes != null
}

/** Vote floors offered in the rating panel. `null` is "any" and stays the default. */
val MIN_VOTE_OPTIONS: List<Int?> = listOf(null, 50, 100, 500, 1000)

/**
 * The values both rating fields offer, best first.
 *
 * Whole points only: the user asked for "a drop-down field and only whole numbers", and on a
 * remote every half step is one more press for a distinction nobody makes out loud.
 */
val RATING_VALUES: List<Double> = (10 downTo 1).map { it.toDouble() }

/**
 * The rating filter after the lower end has been set to [min].
 *
 * "From 8 up to 6" is not a narrow range, it is an empty grid. Setting one end past the other
 * drags the other along, which is what the user means by it and is impossible to misread.
 */
fun withRatingFloor(rating: RatingFilter, min: Double?): RatingFilter {
    val max = rating.max
    return if (min != null && max != null && min > max) rating.copy(min = min, max = min)
    else rating.copy(min = min)
}

/** The rating filter after the upper end has been set to [max] — the mirror of [withRatingFloor]. */
fun withRatingCeiling(rating: RatingFilter, max: Double?): RatingFilter {
    val min = rating.min
    return if (max != null && min != null && max < min) rating.copy(min = max, max = max)
    else rating.copy(max = max)
}

/**
 * Age certifications offered per country.
 *
 * TMDB filters certifications for movies only — `discover/tv` has no equivalent parameter — and
 * the values are country-specific strings, not a scale, so they cannot be derived. Only
 * countries whose list is short and stable are offered; everywhere else the chip stays off.
 */
val CERTIFICATIONS: Map<String, List<String>> = mapOf(
    "US" to listOf("G", "PG", "PG-13", "R", "NC-17"),
    "DE" to listOf("0", "6", "12", "16", "18"),
    "GB" to listOf("U", "PG", "12A", "15", "18"),
    "FR" to listOf("U", "10", "12", "16", "18"),
    "NL" to listOf("AL", "6", "9", "12", "16")
)

/**
 * Genres as TMDB wants them in `with_genres`.
 *
 * The separator is the whole of E3: a comma means AND (only titles carrying every chosen
 * genre), a pipe means OR. AND is the default because it is TMDB's own and because the user
 * decided the same way in his own app.
 */
fun genresParam(genres: List<Genre>, matchAll: Boolean): String? {
    if (genres.isEmpty()) return null
    val separator = if (matchAll) "," else "|"
    return genres.joinToString(separator) { it.id.toString() }
}

/**
 * The genre list offered for a media type — the same lists the rows have always used, so the
 * panel can never offer a genre the following request would drop.
 */
fun genresFor(type: DiscoverType): List<Genre> = when (type) {
    DiscoverType.MOVIES -> MOVIE_GENRES
    DiscoverType.TV_SHOWS -> TV_GENRES
    DiscoverType.ANIME -> ANIME_GENRES
    DiscoverType.ALL -> ALL_GENRES
}

/**
 * Keeps only the genres that exist for [type].
 *
 * Switching Movies → Series used to drop the genre entirely, because ids differ per media type
 * ("Action" is 28 for a film, "Action & Adventure" 10759 for a series) and a genre carried over
 * blindly returns an empty list. Remapping first and dropping only what has no counterpart keeps
 * the chosen genre wherever one exists, which is all the user ever noticed about it.
 */
fun remapGenresForType(genres: List<Genre>, type: DiscoverType): List<Genre> {
    if (genres.isEmpty()) return genres
    val available = genresFor(type)
    return genres.mapNotNull { genre ->
        val mappedId = remapGenreId(genre.id, type)
        available.firstOrNull { it.id == mappedId }
    }.distinctBy { it.id }
}

/**
 * One genre id translated into [type]'s numbering. Ids that mean the same thing in both
 * numberings (Comedy 35, Drama 18, …) are returned unchanged.
 */
fun remapGenreId(genreId: Int, type: DiscoverType): Int = when (type) {
    DiscoverType.TV_SHOWS -> when (genreId) {
        28, 12 -> 10759          // Action, Adventure -> Action & Adventure
        14, 878 -> 10765         // Fantasy, Sci-Fi   -> Sci-Fi & Fantasy
        10752 -> 10768           // War               -> War & Politics
        else -> genreId
    }
    // Anime is a series request, but ANIME_GENRES is written in the movie numbering and is
    // translated to the series one later, on the way to TMDB. Numbering-wise it belongs here.
    DiscoverType.MOVIES, DiscoverType.ANIME -> when (genreId) {
        10759 -> 28
        10765 -> 878
        10768 -> 10752
        10762 -> 10751           // Kids -> Family
        else -> genreId
    }
    DiscoverType.ALL -> genreId
}

/** The oldest year the year panel reaches back to. TMDB has nothing usable below it. */
const val OLDEST_DISCOVER_YEAR = 1950

/** How many full decades the panel offers before the rest is folded into one "older" tile. */
private const val FULL_DECADES = 5

/**
 * One tile of the year panel's upper row.
 *
 * The last one is the tail: everything below the oldest full decade, folded into a single
 * "older" tile rather than six more rows nobody scrolls to.
 */
data class Decade(val start: Int, val end: Int) {
    /** True for that tail — it spans more than ten years, so it is not "the 1950s". */
    val isTail: Boolean get() = end - start != 9
}

/**
 * The decades offered in the year panel, newest first.
 *
 * Seventy-eight year tiles were the complaint (J1): reaching 1994 cost twenty-four presses of
 * "down". Six tiles reach the same place in one, and the exact year stays one press behind them.
 */
fun decadeOptions(currentYear: Int, oldest: Int = OLDEST_DISCOVER_YEAR): List<Decade> {
    val newest = currentYear / 10 * 10
    val full = (0 until FULL_DECADES).map { Decade(newest - it * 10, newest - it * 10 + 9) }
    val tailEnd = full.last().start - 1
    return if (tailEnd >= oldest) full + Decade(oldest, tailEnd) else full
}

/** The years a decade offers, newest first and never past the current year. */
fun yearsIn(decade: Decade, currentYear: Int): List<Int> =
    (minOf(decade.end, currentYear) downTo decade.start).toList()

/** "2020" for the 2020s, "90" for the nineties — the short form the approved draft shows. */
fun decadeLabelNumber(start: Int): String =
    if (start >= 2000) start.toString() else (start % 100).toString().padStart(2, '0')

/** The release window a discover request carries, in the `yyyy-MM-dd` TMDB wants. */
data class ReleaseWindow(val from: String?, val to: String?)

/**
 * The release window for the chosen decade, or for no decade at all.
 *
 * Two rules meet here. The grid stays on what is actually out, because a film released next
 * year has no rating and usually no poster — that is the [today] cap the grid always had. And a
 * decade is a range, so in the decade we are living in the upper edge is today, not the 31st of
 * December 2029; in a finished decade it is that decade's last day.
 *
 * An exact year is the narrower filter and travels as `primary_release_year` instead, so the
 * window steps aside for it — exactly as it did before there were decades.
 *
 * The window itself goes out as `primary_release_date` / `first_air_date`, the first release of
 * a title. Asked by any release it also caught re-runs, so a film could answer the 2020s and
 * print 1994 on its own card (B34).
 */
fun releaseWindowFor(decade: Decade?, year: Int?, today: String): ReleaseWindow {
    if (year != null) return ReleaseWindow(from = null, to = null)
    if (decade == null) return ReleaseWindow(from = null, to = today)
    return ReleaseWindow(from = "${decade.start}-01-01", to = minOf("${decade.end}-12-31", today))
}

/**
 * Whether the row carries the reset chip at all.
 *
 * Only once something is actually set. A reset chip with nothing to reset is a dead stop for the
 * remote — one more press to get past on the way to the grid, and pressing OK on it does nothing
 * at all, which reads as a broken button rather than as an empty one.
 *
 * It asks the same question the grid asks, deliberately: the chip has to appear exactly when the
 * screen switches over to the filtered grid, so the media type does not count here either
 * (see [SearchUiState.hasDiscoverFilters]).
 */
fun showsClearChip(state: SearchUiState): Boolean = state.hasDiscoverFilters

/**
 * Where the focus frame goes the moment the reset chip has done its job.
 *
 * This chip is the only one in the row that deletes itself, and it is the last one, so the index
 * that was on it points past the end of the row one recomposition later. Stepping back by one
 * lands on what is now the last chip — the frame moves one place left instead of disappearing,
 * which is the only outcome that does not read as the row losing the focus.
 */
fun focusAfterClearChip(focusedIndex: Int): Int = (focusedIndex - 1).coerceAtLeast(0)

/**
 * Whether the chip at [itemOffset] lies wholly inside the row's content area.
 *
 * `firstVisibleItemIndex` calls a chip visible when half of it is off the left edge, which is
 * how the media-type chip stayed cut in half after a scroll out and back (Ä3). The offsets say
 * what the index cannot.
 */
fun isChipFullyVisible(itemOffset: Int, itemSize: Int, contentStart: Int, contentEnd: Int): Boolean =
    itemOffset >= contentStart && itemOffset + itemSize <= contentEnd

/**
 * The certification list for the profile's content language, or empty when that country has
 * none we can offer. An empty list is what greys the age chip out.
 */
fun certificationsForLanguage(contentLanguage: String?): List<String> =
    CERTIFICATIONS[ContentRating.regionOf(contentLanguage)].orEmpty()

/**
 * Whether a media type can be filtered by age at all.
 *
 * TMDB offers `certification.lte` under `discover/movie` only. The chip therefore stays in
 * place and greys out for series instead of disappearing — a control that vanishes reads as a
 * bug, one that greys out reads as an answer.
 */
fun supportsCertification(type: DiscoverType): Boolean = type == DiscoverType.MOVIES

/** Leaving the panel upwards, back to the chip that opened it. */
const val PANEL_FOCUS_LEAVE = -1

/**
 * Where the focus sits inside an open filter panel: which section, and which entry inside it.
 *
 * Two numbers rather than one, because a panel is no longer one even grid. The switch above the
 * genres, the two rating fields and the decade row are sections of their own — that is what made
 * the "Match all / Match any" switch unreachable on a remote before (Ä4a): it sat outside the
 * one grid the focus knew about, so there was nowhere for it to go.
 */
data class PanelFocus(val section: Int, val entry: Int) {
    /** True once the focus has left the panel upwards, back to the chip that opened it. */
    val hasLeft: Boolean get() = section == PANEL_FOCUS_LEAVE

    companion object {
        val LEAVE = PanelFocus(PANEL_FOCUS_LEAVE, PANEL_FOCUS_LEAVE)
        val START = PanelFocus(0, 0)
    }
}

/** One section as far as focus movement is concerned: how many entries, how many per row. */
data class PanelSectionShape(val count: Int, val columns: Int) {
    val safeColumns: Int get() = columns.coerceAtLeast(1)
    /** The index the last row of this section starts at. */
    val lastRowStart: Int get() = if (count <= 0) 0 else (count - 1) / safeColumns * safeColumns
}

/** The first section at or beyond [from] that actually has entries, walking in [step] direction. */
private fun filledSection(sections: List<PanelSectionShape>, from: Int, step: Int): Int? {
    var index = from
    while (index in sections.indices) {
        if (sections[index].count > 0) return index
        index += step
    }
    return null
}

/**
 * [focus] put back onto an entry that exists, or `null` when the panel has none at all.
 *
 * Sections come and go while a panel is open — the exact-year row appears with the decade and
 * disappears with it — so the remembered position has to be checked against the panel as it is
 * now, not as it was when it opened.
 */
fun clampPanelFocus(focus: PanelFocus, sections: List<PanelSectionShape>): PanelFocus? {
    val fallback = filledSection(sections, 0, 1) ?: return null
    val section = focus.section.takeIf { it in sections.indices && sections[it].count > 0 }
        ?: filledSection(sections, focus.section.coerceAtLeast(0), 1)
        ?: fallback
    return PanelFocus(section, focus.entry.coerceIn(0, sections[section].count - 1))
}

/**
 * Where the focus lands inside an open filter panel after a direction key.
 *
 * Sideways movement stays inside its row, so the focus cannot jump from the end of one line to
 * the start of the next — on a remote that reads as the selection teleporting. Up and down step
 * through the sections in order, keeping the column, so pressing up out of the genre tiles lands
 * in the AND/OR switch first and only the next press leaves the panel ([PanelFocus.LEAVE]). Down
 * from the very last row stays put rather than closing, because a closing panel is not what
 * "further down the list" means.
 */
fun movePanelFocus(
    focus: PanelFocus,
    sections: List<PanelSectionShape>,
    dx: Int,
    dy: Int
): PanelFocus {
    val current = clampPanelFocus(focus, sections) ?: return PanelFocus.LEAVE
    return when {
        dy != 0 -> moveAcrossRows(current, sections, dy)
        dx != 0 -> moveAlongRow(current, sections[current.section], dx)
        else -> current
    }
}

private fun moveAcrossRows(
    current: PanelFocus,
    sections: List<PanelSectionShape>,
    dy: Int
): PanelFocus {
    val shape = sections[current.section]
    val column = current.entry % shape.safeColumns
    val target = current.entry + dy * shape.safeColumns
    if (target in 0 until shape.count) return PanelFocus(current.section, target)
    // Down out of a full row into a shorter last one: the row below exists, it is just narrower.
    if (dy > 0 && current.entry < shape.lastRowStart) return PanelFocus(current.section, shape.count - 1)
    val neighbour = filledSection(sections, current.section + dy, dy)
        ?: return if (dy < 0) PanelFocus.LEAVE else current
    return PanelFocus(neighbour, entryEntering(sections[neighbour], column, fromAbove = dy > 0))
}

/** Where the column [column] lands when the focus steps into a neighbouring section. */
private fun entryEntering(shape: PanelSectionShape, column: Int, fromAbove: Boolean): Int =
    if (fromAbove) {
        column.coerceIn(0, minOf(shape.safeColumns, shape.count) - 1)
    } else {
        (shape.lastRowStart + column).coerceIn(shape.lastRowStart, shape.count - 1)
    }

private fun moveAlongRow(current: PanelFocus, shape: PanelSectionShape, dx: Int): PanelFocus {
    val row = current.entry / shape.safeColumns
    val target = current.entry + dx
    val staysInRow = target in 0 until shape.count && target / shape.safeColumns == row
    return if (staysInRow) PanelFocus(current.section, target) else current
}

/**
 * Up and down inside an open drop-down list.
 *
 * It stops at both ends instead of wrapping, the same as every other list on this screen: a list
 * that jumps from "1" back to "Any" reads as a glitch on a remote, not as a shortcut.
 */
fun moveDropdownFocus(index: Int, count: Int, dy: Int): Int =
    if (count <= 0) 0 else (index + dy).coerceIn(0, count - 1)
