package com.example.a2desktop.logic

import java.time.LocalDate
import java.time.temporal.ChronoUnit

// Working out the dates a repeating event or task falls on. The phone sends only the NEXT date a repeating item is due, plus a
// label for how it repeats ("Every week", "Every 14 days"); the dates after that are the same steps from it, exactly as the
// phone steps them when an occurrence is completed (it steps from the previous due date, never from the day it was done).
// Plain functions of plain values, unit-tested.

/** How often something repeats: every [interval] [unit]s (DAYS, WEEKS, MONTHS or YEARS). */
data class Repeat(val interval: Int, val unit: ChronoUnit)

private val LABEL = Regex("""^Every (?:(\d{1,4}) )?(day|week|month|year)s?$""", RegexOption.IGNORE_CASE)

/**
 * Whether the phone's label says it comes round again: any label but "One-time" (what a task done once is called) and nothing at all —
 * so "Every 2 weeks", "Weekly" and a bare weekday name ("Tuesday") all count, including the ones [parseRepeatLabel] can't read.
 */
fun labelRepeats(label: String?): Boolean = !label.isNullOrBlank() && !label.trim().equals("One-time", ignoreCase = true)

/**
 * The repeat the phone's label stands for: "Every day", "Every week", "Every 14 days", "Every 3 months"… Null for anything that
 * doesn't say how often — "One-time", nothing at all, and a bare weekday name ("Tuesday", which the phone sends for a weekly task
 * pinned to a weekday and which leaves out how many weeks apart) — because guessing would put things on the wrong days.
 */
fun parseRepeatLabel(label: String?): Repeat? {
    val match = LABEL.matchEntire(label?.trim() ?: return null) ?: return null
    val interval = match.groupValues[1].ifEmpty { "1" }.toInt()
    if (interval < 1) return null
    val unit = when (match.groupValues[2].lowercase()) {
        "day" -> ChronoUnit.DAYS
        "week" -> ChronoUnit.WEEKS
        "month" -> ChronoUnit.MONTHS
        else -> ChronoUnit.YEARS
    }
    return Repeat(interval, unit)
}

/** The most steps taken when stepping month by month or year by year (a thousand years' worth is far beyond any calendar). */
private const val MAX_STEPS = 12_000

/**
 * Whether [date] is a later occurrence of something first due on [due] that repeats as [repeat]: a date after [due] reached by
 * whole steps from it. Days and weeks are exact sums; months and years are stepped one at a time from the previous date, as the
 * phone does (so one starting on the 31st lands on the 28th in February and stays on the 28th).
 */
fun isRepeatOccurrence(due: LocalDate, repeat: Repeat, date: LocalDate): Boolean {
    if (!date.isAfter(due)) return false
    return when (repeat.unit) {
        ChronoUnit.DAYS, ChronoUnit.WEEKS -> {
            val step = repeat.interval.toLong() * (if (repeat.unit == ChronoUnit.WEEKS) 7 else 1)
            ChronoUnit.DAYS.between(due, date) % step == 0L
        }
        else -> {
            var next = due
            var steps = 0
            while (next.isBefore(date) && steps++ < MAX_STEPS) next = next.plus(repeat.interval.toLong(), repeat.unit)
            next == date
        }
    }
}

/**
 * Whether [date] is a day a repeating item will fall on and the Calendar should show it: a later occurrence after [due] (the day the
 * count starts from: the phone's due date, or the day the item was ticked off here)
 * ([isRepeatOccurrence]) that is also after [today]. What lies between the due date and today isn't shown — an overdue item stays
 * on the date it was due until it is completed, and only then moves on to the next date after that day.
 */
fun isProjectedOccurrence(due: LocalDate, repeat: Repeat, today: LocalDate, date: LocalDate): Boolean =
    date.isAfter(today) && isRepeatOccurrence(due, repeat, date)

/**
 * What the desktop knows of how an item the phone holds repeats, for editing it. [known] false means the phone didn't say, so an edit
 * must leave the repeat exactly as it is; [repeat] is null for "doesn't repeat" when [known], and meaningless when not.
 */
data class RepeatState(val known: Boolean, val repeat: Repeat?)

/**
 * An event's repeat from the label on the Upcoming list ("Every 14 days"; none at all for a one-off). An event that is not on the
 * Upcoming list (a completed one-off, a paused repeat) has no label to go by: unknown.
 */
fun eventRepeatState(label: String?, onUpcomingList: Boolean): RepeatState {
    if (!onUpcomingList) return RepeatState(false, null)
    if (label == null) return RepeatState(true, null)
    val repeat = parseRepeatLabel(label)
    return if (repeat != null && repeat.unit == ChronoUnit.DAYS) RepeatState(true, repeat) else RepeatState(false, null)
}

/**
 * A task's repeat from its Upcoming label: "One-time" is a known "doesn't repeat"; "Every 2 weeks" and the like are known; a bare
 * weekday name ("Tuesday", a weekly task pinned to a weekday, how many weeks apart left out) or no label is unknown.
 */
fun taskRepeatState(label: String?): RepeatState {
    if (label == null) return RepeatState(false, null)
    if (label.trim().equals("One-time", ignoreCase = true)) return RepeatState(true, null)
    return parseRepeatLabel(label)?.let { RepeatState(true, it) } ?: RepeatState(false, null)
}
