package com.example.a2desktop.logic

import com.example.a2desktop.model.Habit
import java.time.LocalDate

/**
 * The habits that make a day NOT Sober when recorded on it — the phone's soberConflictingHabitIds:
 * every other habit that is Linked to the Calendar, plus any habit explicitly linked to Sober.
 * A "Do Not Link" habit with no relationship to Sober doesn't affect it.
 */
fun List<Habit>.soberConflictingIds(soberHabitId: Long?): Set<Long> =
    if (soberHabitId == null) {
        emptySet()
    } else {
        filter { it.id != soberHabitId && (it.linkedToCalendar || it.breaksStreakOfHabitId == soberHabitId) }
            .map { it.id }
            .toSet()
    }

/**
 * The phone app's Sober rule (see its SoberBackfill.kt), for one date. Sober is not a habit that
 * sits beside others, it is the default state of a day that has happened: from [trackedStart]
 * through [today] a date is Sober unless a conflicting habit was recorded — then only the recorded
 * habit(s) show, and an explicit Sober record beside one is suppressed. Future dates never become
 * Sober. Read-time only; nothing is stored.
 */
fun habitsOnDate(
    date: LocalDate,
    recorded: Set<Long>,
    habits: List<Habit>,
    trackedStart: LocalDate?,
    today: LocalDate,
): Set<Long> {
    val sober = habits.firstOrNull { it.isSober } ?: return recorded
    val conflicting = habits.soberConflictingIds(sober.id)
    return when {
        recorded.any { it in conflicting } -> recorded - sober.id
        trackedStart != null && !date.isAfter(today) && !date.isBefore(trackedStart) -> recorded + sober.id
        else -> recorded
    }
}
