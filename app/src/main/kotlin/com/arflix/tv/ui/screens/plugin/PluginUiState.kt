package com.arflix.tv.ui.screens.plugin

import android.graphics.Bitmap
import com.arflix.tv.core.plugin.TestDiagnostics
import com.arflix.tv.domain.model.LocalScraperResult
import com.arflix.tv.domain.model.PluginRepository
import com.arflix.tv.domain.model.ScraperInfo

data class PluginUiState(
    val pluginsEnabled: Boolean = true,
    val groupStreamsByRepository: Boolean = false,
    val repositories: List<PluginRepository> = emptyList(),
    val scrapers: List<ScraperInfo> = emptyList(),
    val isLoading: Boolean = false,
    val isAddingRepo: Boolean = false,
    val isTesting: Boolean = false,
    val testResults: List<LocalScraperResult>? = null,
    val testDiagnostics: TestDiagnostics? = null,
    val testScraperId: String? = null,
    val errorMessage: String? = null,
    val successMessage: String? = null,
    // QR mode
    val isQrModeActive: Boolean = false,
    val qrCodeBitmap: Bitmap? = null,
    val serverUrl: String? = null,
    // Pending change from phone
    val pendingRepoChange: PendingRepoChangeInfo? = null,
    // Pending scraper enable confirmation
    val pendingScraperEnable: PendingScraperEnableInfo? = null
)

data class PendingRepoChangeInfo(
    val changeId: String,
    val proposedUrls: List<String>,
    val addedUrls: List<String>,
    val removedUrls: List<String>,
    val isApplying: Boolean = false
)

data class PendingScraperEnableInfo(
    val scraperId: String,
    val scraperName: String
)

sealed interface PluginUiEvent {
    data class AddRepository(val url: String) : PluginUiEvent
    data class RemoveRepository(val repoId: String) : PluginUiEvent
    data class RefreshRepository(val repoId: String) : PluginUiEvent
    data class ToggleScraper(val scraperId: String, val enabled: Boolean) : PluginUiEvent
    data class ToggleAllScrapersForRepo(val repoId: String, val enabled: Boolean) : PluginUiEvent
    data class TestScraper(val scraperId: String) : PluginUiEvent
    data class SetPluginsEnabled(val enabled: Boolean) : PluginUiEvent
    data class SetGroupStreamsByRepository(val enabled: Boolean) : PluginUiEvent
    data object ClearTestResults : PluginUiEvent
    data object ClearError : PluginUiEvent
    data object ClearSuccess : PluginUiEvent
    data object StartQrMode : PluginUiEvent
    data object StopQrMode : PluginUiEvent
    data object ConfirmPendingRepoChange : PluginUiEvent
    data object RejectPendingRepoChange : PluginUiEvent
    data object ConfirmPendingScraperEnable : PluginUiEvent
    data object DismissPendingScraperEnable : PluginUiEvent
    data object ResetAllPlugins : PluginUiEvent
}
