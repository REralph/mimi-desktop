package com.example.a2desktop

import com.example.a2desktop.data.ClientParse
import com.example.a2desktop.data.FileTokenStore
import com.example.a2desktop.data.GMAIL_READONLY_SCOPE
import com.example.a2desktop.data.GmailApi
import com.example.a2desktop.data.GmailClientFile
import com.example.a2desktop.data.GmailSource
import com.example.a2desktop.data.GoogleEndpoints
import com.example.a2desktop.data.MailResult
import com.example.a2desktop.data.MemoryTokenStore
import com.example.a2desktop.data.htmlToText
import com.example.a2desktop.data.parseClientFile
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The whole Gmail connection run against a FAKE Google on this PC (nothing leaves the machine, no account is touched): the sign-in with
 * its one-time code and PKCE check, a wrong account, a refusal, the long-lasting key being refreshed, paging, retries and reading the
 * text of a message. The "browser" is a function that answers Google's page the way the user's click would.
 */
class GmailTest {
    private val account = "alexrobinson@gmail.com"
    private val b64 = Base64.getUrlEncoder().withoutPadding()

    private fun clientJson(kind: String = "installed") =
        """{"$kind":{"client_id":"cid-123.apps.googleusercontent.com","project_id":"p","client_secret":"shh-secret","redirect_uris":["http://localhost"]}}"""

    private class FakeGoogle {
        val server: HttpServer = HttpServer.create(InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), 0), 0)
        val base get() = "http://127.0.0.1:${server.address.port}"
        var profileEmail = "alexrobinson@gmail.com"
        var grantedScope = "https://www.googleapis.com/auth/gmail.readonly"
        var challenge: String? = null
        var refreshInvalid = false
        var rejectNextGmailCall = false
        var busyTimes = 0
        var refreshes = 0

        // Gmail's per-minute allowance: the next [quotaTimes] calls are answered "quota exceeded" ([quotaBody], with [quotaCode], and a Retry-After
        // header when [quotaRetryAfter] is set); [quotaAfterFetches] lets that many messages be read and then refuses every later read the same way.
        var quotaTimes = 0
        var quotaCode = 403
        var quotaRetryAfter: String? = null
        var quotaBody = """{"error":{"code":403,"message":"Quota exceeded for quota metric 'Total Query Cost' and limit 'Units per minute per user' of service 'gmail.googleapis.com'","errors":[{"reason":"rateLimitExceeded"}],"status":"PERMISSION_DENIED"}}"""
        var quotaAfterFetches: Int? = null
        var fetches = 0

        /** A real refusal, not a quota: the next [forbiddenTimes] calls get "the Gmail API is not turned on". */
        var forbiddenTimes = 0
        val accepted = mutableSetOf<String>()
        val revoked = mutableListOf<String>()
        val messages = linkedMapOf<String, String>()
        val ghosts = mutableListOf<String>() // listed, but gone by the time they are fetched
        var listCalls = 0
        val authTokenRequests = mutableListOf<Map<String, String>>()
        val gmailAuthHeaders = mutableListOf<String>()

        private fun form(body: String) = body.split('&').filter { '=' in it }.associate {
            URLDecoder.decode(it.substringBefore('='), Charsets.UTF_8) to URLDecoder.decode(it.substringAfter('='), Charsets.UTF_8)
        }

        private fun HttpExchange.reply(code: Int, body: String) {
            val bytes = body.toByteArray()
            responseHeaders.add("Content-Type", "application/json")
            sendResponseHeaders(code, bytes.size.toLong())
            responseBody.use { it.write(bytes) }
        }

        private fun HttpExchange.quotaReply() {
            quotaRetryAfter?.let { responseHeaders.add("Retry-After", it) }
            reply(quotaCode, quotaBody)
        }

        private fun HttpExchange.authorised(): Boolean {
            val header = requestHeaders.getFirst("Authorization").orEmpty()
            gmailAuthHeaders += header
            if (rejectNextGmailCall) { rejectNextGmailCall = false; reply(401, """{"error":{"message":"expired"}}"""); return false }
            if (busyTimes > 0) { busyTimes--; reply(429, "{}"); return false }
            if (quotaTimes > 0) { quotaTimes--; quotaReply(); return false }
            if (forbiddenTimes > 0) {
                forbiddenTimes--
                reply(403, """{"error":{"code":403,"message":"Gmail API has not been used in project 123 before or it is disabled.","errors":[{"reason":"accessNotConfigured"}],"status":"PERMISSION_DENIED"}}""")
                return false
            }
            if (header.removePrefix("Bearer ") !in accepted) { reply(401, """{"error":{"message":"bad token"}}"""); return false }
            return true
        }

        init {
            server.createContext("/token") { ex ->
                val f = form(ex.requestBody.readBytes().decodeToString())
                authTokenRequests += f
                when (f["grant_type"]) {
                    "authorization_code" -> {
                        val verifier = f["code_verifier"].orEmpty()
                        val sha = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
                        if (f["code"] == "good-code" && sha == challenge && f["client_secret"] == "shh-secret") {
                            accepted += "AT1"
                            ex.reply(200, """{"access_token":"AT1","refresh_token":"RT1","expires_in":3600,"scope":"$grantedScope"}""")
                        } else ex.reply(400, """{"error":"invalid_grant"}""")
                    }
                    "refresh_token" -> {
                        if (refreshInvalid || f["refresh_token"] != "RT1") ex.reply(400, """{"error":"invalid_grant"}""")
                        else { refreshes++; val t = "AT${refreshes + 1}"; accepted += t; ex.reply(200, """{"access_token":"$t","expires_in":3600,"scope":"$grantedScope"}""") }
                    }
                    else -> ex.reply(400, """{"error":"unsupported_grant_type"}""")
                }
            }
            server.createContext("/revoke") { ex -> revoked += form(ex.requestBody.readBytes().decodeToString())["token"].orEmpty(); ex.reply(200, "{}") }
            server.createContext("/gmail/v1/users/me/profile") { ex -> if (ex.authorised()) ex.reply(200, """{"emailAddress":"$profileEmail"}""") }
            server.createContext("/gmail/v1/users/me/messages") { ex ->
                if (!ex.authorised()) return@createContext
                val path = ex.requestURI.path
                if (path.endsWith("/messages")) {
                    listCalls++
                    val q = ex.requestURI.rawQuery.orEmpty().split('&').filter { '=' in it }.associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), Charsets.UTF_8) }
                    val start = q["pageToken"]?.toInt() ?: 0
                    val ids = messages.keys.toList() + ghosts
                    val page = ids.drop(start).take(2)
                    val next = if (start + 2 < ids.size) ""","nextPageToken":"${start + 2}"""" else ""
                    ex.reply(200, """{"messages":[${page.joinToString(",") { """{"id":"$it","threadId":"th-$it"}""" }}]$next}""")
                } else {
                    fetches++
                    if (quotaAfterFetches?.let { fetches > it } == true) { ex.quotaReply(); return@createContext }
                    val id = path.substringAfterLast('/')
                    val body = messages[id]
                    if (body == null) ex.reply(404, """{"error":{"message":"gone"}}""") else ex.reply(200, body)
                }
            }
            server.start()
        }

        fun stop() = server.stop(0)

        private fun enc(text: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(text.toByteArray())

        private fun js(text: String) = text.replace("\\", "\\\\").replace("\"", "\\\"")

        fun addPlain(id: String, from0: String, subject0: String, date: Long, text: String) {
            val from = js(from0)
            val subject = js(subject0)
            messages[id] ="""{"id":"$id","threadId":"th-$id","internalDate":"$date","payload":{"mimeType":"multipart/alternative","headers":[{"name":"From","value":"$from"},{"name":"Subject","value":"$subject"}],"parts":[{"mimeType":"text/plain","body":{"data":"${enc(text)}"}},{"mimeType":"text/html","body":{"data":"${enc("<p>ignored html</p>")}"}}]}}"""
        }

        fun addHtmlOnly(id: String, from0: String, subject0: String, date: Long, html: String) {
            val from = js(from0)
            val subject = js(subject0)
            messages[id] ="""{"id":"$id","threadId":"th-$id","internalDate":"$date","payload":{"mimeType":"text/html","headers":[{"name":"From","value":"$from"},{"name":"Subject","value":"$subject"}],"body":{"data":"${enc(html)}"}}}"""
        }
    }

    private fun okMail(r: MailResult<com.example.a2desktop.data.FetchedMail>): com.example.a2desktop.data.FetchedMail =
        (r as? MailResult.Ok)?.value ?: error("the fetch failed: $r")

    private val google = FakeGoogle()
    private val dirs = mutableListOf<File>()

    @AfterTest fun shutDown() {
        google.stop()
        dirs.forEach { it.deleteRecursively() }
    }

    private fun tempDir() = Files.createTempDirectory("gmail-test").toFile().also { dirs += it }

    private fun endpoints() = GoogleEndpoints(
        authUrl = "${google.base}/auth", tokenUrl = "${google.base}/token", revokeUrl = "${google.base}/revoke", gmailBase = "${google.base}/gmail/v1/users/me",
    )

    private val seenAuthUrls = mutableListOf<String>()

    /** The user's browser: reads Google's page address, then answers it with [code], an error, or a wrong state. */
    private fun browser(code: String? = "good-code", error: String? = null, wrongState: Boolean = false, answer: Boolean = true): (String) -> Unit = { url ->
        seenAuthUrls += url
        val q = URI(url).rawQuery.split('&').associate { URLDecoder.decode(it.substringBefore('='), Charsets.UTF_8) to URLDecoder.decode(it.substringAfter('='), Charsets.UTF_8) }
        google.challenge = q["code_challenge"]
        if (answer) {
            val state = if (wrongState) "forged" else q.getValue("state")
            val params = (if (error != null) "error=$error" else "code=$code") + "&state=$state"
            HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI("${q.getValue("redirect_uri")}?$params")).GET().build(), HttpResponse.BodyHandlers.discarding())
        }
    }

    private fun source(
        tokens: com.example.a2desktop.data.TokenStore = MemoryTokenStore(),
        open: (String) -> Unit = browser(),
        clock: () -> Long = System::currentTimeMillis,
        waitSeconds: Long = 10,
        withClient: Boolean = true,
        sleeps: MutableList<Long> = mutableListOf(),
        fetchGapMillis: Long = 0, // no rest between messages unless a test is about it, so the waits recorded in [sleeps] are only the retries
        maxListed: Int = 2000,
    ): GmailSource {
        val clientFile = GmailClientFile(tempDir())
        if (withClient) {
            val file = File(tempDir(), "client_secret_x.json").also { it.writeText(clientJson()) }
            assertIs<ClientParse.Ok>(clientFile.import(file))
        }
        return GmailSource(clientFile, tokens, endpoints(), HttpClient.newHttpClient(), open, clock, { sleeps += it }, waitSeconds, maxListed = maxListed, fetchGapMillis = fetchGapMillis)
    }

    // ---- the client file ----

    @Test fun onlyADesktopClientFileIsAccepted() {
        val ok = assertIs<ClientParse.Ok>(parseClientFile(clientJson()))
        assertEquals("cid-123.apps.googleusercontent.com", ok.client.clientId)
        assertEquals("shh-secret", ok.client.clientSecret)
        assertTrue(assertIs<ClientParse.Bad>(parseClientFile(clientJson("web"))).why.contains("Desktop app"))
        assertIs<ClientParse.Bad>(parseClientFile("not json at all"))
        assertIs<ClientParse.Bad>(parseClientFile("""{"installed":{"client_id":"x"}}"""))
        assertIs<ClientParse.Bad>(parseClientFile("""{"other":{}}"""))
    }

    @Test fun aDownloadedClientFileIsFoundCheckedAndKept() {
        val downloads = tempDir()
        File(downloads, "holiday.json").writeText("{}")
        val old = File(downloads, "client_secret_old.json").also { it.writeText(clientJson()); it.setLastModified(1_000) }
        val new = File(downloads, "client_secret_123.apps.googleusercontent.com.json").also { it.writeText(clientJson()); it.setLastModified(9_000) }
        assertEquals(listOf(new, old), GmailClientFile.findDownloads(downloads), "only client_secret files, newest first")
        assertEquals(emptyList(), GmailClientFile.findDownloads(null))

        val app = GmailClientFile(tempDir())
        assertNull(app.load())
        assertIs<ClientParse.Bad>(app.import(File(downloads, "holiday.json")))
        assertNull(app.load(), "a bad file is not kept")
        assertIs<ClientParse.Ok>(app.import(new))
        assertEquals("cid-123.apps.googleusercontent.com", app.load()?.clientId)
        app.remove()
        assertNull(app.load())
    }

    // ---- signing in ----

    @Test fun signingInAsTheRightAccountKeepsTheKeyAndAsksForReadOnlyAccessOnly() {
        val tokens = MemoryTokenStore()
        val gmail = source(tokens)
        assertTrue(gmail.clientAvailable())
        assertFalse(gmail.isConnected(account))
        assertEquals(MailResult.Ok(Unit), gmail.connect(account))
        assertTrue(gmail.isConnected(account))
        assertEquals("RT1", tokens.refreshToken(account))

        val q = URI(seenAuthUrls.single()).rawQuery.split('&').associate { URLDecoder.decode(it.substringBefore('='), Charsets.UTF_8) to URLDecoder.decode(it.substringAfter('='), Charsets.UTF_8) }
        assertEquals(GMAIL_READONLY_SCOPE, q["scope"], "one permission: read mail")
        assertEquals("S256", q["code_challenge_method"], "the one-time code is protected with PKCE")
        assertEquals(account, q["login_hint"])
        assertEquals("offline", q["access_type"])
        assertTrue(q.getValue("redirect_uri").startsWith("http://127.0.0.1:"), "Google answers to this PC only")
        assertEquals("cid-123.apps.googleusercontent.com", q["client_id"])
        assertFalse(seenAuthUrls.single().contains("shh-secret"), "the secret never goes in the browser address")
    }

    @Test fun googleSpellingTheSameMailboxAnotherWayIsStillTheRightAccountButAnotherMailboxIsNot() {
        google.profileEmail = "robinson8@googlemail.com" // Google's own spelling of the mailbox the section is named after
        val tokens = MemoryTokenStore()
        assertEquals(MailResult.Ok(Unit), source(tokens).connect("robinson8@gmail.com"))
        assertEquals("RT1", tokens.refreshToken("robinson8@gmail.com"))
        google.profileEmail = "robinson9@gmail.com"
        val other = MemoryTokenStore()
        assertIs<MailResult.Failed>(source(other).connect("robinson8@gmail.com"))
        assertNull(other.refreshToken("robinson8@gmail.com"))
    }

    @Test fun signingInAsSomeoneElseIsRefusedAndNothingIsKept() {
        google.profileEmail = "someone.else@gmail.com"
        val tokens = MemoryTokenStore()
        val gmail = source(tokens)
        val failed = assertIs<MailResult.Failed>(gmail.connect(account))
        assertTrue("someone.else@gmail.com" in failed.message && account in failed.message, failed.message)
        assertNull(tokens.refreshToken(account))
        assertEquals(listOf("RT1"), google.revoked, "the key Google just gave is handed back")
    }

    @Test fun refusingAccessAnAnswerThatIsNotTheOneAskedForAndAMissingPermissionAreAllRefused() {
        val denied = assertIs<MailResult.Failed>(source(open = browser(code = null, error = "access_denied")).connect(account))
        assertTrue("didn't allow" in denied.message, denied.message)
        val forged = assertIs<MailResult.Failed>(source(open = browser(wrongState = true)).connect(account))
        assertTrue("didn't match" in forged.message, forged.message)
        assertTrue(google.authTokenRequests.isEmpty(), "a forged or refused answer never gets as far as asking Google for a key")
        google.grantedScope = "openid"
        val tokens = MemoryTokenStore()
        val noScope = assertIs<MailResult.Failed>(source(tokens).connect(account))
        assertTrue("permission to read mail" in noScope.message, noScope.message)
        assertNull(tokens.refreshToken(account))
    }

    @Test fun aWrongCodeIsRefusedByGoogleAndWaitingForeverGivesUp() {
        val wrong = assertIs<MailResult.Failed>(source(open = browser(code = "stolen-code")).connect(account))
        assertTrue(wrong.expired, wrong.message)
        val silent = assertIs<MailResult.Failed>(source(open = browser(answer = false), waitSeconds = 1).connect(account))
        assertTrue("in time" in silent.message, silent.message)
        val noClient = assertIs<MailResult.Failed>(source(withClient = false).connect(account))
        assertTrue("client file" in noClient.message, noClient.message)
    }

    @Test fun disconnectingForgetsTheKeyAndTellsGoogle() {
        val tokens = MemoryTokenStore()
        val gmail = source(tokens)
        gmail.connect(account)
        gmail.disconnect(account)
        assertFalse(gmail.isConnected(account))
        assertEquals(listOf("RT1"), google.revoked)
    }

    @Test fun keysSurviveARestartInTheirFile() {
        val file = File(tempDir(), "gmail-tokens.json")
        FileTokenStore(file).save(account, "RT-file")
        FileTokenStore(file).save("Other@gmail.com", "RT-other")
        assertEquals("RT-file", FileTokenStore(file).refreshToken("ALEXROBINSON@gmail.com"), "the address's capitals don't matter")
        FileTokenStore(file).forget(account)
        assertNull(FileTokenStore(file).refreshToken(account))
        assertEquals("RT-other", FileTokenStore(file).refreshToken("other@gmail.com"))
        assertNull(FileTokenStore(File(tempDir(), "missing.json")).refreshToken(account))
    }

    // ---- reading mail ----

    private fun connected(sleeps: MutableList<Long> = mutableListOf(), clock: () -> Long = System::currentTimeMillis, fetchGapMillis: Long = 0): GmailSource =
        source(clock = clock, sleeps = sleeps, fetchGapMillis = fetchGapMillis).also { assertEquals(MailResult.Ok(Unit), it.connect(account)) }

    @Test fun messagesAreListedAcrossPagesSkippingWhatWasSeenAndReadAsText() {
        google.addPlain("m1", "\"Acme Careers\" <jobs@acme.co.uk>", "Your application", 3_000, "Dear Alex, we regret to inform you that…")
        google.addHtmlOnly("m2", "NHS Jobs <noreply@jobs.nhs.uk>", "Application received", 2_000, "<style>p{color:red}</style><p>Thank&nbsp;you for applying &amp; good luck</p><script>x()</script>")
        google.addPlain("m3", "Zed <hr@zed.com>", "Interview", 1_000, "We would like to invite you to an interview.")
        val gmail = connected()
        val progress = mutableListOf<Pair<Int, Int>>()
        val first = okMail(gmail.fetchNew(account, "q", emptySet(), 10) { d, t -> progress += d to t })
        assertEquals(listOf("m1", "m2", "m3"), first.messages.map { it.id })
        assertEquals(0, first.remaining)
        assertEquals(2, google.listCalls, "three messages over two pages")
        val m1 = first.messages[0]
        assertEquals("\"Acme Careers\" <jobs@acme.co.uk>", m1.from)
        assertEquals("Your application", m1.subject)
        assertEquals(3_000, m1.dateMillis)
        assertEquals("th-m1", m1.threadId)
        assertEquals("Dear Alex, we regret to inform you that…", m1.text, "the plain part is used, not the html one")
        assertEquals("Thank you for applying & good luck.", first.messages[1].text, "an html-only message is turned into text, styles and scripts dropped (a closing tag leaves a full stop, so sentences stay apart)")
        assertEquals(0 to 3, progress.first())
        assertEquals(3 to 3, progress.last())

        val again = okMail(gmail.fetchNew(account, "q", setOf("m1", "m2"), 10) { _, _ -> })
        assertEquals(listOf("m3"), again.messages.map { it.id }, "what was seen before isn't fetched again")
    }

    @Test fun aLimitLeavesTheRestForNextTime() {
        (1..5).forEach { google.addPlain("m$it", "A <a@a.com>", "s$it", it * 1000L, "text") }
        val gmail = connected()
        val got = okMail(gmail.fetchNew(account, "q", setOf("m1"), 2) { _, _ -> })
        assertEquals(listOf("m2", "m3"), got.messages.map { it.id })
        assertEquals(2, got.remaining, "five minus the one seen minus the two read")
    }

    @Test fun aKeyThatRanOutIsRefreshedOnceAndTheCallRepeated() {
        google.addPlain("m1", "A <a@a.com>", "s", 1, "text")
        val gmail = connected()
        google.rejectNextGmailCall = true // the next call is refused as expired, as if the short-lived key had just run out
        val got = okMail(gmail.fetchNew(account, "q", emptySet(), 5) { _, _ -> })
        assertEquals(listOf("m1"), got.messages.map { it.id })
        assertEquals(1, google.refreshes, "one new short-lived key was asked for")
        assertTrue("Bearer AT2" in google.gmailAuthHeaders, "…and then used: ${google.gmailAuthHeaders}")
    }

    @Test fun aShortLivedKeyIsReusedUntilItIsNearlyOut() {
        google.addPlain("m1", "A <a@a.com>", "s", 1, "text")
        var now = 1_000_000L
        val gmail = connected(clock = { now })
        gmail.fetchNew(account, "q", emptySet(), 5) { _, _ -> }
        gmail.fetchNew(account, "q", emptySet(), 5) { _, _ -> }
        assertEquals(0, google.refreshes, "the key from signing in lasts an hour")
        now += 3_600_000L
        gmail.fetchNew(account, "q", emptySet(), 5) { _, _ -> }
        assertEquals(1, google.refreshes, "an hour on, a new one")
    }

    @Test fun aKeyGoogleNoLongerAcceptsMeansConnectAgainAndIsForgotten() {
        val tokens = MemoryTokenStore()
        val gmail = source(tokens, clock = { 0L }) // the clock never moves on, so use the long-lasting key after the first hour only
        gmail.connect(account)
        var now = 0L
        val late = source(tokens, clock = { now.also { now += 4_000_000L } })
        google.refreshInvalid = true
        val failed = assertIs<MailResult.Failed>(late.fetchNew(account, "q", emptySet(), 5) { _, _ -> })
        assertTrue(failed.expired, failed.message)
        assertTrue("Connect again" in failed.message, failed.message)
        assertNull(tokens.refreshToken(account), "a key Google refuses is dropped so the page can offer Connect")
    }

    @Test fun aBusyGmailIsRetriedAFewTimesThenGivenUp() {
        google.addPlain("m1", "A <a@a.com>", "s", 1, "text")
        val sleeps = mutableListOf<Long>()
        val gmail = connected(sleeps)
        google.busyTimes = 2
        assertIs<MailResult.Ok<*>>(gmail.fetchNew(account, "q", emptySet(), 5) { _, _ -> })
        assertEquals(listOf(1000L, 2000L), sleeps, "it waited a little longer each time")
        google.busyTimes = 10
        val busy = assertIs<MailResult.Failed>(gmail.fetchNew(account, "q", emptySet(), 5) { _, _ -> })
        assertTrue("busy" in busy.message, busy.message)
    }

    // ---- Gmail's per-minute allowance (6,000 units a user; this is what stopped the first real scan after 30 emails) ----

    @Test fun aQuotaErrorIsWaitedOutAndTheScanGoesOnInsteadOfBeingTakenForARefusal() {
        google.addPlain("m1", "A <a@a.com>", "s", 1, "text")
        val sleeps = mutableListOf<Long>()
        val gmail = connected(sleeps)
        google.quotaTimes = 2 // the first two calls come back "Quota exceeded for … Units per minute per user"
        val got = okMail(gmail.fetchNew(account, "q", emptySet(), 5) { _, _ -> })
        assertEquals(listOf("m1"), got.messages.map { it.id }, "it waited for the minute to pass and then read the mail")
        assertEquals(listOf(20_000L, 40_000L), sleeps, "waiting longer each time")
    }

    @Test fun googlesRetryAfterIsHonouredButKeptWithinAMinuteAndAHalf() {
        google.addPlain("m1", "A <a@a.com>", "s", 1, "text")
        listOf("7" to 7_000L, "500" to 90_000L, "0" to 1_000L, "soon" to 20_000L).forEach { (header, expected) ->
            val sleeps = mutableListOf<Long>()
            val gmail = connected(sleeps)
            google.quotaRetryAfter = header
            google.quotaTimes = 1
            assertIs<MailResult.Ok<*>>(gmail.fetchNew(account, "q", emptySet(), 5) { _, _ -> })
            assertEquals(listOf(expected), sleeps, "Retry-After: $header")
        }
    }

    @Test fun aQuotaThatNeverLiftsIsGivenUpOnAfterThreeWaitsWithAClearMessage() {
        google.addPlain("m1", "A <a@a.com>", "s", 1, "text")
        val sleeps = mutableListOf<Long>()
        val gmail = connected(sleeps)
        google.quotaTimes = 100
        val failed = assertIs<MailResult.Failed>(gmail.fetchNew(account, "q", emptySet(), 5) { _, _ -> })
        assertEquals(listOf(20_000L, 40_000L, 60_000L), sleeps)
        assertEquals(GmailApi.QUOTA_GIVE_UP, failed.message)
        assertTrue("press Scan again" in failed.message && "kept" in failed.message, failed.message)
        assertTrue("may not be turned on" !in failed.message, "a quota is not reported as the API being off")
    }

    @Test fun aRealRefusalIsStillFinalAndNeverWaitedOn() {
        google.addPlain("m1", "A <a@a.com>", "s", 1, "text")
        val sleeps = mutableListOf<Long>()
        val gmail = connected(sleeps)
        google.forbiddenTimes = 1 // "Gmail API has not been used in project … or it is disabled"
        val failed = assertIs<MailResult.Failed>(gmail.fetchNew(account, "q", emptySet(), 5) { _, _ -> })
        assertTrue("Google refused" in failed.message && "disabled" in failed.message, failed.message)
        assertEquals(emptyList(), sleeps, "no point waiting for something that won't change")
    }

    @Test fun aQuotaErrorIsRecognisedByItsReasonItsStatusOrItsWordingAloneAndOnA429Too() {
        google.addPlain("m1", "A <a@a.com>", "s", 1, "text")
        listOf(
            403 to """{"error":{"message":"x","errors":[{"reason":"userRateLimitExceeded"}]}}""",
            403 to """{"error":{"message":"Something about 'Total Query Cost'. Quota exceeded for quota metric"}}""",
            429 to """{"error":{"message":"x","status":"RESOURCE_EXHAUSTED"}}""",
            429 to """{"error":{"message":"User-rate limit exceeded. Retry after 2026-11-11T23:30:00Z"}}""",
            403 to """{"error":{"message":"x","errors":[{"reason":"quotaExceeded"}]}}""",
        ).forEach { (code, body) ->
            val sleeps = mutableListOf<Long>()
            val gmail = connected(sleeps)
            google.quotaCode = code
            google.quotaBody = body
            google.quotaTimes = 1
            assertIs<MailResult.Ok<*>>(gmail.fetchNew(account, "q", emptySet(), 5) { _, _ -> }, "$code $body")
            assertEquals(listOf(20_000L), sleeps, "$code $body")
        }
    }

    @Test fun eachMessageIsReadAfterAShortRestSoAScanStaysUnderTheAllowance() {
        (1..3).forEach { google.addPlain("m$it", "A <a@a.com>", "s$it", it * 1000L, "text") }
        val sleeps = mutableListOf<Long>()
        val gmail = connected(sleeps, fetchGapMillis = 100)
        okMail(gmail.fetchNew(account, "q", emptySet(), 5) { _, _ -> })
        assertEquals(listOf(100L, 100L, 100L), sleeps, "one rest before each of the three messages, none for listing them")
        assertEquals(100L, GmailApi.FETCH_GAP_MILLIS, "the real default is ten messages a second at most: 3,000 of the 6,000 units a minute")
    }

    @Test fun aPassCutShortPartWayKeepsWhatItAlreadyReadAndSaysWhyItStopped() {
        (1..5).forEach { google.addPlain("m$it", "A <a@a.com>", "s$it", it * 1000L, "text") }
        val gmail = connected()
        google.quotaAfterFetches = 2 // two messages are read, then the minute's allowance runs out and never comes back
        val got = okMail(gmail.fetchNew(account, "q", emptySet(), 5) { _, _ -> })
        assertEquals(listOf("m1", "m2"), got.messages.map { it.id }, "the two read before it stopped are not thrown away")
        assertEquals(GmailApi.QUOTA_GIVE_UP, got.stopped)
        assertEquals(3, got.remaining, "the three it didn't get to are left for next time")

        val none = connected()
        google.fetches = 0
        google.quotaAfterFetches = 0 // stopped before reading anything: that is just a failure
        assertIs<MailResult.Failed>(none.fetchNew(account, "q", emptySet(), 5) { _, _ -> })
    }

    @Test fun everyListedIdIsReportedSoMessagesDeletedFromGmailCanBeNoticedButOnlyWhenTheListIsWhole() {
        (1..3).forEach { google.addPlain("m$it", "A <a@a.com>", "s$it", it * 1000L, "text") }
        val whole = connected().fetchNew(account, "q", setOf("m1", "m2"), 10) { _, _ -> }
        assertEquals(setOf("m1", "m2", "m3"), okMail(whole).listedIds, "all three, including the two already read and so not fetched again")

        val cutShort = source(maxListed = 3).also { assertEquals(MailResult.Ok(Unit), it.connect(account)) }
        assertNull(okMail(cutShort.fetchNew(account, "q", emptySet(), 10) { _, _ -> }).listedIds, "a list that reached the limit may be cut short: nothing may be concluded from it")
        val roomToSpare = source(maxListed = 4).also { assertEquals(MailResult.Ok(Unit), it.connect(account)) }
        assertEquals(setOf("m1", "m2", "m3"), okMail(roomToSpare.fetchNew(account, "q", emptySet(), 10) { _, _ -> }).listedIds)
    }

    @Test fun aMessageThatHasGoneIsSkippedNotAFailure() {
        google.addPlain("m1", "A <a@a.com>", "s", 1, "text")
        google.ghosts += "deleted-since-listing"
        google.addPlain("m3", "A <a@a.com>", "s", 3, "text")
        val gmail = connected()
        val got = okMail(gmail.fetchNew(account, "q", emptySet(), 5) { _, _ -> })
        assertEquals(listOf("m1", "m3"), got.messages.map { it.id }, "the missing one is passed over and the scan goes on")
    }

    @Test fun notConnectedMeansItSaysSoAndNeverTouchesGoogle() {
        val gmail = source()
        val failed = assertIs<MailResult.Failed>(gmail.fetchNew(account, "q", emptySet(), 5) { _, _ -> })
        assertTrue("isn't connected" in failed.message, failed.message)
        assertTrue(google.authTokenRequests.isEmpty() && google.gmailAuthHeaders.isEmpty())
    }

    @Test fun htmlBecomesReadableText() {
        assertEquals("Hello & welcome. Second. line.", htmlToText("<div>Hello &amp; welcome</div><p>Second<br>line</p>"))
    }
}
