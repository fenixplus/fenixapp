package com.arflix.tv.navigation

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.arflix.tv.data.model.Category
import com.arflix.tv.data.model.MediaItem
import com.arflix.tv.data.model.MediaType
import com.arflix.tv.data.model.Profile
import com.arflix.tv.data.repository.AuthState
import com.arflix.tv.data.telegram.TelegramRepository
import com.arflix.tv.ui.screens.details.DetailsScreen
import com.arflix.tv.ui.screens.home.CategoryViewAllScreen
import com.arflix.tv.ui.screens.home.HomeScreen
import com.arflix.tv.ui.screens.login.LoginScreen
import com.arflix.tv.ui.screens.player.PlayerScreen
import com.arflix.tv.ui.screens.collections.CollectionDetailsScreen
import com.arflix.tv.ui.screens.search.SearchScreen
import com.arflix.tv.ui.screens.settings.SettingsScreen
import com.arflix.tv.ui.screens.settings.telegram.TelegramSettingsScreen
import com.arflix.tv.ui.screens.watchlist.WatchlistScreen
import com.arflix.tv.ui.screens.downloads.DownloadsScreen
import com.arflix.tv.ui.screens.profile.ProfileSelectionScreen
import com.arflix.tv.ui.screens.profile.PinEntryDialog
import com.arflix.tv.util.PinUtil
import com.arflix.tv.R
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.arflix.tv.util.LocalDeviceType

/**
 * Navigation destinations
 */
sealed class Screen(val route: String) {
    data object Login : Screen("login")
    data object Home : Screen("home")
    data object Search : Screen("search")
    data object Watchlist : Screen("watchlist")
    data object Downloads : Screen("downloads")
    data object CollectionDetails : Screen("collections/{catalogId}") {
        fun createRoute(catalogId: String): String {
            return "collections/${android.net.Uri.encode(catalogId)}"
        }
    }
    data object CategoryViewAll : Screen("home_category/{categoryId}") {
        fun createRoute(categoryId: String): String {
            return "home_category/${android.net.Uri.encode(categoryId)}"
        }
    }
    data object Tv : Screen("tv?channelId={channelId}&streamUrl={streamUrl}") {
        fun createRoute(channelId: String? = null, streamUrl: String? = null): String {
            if (channelId == null) return "tv"
            val enc = java.net.URLEncoder.encode(channelId, "UTF-8")
            val streamEnc = streamUrl?.let { java.net.URLEncoder.encode(it, "UTF-8") }
            return if (streamEnc != null) "tv?channelId=$enc&streamUrl=$streamEnc" else "tv?channelId=$enc"
        }
    }
    data object Settings : Screen("settings") {
        fun createRoute(autoCloudAuth: Boolean = false, initialSection: String? = null): String {
            val base = "settings"
            val params = mutableListOf<String>()
            if (autoCloudAuth) params.add("autoCloudAuth=true")
            initialSection?.let { params.add("initialSection=$it") }
            return if (params.isNotEmpty()) "$base?${params.joinToString("&")}" else base
        }
    }
    data object TelegramSettings : Screen("telegram_settings")
    data object ProfileSelection : Screen("profile_selection")

    data object Details : Screen("details/{mediaType}/{mediaId}?initialSeason={initialSeason}&initialEpisode={initialEpisode}") {
        fun createRoute(
            mediaType: MediaType,
            mediaId: Int,
            initialSeason: Int? = null,
            initialEpisode: Int? = null
        ): String {
            val base = "details/${mediaType.name.lowercase()}/$mediaId"
            val params = mutableListOf<String>()
            initialSeason?.let { params.add("initialSeason=$it") }
            initialEpisode?.let { params.add("initialEpisode=$it") }
            return if (params.isNotEmpty()) "$base?${params.joinToString("&")}" else base
        }
    }

    data object Player : Screen("player/{mediaType}/{mediaId}?seasonNumber={seasonNumber}&episodeNumber={episodeNumber}&imdbId={imdbId}&streamUrl={streamUrl}&preferredAddonId={preferredAddonId}&preferredSourceName={preferredSourceName}&preferredBingeGroup={preferredBingeGroup}&preferredDescription={preferredDescription}&startPositionMs={startPositionMs}&isLiveStream={isLiveStream}&fenixEpisodeId={fenixEpisodeId}") {
        fun createRoute(
            mediaType: MediaType,
            mediaId: Int,
            seasonNumber: Int? = null,
            episodeNumber: Int? = null,
            imdbId: String? = null,
            streamUrl: String? = null,
            preferredAddonId: String? = null,
            preferredSourceName: String? = null,
            preferredBingeGroup: String? = null,
            preferredDescription: String? = null,
            startPositionMs: Long? = null,
            isLiveStream: Boolean = false,
            fenixEpisodeId: Int? = null
        ): String {
            val base = "player/${mediaType.name.lowercase()}/$mediaId"
            val params = mutableListOf<String>()
            seasonNumber?.let { params.add("seasonNumber=$it") }
            episodeNumber?.let { params.add("episodeNumber=$it") }
            imdbId?.let { params.add("imdbId=${java.net.URLEncoder.encode(it, "UTF-8")}") }
            streamUrl?.let { params.add("streamUrl=${java.net.URLEncoder.encode(it, "UTF-8")}") }
            preferredAddonId?.let { params.add("preferredAddonId=${java.net.URLEncoder.encode(it, "UTF-8")}") }
            preferredSourceName?.let { params.add("preferredSourceName=${java.net.URLEncoder.encode(it, "UTF-8")}") }
            preferredBingeGroup?.let { params.add("preferredBingeGroup=${java.net.URLEncoder.encode(it, "UTF-8")}") }
            preferredDescription?.let { params.add("preferredDescription=${java.net.URLEncoder.encode(it, "UTF-8")}") }
            startPositionMs?.let { params.add("startPositionMs=$it") }
            if (isLiveStream) params.add("isLiveStream=true")
            fenixEpisodeId?.let { params.add("fenixEpisodeId=$it") }
            return if (params.isNotEmpty()) "$base?${params.joinToString("&")}" else base
        }
    }
}

/**
 * Main navigation graph
 */
@Composable
fun AppNavigation(
    navController: NavHostController = rememberNavController(),
    startDestination: String = Screen.Login.route,
    preloadedCategories: List<Category> = emptyList(),
    preloadedHeroItem: MediaItem? = null,
    preloadedHeroLogoUrl: String? = null,
    preloadedLogoCache: Map<String, String> = emptyMap(),
    currentProfile: Profile? = null,
    isCloudConnected: Boolean = false,
    onSwitchProfile: () -> Unit = {},
    onConnectTelegram: () -> Unit = {},
    onTvFullscreenChanged: (Boolean) -> Unit = {},
    onExitApp: () -> Unit = {}
) {
    val telegramViewModel: com.arflix.tv.ui.screens.settings.telegram.TelegramSettingsViewModel = hiltViewModel()
    val telegramRepository = telegramViewModel.telegramRepository
    val navigateTopLevel: (String) -> Unit = { route ->
        navController.navigate(route) {
            popUpTo(Screen.Home.route) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    val navigateHome: () -> Unit = {
        // Navigate to Home clearing the entire back stack above it.
        // Uses navigate() instead of popBackStack() because popBackStack can
        // silently fail if Home is not found, and restoreState on other
        // navigateTopLevel calls can bring back stale Details pages.
        navController.navigate(Screen.Home.route) {
            popUpTo(Screen.Home.route) { inclusive = true; saveState = false }
            launchSingleTop = true
            restoreState = false
        }
    }

    val isKids = currentProfile?.isKidsProfile == true
    val parentPin = currentProfile?.parentPin ?: currentProfile?.pin

    var showExitKidsPinDialog by remember { mutableStateOf(false) }
    var exitKidsPinError by remember { mutableStateOf("") }
    val context = LocalContext.current

    val protectedOnSwitchProfile: () -> Unit = {
        if (isKids && !parentPin.isNullOrEmpty()) {
            exitKidsPinError = ""
            showExitKidsPinDialog = true
        } else {
            onSwitchProfile()
            navController.navigate(Screen.ProfileSelection.route) {
                popUpTo(Screen.Home.route) { inclusive = true }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        NavHost(
            navController = navController,
            startDestination = startDestination,
            // Premium screen transitions — subtle fade + slight depth push.
            // Netflix TV uses ~250ms fade; this is tuned for Android TV's 60fps.
            // Pure crossfade — no horizontal slides (those feel mobile, not TV).
            // Netflix TV uses ~250ms crossfade for all screen transitions.
            enterTransition = { fadeIn(androidx.compose.animation.core.tween(250)) },
            exitTransition = { fadeOut(androidx.compose.animation.core.tween(200)) },
            popEnterTransition = { fadeIn(androidx.compose.animation.core.tween(250)) },
            popExitTransition = { fadeOut(androidx.compose.animation.core.tween(200)) }
        ) {
        // Login screen
        composable(Screen.Login.route) {
            LoginScreen(
                onLoginSuccess = {
                    navController.navigate(Screen.Home.route) {
                        popUpTo(Screen.Login.route) { inclusive = true }
                    }
                }
            )
        }

        // Home screen
        composable(Screen.Home.route) {
            HomeScreen(
                preloadedCategories = preloadedCategories,
                preloadedHeroItem = preloadedHeroItem,
                preloadedHeroLogoUrl = preloadedHeroLogoUrl,
                preloadedLogoCache = preloadedLogoCache,
                currentProfile = currentProfile,
                onNavigateToDetails = { mediaType, mediaId, initialSeason, initialEpisode ->
                    navController.navigate(Screen.Details.createRoute(mediaType, mediaId, initialSeason, initialEpisode))
                },
                onNavigateToCollection = { catalogId ->
                    navController.navigate(Screen.CollectionDetails.createRoute(catalogId))
                },
                onNavigateToCategory = { categoryId ->
                    if (categoryId == com.arflix.tv.ui.screens.home.HOME_WATCHLIST_ROW_ID) {
                        navController.navigate(Screen.Watchlist.route) {
                            popUpTo(Screen.Home.route) { saveState = true }
                            launchSingleTop = true
                            restoreState = false
                        }
                    } else {
                        navController.navigate(Screen.CategoryViewAll.createRoute(categoryId))
                    }
                },
                onNavigateToSearch = {
                    navigateTopLevel(Screen.Search.route)
                },
                onNavigateToWatchlist = {
                    navigateTopLevel(Screen.Watchlist.route)
                },
                onNavigateToDownloads = {
                    navigateTopLevel(Screen.Downloads.route)
                },
                onNavigateToTv = { channelId, streamUrl ->
                    navigateTopLevel(Screen.Tv.createRoute(channelId, streamUrl))
                },
                onNavigateToPlayer = { mediaType, mediaId, streamUrl, preferredAddonId, preferredSourceName ->
                    navController.navigate(
                        Screen.Player.createRoute(
                            mediaType = mediaType,
                            mediaId = mediaId,
                            streamUrl = streamUrl,
                            preferredAddonId = preferredAddonId,
                            preferredSourceName = preferredSourceName,
                            isLiveStream = true
                        )
                    )
                },
                onNavigateToSettings = {
                    navigateTopLevel(Screen.Settings.route)
                },
                onNavigateToTelegram = onConnectTelegram,
                onSwitchProfile = protectedOnSwitchProfile,
                onExitApp = onExitApp
            )
        }

        // Search screen
        composable(Screen.Search.route) {
            SearchScreen(
                currentProfile = currentProfile,
                onNavigateToDetails = { mediaType, mediaId ->
                    navController.navigate(Screen.Details.createRoute(mediaType, mediaId))
                },
                onNavigateToHome = { navigateHome() },
                onNavigateToWatchlist = { navigateTopLevel(Screen.Watchlist.route) },
                onNavigateToDownloads = { navigateTopLevel(Screen.Downloads.route) },
                onNavigateToTv = { navigateTopLevel(Screen.Tv.createRoute()) },
                onNavigateToSettings = { navigateTopLevel(Screen.Settings.route) },
                onSwitchProfile = protectedOnSwitchProfile,
                onBack = { navigateHome() }
            )
        }

        // Watchlist screen
        composable(Screen.Watchlist.route) {
            WatchlistScreen(
                currentProfile = currentProfile,
                onNavigateToDetails = { mediaType, mediaId ->
                    navController.navigate(Screen.Details.createRoute(mediaType, mediaId))
                },
                onNavigateToHome = { navigateHome() },
                onNavigateToSearch = { navigateTopLevel(Screen.Search.route) },
                onNavigateToDownloads = { navigateTopLevel(Screen.Downloads.route) },
                onNavigateToTv = { navigateTopLevel(Screen.Tv.createRoute()) },
                onNavigateToSettings = { navigateTopLevel(Screen.Settings.route) },
                onSwitchProfile = protectedOnSwitchProfile,
                onBack = { navigateHome() }
            )
        }

        // TV screen (stub / no-op)
        composable(
            route = Screen.Tv.route,
            arguments = listOf(
                navArgument("channelId") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("streamUrl") { type = NavType.StringType; nullable = true; defaultValue = null }
            )
        ) {
            LaunchedEffect(Unit) {
                navController.popBackStack()
            }
        }

        // Downloads screen
        composable(Screen.Downloads.route) {
            DownloadsScreen(
                currentProfile = currentProfile,
                onNavigateToPlayer = { mediaType, mediaId, season, episode, url, title ->
                    navController.navigate(
                        Screen.Player.createRoute(
                            mediaType = mediaType,
                            mediaId = mediaId,
                            seasonNumber = season,
                            episodeNumber = episode,
                            streamUrl = url,
                            preferredSourceName = title
                        )
                    )
                },
                onNavigateToHome = { navigateHome() },
                onNavigateToSearch = { navigateTopLevel(Screen.Search.route) },
                onNavigateToWatchlist = { navigateTopLevel(Screen.Watchlist.route) },
                onNavigateToTv = { navigateTopLevel(Screen.Tv.createRoute()) },
                onNavigateToSettings = { navigateTopLevel(Screen.Settings.route) },
                onSwitchProfile = protectedOnSwitchProfile,
                onBack = { navigateHome() }
            )
        }

        // Settings screen
        composable(
            route = "settings?autoCloudAuth={autoCloudAuth}&initialSection={initialSection}&installPackUrl={installPackUrl}",
            arguments = listOf(
                navArgument("autoCloudAuth") {
                    type = NavType.BoolType
                    defaultValue = false
                },
                navArgument("initialSection") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("installPackUrl") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            )
        ) { backStackEntry ->
            val autoCloudAuth = backStackEntry.arguments?.getBoolean("autoCloudAuth") ?: false
            val initialSection = backStackEntry.arguments?.getString("initialSection")
            val installPackUrl = backStackEntry.arguments?.getString("installPackUrl")

            var settingsUnlocked by rememberSaveable(currentProfile?.id) { mutableStateOf(false) }
            var settingsPinError by remember { mutableStateOf("") }

            if (isKids && !parentPin.isNullOrEmpty() && !settingsUnlocked) {
                PinEntryDialog(
                    title = stringResource(R.string.profile_parent_pin_enter),
                    setupHint = stringResource(R.string.profile_kids_settings_pin_hint),
                    pinError = settingsPinError,
                    onPinConfirmed = { pin ->
                        if (PinUtil.verifyPin(pin, parentPin)) {
                            settingsPinError = ""
                            settingsUnlocked = true
                        } else {
                            settingsPinError = context.getString(R.string.profile_pin_incorrect)
                        }
                    },
                    onDismiss = {
                        settingsPinError = ""
                        navController.popBackStack()
                    }
                )
            } else {
                SettingsScreen(
                    currentProfile = currentProfile,
                    autoStartCloudAuth = autoCloudAuth,
                    initialSection = initialSection,
                    installPackUrl = installPackUrl,
                    onNavigateToHome = { navigateHome() },
                    onNavigateToSearch = { navigateTopLevel(Screen.Search.route) },
                    onNavigateToTv = { navigateTopLevel(Screen.Tv.createRoute()) },
                    onNavigateToWatchlist = { navigateTopLevel(Screen.Watchlist.route) },
                    onNavigateToDownloads = { navigateTopLevel(Screen.Downloads.route) },
                    onNavigateToTelegramSettings = { navController.navigate(Screen.TelegramSettings.route) },
                    onSwitchProfile = protectedOnSwitchProfile,
                    onBack = { navController.popBackStack() }
                )
            }
        }

        // Telegram settings screen
        composable(Screen.TelegramSettings.route) {
            TelegramSettingsScreen(onBack = { navController.popBackStack() })
        }

        // Profile selection screen
        composable(Screen.ProfileSelection.route) {
            ProfileSelectionScreen(
                onProfileSelected = {
                    if (telegramRepository.isAuthenticated()) {
                        navController.navigate(Screen.Home.route) {
                            popUpTo(Screen.ProfileSelection.route) { inclusive = true }
                        }
                    } else {
                        navController.navigate(Screen.TelegramSettings.route)
                    }
                },
                onShowAddProfile = { /* Handled internally by ProfileSelectionScreen */ },
                onConnectTelegram = onConnectTelegram,
                isCloudConnected = isCloudConnected
            )
        }

        // Details screen
        composable(
            route = Screen.CollectionDetails.route,
            arguments = listOf(navArgument("catalogId") { type = NavType.StringType })
        ) { backStackEntry ->
            val catalogId = backStackEntry.arguments?.getString("catalogId").orEmpty()
            if (catalogId.isBlank()) {
                navigateHome()
                return@composable
            }
            CollectionDetailsScreen(
                catalogId = catalogId,
                currentProfile = currentProfile,
                onNavigateToDetails = { mediaType, mediaId ->
                    navController.navigate(Screen.Details.createRoute(mediaType, mediaId))
                },
                onNavigateToPlayer = { mediaType, mediaId, streamUrl, preferredAddonId, preferredSourceName ->
                    navController.navigate(
                        Screen.Player.createRoute(
                            mediaType = mediaType,
                            mediaId = mediaId,
                            streamUrl = streamUrl,
                            preferredAddonId = preferredAddonId,
                            preferredSourceName = preferredSourceName,
                            isLiveStream = true
                        )
                    )
                },
                onNavigateToHome = { navigateHome() },
                onNavigateToSearch = {
                    if (!navController.popBackStack(Screen.Search.route, inclusive = false)) {
                        navigateTopLevel(Screen.Search.route)
                    }
                },
                onNavigateToWatchlist = {
                    if (!navController.popBackStack(Screen.Watchlist.route, inclusive = false)) {
                        navigateTopLevel(Screen.Watchlist.route)
                    }
                },
                onNavigateToTv = {
                    if (!navController.popBackStack(Screen.Tv.route, inclusive = false)) {
                        navigateTopLevel(Screen.Tv.createRoute())
                    }
                },
                onNavigateToSettings = {
                    if (!navController.popBackStack(Screen.Settings.route, inclusive = false)) {
                        navigateTopLevel(Screen.Settings.route)
                    }
                },
                onBack = { navController.popBackStack() }
            )
        }

        // Home row "View all" grid. Shares Home's ViewModel so the row's pages and
        // pagination state live in one place (Home stays on the back stack underneath).
        composable(
            route = Screen.CategoryViewAll.route,
            arguments = listOf(navArgument("categoryId") { type = NavType.StringType })
        ) { backStackEntry ->
            val categoryId = backStackEntry.arguments?.getString("categoryId").orEmpty()
            val homeEntry = remember(backStackEntry) {
                runCatching { navController.getBackStackEntry(Screen.Home.route) }.getOrNull()
            }
            if (categoryId.isBlank() || homeEntry == null) {
                LaunchedEffect(Unit) { navController.popBackStack() }
                return@composable
            }
            CategoryViewAllScreen(
                categoryId = categoryId,
                viewModel = androidx.hilt.navigation.compose.hiltViewModel(homeEntry),
                onNavigateToDetails = { mediaType, mediaId ->
                    navController.navigate(Screen.Details.createRoute(mediaType, mediaId))
                },
                onBack = { navController.popBackStack() }
            )
        }

        // Details screen
        composable(
            route = Screen.Details.route,
            arguments = listOf(
                navArgument("mediaType") { type = NavType.StringType },
                navArgument("mediaId") { type = NavType.IntType },
                navArgument("initialSeason") {
                    type = NavType.IntType
                    defaultValue = -1
                },
                navArgument("initialEpisode") {
                    type = NavType.IntType
                    defaultValue = -1
                }
            )
        ) { backStackEntry ->
            val mediaTypeStr = backStackEntry.arguments?.getString("mediaType") ?: "movie"
            val mediaId = backStackEntry.arguments?.getInt("mediaId") ?: 0
            if (mediaId <= 0) {
                navigateHome()
                return@composable
            }
            val initialSeason = backStackEntry.arguments?.getInt("initialSeason")?.takeIf { it >= 0 }
            val initialEpisode = backStackEntry.arguments?.getInt("initialEpisode")?.takeIf { it >= 0 }
            val mediaType = if (mediaTypeStr == "tv") MediaType.TV else MediaType.MOVIE

            DetailsScreen(
                mediaType = mediaType,
                mediaId = mediaId,
                initialSeason = initialSeason,
                initialEpisode = initialEpisode,
                currentProfile = currentProfile,
                onNavigateToPlayer = { type, id, season, episode, imdbId, url, preferredAddonId, preferredSourceName, startPositionMs, fenixEpId ->
                    navController.navigate(
                        Screen.Player.createRoute(
                            mediaType = type,
                            mediaId = id,
                            seasonNumber = season,
                            episodeNumber = episode,
                            imdbId = imdbId,
                            streamUrl = url,
                            preferredAddonId = preferredAddonId,
                            preferredSourceName = preferredSourceName,
                            startPositionMs = startPositionMs,
                            fenixEpisodeId = fenixEpId
                        )
                    )
                },
                onNavigateToDetails = { type, id ->
                    navController.navigate(Screen.Details.createRoute(type, id))
                },
                onNavigateToCollection = { catalogId ->
                    navController.navigate(Screen.CollectionDetails.createRoute(catalogId))
                },
                onNavigateToHome = {
                    navigateHome()
                },
                onNavigateToSearch = {
                    if (!navController.popBackStack(Screen.Search.route, inclusive = false)) {
                        navigateTopLevel(Screen.Search.route)
                    }
                },
                onNavigateToTv = {
                    if (!navController.popBackStack(Screen.Tv.route, inclusive = false)) {
                        navigateTopLevel(Screen.Tv.createRoute())
                    }
                },
                onNavigateToWatchlist = {
                    if (!navController.popBackStack(Screen.Watchlist.route, inclusive = false)) {
                        navigateTopLevel(Screen.Watchlist.route)
                    }
                },
                onNavigateToDownloads = {
                    if (!navController.popBackStack(Screen.Downloads.route, inclusive = false)) {
                        navigateTopLevel(Screen.Downloads.route)
                    }
                },
                onNavigateToSettings = {
                    if (!navController.popBackStack(Screen.Settings.route, inclusive = false)) {
                        navigateTopLevel(Screen.Settings.route)
                    }
                },
                onSwitchProfile = protectedOnSwitchProfile,
                onBack = { navController.popBackStack() }
            )
        }

        // Player screen
        composable(
            route = Screen.Player.route,
            arguments = listOf(
                navArgument("mediaType") { type = NavType.StringType },
                navArgument("mediaId") { type = NavType.IntType },
                navArgument("seasonNumber") {
                    type = NavType.IntType
                    defaultValue = -1
                },
                navArgument("episodeNumber") {
                    type = NavType.IntType
                    defaultValue = -1
                },
                navArgument("imdbId") {
                    type = NavType.StringType
                    defaultValue = ""
                },
                navArgument("streamUrl") {
                    type = NavType.StringType
                    defaultValue = ""
                },
                navArgument("preferredAddonId") {
                    type = NavType.StringType
                    defaultValue = ""
                },
                navArgument("preferredSourceName") {
                    type = NavType.StringType
                    defaultValue = ""
                },
                navArgument("preferredBingeGroup") {
                    type = NavType.StringType
                    defaultValue = ""
                },
                navArgument("startPositionMs") {
                    type = NavType.LongType
                    defaultValue = -1L
                },
                navArgument("isLiveStream") {
                    type = NavType.BoolType
                    defaultValue = false
                }
            )
        ) { backStackEntry ->
            val mediaTypeStr = backStackEntry.arguments?.getString("mediaType") ?: "movie"
            val mediaId = backStackEntry.arguments?.getInt("mediaId") ?: 0
            val seasonNumber = backStackEntry.arguments?.getInt("seasonNumber")?.takeIf { it >= 0 }
            val episodeNumber = backStackEntry.arguments?.getInt("episodeNumber")?.takeIf { it >= 0 }
            val imdbId = backStackEntry.arguments?.getString("imdbId")?.takeIf { it.isNotBlank() }
            val streamUrl = backStackEntry.arguments?.getString("streamUrl")?.takeIf { it.isNotEmpty() }
            val preferredAddonId = backStackEntry.arguments?.getString("preferredAddonId")?.takeIf { it.isNotBlank() }
            val preferredSourceName = backStackEntry.arguments?.getString("preferredSourceName")?.takeIf { it.isNotBlank() }
            val preferredBingeGroup = backStackEntry.arguments?.getString("preferredBingeGroup")?.takeIf { it.isNotBlank() }
            val preferredDescription = backStackEntry.arguments?.getString("preferredDescription")?.takeIf { it.isNotBlank() }
            val startPositionMs = backStackEntry.arguments?.getLong("startPositionMs")?.takeIf { it >= 0L }
            val isLiveStream = backStackEntry.arguments?.getBoolean("isLiveStream") ?: false
            val mediaType = if (mediaTypeStr == "tv") MediaType.TV else MediaType.MOVIE
            val fenixEpisodeId = backStackEntry.arguments?.getInt("fenixEpisodeId")?.takeIf { it > 0 }

            PlayerScreen(
                mediaType = mediaType,
                mediaId = mediaId,
                seasonNumber = seasonNumber,
                episodeNumber = episodeNumber,
                imdbId = imdbId,
                streamUrl = streamUrl,
                preferredAddonId = preferredAddonId,
                preferredSourceName = preferredSourceName,
                preferredBingeGroup = preferredBingeGroup,
                preferredDescription = preferredDescription,
                startPositionMs = startPositionMs,
                isLiveStream = isLiveStream,
                fenixEpisodeId = fenixEpisodeId,
                onBack = { navController.popBackStack() },
                onPlayNext = { nextSeason, nextEpisode, nextPreferredAddonId, nextPreferredSourceName, nextPreferredBingeGroup, nextPreferredDescription ->
                    // Navigate to next episode
                    navController.navigate(
                        Screen.Player.createRoute(
                            mediaType = mediaType,
                            mediaId = mediaId,
                            seasonNumber = nextSeason,
                            episodeNumber = nextEpisode,
                            preferredAddonId = nextPreferredAddonId,
                            preferredSourceName = nextPreferredSourceName,
                            preferredBingeGroup = nextPreferredBingeGroup,
                            preferredDescription = nextPreferredDescription
                        )
                    ) {
                        popUpTo(Screen.Player.route) { inclusive = true }
                    }
                }
            )
        }
    }

    if (showExitKidsPinDialog) {
        PinEntryDialog(
            title = stringResource(R.string.profile_parent_pin_enter),
            setupHint = stringResource(R.string.profile_kids_exit_pin_hint),
            pinError = exitKidsPinError,
            onPinConfirmed = { pin ->
                if (PinUtil.verifyPin(pin, parentPin)) {
                    showExitKidsPinDialog = false
                    exitKidsPinError = ""
                    onSwitchProfile()
                    navController.navigate(Screen.ProfileSelection.route) {
                        popUpTo(Screen.Home.route) { inclusive = true }
                    }
                } else {
                    exitKidsPinError = context.getString(R.string.profile_pin_incorrect)
                }
            },
            onDismiss = {
                showExitKidsPinDialog = false
                exitKidsPinError = ""
            }
        )
    }
}
}
