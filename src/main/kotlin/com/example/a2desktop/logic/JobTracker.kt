package com.example.a2desktop.logic

import java.io.File
import java.util.Locale
import kotlinx.serialization.Serializable

// The Jobs page's brain, all plain values and functions (so every rule is unit-tested without Gmail or a window):
//  - what an email about an application MEANS (confirmation, interview invitation, offer, rejection) and the sentence that shows why;
//  - which jobs exist, read from the folders in `1. Work\1. Jobs Applying to now` (a folder per job; `0. Rejected` and `0.0. Interview
//    Stage` are status folders, `Round N` folders are just shelves);
//  - which emails belong to which job, and the status that follows from them.
// Nothing here reads a real email or touches the network. Emails are kept only as the small [JobEvent] each one boils down to.

/** Bumped whenever the rules in [classifyEmail] change, so an earlier scan is redone with the new rules instead of being trusted. */
const val CLASSIFIER_VERSION = 2

/**
 * Where a job stands. [NOT_SEEN] is a job whose folder exists but no email about it has been found. [ACCEPTED] means you took the job: it is only
 * ever chosen by you (no email's wording says "I accepted"), so the emails alone never give it.
 */
enum class JobStatus(val label: String, val folderName: String = label) {
    NOT_SEEN("No email yet"),
    APPLIED("Applied"),
    INTERVIEW("Interview"),
    OFFER("Offer"),
    ACCEPTED("Accepted"),
    REJECTED("Unsuccessful", folderName = "Rejected"), // shown as "Unsuccessful"; the person's own folder is still called 0. Rejected
}

/** What one email says about an application. The order is the order of weight when two come at the same moment (an offer beats the rest). */
enum class EmailKind(val label: String) {
    SENT_APPLICATION("You applied"),
    CONFIRMATION("Application received"),
    REJECTION("Rejection"),
    INTERVIEW("Interview"),
    OFFER("Offer"),
}

/** One email as it comes from Gmail (the body already cut down to its first part, as plain text). */
data class EmailMessage(
    val id: String,
    val threadId: String,
    val from: String,
    val subject: String,
    val dateMillis: Long,
    val text: String,
)

/** What an email was judged to say, and the sentence that shows it. */
data class Classification(val kind: EmailKind, val evidence: String)

/**
 * What is kept of one application email: what it means, when, who from, the subject, the sentence that shows it — and a bag of its
 * distinctive words, so it can still be matched to a job added later, without keeping the email itself.
 */
@Serializable
data class JobEvent(
    val messageId: String,
    val threadId: String,
    val kind: EmailKind,
    val dateMillis: Long,
    val from: String,
    val subject: String,
    val evidence: String,
    val tokens: List<String> = emptyList(),
    val classifier: Int = CLASSIFIER_VERSION,
)

// ---- Reading an email ----

private val IC = RegexOption.IGNORE_CASE

private class Rule(val kind: EmailKind, val regex: Regex, val weak: Boolean = false)

private fun rule(kind: EmailKind, pattern: String, weak: Boolean = false) = Rule(kind, Regex(pattern, IC), weak)

private val rules: List<Rule> = listOf(
    // A rejection says so in a handful of well-worn ways.
    rule(EmailKind.REJECTION, """regret to (?:inform|tell|advise)"""),
    rule(EmailKind.REJECTION, """not been successful"""),
    rule(EmailKind.REJECTION, """\bunsuccessful\b"""),
    rule(EmailKind.REJECTION, """not (?:be )?(?:progress|progressing|moving forward|proceeding|taking your application (?:any )?further)"""),
    rule(EmailKind.REJECTION, """decided (?:to (?:proceed|progress|move forward|continue|go ahead) with (?:other|another|a different)|not to (?:proceed|progress|move forward|take (?:your|this) application))"""),
    rule(EmailKind.REJECTION, """(?:proceed|progress|move forward|go forward|continue) with (?:other|another) (?:candidates?|applicants?)"""),
    rule(EmailKind.REJECTION, """offered the (?:role|position|post|job) to (?:another|someone|a different)"""),
    rule(EmailKind.REJECTION, """(?:have |has )?not (?:been )?shortlisted"""),
    rule(EmailKind.REJECTION, """(?:were|was|are) not selected|not selected for"""),
    rule(EmailKind.REJECTION, """unable to (?:offer you|progress|take your application)"""),
    rule(EmailKind.REJECTION, """no longer (?:being )?considered"""),
    rule(EmailKind.REJECTION, """(?:position|role|post|vacancy) (?:has|had) (?:now )?(?:been )?(?:filled|closed)"""),
    rule(EmailKind.REJECTION, """application (?:has been |was )?(?:declined|rejected)"""),
    rule(EmailKind.REJECTION, """unfortunately[^.]{0,200}(?:application|candidate|role|position|post|interview|shortlist|vacancy)"""),
    // An interview (or the next stage) is something being ARRANGED, not just the word.
    rule(EmailKind.INTERVIEW, """(?:invite|invited|inviting|invitation)[^.]{0,60}\binterview"""),
    rule(EmailKind.INTERVIEW, """\binterview invitation\b|\binvitation to (?:an? )?interview"""),
    rule(EmailKind.INTERVIEW, """like to (?:invite|arrange|schedule|offer you)[^.]{0,80}(?:interview|assessment)"""),
    rule(EmailKind.INTERVIEW, """(?:arrange|schedule|book|organise|confirm)[^.]{0,40}(?:an? |your )?(?:interview|assessment)"""),
    rule(EmailKind.INTERVIEW, """(?:telephone|phone|video|online|virtual|panel|face[- ]to[- ]face|first[- ]stage|second[- ]stage|final[- ]stage) interview"""),
    rule(EmailKind.INTERVIEW, """\binterview (?:date|slot|time|details)\b"""),
    rule(EmailKind.INTERVIEW, """(?:you (?:have been|are|'ve been|'re)) (?:shortlisted|selected for (?:an? )?(?:interview|assessment|the next stage))"""),
    // Shortlisted for a JOB: not "the university has been shortlisted for three awards" in an alumni newsletter.
    rule(EmailKind.INTERVIEW, """\bshortlisted for (?:the|an?) (?:interview|next|role|position|post|job|vacancy)|\bapplication\b[^.]{0,60}\bshortlisted\b"""),
    rule(EmailKind.INTERVIEW, """next stage of (?:the|our|your) (?:recruitment|selection|application|process)"""),
    rule(EmailKind.INTERVIEW, """assessment cent(?:re|er)"""),
    rule(EmailKind.INTERVIEW, """invited to (?:complete |take |attend )?(?:an? )?(?:online )?(?:assessment|test)"""),
    // An offer is a phrase that only an offer uses; "job offers" in a rejection's "we'll keep your details" is only a weak hint.
    rule(EmailKind.OFFER, """(?:pleased|delighted|happy) to (?:offer|confirm (?:your )?offer)"""),
    rule(EmailKind.OFFER, """(?:would|'d) like to offer you|wish to offer you|we(?:'d| would) like to make you an offer"""),
    rule(EmailKind.OFFER, """offer you (?:the|a|an) (?:position|role|post|job)"""),
    rule(EmailKind.OFFER, """offer of (?:employment|appointment|a (?:position|role|post|job))"""),
    rule(EmailKind.OFFER, """(?:formal|conditional|verbal|unconditional|written) (?:job )?offer"""),
    // "successful" but not "successfully": Reed's "Congratulations Alex, you've successfully applied" is a receipt, not an offer.
    rule(EmailKind.OFFER, """congratulations[^.]{0,120}(?:offer|successful(?!ly)|appointed|selected)"""),
    rule(EmailKind.OFFER, """(?:you have|you've) been (?:successful|appointed|selected for the (?:role|position|post|job))"""),
    rule(EmailKind.OFFER, """\bjob offer\b(?!s)""", weak = true),
    // A confirmation says it has arrived.
    rule(EmailKind.CONFIRMATION, """thank(?:s| you) for (?:your )?(?:applying|application|interest in)"""),
    rule(EmailKind.CONFIRMATION, """(?:we(?:'ve| have)|has been|have) received your application"""),
    rule(EmailKind.CONFIRMATION, """application (?:has been |was )?(?:received|submitted|successfully submitted)"""),
    rule(EmailKind.CONFIRMATION, """your application (?:for|to)[^.]{0,120}(?:has been )?(?:received|submitted)"""),
    rule(EmailKind.CONFIRMATION, """successfully (?:applied|submitted)"""),
    rule(EmailKind.CONFIRMATION, """application confirmation"""),
    rule(EmailKind.CONFIRMATION, """(?:you(?:'ve| have) applied (?:for|to))"""),
)

private val conditionalWord = Regex("""\b(?:if|should|unless|in the event|in case|whether)\b""", IC)

/** Whether the sentence a match sits in is conditional ("IF you are shortlisted, we will…") — that is not news, it's the small print. */
private fun isConditional(text: String, matchStart: Int): Boolean {
    val before = text.substring((matchStart - 140).coerceAtLeast(0), matchStart)
    val sentence = before.substring(before.lastIndexOfAny(charArrayOf('.', '!', '?')) + 1)
    return conditionalWord.containsMatchIn(sentence)
}

private val whitespace = Regex("""\s+""")

private fun tidy(text: String) = whitespace.replace(text.replace('’', '\'').replace(' ', ' '), " ").trim()

/** The sentence around [start]..[end], cut to a readable length. */
private fun sentenceAround(text: String, start: Int, end: Int, max: Int = 170): String {
    val from = text.lastIndexOfAny(charArrayOf('.', '!', '?'), (start - 1).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }
    val stop = text.indexOfAny(charArrayOf('.', '!', '?'), end).let { if (it < 0) text.length else it + 1 }
    val sentence = text.substring(from, stop).trim()
    if (sentence.length <= max) return sentence
    // Too long: keep a window around the match itself.
    val relative = (start - from).coerceAtLeast(0)
    val windowStart = (relative - max / 3).coerceAtLeast(0)
    val window = sentence.substring(windowStart, (windowStart + max).coerceAtMost(sentence.length)).trim()
    return (if (windowStart > 0) "…" else "") + window + (if (windowStart + max < sentence.length) "…" else "")
}

/**
 * What an email about a job application says, or null if it isn't one. [fromMe] (the user sent it) can only mean "you applied": the user's
 * own replies never count as an interview or an offer. Weight, strongest first: a plain offer, a rejection, a weak "job offer", an
 * interview, an acknowledgement. A phrase inside an "if…" sentence is the small print and is ignored.
 */
fun classifyEmail(subject: String, body: String, fromMe: Boolean = false): Classification? {
    val text = tidy("$subject. ${body.take(3000)}")
    if (fromMe) {
        val m = Regex("""\b(?:application|applying|apply|applied)\b""", IC).find(subject) ?: return null
        return Classification(EmailKind.SENT_APPLICATION, sentenceAround(tidy(subject), m.range.first, m.range.last + 1))
    }
    val found = LinkedHashMap<EmailKind, Pair<Boolean, Classification>>() // kind -> (weak, evidence), first firm match per kind
    for (r in rules) {
        for (m in r.regex.findAll(text)) {
            if (r.kind != EmailKind.CONFIRMATION && isConditional(text, m.range.first)) continue
            val have = found[r.kind]
            if (have == null || (have.first && !r.weak)) found[r.kind] = r.weak to Classification(r.kind, sentenceAround(text, m.range.first, m.range.last + 1))
            break
        }
    }
    val firm = found.filter { !it.value.first }
    return when {
        EmailKind.OFFER in firm -> firm.getValue(EmailKind.OFFER).second
        EmailKind.REJECTION in found -> found.getValue(EmailKind.REJECTION).second
        EmailKind.OFFER in found -> found.getValue(EmailKind.OFFER).second
        EmailKind.INTERVIEW in found -> found.getValue(EmailKind.INTERVIEW).second
        EmailKind.CONFIRMATION in found -> found.getValue(EmailKind.CONFIRMATION).second
        else -> null
    }
}

// ---- Words, senders ----

/** Words too common to tell one job from another (and the generic words of applications themselves). */
private val stopwords = setOf(
    "the", "and", "for", "you", "your", "with", "this", "that", "from", "have", "has", "been", "are", "was", "were", "will", "not", "our",
    "nhs", "trust", "foundation", "hospital", "hospitals", "university", "london", "working", "remote", "job", "jobs", "role", "position",
    "post", "vacancy", "application", "applications", "applying", "applied", "apply", "thank", "thanks", "update", "regarding", "reference",
    "recruitment", "team", "ltd", "limited", "plc", "group", "uk", "dear", "hello", "interview", "candidate", "candidates", "email", "noreply",
    "message", "please", "about", "which", "their", "them", "they", "can", "any", "all", "new", "one", "contact", "details", "information",
    "permanent", "temporary", "full", "time", "part", "fixed", "term", "band", "grade", "www", "com", "https", "http",
    "received", "receipt", "submitted", "successfully", "successful", "unsuccessful", "confirmation", "confirm", "confirmed", "outcome",
    "decision", "status", "shortlisted", "invitation", "invite", "invited", "offer", "congratulations", "acknowledgement", "acknowledgment",
    "notification", "feedback", "careers", "career", "recruiting", "hiring", "talent", "welcome", "good", "news", "reply",
)

/** The distinctive lower-case words of [text] (three letters or more, not a stopword), in order of first appearance, at most [limit]. */
fun tokensOf(text: String, limit: Int = 120): List<String> =
    Regex("""[a-z0-9]{3,}""").findAll(text.lowercase(Locale.ROOT)).map { it.value }
        .filter { it !in stopwords && !it.all(Char::isDigit) }.distinct().take(limit).toList()

/** "Acme Recruitment" from `"Acme Recruitment" <jobs@acme.co.uk>`; the address itself when there is no name. */
fun senderName(from: String): String {
    val name = from.substringBefore('<').trim().trim('"').trim()
    return name.ifEmpty { from.substringAfter('<', from).substringBefore('>').trim() }
}

/** "acme.co.uk" from `"Acme" <jobs@acme.co.uk>` (lower case), or "" if there is no address. */
fun senderDomain(from: String): String =
    (if ('<' in from) from.substringAfter('<').substringBefore('>') else from).substringAfter('@', "").trim().lowercase(Locale.ROOT)

/**
 * The one form every spelling of the same Gmail mailbox has in common: lower case, `googlemail.com` is `gmail.com`, and Gmail ignores dots
 * and anything after a `+` in the name — so `Robinson.8+jobs@googlemail.com` is `robinson8@gmail.com`. Other providers are only lower-cased.
 */
fun mailboxKey(address: String): String {
    val a = address.trim().lowercase(Locale.ROOT)
    val at = a.lastIndexOf('@')
    if (at < 0) return a
    var local = a.substring(0, at)
    var domain = a.substring(at + 1)
    if (domain == "googlemail.com") domain = "gmail.com"
    if (domain == "gmail.com") local = local.substringBefore('+').replace(".", "")
    return "$local@$domain"
}

/** Whether two addresses are the same mailbox (see [mailboxKey]). */
fun sameMailbox(a: String, b: String): Boolean = mailboxKey(a) == mailboxKey(b)

/** Whether [from] is the mailbox [account] itself (the user's own sent mail). */
fun isFromAccount(from: String, account: String): Boolean =
    sameMailbox((if ('<' in from) from.substringAfter('<').substringBefore('>') else from).trim(), account)

/** Senders that write to many people at once, not to you about your application: newsletters, notifications, digests, alumni mailings, customer support. */
private val bulkSender = Regex("""(?:^|[-_.])(?:newsletters?|notifications?|digests?|alerts?|marketing|mailer|promotions?|alumni|support|helpdesk|customer-?care)(?=$|[-_.\d])""", IC)
private val bulkSubject = Regex("""\b(?:newsletter|alumni news|webinar|job alerts?|jobs? for you|recommended for you)\b""", IC)

/**
 * Whether [from] / [subject] look like mass mail (a LinkedIn tips digest, a university alumni newsletter, a phone-maker's support desk) rather than an
 * employer or recruiter writing about an application. Such mail can use any of the words an application email uses, so it is ignored whatever it says.
 * Only the name part of the address and the first part of its domain are looked at, so `noreply@dwpjobs.tal.net` and `no-reply@jobs.reed.co.uk` are not caught.
 */
fun looksLikeBulkMail(from: String, subject: String): Boolean {
    val address = (if ('<' in from) from.substringAfter('<').substringBefore('>') else from).trim().lowercase(Locale.ROOT)
    val local = address.substringBefore('@')
    val firstLabel = address.substringAfter('@', "").substringBefore('.')
    return bulkSender.containsMatchIn(local) || firstLabel in setOf("support", "help", "news", "newsletter", "alumni", "marketing") || bulkSubject.containsMatchIn(subject)
}

/** The email as the small event that is kept, or null if it isn't about an application. */
fun toJobEvent(message: EmailMessage, account: String): JobEvent? {
    if (!isFromAccount(message.from, account) && looksLikeBulkMail(message.from, message.subject)) return null
    val classification = classifyEmail(message.subject, message.text, fromMe = isFromAccount(message.from, account)) ?: return null
    return JobEvent(
        messageId = message.id,
        threadId = message.threadId.ifBlank { message.id },
        kind = classification.kind,
        dateMillis = message.dateMillis,
        from = message.from.take(160),
        subject = message.subject.take(200),
        evidence = classification.evidence,
        tokens = tokensOf("${message.subject} ${senderName(message.from)} ${senderDomain(message.from).substringBefore('.')} ${message.text.take(1500)}"),
    )
}

// ---- The jobs in the folders ----

/**
 * A job found as a folder: [folderStatus] is what the folder it sits in says (Rejected / Interview), or null for the ordinary shelf.
 * [createdMillis] is when the folder was made (0 if unknown) — the best guess at when the job was applied for when no email says.
 */
data class FolderJob(val key: String, val number: Int?, val title: String, val folderStatus: JobStatus?, val path: String, val createdMillis: Long = 0)

private val statusFolder = Regex("""^0(?:\.0)?\.?\s*(rejected|interview(?: stage)?)\b.*""", IC)
private val roundFolder = Regex("""^round\s*\d+$""", IC)
private val leadingNumber = Regex("""^(\d+)\.\s+""")

/** The key a job is known by: its title alone, so moving its folder to Rejected or renumbering it keeps what was said about it. */
fun jobKeyOfTitle(title: String): String = "folder:" + whitespace.replace(title.lowercase(Locale.ROOT).trim(), " ")

/**
 * The jobs in [root] (normally `1. Work\1. Jobs Applying to now`): every folder that isn't a status folder or a "Round N" shelf is a job —
 * a folder directly there, or inside `0. Rejected` / `0.0. Interview Stage` (and their Round folders) — and the status folder it sits in
 * is its [FolderJob.folderStatus]. A job folder is never searched inside. A missing or unreadable folder gives no jobs.
 */
fun scanJobFolders(root: File): List<FolderJob> {
    if (!root.isDirectory) return emptyList()
    val out = ArrayList<FolderJob>()
    fun walk(dir: File, hint: JobStatus?, depth: Int) {
        val children = runCatching { dir.listFiles { f -> f.isDirectory } }.getOrNull()?.sortedWith(compareBy({ numberOf(it.name) ?: Int.MAX_VALUE }, { it.name.lowercase(Locale.ROOT) })) ?: return
        for (child in children) {
            val status = statusFolder.matchEntire(child.name)?.let { if (it.groupValues[1].startsWith("rej", ignoreCase = true)) JobStatus.REJECTED else JobStatus.INTERVIEW }
            when {
                status != null && depth < 4 -> walk(child, status, depth + 1)
                roundFolder.matches(child.name) && depth < 4 -> walk(child, hint, depth + 1)
                else -> {
                    val title = cleanFolderTitle(child.name)
                    if (title.isNotEmpty()) out += FolderJob(jobKeyOfTitle(title), numberOf(child.name), title, hint, child.absolutePath, createdOf(child))
                }
            }
        }
    }
    walk(root, null, 0)
    return out.distinctBy { it.key }
}

private fun numberOf(name: String): Int? = leadingNumber.find(name)?.groupValues?.get(1)?.toIntOrNull()

/** When [folder] was created (falling back to when it was last changed; 0 if neither can be read). Only the folder itself is looked at, never inside it. */
private fun createdOf(folder: File): Long = runCatching {
    java.nio.file.Files.readAttributes(folder.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java).creationTime().toMillis()
}.getOrNull()?.takeIf { it > 0 } ?: folder.lastModified()

/** "2. Northgate College… - Maternity" -> "Northgate College… - Maternity"; "1. X St Anne's…" -> "St Anne's…" (the X the user adds to a rejected one). */
internal fun cleanFolderTitle(name: String): String = name.replace(leadingNumber, "").replace(Regex("""^X\s+"""), "").trim()

// ---- Which email belongs to which job ----

/** How well an email's words cover one job's name: [distinct] words only that job has, and [ratio] of the name's weight, 0..1. */
internal data class WordMatch(val ratio: Double, val distinct: Int, val matched: Int = 0) {
    /**
     * Enough to say "this is that job": most of its name or two words nobody else's name has (an employer's name, say) — together with at
     * least one such word — or the whole of its name (two words or more) even when no word is unique to it.
     */
    val accepted: Boolean get() = (distinct >= 1 && (ratio >= 0.5 || distinct >= 2)) || (matched >= 2 && ratio >= 0.999)

    /** For choosing between two jobs that both fit: more distinctive words first, then more of the name. */
    val strength: Double get() = distinct + ratio
}

/**
 * Words a distinctive job name is made of, with how much each one counts: a word only one job has counts most. Folders that are the same job
 * ([familyOf] gives them one name) count as one job, so a word two such folders share ("Army") still counts as belonging to just one job.
 */
internal class JobWords(jobs: List<FolderJob>, familyOf: (FolderJob) -> String = { it.key }) {
    private val words: Map<String, Set<String>> = jobs.associate { it.key to tokensOf(it.title).toSet() }
    private val frequency: Map<String, Int> =
        jobs.groupBy(familyOf).values.flatMap { family -> family.flatMap { words[it.key].orEmpty() }.toSet() }.groupingBy { it }.eachCount()

    private fun weight(word: String) = 1.0 / (frequency[word] ?: 1)

    /** How much of [jobKey]'s name an email with these [tokens] covers. */
    fun match(jobKey: String, tokens: Set<String>): WordMatch {
        val mine = words[jobKey].orEmpty()
        if (mine.isEmpty()) return WordMatch(0.0, 0, 0)
        val matched = mine.filter { it in tokens }
        return WordMatch(matched.sumOf { weight(it) } / mine.sumOf { weight(it) }, matched.count { (frequency[it] ?: 1) == 1 }, matched.size)
    }
}

/**
 * Folders that are really the same job, together: a folder with a short name (one or two distinctive words) that is all inside another's name
 * ("Army" and "IT & Communications Apprentice UK _ Army"), when those words belong to no other folder — so a generic word ("technician" inside
 * several longer names) never glues different jobs together, and a complete title of three words or more is always a job of its own. Each family lists its fullest name first (that folder stands for the job); jobs on their own are families of one.
 */
fun folderFamilies(jobs: List<FolderJob>): List<List<FolderJob>> {
    val tokens = jobs.map { tokensOf(it.title).toSet() }
    val frequency = tokens.flatten().groupingBy { it }.eachCount()
    val parent = IntArray(jobs.size) { it }
    fun find(i: Int): Int { var x = i; while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x] }; return x }
    for (i in jobs.indices) for (j in i + 1 until jobs.size) {
        val small = if (tokens[i].size <= tokens[j].size) tokens[i] else tokens[j]
        val big = if (tokens[i].size <= tokens[j].size) tokens[j] else tokens[i]
        // A SHORT name (one or two distinctive words, like "Army") inside a fuller one; a complete title of three words or more is a job of its own.
        if (small.isNotEmpty() && small.size <= 2 && big.containsAll(small) && small.all { (frequency[it] ?: 0) <= 2 }) parent[find(i)] = find(j)
    }
    return jobs.indices.groupBy { find(it) }.values
        .map { group -> group.map { jobs[it] }.sortedWith(compareByDescending<FolderJob> { tokensOf(it.title).size }.thenByDescending { it.title.length }.thenBy { it.title.lowercase(Locale.ROOT) }) }
        .sortedBy { family -> jobs.indexOf(family.first()) }
}

/** A job as the Jobs page shows it. [status] is what counts: the user's own choice, else what the emails say, else what the folder says. */
data class JobRow(
    val key: String,
    val title: String,
    val folder: FolderJob?,
    /** The mailbox whose newest email decided the status, or null when there is no email about it. */
    val account: String?,
    /** What the emails say (null if none). */
    val auto: JobStatus?,
    val status: JobStatus,
    /** True when the user chose [status] themselves. */
    val overridden: Boolean,
    val lastEvent: JobEvent?,
    /** A note when the folder and the emails disagree (e.g. an email says rejected but the folder isn't under 0. Rejected). */
    val note: String?,
    val events: List<JobEvent>,
    /** When the job was applied for: its earliest application or acknowledgement email, else its earliest email of any kind, else when its folder was made (0 = unknown). See [appliedAt]. */
    val appliedMillis: Long = 0,
    /** The job's number ("1", "2"…): its place in the list, newest application first. Set by the store; it changes as applications come and go. */
    val label: String = "",
    /** The same place as a number, for ordering. */
    val order: Int = Int.MAX_VALUE,
    /** True for a folder job noticed recently (not one that was there when the page first looked). */
    val isNew: Boolean = false,
    /** Other folders that are the same job as [folder] (see [folderFamilies]); the row stands for all of them. */
    val alsoFolders: List<FolderJob> = emptyList(),
)

// ---- Numbers and letters ----
// Each folder job gets a number the first time it is seen and keeps it for good: the page can be sorted, filtered or have jobs
// rejected without any number moving, a job that leaves the folder keeps its number reserved (it is not handed to another), and a new
// folder gets the next number. Jobs found only in email get letters the same way, so the two lists can never be mixed up.

/**
 * The order folders are numbered in the first time: the jobs on the shelf (not yet in a status folder) first, then those in Interview
 * Stage, then Rejected — each by the number on its folder, then by name. So the first job on the shelf is number 1.
 */
fun initialNumberOrder(jobs: List<FolderJob>): List<FolderJob> =
    jobs.sortedWith(
        compareBy<FolderJob>(
            { when (it.folderStatus) { null -> 0; JobStatus.INTERVIEW -> 1; else -> 2 } },
            { it.number ?: Int.MAX_VALUE },
            { it.title.lowercase(Locale.ROOT) },
        ),
    )

/** [existing] numbers kept as they are, and every job in [ordered] that has none given the next numbers (after the highest ever used) in that order. */
fun assignNumbers(existing: Map<String, Int>, ordered: List<FolderJob>): Map<String, Int> {
    var next = (existing.values.maxOrNull() ?: 0) + 1
    val out = LinkedHashMap(existing)
    for (job in ordered) if (job.key !in out) out[job.key] = next++
    return out
}

/** [existing] letters (as positions, 0 = A) kept, and every key in [keysInOrder] that has none given the next ones in that order. */
fun assignLetters(existing: Map<String, Int>, keysInOrder: List<String>): Map<String, Int> {
    var next = (existing.values.maxOrNull() ?: -1) + 1
    val out = LinkedHashMap(existing)
    for (key in keysInOrder) if (key !in out) out[key] = next++
    return out
}

/** A, B, C … Z, then AA, AB … for the position [index] (0-based). */
fun letterLabel(index: Int): String {
    var n = index
    val sb = StringBuilder()
    do {
        sb.insert(0, 'A' + n % 26)
        n = n / 26 - 1
    } while (n >= 0)
    return sb.toString()
}

/** "12 jobs · 1 offer · 2 interviews · 5 unsuccessful" — only the parts that aren't zero; "No jobs yet" when there are none. */
fun jobSummary(rows: List<JobRow>): String {
    if (rows.isEmpty()) return "No jobs yet"
    val count = { s: JobStatus -> rows.count { it.status == s } }
    val parts = buildList {
        add(if (rows.size == 1) "1 job" else "${rows.size} jobs")
        count(JobStatus.ACCEPTED).takeIf { it > 0 }?.let { add("$it accepted") }
        count(JobStatus.OFFER).takeIf { it > 0 }?.let { add(if (it == 1) "1 offer" else "$it offers") }
        count(JobStatus.INTERVIEW).takeIf { it > 0 }?.let { add(if (it == 1) "1 interview" else "$it interviews") }
        count(JobStatus.APPLIED).takeIf { it > 0 }?.let { add("$it applied") }
        count(JobStatus.REJECTED).takeIf { it > 0 }?.let { add("$it unsuccessful") }
    }
    return parts.joinToString(" · ")
}

/** One kept email event and the mailbox it came from. */
data class AccountEvent(val account: String, val event: JobEvent)

/** The status a job's emails add up to: the newest rejection, interview or offer; otherwise "applied" if there is an acknowledgement; otherwise nothing. */
fun statusFromEvents(events: List<JobEvent>): JobStatus? {
    val newest = events.filter { it.kind == EmailKind.REJECTION || it.kind == EmailKind.INTERVIEW || it.kind == EmailKind.OFFER }
        .maxWithOrNull(compareBy<JobEvent>({ it.dateMillis }, { it.kind.ordinal })) ?: return if (events.isNotEmpty()) JobStatus.APPLIED else null
    return when (newest.kind) {
        EmailKind.REJECTION -> JobStatus.REJECTED
        EmailKind.INTERVIEW -> JobStatus.INTERVIEW
        else -> JobStatus.OFFER
    }
}

private val subjectNoise = Regex("""^(?:(?:re|fwd?|fw)\s*:\s*)+""", IC)

private fun cleanSubject(subject: String) = subjectNoise.replace(subject.trim(), "").trim()

private class MailThread(val account: String, val threadId: String, val events: List<JobEvent>) {
    val tokens: Set<String> = events.flatMap { it.tokens }.toSet()
    val domain: String = events.map { senderDomain(it.from) }.firstOrNull { it.isNotEmpty() }.orEmpty()
    val subjectWords: Set<String> = events.flatMap { tokensOf(cleanSubject(it.subject)) }.toSet()

    /** The job titles this thread's subjects name, as order-free word sets, for joining threads about the same job. */
    val titleKeys: Set<String> = events.mapNotNull { jobTitleOf(it.subject) }.map(::jobTitleKey).filter { it.isNotEmpty() }.toSet()

    /** For a thread whose subjects name no job but do name an employer ("…update on your application from hireful"): that employer, as a bare key. */
    val employerKey: String? = if (titleKeys.isNotEmpty()) null else events.firstNotNullOfOrNull { employerNamedIn(it.subject) }?.let(::employerKeyOf)?.takeIf { it.length >= 3 }

    /** "trac.jobs" for "recruit.trac.jobs", "reed.co.uk" for "jobs.reed.co.uk": the sender's own domain, whatever its sub-domain. */
    val registrable: String = domain.split('.').let { labels ->
        val keep = if (labels.size >= 3 && labels.last().length == 2 && labels[labels.size - 2] in setOf("co", "org", "ac", "gov", "com", "net", "nhs", "sch")) 3 else 2
        labels.takeLast(keep).joinToString(".")
    }
}

/** Whether two unmatched threads are the same job: the same title from the same sender, or a title of three or more distinctive words from anyone. */
private fun sameJobThreads(a: MailThread, b: MailThread): Boolean {
    if (a.account != b.account) return false
    // The old rule: one employer's acknowledgement and verdict came in separate threads from the same sender and share a subject word.
    if (a.domain.isNotEmpty() && a.domain == b.domain && a.subjectWords.intersect(b.subjectWords).isNotEmpty()) return true
    // An email that names only an employer joins another that names the same employer, or one sent from that employer's own domain
    // ("update on your application from hireful" and a "You've successfully applied…" from hireful.co.uk).
    fun ownDomain(t: MailThread) = t.registrable.substringBefore('.').let(::employerKeyOf)
    if (a.employerKey != null && (a.employerKey == b.employerKey || a.employerKey == ownDomain(b))) return true
    if (b.employerKey != null && b.employerKey == ownDomain(a)) return true
    // The new one: they name the same job. Two generic titles from different senders ("IT Technician") are not enough; a specific one is.
    val shared = a.titleKeys.intersect(b.titleKeys)
    return shared.isNotEmpty() && (a.registrable.isNotEmpty() && a.registrable == b.registrable || shared.any { it.split(' ').size >= 3 })
}

/**
 * The jobs list: every job (a folder, or several folders that are the same job — see [folderFamilies]) with what its emails say, and a row for
 * each application no folder matches, named by its job title (see [jobNameOf]) and joined with the other emails about the same job. A job the user
 * hid or deleted is left out; one they gave a status of their own shows that.
 */
fun deriveJobs(
    folderJobs: List<FolderJob>,
    events: List<AccountEvent>,
    overrides: Map<String, JobStatus> = emptyMap(),
    hidden: Set<String> = emptySet(),
): List<JobRow> {
    val families = folderFamilies(folderJobs)
    val familyOf = families.flatMap { family -> family.map { it.key to family.first().key } }.toMap()
    val words = JobWords(folderJobs) { familyOf.getValue(it.key) }
    val threads = events.groupBy { it.account to it.event.threadId }.map { (id, list) -> MailThread(id.first, id.second, list.map { it.event }) }

    // Each thread goes to the job (folder family) it covers best — if that is clearly better than the next best.
    val byFolder = HashMap<String, MutableList<MailThread>>()
    val unmatched = ArrayList<MailThread>()
    for (thread in threads) {
        val scored = families.mapNotNull { family ->
            family.map { words.match(it.key, thread.tokens) }.filter { it.accepted }.maxByOrNull { it.strength }?.let { family.first().key to it }
        }.sortedByDescending { it.second.strength }
        val best = scored.firstOrNull()
        val clear = best != null && (scored.size == 1 || best.second.strength > scored[1].second.strength)
        if (best != null && clear) byFolder.getOrPut(best.first) { ArrayList() }.add(thread) else unmatched += thread
    }

    val rows = ArrayList<JobRow>()
    for (family in families) {
        val primary = family.first()
        rows += buildRow(primary.key, primary.title, primary, byFolder[primary.key].orEmpty(), overrides, family.drop(1))
    }

    // The rest: one row per cluster of threads about the same job.
    val parent = IntArray(unmatched.size) { it }
    fun find(i: Int): Int { var x = i; while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x] }; return x }
    for (i in unmatched.indices) for (j in i + 1 until unmatched.size) {
        if (sameJobThreads(unmatched[i], unmatched[j])) parent[find(i)] = find(j)
    }
    unmatched.indices.groupBy { find(it) }.values.forEach { group ->
        val cluster = group.map { unmatched[it] }
        val all = cluster.flatMap { it.events }
        if (all.isEmpty()) return@forEach
        val key = "mail:${cluster.first().account.lowercase(Locale.ROOT)}:${cluster.minOf { it.threadId }}"
        rows += buildRow(key, jobNameOf(all), null, cluster, overrides)
    }
    return rows.filter { row -> (listOf(row.key) + row.alsoFolders.map { it.key }).none { it in hidden } }
}

private fun buildRow(key: String, title: String, folder: FolderJob?, threads: List<MailThread>, overrides: Map<String, JobStatus>, also: List<FolderJob> = emptyList()): JobRow {
    val tagged = threads.flatMap { t -> t.events.map { AccountEvent(t.account, it) } }
    val events = tagged.map { it.event }.sortedBy { it.dateMillis }
    val auto = statusFromEvents(events)
    val last = events.maxWithOrNull(compareBy<JobEvent>({ it.dateMillis }, { it.kind.ordinal }))
    val account = tagged.firstOrNull { it.event === last }?.account
    // A status the user set on any folder of the job counts (the row stands for them all).
    val override = overrides[key] ?: also.firstNotNullOfOrNull { overrides[it.key] }
    val status = override ?: auto ?: folder?.folderStatus ?: JobStatus.NOT_SEEN
    val made = (listOfNotNull(folder) + also).map { it.createdMillis }.filter { it > 0 }.minOrNull() ?: 0L
    return JobRow(key, title, folder, account, auto, status, override != null, last, folderNote(folder, auto), events, appliedMillis = appliedAt(events, made), alsoFolders = also)
}

/**
 * When a job was applied for. The truest answer is the first email that says so: the earliest mail you sent about it or the employer's first
 * acknowledgement. Failing that, the earliest email of any kind (a job whose only email is an interview invitation, say). Failing that, when its
 * folder was made ([folderCreatedMillis]). 0 means nothing says.
 */
fun appliedAt(events: List<JobEvent>, folderCreatedMillis: Long): Long {
    val applied = events.filter { it.kind == EmailKind.SENT_APPLICATION || it.kind == EmailKind.CONFIRMATION }.minOfOrNull { it.dateMillis }
    return applied ?: events.minOfOrNull { it.dateMillis } ?: folderCreatedMillis
}

/** The jobs with the one applied for most recently first; jobs with no date at all last, and jobs on the same date by name. */
fun orderByApplied(rows: List<JobRow>): List<JobRow> =
    rows.sortedWith(compareByDescending<JobRow> { it.appliedMillis }.thenBy { it.title.lowercase(Locale.ROOT) })

/** What to say when the folder and the emails disagree — never moves anything, only points it out. */
internal fun folderNote(folder: FolderJob?, auto: JobStatus?): String? {
    if (folder == null || auto == null) return null
    return when {
        auto == JobStatus.REJECTED && folder.folderStatus != JobStatus.REJECTED -> "Emails say unsuccessful — the folder isn't in 0. Rejected yet."
        auto == JobStatus.INTERVIEW && folder.folderStatus == null -> "Emails say interview — the folder isn't in 0.0. Interview Stage yet."
        auto == JobStatus.OFFER && folder.folderStatus != null -> "An offer came after the folder was filed under ${folder.folderStatus.folderName.lowercase(Locale.ROOT)}."
        auto == JobStatus.APPLIED && folder.folderStatus == JobStatus.REJECTED -> "The folder is in 0. Rejected, but the emails only show it as applied."
        auto == JobStatus.INTERVIEW && folder.folderStatus == JobStatus.REJECTED -> "The folder is in 0. Rejected, but the newest email is an interview."
        else -> null
    }
}
