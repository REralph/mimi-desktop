package com.example.a2desktop.link

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The security layer of Desktop link. The two apps talk plain HTTP over the home Wi-Fi, so nothing
 * relies on the network being private: everything is protected by a shared pairing key that the
 * phone shows once and the user types once on the PC.
 *
 *  - [generatePairingKey] makes a 100-bit random key (20 characters), far beyond guessing.
 *  - Two independent 256-bit keys are derived from it (HMAC-SHA256 key derivation): one signs
 *    requests, one encrypts responses.
 *  - Each request carries a timestamp, a random nonce and an HMAC-SHA256 over method + path + both,
 *    so only someone holding the key can ask, and a captured request can't be replayed later.
 *  - The snapshot (gzipped JSON) is encrypted with AES-256-GCM, bound to that request's nonce, so a
 *    captured response is unreadable, can't be altered, and can't be passed off as the answer to a
 *    different request.
 *  - The same goes for the one request that carries data, a dream saved on the desktop: its body is
 *    AES-256-GCM encrypted and bound to that request's method, path, timestamp and nonce.
 *
 * Pure JVM with no Android APIs (nonces and signatures travel as hex rather than Base64, which
 * needs API 26+), so the same code runs unchanged on the phone and the desktop.
 */
object LinkCrypto {
    private const val KEY_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" // 32 symbols, no 0/O/1/I
    private const val KEY_LENGTH = 20 // 20 symbols x 5 bits = 100 bits
    private val random = SecureRandom()

    const val MAX_CLOCK_SKEW_MILLIS = 120_000L

    /** "ABCD-EFGH-JKLM-NPQR-STUV". */
    fun generatePairingKey(): String =
        (1..KEY_LENGTH).map { KEY_ALPHABET[random.nextInt(KEY_ALPHABET.length)] }.joinToString("").chunked(4).joinToString("-")

    /** Upper-cases and strips dashes/spaces; null if the result isn't a well-formed key. */
    fun normalizePairingKey(input: String): String? {
        val cleaned = input.uppercase().filter { it != '-' && !it.isWhitespace() }
        return cleaned.takeIf { it.length == KEY_LENGTH && it.all { c -> c in KEY_ALPHABET } }
    }

    class Keys(val auth: ByteArray, val enc: ByteArray)

    fun deriveKeys(normalizedKey: String): Keys {
        val prk = hmac("a2link-v1".toByteArray(), normalizedKey.toByteArray())
        return Keys(
            auth = hmac(prk, "auth".toByteArray() + byteArrayOf(1)),
            enc = hmac(prk, "enc".toByteArray() + byteArrayOf(1)),
        )
    }

    /**
     * A short public fingerprint of the pairing key, which the phone shows in its (unauthenticated) ping reply. It lets the desktop tell
     * "this is the phone I am paired with" from another phone, without sending a wrong key to it — a wrong key counts as a failed
     * attempt, and too many lock the phone's endpoint for a minute. It is an HMAC under the key-derived signing key, so it tells
     * nobody anything about the key.
     */
    fun keyId(keys: Keys): String = hmac(keys.auth, "keyid".toByteArray()).toHex().take(16)

    /** "ABCDEFGHJKLMNPQRSTUV" -> "ABCD-EFGH-JKLM-NPQR-STUV". */
    fun formatPairingKey(normalizedKey: String): String = normalizedKey.chunked(4).joinToString("-")

    fun newNonce(): String = ByteArray(16).also(random::nextBytes).toHex()

    fun sign(keys: Keys, method: String, path: String, timeMillis: Long, nonce: String): String =
        hmac(keys.auth, signedText(method, path, timeMillis, nonce)).toHex()

    fun verify(keys: Keys, method: String, path: String, timeMillis: Long, nonce: String, signature: String): Boolean {
        val provided = signature.hexToBytesOrNull() ?: return false
        val expected = hmac(keys.auth, signedText(method, path, timeMillis, nonce))
        return MessageDigest.isEqual(provided, expected) // constant-time
    }

    /** gzip + AES-256-GCM. Output = 12-byte IV followed by ciphertext+tag. */
    fun encryptResponse(keys: Keys, path: String, requestNonce: String, plaintext: ByteArray): ByteArray =
        seal(keys, aad(path, requestNonce), plaintext)

    /** Throws if the data was tampered with, belongs to another request, or the key is wrong. */
    fun decryptResponse(keys: Keys, path: String, requestNonce: String, data: ByteArray): ByteArray =
        open(keys, aad(path, requestNonce), data)

    /** The body of a request that carries data (a dream saved on the desktop): sealed like a
     * response, but bound to the whole request — method, path, timestamp and nonce — so a captured
     * body can't be re-sent with different headers or inside another request. */
    fun encryptRequest(keys: Keys, method: String, path: String, timeMillis: Long, nonce: String, plaintext: ByteArray): ByteArray =
        seal(keys, requestAad(method, path, timeMillis, nonce), plaintext)

    /** Throws if the body was tampered with, belongs to another request, or the key is wrong. */
    fun decryptRequest(keys: Keys, method: String, path: String, timeMillis: Long, nonce: String, data: ByteArray): ByteArray =
        open(keys, requestAad(method, path, timeMillis, nonce), data)

    private fun seal(keys: Keys, aad: ByteArray, plaintext: ByteArray): ByteArray {
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keys.enc, "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD(aad)
        return iv + cipher.doFinal(gzip(plaintext))
    }

    private fun open(keys: Keys, aad: ByteArray, data: ByteArray): ByteArray {
        require(data.size > 12 + 16) { "Data too short" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keys.enc, "AES"), GCMParameterSpec(128, data.copyOfRange(0, 12)))
        cipher.updateAAD(aad)
        return gunzip(cipher.doFinal(data, 12, data.size - 12))
    }

    private fun signedText(method: String, path: String, timeMillis: Long, nonce: String): ByteArray =
        "v1\n$method\n$path\n$timeMillis\n$nonce".toByteArray()

    private fun aad(path: String, nonce: String): ByteArray = "v1|$path|$nonce".toByteArray()

    private fun requestAad(method: String, path: String, timeMillis: Long, nonce: String): ByteArray =
        "v1|req|$method|$path|$timeMillis|$nonce".toByteArray()

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun String.hexToBytesOrNull(): ByteArray? {
        if (length % 2 != 0 || !all { it in '0'..'9' || it in 'a'..'f' }) return null
        return ByteArray(length / 2) { i -> substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").also { it.init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)

    private fun gzip(data: ByteArray): ByteArray =
        ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(data) } }.toByteArray()

    private fun gunzip(data: ByteArray): ByteArray = GZIPInputStream(ByteArrayInputStream(data)).use { it.readBytes() }
}
