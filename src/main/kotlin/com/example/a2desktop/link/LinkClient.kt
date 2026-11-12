package com.example.a2desktop.link

import java.io.IOException
import java.net.InetAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** The phone's address on the home network — always an IPv4 literal, never a host name. */
data class LinkAddress(val host: String, val port: Int) {
    override fun toString() = "$host:$port"

    companion object {
        /** "192.168.1.23" or "192.168.1.23:47321". Null unless it is a private-network (or this machine's
         * own loopback) IPv4 address: the desktop only ever talks to a device on the local network,
         * never to an arbitrary host. */
        fun parse(input: String): LinkAddress? {
            val text = input.trim()
            val host = text.substringBefore(':')
            val port = if (':' in text) text.substringAfter(':').toIntOrNull() ?: return null else LINK_DEFAULT_PORT
            val octets = host.split('.')
            if (octets.size != 4 || octets.any { it.isEmpty() || it.length > 3 || it.toIntOrNull() !in 0..255 }) return null
            if (port !in 1..65535) return null
            val address = InetAddress.getByName(host) // a numeric literal: no DNS lookup happens
            return if (address.isSiteLocalAddress || address.isLinkLocalAddress || address.isLoopbackAddress) LinkAddress(host, port) else null
        }
    }
}

sealed interface LinkResult {
    class Ok(val snapshot: LinkSnapshot, val rawJson: String) : LinkResult
    /** [unreachable] is true when the phone could not be reached over the network at all (rather than answering "no"). */
    class Failed(val message: String, val unreachable: Boolean = false) : LinkResult
}

sealed interface DreamSaveResult {
    /** The phone saved it; [dream] is the phone's own saved copy. */
    class Saved(val dream: LinkDream) : DreamSaveResult

    /** Not saved. [phoneRefused] is true when the phone answered and said no (the dream changed there,
     * say) rather than not being reachable at all. */
    class Failed(val message: String, val phoneRefused: Boolean = false) : DreamSaveResult
}

/** Something that can send a dream edited on the desktop to the phone — the real [LinkClient], or a fake. */
fun interface DreamWriter {
    fun save(address: LinkAddress, pairingKey: String, edit: LinkDreamEdit): DreamSaveResult
}

sealed interface EditResult {
    /** The phone made the change; [reply] says what it now holds (see [LinkEditReply]). */
    class Done(val reply: LinkEditReply) : EditResult

    /**
     * Not done. [phoneRefused] is true when the phone answered and said no; [unreachable] is true when it could not be reached over the
     * network at all — the one case where the change is kept and sent later instead of being given up.
     */
    class Failed(val message: String, val phoneRefused: Boolean = false, val unreachable: Boolean = false) : EditResult
}

/** Something that can send a habit/event/task request to the phone — the real [LinkClient], or a fake. */
fun interface EditWriter {
    fun send(address: LinkAddress, pairingKey: String, path: String, bodyJson: String): EditResult
}

/** Something that can fetch a snapshot — the real [LinkClient], or a fake in tests/previews. */
fun interface SnapshotSource {
    fun fetch(address: LinkAddress, pairingKey: String): LinkResult
}

/**
 * Asks the phone for its snapshot: a signed GET, an encrypted answer (see [LinkCrypto]). Blocking —
 * call it off the UI thread. Every failure comes back as a plain-English [LinkResult.Failed].
 */
class LinkClient(private val clock: () -> Long = System::currentTimeMillis) : SnapshotSource, DreamWriter, EditWriter {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** How a failed POST is worded: what didn't happen, what to do next, and the two special cases. */
    private class Wording(val notDone: String, val retryHint: String, val tooOld: String, val notSure: String)

    private sealed interface Posted {
        class Ok(val text: String) : Posted
        class Failed(val message: String, val unreachable: Boolean = false) : Posted
    }

    /**
     * One signed POST with an encrypted body to [path] (see [LinkCrypto]); returns the decrypted reply
     * text of a 200, or a plain-English failure. Blocking — call it off the UI thread.
     */
    private fun post(address: LinkAddress, pairingKey: String, path: String, bodyJson: String, say: Wording): Posted {
        val normalized = LinkCrypto.normalizePairingKey(pairingKey)
            ?: return Posted.Failed("That pairing key isn't valid — it is 20 letters and digits, shown on the phone.")
        val keys = LinkCrypto.deriveKeys(normalized)
        val time = clock()
        val nonce = LinkCrypto.newNonce()
        val body = LinkCrypto.encryptRequest(keys, "POST", path, time, nonce, bodyJson.toByteArray())
        val request = HttpRequest.newBuilder(URI("http://$address$path"))
            .timeout(Duration.ofSeconds(20))
            .header("X-A2-Time", time.toString())
            .header("X-A2-Nonce", nonce)
            .header("X-A2-Auth", LinkCrypto.sign(keys, "POST", path, time, nonce))
            .header("Content-Type", "application/octet-stream")
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .build()
        return try {
            val response = http.send(request, HttpResponse.BodyHandlers.ofByteArray())
            when (response.statusCode()) {
                200 -> try {
                    Posted.Ok(String(LinkCrypto.decryptResponse(keys, path, nonce, response.body()), Charsets.UTF_8))
                } catch (_: Exception) {
                    Posted.Failed(say.notSure)
                }
                401 -> Posted.Failed("The phone rejected the request — wrong pairing key, or the PC and phone clocks differ by more than 2 minutes.")
                429 -> Posted.Failed("The phone is pausing requests after several wrong attempts. Wait a minute and try again.")
                // An a2 app from before this could be sent from the desktop answers "no such thing".
                404, 405 -> Posted.Failed(say.tooOld)
                else -> Posted.Failed("Unexpected reply from the phone (HTTP ${response.statusCode()}), ${say.notDone}.")
            }
        } catch (_: IOException) {
            Posted.Failed("Couldn't reach the phone at $address, ${say.notDone}. ${say.retryHint}", unreachable = true)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            Posted.Failed("That was interrupted, ${say.notDone}.")
        }
    }

    private val dreamWording = Wording(
        notDone = "so the dream was not saved",
        retryHint = "Your text is still here — press Save again.",
        tooOld = "The Mimi app on the phone is too old to receive dreams from the desktop. Install the updated app on the phone, then press Save again — your text is still here.",
        notSure = "The phone's reply couldn't be verified, so the dream may not have been saved. Sync, then check it on the phone.",
    )

    private val editWording = Wording(
        notDone = "so that change was not made",
        retryHint = "Nothing was changed — try again.",
        tooOld = "The Mimi app on the phone is too old for that. Install the updated app on the phone, then try again.",
        notSure = "The phone's reply couldn't be verified, so that change may not have been made. Sync, then check the phone.",
    )

    /**
     * Sends a dream saved on the desktop to the phone. The phone decides whether it goes in and answers
     * with its own saved copy. Blocking — call it off the UI thread.
     */
    override fun save(address: LinkAddress, pairingKey: String, edit: LinkDreamEdit): DreamSaveResult =
        when (val posted = post(address, pairingKey, "/v1/dream", json.encodeToString(edit), dreamWording)) {
            is Posted.Failed -> DreamSaveResult.Failed(posted.message)
            is Posted.Ok -> {
                val reply = try {
                    json.decodeFromString<LinkDreamReply>(posted.text)
                } catch (_: Exception) {
                    return DreamSaveResult.Failed(dreamWording.notSure)
                }
                val saved = reply.dream
                if (reply.ok && saved != null) DreamSaveResult.Saved(saved)
                else DreamSaveResult.Failed(reply.message ?: "The phone didn't save the dream.", phoneRefused = true)
            }
        }

    /**
     * Sends a habit, event or task request ([bodyJson] is the JSON of a [LinkHabitEdit], [LinkEventCreate]
     * or [LinkTaskCreate]) to [path] on the phone, which decides and answers with a [LinkEditReply].
     * Blocking — call it off the UI thread.
     */
    override fun send(address: LinkAddress, pairingKey: String, path: String, bodyJson: String): EditResult =
        when (val posted = post(address, pairingKey, path, bodyJson, editWording)) {
            is Posted.Failed -> EditResult.Failed(posted.message, unreachable = posted.unreachable)
            is Posted.Ok -> {
                val reply = try {
                    json.decodeFromString<LinkEditReply>(posted.text)
                } catch (_: Exception) {
                    return EditResult.Failed(editWording.notSure)
                }
                if (reply.ok) EditResult.Done(reply)
                else EditResult.Failed(reply.message ?: "The phone didn't make that change.", phoneRefused = true)
            }
        }

    override fun fetch(address: LinkAddress, pairingKey: String): LinkResult {
        val normalized = LinkCrypto.normalizePairingKey(pairingKey)
            ?: return LinkResult.Failed("That pairing key isn't valid — it is 20 letters and digits, shown on the phone.")
        val keys = LinkCrypto.deriveKeys(normalized)
        val path = "/v1/snapshot"
        val time = clock()
        val nonce = LinkCrypto.newNonce()
        val request = HttpRequest.newBuilder(URI("http://$address$path"))
            .timeout(Duration.ofSeconds(20))
            .header("X-A2-Time", time.toString())
            .header("X-A2-Nonce", nonce)
            .header("X-A2-Auth", LinkCrypto.sign(keys, "GET", path, time, nonce))
            .GET()
            .build()
        return try {
            val response = http.send(request, HttpResponse.BodyHandlers.ofByteArray())
            when (response.statusCode()) {
                200 -> {
                    val plain = try {
                        LinkCrypto.decryptResponse(keys, path, nonce, response.body())
                    } catch (_: Exception) {
                        return LinkResult.Failed("The phone's reply couldn't be verified — check the pairing key.")
                    }
                    val text = String(plain, Charsets.UTF_8)
                    try {
                        LinkResult.Ok(json.decodeFromString<LinkSnapshot>(text), text)
                    } catch (_: Exception) {
                        LinkResult.Failed("The phone sent data this version of the desktop app doesn't understand.")
                    }
                }
                401 -> LinkResult.Failed("The phone rejected the request — wrong pairing key, or the PC and phone clocks differ by more than 2 minutes.")
                429 -> LinkResult.Failed("The phone is pausing requests after several wrong attempts. Wait a minute and try again.")
                else -> LinkResult.Failed("Unexpected reply from the phone (HTTP ${response.statusCode()}).")
            }
        } catch (_: IOException) {
            LinkResult.Failed("Couldn't reach the phone at $address.", unreachable = true)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            LinkResult.Failed("Sync was interrupted.")
        }
    }
}
