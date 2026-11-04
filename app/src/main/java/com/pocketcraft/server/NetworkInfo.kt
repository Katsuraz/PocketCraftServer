package com.pocketcraft.server

import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

/** Returns a device-local address only. An address here is NOT an internet tunnel. */
object NetworkInfo {
    fun lanAddress(): String? = try {
        val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
        val ordered = interfaces.filter { it.isUp && !it.isLoopback }
            .sortedBy { if (it.name.startsWith("wlan")) 0 else if (it.name.startsWith("eth")) 1 else 2 }
        ordered.asSequence()
            .flatMap { Collections.list(it.inetAddresses).asSequence() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress && it.isSiteLocalAddress }
            ?.hostAddress
    } catch (_: Exception) { null }
}
