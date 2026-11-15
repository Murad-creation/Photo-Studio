package com.smartscan.photostudio.core.transfer

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.NetworkInterface

data class LanInfo(val connected: Boolean, val ip: String?)

object NetInfo {
    /** The phone's IPv4 address on Wi-Fi / Ethernet, or on its own hotspot interface. */
    fun current(ctx: Context): LanInfo {
        runCatching {
            val cm = ctx.getSystemService(ConnectivityManager::class.java)
            val net = cm.activeNetwork
            val caps = net?.let { cm.getNetworkCapabilities(it) }
            if (net != null && caps != null &&
                (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
            ) {
                val ip = cm.getLinkProperties(net)?.linkAddresses?.map { it.address }
                    ?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress
                if (ip != null) return LanInfo(true, ip)
            }
        }
        val hotspot = runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList()
                ?.filter { it.isUp && !it.isLoopback && (it.name.startsWith("wlan") || it.name.startsWith("ap") || it.name.startsWith("swlan")) }
                ?.flatMap { it.inetAddresses.toList() }
                ?.firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.hostAddress
        }.getOrNull()
        return if (hotspot != null) LanInfo(true, hotspot) else LanInfo(false, null)
    }
}
