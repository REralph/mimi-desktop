package com.example.a2desktop.link

// COPY of the phone project's link/QrPairing.kt (only the package line differs). Keep the two in step (the interop test fails if they drift).

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * What the PC's pairing QR code says: `mimi-pair://pair?v=1&k=<key>&n=<PC name>`. The PC makes a fresh pairing key, shows it as this
 * code, and the phone adopts it when scanned; the PC then finds the phone on the network by itself (see the desktop's PhoneFinder) and
 * proves it has the same key. Pure text work — no Android APIs — and an identical copy of the desktop's `QrPairing`, so the two always
 * agree on the format.
 */
object QrPairing {
    const val SCHEME_PREFIX = "mimi-pair://pair?"

    class Pairing(val key: String, val pcName: String?)

    fun encode(normalizedKey: String, pcName: String): String =
        SCHEME_PREFIX + "v=1&k=" + normalizedKey + "&n=" + URLEncoder.encode(pcName.take(60), "UTF-8")

    /** Null unless [text] is a Mimi pairing code carrying a well-formed key. */
    fun parse(text: String): Pairing? {
        val trimmed = text.trim()
        if (!trimmed.startsWith(SCHEME_PREFIX)) return null
        val fields = trimmed.removePrefix(SCHEME_PREFIX).split('&').mapNotNull { part ->
            val index = part.indexOf('=')
            if (index <= 0) null else part.substring(0, index) to part.substring(index + 1)
        }.toMap()
        if (fields["v"] != "1") return null
        val key = LinkCrypto.normalizePairingKey(fields["k"] ?: return null) ?: return null
        val name = fields["n"]?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull() }?.trim()?.takeIf { it.isNotEmpty() }
        return Pairing(key, name)
    }
}
