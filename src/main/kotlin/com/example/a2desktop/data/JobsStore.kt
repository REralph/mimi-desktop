package com.example.a2desktop.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.a2desktop.logic.AccountEvent
import com.example.a2desktop.logic.CLASSIFIER_VERSION
import com.example.a2desktop.logic.FolderJob
import com.example.a2desktop.logic.JobEvent
import com.example.a2desktop.logic.JobRow
import com.example.a2desktop.logic.JobStatus
import com.example.a2desktop.logic.assignNumbers
import com.example.a2desktop.logic.deriveJobs
import com.example.a2desktop.logic.initialNumberOrder
import com.example.a2desktop.logic.orderByApplied
import com.example.a2desktop.logic.sameMailbox
import com.example.a2desktop.logic.scanJobFolders
import com.example.a2desktop.logic.toJobEvent
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

// The Jobs page's state: the mailboxes (each with what its emails said about applications), the jobs worked out from them and from the
// folders in `1. Work\1. Jobs Applying to now`, and the user's own choices (a status they set by hand, jobs they hid). Kept in
// `%APPDATA%\a2-desktop\jobs.json`. Only the small event each application email boils down to is kept — never the email — and the keys
// that let the app read the mailbox live in a separate file (see FileTokenStore), so this file holds no secrets.

/** The user's own mailbox: the first section of the page until another is added. */
const val DEFAULT_JOBS_ACCOUNT = "robinson8@gmail.com"

/** The Gmail search that finds mail about applications. Wide on purpose: the rules in classifyEmail decide what really is one. */
fun jobsQuery(lookbackDays: Int = 365): String =
    "newer_than:${lookbackDays}d -in:spam -in:trash " +
        "(subject:(application OR applying OR applied OR interview OR unsuccessful OR shortlisted OR offer OR vacancy OR candidate) " +
        "OR \"thank you for applying\" OR \"your application\" OR \"regret to inform\" OR \"not been successful\" OR \"pleased to offer\" OR \"invite you to\")"

/** One mailbox as the page shows it. */
data class JobAccount(val email: String, val connected: Boolean, val lastScanMillis: Long, val emailsFound: Int)

/** The Connect page's one-line answer: "Connected: a@x.com, b@y.com", "Not connected", or "No mailbox added". */
fun connectionSummary(accounts: List<JobAccount>): String {
    val connected = accounts.filter { it.connected }
    return when {
        connected.isNotEmpty() -> "Connected: " + connected.joinToString(", ") { it.email }
        accounts.isEmpty() -> "No mailbox added"
        else -> "Not connected"
    }
}

/** What the page can ask the PC to do that isn't about data: open an address in the browser, open a folder in Explorer. */
interface JobsActions {
    fun openUrl(url: String)
    fun openFolder(folder: File)

    /** Does nothing (tests and previews: a click must never open a real window). */
    object None : JobsActions {
        override fun openUrl(url: String) = Unit
        override fun openFolder(folder: File) = Unit
    }
}

@Serializable
internal data class JobAccountFile(
    val email: String,
    val lastScanMillis: Long = 0,
    val events: List<JobEvent> = emptyList(),
    /** Every message already looked at (application or not), so a scan only reads new ones. */
    val seen: List<String> = emptyList(),
    val classifier: Int = CLASSIFIER_VERSION,
)

@Serializable
internal data class JobsFile(
    val accounts: List<JobAccountFile> = emptyList(),
    val overrides: Map<String, JobStatus> = emptyMap(),
    val hidden: List<String> = emptyList(),
    /** Jobs the person deleted from Mimi's list for good (the bar's Delete): unlike [hidden] the "Show hidden" button does not bring these back. Folders on disk are never touched. */
    val deleted: List<String> = emptyList(),
    /** The number each folder job was given (never changes, never reused, kept even if the folder goes away), by the job's key. */
    val numbers: Map<String, Int> = emptyMap(),
    /** When each numbered job was first noticed; 0 for the ones there when numbering began (they are not "new"). */
    val noticed: Map<String, Long> = emptyMap(),
    /** The letter (0 = A) each job found only in email was given, by key — same rules as [numbers]. */
    val letters: Map<String, Int> = emptyMap(),
)

/** How often the jobs folder is looked at again by itself, and how long a newly found folder is flagged "New". */
const val FOLDER_WATCH_MILLIS = 5_000L
private const val NEW_FOR_MILLIS = 3L * 24 * 60 * 60 * 1000
private const val DAY_MILLIS = 24L * 60 * 60 * 1000

/** How many messages one pass reads, and how many passes one press of Scan makes. */
private const val BATCH = 300
private const val MAX_PASSES = 3

class JobsStore(
    private val file: File? = null,
    private val mail: MailSource? = null,
    private val folders: () -> List<FolderJob> = { emptyList() },
    private val clientFile: GmailClientFile? = null,
    private val downloadsDir: File? = null,
    private val actions: JobsActions = JobsActions.None,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val clock: () -> Long = System::currentTimeMillis,
    /** The folder holding one folder per job (shown on the page, opened by its button). */
    val jobsFolder: File? = null,
    private val lookbackDays: Int = 365,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = Any()
    private var data: JobsFile = load()
    private var folderJobs: List<FolderJob> = emptyList()
    private var computed = false

    /** The jobs, newest news first within each section is the page's business; this is simply every job not hidden. */
    var rows by mutableStateOf<List<JobRow>>(emptyList())
        private set
    var accounts by mutableStateOf<List<JobAccount>>(emptyList())
        private set

    /** What is happening or went wrong, in plain words, or null. */
    var message by mutableStateOf<String?>(null)
        private set
    var busy by mutableStateOf(false)
        private set

    /** The mailbox being read right now (or null). */
    var busyAccount by mutableStateOf<String?>(null)
        private set

    /** How far a scan is, e.g. "Reading 120 of 300 emails…", or null. */
    var progress by mutableStateOf<String?>(null)
        private set

    /** Whether the Google client file is in place, and any freshly downloaded ones found in Downloads. */
    var clientReady by mutableStateOf(false)
        private set
    var downloadedClients by mutableStateOf<List<File>>(emptyList())
        private set
    var hiddenCount by mutableStateOf(0)
        private set

    /** Set when the jobs folder can't be found (the page says where it looked); null when it is there. */
    var folderProblem by mutableStateOf<String?>(null)
        private set

    /** Whether this store can reach Gmail at all (false in previews and tests without a mail source). */
    val online: Boolean get() = mail != null

    val appFolder: File? get() = clientFile?.location?.parentFile

    init {
        refreshFolders()
        checkSetup()
    }

    // ---- reading what is there ----

    /**
     * Looks at the job folders again. A folder not seen before is given the next number (the first time ever, every folder is numbered
     * at once, the shelf first); nothing is redrawn when nothing changed. Safe to call from any thread.
     */
    fun refreshFolders() {
        val found = runCatching(folders).getOrDefault(emptyList())
        folderProblem = jobsFolder?.takeIf { !it.isDirectory }?.let { "Your jobs folder wasn't found: ${it.absolutePath}" }
        val changed = synchronized(lock) {
            val firstTime = data.numbers.isEmpty()
            val numbered = assignNumbers(data.numbers, initialNumberOrder(found))
            val fresh = numbered.keys - data.numbers.keys
            if (fresh.isNotEmpty()) {
                data = data.copy(numbers = numbered, noticed = data.noticed + fresh.associateWith { if (firstTime) 0L else clock() })
                save()
            }
            val foldersChanged = found != folderJobs
            folderJobs = found
            fresh.isNotEmpty() || foldersChanged || !computed
        }
        if (changed) recompute()
    }

    /**
     * Looks at the jobs folder again every [everyMillis] for as long as it runs (cancel it to stop): a folder you add to the jobs folder is
     * picked up, numbered and listed by itself. [pause] is how it waits (a test passes its own).
     */
    suspend fun watchFolders(everyMillis: Long = FOLDER_WATCH_MILLIS, pause: suspend (Long) -> Unit = { delay(it) }) {
        while (true) {
            pause(everyMillis)
            withContext(Dispatchers.IO) { refreshFolders() }
        }
    }

    /** Whether the client file is in place yet and whether one was just downloaded. */
    fun checkSetup() {
        clientReady = mail?.clientAvailable() == true
        downloadedClients = GmailClientFile.findDownloads(downloadsDir)
    }

    /**
     * Works the jobs out afresh and numbers them: one list, the job applied for most recently first (see [orderByApplied]), so a job's number is
     * its place in that list and moves when an application newer than it comes in or one is deleted. (The numbers and letters older versions
     * kept in the file are no longer used.)
     */
    private fun recompute() = synchronized(lock) {
        computed = true
        val events = data.accounts.flatMap { a -> a.events.map { AccountEvent(a.email, it) } }
        val derived = deriveJobs(folderJobs, events, data.overrides, data.hidden.toSet() + data.deleted)
        val now = clock()
        rows = orderByApplied(derived).mapIndexed { index, row ->
            row.copy(
                label = (index + 1).toString(), order = index + 1,
                isNew = row.folder != null && (data.noticed[row.key] ?: 0L).let { it > 0 && now - it < NEW_FOR_MILLIS },
            )
        }
        hiddenCount = data.hidden.size
        accounts = data.accounts.map { JobAccount(it.email, mail?.isConnected(it.email) == true, it.lastScanMillis, it.events.size) }
    }

    // ---- setup ----

    /** Keeps [source] as the Google client file if it is a good one. */
    fun importClient(source: File) {
        val keeper = clientFile ?: run { message = "Setting up Gmail isn't available here."; return }
        when (val parsed = keeper.import(source)) {
            is ClientParse.Ok -> message = "Your Google client file is in place. Now press Connect."
            is ClientParse.Bad -> message = parsed.why
        }
        checkSetup()
    }

    fun openUrl(url: String) = actions.openUrl(url)

    fun openFolder(folder: File?) {
        if (folder != null) actions.openFolder(folder)
    }

    // ---- mailboxes ----

    /** Adds another mailbox section (the first is [DEFAULT_JOBS_ACCOUNT]). Returns false with a message if [email] doesn't look like an address or is already there. */
    fun addAccount(email: String): Boolean {
        val clean = email.trim()
        if (!Regex("""^[^@\s]+@[^@\s]+\.[^@\s]+$""").matches(clean)) {
            message = "That doesn't look like an email address."
            return false
        }
        if (synchronized(lock) { data.accounts.any { sameMailbox(it.email, clean) } }) {
            message = "$clean is already on the page."
            return false
        }
        update { it.copy(accounts = it.accounts + JobAccountFile(clean)) }
        message = null
        return true
    }

    /** Takes a mailbox section off the page along with what was found in it. */
    fun removeAccount(email: String) {
        mail?.disconnect(email)
        update { it.copy(accounts = it.accounts.filterNot { a -> a.email.equals(email, ignoreCase = true) }) }
    }

    /** Signs in to [email] (the browser opens), then reads its mail. */
    fun connect(email: String) {
        val source = mail ?: run { message = "Connecting to Gmail isn't available here."; return }
        if (busy) return
        if (!source.clientAvailable()) {
            message = "First put your Google client file in place — see the steps above."
            return
        }
        busy = true
        busyAccount = email
        progress = "Waiting for you to sign in in your browser…"
        message = null
        scope.launch {
            try {
                when (val r = source.connect(email)) {
                    is MailResult.Failed -> { message = r.message; recompute() }
                    is MailResult.Ok -> { recompute(); runScan(source, email) }
                }
            } finally {
                busy = false
                busyAccount = null
                progress = null
            }
        }
    }

    /** Stops reading [email]'s mail: the key is forgotten here and Google is told. What was already found stays. */
    fun disconnect(email: String) {
        mail?.disconnect(email)
        message = "$email is disconnected. The jobs already found stay on this page."
        recompute()
    }

    /** Reads [email]'s new mail. */
    fun scan(email: String) {
        val source = mail ?: run { message = "Reading Gmail isn't available here."; return }
        if (busy) return
        busy = true
        busyAccount = email
        message = null
        scope.launch {
            try {
                runScan(source, email)
            } finally {
                busy = false
                busyAccount = null
                progress = null
            }
        }
    }

    /** Reads every connected mailbox. */
    fun scanAll() {
        val source = mail ?: return
        if (busy) return
        val connected = synchronized(lock) { data.accounts.map { it.email } }.filter(source::isConnected)
        if (connected.isEmpty()) { message = "No mailbox is connected yet."; return }
        busy = true
        message = null
        scope.launch {
            try {
                for (email in connected) {
                    busyAccount = email
                    runScan(source, email)
                }
            } finally {
                busy = false
                busyAccount = null
                progress = null
            }
        }
    }

    private fun runScan(source: MailSource, email: String) {
        var found = 0
        var read = 0
        var remaining = 0
        var dropped = 0
        fun said(text: String) = if (dropped > 0) "$text Dropped $dropped you deleted from Gmail." else text
        repeat(MAX_PASSES) { pass ->
            val seen = synchronized(lock) { data.accounts.firstOrNull { it.email.equals(email, ignoreCase = true) }?.seen.orEmpty().toSet() }
            progress = "Reading $email…"
            when (val r = source.fetchNew(email, jobsQuery(lookbackDays), seen, BATCH) { done, total -> progress = "Reading $email: ${read + done} emails (this pass $done of $total)…" }) {
                is MailResult.Failed -> {
                    message = r.message
                    recompute()
                    return
                }
                is MailResult.Ok -> {
                    val fetched = r.value
                    val events = fetched.messages.mapNotNull { toJobEvent(it, email) }
                    found += events.size
                    read += fetched.messages.size
                    remaining = fetched.remaining
                    // An email read earlier that Gmail no longer lists (inside the search window) was deleted from Gmail: its job goes too. An empty or
                    // cut-short list ([FetchedMail.listedIds] null) never deletes anything, and mail older than the window is out of the search anyway.
                    val listed = fetched.listedIds?.takeIf { it.isNotEmpty() }
                    val windowStart = clock() - (lookbackDays - 2) * DAY_MILLIS
                    update { f ->
                        f.copy(accounts = f.accounts.map { a ->
                            if (!a.email.equals(email, ignoreCase = true)) a
                            else {
                                val merged = a.events.filterNot { old -> events.any { it.messageId == old.messageId } } + events
                                val kept = merged.filter { e -> listed == null || e.messageId in listed || e.dateMillis < windowStart }
                                dropped += merged.size - kept.size
                                val goneIds = (merged - kept.toSet()).map { it.messageId }.toSet()
                                a.copy(
                                    lastScanMillis = clock(),
                                    classifier = CLASSIFIER_VERSION,
                                    events = kept.sortedBy { it.dateMillis },
                                    seen = ((a.seen + fetched.messages.map { it.id }).distinct()) - goneIds,
                                )
                            }
                        })
                    }
                    if (fetched.stopped != null) {
                        // Google refused something part-way: what was read is kept (saved just above) and the person is told why it stopped.
                        message = said("Read $read emails and found $found about applications — kept. Stopped early: ${fetched.stopped}")
                        return
                    }
                    if (remaining == 0 || fetched.messages.isEmpty()) {
                        message = said(scanSummary(read, found, 0))
                        return
                    }
                }
            }
        }
        message = said(scanSummary(read, found, remaining))
    }

    private fun scanSummary(read: Int, found: Int, remaining: Int): String = when {
        read == 0 -> "Nothing new in your mail."
        else -> "Read $read emails and found $found about applications." + if (remaining > 0) " $remaining older ones are left — press Scan again for them." else ""
    }

    // ---- the user's own choices ----

    /** Sets [key]'s status by hand ([status] null goes back to what the emails say). */
    fun setStatus(key: String, status: JobStatus?) = setStatusOfAll(listOf(key), status)

    /** Sets the status of every job in [keys] by hand in one go ([status] null sends them all back to what the emails say). One save, however many. */
    fun setStatusOfAll(keys: Collection<String>, status: JobStatus?) {
        if (keys.isEmpty()) return
        update { f -> f.copy(overrides = if (status == null) f.overrides - keys.toSet() else f.overrides + keys.associateWith { status }) }
    }

    fun hide(key: String) = hideAll(listOf(key))

    /** Hides every job in [keys] in one go (the "Show N hidden" button brings them back). */
    fun hideAll(keys: Collection<String>) {
        if (keys.isEmpty()) return
        update { f -> f.copy(hidden = (f.hidden + keys).distinct()) }
    }

    fun unhideAll() = update { f -> f.copy(hidden = emptyList()) }

    /**
     * Deletes every job in [keys] from Mimi's list for good: they stay gone (a rescan or "Show hidden" does not bring them back), their own status
     * choice is forgotten, and a folder job's number stays reserved and is never handed to another job. Only Mimi's own file changes — a job's
     * folder on disk is never touched, so deleting a folder job here does not delete its folder.
     */
    fun deleteAll(keys: Collection<String>) {
        if (keys.isEmpty()) return
        val gone = keys.toSet()
        update { f -> f.copy(deleted = (f.deleted + gone).distinct(), hidden = f.hidden - gone, overrides = f.overrides - gone) }
    }

    // ---- keeping it ----

    private fun update(change: (JobsFile) -> JobsFile) {
        synchronized(lock) {
            data = change(data)
            save()
        }
        recompute()
    }

    private fun load(): JobsFile {
        // No file, or one that can't be read, is a fresh start: with the mailbox the user asked for first. (A file the user emptied by removing
        // every mailbox is a real file and stays empty.)
        val saved = file?.let { f -> runCatching { json.decodeFromString<JobsFile>(f.readText()) }.getOrNull() }
            ?: return JobsFile(accounts = listOf(JobAccountFile(DEFAULT_JOBS_ACCOUNT)))
        // Rules that read emails were changed since a mailbox was scanned: its events are dropped so the next scan reads again with the new rules.
        return saved.copy(accounts = saved.accounts.map { if (it.classifier == CLASSIFIER_VERSION) it else JobAccountFile(it.email) })
    }

    private fun save() {
        val f = file ?: return
        runCatching {
            f.parentFile?.mkdirs()
            val temp = File(f.parentFile, f.name + ".tmp")
            temp.writeText(json.encodeToString(data))
            Files.move(temp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    companion object {
        /** The folder the user keeps their applications in. */
        fun defaultJobsFolder(): File = File(System.getProperty("user.home"), "Documents/1. Work/1. Jobs Applying to now")

        /**
         * The real thing: `jobs.json` and the Gmail files in `%APPDATA%\a2-desktop`, the folders in Documents, Gmail through Google's sign-in,
         * and the browser/Explorer opened for real.
         */
        fun default(): JobsStore {
            val appDir = File(System.getenv("APPDATA") ?: System.getProperty("user.home"), "a2-desktop")
            val clientFile = GmailClientFile(appDir)
            val actions = object : JobsActions {
                override fun openUrl(url: String) { runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI(url)) } }
                override fun openFolder(folder: File) { runCatching { java.awt.Desktop.getDesktop().open(folder) } }
            }
            val mail = GmailSource(clientFile, FileTokenStore(File(appDir, "gmail-tokens.json")), openBrowser = { url ->
                java.awt.Desktop.getDesktop().browse(java.net.URI(url))
            })
            val jobsFolder = defaultJobsFolder()
            return JobsStore(
                file = File(appDir, "jobs.json"),
                mail = mail,
                folders = { scanJobFolders(jobsFolder) },
                clientFile = clientFile,
                downloadsDir = File(System.getProperty("user.home"), "Downloads"),
                actions = actions,
                jobsFolder = jobsFolder,
            )
        }
    }
}
