package com.example.a2desktop

import com.example.a2desktop.data.DEFAULT_JOBS_ACCOUNT
import com.example.a2desktop.data.FetchedMail
import com.example.a2desktop.data.GmailClientFile
import com.example.a2desktop.data.JobsActions
import com.example.a2desktop.data.JobsStore
import com.example.a2desktop.data.MailResult
import com.example.a2desktop.data.MailSource
import com.example.a2desktop.data.jobsQuery
import com.example.a2desktop.logic.CLASSIFIER_VERSION
import com.example.a2desktop.logic.EmailMessage
import com.example.a2desktop.logic.FolderJob
import com.example.a2desktop.logic.JobStatus
import com.example.a2desktop.logic.jobKeyOfTitle
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job

/** The Jobs page's state, with a fake mailbox: connecting, scanning in passes, remembering, the user's own choices, and never keeping a message. */
class JobsStoreTest {
    private val account = DEFAULT_JOBS_ACCOUNT

    private class FakeMail(var client: Boolean = true) : MailSource {
        val connected = mutableSetOf<String>()
        var messages: List<EmailMessage> = emptyList()
        var connectResult: MailResult<Unit> = MailResult.Ok(Unit)
        var fetchFailure: MailResult.Failed? = null
        var stopWith: String? = null // when set, a pass reads one message and then reports it was stopped for this reason
        var reportListing = false // when set, every pass also reports which messages Gmail lists (the ones in [messages]), as the real one does when its list is whole
        val queries = mutableListOf<String>()
        val skipSizes = mutableListOf<Int>()
        val disconnected = mutableListOf<String>()
        override fun clientAvailable() = client
        override fun isConnected(account: String) = account.lowercase() in connected
        override fun connect(account: String): MailResult<Unit> {
            if (connectResult is MailResult.Ok) connected += account.lowercase()
            return connectResult
        }
        override fun disconnect(account: String) { connected -= account.lowercase(); disconnected += account }
        override fun fetchNew(account: String, query: String, skip: Set<String>, limit: Int, progress: (Int, Int) -> Unit): MailResult<FetchedMail> {
            queries += query
            skipSizes += skip.size
            fetchFailure?.let { return it }
            val fresh = messages.filter { it.id !in skip }
            stopWith?.let { why -> val one = fresh.take(1); return MailResult.Ok(FetchedMail(one, fresh.size - one.size, stopped = why)) }
            val batch = fresh.take(limit)
            progress(batch.size, batch.size)
            return MailResult.Ok(FetchedMail(batch, fresh.size - batch.size, listedIds = if (reportListing) messages.map { it.id }.toSet() else null))
        }
    }

    private val dirs = mutableListOf<File>()
    private fun temp() = Files.createTempDirectory("jobs-store").toFile().also { dirs += it }

    private val scope = CoroutineScope(Job() + Dispatchers.Unconfined)

    private val folders = listOf(
        FolderJob(jobKeyOfTitle("Northgate College Healthcare NHS Trust Clinic Clerk - Maternity"), 2, "Northgate College Healthcare NHS Trust Clinic Clerk - Maternity", null, "p1"),
        FolderJob(jobKeyOfTitle("Lakeside and Hillcrest NHS Foundation Trust Patient Pathway Coordinator"), 6, "Lakeside and Hillcrest NHS Foundation Trust Patient Pathway Coordinator", JobStatus.REJECTED, "p2"),
    )

    private fun store(file: File? = null, mail: FakeMail? = FakeMail(), clientFile: GmailClientFile? = null, downloads: File? = null, clock: () -> Long = { 5_000L }) =
        JobsStore(file, mail, { folders }, clientFile, downloads, JobsActions.None, scope, clock)

    private fun msg(id: Int, text: String, subject: String = "Your application", from: String = "Northgate <careers@northgate.nhs.uk>") =
        EmailMessage("m$id", "t$id", from, subject, id * 1000L, text)

    private val rejection = "Dear Alex. We regret to inform you that your application to Northgate College Healthcare NHS Trust was not successful. Secret detail 12345."

    @Test fun theFirstRunHasTheRequestedMailboxNotConnectedAndEveryFolderAsAJob() {
        val s = store()
        assertEquals(listOf("robinson8@gmail.com"), s.accounts.map { it.email }, "the user's own mailbox is the first section")
        assertFalse(s.accounts.single().connected)
        assertEquals(2, s.rows.size)
        assertEquals(JobStatus.NOT_SEEN, s.rows.single { it.title.startsWith("Northgate") }.status)
        assertEquals(JobStatus.REJECTED, s.rows.single { it.title.startsWith("Lakeside") }.status, "its folder says rejected")
    }

    @Test fun connectingNeedsTheClientFileAndSaysWhatToDoWithoutOpeningAnything() {
        val mail = FakeMail(client = false)
        val s = store(mail = mail)
        s.connect(account)
        assertTrue("client file" in s.message.orEmpty(), s.message)
        assertFalse(mail.isConnected(account))
        assertTrue(mail.queries.isEmpty())
        val offline = JobsStore(mail = null, scope = scope)
        offline.connect(account)
        assertTrue("isn't available" in offline.message.orEmpty())
        assertFalse(offline.online)
    }

    @Test fun connectingReadsTheMailAndTheJobsTakeTheirStatusFromIt() {
        val mail = FakeMail().apply { messages = listOf(msg(1, rejection), msg(2, "Hello, your parcel is on its way.", subject = "Delivery")) }
        val s = store(mail = mail)
        s.connect(account)
        assertTrue(mail.isConnected(account))
        assertTrue(s.accounts.single().connected)
        assertEquals(JobStatus.REJECTED, s.rows.single { it.title.startsWith("Northgate") }.status)
        assertEquals(1, s.accounts.single().emailsFound, "the parcel email isn't about an application")
        assertEquals(5_000L, s.accounts.single().lastScanMillis)
        assertEquals("Read 2 emails and found 1 about applications.", s.message)
        assertTrue(mail.queries.single().startsWith("newer_than:365d"), mail.queries.single())
        assertEquals(jobsQuery(), mail.queries.single())
        assertFalse(s.busy)
        assertNull(s.progress)
    }

    @Test fun aRefusedSignInSaysWhyAndLeavesEverythingAsItWas() {
        val mail = FakeMail().apply { connectResult = MailResult.Failed("You signed in as someone else.") }
        val s = store(mail = mail)
        s.connect(account)
        assertEquals("You signed in as someone else.", s.message)
        assertFalse(s.accounts.single().connected)
        assertTrue(mail.queries.isEmpty(), "no scan after a refused sign-in")
        assertFalse(s.busy)
    }

    @Test fun aLaterScanReadsOnlyWhatIsNew() {
        val mail = FakeMail().apply { messages = listOf(msg(1, rejection)) }
        val s = store(mail = mail)
        s.connect(account)
        mail.messages = mail.messages + msg(3, "Thank you for applying for Clinic Clerk at Northgate College Healthcare. We have received your application.")
        s.scan(account)
        assertEquals(listOf(0, 1), mail.skipSizes, "the second scan was told which messages were already read")
        assertEquals("Read 1 emails and found 1 about applications.", s.message)
        s.scan(account)
        assertEquals("Nothing new in your mail.", s.message)
        assertEquals(2, s.accounts.single().emailsFound)
    }

    @Test fun aBigMailboxIsReadInPassesAndTheRestIsSaidToBeLeft() {
        val mail = FakeMail().apply { messages = (1..1000).map { msg(it, if (it % 100 == 0) rejection else "Hello $it") } }
        val s = store(mail = mail)
        s.connect(account)
        assertEquals(3, mail.queries.size, "three passes of 300")
        assertEquals("Read 900 emails and found 9 about applications. 100 older ones are left — press Scan again for them.", s.message)
        s.scan(account)
        assertEquals("Read 100 emails and found 1 about applications.", s.message)
        assertEquals(10, s.accounts.single().emailsFound)
    }

    private val acknowledgement = "Thank you for applying for Clinic Clerk at Northgate College Healthcare. We have received your application."

    @Test fun anEmailDeletedFromGmailTakesItsJobAwayOnTheNextScanAndSaysSo() {
        val mail = FakeMail().apply {
            reportListing = true
            messages = listOf(
                EmailMessage("m1", "t1", "Northgate <careers@northgate.nhs.uk>", "Your application", 3_000, acknowledgement),
                EmailMessage("m2", "t2", "Odd Co <jobs@oddco.example>", "Thank you for applying - Porter", 2_000, "Thank you for applying. We have received your application."),
                EmailMessage("m3", "t3", "Shop <orders@shop.example>", "Your application for a loyalty card", 1_000, "Your parcel is on its way."), // not a job; keeps the list from ever being empty
            )
        }
        val s = store(mail = mail)
        s.connect(account)
        assertEquals(2, s.accounts.single().emailsFound)
        assertEquals(JobStatus.APPLIED, s.rows.single { it.title.startsWith("Northgate") }.status)
        assertEquals(3, s.rows.size, "the two folders and the email-only job")

        mail.messages = mail.messages.filter { it.id != "m2" } // the person deletes the email from Gmail
        s.scan(account)
        assertEquals(1, s.accounts.single().emailsFound, "its event is dropped")
        assertEquals(2, s.rows.size, "and the job that only it created is gone")
        assertEquals(JobStatus.APPLIED, s.rows.single { it.title.startsWith("Northgate") }.status, "the other email's job is untouched")
        assertTrue("Dropped 1 you deleted from Gmail" in s.message.orEmpty(), s.message)

        mail.messages = mail.messages.filter { it.id != "m1" }
        s.scan(account)
        assertEquals(JobStatus.NOT_SEEN, s.rows.single { it.title.startsWith("Northgate") }.status, "a folder job whose only email is deleted goes back to no email yet")
    }

    @Test fun anEmptyOrCutShortListFromGmailNeverDeletesAnythingAndNeitherDoesMailOlderThanTheSearch() {
        val mail = FakeMail().apply {
            reportListing = true
            messages = listOf(EmailMessage("m1", "t1", "Northgate <careers@northgate.nhs.uk>", "Your application", 3_000, acknowledgement))
        }
        var now = 5_000L
        val s = store(mail = mail, clock = { now })
        s.connect(account)
        assertEquals(1, s.accounts.single().emailsFound)

        mail.messages = emptyList() // Gmail lists nothing at all: far more likely a hiccup than everything deleted
        s.scan(account)
        assertEquals(1, s.accounts.single().emailsFound, "an empty list deletes nothing")

        mail.reportListing = false // a list cut short at the limit is reported as unknown
        mail.messages = listOf(EmailMessage("m9", "t9", "Other <x@other.example>", "Hello", 1, "Hello there."))
        s.scan(account)
        assertEquals(1, s.accounts.single().emailsFound, "an unknown list deletes nothing")

        mail.reportListing = true // now a whole list that lacks m1, but a year and more later: m1 is outside the search window, so it is not 'deleted'
        now += 400L * 24 * 60 * 60 * 1000
        s.scan(account)
        assertEquals(1, s.accounts.single().emailsFound, "mail older than the search covers is kept, not mistaken for deleted")
    }

    @Test fun aScanGoogleCutsShortKeepsWhatItReadSaysWhyAndPicksUpTheRestNextTime() {
        val mail = FakeMail().apply {
            messages = listOf(msg(1, rejection), msg(2, "Thank you for applying for Clinic Clerk at Northgate College Healthcare. We have received your application."), msg(3, "Hello, a parcel."))
            stopWith = "Gmail's limit on how fast mail can be read was reached. What was read is kept — press Scan again in a minute."
        }
        val s = store(mail = mail)
        s.connect(account)
        assertEquals(1, mail.queries.size, "it stopped there instead of going on to more passes that would only be refused too")
        assertEquals(1, s.accounts.single().emailsFound, "the message read before it stopped is kept (its rejection is on the page)")
        assertEquals(JobStatus.REJECTED, s.rows.single { it.title.startsWith("Northgate") }.status)
        val said = s.message.orEmpty()
        assertTrue("Read 1 emails and found 1" in said && "kept" in said && "limit on how fast" in said, said)
        assertFalse(s.busy)

        mail.stopWith = null
        s.scan(account)
        assertEquals(listOf(0, 1), mail.skipSizes, "next time it was told the first message was read already")
        assertEquals("Read 2 emails and found 1 about applications.", s.message, "and it read only the two it hadn't reached")
    }

    @Test fun aFailedScanSaysWhyAndKeepsWhatWasFound() {
        val mail = FakeMail().apply { messages = listOf(msg(1, rejection)) }
        val s = store(mail = mail)
        s.connect(account)
        mail.fetchFailure = MailResult.Failed("Google says the connection has expired or was removed. Connect again.", expired = true)
        s.scan(account)
        assertTrue("Connect again" in s.message.orEmpty())
        assertEquals(JobStatus.REJECTED, s.rows.single { it.title.startsWith("Northgate") }.status)
    }

    @Test fun whatIsFoundIsRememberedButNeverTheMessageItself() {
        val file = File(temp(), "jobs.json")
        val mail = FakeMail().apply { messages = listOf(msg(1, rejection)) }
        store(file, mail).connect(account)
        val text = file.readText()
        assertTrue("We regret to inform you" in text, "the sentence that shows why is kept")
        assertFalse("Secret detail" in text, "…but not the rest of the email")
        assertFalse("12345" in text)
        assertFalse(text.contains("access_token") || text.contains("refresh"), "no keys in this file")

        val again = store(file, FakeMail())
        assertEquals(JobStatus.REJECTED, again.rows.single { it.title.startsWith("Northgate") }.status, "back after a restart, with no new scan")
        assertEquals(1, again.accounts.single().emailsFound)
        assertEquals(5_000L, again.accounts.single().lastScanMillis)
        assertFalse(again.accounts.single().connected, "being signed in is the mail source's business, not the file's")
    }

    @Test fun yourOwnStatusAndHidingSurviveARestartAndCanBeTakenBack() {
        val file = File(temp(), "jobs.json")
        val s = store(file)
        val northgate = folders[0].key
        val lakeside = folders[1].key
        s.setStatus(northgate, JobStatus.INTERVIEW)
        s.hide(lakeside)
        assertEquals(JobStatus.INTERVIEW, s.rows.single().status)
        assertTrue(s.rows.single().overridden)
        assertEquals(1, s.hiddenCount)

        val again = store(file)
        assertEquals(listOf(northgate), again.rows.map { it.key })
        assertEquals(JobStatus.INTERVIEW, again.rows.single().status)
        again.setStatus(northgate, null)
        again.unhideAll()
        assertEquals(2, again.rows.size)
        assertEquals(JobStatus.NOT_SEEN, again.rows.single { it.key == northgate }.status)
        assertEquals(0, again.hiddenCount)
    }

    @Test fun manyJobsCanBeGivenAStatusOrHiddenInOneGoAndItSurvivesARestart() {
        val list = listOf(job("Northgate", 2), job("Riverton", 3), job("Lakeside", 6))
        val file = File(temp(), "jobs.json")
        fun open() = JobsStore(file, FakeMail(), { list }, scope = scope, clock = { 5_000L })
        val statusOf = { s: JobsStore, title: String -> s.rows.single { it.title == title }.status }
        val s = open()
        s.setStatusOfAll(listOf(jobKeyOfTitle("Northgate"), jobKeyOfTitle("Riverton")), JobStatus.OFFER)
        assertEquals(listOf(JobStatus.OFFER, JobStatus.OFFER, JobStatus.NOT_SEEN), listOf("Northgate", "Riverton", "Lakeside").map { statusOf(s, it) })
        assertTrue(s.rows.filter { it.title != "Lakeside" }.all { it.overridden }, "set by hand, like the single menu does")

        val again = open()
        assertEquals(JobStatus.OFFER, statusOf(again, "Riverton"), "kept for the next run")
        again.setStatusOfAll(listOf(jobKeyOfTitle("Northgate"), jobKeyOfTitle("Lakeside")), null)
        assertEquals(JobStatus.NOT_SEEN, statusOf(again, "Northgate"), "sent back to what the emails say")
        assertEquals(JobStatus.OFFER, statusOf(again, "Riverton"), "a job not in the list keeps its own")

        again.hideAll(listOf(jobKeyOfTitle("Northgate"), jobKeyOfTitle("Riverton")))
        assertEquals(listOf("Lakeside"), again.rows.map { it.title })
        again.hideAll(listOf(jobKeyOfTitle("Northgate")))
        assertEquals(2, again.hiddenCount, "hiding one twice is still hiding it once")
        again.setStatusOfAll(emptyList(), JobStatus.OFFER)
        again.hideAll(emptyList())
        assertEquals(2, again.hiddenCount, "nothing ticked, nothing done")
        assertEquals(2, open().hiddenCount, "and the hiding was kept for the next run")
    }

    @Test fun deletedJobsStayGoneEvenAfterShowHiddenAndTheListClosesUpAndItSurvivesARestart() {
        val list = mutableListOf(job("Northgate", 2, created = 3 * day), job("Riverton", 3, created = 2 * day), job("Lakeside", 6, created = 1 * day))
        val file = File(temp(), "jobs.json")
        fun open() = JobsStore(file, FakeMail(), { list.toList() }, scope = scope, clock = { 5_000L })
        val s = open()
        s.setStatus(jobKeyOfTitle("Riverton"), JobStatus.OFFER)
        s.hide(jobKeyOfTitle("Lakeside"))
        s.deleteAll(listOf(jobKeyOfTitle("Riverton"), jobKeyOfTitle("Northgate")))
        assertEquals(emptyList(), s.rows.map { it.title }, "the two deleted are gone and the third is hidden")
        assertEquals(1, s.hiddenCount, "deleting is not hiding: only Lakeside is hidden")
        assertFalse("OFFER" in file.readText(), "a deleted job's own status choice is forgotten too")

        s.unhideAll()
        assertEquals(listOf("Lakeside"), s.rows.map { it.title }, "Show hidden brings back the hidden one, not the deleted ones")
        list += job("Brand new", 11, created = 9 * day)
        s.refreshFolders()
        assertEquals(listOf("Brand new" to "1", "Lakeside" to "2"), numbered(s).toList(), "the list just closes up: the deleted jobs leave no gaps in the numbers")

        val again = open()
        assertEquals(listOf("Brand new", "Lakeside"), again.rows.sortedBy { it.order }.map { it.title }, "still gone after a restart")
        again.deleteAll(emptyList())
        assertEquals(2, again.rows.size, "nothing ticked, nothing deleted")
    }

    @Test fun rulesThatChangedMeanTheOldScanIsDroppedSoItIsReadAgain() {
        val file = File(temp(), "jobs.json")
        val mail = FakeMail().apply { messages = listOf(msg(1, rejection)) }
        store(file, mail).connect(account)
        assertTrue("\"classifier\":$CLASSIFIER_VERSION" in file.readText(), "the file says which rules read it: ${file.readText().take(200)}")
        file.writeText(file.readText().replace("\"classifier\":$CLASSIFIER_VERSION", "\"classifier\":0")) // as if saved by older rules
        val again = store(file, mail)
        assertEquals(0, again.accounts.single().emailsFound, "the old reading is not trusted")
        assertEquals(JobStatus.NOT_SEEN, again.rows.single { it.title.startsWith("Northgate") }.status)
        again.scan(account.also { mail.connected += it })
        assertEquals(1, again.accounts.single().emailsFound, "…and the next scan reads everything again")
    }

    @Test fun aDamagedFileIsJustAFreshStart() {
        val file = File(temp(), "jobs.json").also { it.writeText("{ not json") }
        val s = store(file)
        assertEquals(listOf(account), s.accounts.map { it.email })
        assertEquals(2, s.rows.size)
    }

    @Test fun anotherMailboxCanBeAddedAndRemoved() {
        val mail = FakeMail()
        val s = store(mail = mail)
        assertFalse(s.addAccount("not an address"))
        assertTrue("doesn't look like" in s.message.orEmpty())
        assertFalse(s.addAccount("Robin.son8+jobs@Googlemail.com"), "the same mailbox spelled another way (capitals, dots, +tag, googlemail) is not added twice")
        assertTrue(s.addAccount("  second@example.com "))
        assertEquals(listOf(account, "second@example.com"), s.accounts.map { it.email })
        s.removeAccount("second@example.com")
        assertEquals(listOf(account), s.accounts.map { it.email })
        assertEquals(listOf("second@example.com"), mail.disconnected)
    }

    @Test fun disconnectingKeepsTheJobsAlreadyFound() {
        val mail = FakeMail().apply { messages = listOf(msg(1, rejection)) }
        val s = store(mail = mail)
        s.connect(account)
        s.disconnect(account)
        assertFalse(s.accounts.single().connected)
        assertEquals(JobStatus.REJECTED, s.rows.single { it.title.startsWith("Northgate") }.status)
        assertTrue("stay" in s.message.orEmpty())
    }

    @Test fun theClientFileIsCheckedAndAFreshDownloadIsFound() {
        val downloads = temp()
        val app = temp()
        val clientFile = GmailClientFile(app)
        val mail = FakeMail(client = false)
        val s = store(mail = mail, clientFile = clientFile, downloads = downloads)
        assertFalse(s.clientReady)
        assertTrue(s.downloadedClients.isEmpty())
        val bad = File(downloads, "client_secret_1.json").also { it.writeText("nope") }
        s.checkSetup()
        assertEquals(listOf(bad), s.downloadedClients)
        s.importClient(bad)
        assertTrue("isn't a Google client file" in s.message.orEmpty(), s.message)
        val good = File(downloads, "client_secret_2.json").also { it.writeText("""{"installed":{"client_id":"a","client_secret":"b"}}""") }
        s.importClient(good)
        assertTrue("in place" in s.message.orEmpty(), s.message)
        assertNotNull(clientFile.load())
        mail.client = true
        s.checkSetup()
        assertTrue(s.clientReady)
    }

    @Test fun theConnectPageSummaryNamesTheConnectedMailboxes() {
        val none = emptyList<com.example.a2desktop.data.JobAccount>()
        assertEquals("No mailbox added", com.example.a2desktop.data.connectionSummary(none))
        val off = com.example.a2desktop.data.JobAccount("robinson8@gmail.com", false, 0, 0)
        val on = com.example.a2desktop.data.JobAccount("robinson8@gmail.com", true, 0, 0)
        assertEquals("Not connected", com.example.a2desktop.data.connectionSummary(listOf(off)))
        assertEquals("Connected: robinson8@gmail.com", com.example.a2desktop.data.connectionSummary(listOf(on)))
        assertEquals("Connected: robinson8@gmail.com, b@example.com", com.example.a2desktop.data.connectionSummary(listOf(on, off.copy(email = "x@example.com"), on.copy(email = "b@example.com"))))
        val mail = FakeMail()
        val s = store(mail = mail)
        assertEquals("Not connected", com.example.a2desktop.data.connectionSummary(s.accounts))
        s.connect(account)
        assertEquals("Connected: $account", com.example.a2desktop.data.connectionSummary(s.accounts))
    }

    // ---- numbers, letters and the folder watch ----

    private fun job(title: String, number: Int?, status: JobStatus? = null, created: Long = 0) = FolderJob(jobKeyOfTitle(title), number, title, status, "p", created)

    /** Every job's title and number, in the order the page lists them. */
    private fun numbered(s: JobsStore) = s.rows.sortedBy { it.order }.associate { it.title to it.label }

    private val day = 24L * 60 * 60 * 1000

    @Test fun jobsAreNumberedInTheOrderTheyWereAppliedForTheNewestFirstAndTheNumbersMoveWithThem() {
        // Riverton's folder is the newest, then Northgate's, then Lakeside's; Orphan's folder has no date at all.
        val list = mutableListOf(job("Lakeside", 6, JobStatus.REJECTED, created = 1 * day), job("Riverton", 3, created = 3 * day), job("Northgate", 2, created = 2 * day), job("Orphan", 9))
        val file = File(temp(), "jobs.json")
        val s = JobsStore(file, FakeMail(), { list.toList() }, scope = scope, clock = { 5_000L })
        assertEquals(listOf("Riverton" to "1", "Northgate" to "2", "Lakeside" to "3", "Orphan" to "4"), numbered(s).toList(), "newest application first; a job with no date last")
        assertTrue(s.rows.none { it.isNew }, "the ones there from the start are not new")
        s.hide(jobKeyOfTitle("Riverton"))
        assertEquals(listOf("Northgate" to "1", "Lakeside" to "2", "Orphan" to "3"), numbered(s).toList(), "the numbers close up: they are places in the list, not names")
        s.setStatus(jobKeyOfTitle("Northgate"), JobStatus.REJECTED)
        list.reverse()
        s.refreshFolders()
        assertEquals(listOf("Northgate" to "1", "Lakeside" to "2", "Orphan" to "3"), numbered(s).toList(), "a status, or the folder list coming in another order, changes nothing")
        val again = JobsStore(file, FakeMail(), { list.toList() }, scope = scope, clock = { 5_000L })
        again.unhideAll()
        assertEquals(listOf("Riverton" to "1", "Northgate" to "2", "Lakeside" to "3", "Orphan" to "4"), numbered(again).toList(), "and the same after a restart")
    }

    @Test fun aFolderAddedLaterIsPickedUpTakesItsPlaceByDateAndIsNewForAFewDays() {
        val list = mutableListOf(job("Northgate", 2, created = 1 * day), job("Riverton", 3, created = 2 * day))
        var now = 10 * day
        val s = JobsStore(null, FakeMail(), { list.toList() }, scope = scope, clock = { now })
        assertEquals(listOf("Riverton" to "1", "Northgate" to "2"), numbered(s).toList())
        list += job("Brand new job", 11, created = 10 * day)
        s.refreshFolders()
        val added = s.rows.single { it.title == "Brand new job" }
        assertEquals("1", added.label, "the newest application is number 1 and the others move down")
        assertEquals(listOf("Brand new job" to "1", "Riverton" to "2", "Northgate" to "3"), numbered(s).toList())
        assertTrue(added.isNew, "just found")
        assertTrue(s.rows.filter { it.title != "Brand new job" }.none { it.isNew })
        assertEquals(JobStatus.NOT_SEEN, added.status, "and beside it: no email yet")
        now += 4 * day
        s.refreshFolders() // nothing changed, nothing redrawn
        list += job("Another", 12, created = now)
        s.refreshFolders()
        assertTrue(!s.rows.single { it.title == "Brand new job" }.isNew, "a few days on it is no longer flagged")
        assertEquals("1", s.rows.single { it.title == "Another" }.label)
        assertEquals("2", s.rows.single { it.title == "Brand new job" }.label)
    }

    @Test fun aFolderThatLeavesTheListClosesUpAndComesBackInItsPlaceByDate() {
        val list = mutableListOf(job("One", 1, created = 3 * day), job("Two", 2, created = 2 * day), job("Three", 3, created = 1 * day))
        val s = JobsStore(null, FakeMail(), { list.toList() }, scope = scope)
        val two = list.removeAt(1)
        s.refreshFolders()
        assertEquals(listOf("One" to "1", "Three" to "2"), numbered(s).toList())
        list += job("Newcomer", 9, created = 9 * day)
        s.refreshFolders()
        assertEquals(listOf("Newcomer" to "1", "One" to "2", "Three" to "3"), numbered(s).toList())
        list += two
        s.refreshFolders()
        assertEquals(listOf("Newcomer" to "1", "One" to "2", "Two" to "3", "Three" to "4"), numbered(s).toList(), "back where its date puts it")
    }

    @Test fun theWatcherLooksAgainByItselfAndPicksUpANewFolder() {
        val list = mutableListOf(job("Northgate", 2, created = 1 * day))
        val s = JobsStore(null, FakeMail(), { list.toList() }, scope = scope)
        var ticks = 0
        val waits = mutableListOf<Long>()
        kotlinx.coroutines.runBlocking {
            try {
                s.watchFolders(everyMillis = 7, pause = { ms ->
                    waits += ms
                    ticks++
                    if (ticks == 2) list += job("Dropped into the folder", 5, created = 5 * day)
                    if (ticks > 3) throw kotlinx.coroutines.CancellationException("enough")
                })
            } catch (_: kotlinx.coroutines.CancellationException) {
            }
        }
        assertEquals(listOf(7L, 7L, 7L, 7L), waits)
        assertEquals(listOf("Dropped into the folder" to "1", "Northgate" to "2"), numbered(s).toList(), "the folder dropped in later is the newer application, so it is number 1")
    }

    @Test fun nothingIsRedrawnWhenTheFolderHasNotChanged() {
        val list = mutableListOf(job("Northgate", 2))
        val s = JobsStore(null, FakeMail(), { list.toList() }, scope = scope)
        val before = s.rows
        repeat(3) { s.refreshFolders() }
        assertTrue(before === s.rows, "the same list, not a new equal one")
    }

    @Test fun aMissingJobsFolderIsSaidAndStopsBeingSaidOnceItIsThere() {
        val parent = temp()
        val missing = File(parent, "1. Jobs Applying to now")
        val s = JobsStore(null, FakeMail(), { emptyList() }, scope = scope, jobsFolder = missing)
        assertTrue(s.folderProblem.orEmpty().contains("wasn't found") && missing.absolutePath in s.folderProblem.orEmpty(), s.folderProblem)
        missing.mkdirs()
        s.refreshFolders()
        assertNull(s.folderProblem)
    }

    @Test fun jobsFoundOnlyInEmailShareTheOneListByDateAndRenumberWhenANewerOneArrives() {
        val mail = FakeMail().apply {
            messages = listOf(
                EmailMessage("o1", "to1", "\"Old Co\" <jobs@oldco.example>", "Thank you for applying - Cleaner", 1_000, "Thank you for applying. We have received your application."),
                EmailMessage("n1", "tn1", "\"New Co\" <jobs@newco.example>", "Thank you for applying - Driver", 9_000, "Thank you for applying. We have received your application."),
            )
        }
        val file = File(temp(), "jobs.json")
        val s = JobsStore(file, mail, { folders }, scope = scope)
        s.connect(account)
        fun list() = s.rows.sortedBy { it.order }.map { it.label to it.title.substringBefore(" —") }
        // The two folder jobs have no date and no email, so they come after the jobs that do.
        assertEquals(listOf("1" to "Driver", "2" to "Cleaner"), list().take(2), "one list: the newest application is number 1, whether it has a folder or not (each named by its job title)")
        assertEquals(4, s.rows.size)
        assertTrue(s.rows.all { it.label.toIntOrNull() != null }, "every job has a number: there are no letters any more")
        mail.messages = mail.messages + EmailMessage("z1", "tz1", "\"Newest Co\" <jobs@newest.example>", "Thank you for applying - Porter", 20_000, "Thank you for applying. We have received your application.")
        s.scan(account)
        assertEquals(listOf("1" to "Porter", "2" to "Driver", "3" to "Cleaner"), list().take(3), "a newer application arrives and takes number 1; the others move down")
        val again = JobsStore(file, FakeMail(), { folders }, scope = scope)
        assertEquals(listOf("1" to "Porter", "2" to "Driver", "3" to "Cleaner"), again.rows.sortedBy { it.order }.map { it.label to it.title.substringBefore(" —") }.take(3), "and after a restart")
    }

    @Test fun theDateAJobWasAppliedForIsItsFirstApplicationEmailThenItsFirstEmailThenItsFolder() {
        val mail = FakeMail().apply {
            messages = listOf(
                // Northgate: acknowledged on day 5, rejected on day 9 — the application date is the acknowledgement's, not the later verdict's.
                EmailMessage("a1", "ta", "Northgate <careers@northgate.nhs.uk>", "Your application - Clinic Clerk", 5 * day, "Thank you for applying for Clinic Clerk at Northgate College Healthcare. We have received your application."),
                EmailMessage("a2", "ta", "Northgate <careers@northgate.nhs.uk>", "Your application - Clinic Clerk", 9 * day, "We regret to inform you that your application to Northgate College Healthcare NHS Trust was not successful."),
            )
        }
        val s = JobsStore(null, mail, { folders.map { it.copy(createdMillis = 2 * day) } }, scope = scope)
        s.connect(account)
        assertEquals(5 * day, s.rows.single { it.title.startsWith("Northgate") }.appliedMillis, "the first acknowledgement, not the later rejection and not the folder's date")
        assertEquals(2 * day, s.rows.single { it.title.startsWith("Lakeside") }.appliedMillis, "no email at all: when the folder was made")
        assertEquals("1", s.rows.single { it.title.startsWith("Northgate") }.label, "so Northgate is the more recent application")
    }

    @Test fun theOpenButtonsDoNothingInATestAndAskTheDesktopOtherwise() {
        val opened = mutableListOf<String>()
        val actions = object : JobsActions {
            override fun openUrl(url: String) { opened += "url:$url" }
            override fun openFolder(folder: File) { opened += "folder:${folder.name}" }
        }
        val s = JobsStore(actions = actions, scope = scope, jobsFolder = File("1. Jobs Applying to now"))
        s.openUrl("https://example.com")
        s.openFolder(s.jobsFolder)
        s.openFolder(null)
        assertEquals(listOf("url:https://example.com", "folder:1. Jobs Applying to now"), opened)
    }
}
