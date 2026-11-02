package com.arflix.tv.ui.screens.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arflix.tv.data.model.KidsAgeLevel
import com.arflix.tv.data.model.MediaItem
import com.arflix.tv.data.model.MediaType
import com.arflix.tv.data.model.Category
import com.arflix.tv.data.repository.KidsContentFilter
import com.arflix.tv.data.repository.MediaRepository
import com.arflix.tv.data.repository.MediaSearchResults
import com.arflix.tv.data.repository.PersonMediaSearchResult
import com.arflix.tv.data.repository.RecentSearchRepository
import com.arflix.tv.data.repository.TraktRepository
import com.arflix.tv.data.telegram.FenixRepository
import com.arflix.tv.util.ContentRating
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import javax.inject.Inject

data class Genre(val id: Int, val name: String)

val MOVIE_GENRES = listOf(
    Genre(28, "Action"), Genre(12, "Adventure"), Genre(16, "Animation"),
    Genre(35, "Comedy"), Genre(80, "Crime"), Genre(99, "Documentary"),
    Genre(18, "Drama"), Genre(10751, "Family"), Genre(14, "Fantasy"),
    Genre(36, "History"), Genre(27, "Horror"), Genre(10402, "Music"),
    Genre(9648, "Mystery"), Genre(10749, "Romance"), Genre(878, "Sci-Fi"),
    Genre(53, "Thriller"), Genre(10752, "War"), Genre(37, "Western")
)
val TV_GENRES = listOf(
    Genre(10759, "Action & Adventure"), Genre(16, "Animation"),
    Genre(35, "Comedy"), Genre(80, "Crime"), Genre(99, "Documentary"),
    Genre(18, "Drama"), Genre(10751, "Family"), Genre(10762, "Kids"),
    Genre(9648, "Mystery"), Genre(10765, "Sci-Fi & Fantasy"),
    Genre(10768, "War & Politics"), Genre(37, "Western")
)
val ALL_GENRES = listOf(
    Genre(28, "Action"), Genre(12, "Adventure"), Genre(16, "Animation"),
    Genre(35, "Comedy"), Genre(80, "Crime"), Genre(99, "Documentary"),
    Genre(18, "Drama"), Genre(10751, "Family"), Genre(14, "Fantasy"),
    Genre(27, "Horror"), Genre(9648, "Mystery"), Genre(10749, "Romance"),
    Genre(878, "Sci-Fi"), Genre(53, "Thriller"), Genre(10752, "War"),
    Genre(37, "Western")
)
val ANIME_GENRES = listOf(
    Genre(28, "Action"), Genre(12, "Adventure"), Genre(35, "Comedy"),
    Genre(18, "Drama"), Genre(14, "Fantasy"), Genre(27, "Horror"),
    Genre(10749, "Romance"), Genre(878, "Sci-Fi"), Genre(9648, "Mystery")
)

data class Country(val code: String, val name: String)
val COUNTRIES = listOf(
    Country("en", "English"), Country("ja", "Japanese"), Country("ko", "Korean"),
    Country("es", "Spanish"), Country("fr", "French"), Country("de", "German"),
    Country("it", "Italian"), Country("pt", "Portuguese"), Country("hi", "Hindi"),
    Country("zh", "Chinese"), Country("tr", "Turkish"), Country("ar", "Arabic"),
    Country("th", "Thai"), Country("nl", "Dutch"), Country("ru", "Russian")
)

enum class DiscoverType(val label: String) { ALL("All"), MOVIES("Movies"), TV_SHOWS("TV Shows"), ANIME("Anime") }
enum class SortOption(val label: String, val apiValue: String) { POPULAR("Popular", "popularity.desc"), TOP_RATED("Top Rated", "vote_average.desc"), NEWEST("Newest", "primary_release_date.desc") }

/** A filter change loads straight away (E4) — debounced so two quick taps cost one load. */
private const val DISCOVER_FILTER_DEBOUNCE_MS = 350L
/** Same floor the "Trending" row uses when the user has not set one: keeps single-vote entries out. */
private const val DISCOVER_GRID_MIN_VOTES = 50
/**
 * How many TMDB pages one scroll step may ask for while filters throw pages away.
 */
private const val MAX_GRID_PAGE_ATTEMPTS = 6
/** TMDB's own id for the animation genre — an anime request is never without it. */
private const val ANIMATION_GENRE_ID = "16"
/** TMDB keyword id "anime": what separates anime from western animation. */
private const val ANIME_KEYWORD = "210024"
/** TMDB serves at most 500 discover pages; asking beyond that only returns errors. */
private const val TMDB_MAX_DISCOVER_PAGE = 500

// Memoized empty collections to reduce GC pressure
private val EMPTY_MEDIA_ITEMS: List<MediaItem> = emptyList()
private val EMPTY_CATEGORIES: List<Category> = emptyList()
private val EMPTY_LOGO_URLS: Map<String, String> = emptyMap()

data class SearchUiState(
    val query: String = "",
    val isLoading: Boolean = false,
    val results: List<MediaItem> = EMPTY_MEDIA_ITEMS,
    val movieResults: List<MediaItem> = EMPTY_MEDIA_ITEMS,
    val tvResults: List<MediaItem> = EMPTY_MEDIA_ITEMS,
    val personResults: List<Category> = EMPTY_CATEGORIES,
    val cardLogoUrls: Map<String, String> = EMPTY_LOGO_URLS,
    val error: String? = null,
    // Discover rows - always 5 rows, dynamically built from active filters
    val discoverCategories: List<Category> = EMPTY_CATEGORIES,
    val discoverLogoUrls: Map<String, String> = EMPTY_LOGO_URLS,
    val isDiscoverLoading: Boolean = false,
    // Filters.
    val selectedType: DiscoverType = DiscoverType.MOVIES,
    /** Several genres at once, combined with AND unless [matchAllGenres] is turned off. */
    val selectedGenres: List<Genre> = emptyList(),
    val matchAllGenres: Boolean = true,
    val sortOption: SortOption = SortOption.POPULAR,
    val rating: RatingFilter = RatingFilter(),
    /** The chosen decade. It filters on its own; [year] narrows it further, optionally. */
    val decade: Decade? = null,
    val year: Int? = null,
    /** Movies only — `discover/tv` has no certification parameter at TMDB. */
    val certification: String? = null,
    val language: String? = null,
    val hideWatched: Boolean = false,
    // Discover grid - shown instead of the five rows as soon as a filter is set
    val discoverGridItems: List<MediaItem> = EMPTY_MEDIA_ITEMS,
    val isGridLoading: Boolean = false,
    val isGridLoadingMore: Boolean = false,
    val gridEndReached: Boolean = false,
    val gridLoadFailed: Boolean = false,
    val gridScanPaused: Boolean = false,
    // AI
    val aiInterpretation: String? = null,
    val aiResults: List<MediaItem> = EMPTY_MEDIA_ITEMS,
    val isAiSearch: Boolean = false
) {
    val hasDiscoverFilters: Boolean
        get() = selectedGenres.isNotEmpty() || rating.isSet || decade != null || year != null ||
            certification != null || language != null || hideWatched
}

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val mediaRepository: MediaRepository,
    private val traktRepository: TraktRepository,
    private val recentSearchRepository: RecentSearchRepository,
    private val kidsContentFilter: KidsContentFilter,
    private val fenixRepository: FenixRepository
) : ViewModel() {

    val kidsFilterState: StateFlow<KidsContentFilter.State> = kidsContentFilter.state

    /** Results as a kids profile may see them; any other profile gets [state] unchanged. */
    fun applyKidsFilter(state: SearchUiState, kids: KidsContentFilter.State): SearchUiState {
        if (!kids.isActive) return state
        return state.copy(
            results = kidsContentFilter.filter(state.results, kids),
            movieResults = kidsContentFilter.filter(state.movieResults, kids),
            tvResults = kidsContentFilter.filter(state.tvResults, kids),
            personResults = kidsContentFilter.filterCategories(state.personResults, kids),
            discoverCategories = kidsContentFilter.filterCategories(state.discoverCategories, kids),
            discoverGridItems = kidsContentFilter.filter(state.discoverGridItems, kids),
            aiResults = kidsContentFilter.filter(state.aiResults, kids)
        )
    }

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    /** The profile's recent queries, newest first; shown while the search bar is empty. */
    val recentSearches: StateFlow<List<String>> = recentSearchRepository.recentSearches
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    /** A submitted query waiting for its results: only a query that found something is kept. */
    private var pendingRecentSearch: String? = null

    private var searchJob: Job? = null
    private var discoverJob: Job? = null
    private var filterDebounceJob: Job? = null
    private var gridPage = 0
    private var gridGeneration = 0L
    private var gridJob: Job? = null
    private var watchedMarksJob: Job? = null

    private fun cancelGridLoad() {
        gridGeneration++
        gridJob?.cancel()
        gridJob = null
    }
    private var cachedSuggestionQuery = ""
    private var cachedSuggestionResults: List<MediaItem> = EMPTY_MEDIA_ITEMS
    private var cachedPeopleQuery = ""
    private var cachedPeopleResults: List<Category> = EMPTY_CATEGORIES
    private var activeSearchQuery: String? = null
    private var peopleNeedingCredits: List<PersonMediaSearchResult> = emptyList()

    val contentLanguage: String get() = mediaRepository.contentLanguage ?: "en-US"

    init {
        startDiscoverLoad()
        viewModelScope.launch {
            var previousLevel = kidsFilterState.value.level
            kidsFilterState.collect { state ->
                if (state.level != previousLevel) {
                    previousLevel = state.level
                    startDiscoverLoad()
                }
            }
        }
    }

    private fun filterFenixItems(items: List<MediaItem>): List<MediaItem> {
        return items.filter { item ->
            if (item.mediaType == MediaType.MOVIE) fenixRepository.isMovieAvailable(item.id)
            else fenixRepository.isSeriesAvailable(item.id)
        }
    }

    // ── Discover Rows (5 dynamic rows based on filters) ─────────────────

    private fun loadDiscoverRows() {
        discoverJob?.cancel()
        val state = _uiState.value
        _uiState.value = state.copy(isDiscoverLoading = true)

        discoverJob = viewModelScope.launch {
            try {
                val type = state.selectedType
                val genres = genresParam(state.selectedGenres, state.matchAllGenres)
                val kids = kidsFilterState.value

                val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
                val cal = java.util.Calendar.getInstance()
                cal.add(java.util.Calendar.DAY_OF_YEAR, -90)
                val threeMonthsAgo = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(cal.time)

                val categories = withContext(Dispatchers.IO) {
                    coroutineScope {
                        if (kids.isActive) {
                            val maxAge = kids.level?.maxAge ?: 10
                            val certLte = when (kids.level) {
                                KidsAgeLevel.LITTLE_KIDS -> "L"
                                KidsAgeLevel.OLDER_KIDS -> "10"
                                KidsAgeLevel.PRE_TEENS -> "12"
                                KidsAgeLevel.TEENS -> "14"
                                else -> "10"
                            }

                            fun kidsRow(
                                rowType: DiscoverType,
                                rowGenres: String?,
                                withoutGenres: String? = null,
                                sort: String = "popularity.desc",
                                minVotes: Int = 10,
                                from: String? = null
                            ) = DiscoverRequest(
                                type = rowType,
                                genres = rowGenres,
                                withoutGenres = withoutGenres,
                                sort = sort,
                                minVotes = minVotes,
                                page = 1,
                                certification = certLte,
                                certificationCountry = "BR",
                                releaseDateGte = from,
                                releaseDateLte = today
                            )

                            when (type) {
                                DiscoverType.MOVIES -> {
                                    if (maxAge <= 10) {
                                        val row1 = async { buildRow("Trending", kidsRow(DiscoverType.MOVIES, if (maxAge <= 7) "10751,16" else null, minVotes = 15)) }
                                        val row2 = async { buildRow("Adventure & Fantasy", kidsRow(DiscoverType.MOVIES, "12,14,10751", minVotes = 10)) }
                                        val row3 = async { buildRow("Comedies", kidsRow(DiscoverType.MOVIES, "35,10751", minVotes = 10)) }
                                        val row4 = async { buildRow("Top Rated", kidsRow(DiscoverType.MOVIES, if (maxAge <= 7) "10751,16" else null, sort = "vote_average.desc", minVotes = 50)) }
                                        val raw = listOfNotNull(row1.await(), row2.await(), row3.await(), row4.await())
                                        raw.filter { it.items.isNotEmpty() }
                                    } else {
                                        val row1 = async { buildRow("Trending", kidsRow(DiscoverType.MOVIES, null, minVotes = 20)) }
                                        val row2 = async { buildRow("Adventure & Fantasy", kidsRow(DiscoverType.MOVIES, if (maxAge >= 14) "878,12" else "878,14", minVotes = 15)) }
                                        val row3 = async { buildRow("Comedies", kidsRow(DiscoverType.MOVIES, "35", minVotes = 10)) }
                                        val row4 = async { buildRow("Top Rated", kidsRow(DiscoverType.MOVIES, null, sort = "vote_average.desc", minVotes = 100)) }
                                        val raw = listOfNotNull(row1.await(), row2.await(), row3.await(), row4.await())
                                        raw.filter { it.items.isNotEmpty() }
                                    }
                                }
                                DiscoverType.TV_SHOWS -> {
                                    if (maxAge <= 10) {
                                        // Para até 10 anos: une séries e desenhos em um único carrossel
                                        val row1 = async { buildRow("Cartoons", kidsRow(DiscoverType.TV_SHOWS, "10762|16", minVotes = 5)) }
                                        val row2 = async { buildRow("Adventure & Fantasy", kidsRow(DiscoverType.TV_SHOWS, "10751|10759|10765", minVotes = 5)) }
                                        val row3 = async { buildRow("Top Rated", kidsRow(DiscoverType.TV_SHOWS, "10762|16", sort = "vote_average.desc", minVotes = 10)) }
                                        val raw = listOfNotNull(row1.await(), row2.await(), row3.await())
                                        raw.filter { it.items.isNotEmpty() }
                                    } else {
                                        val row1 = async { buildRow("Series", kidsRow(DiscoverType.TV_SHOWS, null, withoutGenres = "16", minVotes = 10)) }
                                        val row2 = async { buildRow("Cartoons", kidsRow(DiscoverType.TV_SHOWS, "16", minVotes = 10)) }
                                        val row3 = async { buildRow("Top Rated", kidsRow(DiscoverType.TV_SHOWS, null, sort = "vote_average.desc", minVotes = 30)) }
                                        val raw = listOfNotNull(row1.await(), row2.await(), row3.await())
                                        raw.filter { it.items.isNotEmpty() }
                                    }
                                }
                                DiscoverType.ANIME -> {
                                    val row1 = async { buildRow("Anime", kidsRow(DiscoverType.ANIME, "16", minVotes = 5)) }
                                    val row2 = async { buildRow("Adventure & Fantasy", kidsRow(DiscoverType.ANIME, "16,10759", minVotes = 5)) }
                                    val row3 = async { buildRow("Top Rated", kidsRow(DiscoverType.ANIME, "16", sort = "vote_average.desc", minVotes = 10)) }
                                    val raw = listOfNotNull(row1.await(), row2.await(), row3.await())
                                    raw.filter { it.items.isNotEmpty() }
                                }
                                else -> {
                                    val row1 = async { buildRow("Trending", kidsRow(DiscoverType.ALL, if (maxAge <= 10) "10751,16,10762" else null, minVotes = 15)) }
                                    val row2 = async { buildRow("Movies", kidsRow(DiscoverType.MOVIES, if (maxAge <= 10) "10751,16" else null, minVotes = 15)) }
                                    val row3 = async { buildRow("Cartoons", kidsRow(DiscoverType.TV_SHOWS, if (maxAge <= 10) "10762|16" else "16", minVotes = 5)) }
                                    val row4 = async { buildRow("Adventure & Fantasy", kidsRow(DiscoverType.ALL, "12,14", minVotes = 10)) }
                                    val row5 = async { buildRow("Comedies", kidsRow(DiscoverType.ALL, "35", minVotes = 10)) }
                                    val raw = listOfNotNull(row1.await(), row2.await(), row3.await(), row4.await(), row5.await())
                                    raw.filter { it.items.isNotEmpty() }
                                }
                            }
                        } else {
                            fun row(sort: String, minVotes: Int, page: Int = 1, from: String? = null) = DiscoverRequest(
                                type = type, genres = genres, sort = sort, minVotes = minVotes, page = page,
                                releaseDateGte = from, releaseDateLte = today
                            )
                            val topRatedVotes = when (type) {
                                DiscoverType.MOVIES -> 1000
                                DiscoverType.TV_SHOWS -> 500
                                else -> 100
                            }
                            val hiddenGemsVotes = when (type) {
                                DiscoverType.MOVIES -> 200
                                DiscoverType.TV_SHOWS -> 150
                                else -> 30
                            }
                            val row1 = async { buildRow("Trending", row("popularity.desc", 50)) }
                            val row2 = async { buildRow("New Releases", row("popularity.desc", 10, from = threeMonthsAgo)) }
                            val row3 = async { buildRow("Top Rated", row("vote_average.desc", topRatedVotes)) }
                            val row4 = async { buildRow("Hidden Gems", row("vote_average.desc", hiddenGemsVotes, page = 2)) }
                            val raw = listOfNotNull(row1.await(), row2.await(), row3.await(), row4.await())
                            raw.filter { it.items.isNotEmpty() }
                        }
                    }
                }
                categories.forEach { cat -> cat.items.forEach { mediaRepository.cacheItem(it) } }
                val isWatched = watchedMatcher()
                _uiState.value = _uiState.value.copy(discoverCategories = markWatched(categories, isWatched), isDiscoverLoading = false)

                launch(Dispatchers.IO) {
                    val slots = Semaphore(3)
                    val allItems = categories.flatMap { it.items }.distinctBy { "${it.mediaType}_${it.id}" }.take(12)
                    val logos = allItems.map { item ->
                        async {
                            val key = "${item.mediaType}_${item.id}"
                            val logo = slots.withPermit {
                                try { withTimeoutOrNull(2_000) { mediaRepository.getLogoUrl(item.mediaType, item.id) } }
                                catch (e: CancellationException) { throw e }
                                catch (_: Exception) { null }
                            }
                            if (!logo.isNullOrBlank()) {
                                mediaRepository.cacheLogoUrl(item.mediaType, item.id, logo)
                                key to logo
                            } else null
                        }
                    }.awaitAll().filterNotNull().toMap()
                    _uiState.update { it.copy(discoverLogoUrls = it.discoverLogoUrls + logos) }
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                _uiState.value = _uiState.value.copy(isDiscoverLoading = false)
            }
        }
    }

    private suspend fun buildRow(
        title: String,
        request: DiscoverRequest,
        targetCount: Int = 10,
        maxPages: Int = 4
    ): Category? {
        return try {
            val accumulated = mutableListOf<MediaItem>()
            var page = request.page
            val kids = kidsFilterState.value

            while (accumulated.size < targetCount && page <= maxPages) {
                val pageItems = fetchDiscoverPage(request.copy(page = page))
                if (pageItems.isEmpty()) break
                if (kids.isActive && pageItems.isNotEmpty()) {
                    kidsContentFilter.ensureAgesCached(pageItems)
                }
                val fenixAvailable = filterFenixItems(pageItems)
                val approved = if (kids.isActive) {
                    fenixAvailable.filter(kids::allows)
                } else {
                    fenixAvailable
                }
                for (item in approved) {
                    if (accumulated.none { it.id == item.id && it.mediaType == item.mediaType }) {
                        accumulated.add(item)
                        if (accumulated.size >= targetCount) break
                    }
                }
                page++
            }

            if (accumulated.isEmpty()) null
            else Category(
                id = "${request.type}_${title}_${request.genres}_${request.page}",
                title = title,
                items = accumulated
            )
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { null }
    }

    private data class DiscoverRequest(
        val type: DiscoverType,
        val genres: String?,
        val withoutGenres: String? = null,
        val sort: String,
        val minVotes: Int?,
        val page: Int,
        val minRating: Double? = null,
        val maxRating: Double? = null,
        val year: Int? = null,
        val certification: String? = null,
        val certificationCountry: String? = null,
        val language: String? = null,
        val releaseDateGte: String? = null,
        val releaseDateLte: String? = null,
        val premiereDateGte: String? = null,
        val premiereDateLte: String? = null
    )

    private suspend fun fetchDiscoverPage(request: DiscoverRequest): List<MediaItem> {
        val movieGenres = request.genres
        val tvGenres = mapMovieGenresToTvGenres(request.genres)
        return when (request.type) {
            DiscoverType.MOVIES -> discoverMoviesFor(request, movieGenres)
            DiscoverType.TV_SHOWS -> discoverTvFor(request, tvGenres)
            DiscoverType.ANIME -> discoverTvFor(request, buildAnimeGenre(tvGenres), keywords = ANIME_KEYWORD)
            DiscoverType.ALL -> coroutineScope {
                val m = async { discoverMoviesFor(request, movieGenres) }
                val t = async { discoverTvFor(request, tvGenres) }
                interleave(m.await(), t.await())
            }
        }
    }

    private suspend fun discoverMoviesFor(request: DiscoverRequest, genres: String?): List<MediaItem> =
        mediaRepository.discoverMovies(
            genres = genres,
            withoutGenres = request.withoutGenres,
            sortBy = request.sort,
            minVoteCount = request.minVotes,
            page = request.page,
            year = request.year,
            releaseDateLte = request.releaseDateLte,
            releaseDateGte = request.releaseDateGte,
            primaryReleaseDateLte = request.premiereDateLte,
            primaryReleaseDateGte = request.premiereDateGte,
            minVoteAverage = request.minRating,
            maxVoteAverage = request.maxRating,
            certificationCountry = request.certificationCountry,
            certificationLte = request.certification,
            language = request.language
        )

    private suspend fun discoverTvFor(
        request: DiscoverRequest,
        genres: String?,
        keywords: String? = null
    ): List<MediaItem> =
        mediaRepository.discoverTv(
            genres = genres,
            withoutGenres = request.withoutGenres,
            sortBy = request.sort,
            minVoteCount = request.minVotes,
            page = request.page,
            year = request.year,
            keywords = keywords,
            airDateLte = request.releaseDateLte,
            airDateGte = request.releaseDateGte,
            firstAirDateLte = request.premiereDateLte,
            firstAirDateGte = request.premiereDateGte,
            minVoteAverage = request.minRating,
            maxVoteAverage = request.maxRating,
            language = request.language
        )

    private fun mapMovieGenresToTvGenres(genres: String?): String? {
        if (genres.isNullOrBlank()) return genres
        val separator = if (genres.contains('|')) "|" else ","
        return genres.split(',', '|')
            .mapNotNull { it.trim().toIntOrNull() }
            .map { remapGenreId(it, DiscoverType.TV_SHOWS) }
            .distinct()
            .joinToString(separator) { it.toString() }
    }

    private fun buildAnimeGenre(genres: String?): String = when {
        genres.isNullOrBlank() -> ANIMATION_GENRE_ID
        genres.split(',', '|').any { it.trim() == ANIMATION_GENRE_ID } -> genres
        else -> "$ANIMATION_GENRE_ID,$genres"
    }

    // ── Discover grid (shown as soon as a filter is set) ────────────────

    private fun loadDiscoverGrid() {
        discoverJob?.cancel()
        cancelGridLoad()
        gridPage = 0
        _uiState.value = _uiState.value.copy(isGridLoading = true, isGridLoadingMore = false, gridEndReached = false, gridLoadFailed = false, gridScanPaused = false)
        val generation = gridGeneration
        gridJob = viewModelScope.launch { fetchGridPage(append = false, generation) }
    }

    fun loadMoreDiscoverGrid() {
        val state = _uiState.value
        if (state.query.isNotEmpty() || !state.hasDiscoverFilters) return
        if (state.isGridLoading || state.isGridLoadingMore || state.gridEndReached || state.gridLoadFailed || state.gridScanPaused) return
        _uiState.value = state.copy(isGridLoadingMore = true)
        val generation = gridGeneration
        gridJob = viewModelScope.launch { fetchGridPage(append = true, generation) }
    }

    fun retryDiscoverGrid() {
        val state = _uiState.value
        if ((!state.gridLoadFailed && !state.gridScanPaused) || state.query.isNotEmpty() || !state.hasDiscoverFilters) return
        val append = gridPage > 0
        _uiState.value = state.copy(gridLoadFailed = false, gridScanPaused = false, isGridLoading = !append, isGridLoadingMore = append)
        val generation = gridGeneration
        gridJob = viewModelScope.launch { fetchGridPage(append, generation) }
    }

    private suspend fun fetchGridPage(append: Boolean, generation: Long) {
        val started = _uiState.value
        val signature = filterSignature(started)
        try {
            val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
            val startPage = gridPage
            val collected = withContext(Dispatchers.IO) { collectGridPages(started, today, startPage) }
            val isWatched = watchedMatcher()
            val current = _uiState.value
            if (generation != gridGeneration || filterSignature(current) != signature || current.query.isNotEmpty()) return
            collected.items.forEach { mediaRepository.cacheItem(it) }
            val existing = if (append) current.discoverGridItems else EMPTY_MEDIA_ITEMS
            val known = existing.mapTo(HashSet()) { it.mediaType to it.id }
            val fresh = collected.items.filter { known.add(it.mediaType to it.id) }
            gridPage = collected.lastPage
            _uiState.value = current.copy(
                discoverGridItems = existing + markWatched(fresh, isWatched),
                isGridLoading = false,
                isGridLoadingMore = false,
                gridEndReached = collected.endReached,
                gridScanPaused = fresh.isEmpty() && !collected.endReached
            )
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) {
            val current = _uiState.value
            if (generation != gridGeneration || filterSignature(current) != signature || current.query.isNotEmpty()) return
            _uiState.value = current.copy(isGridLoading = false, isGridLoadingMore = false, gridLoadFailed = true)
        }
    }

    private data class GridPageResult(val items: List<MediaItem>, val lastPage: Int, val endReached: Boolean)

    private suspend fun collectGridPages(state: SearchUiState, today: String, startPage: Int): GridPageResult {
        val watchedFilter = if (state.hideWatched) watchedMatcher() else null
        val kids = kidsFilterState.value
        val collected = mutableListOf<MediaItem>()
        var page = startPage
        var endReached = false
        var attempts = 0
        val targetCount = 24
        while (attempts < MAX_GRID_PAGE_ATTEMPTS && collected.size < targetCount) {
            attempts++
            page++
            if (page > TMDB_MAX_DISCOVER_PAGE) { endReached = true; break }
            val items = fetchDiscoverPage(gridRequestFor(state, page, today))
            if (items.isEmpty()) { endReached = true; break }
            if (kids.isActive && items.isNotEmpty()) {
                kidsContentFilter.ensureAgesCached(items)
            }
            val fenixFiltered = filterFenixItems(items)
            val ageFiltered = if (kids.isActive) fenixFiltered.filter(kids::allows) else fenixFiltered
            val approved = if (watchedFilter == null) ageFiltered else ageFiltered.filterNot { watchedFilter(it) }
            for (item in approved) {
                if (collected.none { it.id == item.id && it.mediaType == item.mediaType }) {
                    collected.add(item)
                }
            }
        }
        return GridPageResult(collected, page, endReached)
    }

    private fun gridRequestFor(state: SearchUiState, page: Int, today: String): DiscoverRequest {
        val window = releaseWindowFor(state.decade, state.year, today)
        val kids = kidsFilterState.value
        val cert = if (kids.isActive) {
            when (kids.level) {
                KidsAgeLevel.LITTLE_KIDS -> "L"
                KidsAgeLevel.OLDER_KIDS -> "10"
                KidsAgeLevel.PRE_TEENS -> "12"
                KidsAgeLevel.TEENS -> "14"
                else -> "10"
            }
        } else {
            state.certification.takeIf { supportsCertification(state.selectedType) }
        }
        val certCountry = if (kids.isActive) {
            "BR"
        } else {
            ContentRating.regionOf(mediaRepository.contentLanguage)
        }

        return DiscoverRequest(
            type = state.selectedType,
            genres = genresParam(state.selectedGenres, state.matchAllGenres),
            withoutGenres = null,
            sort = state.sortOption.apiValue,
            minVotes = state.rating.minVotes ?: DISCOVER_GRID_MIN_VOTES,
            page = page,
            minRating = state.rating.min,
            maxRating = state.rating.max,
            year = state.year,
            certification = cert,
            certificationCountry = certCountry,
            language = state.language,
            premiereDateGte = window.from,
            premiereDateLte = window.to
        )
    }

    private suspend fun watchedMatcher(): (MediaItem) -> Boolean = withContext(Dispatchers.Default) {
        val watchedMovies = traktRepository.getWatchedMoviesFromCache()
        val startedShows = traktRepository.getWatchedEpisodesFromCache().mapNotNullTo(HashSet<Int>()) { key ->
            if (key.startsWith("show_tmdb:")) {
                key.removePrefix("show_tmdb:").substringBefore(':', "").toIntOrNull()
            } else null
        }
        val matches: (MediaItem) -> Boolean = { item ->
            when (item.mediaType) {
                MediaType.MOVIE -> item.id in watchedMovies
                MediaType.TV -> item.id in startedShows
            }
        }
        matches
    }

    private fun markWatched(
        items: List<MediaItem>,
        isWatched: (MediaItem) -> Boolean
    ): List<MediaItem> {
        if (items.none { it.isWatched != isWatched(it) }) return items
        return items.map { item ->
            val watched = isWatched(item)
            if (item.isWatched == watched) item else item.copy(isWatched = watched)
        }
    }

    @JvmName("markWatchedRows")
    private fun markWatched(
        rows: List<Category>,
        isWatched: (MediaItem) -> Boolean
    ): List<Category> {
        if (rows.none { row -> row.items.any { it.isWatched != isWatched(it) } }) return rows
        return rows.map { row -> row.copy(items = markWatched(row.items, isWatched)) }
    }

    fun refreshWatchedMarks() {
        watchedMarksJob?.cancel()
        watchedMarksJob = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { traktRepository.initializeWatchedCache() }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { }
            val isWatched = watchedMatcher()
            withContext(Dispatchers.Default) {
                _uiState.update { state ->
                    state.copy(
                        discoverCategories = markWatched(state.discoverCategories, isWatched),
                        discoverGridItems = markWatched(state.discoverGridItems, isWatched),
                        results = markWatched(state.results, isWatched),
                        movieResults = markWatched(state.movieResults, isWatched),
                        tvResults = markWatched(state.tvResults, isWatched),
                        personResults = markWatched(state.personResults, isWatched),
                        aiResults = markWatched(state.aiResults, isWatched)
                    )
                }
            }
        }
    }

    private fun filterSignature(state: SearchUiState): String = listOf(
        state.selectedType,
        state.selectedGenres.joinToString(",") { it.id.toString() },
        state.matchAllGenres,
        state.sortOption,
        state.rating.min, state.rating.max, state.rating.minVotes,
        state.decade,
        state.year,
        state.certification,
        state.hideWatched
    ).joinToString("|")

    // ── Filters → reload rows or grid ───────────────────────────────────

    private fun applyDiscoverSelection(debounce: Boolean = true) {
        cancelGridLoad()
        filterDebounceJob?.cancel()
        discoverJob?.cancel()
        gridPage = 0
        val state = _uiState.value
        _uiState.value = state.copy(
            discoverCategories = EMPTY_CATEGORIES,
            discoverLogoUrls = EMPTY_LOGO_URLS,
            discoverGridItems = EMPTY_MEDIA_ITEMS,
            isDiscoverLoading = !state.hasDiscoverFilters,
            isGridLoading = state.hasDiscoverFilters,
            isGridLoadingMore = false,
            gridEndReached = false,
            gridLoadFailed = false,
            gridScanPaused = false
        )
        if (!debounce) { startDiscoverLoad(); return }
        filterDebounceJob = viewModelScope.launch {
            delay(DISCOVER_FILTER_DEBOUNCE_MS)
            startDiscoverLoad()
        }
    }

    private fun startDiscoverLoad() {
        if (_uiState.value.hasDiscoverFilters) loadDiscoverGrid() else loadDiscoverRows()
    }

    fun selectType(type: DiscoverType) {
        val state = _uiState.value
        if (state.selectedType == type) return
        _uiState.value = state.copy(
            selectedType = type,
            selectedGenres = remapGenresForType(state.selectedGenres, type),
            certification = if (supportsCertification(type)) state.certification else null
        )
        applyDiscoverSelection()
    }

    fun toggleGenre(genre: Genre) {
        val state = _uiState.value
        val genres = if (state.selectedGenres.any { it.id == genre.id }) {
            state.selectedGenres.filterNot { it.id == genre.id }
        } else {
            state.selectedGenres + genre
        }
        _uiState.value = state.copy(selectedGenres = genres)
        applyDiscoverSelection()
    }

    fun setMatchAllGenres(matchAll: Boolean) {
        val state = _uiState.value
        if (state.matchAllGenres == matchAll) return
        _uiState.value = state.copy(matchAllGenres = matchAll)
        if (state.selectedGenres.size > 1) applyDiscoverSelection()
    }

    fun selectSort(sort: SortOption) {
        val state = _uiState.value
        if (state.sortOption == sort) return
        _uiState.value = state.copy(sortOption = sort)
        if (state.hasDiscoverFilters) applyDiscoverSelection()
    }

    fun setRating(rating: RatingFilter) {
        _uiState.value = _uiState.value.copy(rating = rating)
        applyDiscoverSelection()
    }

    fun selectDecade(decade: Decade?) {
        val year = _uiState.value.year?.takeIf { decade != null && it in decade.start..decade.end }
        _uiState.value = _uiState.value.copy(decade = decade, year = year)
        applyDiscoverSelection()
    }

    fun selectYear(year: Int?) {
        _uiState.value = _uiState.value.copy(year = year)
        applyDiscoverSelection()
    }

    fun selectCertification(certification: String?) {
        if (!supportsCertification(_uiState.value.selectedType)) return
        _uiState.value = _uiState.value.copy(certification = certification)
        applyDiscoverSelection()
    }

    fun selectLanguage(code: String?) {
        val state = _uiState.value
        if (state.language == code) return
        _uiState.value = state.copy(language = code)
        applyDiscoverSelection()
    }

    fun setHideWatched(hide: Boolean) {
        val state = _uiState.value
        if (state.hideWatched == hide) return
        _uiState.value = state.copy(hideWatched = hide)
        applyDiscoverSelection()
    }

    fun clearDiscoverFilters() {
        val state = _uiState.value
        if (!state.hasDiscoverFilters) return
        _uiState.value = state.copy(
            selectedGenres = emptyList(),
            rating = RatingFilter(),
            decade = null,
            year = null,
            certification = null,
            language = null,
            hideWatched = false
        )
        applyDiscoverSelection(debounce = false)
    }

    // ── Search + AI ─────────────────────────────────────────────────────

    fun addChar(char: String) { updateQuery(_uiState.value.query + char) }
    fun deleteChar() { if (_uiState.value.query.isNotEmpty()) updateQuery(_uiState.value.query.dropLast(1)) }

    fun updateQuery(newQuery: String) {
        if (newQuery == _uiState.value.query) return
        searchJob?.cancel()
        activeSearchQuery = null
        _uiState.value = _uiState.value.copy(query = newQuery, isAiSearch = false, aiInterpretation = null, aiResults = EMPTY_MEDIA_ITEMS)
        if (newQuery.trim().isEmpty()) {
            clearSearch()
            return
        }
        filterDebounceJob?.cancel()
        discoverJob?.cancel()
        cancelGridLoad()
        _uiState.value = _uiState.value.copy(isLoading = true, isDiscoverLoading = false,
            isGridLoading = false, isGridLoadingMore = false, error = null, results = EMPTY_MEDIA_ITEMS,
            movieResults = EMPTY_MEDIA_ITEMS, tvResults = EMPTY_MEDIA_ITEMS,
            personResults = EMPTY_CATEGORIES, cardLogoUrls = EMPTY_LOGO_URLS)
        debounceSearch()
    }

    fun search() {
        val query = _uiState.value.query.trim(); if (query.isEmpty()) return
        if (searchJob?.isActive == true && activeSearchQuery == query && _uiState.value.error == null) return
        val aiQuery = parseSmartQuery(query); if (aiQuery != null) { executeSmartSearch(aiQuery); return }
        searchJob?.cancel()
        activeSearchQuery = query
        searchJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = it.results.isEmpty(), error = null, isAiSearch = false) }
            try {
                if (cachedSuggestionQuery != query || cachedPeopleQuery != query) {
                    val response = withContext(Dispatchers.IO) { mediaRepository.searchWithPeople(query) }
                    val fenixFiltered = filterFenixItems(response.items)
                    val sorted = withContext(Dispatchers.Default) { rankSearchResults(query, fenixFiltered) }
                    cachedSuggestionQuery = query
                    cachedSuggestionResults = sorted
                    cachedPeopleQuery = query
                    peopleNeedingCredits = response.people.filter { it.items.isEmpty() }
                    cachedPeopleResults = response.people.filter { it.items.isNotEmpty() }
                        .mapNotNull {
                            val filtered = filterFenixItems(it.items)
                            if (filtered.isEmpty()) null
                            else Category("person_${it.personId}", it.name, filtered)
                        }
                }
                val sorted = cachedSuggestionResults
                val peopleRows = cachedPeopleResults
                val isWatched = watchedMatcher()
                val marked = markWatched(sorted, isWatched)
                val markedPeople = markWatched(peopleRows, isWatched)
                _uiState.update { it.copy(isLoading = sorted.isEmpty() && peopleRows.isEmpty() && peopleNeedingCredits.isNotEmpty(), results = marked,
                    movieResults = marked.filter { item -> item.mediaType == MediaType.MOVIE },
                    tvResults = marked.filter { item -> item.mediaType == MediaType.TV }, personResults = markedPeople) }
                if (sorted.isNotEmpty() || peopleRows.isNotEmpty()) savePendingRecentSearch(query)
                if (kidsFilterState.value.isActive && sorted.isNotEmpty()) {
                    viewModelScope.launch { kidsContentFilter.ensureAgesCached(sorted) }
                }

                val slots = Semaphore(3)
                val top = (sorted + peopleRows.flatMap { it.items }).distinctBy { it.mediaType to it.id }.take(12)
                coroutineScope {
                    launch {
                        for (person in peopleNeedingCredits) {
                            val credits = try {
                                withTimeoutOrNull(2_000) {
                                    withContext(Dispatchers.IO) { mediaRepository.getPersonDetails(person.personId).knownFor }
                                }.orEmpty()
                            } catch (e: CancellationException) { throw e }
                            catch (_: Exception) { emptyList() }
                            val fenixCredits = filterFenixItems(credits)
                            if (fenixCredits.isNotEmpty()) {
                                val row = Category("person_${person.personId}", person.name, fenixCredits.distinctBy { it.mediaType to it.id })
                                cachedPeopleResults = cachedPeopleResults + row
                                savePendingRecentSearch(query)
                                val creditsWatched = watchedMatcher()
                                _uiState.update { it.copy(personResults = it.personResults + row.copy(items = markWatched(row.items, creditsWatched)), isLoading = false) }
                            }
                        }
                        peopleNeedingCredits = emptyList()
                        _uiState.update { it.copy(isLoading = false) }
                    }
                    top.forEach { item -> launch {
                        val key = "${item.mediaType}_${item.id}"
                        val logo = slots.withPermit {
                            try {
                                withTimeoutOrNull(2_000) {
                                    withContext(Dispatchers.IO) { mediaRepository.getLogoUrl(item.mediaType, item.id) }
                                }
                            } catch (e: CancellationException) { throw e }
                            catch (_: Exception) { null }
                        }
                        if (!logo.isNullOrBlank()) {
                            _uiState.update { it.copy(cardLogoUrls = it.cardLogoUrls + (key to logo)) }
                        }
                    } }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { _uiState.update { it.copy(isLoading = false, error = e.message) } }
        }
    }

    private data class SmartQuery(val interpretation: String, val type: DiscoverType, val genreId: String?, val sort: String, val minVotes: Int?, val limit: Int?, val similarTo: String?)

    private fun parseSmartQuery(raw: String): SmartQuery? {
        if (!isSmartDiscoveryQuery(raw)) return null
        val q = raw.lowercase().trim()
        val genreKeywords = mapOf("horror" to "27", "comedy" to "35", "action" to "28", "drama" to "18", "thriller" to "53", "sci-fi" to "878", "science fiction" to "878", "romance" to "10749", "animation" to "16", "anime" to "16", "documentary" to "99", "crime" to "80", "fantasy" to "14", "adventure" to "12", "mystery" to "9648", "war" to "10752", "western" to "37", "family" to "10751", "history" to "36")
        val likeMatch = SearchRegexes.LIKE_MATCH_REGEX.find(q)
        if (likeMatch != null) { val t = likeMatch.groupValues[1].trim(); return SmartQuery("Similar to \"${t.replaceFirstChar { it.uppercase() }}\"", if (q.contains("show") || q.contains("series")) DiscoverType.TV_SHOWS else DiscoverType.MOVIES, null, "popularity.desc", null, null, t) }
        if (!(q.contains("top") || q.contains("best") || q.contains("popular") || q.contains("trending") || q.contains("new") || q.contains("latest"))) return null
        var gId: String? = null; var gName: String? = null; for ((kw, id) in genreKeywords) { if (q.contains(kw)) { gId = id; gName = kw.replaceFirstChar { it.uppercase() }; break } }
        if (gId == null && !q.contains("movie") && !q.contains("show") && !q.contains("series") && !q.contains("film") && !q.contains("trending") && !q.contains("anime")) return null
        val isAnime = q.contains("anime"); val isTV = q.contains("show") || q.contains("series"); val isMovie = q.contains("movie") || q.contains("film")
        val type = when { isAnime -> DiscoverType.ANIME; isTV && !isMovie -> DiscoverType.TV_SHOWS; isMovie && !isTV -> DiscoverType.MOVIES; else -> DiscoverType.ALL }
        val limit = SearchRegexes.LIMIT_MATCH_REGEX.find(q)?.groupValues?.get(1)?.toIntOrNull()
        val sort = when { q.contains("best") || q.contains("top rated") || limit != null -> "vote_average.desc"; q.contains("new") || q.contains("latest") -> if (isTV || isAnime) "first_air_date.desc" else "primary_release_date.desc"; else -> "popularity.desc" }
        val parts = mutableListOf<String>(); if (limit != null) parts.add("Top $limit"); if (sort == "vote_average.desc" && limit == null) parts.add("Best") else if (sort.contains("date")) parts.add("Newest") else parts.add("Popular")
        if (gName != null) parts.add(gName); parts.add(when(type) { DiscoverType.MOVIES -> "Movies"; DiscoverType.TV_SHOWS -> "Series"; DiscoverType.ANIME -> "Anime"; DiscoverType.ALL -> "Movies & Series" })
        return SmartQuery(parts.joinToString(" "), type, gId, sort, if (sort == "vote_average.desc") 500 else null, limit, null)
    }

    private fun executeSmartSearch(sq: SmartQuery) {
        val query = _uiState.value.query.trim()
        searchJob?.cancel(); searchJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, isAiSearch = true, aiInterpretation = sq.interpretation, error = null, movieResults = EMPTY_MEDIA_ITEMS, tvResults = EMPTY_MEDIA_ITEMS, personResults = EMPTY_CATEGORIES)
            try {
                val items = withContext(Dispatchers.IO) {
                    if (sq.similarTo != null) { val r = mediaRepository.search(sq.similarTo); val m = r.firstOrNull(); if (m != null) mediaRepository.getSimilar(m.mediaType, m.id) else EMPTY_MEDIA_ITEMS }
                    else {
                        val tvGenre = mapMovieGenresToTvGenres(sq.genreId)
                        when (sq.type) {
                            DiscoverType.MOVIES -> mediaRepository.discoverMovies(sq.genreId, sq.sort, sq.minVotes, 1)
                            DiscoverType.TV_SHOWS -> mediaRepository.discoverTv(tvGenre, sq.sort, sq.minVotes, 1)
                            DiscoverType.ANIME -> mediaRepository.discoverTv(buildAnimeGenre(tvGenre), sq.sort, sq.minVotes, 1, keywords = ANIME_KEYWORD)
                            DiscoverType.ALL -> {
                                coroutineScope {
                                    val a = async { mediaRepository.discoverMovies(sq.genreId, sq.sort, sq.minVotes, 1) }
                                    val b = async { mediaRepository.discoverTv(tvGenre, sq.sort, sq.minVotes, 1) }
                                    interleave(a.await(), b.await())
                                }
                            }
                        }
                    }
                }
                val fenixItems = filterFenixItems(items)
                fenixItems.forEach { mediaRepository.cacheItem(it) }
                val isWatched = watchedMatcher()
                _uiState.value = _uiState.value.copy(isLoading = false, aiResults = markWatched(if (sq.limit != null) fenixItems.take(sq.limit) else fenixItems, isWatched))
                if (fenixItems.isNotEmpty()) savePendingRecentSearch(query)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
            }
        }
    }

    // ── Recent searches ─────────────────────────────────────────────────

    fun rememberSearch() {
        val query = _uiState.value.query.trim()
        if (query.length < MIN_RECENT_SEARCH_LENGTH) return
        pendingRecentSearch = query
        val state = _uiState.value
        val hasResults = if (state.isAiSearch) state.aiResults.isNotEmpty()
            else state.results.isNotEmpty() || state.personResults.isNotEmpty()
        if (hasResults) savePendingRecentSearch(query)
    }

    fun searchRecent(query: String) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return
        pendingRecentSearch = trimmed
        updateQuery(trimmed)
        search()
    }

    fun clearRecentSearches() {
        viewModelScope.launch { recentSearchRepository.clear() }
    }

    private fun savePendingRecentSearch(query: String) {
        if (!pendingRecentSearch.equals(query, ignoreCase = true)) return
        pendingRecentSearch = null
        viewModelScope.launch { recentSearchRepository.add(query) }
    }

    private fun debounceSearch() { searchJob?.cancel(); searchJob = viewModelScope.launch { delay(260); search() } }

    fun clearSearch() {
        cancelGridLoad()
        searchJob?.cancel()
        activeSearchQuery = null
        peopleNeedingCredits = emptyList()
        cachedSuggestionQuery = ""; cachedSuggestionResults = EMPTY_MEDIA_ITEMS
        cachedPeopleQuery = ""; cachedPeopleResults = EMPTY_CATEGORIES
        _uiState.value = _uiState.value.copy(query = "", isLoading = false, isGridLoading = false, isGridLoadingMore = false, results = EMPTY_MEDIA_ITEMS,
            movieResults = EMPTY_MEDIA_ITEMS, tvResults = EMPTY_MEDIA_ITEMS, personResults = EMPTY_CATEGORIES,
            cardLogoUrls = EMPTY_LOGO_URLS, error = null, isAiSearch = false, aiInterpretation = null, aiResults = EMPTY_MEDIA_ITEMS)
        val state = _uiState.value
        if (state.hasDiscoverFilters) {
            if (state.discoverGridItems.isEmpty()) loadDiscoverGrid()
        } else if (state.discoverCategories.isEmpty()) {
            loadDiscoverRows()
        }
    }

    fun getGenresForType(): List<Genre> {
        return when (_uiState.value.selectedType) {
            DiscoverType.MOVIES -> MOVIE_GENRES
            DiscoverType.TV_SHOWS -> TV_GENRES
            DiscoverType.ALL -> ALL_GENRES
            DiscoverType.ANIME -> ANIME_GENRES
        }
    }
    private fun interleave(a: List<MediaItem>, b: List<MediaItem>): List<MediaItem> { val r = mutableListOf<MediaItem>(); for (i in 0 until maxOf(a.size, b.size)) { if (i < a.size) r.add(a[i]); if (i < b.size) r.add(b[i]) }; return r }
}

private const val MIN_RECENT_SEARCH_LENGTH = 2

private object SearchRegexes {
    val LIKE_MATCH_REGEX = Regex("(?:movies?|shows?|series|films?)\\s+like\\s+(.+)", RegexOption.IGNORE_CASE)
    val LIMIT_MATCH_REGEX = Regex("top\\s+(\\d+)", RegexOption.IGNORE_CASE)
}
