package com.example.a2desktop.logic

import com.example.a2desktop.model.GoalStartMode
import com.example.a2desktop.model.HabitDisplayValue
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

// COPY of the phone project's ui/calendar/HabitDisplayLine.kt (package/imports differ; the retired
// periodTotals helper is dropped) so the Information card shows exactly the phone's numbers.
private val habitDateFmt = DateTimeFormatter.ofPattern("d MMM yyyy")
private val goalUntilDateFmt = DateTimeFormatter.ofPattern("d MMM yyyy")

/**
 * The exact "emoji — text" line shown for a habit in the Information section — shared by
 * InformationBox (real data) and the Edit Habit screen's live preview (real-or-sample data), so
 * the two can never drift apart. A habit can combine any number of [displayValues]; they're always
 * rendered in the same canonical order (Streak, Last time, Total, 2 Week progress, 90 Day progress,
 * Last date, Goal progress) regardless of the order they were selected in, joined with " • ". An
 * empty selection renders as just the emoji.
 *
 * [total] is whatever [totalForPeriod] already computed for this habit's configured
 * [HabitDisplayValue.TOTAL] period (All Time or a rolling N months), and the line shows it together
 * with the length of that period in days — "Total: 20 / 61 days", "Total: 20 / All Time"
 * ([totalPeriodLabel]) — so the number always says what it covers. [totalPeriodMonths] is the same
 * period [total] was computed for; it only affects the wording, never the count.
 *
 * TWO_WEEK_PROGRESS/NINETY_DAY_PROGRESS always measure against [streakDays] directly, at a fixed
 * 14/90 days. GOAL_PROGRESS is a single goal of [goalDurationDays] days measured via
 * [goalElapsedDays]/[goalStartMode] — see [GoalStartMode]'s own doc comment. Both are completely
 * independent of [HabitDisplayValue.STREAK] being selected: whether the "Streak: N days" segment
 * renders is controlled purely by [displayValues], and never changes what number Goal/2 Week/90 Day
 * Progress computes (page 0.10's "Goal must work independently from Streak" rule) — [streakDays]
 * itself is simply whatever this habit's actual current streak is, 0 if it isn't streak-tracked.
 */
fun habitDisplayLine(
    emoji: String,
    displayValues: Set<HabitDisplayValue>,
    goalDurationDays: Int,
    goalStartMode: GoalStartMode,
    goalStartDateEpochDay: Long?,
    goalCompletionDateEpochDay: Long?,
    streakDays: Int,
    lastDate: LocalDate?,
    total: Int,
    totalPeriodMonths: Int?,
    today: LocalDate,
): String {
    fun relativeDays(): String {
        val date = lastDate ?: return "Never recorded"
        val daysAgo = ChronoUnit.DAYS.between(date, today)
        return if (daysAgo == 1L) "1 day ago" else "$daysAgo days ago"
    }

    val parts = mutableListOf<String>()
    if (HabitDisplayValue.STREAK in displayValues) parts += "Streak: $streakDays days"
    if (HabitDisplayValue.LAST_TIME in displayValues) parts += "Last: ${relativeDays()}"
    if (HabitDisplayValue.TOTAL in displayValues) parts += "Total: $total / ${totalPeriodLabel(totalPeriodMonths, today)}"
    if (HabitDisplayValue.TWO_WEEK_PROGRESS in displayValues) parts += goalProgress("2 Week", 14, streakDays)
    if (HabitDisplayValue.NINETY_DAY_PROGRESS in displayValues) parts += goalProgress("90 Day", 90, streakDays)
    if (HabitDisplayValue.LAST_DATE in displayValues) parts += if (lastDate == null) "Never recorded" else lastDate.format(habitDateFmt)
    if (HabitDisplayValue.GOAL_PROGRESS in displayValues) {
        val elapsed = goalElapsedDays(goalStartMode, goalStartDateEpochDay, streakDays, lastDate, today)
        val completionDate = goalCompletionDateEpochDay?.let(LocalDate::ofEpochDay)
        parts += if (elapsed == null) "Goal: Not started" else goalDisplaySegment(elapsed, goalDurationDays, completionDate, today)
    }

    if (parts.isEmpty()) return emoji
    return "$emoji — ${parts.joinToString(" • ")}"
}

/** A Total period spelled out for display, in days: "All Time" for no limit, otherwise how many
 * days the period actually covers as of [today]. The period is chosen in whole months (Edit Habit),
 * but it is a rolling window ending today — [totalForPeriod] counts the dates after
 * `today − N months` up to today — so its length in days is that window's real length: 2 months is
 * 61 days on 15 Oct (back to 15 Aug) but 59 days on 15 Mar (back to 15 Jan). Using the real figure
 * keeps the label exactly true to the number beside it. */
fun totalPeriodLabel(periodMonths: Int?, today: LocalDate): String {
    if (periodMonths == null) return "All Time"
    val days = ChronoUnit.DAYS.between(today.minusMonths(periodMonths.toLong()), today)
    return if (days == 1L) "1 day" else "$days days"
}

/** "label: elapsedDays/goal • N days remaining", or "label: Complete" once [elapsedDays] has
 * reached [goal] — remaining never goes negative since Complete replaces it instead. Used for 2
 * Week/90 Day Progress, which have no completion-date/overdue concept — see [goalDisplaySegment]
 * for the richer version Goal Progress itself uses. Not private: InsightsPrompt.kt's Habit Summary
 * reuses this directly so Copy Prompt/Copy Data's figures can never drift from what the Information
 * section itself shows. */
fun goalProgress(label: String, goal: Int, elapsedDays: Int): String {
    if (elapsedDays >= goal) return "$label: Complete"
    val remaining = goal - elapsedDays
    val remainingLabel = if (remaining == 1) "1 day remaining" else "$remaining days remaining"
    return "$label: $elapsedDays/$goal • $remainingLabel"
}

/**
 * The Goal Progress segment: "Goal: elapsed/duration • remaining • Until d MMM yyyy", or
 * "Goal: Complete • Until d MMM yyyy" once [elapsedDays] reaches [durationDays] — the "Until" suffix
 * (from [completionDateEpochDay]) appears either way, or not at all when there's no completion date
 * configured yet. If [completionDateEpochDay] has already passed and the goal isn't Complete, shows
 * "Overdue" instead of a (potentially negative) remaining-days count — reaching the day-count target
 * always wins and shows Complete regardless of whether that happens to land before/after the
 * original target date; Overdue is specifically the in-between "haven't hit it yet, and the date
 * I'd committed to has already gone by" state. Not private: InsightsPrompt.kt's Habit Summary reuses
 * this directly so Copy Prompt/Copy Data can never disagree with the Information section. */
fun goalDisplaySegment(elapsedDays: Int, durationDays: Int, completionDateEpochDay: LocalDate?, today: LocalDate): String {
    val untilSuffix = completionDateEpochDay?.let { " • Until ${it.format(goalUntilDateFmt)}" } ?: ""
    if (elapsedDays >= durationDays) return "Goal: Complete$untilSuffix"
    val overdue = completionDateEpochDay != null && today.isAfter(completionDateEpochDay)
    val statusText = when {
        overdue -> "Overdue"
        durationDays - elapsedDays == 1 -> "1 day remaining"
        else -> "${durationDays - elapsedDays} days remaining"
    }
    return "Goal: $elapsedDays/$durationDays • $statusText$untilSuffix"
}

/**
 * Elapsed days for Goal Progress — the one place this is computed, shared by habitDisplayLine, the
 * Edit Habit preview and InsightsPrompt's Habit Summary. Driven entirely by [goalStartMode], which
 * is a genuinely independent setting from [HabitDisplayValue.STREAK]/[com.example.a2.data.CustomHabit.isStreakHabit]
 * (see [GoalStartMode]'s own doc comment for why that independence matters):
 *  - CURRENT_STREAK: elapsed = this habit's actual current consecutive [streakDays].
 *  - LAST_RECORDED: elapsed = days since [lastDate] — the original, always-on Break-Goal behaviour.
 *    Null (never recorded) means there's no start point to measure from, so this returns null
 *    rather than inventing one.
 *  - TODAY / CHOSEN_DATE: elapsed = days since the fixed [goalStartDateEpochDay] (never negative —
 *    a future start date simply reads as 0 elapsed rather than going below zero).
 */
fun goalElapsedDays(
    goalStartMode: GoalStartMode,
    goalStartDateEpochDay: Long?,
    streakDays: Int,
    lastDate: LocalDate?,
    today: LocalDate,
): Int? = when (goalStartMode) {
    GoalStartMode.CURRENT_STREAK -> streakDays
    GoalStartMode.LAST_RECORDED -> lastDate?.let { ChronoUnit.DAYS.between(it, today).toInt() }
    GoalStartMode.TODAY, GoalStartMode.CHOSEN_DATE -> {
        val start = goalStartDateEpochDay?.let(LocalDate::ofEpochDay) ?: today
        ChronoUnit.DAYS.between(start, today).toInt().coerceAtLeast(0)
    }
}

/** The calendar date Goal Progress is currently measuring elapsed time from — null only for
 * LAST_RECORDED when the habit has never been recorded (there's nothing to anchor to yet). Used by
 * Edit Habit to auto-recompute [com.example.a2.data.CustomHabit.goalCompletionDateEpochDay]
 * whenever Duration or Start changes. */
fun goalEffectiveStartDate(goalStartMode: GoalStartMode, goalStartDateEpochDay: Long?, streakDays: Int, lastDate: LocalDate?, today: LocalDate): LocalDate? =
    when (goalStartMode) {
        GoalStartMode.CURRENT_STREAK -> today.minusDays(streakDays.toLong())
        GoalStartMode.LAST_RECORDED -> lastDate
        GoalStartMode.TODAY, GoalStartMode.CHOSEN_DATE -> goalStartDateEpochDay?.let(LocalDate::ofEpochDay)
    }

/** Target completion date = [effectiveStartDate] + [durationDays] — used by Edit Habit to
 * auto-recompute [com.example.a2.data.CustomHabit.goalCompletionDateEpochDay] whenever Duration or
 * Start changes (picking a completion date directly works the other way: recompute duration =
 * daysBetween(start, pickedDate) instead). Null when there's no effective start yet to anchor from. */
fun goalCompletionDate(effectiveStartDate: LocalDate?, durationDays: Int): LocalDate? =
    effectiveStartDate?.plusDays(durationDays.toLong())

/** [HabitDisplayValue.TOTAL]'s configured-period count — the one place this math happens, called
 * identically by InformationBox (real recorded dates, from HabitStatsTracker.recordedDatesPerHabit)
 * and the Edit Habit live preview, so a habit's Total can never be computed two different ways.
 * [periodMonths] null means All Time (every recorded date, i.e. the plain lifetime count); a
 * non-null value counts only dates within the last [periodMonths] calendar months, ending today —
 * "the current date backwards through the selected N-month period", not calendar-month-aligned. A
 * date outside the window simply isn't counted — there's no interpolation/estimation of any kind. */
fun totalForPeriod(recordedDates: List<LocalDate>, today: LocalDate, periodMonths: Int?): Int {
    if (periodMonths == null) return recordedDates.size
    val start = today.minusMonths(periodMonths.toLong())
    return recordedDates.count { it.isAfter(start) && !it.isAfter(today) }
}
