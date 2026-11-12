package com.example.a2desktop.logic

import java.util.Locale

/*
 * Giving a job found in your email the name you would give it: the job title, not "Reed.co.uk — Your job application for 'IT Support Engineer'".
 * Emails say it in a handful of well-worn ways ("Application for job 289-CS-355-A Patient Administrator", "Your application for X",
 * "TXP: Your application to our role of Trainee Field Service Engineer", "X application at Y"...), so plain patterns take the title out, throw away
 * the job's reference code and the boilerplate around it, and when an email names no job at all, fall back to the employer, then the sender.
 * Everything here works on text already kept in a JobEvent (subject, evidence sentence, sender): no email is read again.
 */

private val IC = RegexOption.IGNORE_CASE

private val replyPrefix = Regex("""^(?:(?:re|fwd?|fw)\s*:\s*)+""", IC)

/** "[290-WCCS-2440] Clinic Clerk" -> the reference in brackets at the front. */
private val bracketRef = Regex("""^\s*[\[(][^\])]{2,40}[\])]\s*""")

/** "Vacancy 749 749", "job 200-NN-7662939-A&C-B", "ref: ABC-123": a job's reference, which is not part of its name. */
private val labelledRef = Regex("""\b(?:job|vacancy|ref(?:erence)?|req(?:uisition)?)\s*(?:no\.?|number|id)?\s*[:#]?\s*(?:\d+(?:\s+\d+)+|[A-Z0-9][A-Z0-9&]*(?:[-/][A-Z0-9&]+)+)\b""", IC)

/** A bare reference like "396-7833310-DE-GV-Z": three or more hyphenated parts, at least one with a digit. */
private val bareRef = Regex("""\b(?=[A-Z0-9&-]*\d)[A-Z0-9&]{2,}(?:-[A-Z0-9&]+){2,}\b""")

/** A segment of a subject that is boilerplate, not part of the title: "Invite to Pre Recorded Video Interview", "Unfortunately, …", "First stage interview". */
private val boilerSegment = Regex(
    """^(?:invite|invitation|please|unfortunately|your application|application (?:update|received|outcome|status|confirmation|submitted|result)|update on\b|thank|thanks|congratulations|next steps|reminder|""" +
        """(?:(?:first|second|third|final)[- ]stage |telephone |phone |video |online |panel |pre[- ]recorded )*interview\b|""" +
        """we(?:'ve| have)\b|you(?:'ve| have)\b|good news|important)""",
    IC,
)

/** What is left when an email names no job: a subject that is nothing but boilerplate. */
private val pureBoiler = Regex(
    """^(?:thank(?:s| you)\b.*|we(?:'ve| have) (?:received|sent) your application.*|(?:your )?application (?:update|received|confirmation|outcome|submitted|status|result)s?|""" +
        """update on your application.*|your application\b.*|congratulations\b.*|re:.*|good news.*)$""",
    IC,
)

private val separators = Regex("""\s+[-–—|]\s+|:\s+""")

private fun tidyTitle(text: String): String =
    text.replace('’', '\'').replace('‘', '\'').replace('“', '"').replace('”', '"').replace(Regex("""\s+"""), " ")
        .trim().trim('-', '–', '—', ':', '|', '.', ',', ';', ' ', '!', '?', '\'', '"').trim()

/** Takes the boilerplate off both ends of "A - B - C - Invite to Interview" one segment at a time, keeping the title in the middle. */
private fun stripBoilerSegments(text: String): String {
    val segments = text.split(separators).map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
    while (segments.size > 1 && boilerSegment.containsMatchIn(segments.last())) segments.removeAt(segments.lastIndex)
    while (segments.size > 1 && boilerSegment.containsMatchIn(segments.first())) segments.removeAt(0)
    return segments.joinToString(" - ")
}

/**
 * The job title an email [subject] names, or null if it names none ("Application Update", "Thank You For Applying!", "We've sent your application to
 * Cerco" — which names an employer, not a job). The reference code of the job and the boilerplate around the title are dropped.
 */
fun jobTitleOf(subject: String): String? {
    var s = replyPrefix.replace(subject.trim(), "")
    s = s.replace('’', '\'').replace('‘', '\'').replace('“', '"').replace('”', '"')
    s = bracketRef.replace(s, "")
    s = labelledRef.replace(s, " ")
    s = bareRef.replace(s, " ")
    s = s.replace(Regex("""\s+"""), " ").trim() // (not tidyTitle: that would trim the quotes round a quoted title)

    val quoted = Regex("""application\s+(?:for|to|with)\s+(?:the\s+)?(?:job\s+)?['"](.+?)['"]""", IC).find(s)?.groupValues?.get(1)
    val roleOf = Regex("""\brole of\s+(.+)$""", IC).find(s)?.groupValues?.get(1)
    val appliedFor = Regex("""\bapplied for\s+(?:the\s+)?(.+?)\s+(?:vacancy|role|position|post|job)\b""", IC).find(s)?.groupValues?.get(1)
    // "IT Help desk analyst application at Arora": a title, then the word application. But "We've sent your application to Cerco" is not one.
    val titleThenAt = Regex("""^(.+?)\s+application\s+(?:at|with|to)\s+.+$""", IC).find(s)?.groupValues?.get(1)
        ?.takeUnless { Regex("""\b(?:we|we've|you|you've|your|our|my|sent|received|have|has|thank|thanks|for|the)\b""", IC).containsMatchIn(it) }
    val forTitle = Regex("""^(?:your\s+)?(?:job\s+)?application\s+(?:for|to)\s+(?:the\s+)?(?:job|vacancy|role|position|post)?\s*[-–:]?\s*(.+)$""", IC).find(s)?.groupValues?.get(1)
    val outcome = Regex("""^application\s+(?:outcome|update|status|result)s?\b[\s:–-]*(.+)$""", IC).find(s)?.groupValues?.get(1)

    val candidate = quoted ?: roleOf ?: appliedFor ?: titleThenAt ?: forTitle ?: outcome ?: s
    val title = tidyTitle(stripBoilerSegments(tidyTitle(candidate))).replace(Regex("""\s+applications?$""", IC), "").trim()
    if (title.length < 3 || pureBoiler.matches(title) || tokensOf(title).isEmpty()) return null
    return title.take(110)
}

/** The employer a SUBJECT itself names: "We've sent your application to Cerco", "Thanks for applying to ABM UK", "Your Application at X", "update on your application from Y". */
fun employerNamedIn(subject: String): String? {
    val s = tidyTitle(replyPrefix.replace(subject.trim(), "")).let { Regex("""\s+""").replace(it, " ") }
    val named = Regex("""\b(?:application|applying|applied)\s+(?:to|at|with)\s+(?!our\b|the\b|this\b|your\b)(.+)$""", IC).find(s)?.groupValues?.get(1)
        ?: Regex("""\bupdate on your application from\s+(.+)$""", IC).find(s)?.groupValues?.get(1)
        ?: Regex("""\bapplication (?:at|with)\s+(.+)$""", IC).find(s)?.groupValues?.get(1)
    return named?.let { tidyTitle(it) }?.takeIf { it.length >= 2 }?.take(80)
}

/** An employer's name as a bare word-and-number key, for comparing "Hireful" with "hireful.co.uk": lower case, letters and digits only. */
fun employerKeyOf(name: String): String = name.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

/** The employer an email names when it names no job: what the subject says ([employerNamedIn]), else the sender's name. */
fun employerOf(subject: String, from: String): String? {
    employerNamedIn(subject)?.let { return it }
    val name = senderName(from)
    // "Walker, Jamie" is a person writing to you, not an employer; a bare address or "noreply" says nothing either.
    if (name.isEmpty() || ',' in name || '@' in name || name.lowercase(Locale.ROOT).replace("-", "").replace(" ", "") in setOf("noreply", "donotreply")) return null
    return tidyTitle(name).takeIf { it.length >= 2 }?.take(80)
}

/**
 * The name for a job found only in email, from all the emails about it: the job title most of them agree on (the most specific one if they tie),
 * else the employer, else the sender, else the subject with its noise cut, else a plain "Application". Never the sender prefix or the boilerplate.
 */
fun jobNameOf(events: List<JobEvent>): String {
    val titles = events.mapNotNull { jobTitleOf(it.subject) }
    if (titles.isNotEmpty()) {
        return titles.groupBy { jobTitleKey(it) }.values
            .sortedWith(compareByDescending<List<String>> { it.size }.thenByDescending { group -> group.maxOf { it.length } })
            .first().maxByOrNull { it.length }!!
    }
    events.firstNotNullOfOrNull { employerOf(it.subject, it.from) }?.let { return it }
    val subject = events.firstOrNull()?.subject?.let { tidyTitle(replyPrefix.replace(it, "")) }.orEmpty()
    if (subject.isNotBlank() && !pureBoiler.matches(subject)) return subject.take(110)
    // Nothing names the job or the employer: say whose email it is, so it can be recognised (and deleted if it isn't a job).
    val address = events.firstOrNull()?.from?.let { if ('<' in it) it.substringAfter('<').substringBefore('>') else it }?.trim().orEmpty()
    return if ('@' in address) "Application from $address".take(110) else "Application"
}

/** Two titles are the same job's title when they are made of the same distinctive words, whatever the case, order or punctuation. */
fun jobTitleKey(title: String): String = tokensOf(title).sorted().joinToString(" ")
