package com.arflix.tv.ui.screens.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arflix.tv.data.download.DownloadItem
import com.arflix.tv.data.download.DownloadManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class DownloadsUiState(
    val items: List<DownloadItem> = emptyList(),
    val totalDownloadsBytes: Long = 0L,
    val availableSpaceBytes: Long = 0L,
    val totalDownloadsFormatted: String = "0 MB",
    val availableSpaceFormatted: String = "0 MB"
)

@HiltViewModel
class DownloadsViewModel @Inject constructor(
    private val downloadManager: DownloadManager
) : ViewModel() {

    val uiState: StateFlow<DownloadsUiState> = downloadManager.downloads
        .map { list ->
            val totalBytes = downloadManager.getTotalDownloadsBytes()
            val freeBytes = downloadManager.getAvailableStorageBytes()
            DownloadsUiState(
                items = list,
                totalDownloadsBytes = totalBytes,
                availableSpaceBytes = freeBytes,
                totalDownloadsFormatted = downloadManager.formatBytes(totalBytes),
                availableSpaceFormatted = downloadManager.formatBytes(freeBytes)
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
