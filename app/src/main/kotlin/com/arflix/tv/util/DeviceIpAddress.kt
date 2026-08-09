package com.arflix.tv.util

import android.content.Context
import java.net.Inet4Address
import java.net.NetworkInterface

object DeviceIpAddress {

    fun get(context: Context): String? = try {
        NetworkInterface.getNetworkInterfaces()?.toList()
            ?.flatMap { it.inetAddresses.toList() }
            ?.firstOrNull { !it.isLoopbackAddress && it is Inet4Address }
            ?.hostAddress
    } catch (_: Exception) {
        null
    }
}
