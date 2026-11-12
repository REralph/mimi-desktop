package com.example.a2desktop.logic

import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit

// "Smart" days for something that repeats: instead of falling on the exact date the phone has for it, each time it comes round it
// lands on the best day for it given when the user works — a day off, the day before work, the last work day before days off… The
// phone knows nothing of this (it only has the plain repeat), so it is worked out here from the work times the phone shares, and kept on
// this PC. Plain functions of plain values, unit-tested.

/**
 * Which day of its period something should land on. [ANY] is no rule at all (the plain date). The rest each name a kind of day in the
 * user's rota. A day outside the rota the phone has read is neither work nor a day off: it never counts as either.
 */
enum class DayPick(val label: String, val explain: String) {
    ANY("On the date itself", "Exactly on the date it falls."),
    DAY_OFF("On a day off", "The first day off from that date on."),
    FIRST_DAY_OFF("On my first day off", "The first day of a run of days off, once I've been working."),
    LAST_DAY_OFF("On my last day off", "The last day off before I'm back at work."),
    DAY_BEFORE_WORK("The day before work", "A day when I work the next day."),
    LAST_WORK_DAY("On my last work day", "My final shift before days off."),
    FIRST_WORK_DAY("On my first day back at work", "The first shift after days off."),
    WORK_DAY("On a work day", "Any day I have a shift."),
    EARLY_START("On a day I start early", "A work day when my shift starts before 12:00."),
    LATE_START("On a day I start late", "A work day when my shift starts at 12:00 or later."),
    ;

    /** Whether [date] is this kind of day in [schedule]. A day the rota doesn't cover is never any kind (nothing is guessed). */
    fun matches(date: LocalDate, schedule: WorkSchedule): Boolean {
        fun working(d: LocalDate) = schedule.shiftOn(d) != null
        fun off(d: LocalDate) = schedule.isOff(d)
        return when (this) {
            ANY -> true
            DAY_OFF -> off(date)
            FIRST_DAY_OFF -> off(date) && working(date.minusDays(1))
            LAST_DAY_OFF -> off(date) && working(date.plusDays(1))
            DAY_BEFORE_WORK -> (working(date) || off(date)) && working(date.plusDays(1))
            LAST_WORK_DAY -> working(date) && off(date.plusDays(1))
            FIRST_WORK_DAY -> working(date) && off(date.minusDays(1))
            WORK_DAY -> working(date)
            EARLY_START -> schedule.shiftOn(date)?.let { it.start < NOON } == true
            LATE_START -> schedule.shiftOn(date)?.let { it.start >= NOON } == true
        }
    }

    companion object {
        private val NOON: LocalTime = LocalTime.NOON

        /** The pick saved under [name], or [ANY] for nothing saved or a name this version doesn't know. */
        fun fromName(name: String?): DayPick = entries.firstOrNull { it.name == name } ?: ANY
    }
}

/** Where a smart pick put one occurrence: [date], and whether a day of the wanted kind was really found ([matched]; else it stays on its plain date). */
data class SmartDate(val date: LocalDate, val matched: Boolean)

/**
 * Which repeat the smart day works within, from the phone's label: the label's own repeat, or — for a repeat the PC can't read
 * ("Weekly", a weekday name) — a week, so nothing is moved further than that. Null when the label says it doesn't repeat. A smart day
 * only means something for a repeat of a week or more, so a shorter one gives null too ([SMART_MIN_DAYS]).
 */
fun smartRepeat(label: String?): Repeat? {
    if (!labelRepeats(label)) return null
    val repeat = parseRepeatLabel(label) ?: Repeat(1, ChronoUnit.WEEKS)
    return repeat.takeIf { approxDays(it) >= SMART_MIN_DAYS }
}

/** The fewest days between repeats for which a smart day is offered: each one needs room to be moved to the right day. */
const val SMART_MIN_DAYS = 7

/** About how many days one step of [repeat] is (a month as its shortest, 28; a year as 365). */
fun approxDays(repeat: Repeat): Int = when (repeat.unit) {
    ChronoUnit.DAYS -> repeat.interval
    ChronoUnit.WEEKS -> repeat.interval * 7
    ChronoUnit.MONTHS -> repeat.interval * 28
    else -> repeat.interval * 365
}

/** How many days lie between the occurrence on [from] and the next one: the room an occurrence has to move within without passing the next. */
fun windowDays(from: LocalDate, repeat: Repeat): Int = ChronoUnit.DAYS.between(from, from.plus(repeat.interval.toLong(), repeat.unit)).toInt().coerceAtLeast(1)

/**
 * Where the occurrence that falls on [nominal] lands for [pick]: the first day of that kind from [nominal] on, within [windowDays]
 * (so never early, and never as late as the next occurrence). An occurrence that is already late (its [nominal] day passed, but still
 * inside its window) is looked for from [today] on, so it isn't put on a day that has gone, and one whose whole window has gone by stays
 * on [nominal]. If no day of the kind is found — the rota
 * isn't known that far, or there is none in the window — it stays on [nominal] (and [SmartDate.matched] says so).
 */
fun snapDay(pick: DayPick, nominal: LocalDate, windowDays: Int, today: LocalDate, schedule: WorkSchedule): SmartDate {
    if (pick == DayPick.ANY) return SmartDate(nominal, true)
    val end = nominal.plusDays(windowDays.toLong())
    if (!end.isAfter(today)) return SmartDate(nominal, true) // the whole window has gone by: it stays where it was due
    var day = if (today.isAfter(nominal) && today.isBefore(end)) today else nominal
    while (day.isBefore(end)) {
        if (pick.matches(day, schedule)) return SmartDate(day, true)
        day = day.plusDays(1)
    }
    return SmartDate(nominal, false)
}

/**
 * The latest occurrence of something first due on [due] that falls on or before [date], not counting [due] itself — the start of the
 * period [date] is in — or null when [date] is before the first repeat. Days and weeks are exact; months and years are stepped one at a
 * time, as the phone does ([isRepeatOccurrence]).
 */
fun occurrenceOnOrBefore(due: LocalDate, repeat: Repeat, date: LocalDate): LocalDate? {
    if (!date.isAfter(due)) return null
    return when (repeat.unit) {
        ChronoUnit.DAYS, ChronoUnit.WEEKS -> {
            val step = repeat.interval.toLong() * (if (repeat.unit == ChronoUnit.WEEKS) 7 else 1)
            val k = ChronoUnit.DAYS.between(due, date) / step
            if (k < 1) null else due.plusDays(k * step)
        }
        else -> {
            var last: LocalDate? = null
            var next = due.plus(repeat.interval.toLong(), repeat.unit)
            var steps = 0
            while (!next.isAfter(date) && steps++ < MAX_SMART_STEPS) {
                last = next
                next = next.plus(repeat.interval.toLong(), repeat.unit)
            }
            last
        }
    }
}

private const val MAX_SMART_STEPS = 12_000

/**
 * Where the occurrence due on [due] is shown for [pick], using [label] for how it repeats (a one-off, or a repeat too short for a smart
 * day, stays on [due]).
 */
fun shownDue(pick: DayPick, due: LocalDate, label: String?, today: LocalDate, schedule: WorkSchedule): SmartDate {
    val repeat = smartRepeat(label)
    if (pick == DayPick.ANY || repeat == null) return SmartDate(due, true)
    return snapDay(pick, due, windowDays(due, repeat), today, schedule)
}

/**
 * Whether [date] is a later occurrence (after [due], and after [today]) of something that repeats as [repeat] once it is moved to [pick]
 * days: the occurrence that starts the period [date] is in must itself start after today, and land on [date].
 */
fun isSmartProjected(pick: DayPick, due: LocalDate, repeat: Repeat, today: LocalDate, date: LocalDate, schedule: WorkSchedule): Boolean {
    if (!date.isAfter(today) || smartRepeat(labelFor(repeat)) == null) return false
    val start = occurrenceOnOrBefore(due, repeat, date) ?: return false
    if (!start.isAfter(today)) return false
    return snapDay(pick, start, windowDays(start, repeat), today, schedule).date == date
}

/** The phone's own wording for [repeat], so a repeat the PC has parsed can be checked by [smartRepeat] again. */
private fun labelFor(repeat: Repeat): String {
    val word = when (repeat.unit) { ChronoUnit.DAYS -> "day"; ChronoUnit.WEEKS -> "week"; ChronoUnit.MONTHS -> "month"; else -> "year" }
    return if (repeat.interval <= 1) "Every $word" else "Every ${repeat.interval} ${word}s"
}
