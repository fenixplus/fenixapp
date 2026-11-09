package com.arflix.tv.data.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.annotation.SuppressLint
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.arflix.tv.MainActivity
import com.arflix.tv.R

@SuppressLint("MissingPermission")
class DownloadNotificationHelper(private val context: Context) {

    private val notificationManager = NotificationManagerCompat.from(context)

    init {
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

            // Channel for active ongoing download progress (silent)
            val progressChannel = NotificationChannel(
                CHANNEL_PROGRESS,
                "Downloads em Andamento",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Progresso e velocidade de downloads do Fênix"
                setShowBadge(true)
                setSound(null, null)
                enableVibration(false)
            }
            manager.createNotificationChannel(progressChannel)

            // Channel for completed or failed downloads (default importance)
            val completeChannel = NotificationChannel(
                CHANNEL_COMPLETE,
                "Downloads Concluídos",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notificações de conclusão de downloads"
                setShowBadge(true)
            }
            manager.createNotificationChannel(completeChannel)
        }
    }

    private fun getAppPendingIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("navigate_to", "downloads")
        }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun getActionPendingIntent(action: String, downloadId: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, DownloadActionReceiver::class.java).apply {
            this.action = action
            putExtra(DownloadActionReceiver.EXTRA_DOWNLOAD_ID, downloadId)
        }
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun buildInitialForegroundNotification(title: String): Notification {
        return NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setContentTitle(title)
            .setContentText("Iniciando download...")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(getAppPendingIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setProgress(100, 0, true)
            .build()
    }

    fun updateProgress(item: DownloadItem) {
        val percent = (item.progress * 100).toInt().coerceIn(0, 100)
        val downloaded = formatBytesShort(item.downloadedBytes)
        val total = formatBytesShort(item.totalBytes)
        val speed = item.formattedSpeed

        val contentText = "$percent% • $downloaded / $total • $speed"
        val subText = listOfNotNull(
            item.quality.takeIf { it.isNotBlank() },
            item.audioLabel
        ).joinToString(" • ")

        val pausePendingIntent = getActionPendingIntent(
            DownloadActionReceiver.ACTION_PAUSE,
            item.id,
            101
        )
        val cancelPendingIntent = getActionPendingIntent(
            DownloadActionReceiver.ACTION_CANCEL,
            item.id,
            102
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setContentTitle(item.title)
            .setContentText(contentText)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(getAppPendingIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setProgress(100, percent, false)
            .addAction(0, "Pausar", pausePendingIntent)
            .addAction(0, "Cancelar", cancelPendingIntent)

        if (subText.isNotBlank()) {
            builder.setSubText(subText)
        }

        runCatching {
            notificationManager.notify(NOTIFICATION_ID_PROGRESS, builder.build())
        }
    }

    fun showCompleted(item: DownloadItem) {
        val notificationId = item.id.hashCode()
        val text = "${item.title} já está disponível para reprodução offline."

        val builder = NotificationCompat.Builder(context, CHANNEL_COMPLETE)
            .setContentTitle("Download Concluído")
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(getAppPendingIntent())
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)

        val subText = listOfNotNull(
            item.quality.takeIf { it.isNotBlank() },
            item.audioLabel
        ).joinToString(" • ")
        if (subText.isNotBlank()) {
            builder.setSubText(subText)
        }

        runCatching {
            notificationManager.notify(notificationId, builder.build())
        }
    }

    fun showFailed(item: DownloadItem, error: String?) {
        val notificationId = item.id.hashCode()
        val text = error ?: "Ocorreu um erro durante o download"

        val retryPendingIntent = getActionPendingIntent(
            DownloadActionReceiver.ACTION_RESUME,
            item.id,
            103
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_COMPLETE)
            .setContentTitle("Falha no Download: ${item.title}")
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(getAppPendingIntent())
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(0, "Tentar Novamente", retryPendingIntent)

        runCatching {
            notificationManager.notify(notificationId, builder.build())
        }
    }

    fun dismissProgress() {
        runCatching {
            notificationManager.cancel(NOTIFICATION_ID_PROGRESS)
        }
    }

    companion object {
        const val CHANNEL_PROGRESS = "fenix_downloads_progress"
        const val CHANNEL_COMPLETE = "fenix_downloads_complete"
        const val NOTIFICATION_ID_PROGRESS = 2001

        private fun formatBytesShort(bytes: Long): String = when {
            bytes <= 0L -> "0 MB"
            bytes < 1024L * 1024L -> "%.0f KB".format(java.util.Locale.US, bytes / 1024.0)
            bytes < 1024L * 1024L * 1024L -> "%.0f MB".format(java.util.Locale.US, bytes / (1024.0 * 1024.0))
            else -> "%.1f GB".format(java.util.Locale.US, bytes / (1024.0 * 1024.0 * 1024.0))
        }
    }
}
