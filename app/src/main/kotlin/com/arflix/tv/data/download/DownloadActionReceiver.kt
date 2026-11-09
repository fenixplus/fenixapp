package com.arflix.tv.data.download

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class DownloadActionReceiver : BroadcastReceiver() {

    @Inject
    lateinit var downloadManager: DownloadManager

    override fun onReceive(context: Context, intent: Intent) {
        val downloadId = intent.getStringExtra(EXTRA_DOWNLOAD_ID) ?: return
        Log.i(TAG, "Received download action: ${intent.action} for id: $downloadId")
        when (intent.action) {
            ACTION_PAUSE -> downloadManager.pauseDownload(downloadId)
            ACTION_RESUME -> downloadManager.resumeDownload(downloadId)
            ACTION_CANCEL -> downloadManager.deleteDownload(downloadId)
        }
    }

    companion object {
        private const val TAG = "DownloadActionReceiver"
        const val ACTION_PAUSE = "com.fenix.plus.ACTION_DOWNLOAD_PAUSE"
        const val ACTION_RESUME = "com.fenix.plus.ACTION_DOWNLOAD_RESUME"
        const val ACTION_CANCEL = "com.fenix.plus.ACTION_DOWNLOAD_CANCEL"
        const val EXTRA_DOWNLOAD_ID = "download_id"
    }
}
