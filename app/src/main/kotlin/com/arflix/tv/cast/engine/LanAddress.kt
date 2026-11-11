package com.arflix.tv.cast.engine

import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * The device's own address on the network a receiver is on, which is what [CastServer] binds to
 * and what goes into the URLs the receiver is sent.
 *
 * With the receiver's address known (the desktop finds it by mDNS, Android's Cast framework names
 * it), the interface whose subnet holds it wins, so a laptop on Wi-Fi and a VPN, or a phone with a
 * hotspot up, still hands out the address the TV can reach. Without it, the first private IPv4
 * address on an interface that is up and not a tunnel, a bridge or a container's.
 */
object LanAddress {

    /** One address of one interface, as [pick] weighs them. */
    data class Candidate(val interfaceName: String, val address: InetAddress, val prefixLength: Int)

    fun forReceiver(receiver: InetAddress?): InetAddress? = pick(candidates(), receiver)

    fun candidates(): List<Candidate> = runCatching {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { runCatching { it.isUp && !it.isLoopback && !it.isPointToPoint }.getOrDefault(false) }
            .flatMap { nif ->
                nif.interfaceAddresses.mapNotNull { ia ->
                    val a = ia.address ?: return@mapNotNull null
                    Candidate(nif.name, a, ia.networkPrefixLength.toInt())
                }
            }
    }.getOrDefault(emptyList())

    /** The best of [candidates] for reaching [receiver]; pure, for the tests. */
    fun pick(candidates: List<Candidate>, receiver: InetAddress?): InetAddress? {
        val usable = candidates.filter { it.address is Inet4Address && CastServer.isLocalNetwork(it.address) && !it.address.isLoopbackAddress }
        if (receiver != null) {
            usable.firstOrNull { sameSubnet(it.address, receiver, it.prefixLength) }?.let { return it.address }
        }
        return usable
            .sortedBy { if (virtual(it.interfaceName)) 1 else 0 }
            .firstOrNull()?.address
    }

    fun sameSubnet(a: InetAddress, b: InetAddress, prefix: Int): Boolean {
        val x = a.address
        val y = b.address
        if (x.size != y.size || prefix <= 0) return false
        var bits = prefix.coerceAtMost(x.size * 8)
        var i = 0
        while (bits > 0) {
            val mask = if (bits >= 8) 0xFF else (0xFF shl (8 - bits)) and 0xFF
            if ((x[i].toInt() and mask) != (y[i].toInt() and mask)) return false
            bits -= 8
            i++
        }
        return true
    }

    private val virtualNames = listOf("docker", "br-", "veth", "virbr", "vmnet", "vboxnet", "tun", "tap", "zt", "tailscale", "wg", "utun", "lxc", "podman", "cni")

    private fun virtual(name: String): Boolean = virtualNames.any { name.lowercase().startsWith(it) }
}

