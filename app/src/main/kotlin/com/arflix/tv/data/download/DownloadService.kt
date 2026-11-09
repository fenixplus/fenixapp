package com.arflix.tv.data.download

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat

class DownloadService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val title = intent?.getStringExtra(EXTRA_TITLE) ?: "Download em andamento"
        val notificationHelper = DownloadNotificationHelper(this)
        val notification = notificationHelper.buildInitialForegroundNotification(title)

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    DownloadNotificationHelper.NOTIFICATION_ID_PROGRESS,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(DownloadNotificationHelper.NOTIFICATION_ID_PROGRESS, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed starting foreground service", e)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "DownloadService destroyed")
    }

    companion object {
        private const val TAG = "DownloadService"
        const val EXTRA_TITLE = "extra_download_title"

        fun start(context: Context, title: String) {
            val intent = Intent(context, DownloadService::class.java).apply {
                putExtra(EXTRA_TITLE, title)
            }
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                Log.w(TAG, "Could not start foreground service, attempting startService", e)
                runCatching { context.startService(intent) }
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, DownloadService::class.java)
            runCatching { context.stopService(intent) }
        }
    }
}
