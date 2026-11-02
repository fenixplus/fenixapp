package com.arflix.tv.ui.screens.search

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.FilterAltOff
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.LocalOffer
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.arflix.tv.R
import com.arflix.tv.ui.components.movieGenreNameRes
import com.arflix.tv.ui.components.tvGenreNameRes
import java.util.Calendar

/**
 * Builds the discover row and its panels out of the current filter state.
 *
 * It sits beside the screen rather than inside it so the shape of the row — which chip, which
 * icon, what a set chip says — can be read in one place instead of being spread through a
 * thousand-line composable.
 */

/** Every action the row can trigger, so the specs stay free of the view model. */
internal data class DiscoverFilterActions(
    val onSelectType: (DiscoverType) -> Unit,
    val onToggleGenre: (Genre) -> Unit,
    val onMatchAllGenres: (Boolean) -> Unit,
    val onSelectSort: (SortOption) -> Unit,
    val onSetRating: (RatingFilter) -> Unit,
    val onSelectDecade: (Decade?) -> Unit,
    val onSelectYear: (Int?) -> Unit,
    val onSelectCertification: (String?) -> Unit,
    val onSelectLanguage: (String?) -> Unit,
    val onToggleHideWatched: () -> Unit,
    val onClearFilters: () -> Unit,
    val onOpenPanel: (DiscoverFilterId) -> Unit
)

/** How wide each captioned row of tiles is. Narrow enough that a phone never has to clip one. */
private const val MATCH_COLUMNS = 4
private const val VOTE_COLUMNS = 5
private const val DECADE_COLUMNS = 4
private const val YEAR_COLUMNS = 5
private const val DROPDOWN_COLUMNS = 2

/** The marks under the rating bar. They label the bar; they are not values one can pick. */
private val RATING_BAR_TICKS = listOf(0.0, 2.5, 5.0, 7.5, 10.0)

/**
 * Display-only localization of a TMDB genre name.
 *
 * The media type decides which table is asked. Asking the movie table for a series genre is
 * exactly how "Action & Adventure", "Kids", "Sci-Fi & Fantasy" and "War & Politics" ended up
 * untranslated in the German app, so the type travels with the id here.
 */
@Composable
internal fun Genre.localizedNameFor(type: DiscoverType): String {
    val res = when (type) {
        DiscoverType.TV_SHOWS -> tvGenreNameRes(id) ?: movieGenreNameRes(id)
        else -> movieGenreNameRes(id) ?: tvGenreNameRes(id)
    }
    return if (res != null) stringResource(res) else name
}

/** Localized name of a sort order. */
@Composable
internal fun SortOption.localizedLabel(): String = stringResource(
    when (this) {
        SortOption.POPULAR -> R.string.search_sort_popular
        SortOption.TOP_RATED -> R.string.search_sort_top_rated
        SortOption.NEWEST -> R.string.search_sort_newest
    }
)

/**
 * The seven chips of the approved design, in its order, plus the reset chip behind them.
 *
 * The reset chip is the one entry that comes and goes: it joins the row once a filter is set and
 * leaves again the moment it has cleared them, which is why it is added last and conditionally
 * rather than sitting in the fixed list ([showsClearChip]).
 *
 * The draft also shows a shortened row while the user is typing — media type and year, the only
 * two filters TMDB's search endpoints accept. That row is not built here, and the reason is
 * technical rather than a decision: the search path goes through `search/multi`, which takes a
 * query and nothing else. A year filter there means replacing that one call with separate
 * `search/movie` and `search/tv` calls, which changes how search results are ranked. That is its
 * own change, not part of a filter row, so while typing the row stays away as it does today.
 */
@Composable
internal fun discoverChips(
    state: SearchUiState,
    certifications: List<String>,
    actions: DiscoverFilterActions
): List<DiscoverChip> = buildList {
    add(typeChip(state, actions))
    add(genreChip(state, actions))
    add(sortChip(state, actions))
    add(ratingChip(state, actions))
    add(yearChip(state, actions))
    add(certificationChip(state, certifications, actions))
    add(languageChip(state, actions))
    add(hideWatchedChip(state, actions))
    if (showsClearChip(state)) add(clearChip(actions))
}

/** The three media types the app knows, in the order the row shows them. */
private val TYPES = listOf(DiscoverType.MOVIES, DiscoverType.TV_SHOWS, DiscoverType.ANIME)

@Composable
private fun typeChip(state: SearchUiState, actions: DiscoverFilterActions): DiscoverChip {
    val current = TYPES.indexOf(state.selectedType).coerceAtLeast(0)
    return DiscoverChip(
        id = DiscoverFilterId.TYPE,
        key = "type",
        label = typeLabel(state.selectedType),
        hasPanel = false,
        // The type is always set, so it is never the white "a filter is on" chip — that colour
        // has to keep meaning "this narrows the list", or the row stops saying anything.
        isSet = false,
        segments = TYPES.map { typeLabel(it) },
        selectedSegment = current,
        onActivate = { actions.onSelectType(TYPES[(current + 1) % TYPES.size]) },
        onSelectSegment = { index ->
            if (index in TYPES.indices) actions.onSelectType(TYPES[index])
        }
    )
}

@Composable
private fun typeLabel(type: DiscoverType): String = stringResource(
    when (type) {
        DiscoverType.MOVIES -> R.string.movies
        DiscoverType.TV_SHOWS -> R.string.tv_shows
        DiscoverType.ANIME -> R.string.search_filter_anime
        DiscoverType.ALL -> R.string.search_filter_all
    }
)

@Composable
private fun genreChip(state: SearchUiState, actions: DiscoverFilterActions): DiscoverChip {
    val names = state.selectedGenres.map { it.localizedNameFor(state.selectedType) }
    return DiscoverChip(
        id = DiscoverFilterId.GENRE,
        key = "genre",
        label = stringResource(R.string.search_filter_genre),
        value = names.joinToString(", "),
        icon = Icons.Default.LocalOffer,
        isSet = state.selectedGenres.isNotEmpty(),
        onActivate = { actions.onOpenPanel(DiscoverFilterId.GENRE) }
    )
}

@Composable
private fun sortChip(state: SearchUiState, actions: DiscoverFilterActions) = DiscoverChip(
    id = DiscoverFilterId.SORT,
    key = "sort",
    label = stringResource(R.string.search_filter_sort),
    value = state.sortOption.localizedLabel(),
    icon = Icons.AutoMirrored.Filled.Sort,
    isSet = state.sortOption != SortOption.POPULAR,
    onActivate = { actions.onOpenPanel(DiscoverFilterId.SORT) }
)

@Composable
private fun ratingChip(state: SearchUiState, actions: DiscoverFilterActions) = DiscoverChip(
    id = DiscoverFilterId.RATING,
    key = "rating",
    label = stringResource(R.string.search_filter_rating),
    value = ratingChipValue(state.rating),
    icon = Icons.Default.StarBorder,
    isSet = state.rating.isSet,
    onActivate = { actions.onOpenPanel(DiscoverFilterId.RATING) }
)

@Composable
private fun ratingChipValue(rating: RatingFilter): String {
    val any = stringResource(R.string.search_filter_any)
    val from = rating.min?.let { formatRating(it) } ?: any
    val to = rating.max?.let { formatRating(it) } ?: any
    val range = stringResource(R.string.search_filter_rating_range, from, to)
    return rating.minVotes?.let { "$range · $it+" } ?: range
}

/**
 * "7", not "7.0" — the two rating fields offer whole points only, so a trailing zero would be
 * the only decimal on the screen. A half point kept from an older state still prints as one.
 */
private fun formatRating(value: Double): String =
    if (value % 1.0 == 0.0) value.toInt().toString()
    else String.format(java.util.Locale.getDefault(), "%.1f", value)

@Composable
private fun yearChip(state: SearchUiState, actions: DiscoverFilterActions) = DiscoverChip(
    id = DiscoverFilterId.YEAR,
    key = "year",
    label = stringResource(R.string.search_filter_year),
    // A decade alone already filters (J1), so the chip has to be able to say "2010s" as well
    // as "2014" — otherwise a set filter would look unset.
    value = state.year?.toString() ?: state.decade?.let { decadeLabel(it) },
    icon = Icons.Default.DateRange,
    isSet = state.year != null || state.decade != null,
    onActivate = { actions.onOpenPanel(DiscoverFilterId.YEAR) }
)

/** "2020s" / "older" — what one decade tile says. */
@Composable
private fun decadeLabel(decade: Decade): String =
    if (decade.isTail) stringResource(R.string.search_filter_decade_older)
    else stringResource(R.string.search_filter_decade, decadeLabelNumber(decade.start))

@Composable
private fun certificationChip(
    state: SearchUiState,
    certifications: List<String>,
    actions: DiscoverFilterActions
): DiscoverChip {
    // Greyed out rather than gone: TMDB has no certification parameter for series, and a chip
    // that disappears changes the row's length under the user's thumb without explaining why.
    val enabled = supportsCertification(state.selectedType) && certifications.isNotEmpty()
    return DiscoverChip(
        id = DiscoverFilterId.CERTIFICATION,
        key = "certification",
        label = stringResource(R.string.search_filter_age),
        value = state.certification,
        icon = Icons.Default.Security,
        isSet = enabled && state.certification != null,
        isEnabled = enabled,
        onActivate = { if (enabled) actions.onOpenPanel(DiscoverFilterId.CERTIFICATION) }
    )
}

/**
 * The name of an offered original language, as a text of our own.
 *
 * `Constants.getLanguageName` looks like the obvious source and is not usable here: its table is
 * English only (`"ja" to "Japanese"`), so it would write "Japanese" into the German menu — the
 * exact kind of leftover the German translation is busy removing. `null` for anything not
 * offered, which can only happen if [DISCOVER_LANGUAGES] and this list drift apart.
 */
internal fun languageNameRes(code: String): Int? = when (code) {
    "ja" -> R.string.search_filter_language_ja
    "ko" -> R.string.search_filter_language_ko
    "hi" -> R.string.search_filter_language_hi
    else -> null
}

@Composable
private fun languageLabel(code: String): String =
    languageNameRes(code)?.let { stringResource(it) } ?: code

@Composable
private fun languageChip(state: SearchUiState, actions: DiscoverFilterActions) = DiscoverChip(
    id = DiscoverFilterId.LANGUAGE,
    key = "language",
    label = stringResource(R.string.search_filter_language),
    // Only once one is chosen: with nothing set the chip would read "Language: Any", which
    // looks like a filter that is on and says nothing the label does not already say.
    value = state.language?.let { languageLabel(it) },
    icon = Icons.Default.Language,
    isSet = state.language != null,
    onActivate = { actions.onOpenPanel(DiscoverFilterId.LANGUAGE) }
)

@Composable
private fun hideWatchedChip(state: SearchUiState, actions: DiscoverFilterActions) = DiscoverChip(
    id = DiscoverFilterId.HIDE_WATCHED,
    key = "hidewatched",
    label = stringResource(R.string.search_filter_hide_watched),
    icon = Icons.Default.VisibilityOff,
    // A switch, not a list: there is nothing to choose, so it carries no chevron and no panel.
    hasPanel = false,
    isSet = state.hideWatched,
    onActivate = actions.onToggleHideWatched
)

/**
 * The eighth chip: one press and every filter is off again.
 *
 * It is not a filter itself, so it never wears the white "this narrows the list" colour — that
 * colour has to keep meaning one thing — and it opens nothing, so it carries no chevron either.
 * The media type survives the press on purpose; it is always set and therefore not a filter
 * (the reason is written out on [SearchViewModel.clearDiscoverFilters]).
 */
@Composable
private fun clearChip(actions: DiscoverFilterActions) = DiscoverChip(
    id = DiscoverFilterId.CLEAR,
    key = "clear",
    label = stringResource(R.string.search_filter_clear),
    icon = Icons.Default.FilterAltOff,
    hasPanel = false,
    isSet = false,
    onActivate = actions.onClearFilters
)

/** The panel that belongs to [id], or `null` for a chip that is a plain switch. */
@Composable
internal fun filterPanelSpec(
    id: DiscoverFilterId,
    state: SearchUiState,
    certifications: List<String>,
    actions: DiscoverFilterActions
): FilterPanelSpec? = when (id) {
    // The media type is a segmented switch in the row itself, so it has no panel.
    DiscoverFilterId.TYPE -> null
    DiscoverFilterId.GENRE -> genrePanel(state, actions)
    DiscoverFilterId.SORT -> sortPanel(state, actions)
    DiscoverFilterId.RATING -> ratingPanel(state, actions)
    DiscoverFilterId.YEAR -> yearPanel(state, actions)
    DiscoverFilterId.CERTIFICATION -> certificationPanel(state, certifications, actions)
    DiscoverFilterId.LANGUAGE -> languagePanel(state, actions)
    DiscoverFilterId.HIDE_WATCHED -> null
    // The reset chip acts on the press itself; there is nothing to choose behind it.
    DiscoverFilterId.CLEAR -> null
}

@Composable
private fun genrePanel(state: SearchUiState, actions: DiscoverFilterActions) = FilterPanelSpec(
    id = DiscoverFilterId.GENRE,
    title = stringResource(R.string.search_filter_genres),
    subtitle = state.selectedGenres.size
        .takeIf { it > 0 }
        ?.let { stringResource(R.string.search_filter_selected_count, it) },
    sections = listOf(
        // Its own section, which is the point: as a decoration above the grid the switch was
        // unreachable with a remote (Ä4a). A section the focus can enter fixes that by itself.
        PanelSection(
            key = "genre_match",
            entries = PanelEntries.Tiles(
                listOf(
                    PanelOption(
                        key = "match_all",
                        label = stringResource(R.string.search_filter_match_all),
                        isSelected = state.matchAllGenres,
                        onToggle = { actions.onMatchAllGenres(true) }
                    ),
                    PanelOption(
                        key = "match_any",
                        label = stringResource(R.string.search_filter_match_any),
                        isSelected = !state.matchAllGenres,
                        onToggle = { actions.onMatchAllGenres(false) }
                    )
                )
            ),
            columns = MATCH_COLUMNS
        ),
        PanelSection(
            key = "genre_list",
            entries = PanelEntries.Tiles(
                genresFor(state.selectedType).map { genre ->
                    PanelOption(
                        key = "genre_${genre.id}",
                        label = genre.localizedNameFor(state.selectedType),
                        isSelected = state.selectedGenres.any { it.id == genre.id },
                        onToggle = { actions.onToggleGenre(genre) }
                    )
                }
            )
        )
    ),
    // "AND" and "OR" are the two words the user expected of his own accord; the line under them
    // is there for everyone who does not read them as the words from the arithmetic corner.
    footer = stringResource(R.string.search_filter_match_hint)
)

@Composable
private fun sortPanel(state: SearchUiState, actions: DiscoverFilterActions) = FilterPanelSpec(
    id = DiscoverFilterId.SORT,
    title = stringResource(R.string.search_filter_sort),
    sections = listOf(
        PanelSection(
            key = "sort_list",
            entries = PanelEntries.Tiles(
                SortOption.entries.map { sort ->
                    PanelOption(
                        key = "sort_$sort",
                        label = sort.localizedLabel(),
                        isSelected = state.sortOption == sort,
                        onToggle = { actions.onSelectSort(sort) }
                    )
                }
            )
        )
    ),
    footer = stringResource(R.string.search_filter_panel_hint)
)

@Composable
private fun ratingPanel(state: SearchUiState, actions: DiscoverFilterActions): FilterPanelSpec {
    val any = stringResource(R.string.search_filter_any)
    val rating = state.rating
    val fields = listOf(
        PanelDropdown(
            key = "rating_min",
            label = stringResource(R.string.search_filter_rating_from_short),
            valueLabel = rating.min?.let { formatRating(it) } ?: any,
            entries = ratingValues("min", any, rating.min) {
                actions.onSetRating(withRatingFloor(rating, it))
            }
        ),
        PanelDropdown(
            key = "rating_max",
            label = stringResource(R.string.search_filter_rating_to_short),
            valueLabel = rating.max?.let { formatRating(it) } ?: any,
            entries = ratingValues("max", any, rating.max) {
                actions.onSetRating(withRatingCeiling(rating, it))
            }
        )
    )
    return FilterPanelSpec(
        id = DiscoverFilterId.RATING,
        title = stringResource(R.string.search_filter_rating),
        subtitle = ratingChipValue(rating),
        bar = PanelBar(
            from = rating.min ?: 0.0,
            to = rating.max ?: 10.0,
            ticks = RATING_BAR_TICKS.map { formatRating(it) }
        ),
        sections = listOf(
            PanelSection(
                key = "rating_range",
                entries = PanelEntries.Dropdowns(fields),
                columns = DROPDOWN_COLUMNS
            ),
            PanelSection(
                key = "rating_votes",
                caption = stringResource(R.string.search_filter_min_votes),
                entries = PanelEntries.Tiles(
                    MIN_VOTE_OPTIONS.map { value ->
                        PanelOption(
                            key = "votes_$value",
                            label = value?.let { "$it+" } ?: any,
                            isSelected = rating.minVotes == value,
                            onToggle = { actions.onSetRating(rating.copy(minVotes = value)) }
                        )
                    }
                ),
                columns = VOTE_COLUMNS
            )
        ),
        footer = stringResource(R.string.search_filter_rating_hint)
    )
}

/** "Any" plus the whole points, for one of the two rating fields. */
private fun ratingValues(
    prefix: String,
    any: String,
    selected: Double?,
    onPick: (Double?) -> Unit
): List<PanelOption> {
    val all: List<Double?> = listOf(null) + RATING_VALUES
    return all.map { value ->
        PanelOption(
            key = "${prefix}_$value",
            label = value?.let { formatRating(it) } ?: any,
            isSelected = selected == value,
            onToggle = { onPick(value) }
        )
    }
}

@Composable
private fun yearPanel(state: SearchUiState, actions: DiscoverFilterActions): FilterPanelSpec {
    val any = stringResource(R.string.search_filter_any)
    val currentYear = Calendar.getInstance().get(Calendar.YEAR)
    val decades = decadeOptions(currentYear)
    val decadeTiles = listOf(
        PanelOption("decade_any", any, state.decade == null) { actions.onSelectDecade(null) }
    ) + decades.map { decade ->
        PanelOption(
            key = "decade_${decade.start}",
            label = decadeLabel(decade),
            isSelected = state.decade == decade,
            onToggle = { actions.onSelectDecade(decade) }
        )
    }
    val sections = buildList {
        add(
            PanelSection(
                key = "year_decade",
                caption = stringResource(R.string.search_filter_decade_caption),
                entries = PanelEntries.Tiles(decadeTiles),
                columns = DECADE_COLUMNS
            )
        )
        // The exact year only exists inside a decade — without one there is nothing to narrow,
        // and offering 1950 to 2026 in one list is the complaint this panel was rebuilt for.
        state.decade?.let { decade ->
            val years = yearsIn(decade, currentYear)
            add(
                PanelSection(
                    key = "year_exact",
                    caption = stringResource(R.string.search_filter_exact_year),
                    entries = PanelEntries.Tiles(
                        listOf(
                            PanelOption("year_any", any, state.year == null) { actions.onSelectYear(null) }
                        ) + years.map { year ->
                            PanelOption(
                                key = "year_$year",
                                label = year.toString(),
                                isSelected = state.year == year,
                                onToggle = { actions.onSelectYear(year) }
                            )
                        }
                    ),
                    columns = YEAR_COLUMNS
                )
            )
        }
    }
    return FilterPanelSpec(
        id = DiscoverFilterId.YEAR,
        title = stringResource(R.string.search_filter_year),
        subtitle = state.year?.toString() ?: state.decade?.let { decadeLabel(it) },
        sections = sections,
        footer = stringResource(R.string.search_filter_year_hint)
    )
}

@Composable
private fun certificationPanel(
    state: SearchUiState,
    certifications: List<String>,
    actions: DiscoverFilterActions
): FilterPanelSpec {
    val any = stringResource(R.string.search_filter_any)
    val options = listOf(
        PanelOption("cert_any", any, state.certification == null) { actions.onSelectCertification(null) }
    ) + certifications.map { certification ->
        PanelOption(
            key = "cert_$certification",
            label = certification,
            isSelected = state.certification == certification,
            onToggle = { actions.onSelectCertification(certification) }
        )
    }
    return FilterPanelSpec(
        id = DiscoverFilterId.CERTIFICATION,
        title = stringResource(R.string.search_filter_age),
        sections = listOf(
            PanelSection(key = "cert_list", entries = PanelEntries.Tiles(options))
        ),
        // The labels are data, not translations: they follow the certification body of the
        // content country, so "12" here is an FSK 12 and a "15" in the UK is a BBFC 15.
        footer = stringResource(R.string.search_filter_age_movies_only)
    )
}

/**
 * "Any" and the three languages, one at a time.
 *
 * Built like the age panel because it answers the same shape of question — one value out of a
 * short fixed list — and a second panel layout for the same question would only be a second
 * place to keep in step.
 */
@Composable
private fun languagePanel(
    state: SearchUiState,
    actions: DiscoverFilterActions
): FilterPanelSpec {
    val any = stringResource(R.string.search_filter_any)
    val options = listOf(
        PanelOption("lang_any", any, state.language == null) { actions.onSelectLanguage(null) }
    ) + DISCOVER_LANGUAGES.map { code ->
        PanelOption(
            key = "lang_$code",
            label = languageLabel(code),
            isSelected = state.language == code,
            onToggle = { actions.onSelectLanguage(code) }
        )
    }
    return FilterPanelSpec(
        id = DiscoverFilterId.LANGUAGE,
        title = stringResource(R.string.search_filter_language),
        sections = listOf(
            PanelSection(key = "lang_list", entries = PanelEntries.Tiles(options))
        ),
        // Spelled out because the two languages on this screen are easy to mix up: this one
        // picks which titles show up, the one in the settings picks the words around them.
        footer = stringResource(R.string.search_filter_language_hint)
    )
}
