package com.arflix.tv.data.telegram

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.arflix.tv.MainActivity
import com.arflix.tv.R
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class TelegramProxyService : Service() {

    @Inject
    lateinit var proxy: TelegramStreamingProxy

    private val wakeHelper by lazy { com.arflix.tv.util.PlaybackWakeHelper(this, "ExternalProxy") }

    companion object {
        private const val TAG = "TelegramProxyService"
        private const val CHANNEL_ID = "telegram_proxy_channel"
        private const val NOTIFICATION_ID = 1001
        
        const val ACTION_START = "com.arflix.tv.START_PROXY"
        const val ACTION_STOP = "com.arflix.tv.STOP_PROXY"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        proxy.onIdleTimeout = {
            Log.i(TAG, "External player idle timeout triggered. Stopping proxy service.")
            stopProxy()
        }
    }

    override fun onDestroy() {
        proxy.onIdleTimeout = null
        wakeHelper.release()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startProxy()
            ACTION_STOP -> stopProxy()
        }
        return START_NOT_STICKY
    }

    private fun startProxy() {
        createNotificationChannel()
        val notification = createNotification()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                } else {
                    0
                }
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        
        if (!proxy.isRunning()) {
            proxy.start()
        }
        wakeHelper.acquire()
    }

    private fun stopProxy() {
        wakeHelper.release()
        proxy.stop()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager?.cancel(NOTIFICATION_ID)
        stopSelf()
        Log.i(TAG, "TelegramProxyService stopped and notification removed")
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        stopProxy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Telegram Streaming Proxy",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Mantém o proxy de vídeo do Telegram ativo para player externo"
                setShowBadge(true)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
        }
    }

    private fun createNotification(): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, TelegramProxyService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Fênix +")
            .setContentText("Reproduzindo em Player Externo")
            .setSmallIcon(R.drawable.ic_notification_external_player)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, "Parar", stopPendingIntent)
            .build()
    }
}
