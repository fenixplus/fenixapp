package com.arflix.tv.util

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.util.Log

/**
 * Manages WakeLock and WifiLock to keep CPU and Wi-Fi interface running at
 * maximum performance without entering power-saving sleep during Google Cast streaming
 * or external player playback.
 */
class PlaybackWakeHelper(private val context: Context, private val tag: String) {

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    @Synchronized
    fun acquire() {
        try {
            if (wakeLock == null) {
                val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                wakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ARVIO:$tag:WakeLock")?.apply {
                    setReferenceCounted(false)
                }
            }
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire(4 * 60 * 60 * 1000L) // Safety cap of 4 hours
                Log.d("PlaybackWakeHelper", "[$tag] WakeLock acquired")
            }

            if (wifiLock == null) {
                val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                } else {
                    @Suppress("DEPRECATION")
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF
                }
                wifiLock = wifiManager?.createWifiLock(mode, "ARVIO:$tag:WifiLock")?.apply {
                    setReferenceCounted(false)
                }
            }
            if (wifiLock?.isHeld == false) {
                wifiLock?.acquire()
                Log.d("PlaybackWakeHelper", "[$tag] WifiLock acquired")
            }
        } catch (e: Exception) {
            Log.w("PlaybackWakeHelper", "Failed to acquire locks for $tag: ${e.message}")
        }
    }

    @Synchronized
    fun release() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                Log.d("PlaybackWakeHelper", "[$tag] WakeLock released")
            }
            if (wifiLock?.isHeld == true) {
                wifiLock?.release()
                Log.d("PlaybackWakeHelper", "[$tag] WifiLock released")
            }
        } catch (e: Exception) {
            Log.w("PlaybackWakeHelper", "Failed to release locks for $tag: ${e.message}")
        }
    }
}
