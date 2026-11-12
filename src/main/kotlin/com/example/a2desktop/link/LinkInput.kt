package com.example.a2desktop.link

import java.net.InetAddress
import java.net.NetworkInterface

/**
 * Makes sense of what a person pastes into the Phone page. The phone shows an address (often TWO —
 * its Wi-Fi one and its mobile-data one) and a pairing key, and people copy them out of chat apps,
 * which add names, times and invisible direction marks. So this never insists on a tidy string: it
 * looks for the addresses and the key inside whatever was given.
 */
object LinkInput {
    // 1.2.3.4 or 1.2.3.4:5678 — with the usual colon, or the full-width one some keyboards produce.
    private val ADDRESS = Regex("""(\d{1,3}(?:\.\d{1,3}){3})(?:[:：](\d{1,5}))?""")

    // The key's alphabet has no I, O, 0 or 1: 5 groups of 4, separated by dashes and/or spaces.
    private val KEY = Regex("""[A-HJ-NP-Z2-9]{4}(?:[\s\-–—]*[A-HJ-NP-Z2-9]{4}){4}""")

    /** Invisible formatting characters (direction marks, zero-width spaces, …) that chat apps add. */
    private fun withoutInvisibles(text: String) = text.filter { Character.getType(it) != Character.FORMAT.toInt() }

    /**
     * Every local-network address found in [text], most likely first: those on the same network as
     * this PC (the one that can actually be reached), then the rest in the order given. Addresses
     * outside the local network are ignored — the app only ever talks to something on the home network.
     */
    fun addresses(text: String, thisPcAddresses: List<String> = localPcAddresses()): List<LinkAddress> {
        val found = ADDRESS.findAll(withoutInvisibles(text)).mapNotNull { match ->
            val host = match.groupValues[1]
            val port = match.groupValues[2].ifEmpty { LINK_DEFAULT_PORT.toString() }
            LinkAddress.parse("$host:$port")
        }.distinct().toList()
        fun sameNetwork(a: LinkAddress) = thisPcAddresses.any { it.substringBeforeLast('.') == a.host.substringBeforeLast('.') }
        return found.sortedBy { if (sameNetwork(it)) 0 else 1 } // stable: keeps the given order within each group
    }

    /** The pairing key found in [text], normalised (20 characters, no dashes), or null. */
    fun pairingKey(text: String): String? {
        val match = KEY.find(withoutInvisibles(text).uppercase()) ?: return null
        return LinkCrypto.normalizePairingKey(match.value.filter { it.isLetterOrDigit() })
    }

    /** This PC's own IPv4 addresses, to tell which of the phone's addresses is on the same network. */
    fun localPcAddresses(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<java.net.Inet4Address>()
            .mapNotNull { (it as InetAddress).hostAddress }
    }.getOrDefault(emptyList())
}
