package com.example.a2desktop.data

import com.example.a2desktop.logic.EmailMessage
import com.example.a2desktop.logic.sameMailbox
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// Reading the user's Gmail for the Jobs page — READ ONLY. The app asks Google for one permission, `gmail.readonly`: it can read mail and
// can't send, delete or change any. How it connects is Google's own "installed app" sign-in: the user's browser opens Google's page, the
// user signs in THERE (the app never sees a password) and agrees, and Google hands a one-time code back to a tiny listener the app runs on
// this PC for a minute (127.0.0.1 only). The code is swapped for a long-lasting key kept in `%APPDATA%\a2-desktop\gmail-tokens.json`.
// Mail goes straight from Google to this PC; nothing is sent anywhere else. With no client file and no connected account, nothing here ever
// touches the internet. Everything that talks to Google takes the addresses as a parameter, so the tests run it against a fake on this PC.

/** The scope asked for: read mail, nothing else. */
const val GMAIL_READONLY_SCOPE = "https://www.googleapis.com/auth/gmail.readonly"

/** Where Google's services are. Tests point these at a fake server on this PC. */
data class GoogleEndpoints(
    val authUrl: String = "https://accounts.google.com/o/oauth2/v2/auth",
    val tokenUrl: String = "https://oauth2.googleapis.com/token",
    val revokeUrl: String = "https://oauth2.googleapis.com/revoke",
    val gmailBase: String = "https://gmail.googleapis.com/gmail/v1/users/me",
)

/** What an operation gave back: its value, or a plain-words reason it failed ([expired] when Google says the key no longer works). */
sealed interface MailResult<out T> {
    data class Ok<T>(val value: T) : MailResult<T>
    data class Failed(val message: String, val expired: Boolean = false, val missing: Boolean = false) : MailResult<Nothing>
}

// ---- The client file (made by the user in Google Cloud) ----

/** The pair Google gives for a "Desktop app" client. For an installed app the secret is not really secret (Google says so); it is still kept private. */
@Serializable
data class OAuthClient(val clientId: String, val clientSecret: String)

sealed interface ClientParse {
    data class Ok(val client: OAuthClient) : ClientParse
    data class Bad(val why: String) : ClientParse
}

private val lenient = Json { ignoreUnknownKeys = true }

/** Reads the JSON Google lets you download for an OAuth client. Only a "Desktop app" client works (a web one needs fixed redirect addresses). */
fun parseClientFile(text: String): ClientParse {
    val root = runCatching { lenient.parseToJsonElement(text).jsonObject }.getOrNull()
        ?: return ClientParse.Bad("That isn't a Google client file (it should be a .json file you downloaded from Google Cloud).")
    val installed = root["installed"] as? JsonObject
    if (installed == null) {
        return if (root["web"] != null) ClientParse.Bad("That client was created as a \"Web application\". Create a new one and choose \"Desktop app\".")
        else ClientParse.Bad("That file isn't an OAuth client file (it has no \"installed\" part). Download the client's JSON again from Google Cloud.")
    }
    val id = installed["client_id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
    val secret = installed["client_secret"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
    if (id == null || secret == null) return ClientParse.Bad("That client file is missing its ID or its secret. Download it again from Google Cloud.")
    return ClientParse.Ok(OAuthClient(id, secret))
}

/** The client file as kept by the app (`gmail-client.json` in the app's folder), and the places a freshly downloaded one is looked for. */
class GmailClientFile(private val dir: File = File(System.getenv("APPDATA") ?: System.getProperty("user.home"), "a2-desktop")) {
    private val file get() = File(dir, "gmail-client.json")

    val location: File get() = file

    fun load(): OAuthClient? = runCatching { (parseClientFile(file.readText()) as? ClientParse.Ok)?.client }.getOrNull()

    /** Checks [source] and, if it is a good client file, keeps a copy of it. */
    fun import(source: File): ClientParse {
        val text = runCatching { source.readText() }.getOrNull() ?: return ClientParse.Bad("Couldn't read that file.")
        val parsed = parseClientFile(text)
        if (parsed is ClientParse.Ok) {
            dir.mkdirs()
            val temp = File(dir, "gmail-client.json.tmp")
            temp.writeText(text)
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        return parsed
    }

    fun remove() {
        file.delete()
    }

    companion object {
        /** Files in [folder] that look like the download Google gives (`client_secret_….json`), newest first. */
        fun findDownloads(folder: File?): List<File> =
            folder?.listFiles { f -> f.isFile && Regex("""(?i)client_secret.*\.json""").matches(f.name) }?.sortedByDescending { it.lastModified() }.orEmpty()
    }
}

// ---- Keys kept between runs ----

/** The long-lasting keys Google gave, one per mailbox. */
interface TokenStore {
    fun refreshToken(account: String): String?
    fun save(account: String, refreshToken: String)
    fun forget(account: String)
}

class MemoryTokenStore : TokenStore {
    private val keys = HashMap<String, String>()
    override fun refreshToken(account: String) = keys[account.lowercase()]
    override fun save(account: String, refreshToken: String) { keys[account.lowercase()] = refreshToken }
    override fun forget(account: String) { keys.remove(account.lowercase()) }
}

/** The keys in `gmail-tokens.json` in the app's folder: readable only by the signed-in Windows user by default, like the phone's pairing key. */
class FileTokenStore(private val file: File) : TokenStore {
    private val json = Json { ignoreUnknownKeys = true }

    private fun read(): Map<String, String> =
        runCatching { json.decodeFromString<Map<String, String>>(file.readText()) }.getOrNull().orEmpty()

    private fun write(map: Map<String, String>) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(json.encodeToString(map))
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    override fun refreshToken(account: String) = read()[account.lowercase()]
    override fun save(account: String, refreshToken: String) = write(read() + (account.lowercase() to refreshToken))
    override fun forget(account: String) = write(read() - account.lowercase())
}

// ---- Signing in ----

/** What a finished sign-in gives: the long-lasting key, a first short-lived one, and the permissions Google says were granted. */
data class Authorized(val refreshToken: String, val accessToken: String, val expiresInSeconds: Long, val scopes: String)

data class AccessToken(val token: String, val expiresInSeconds: Long)

@Serializable
private data class TokenResponse(
    @SerialName("access_token") val accessToken: String? = null,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("expires_in") val expiresIn: Long = 3600,
    val scope: String = "",
    val error: String? = null,
    @SerialName("error_description") val errorDescription: String? = null,
)

private fun form(values: Map<String, String>) =
    values.entries.joinToString("&") { "${URLEncoder.encode(it.key, StandardCharsets.UTF_8)}=${URLEncoder.encode(it.value, StandardCharsets.UTF_8)}" }

private fun parseQuery(raw: String?): Map<String, String> =
    raw.orEmpty().split('&').filter { it.contains('=') }.associate {
        URLDecoder.decode(it.substringBefore('='), StandardCharsets.UTF_8) to URLDecoder.decode(it.substringAfter('='), StandardCharsets.UTF_8)
    }

private fun base64Url(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

/** Google's "installed app" sign-in with PKCE: see the notes at the top of this file. Blocks until the user has answered in the browser (or time runs out). */
class GmailAuth(
    private val client: () -> OAuthClient?,
    private val endpoints: GoogleEndpoints = GoogleEndpoints(),
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build(),
    private val openBrowser: (String) -> Unit,
    private val waitSeconds: Long = 180,
) {
    private val random = SecureRandom()

    private fun randomText(bytes: Int) = base64Url(ByteArray(bytes).also(random::nextBytes))

    fun authorize(account: String): MailResult<Authorized> {
        val oauth = client() ?: return MailResult.Failed("Mimi doesn't have your Google client file yet.")
        val verifier = randomText(48)
        val challenge = base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(StandardCharsets.US_ASCII)))
        val state = randomText(18)
        val answer = CompletableFuture<Map<String, String>>()
        val server = try {
            HttpServer.create(InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), 0), 0)
        } catch (e: IOException) {
            return MailResult.Failed("Couldn't open a way for Google to answer on this PC.")
        }
        server.createContext("/callback") { exchange ->
            val query = parseQuery(exchange.requestURI.rawQuery)
            val good = "code" in query && "error" !in query
            val page = "<!doctype html><meta charset=utf-8><title>Mimi</title><body style=\"font-family:sans-serif;margin:3em\">" +
                (if (good) "<h2>Mimi is connected.</h2><p>You can close this tab and go back to Mimi.</p>" else "<h2>Mimi wasn't connected.</h2><p>You can close this tab and go back to Mimi.</p>") +
                "</body>"
            val bytes = page.toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            answer.complete(query)
        }
        server.start()
        try {
            val redirect = "http://127.0.0.1:${server.address.port}/callback"
            val url = endpoints.authUrl + "?" + form(
                mapOf(
                    "client_id" to oauth.clientId, "redirect_uri" to redirect, "response_type" to "code", "scope" to GMAIL_READONLY_SCOPE,
                    "code_challenge" to challenge, "code_challenge_method" to "S256", "state" to state,
                    "access_type" to "offline", "prompt" to "consent", "login_hint" to account,
                ),
            )
            try {
                openBrowser(url)
            } catch (e: Exception) {
                return MailResult.Failed("Couldn't open your browser to sign in.")
            }
            val reply = try {
                answer.get(waitSeconds, TimeUnit.SECONDS)
            } catch (e: TimeoutException) {
                return MailResult.Failed("Google didn't answer in time. Press Connect and try again.")
            }
            if (reply["state"] != state) return MailResult.Failed("Google's answer didn't match what Mimi asked, so it was ignored. Press Connect and try again.")
            reply["error"]?.let { error ->
                return MailResult.Failed(if (error == "access_denied") "Gmail wasn't connected: you didn't allow access." else "Google said no ($error).")
            }
            val code = reply["code"] ?: return MailResult.Failed("Google's answer had no code. Press Connect and try again.")
            val token = postToken(
                mapOf(
                    "code" to code, "client_id" to oauth.clientId, "client_secret" to oauth.clientSecret, "redirect_uri" to redirect,
                    "grant_type" to "authorization_code", "code_verifier" to verifier,
                ),
            )
            return when (token) {
                is MailResult.Failed -> token
                is MailResult.Ok -> {
                    val t = token.value
                    when {
                        t.accessToken == null -> MailResult.Failed("Google didn't give access. Press Connect and try again.")
                        !t.scope.split(' ').contains(GMAIL_READONLY_SCOPE) -> MailResult.Failed("Gmail wasn't connected: the permission to read mail wasn't ticked on Google's page.")
                        t.refreshToken == null -> MailResult.Failed("Google didn't give a long-lasting key. Remove Mimi at myaccount.google.com/permissions, then connect again.")
                        else -> MailResult.Ok(Authorized(t.refreshToken, t.accessToken, t.expiresIn, t.scope))
                    }
                }
            }
        } finally {
            server.stop(0)
        }
    }

    /** A new short-lived key from the long-lasting one. [MailResult.Failed.expired] when Google no longer accepts it. */
    fun refresh(refreshToken: String): MailResult<AccessToken> {
        val oauth = client() ?: return MailResult.Failed("Mimi doesn't have your Google client file.")
        return when (
            val r = postToken(mapOf("client_id" to oauth.clientId, "client_secret" to oauth.clientSecret, "refresh_token" to refreshToken, "grant_type" to "refresh_token"))
        ) {
            is MailResult.Failed -> r
            is MailResult.Ok -> r.value.accessToken?.let { MailResult.Ok(AccessToken(it, r.value.expiresIn)) } ?: MailResult.Failed("Google didn't give access.", expired = true)
        }
    }

    /** Tells Google to forget the key (best effort — the answer is not needed). */
    fun revoke(token: String) {
        runCatching {
            val request = HttpRequest.newBuilder(URI.create(endpoints.revokeUrl)).timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form(mapOf("token" to token)))).build()
            http.send(request, HttpResponse.BodyHandlers.discarding())
        }
    }

    private fun postToken(values: Map<String, String>): MailResult<TokenResponse> = try {
        val request = HttpRequest.newBuilder(URI.create(endpoints.tokenUrl)).timeout(Duration.ofSeconds(20))
            .header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form(values))).build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        val parsed = runCatching { lenient.decodeFromString<TokenResponse>(response.body()) }.getOrNull()
        when {
            response.statusCode() == 200 && parsed != null -> MailResult.Ok(parsed)
            parsed?.error == "invalid_grant" -> MailResult.Failed("Google says the connection has expired or was removed. Connect again.", expired = true)
            parsed?.error == "invalid_client" || parsed?.error == "unauthorized_client" ->
                MailResult.Failed("Google doesn't accept this client file (${parsed.error}). Check it was created as a \"Desktop app\" in the project where the Gmail API is turned on.")
            else -> MailResult.Failed("Google answered with an error (${response.statusCode()}${parsed?.error?.let { ", $it" }.orEmpty()}).")
        }
    } catch (e: IOException) {
        MailResult.Failed("Couldn't reach Google — check the internet connection.")
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        MailResult.Failed("The request was interrupted.")
    }
}

// ---- Reading mail ----

data class MailRef(val id: String, val threadId: String)

/** The few Gmail calls this app makes — all reads. */
class GmailApi(
    private val endpoints: GoogleEndpoints = GoogleEndpoints(),
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build(),
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    /** A short rest before each message is read, so a scan stays under Gmail's per-minute allowance (see [FETCH_GAP_MILLIS]). */
    private val fetchGapMillis: Long = FETCH_GAP_MILLIS,
) {
    /** The address of the signed-in mailbox. */
    fun profile(accessToken: String): MailResult<String> = when (val r = getJson("${endpoints.gmailBase}/profile", accessToken)) {
        is MailResult.Failed -> r
        is MailResult.Ok -> r.value["emailAddress"]?.jsonPrimitive?.contentOrNull?.let { MailResult.Ok(it) } ?: MailResult.Failed("Gmail didn't say whose mailbox this is.")
    }

    /** Ids of the messages matching [query], newest first, at most [max]. */
    fun listIds(accessToken: String, query: String, max: Int): MailResult<List<MailRef>> {
        val out = ArrayList<MailRef>()
        var page: String? = null
        do {
            val url = "${endpoints.gmailBase}/messages?q=${URLEncoder.encode(query, StandardCharsets.UTF_8)}&maxResults=100" + page?.let { "&pageToken=${URLEncoder.encode(it, StandardCharsets.UTF_8)}" }.orEmpty()
            when (val r = getJson(url, accessToken)) {
                is MailResult.Failed -> return r
                is MailResult.Ok -> {
                    (r.value["messages"] as? JsonArray)?.forEach { m ->
                        val o = m.jsonObject
                        val id = o["id"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                        out += MailRef(id, o["threadId"]?.jsonPrimitive?.contentOrNull ?: id)
                    }
                    page = r.value["nextPageToken"]?.jsonPrimitive?.contentOrNull
                }
            }
        } while (page != null && out.size < max)
        return MailResult.Ok(out.take(max))
    }

    /** One message, cut down to what the Jobs page needs: sender, subject, date and the first part of its text. */
    fun fetch(accessToken: String, ref: MailRef): MailResult<EmailMessage> {
        if (fetchGapMillis > 0) sleep(fetchGapMillis)
        return when (val r = getJson("${endpoints.gmailBase}/messages/${URLEncoder.encode(ref.id, StandardCharsets.UTF_8)}?format=full", accessToken)) {
            is MailResult.Failed -> r
            is MailResult.Ok -> runCatching { toMessage(ref, r.value) }.getOrElse { MailResult.Failed("A message from Gmail wasn't understood.", missing = true) }
        }
    }

    private fun toMessage(ref: MailRef, o: JsonObject): MailResult<EmailMessage> {
        val payload = o["payload"] as? JsonObject ?: return MailResult.Failed("An empty message.", missing = true)
        val headers = (payload["headers"] as? JsonArray).orEmpty().associate {
            val h = it.jsonObject
            (h["name"]?.jsonPrimitive?.contentOrNull.orEmpty().lowercase()) to h["value"]?.jsonPrimitive?.contentOrNull.orEmpty()
        }
        return MailResult.Ok(
            EmailMessage(
                id = ref.id,
                threadId = o["threadId"]?.jsonPrimitive?.contentOrNull ?: ref.threadId,
                from = headers["from"].orEmpty(),
                subject = headers["subject"].orEmpty(),
                dateMillis = o["internalDate"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L,
                text = messageText(payload).take(MAX_TEXT),
            ),
        )
    }

    private fun getJson(url: String, accessToken: String): MailResult<JsonObject> {
        var attempt = 0
        var rateWaits = 0
        while (true) {
            attempt++
            try {
                val request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer $accessToken").header("Accept", "application/json").GET().build()
                val response = http.send(request, HttpResponse.BodyHandlers.ofString())
                // Gmail lets each user spend a set number of "units" a minute (6,000 on this project; reading one email costs 5). Past that it answers 403
                // or 429 saying the quota was exceeded. That is not a refusal: the minute passes and the allowance comes back, so wait and go on.
                if ((response.statusCode() == 403 || response.statusCode() == 429) && isQuotaTrouble(response.body())) {
                    if (rateWaits >= RATE_WAITS_MILLIS.size) return MailResult.Failed(QUOTA_GIVE_UP)
                    sleep(retryAfterMillis(response) ?: RATE_WAITS_MILLIS[rateWaits])
                    rateWaits++
                    continue
                }
                when (val code = response.statusCode()) {
                    200 -> return runCatching { MailResult.Ok(lenient.parseToJsonElement(response.body()).jsonObject) }
                        .getOrElse { MailResult.Failed("Gmail's answer wasn't understood.") }
                    401 -> return MailResult.Failed("Google no longer accepts this connection. Connect again.", expired = true)
                    403 -> return MailResult.Failed("Google refused: the Gmail API may not be turned on for your project, or this account isn't allowed. (${errorReason(response.body())})")
                    404 -> return MailResult.Failed("Not found.", missing = true)
                    429, 500, 502, 503 -> if (attempt >= 3) return MailResult.Failed("Gmail is busy right now ($code). Try again in a few minutes.") else sleep(1000L * attempt)
                    else -> return MailResult.Failed("Gmail answered with an error ($code).")
                }
            } catch (e: IOException) {
                return MailResult.Failed("Couldn't reach Gmail — check the internet connection.")
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return MailResult.Failed("The request was interrupted.")
            }
        }
    }

    private fun errorReason(body: String): String =
        runCatching { lenient.parseToJsonElement(body).jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull }.getOrNull()?.take(160) ?: "no reason given"

    /** Whether Google's error [body] says a quota or rate limit was hit (as opposed to a real refusal such as the API being off). */
    private fun isQuotaTrouble(body: String): Boolean = runCatching {
        val error = lenient.parseToJsonElement(body).jsonObject["error"]?.jsonObject ?: return@runCatching false
        val reasons = (error["errors"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.get("reason")?.jsonPrimitive?.contentOrNull }
        val status = error["status"]?.jsonPrimitive?.contentOrNull
        val message = error["message"]?.jsonPrimitive?.contentOrNull.orEmpty()
        reasons.any { it in QUOTA_REASONS } || status == "RESOURCE_EXHAUSTED" || message.contains("quota exceeded", ignoreCase = true) || message.contains("rate limit", ignoreCase = true)
    }.getOrDefault(false)

    /** How long Google asked us to wait (its `Retry-After` header, in seconds), kept between a second and a minute and a half. */
    private fun retryAfterMillis(response: HttpResponse<*>): Long? =
        response.headers().firstValue("Retry-After").orElse(null)?.trim()?.toLongOrNull()?.coerceIn(1L, 90L)?.times(1000L)

    companion object {
        /** How much of a message's text is read: the start is where an application's outcome is said. */
        const val MAX_TEXT = 3000

        /**
         * The rest before reading each message: ten a second at most is 3,000 units a minute, half of Gmail's 6,000-a-minute allowance for this
         * project, so a scan of hundreds of emails doesn't run into the limit.
         */
        const val FETCH_GAP_MILLIS = 100L

        /** How long to wait each time the minute's allowance is used up (Google's `Retry-After` wins if it gives one): after the last, give up. */
        private val RATE_WAITS_MILLIS = listOf(20_000L, 40_000L, 60_000L)

        private val QUOTA_REASONS = setOf("rateLimitExceeded", "userRateLimitExceeded", "quotaExceeded")

        const val QUOTA_GIVE_UP = "Gmail's limit on how fast mail can be read was reached. What was read is kept — press Scan again in a minute."
    }
}

/** The readable text of a Gmail message: its plain-text part, or failing that its HTML with the tags taken out. */
internal fun messageText(payload: JsonObject): String {
    val plain = StringBuilder()
    val html = StringBuilder()
    fun walk(part: JsonObject) {
        val type = part["mimeType"]?.jsonPrimitive?.contentOrNull.orEmpty().lowercase()
        val data = (part["body"] as? JsonObject)?.get("data")?.jsonPrimitive?.contentOrNull
        if (data != null) {
            val text = runCatching { String(Base64.getUrlDecoder().decode(data), StandardCharsets.UTF_8) }.getOrNull().orEmpty()
            when (type) {
                "text/plain" -> plain.append(text).append('\n')
                "text/html" -> html.append(text).append('\n')
            }
        }
        (part["parts"] as? JsonArray)?.forEach { walk(it.jsonObject) }
    }
    walk(payload)
    return if (plain.isNotBlank()) plain.toString().trim() else htmlToText(html.toString())
}

internal fun htmlToText(html: String): String =
    html.replace(Regex("""(?is)<(script|style)\b.*?</\1>"""), " ")
        .replace(Regex("""(?i)<br\s*/?>|</p>|</div>|</li>|</tr>"""), ". ")
        .replace(Regex("""<[^>]+>"""), " ")
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&rsquo;", "'")
        .replace(Regex("""\s+"""), " ").trim()

// ---- Putting it together ----

/** What the Jobs page needs from a mailbox. The real one is [GmailSource]; tests use a fake. */
interface MailSource {
    /** Whether the Google client file is in place (without it nothing can connect). */
    fun clientAvailable(): Boolean

    fun isConnected(account: String): Boolean

    /** Signs in to [account] (opens the browser and waits). Fails if the user signs in as someone else. */
    fun connect(account: String): MailResult<Unit>

    fun disconnect(account: String)

    /** The newest messages matching [query] that are not in [skip] — at most [limit] — and how many more there are beyond them. */
    fun fetchNew(account: String, query: String, skip: Set<String>, limit: Int, progress: (done: Int, total: Int) -> Unit): MailResult<FetchedMail>
}

/**
 * What a pass read. [stopped] is why it ended early (Google refused something part-way) — the [messages] read before that are still good and
 * are kept, and the rest are counted in [remaining] for next time.
 */
data class FetchedMail(
    val messages: List<EmailMessage>,
    val remaining: Int,
    val stopped: String? = null,
    /**
     * Every message id Gmail listed for the search this pass — so a message read earlier that is no longer in it has been deleted from Gmail.
     * Null when that can't be trusted (the list was cut off at the limit), in which case nothing is concluded from it.
     */
    val listedIds: Set<String>? = null,
)

/** The real thing: Google sign-in, the saved keys and Gmail's own web API. */
class GmailSource(
    private val clientFile: GmailClientFile,
    private val tokens: TokenStore,
    private val endpoints: GoogleEndpoints = GoogleEndpoints(),
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build(),
    private val openBrowser: (String) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    sleep: (Long) -> Unit = { Thread.sleep(it) },
    waitSeconds: Long = 180,
    private val maxListed: Int = 2000,
    fetchGapMillis: Long = GmailApi.FETCH_GAP_MILLIS,
) : MailSource {
    private val auth = GmailAuth({ clientFile.load() }, endpoints, http, openBrowser, waitSeconds)
    private val api = GmailApi(endpoints, http, sleep, fetchGapMillis)
    private val cache = HashMap<String, Pair<String, Long>>() // account -> (short-lived key, when it runs out)

    override fun clientAvailable() = clientFile.load() != null

    override fun isConnected(account: String) = tokens.refreshToken(account) != null

    override fun connect(account: String): MailResult<Unit> {
        val authorized = when (val r = auth.authorize(account)) {
            is MailResult.Failed -> return r
            is MailResult.Ok -> r.value
        }
        val actual = when (val p = api.profile(authorized.accessToken)) {
            is MailResult.Failed -> return p
            is MailResult.Ok -> p.value
        }
        // Google may spell the same mailbox differently (gmail.com / googlemail.com, dots, +tags): that is still the right account.
        if (!sameMailbox(actual, account)) {
            auth.revoke(authorized.refreshToken)
            return MailResult.Failed("You signed in as $actual, but this section is for $account. Press Connect again and choose $account.")
        }
        tokens.save(account, authorized.refreshToken)
        cache[account.lowercase()] = authorized.accessToken to clock() + (authorized.expiresInSeconds - 60) * 1000
        return MailResult.Ok(Unit)
    }

    override fun disconnect(account: String) {
        tokens.refreshToken(account)?.let(auth::revoke)
        tokens.forget(account)
        cache.remove(account.lowercase())
    }

    private fun accessToken(account: String, forceNew: Boolean = false): MailResult<String> {
        val key = account.lowercase()
        cache[key]?.takeIf { !forceNew && it.second > clock() }?.let { return MailResult.Ok(it.first) }
        val refresh = tokens.refreshToken(account) ?: return MailResult.Failed("$account isn't connected yet.", expired = true)
        return when (val r = auth.refresh(refresh)) {
            is MailResult.Failed -> {
                if (r.expired) tokens.forget(account)
                r
            }
            is MailResult.Ok -> {
                cache[key] = r.value.token to clock() + (r.value.expiresInSeconds - 60) * 1000
                MailResult.Ok(r.value.token)
            }
        }
    }

    override fun fetchNew(account: String, query: String, skip: Set<String>, limit: Int, progress: (Int, Int) -> Unit): MailResult<FetchedMail> {
        var token = when (val t = accessToken(account)) {
            is MailResult.Failed -> return t
            is MailResult.Ok -> t.value
        }
        val listed = when (val l = api.listIds(token, query, maxListed)) {
            is MailResult.Failed -> {
                if (!l.expired) return l
                // The short-lived key ran out between calls: get a fresh one and list again, once.
                token = when (val t = accessToken(account, forceNew = true)) { is MailResult.Failed -> return t; is MailResult.Ok -> t.value }
                when (val again = api.listIds(token, query, maxListed)) { is MailResult.Failed -> return again; is MailResult.Ok -> again.value }
            }
            is MailResult.Ok -> l.value
        }
        // A list that reached the limit may have been cut short, so it says nothing about what is gone; a shorter one is the whole of what matches.
        val listedIds = if (listed.size < maxListed) listed.map { it.id }.toSet() else null
        val fresh = listed.filter { it.id !in skip }
        val batch = fresh.take(limit)
        val out = ArrayList<EmailMessage>()
        batch.forEachIndexed { index, ref ->
            progress(index, batch.size)
            var result = api.fetch(token, ref)
            if (result is MailResult.Failed && result.expired) {
                token = when (val t = accessToken(account, forceNew = true)) { is MailResult.Failed -> return t; is MailResult.Ok -> t.value }
                result = api.fetch(token, ref)
            }
            when (result) {
                is MailResult.Ok -> out += result.value
                is MailResult.Failed -> if (!result.missing) { // a message that has gone is simply skipped
                    // Nothing read yet: report the failure as it is. Otherwise keep what was read and say why it stopped (the rest wait for next time).
                    if (out.isEmpty()) return result
                    return MailResult.Ok(FetchedMail(out, remaining = fresh.size - index, stopped = result.message, listedIds = listedIds))
                }
            }
        }
        progress(batch.size, batch.size)
        return MailResult.Ok(FetchedMail(out, remaining = fresh.size - batch.size, listedIds = listedIds))
    }
}
