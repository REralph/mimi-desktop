package com.example.a2desktop

import com.example.a2desktop.logic.AccountEvent
import com.example.a2desktop.logic.EmailKind
import com.example.a2desktop.logic.EmailMessage
import com.example.a2desktop.logic.FolderJob
import com.example.a2desktop.logic.JobEvent
import com.example.a2desktop.logic.JobRow
import com.example.a2desktop.logic.JobStatus
import com.example.a2desktop.logic.appliedAt
import com.example.a2desktop.logic.assignNumbers
import com.example.a2desktop.logic.folderFamilies
import com.example.a2desktop.logic.looksLikeBulkMail
import com.example.a2desktop.logic.orderByApplied
import com.example.a2desktop.logic.classifyEmail
import com.example.a2desktop.logic.initialNumberOrder
import com.example.a2desktop.logic.deriveJobs
import com.example.a2desktop.logic.isFromAccount
import com.example.a2desktop.logic.jobSummary
import com.example.a2desktop.logic.jobKeyOfTitle
import com.example.a2desktop.logic.scanJobFolders
import com.example.a2desktop.logic.senderDomain
import com.example.a2desktop.logic.senderName
import com.example.a2desktop.logic.statusFromEvents
import com.example.a2desktop.logic.toJobEvent
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Jobs page's rules, with invented emails in the wording UK employers and recruiters usually use — none of them real. What an email
 * means, which job it belongs to, what the folders say, and what status it all adds up to.
 */
class JobTrackerTest {
    private val account = "alexrobinson@gmail.com"

    private fun kind(subject: String, body: String, fromMe: Boolean = false) = classifyEmail(subject, body, fromMe)?.kind

    // ---- what one email means ----

    @Test fun aRejectionIsRecognisedInTheWaysEmployersWriteIt() {
        listOf(
            "We regret to inform you that you have not been shortlisted for this role.",
            "Thank you for your interest. Unfortunately your application has not been successful on this occasion.",
            "After careful consideration we will not be progressing your application any further.",
            "We have decided to move forward with other candidates whose experience better matches the role.",
            "I'm sorry to say the vacancy has been filled.",
            "Unfortunately, we are unable to offer you an interview at this time.",
            "Your application was unsuccessful.",
        ).forEach { body -> assertEquals(EmailKind.REJECTION, kind("Update on your application", body), body) }
    }

    @Test fun anInterviewInvitationIsRecognisedWhenSomethingIsBeingArranged() {
        listOf(
            "We would like to invite you to an interview on Tuesday 18 November at 10:30.",
            "Congratulations, you have been shortlisted for the next stage. Please book a slot for a telephone interview.",
            "Please confirm your availability so we can arrange an interview.",
            "You are invited to attend an assessment centre at our Riverton office.",
        ).forEach { body -> assertEquals(EmailKind.INTERVIEW, kind("Your application for IT Technician", body), body) }
        assertEquals(EmailKind.INTERVIEW, kind("Interview invitation - IT Support Technician", "Details are below."))
    }

    @Test fun anOfferIsRecognisedByPhrasesOnlyAnOfferUses() {
        listOf(
            "We are pleased to offer you the position of IT Support Technician.",
            "I would like to offer you the role, subject to references.",
            "Please find attached your formal offer of employment.",
            "Congratulations! We are delighted to extend a conditional offer, pending pre-employment checks.",
            "You have been successful in your application and we would like to offer you the post.",
        ).forEach { body -> assertEquals(EmailKind.OFFER, kind("Good news", body), body) }
    }

    @Test fun anAcknowledgementIsAConfirmation() {
        listOf(
            "Thank you for applying for the role of Administrator. We have received your application.",
            "Your application for IT Help Desk Analyst has been submitted successfully.",
            "Application received — thanks for your interest in joining us.",
        ).forEach { body -> assertEquals(EmailKind.CONFIRMATION, kind("Application", body), body) }
    }

    @Test fun smallPrintInAnIfSentenceIsNotNews() {
        // A confirmation that only promises an interview IF shortlisted is still just a confirmation.
        val confirmation = "Thank you for applying. If you are shortlisted for interview we will be in touch within 4 weeks. " +
            "Should you be unsuccessful you will not be contacted."
        assertEquals(EmailKind.CONFIRMATION, kind("Application received", confirmation))
        // …and a rejection that offers to keep your details is a rejection, not an offer.
        assertEquals(
            EmailKind.REJECTION,
            kind("Your application", "Unfortunately you have not been successful. We will keep your details on file for future job offers."),
        )
    }

    @Test fun anOfferBeatsEverythingAndARejectionBeatsAConfirmationAndAnInterview() {
        assertEquals(EmailKind.OFFER, kind("Update", "Thank you for attending the interview. We are pleased to offer you the position."))
        assertEquals(EmailKind.REJECTION, kind("Update", "Thank you for applying and for attending the video interview. Unfortunately we will not be progressing your application."))
    }

    @Test fun emailsThatAreNotAboutAnApplicationAreIgnored() {
        assertNull(classifyEmail("Special offer: 20% off everything", "Hurry, this offer ends on Sunday."))
        assertNull(classifyEmail("Your parcel", "Unfortunately your parcel was delayed by the weather."))
        assertNull(classifyEmail("Interview tips weekly", "Ten ways to prepare for any interview. Unsubscribe here."))
        assertNull(classifyEmail("Your bank statement", "Your statement is ready."))
    }

    @Test fun yourOwnMailCanOnlyMeanYouApplied() {
        assertEquals(EmailKind.SENT_APPLICATION, kind("Application for IT Technician - Alex", "Please find my CV attached.", fromMe = true))
        assertNull(classifyEmail("Re: Interview on Tuesday", "Thank you for the invitation to interview, I can attend.", fromMe = true))
        assertTrue(isFromAccount("\"Alex\" <AlexRobinson@gmail.com>", account), "case doesn't matter")
        assertTrue(!isFromAccount("Recruiter <jobs@acme.co.uk>", account))
    }

    @Test fun theEvidenceIsTheSentenceThatShowsWhy() {
        val c = classifyEmail("Update", "Dear Alex. Thank you for your time. We regret to inform you that the role is no longer available. Kind regards.")!!
        assertEquals("We regret to inform you that the role is no longer available.", c.evidence)
        val long = classifyEmail("Update", "Dear Alex, " + "blah ".repeat(80) + "we regret to inform you of the outcome " + "more ".repeat(80) + "end.")!!
        assertTrue(c.evidence.length <= 175 && long.evidence.length <= 180, "an endless sentence is cut down (was ${long.evidence.length})")
        assertTrue("regret to inform" in long.evidence, "…around the part that matters")
    }

    @Test fun theSameGmailMailboxIsRecognisedWhateverItsSpelling() {
        assertTrue(com.example.a2desktop.logic.sameMailbox("robinson8@gmail.com", "Robinson8@Googlemail.com"), "googlemail.com is gmail.com")
        assertTrue(com.example.a2desktop.logic.sameMailbox("robin.son8@gmail.com", "robinson8+jobs@gmail.com"), "Gmail ignores dots and +tags")
        assertTrue(!com.example.a2desktop.logic.sameMailbox("robinson8@gmail.com", "robinson9@gmail.com"), "another mailbox is another mailbox")
        assertTrue(!com.example.a2desktop.logic.sameMailbox("a.b@example.com", "ab@example.com"), "other providers keep their dots")
        assertTrue(isFromAccount("\"Alex\" <Robinson8@googlemail.com>", "robinson8@gmail.com"), "mail from the mailbox, spelled the other way, is still yours")
    }

    @Test fun sendersAreSplitIntoNameAndDomain() {
        assertEquals("NHS Jobs", senderName("\"NHS Jobs\" <noreply@jobs.nhs.uk>"))
        assertEquals("jobs.nhs.uk", senderDomain("\"NHS Jobs\" <noreply@jobs.nhs.uk>"))
        assertEquals("jobs@acme.co.uk", senderName("<jobs@acme.co.uk>"))
        assertEquals("acme.co.uk", senderDomain("jobs@Acme.co.uk"))
        assertEquals("", senderDomain("no address"))
    }

    @Test fun anEmailKeepsOnlyWhatMattersNotTheMessage() {
        val message = EmailMessage("m1", "t1", "\"Acme Careers\" <jobs@acme.co.uk>", "Your application", 1_000, "Dear Alex, unfortunately your application for Carpenter was not successful. Secret detail 12345 about your salary.")
        val event = toJobEvent(message, account)!!
        assertEquals(EmailKind.REJECTION, event.kind)
        assertTrue("carpenter" in event.tokens && "acme" in event.tokens)
        assertTrue(event.tokens.none { it.all(Char::isDigit) }, "no bare numbers kept")
        assertNull(toJobEvent(message.copy(text = "Hello", subject = "Hi"), account))
    }

    // ---- what the emails add up to ----

    private fun event(kind: EmailKind, at: Long, thread: String = "t", subject: String = "s", from: String = "A <a@acme.co.uk>", tokens: List<String> = emptyList()) =
        JobEvent("m$at$kind", thread, kind, at, from, subject, "evidence", tokens)

    @Test fun theNewestVerdictDecidesAndAnAcknowledgementAloneMeansApplied() {
        assertNull(statusFromEvents(emptyList()))
        assertEquals(JobStatus.APPLIED, statusFromEvents(listOf(event(EmailKind.CONFIRMATION, 1))))
        assertEquals(JobStatus.APPLIED, statusFromEvents(listOf(event(EmailKind.SENT_APPLICATION, 1))))
        assertEquals(JobStatus.INTERVIEW, statusFromEvents(listOf(event(EmailKind.CONFIRMATION, 1), event(EmailKind.INTERVIEW, 2))))
        assertEquals(JobStatus.REJECTED, statusFromEvents(listOf(event(EmailKind.INTERVIEW, 2), event(EmailKind.REJECTION, 3), event(EmailKind.CONFIRMATION, 4))), "a later acknowledgement doesn't undo a rejection")
        assertEquals(JobStatus.OFFER, statusFromEvents(listOf(event(EmailKind.REJECTION, 1), event(EmailKind.OFFER, 2))), "a later offer wins")
        assertEquals(JobStatus.OFFER, statusFromEvents(listOf(event(EmailKind.REJECTION, 5), event(EmailKind.OFFER, 5))), "at the same moment an offer beats the rest")
    }

    // ---- the folders ----

    private fun tree(vararg dirs: String): File {
        val root = Files.createTempDirectory("jobs-folders").toFile()
        dirs.forEach { File(root, it).mkdirs() }
        return root
    }

    @Test fun everyFolderIsAJobAndTheStatusFoldersAreTheirHints() {
        val root = tree(
            "2. Northgate College Healthcare NHS Trust Clinic Clerk - Maternity/Job Advert_files",
            "10. IT & Communications Apprentice UK _ Army",
            "Deskside Support Technician",
            "0. Rejected/1. X St Anne's University Hospitals NHS Foundation Trust",
            "0. Rejected/Round 1/5. Junior Data Engineer(Apprentice)",
            "0.0. Interview Stage/Round 1/1. IT Technician Meadow Academy Trust",
        )
        File(root, "stray file.txt").writeText("not a folder")
        val jobs = scanJobFolders(root)
        val byTitle = jobs.associateBy { it.title }
        assertEquals(6, jobs.size, jobs.map { it.title }.toString())
        assertEquals(null, byTitle.getValue("Northgate College Healthcare NHS Trust Clinic Clerk - Maternity").folderStatus)
        assertEquals(2, byTitle.getValue("Northgate College Healthcare NHS Trust Clinic Clerk - Maternity").number)
        assertEquals(null, byTitle.getValue("Deskside Support Technician").number)
        assertEquals(JobStatus.REJECTED, byTitle.getValue("St Anne's University Hospitals NHS Foundation Trust").folderStatus, "the X the user adds is dropped from the title")
        assertEquals(JobStatus.REJECTED, byTitle.getValue("Junior Data Engineer(Apprentice)").folderStatus, "a Round folder is just a shelf")
        assertEquals(JobStatus.INTERVIEW, byTitle.getValue("IT Technician Meadow Academy Trust").folderStatus)
        assertTrue(jobs.none { it.title.contains("Job Advert_files") }, "a job's own files are never jobs")
        assertEquals(emptyList(), scanJobFolders(File(root, "does not exist")))
    }

    // ---- numbers and letters ----

    private fun fj(title: String, number: Int?, status: JobStatus? = null) = FolderJob(jobKeyOfTitle(title), number, title, status, "p")

    @Test fun foldersAreNumberedShelfFirstThenInterviewThenRejectedSoTheFirstShelfJobIsNumberOne() {
        val jobs = listOf(
            fj("St George", 1, JobStatus.REJECTED), fj("Meadow", 1, JobStatus.INTERVIEW), fj("Riverton", 3), fj("Northgate", 2), fj("Deskside", null), fj("Army", null),
            fj("Lakeside", 6, JobStatus.REJECTED),
        )
        val numbers = assignNumbers(emptyMap(), initialNumberOrder(jobs))
        val byNumber = numbers.entries.sortedBy { it.value }.map { e -> jobs.single { it.key == e.key }.title }
        assertEquals(listOf("Northgate", "Riverton", "Army", "Deskside", "Meadow", "St George", "Lakeside"), byNumber, "shelf by folder number (then name), then interview, then rejected")
        assertEquals((1..7).toList(), numbers.values.sorted())
        assertEquals(1, numbers.getValue(jobKeyOfTitle("Northgate")))
    }

    @Test fun aNumberIsKeptForGoodNeverReusedAndANewFolderGetsTheNextOne() {
        val a = fj("A job", 1)
        val b = fj("B job", 2)
        val c = fj("C job", 3)
        val first = assignNumbers(emptyMap(), listOf(a, b, c))
        assertEquals(listOf(1, 2, 3), listOf(a, b, c).map { first.getValue(it.key) })
        // The folder of B is gone and two new ones arrive: A and C keep theirs, B's number is not handed on, the new ones continue from the highest.
        val later = assignNumbers(first, listOf(fj("E job", 9), a, c, fj("D job", 4)))
        assertEquals(1, later.getValue(a.key))
        assertEquals(3, later.getValue(c.key))
        assertEquals(4, later.getValue(jobKeyOfTitle("E job")), "new ones are numbered in the order given (E came first)")
        assertEquals(5, later.getValue(jobKeyOfTitle("D job")))
        assertEquals(2, later.getValue(b.key), "B keeps its number reserved")
        // B comes back: still 2.
        assertEquals(2, assignNumbers(later, listOf(b)).getValue(b.key))
    }

    @Test fun lettersCountAToZThenAAAB() {
        assertEquals(listOf("A", "B", "C"), (0..2).map { com.example.a2desktop.logic.letterLabel(it) })
        assertEquals("Z", com.example.a2desktop.logic.letterLabel(25))
        assertEquals(listOf("AA", "AB", "AZ", "BA"), listOf(26, 27, 51, 52).map { com.example.a2desktop.logic.letterLabel(it) })
        val first = com.example.a2desktop.logic.assignLetters(emptyMap(), listOf("x", "y"))
        assertEquals(mapOf("x" to 0, "y" to 1), first)
        assertEquals(mapOf("x" to 0, "y" to 1, "z" to 2), com.example.a2desktop.logic.assignLetters(first, listOf("z", "y", "x")), "a letter once given stays; a new one gets the next")
    }

    @Test fun aJobKeepsItsKeyWhenItsFolderIsMovedOrRenumbered() {
        assertEquals(jobKeyOfTitle("IT Technician Meadow Academy Trust"), jobKeyOfTitle("  it technician   meadow academy trust "))
        val before = scanJobFolders(tree("3. IT Support Technician Riverton (Remote working)")).single()
        val after = scanJobFolders(tree("0. Rejected/7. IT Support Technician Riverton (Remote working)")).single()
        assertEquals(before.key, after.key)
    }

    // ---- which email is which job ----

    private val folderJobs = listOf(
        FolderJob(jobKeyOfTitle("Northgate College Healthcare NHS Trust Clinic Clerk - Maternity"), 2, "Northgate College Healthcare NHS Trust Clinic Clerk - Maternity", null, "p1"),
        FolderJob(jobKeyOfTitle("Lakeside and Hillcrest NHS Foundation Trust Patient Pathway Coordinator"), 6, "Lakeside and Hillcrest NHS Foundation Trust Patient Pathway Coordinator", JobStatus.REJECTED, "p2"),
        FolderJob(jobKeyOfTitle("IT Technician Meadow Academy Trust"), 1, "IT Technician Meadow Academy Trust", JobStatus.INTERVIEW, "p3"),
        FolderJob(jobKeyOfTitle("IT Support Technician Riverton (Remote working)"), 3, "IT Support Technician Riverton (Remote working)", null, "p4"),
        FolderJob(jobKeyOfTitle("Deskside Support Technician"), null, "Deskside Support Technician", null, "p5"),
    )

    private fun mail(id: String, thread: String, from: String, subject: String, at: Long, text: String) =
        AccountEvent(account, toJobEvent(EmailMessage(id, thread, from, subject, at, text), account)!!)

    private fun row(rows: List<com.example.a2desktop.logic.JobRow>, title: String) = rows.single { it.title.startsWith(title) }

    @Test fun anEmailThatNamesTheEmployerIsMatchedToItsFolderEvenWithoutTheRole() {
        val events = listOf(
            mail("1", "tA", "\"NHS Jobs\" <noreply@jobs.nhs.uk>", "Your application", 100, "Thank you for applying to Lakeside and Hillcrest NHS Foundation Trust. We have received your application."),
            mail("2", "tB", "\"Lakeside Recruitment\" <recruitment@lakeside.nhs.uk>", "Outcome", 200, "Unfortunately we will not be progressing your application with Lakeside and Hillcrest NHS Foundation Trust."),
        )
        val rows = deriveJobs(folderJobs, events)
        val job = row(rows, "Lakeside and Hillcrest")
        assertEquals(JobStatus.REJECTED, job.status)
        assertEquals(JobStatus.REJECTED, job.auto)
        assertEquals(account, job.account)
        assertEquals(2, job.events.size, "the acknowledgement and the verdict, from two threads, are one job")
        assertNull(job.note, "the folder already says rejected")
        assertEquals(folderJobs.size, rows.size, "no extra row")
    }

    @Test fun theStatusIsTheNewestEmailAndTheNoteSaysWhenTheFolderLagsBehind() {
        val events = listOf(
            mail("1", "t1", "Northgate <careers@northgate.nhs.uk>", "Application received", 100, "Thank you for applying for Clinic Clerk - Maternity at Northgate College Healthcare NHS Trust."),
            mail("2", "t2", "Northgate <careers@northgate.nhs.uk>", "Interview", 300, "We would like to invite you to an interview for the Clinic Clerk role at Northgate College Healthcare."),
        )
        val job = row(deriveJobs(folderJobs, events), "Northgate College")
        assertEquals(JobStatus.INTERVIEW, job.status)
        assertEquals("Emails say interview — the folder isn't in 0.0. Interview Stage yet.", job.note)
        assertEquals(EmailKind.INTERVIEW, job.lastEvent?.kind)
    }

    @Test fun withNoEmailAJobShowsWhatItsFolderSaysOrNothingYet() {
        val rows = deriveJobs(folderJobs, emptyList())
        assertEquals(JobStatus.REJECTED, row(rows, "Lakeside").status)
        assertEquals(JobStatus.INTERVIEW, row(rows, "IT Technician Meadow").status)
        assertEquals(JobStatus.NOT_SEEN, row(rows, "Deskside").status)
        assertNull(row(rows, "Deskside").auto)
        assertNull(row(rows, "Deskside").account)
    }

    @Test fun anApplicationNoFolderMatchesGetsARowOfItsOwnAndItsThreadsAreJoined() {
        val events = listOf(
            mail("1", "x1", "\"Acme Careers\" <jobs@acme.co.uk>", "Thank you for applying - Warehouse Operative", 100, "Thank you for applying. We have received your application."),
            mail("2", "x2", "\"Acme Careers\" <jobs@acme.co.uk>", "Re: Warehouse Operative application", 500, "Unfortunately you have not been successful on this occasion."),
            mail("3", "y1", "\"Zed Ltd\" <hr@zed.com>", "Thank you for applying - Driver", 200, "Thank you for applying. We have received your application."),
        )
        val rows = deriveJobs(folderJobs, events)
        val extra = rows.filter { it.folder == null }
        assertEquals(2, extra.size, extra.map { it.title }.toString())
        val acme = extra.single { it.title == "Warehouse Operative" }
        assertEquals(JobStatus.REJECTED, acme.status, "Acme's two threads share a subject word, so they are one job — named by its title alone, not 'Acme Careers — …'")
        assertEquals(2, acme.events.size)
        assertEquals(JobStatus.APPLIED, extra.single { it.title == "Driver" }.status)
        assertTrue(acme.key.startsWith("mail:"))
    }

    @Test fun twoEmployersWithDifferentRolesAreNotMixedUp() {
        val events = listOf(
            mail("1", "n1", "\"NHS Jobs\" <noreply@jobs.nhs.uk>", "Application received - Ward Clerk", 100, "Thank you for applying. We have received your application."),
            mail("2", "n2", "\"NHS Jobs\" <noreply@jobs.nhs.uk>", "Application received - Porter", 150, "Thank you for applying. We have received your application."),
        )
        assertEquals(2, deriveJobs(emptyList(), events).size)
    }

    @Test fun anEmailThatNamesAWholeTitleIsMatchedToThatTitleNotToALongerOne() {
        val twins = listOf(
            FolderJob(jobKeyOfTitle("Acme Warehouse Operative"), 1, "Acme Warehouse Operative", null, "a"),
            FolderJob(jobKeyOfTitle("Acme Warehouse Operative Nights"), 2, "Acme Warehouse Operative Nights", null, "b"),
        )
        val e = mail("1", "t", "Acme <jobs@acme.co.uk>", "Application", 100, "Thank you for applying for the Acme Warehouse Operative post. We have received your application.")
        val rows = deriveJobs(twins, listOf(e))
        // "nights" is only in the second name: the email covers the first name completely and the second only partly.
        assertEquals(JobStatus.APPLIED, rows.single { it.title == "Acme Warehouse Operative" }.status)
        assertEquals(JobStatus.NOT_SEEN, rows.single { it.title == "Acme Warehouse Operative Nights" }.status)
        assertEquals(2, rows.size, "and no row of its own")
    }

    @Test fun anEmailThatOnlySharesCommonWordsWithAJobIsNotMatchedToIt() {
        val jobs = listOf(
            FolderJob(jobKeyOfTitle("Deskside Support Technician"), 1, "Deskside Support Technician", null, "a"),
            FolderJob(jobKeyOfTitle("Network Support Technician Riverton"), 2, "Network Support Technician Riverton", null, "b"),
            FolderJob(jobKeyOfTitle("Support Technician Night Shift Cover"), 3, "Support Technician Night Shift Cover", null, "c"),
        )
        // "support technician" is in every one of the names, so on its own it says nothing about WHICH job this is (and the first fits it best).
        val e = mail("1", "t", "Foo Ltd <jobs@foo.example>", "Application received - Support Technician", 100, "Thank you for applying for the Support Technician role. We have received your application.")
        val rows = deriveJobs(jobs, listOf(e))
        assertTrue(rows.filter { it.folder != null }.all { it.status == JobStatus.NOT_SEEN }, "none of the three folders is given it")
        assertEquals(1, rows.count { it.folder == null }, "it gets a row of its own instead: ${rows.map { it.title }}")
    }

    @Test fun anEmailThatFitsTwoJobsEquallyWellIsNotGuessedAt() {
        val two = listOf(
            FolderJob(jobKeyOfTitle("Acme Cleaner"), 1, "Acme Cleaner", null, "a"),
            FolderJob(jobKeyOfTitle("Acme Driver"), 2, "Acme Driver", null, "b"),
        )
        val e = mail("1", "t", "Acme <jobs@acme.co.uk>", "Application", 100, "Thank you for applying for the Acme cleaner or driver post. We have received your application.")
        val rows = deriveJobs(two, listOf(e))
        assertEquals(3, rows.size, "neither folder is given it; it gets a row of its own: ${rows.map { it.title }}")
        assertTrue(rows.filter { it.folder != null }.all { it.status == JobStatus.NOT_SEEN })
    }

    @Test fun yourOwnChoiceBeatsTheEmailsAndAHiddenJobIsLeftOut() {
        val events = listOf(mail("1", "t1", "Northgate <careers@northgate.nhs.uk>", "Update", 100, "Unfortunately your application to Northgate College Healthcare was unsuccessful."))
        val key = folderJobs[0].key
        val chosen = row(deriveJobs(folderJobs, events, overrides = mapOf(key to JobStatus.INTERVIEW)), "Northgate College")
        assertEquals(JobStatus.INTERVIEW, chosen.status)
        assertTrue(chosen.overridden)
        assertEquals(JobStatus.REJECTED, chosen.auto, "what the emails say is still known")
        assertTrue(deriveJobs(folderJobs, events, hidden = setOf(key)).none { it.key == key })
        assertNotNull(deriveJobs(folderJobs, events).firstOrNull { it.key == key })
    }

    // ---- Accepted: a job you got ----

    @Test fun noEmailEverMeansAcceptedOnlyYouCanSayYouTookTheJob() {
        val kinds = EmailKind.entries
        // Every combination of up to three emails, in any order and on any days: the status they add up to is never Accepted.
        for (a in kinds) for (b in kinds) for (c in kinds) {
            val status = statusFromEvents(listOf(event(a, 1), event(b, 2), event(c, 3)))
            assertTrue(status != JobStatus.ACCEPTED, "$a $b $c gave $status")
        }
        assertEquals("Accepted", JobStatus.ACCEPTED.label)
        val row = deriveJobs(folderJobs, emptyList(), overrides = mapOf(folderJobs[0].key to JobStatus.ACCEPTED)).first { it.key == folderJobs[0].key }
        assertEquals(JobStatus.ACCEPTED, row.status)
        assertTrue(row.overridden)
    }

    @Test fun aRejectionIsShownAsUnsuccessfulButYourFolderIsStillCalledRejected() {
        assertEquals("Unsuccessful", JobStatus.REJECTED.label, "what the status says on the page")
        assertEquals("Rejected", JobStatus.REJECTED.folderName, "your folder 0. Rejected keeps its own name")
        assertEquals("Interview", JobStatus.INTERVIEW.folderName)
        val events = listOf(mail("1", "t1", "Northgate <careers@northgate.nhs.uk>", "Update", 100, "Unfortunately your application to Northgate College Healthcare was unsuccessful."))
        val row = row(deriveJobs(folderJobs, events), "Northgate College")
        assertEquals(JobStatus.REJECTED, row.status)
        assertEquals("Emails say unsuccessful — the folder isn't in 0. Rejected yet.", row.note, "the note says unsuccessful but still names the folder as you named it")
        assertTrue(jobSummary(listOf(row)).endsWith("1 unsuccessful"), jobSummary(listOf(row)))
    }
    @Test fun theSummaryCountsAcceptedJobsFirst() {
        fun r(i: Int, s: JobStatus) = JobRow("k$i", "t$i", null, null, null, s, false, null, null, emptyList())
        assertEquals("4 jobs · 1 accepted · 1 offer · 1 applied · 1 unsuccessful", jobSummary(listOf(r(1, JobStatus.ACCEPTED), r(2, JobStatus.OFFER), r(3, JobStatus.APPLIED), r(4, JobStatus.REJECTED))))
        assertEquals("2 jobs · 2 accepted", jobSummary(listOf(r(1, JobStatus.ACCEPTED), r(2, JobStatus.ACCEPTED))))
    }

    // ---- when a job was applied for, and the order of the list ----

    @Test fun aJobWasAppliedForWhenItsFirstApplicationEmailSaysThenItsFirstEmailThenWhenItsFolderWasMade() {
        val ack = event(EmailKind.CONFIRMATION, 5_000)
        val sent = event(EmailKind.SENT_APPLICATION, 4_000)
        val rejection = event(EmailKind.REJECTION, 9_000)
        val interview = event(EmailKind.INTERVIEW, 2_000)
        assertEquals(4_000, appliedAt(listOf(rejection, ack, sent), 1_000), "the earliest application or acknowledgement, not the folder's date")
        assertEquals(5_000, appliedAt(listOf(ack, rejection, interview), 1_000), "an interview email dated before the acknowledgement is not the application")
        assertEquals(2_000, appliedAt(listOf(rejection, interview), 1_000), "no acknowledgement at all: the earliest email of any kind")
        assertEquals(1_000, appliedAt(emptyList(), 1_000), "no email: when the folder was made")
        assertEquals(0, appliedAt(emptyList(), 0), "nothing says")
    }

    @Test fun theListIsTheMostRecentApplicationFirstUndatedJobsLastAndTiesByName() {
        fun r(title: String, at: Long) = JobRow(title, title, null, null, null, JobStatus.NOT_SEEN, false, null, null, emptyList(), appliedMillis = at)
        val rows = listOf(r("Old", 1_000), r("Undated", 0), r("Newest", 9_000), r("B same day", 5_000), r("A same day", 5_000))
        assertEquals(listOf("Newest", "A same day", "B same day", "Old", "Undated"), orderByApplied(rows).map { it.title })
    }

    @Test fun aFoldersCreationDateIsReadAndOnlyTheFolderItselfIsLookedAt() {
        val before = System.currentTimeMillis() - 60_000
        val job = scanJobFolders(tree("Acme Cleaner/Job Advert_files")).single()
        assertTrue(job.createdMillis >= before, "a folder just made has a date from just now: ${job.createdMillis}")
    }

    // ---- the same job twice: folders that are one job, and emails about one job ----

    private fun folder(title: String, status: JobStatus? = null) = FolderJob(jobKeyOfTitle(title), null, title, status, "p-$title")

    private val armyFolders = listOf(
        folder("Army"), folder("IT & Communications Apprentice UK _ Army", JobStatus.REJECTED),
        folder("IT Technician Meadow Academy Trust"), folder("15.4 IT Technician (Seasonal Surrey)"), folder("Deskside Support Technician"),
    )

    @Test fun foldersThatAreTheSameJobGoTogetherAndAGenericWordNeverGluesDifferentJobs() {
        val families = folderFamilies(armyFolders).map { family -> family.map { it.title } }
        assertEquals(listOf("IT & Communications Apprentice UK _ Army", "Army"), families.first { "Army" in it }, "the fullest name stands for the job and comes first")
        assertEquals(4, families.size, "five folders, one pair: $families")
        assertTrue(families.filter { it.size > 1 }.size == 1, "the three technician jobs share the word 'technician' with each other but are different jobs: $families")
        // With only two folders sharing a short name, they are taken to be one job.
        assertEquals(1, folderFamilies(listOf(folder("Porter"), folder("Hospital Porter Nights"))).size)
        // But a short name that other jobs use too is generic.
        assertEquals(3, folderFamilies(listOf(folder("Porter"), folder("Hospital Porter Nights"), folder("Porter Weekends Clinic"))).size)
        assertEquals(emptyList(), folderFamilies(emptyList()))
    }

    @Test fun anEmailAboutOneOfTwoFoldersOfTheSameJobJoinsTheCombinedRowInsteadOfMakingAThird() {
        val survey = mail("1", "t1", "Survey <noreply@survey.example>", "Tell us about your recent meeting", 100, "Dear Sam, thank you for applying to join the British Army. Well done on progressing.")
        val rows = deriveJobs(armyFolders, listOf(survey))
        assertEquals(4, rows.size, "the two Army folders are one row, and the email made no row of its own: ${rows.map { it.title }}")
        val army = rows.single { it.title.startsWith("IT & Communications") }
        assertEquals(listOf("Army"), army.alsoFolders.map { it.title }, "the row says which other folder it also is")
        assertEquals(1, army.events.size, "and it holds the email")
        assertTrue(rows.none { it.title == "Army" }, "the short folder is not a row of its own")
    }

    @Test fun aStatusYouSetOnEitherFolderOfACombinedJobCountsAndHidingEitherHidesTheJob() {
        val key = armyFolders[0].key // the short "Army" folder, which is not the one that stands for the job
        val set = deriveJobs(armyFolders, emptyList(), overrides = mapOf(key to JobStatus.OFFER)).single { it.title.startsWith("IT & Communications") }
        assertEquals(JobStatus.OFFER, set.status)
        assertTrue(set.overridden)
        assertTrue(deriveJobs(armyFolders, emptyList(), hidden = setOf(key)).none { it.title.contains("Army") }, "hiding the short folder's key hides the whole job")
        assertTrue(deriveJobs(armyFolders, emptyList(), hidden = setOf(armyFolders[1].key)).none { it.title.contains("Army") }, "and so does hiding the fuller one")
        assertEquals(3, deriveJobs(armyFolders, emptyList(), hidden = setOf(key)).size, "four jobs, one hidden")
    }

    @Test fun theCombinedJobIsAppliedForWhenItsEarliestFolderWasMade() {
        val dated = listOf(armyFolders[0].copy(createdMillis = 5_000), armyFolders[1].copy(createdMillis = 9_000)) + armyFolders.drop(2)
        assertEquals(5_000, deriveJobs(dated, emptyList()).single { it.title.startsWith("IT & Communications") }.appliedMillis)
    }

    @Test fun emailOnlyJobsAreNamedByTheirTitleAndJoinedWhenTheyAreTheSameJob() {
        val rows = deriveJobs(
            emptyList(),
            listOf(
                mail("1", "t1", "Trac <noreply@trac.example>", "Application for job 200-NN-7662939-A&C-B Ward Clerk - AMU", 100, "Thank you for applying. We have received your application."),
                mail("2", "t2", "Trac <noreply@recruit.trac.example>", "Application for vacancy 200-NN-7662939-A&C-B - Ward Clerk - AMU", 200, "Unfortunately your application was unsuccessful."),
                mail("3", "t3", "reed.co.uk <no-reply@jobs.reed.example>", "Your job application for 'IT Support Engineer'", 300, "Thank you for applying. We have received your application."),
            ),
        )
        assertEquals(listOf("IT Support Engineer", "Ward Clerk - AMU"), rows.map { it.title }.sorted(), "two jobs: the two Trac threads are one, and no 'Reed.co.uk —' in front")
        val ward = rows.single { it.title == "Ward Clerk - AMU" }
        assertEquals(2, ward.events.size)
        assertEquals(JobStatus.REJECTED, ward.status, "the acknowledgement and the later verdict are one job")
        assertEquals(100, ward.appliedMillis, "applied when the first of them came")
    }

    @Test fun emailsThatNameOnlyAnEmployerJoinOtherEmailsFromThatEmployerAndNobodyElse() {
        val ack = "Thank you for applying. We have received your application."
        val rows = deriveJobs(
            emptyList(),
            listOf(
                mail("1", "t1", "jobboard <no-reply@jobs.jobboard.example>", "Your application with hireful", 100, ack), // a job board's copy: names the employer only
                mail("2", "t2", "Hireful <noreply@mail.hireful.example>", "update on your application from hireful", 300, "Unfortunately your application was unsuccessful."),
                mail("3", "t3", "Hireful <jobs@hireful.example>", "You've successfully applied for the ICT Technician - Schools vacancy", 50, ack), // names the job, sent from hireful's own domain
                mail("4", "t4", "jobboard <no-reply@jobs.jobboard.example>", "We've sent your application to Cerco", 200, ack), // a different employer
            ),
        )
        assertEquals(listOf("Cerco", "ICT Technician - Schools"), rows.map { it.title }.sorted(), "the three about hireful are one job; Cerco is another: ${rows.map { it.title }}")
        val hireful = rows.single { it.title == "ICT Technician - Schools" }
        assertEquals(3, hireful.events.size)
        assertEquals(JobStatus.REJECTED, hireful.status, "its newest news is the rejection")
        assertEquals(50, hireful.appliedMillis, "applied when the first of them came")
    }

    @Test fun twoGenericTitlesFromDifferentSendersAreNotTheSameJobButAGenericOneFromTheSameSenderIs() {
        fun ack(id: String, from: String, at: Long) = mail(id, "t$id", from, "Application for job 1-A-2 IT Technician", at, "Thank you for applying. We have received your application.")
        val apart = deriveJobs(emptyList(), listOf(ack("1", "A <noreply@firm-one.example>", 1), ack("2", "B <noreply@firm-two.example>", 2)))
        assertEquals(2, apart.size, "an 'IT Technician' at two different employers is two jobs")
        val together = deriveJobs(emptyList(), listOf(ack("1", "A <noreply@jobs.firm-one.example>", 1), ack("2", "A <noreply@recruit.firm-one.example>", 2)))
        assertEquals(1, together.size, "the same title from one employer's system is one job")
        val specific = deriveJobs(emptyList(), listOf(mail("1", "t1", "A <n@one.example>", "Application for job 1-A-2 Trainee Field Service Engineer", 1, "Thank you for applying. We have received your application."), mail("2", "t2", "B <n@two.example>", "Your application to our role of Trainee Field Service Engineer", 2, "We regret to inform you that your application was not successful.")))
        assertEquals(1, specific.size, "a specific title of three or more words is the same job whoever sends it")
    }

    // ---- what the first real scan got wrong (invented sentences of the same shapes) ----

    @Test fun aReceiptThatCongratulatesYouOnApplyingIsNotAnOffer() {
        assertEquals(EmailKind.CONFIRMATION, classifyEmail("Your application with Acme", "We've sent your application to the recruiter. Congratulations Sam, you've successfully applied for the following job on a job board.")?.kind)
        assertEquals(EmailKind.OFFER, classifyEmail("Good news", "Congratulations, you have been successful and we would like to confirm the role.")?.kind, "a real 'successful' still counts")
        assertEquals(EmailKind.OFFER, classifyEmail("Good news", "Congratulations! We are pleased to offer you the position of Porter.")?.kind)
    }

    @Test fun beingShortlistedForAJobIsAnInterviewButAnOrganisationShortlistedForAwardsIsNot() {
        assertNull(classifyEmail("Alumni News", "The University has been shortlisted for three Higher Education awards this year."))
        assertEquals(EmailKind.INTERVIEW, classifyEmail("Update", "Your application has been shortlisted for the next stage.")?.kind)
        assertEquals(EmailKind.INTERVIEW, classifyEmail("Update", "You have been shortlisted for the IT Technician post.")?.kind)
        assertEquals(EmailKind.REJECTION, classifyEmail("Update", "Unfortunately you have not been shortlisted for this role.")?.kind, "not shortlisted is still a rejection")
    }

    @Test fun massMailIsIgnoredWhateverWordsItUsesButEmployersAndRecruitingSystemsAreNot() {
        val me = "me@gmail.com"
        fun ev(from: String, subject: String, text: String) = toJobEvent(EmailMessage("m", "t", from, subject, 1, text), me)
        assertNull(ev("LinkedIn <notifications-noreply@linkedin.com>", "Why a virtual interview no-show can be a red flag", "Tips for your video interview."), "a networking site's tips digest")
        assertNull(ev("Alumni team <alumni@uni.ac.uk>", "Alumni News: save the date", "We would like to invite you to an interview about giving."), "a university alumni mailing")
        assertNull(ev("Phone Support <noreply@support.phonemaker.com>", "Re: Email Number 123", "Unfortunately your application for a repair was not successful."), "a customer-support desk")
        assertNull(ev("Jobs <hello@acme.com>", "Job alerts for you: 12 new jobs", "We would like to invite you to an interview."), "a job-alert digest")
        assertEquals(EmailKind.INTERVIEW, ev("\"Department for Work & Pensions\" <noreply@dwpjobs.tal.net>", "Invite to Pre Recorded Video Interview", "Dear Sam, we would like to invite you to a video interview.")?.kind)
        assertEquals(EmailKind.CONFIRMATION, ev("jobboard <no-reply@jobs.jobboard.co.uk>", "Your application with Acme", "You've successfully applied for the following job.")?.kind)
        assertEquals(EmailKind.INTERVIEW, ev("\"Walker, Jamie\" <jamie.walker@hotel.example>", "First stage interview - Hotel Airport", "Hi Sam, we would like to arrange a first stage interview.")?.kind)
        assertEquals(EmailKind.SENT_APPLICATION, ev("Me <me@gmail.com>", "My application for the support role", "Dear team")?.kind, "your own mail is never taken for mass mail")
    }

    @Test fun massMailIsRecognisedByTheNamePartTheFirstPartOfTheDomainAndTheSubjectOnly() {
        assertTrue(looksLikeBulkMail("x <notifications-noreply@linkedin.com>", "s"))
        assertTrue(looksLikeBulkMail("x <alumni@uni.ac.uk>", "s"))
        assertTrue(looksLikeBulkMail("x <noreply@support.example.com>", "s"))
        assertTrue(looksLikeBulkMail("x <a@b.com>", "Our monthly Newsletter"))
        assertFalse(looksLikeBulkMail("x <noreply@dwpjobs.tal.net>", "Vacancy 749"))
        assertFalse(looksLikeBulkMail("x <no-reply@jobs.jobboard.co.uk>", "s"))
        assertFalse(looksLikeBulkMail("x <careers@supportservices.org>", "s"), "'support' inside a longer name is not a support desk")
        assertFalse(looksLikeBulkMail("x <recruitment@acme.co.uk>", "Your application"))
    }
}
