package com.garuda.livestream

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import javax.net.SocketFactory

data class RtspRoute(val networkKey: String?, val socketFactory: SocketFactory?)

object RtspNetwork {
    /** Interleaved RTP over TCP lets the local Wi-Fi socket factory route RTSP media on Wi-Fi
     * while WebRTC sockets continue using Android's default Internet network. */
    fun select(context: Context, host: String): RtspRoute {
        if (!isLocalTarget(host)) return RtspRoute("default", null)
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val local = cm.allNetworks.firstOrNull { network ->
            val caps = cm.getNetworkCapabilities(network) ?: return@firstOrNull false
            (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        }
        return RtspRoute(local?.toString(), local?.socketFactory)
    }

    private fun isLocalTarget(host: String): Boolean {
        val value = host.lowercase()
        if (value == "localhost" || value.endsWith(".local") || !value.contains('.')) return true
        if (value.contains(':')) {
            val bytes = runCatching { java.net.InetAddress.getByName(value).address }.getOrNull() ?: return false
            return bytes.size == 16 && (bytes[0].toInt() and 0xfe) == 0xfc ||
                bytes.size == 16 && (bytes[0].toInt() and 0xff) == 0xfe && (bytes[1].toInt() and 0xc0) == 0x80 ||
                bytes.size == 16 && bytes.take(15).all { it == 0.toByte() } && bytes[15] == 1.toByte()
        }
        val octets = value.split('.').mapNotNull { it.toIntOrNull() }
        if (octets.size != 4 || value.split('.').size != 4 || octets.any { it !in 0..255 }) return false
        return octets[0] == 10 || octets[0] == 127 || octets[0] == 169 && octets[1] == 254 ||
            octets[0] == 192 && octets[1] == 168 || octets[0] == 172 && octets[1] in 16..31
    }
}
