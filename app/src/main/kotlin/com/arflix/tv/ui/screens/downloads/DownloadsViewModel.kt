package com.arflix.tv.ui.screens.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arflix.tv.data.download.DownloadItem
import com.arflix.tv.data.download.DownloadManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import com.arflix.tv.network.NetworkMonitor

data class DownloadsUiState(
    val items: List<DownloadItem> = emptyList(),
    val totalDownloadsBytes: Long = 0L,
    val availableSpaceBytes: Long = 0L,
    val totalDownloadsFormatted: String = "0 MB",
    val availableSpaceFormatted: String = "0 MB",
    val isOffline: Boolean = false
)

@HiltViewModel
class DownloadsViewModel @Inject constructor(
    private val downloadManager: DownloadManager,
    private val networkMonitor: NetworkMonitor
) : ViewModel() {

    val uiState: StateFlow<DownloadsUiState> = combine(
        downloadManager.downloads,
        networkMonitor.connectionState
    ) { list, isConnected ->
        val totalBytes = downloadManager.getTotalDownloadsBytes()
        val freeBytes = downloadManager.getAvailableStorageBytes()
        DownloadsUiState(
            items = list,
            totalDownloadsBytes = totalBytes,
            availableSpaceBytes = freeBytes,
            totalDownloadsFormatted = downloadManager.formatBytes(totalBytes),
            availableSpaceFormatted = downloadManager.formatBytes(freeBytes),
            isOffline = !isConnected
        )
    }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = DownloadsUiState()
        )

    fun pauseDownload(id: String) {
        downloadManager.pauseDownload(id)
    }

    fun resumeDownload(id: String) {
        downloadManager.resumeDownload(id)
    }

    fun deleteDownload(id: String) {
        downloadManager.deleteDownload(id)
    }
}
