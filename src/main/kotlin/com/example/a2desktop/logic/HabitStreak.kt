package com.example.a2desktop.logic

import java.time.LocalDate
import java.time.temporal.ChronoUnit

data class HabitStreak(val start: LocalDate, val end: LocalDate) {
    val lengthDays: Long get() = ChronoUnit.DAYS.between(start, end) + 1
}

/**
 * Splits a streak habit's history into consecutive-day runs. Only dates where the streak habit
 * or one of [breakingHabitIds] was actually recorded are considered — an unrecorded day is never
 * assumed to continue the streak, it ends it exactly like an explicit breaking habit would. The
 * walk spans from the earliest to the latest of those relevant dates (not "today"), so a streak
 * habit that hasn't been touched in months doesn't silently erode just from time passing.
 */
fun computeHabitStreaks(
    habitsByDate: Map<LocalDate, Set<Long>>,
    streakHabitId: Long,
    breakingHabitIds: Set<Long>,
): List<HabitStreak> {
    val relevantIds = breakingHabitIds + streakHabitId
    val relevantDates = habitsByDate.filterValues { ids -> ids.any { it in relevantIds } }.keys
    if (relevantDates.isEmpty()) return emptyList()
    val streakDates = habitsByDate.filterValues { streakHabitId in it }.keys

    val streaks = mutableListOf<HabitStreak>()
    var streakStart: LocalDate? = null
    var day = relevantDates.min()
    val last = relevantDates.max()
    while (!day.isAfter(last)) {
        if (day in streakDates) {
            if (streakStart == null) streakStart = day
        } else {
            val start = streakStart
            if (start != null) {
                streaks += HabitStreak(start, day.minusDays(1))
                streakStart = null
            }
        }
        day = day.plusDays(1)
    }
    streakStart?.let { streaks += HabitStreak(it, last) }
    return streaks
}

/** 0 if the streak habit's most recent relevant date doesn't itself carry the streak habit
 * (streak just reset), or there's no history yet. */
fun currentHabitStreakLength(streaks: List<HabitStreak>, habitsByDate: Map<LocalDate, Set<Long>>, streakHabitId: Long, breakingHabitIds: Set<Long>): Int {
    val relevantIds = breakingHabitIds + streakHabitId
    val relevantDates = habitsByDate.filterValues { ids -> ids.any { it in relevantIds } }.keys
    if (relevantDates.isEmpty()) return 0
    val last = streaks.lastOrNull() ?: return 0
    return if (last.end == relevantDates.max()) last.lengthDays.toInt() else 0
}

/** Far enough back that a run which never stops (a test's "every day") still ends the walk. */
private const val MAX_RUN_DAYS = 366 * 150

/**
 * The days of [year] that END a run of consecutive days for which [inRun] is true — the run's last day — each with the
 * run's length in days, counting the days before the year too (a streak that began in December and ends in March shows
 * its whole length). A run that carries on past 31 December has no end in this year, so it is not listed.
 */
fun runEndsInYear(year: Int, inRun: (LocalDate) -> Boolean): Map<LocalDate, Int> {
    val first = LocalDate.of(year, 1, 1)
    var length = 0
    var before = first.minusDays(1)
    while (length < MAX_RUN_DAYS && inRun(before)) {
        length++
        before = before.minusDays(1)
    }
    val ends = LinkedHashMap<LocalDate, Int>()
    var day = first
    while (day.year == year) {
        if (inRun(day)) {
            length++
            if (!inRun(day.plusDays(1))) ends[day] = length
        } else {
            length = 0
        }
        day = day.plusDays(1)
    }
    return ends
}
