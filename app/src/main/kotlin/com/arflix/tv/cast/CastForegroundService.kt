package com.arflix.tv.cast

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
import com.arflix.tv.util.PlaybackWakeHelper
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@AndroidEntryPoint
class CastForegroundService : Service() {

    private val wakeHelper by lazy { PlaybackWakeHelper(this, "CastForegroundService") }
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var heartbeatJob: Job? = null

    companion object {
        private const val TAG = "CastForegroundService"
        private const val CHANNEL_ID = "cast_playback_channel"
        private const val NOTIFICATION_ID = 1002

        const val ACTION_START = "com.arflix.tv.cast.START"
        const val ACTION_STOP = "com.arflix.tv.cast.STOP"
        const val ACTION_DISCONNECT = "com.arflix.tv.cast.DISCONNECT"

        const val EXTRA_DEVICE_NAME = "extra_device_name"
        const val EXTRA_MEDIA_TITLE = "extra_media_title"

        fun start(context: Context, deviceName: String?, mediaTitle: String?) {
            val intent = Intent(context, CastForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_DEVICE_NAME, deviceName)
                putExtra(EXTRA_MEDIA_TITLE, mediaTitle)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, CastForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val device = intent.getStringExtra(EXTRA_DEVICE_NAME) ?: "TV"
                val title = intent.getStringExtra(EXTRA_MEDIA_TITLE) ?: "Reproduzindo..."
                startCastForeground(device, title)
            }
            ACTION_DISCONNECT -> {
                disconnectAndStop()
            }
            ACTION_STOP -> {
                stopCastForeground()
            }
        }
        return START_NOT_STICKY
    }

    private fun startCastForeground(deviceName: String, mediaTitle: String) {
        createNotificationChannel()
        val notification = createNotification(deviceName, mediaTitle)

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
        wakeHelper.acquire()
        startHeartbeat()
        Log.i(TAG, "CastForegroundService started in foreground for device=$deviceName")
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = serviceScope.launch {
            while (isActive) {
                delay(20_000L) // Envia a cada 20 segundos para manter o CAF e o socket ativos
                try {
                    val entryPoint = EntryPointAccessors.fromApplication(
                        applicationContext,
                        CastManagerEntryPoint::class.java
                    )
                    entryPoint.castManager().sendKeepAlive()
                    Log.d(TAG, "Cast keep-alive heartbeat sent to receiver")
                } catch (e: Exception) {
                    Log.w(TAG, "Cast keep-alive heartbeat failed", e)
                }
            }
        }
    }

    private fun disconnectAndStop() {
        Log.i(TAG, "User clicked disconnect on Cast notification")
        try {
            val entryPoint = EntryPointAccessors.fromApplication(
                applicationContext,
                CastManagerEntryPoint::class.java
            )
            entryPoint.castManager().disconnect()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to disconnect cast via entry point", e)
        }
        stopCastForeground()
    }

    private fun stopCastForeground() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        wakeHelper.release()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager?.cancel(NOTIFICATION_ID)
        stopSelf()
        Log.i(TAG, "CastForegroundService stopped and notification removed")
    }

    override fun onDestroy() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        serviceScope.cancel()
        wakeHelper.release()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Transmissão na TV",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Mantém a transmissão contínua de vídeo para a TV"
                setShowBadge(true)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(deviceName: String, mediaTitle: String): Notification {
        val appIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            appIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val disconnectIntent = Intent(this, CastForegroundService::class.java).apply {
            action = ACTION_DISCONNECT
        }
        val disconnectPendingIntent = PendingIntent.getService(
            this,
            2,
            disconnectIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Transmitindo para $deviceName")
            .setContentText(mediaTitle)
            .setSmallIcon(R.drawable.ic_notification_cast)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, "Desconectar", disconnectPendingIntent)
            .build()
    }
}
