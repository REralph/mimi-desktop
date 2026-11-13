package com.example.a2desktop.logic

import com.example.a2desktop.model.Habit
import java.time.LocalDate

/**
 * The habits that stop a day counting for the streak habit when recorded on it (the same rule as the
 * phone): every other habit that is Linked to the Calendar, plus any habit explicitly set to break the
 * streak. A "Do Not Link" habit with no relationship to the streak habit doesn't affect it.
 */
fun List<Habit>.streakConflictingIds(streakHabitId: Long?): Set<Long> =
    if (streakHabitId == null) {
        emptySet()
    } else {
        filter { it.id != streakHabitId && (it.linkedToCalendar || it.breaksStreakOfHabitId == streakHabitId) }
            .map { it.id }
            .toSet()
    }

/**
 * The phone app's streak rule, for one date. The streak habit (any habit the user marks as their streak,
 * with any name and emoji) is not logged beside others: it is the default state of a day that has
 * happened. From [trackedStart] through [today] a date counts for the streak unless a conflicting habit
 * was recorded; then only the recorded habit(s) show, and an explicit streak record beside one is
 * suppressed. Future dates never count. Read-time only; nothing is stored.
 */
fun habitsOnDate(
    date: LocalDate,
    recorded: Set<Long>,
    habits: List<Habit>,
    trackedStart: LocalDate?,
    today: LocalDate,
): Set<Long> {
    val streak = habits.firstOrNull { it.isStreak } ?: return recorded
    val conflicting = habits.streakConflictingIds(streak.id)
    return when {
        recorded.any { it in conflicting } -> recorded - streak.id
        trackedStart != null && !date.isAfter(today) && !date.isBefore(trackedStart) -> recorded + streak.id
        else -> recorded
    }
}
