package com.example.a2desktop.link

import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** One IPv4 network this PC is on: its own [address] and how many leading bits are the network ([prefixLength]). */
data class LocalSubnet(val address: String, val prefixLength: Int)

/** What a Mimi phone answers to `GET /v1/ping`: [keyId] is the fingerprint of its pairing key (null from an older phone app). */
class PingReply(val keyId: String?)

/** A Mimi phone found on the network. */
class FoundPhone(val address: LinkAddress, val keyId: String?)

/** This PC's networks, as far as finding the phone is concerned. */
object LocalNetworks {
    private val VIRTUAL = Regex("(?i)(tun|tap|wireguard|wintun|proton|nordlynx|vpn|mullvad|openvpn|vethernet|virtual|vmware|hyper-v|loopback|bluetooth|pseudo)")

    /** True for a VPN or virtual adapter: not a network the phone can be on. */
    fun isVirtual(name: String, displayName: String): Boolean = VIRTUAL.containsMatchIn(name) || VIRTUAL.containsMatchIn(displayName)

    /** The real (not VPN or virtual) IPv4 networks this PC is on, private addresses only. */
    fun current(): List<LocalSubnet> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && !isVirtual(it.name, it.displayName.orEmpty()) }
            .flatMap { iface ->
                iface.interfaceAddresses
                    .filter { it.address is Inet4Address && it.address.isSiteLocalAddress }
                    .map { LocalSubnet(it.address.hostAddress!!, it.networkPrefixLength.toInt()) }
            }
            .distinct()
    }.getOrDefault(emptyList())

    /** True while a VPN adapter is up on this PC: a VPN can stop the PC reaching devices on the home network. */
    fun vpnActive(): Boolean = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().any { it.isUp && !it.isLoopback && VIRTUAL.containsMatchIn(it.name + " " + it.displayName.orEmpty()) && !isPlainVirtualMachine(it) }
    }.getOrDefault(false)

    private fun isPlainVirtualMachine(iface: NetworkInterface): Boolean {
        val text = (iface.name + " " + iface.displayName.orEmpty()).lowercase()
        return listOf("vethernet", "vmware", "hyper-v", "virtualbox", "bluetooth", "loopback", "pseudo").any { it in text }
    }

    /** Every host address of [subnet] other than this PC's own, the network and the broadcast address. A network larger than /22 is cut down to the /24 around this PC's address. */
    fun hostsOf(subnet: LocalSubnet): List<String> {
        val own = toInt(subnet.address) ?: return emptyList()
        val prefix = if (subnet.prefixLength in 22..30) subnet.prefixLength else if (subnet.prefixLength in 1..21) 24 else return emptyList()
        val mask = (-1 shl (32 - prefix))
        val network = own and mask
        val broadcast = network or mask.inv()
        return ((network + 1) until broadcast).filter { it != own }.map(::toText)
    }

    private fun toInt(text: String): Int? {
        val parts = text.split('.').map { it.toIntOrNull() ?: return null }
        if (parts.size != 4 || parts.any { it !in 0..255 }) return null
        return (parts[0] shl 24) or (parts[1] shl 16) or (parts[2] shl 8) or parts[3]
    }

    private fun toText(value: Int) = "${value ushr 24 and 255}.${value ushr 16 and 255}.${value ushr 8 and 255}.${value and 255}"
}

/** Asks one address whether a Mimi phone is listening there. Pure sockets, no HTTP library: quick to give up on an address nobody answers. */
object PhonePing {
    private val APP = Regex(""""app"\s*:\s*"a2"""")
    private val KID = Regex(""""kid"\s*:\s*"([0-9a-f]{16})"""")

    fun ping(address: LinkAddress, connectMillis: Int = 400, readMillis: Int = 1_500): PingReply? = try {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(address.host, address.port), connectMillis)
            socket.soTimeout = readMillis
            socket.getOutputStream().apply {
                write("GET /v1/ping HTTP/1.1\r\nHost: ${address.host}\r\nConnection: close\r\n\r\n".toByteArray())
                flush()
            }
            val text = socket.getInputStream().readBytes().toString(Charsets.UTF_8)
            if (APP.containsMatchIn(text)) PingReply(KID.find(text)?.groupValues?.get(1)) else null
        }
    } catch (_: Exception) {
        null
    }
}

/**
 * Finds the phone on the home network when its address has changed (a router gives out addresses that can change), by asking every host
 * of this PC's own network(s) whether a Mimi phone is listening. Only the phone's own port is tried on most hosts; every port the phone
 * may have moved to is tried on [firstHosts] (where it was last seen). The answer needs no key, so nothing secret is sent to any
 * device; [FoundPhone.keyId] says whose phone it is.
 */
class PhoneFinder(
    private val subnets: () -> List<LocalSubnet> = LocalNetworks::current,
    private val ping: (LinkAddress) -> PingReply? = { PhonePing.ping(it) },
    private val threads: Int = 48,
    private val totalMillis: Long = 10_000,
    /** The ports tried on [search]'s first hosts; the rest of the network is asked on the first of them only. */
    private val ports: IntRange = LINK_PORTS,
) {
    fun search(firstHosts: List<String> = emptyList()): List<FoundPhone> {
        val addresses = LinkedHashSet<LinkAddress>()
        firstHosts.forEach { host -> ports.forEach { addresses += LinkAddress(host, it) } }
        subnets().forEach { subnet -> LocalNetworks.hostsOf(subnet).forEach { addresses += LinkAddress(it, ports.first) } }
        if (addresses.isEmpty()) return emptyList()

        val pool = Executors.newFixedThreadPool(threads.coerceAtMost(addresses.size))
        try {
            val futures = addresses.map { address -> pool.submit(Callable { ping(address)?.let { FoundPhone(address, it.keyId) } }) }
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(totalMillis)
            return futures.mapNotNull { future ->
                val left = (deadline - System.nanoTime()).coerceAtLeast(0)
                runCatching { future.get(left, TimeUnit.NANOSECONDS) }.getOrNull()
            }
        } finally {
            pool.shutdownNow()
        }
    }

    companion object {
        /** The ports the phone's server may be on: it uses the first free one from its default upwards. */
        val LINK_PORTS = LINK_DEFAULT_PORT..LINK_DEFAULT_PORT + 9
    }
}
