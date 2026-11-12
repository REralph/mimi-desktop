package com.example.a2desktop.data

import com.example.a2desktop.logic.EmailMessage
import com.example.a2desktop.logic.FolderJob
import com.example.a2desktop.logic.JobStatus
import com.example.a2desktop.logic.jobKeyOfTitle
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job

// Invented jobs and emails, used ONLY by the layout previews and the tests (never by the real app): a set of job folders and a fake
// mailbox with a few emails in the wording employers usually use. Nothing here is real.

private fun job(number: Int?, title: String, status: JobStatus? = null) = FolderJob(jobKeyOfTitle(title), number, title, status, "sample/$title")

/** Job folders like the user's own layout: some on the shelf, some in Rejected / Interview Stage. */
internal fun sampleJobFolders(): List<FolderJob> = listOf(
    job(1, "IT Technician Meadow Academy Trust", JobStatus.INTERVIEW),
    job(2, "Northgate College Healthcare NHS Trust Clinic Clerk - Maternity"),
    job(3, "IT Support Technician Riverton (Remote working)"),
    job(5, "Eastbury and Westbury Hospital NHS Foundation Trust", JobStatus.REJECTED),
    job(6, "Lakeside and Hillcrest NHS Foundation Trust Patient Pathway Coordinator", JobStatus.REJECTED),
    job(null, "Deskside Support Technician"),
)

/** A fake mailbox holding a handful of invented application emails; [clientReady] false makes the page show its Gmail setup steps. */
internal class SampleMail(private val clientReady: Boolean = true, private val connectedAtStart: Boolean = false, private val now: LocalDate = LocalDate.now()) : MailSource {
    private val connected = mutableSetOf<String>().also { if (connectedAtStart) it += DEFAULT_JOBS_ACCOUNT }

    private fun at(daysAgo: Long) = now.minusDays(daysAgo).atTime(10, 30).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private val messages = listOf(
        EmailMessage("s1", "t1", "\"NHS Jobs\" <noreply@jobs.nhs.uk>", "Application received", at(40), "Thank you for applying for Clinic Clerk - Maternity at Northgate College Healthcare NHS Trust. We have received your application."),
        EmailMessage("s2", "t2", "\"Northgate Recruitment\" <recruitment@northgate.nhs.uk>", "Interview invitation", at(12), "We would like to invite you to an interview for the Clinic Clerk post at Northgate College Healthcare NHS Trust on Tuesday at 10:30."),
        EmailMessage("s3", "t3", "\"NHS Jobs\" <noreply@jobs.nhs.uk>", "Application received", at(55), "Thank you for applying to Lakeside and Hillcrest NHS Foundation Trust. We have received your application."),
        EmailMessage("s4", "t4", "\"Lakeside Recruitment\" <recruitment@lakeside.nhs.uk>", "Your application", at(30), "Unfortunately your application to Lakeside and Hillcrest NHS Foundation Trust has not been successful on this occasion."),
        EmailMessage("s5", "t5", "\"Meadow Academy\" <hr@meadow.example.org>", "IT Technician", at(20), "Thank you for applying for the IT Technician post at Meadow Academy Trust."),
        EmailMessage("s6", "t6", "\"Meadow Academy\" <hr@meadow.example.org>", "IT Technician - next steps", at(6), "You have been shortlisted for the IT Technician post at Meadow Academy Trust. Please book a slot for a video interview."),
        EmailMessage("s7", "t7", "\"Eastbury and Westbury\" <jobs@eastwest.example.org>", "Outcome", at(70), "We regret to inform you that Eastbury and Westbury Hospital NHS Foundation Trust will not be progressing your application."),
        EmailMessage("s8", "t8", "\"Riverton Council\" <jobs@riverton.example.gov.uk>", "Application confirmation", at(3), "Your application for IT Support Technician in Riverton has been submitted."),
        EmailMessage("s9", "t9", "\"Acme Careers\" <jobs@acme.example.co.uk>", "Thank you for applying - Warehouse Operative", at(9), "Thank you for applying. We have received your application."),
        EmailMessage("s10", "t10", "Delivery <noreply@parcels.example.com>", "Your parcel", at(2), "Your parcel is on its way."),
    )

    override fun clientAvailable() = clientReady
    override fun isConnected(account: String) = account.lowercase() in connected
    override fun connect(account: String): MailResult<Unit> { connected += account.lowercase(); return MailResult.Ok(Unit) }
    override fun disconnect(account: String) { connected -= account.lowercase() }
    override fun fetchNew(account: String, query: String, skip: Set<String>, limit: Int, progress: (Int, Int) -> Unit): MailResult<FetchedMail> {
        val fresh = messages.filter { it.id !in skip }
        return MailResult.Ok(FetchedMail(fresh.take(limit), (fresh.size - limit).coerceAtLeast(0)))
    }
}

/** A store full of invented jobs, with the sample mailbox connected and read (or, with [connected] false, not connected yet). Memory-only and offline. */
internal fun sampleJobsStore(connected: Boolean = true, clientReady: Boolean = true): JobsStore {
    val mail = SampleMail(clientReady = clientReady)
    val store = JobsStore(
        mail = mail, folders = ::sampleJobFolders, scope = CoroutineScope(Job() + Dispatchers.Unconfined),
        jobsFolder = java.nio.file.Files.createTempDirectory("sample-jobs-folder").toFile().also { it.deleteOnExit() }, // a real (empty) folder, so no "not found" line
    )
    if (connected) store.connect(DEFAULT_JOBS_ACCOUNT)
    return store
}
