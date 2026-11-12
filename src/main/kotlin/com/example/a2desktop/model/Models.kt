package com.example.a2desktop.model

import androidx.compose.ui.graphics.Color
import java.time.LocalDate

// Mirrors the phone app's entities (CustomHabit, CalendarEvent, Upcoming, Dream, WorkoutSession) at
// the level of detail the desktop shows. These are built from the snapshot the phone sends (see
// DesktopStore.applySnapshot); the desktop never writes them back — the phone is the source of truth.

/** One piece of information a habit's Information line can show — same names as the phone's. */
enum class HabitDisplayValue {
    STREAK, LAST_TIME, TOTAL,
    MONTHLY_TOTAL, TWO_MONTH_TOTAL, CUSTOM_PERIOD_TOTAL, // retired on the phone; still parsed, not shown
    TWO_WEEK_PROGRESS, NINETY_DAY_PROGRESS, LAST_DATE, GOAL_PROGRESS,
}

/** How a habit's Goal measures elapsed days — same as the phone's. */
enum class GoalStartMode { CURRENT_STREAK, TODAY, LAST_RECORDED, CHOSEN_DATE }

data class Habit(
    val id: Long,
    val emoji: String,
    val name: String,
    val colorArgb: Int,
    /** The designated Sober/streak habit (☁️ Sober) — derived for past/present days, not logged. */
    val isSober: Boolean = false,
    /** "Linked" paints this habit's dot on the Calendar; "Do Not Link" tracks it without a dot. */
    val linkedToCalendar: Boolean = true,
    val breaksStreakOfHabitId: Long? = null,
    val showInInformation: Boolean = true,
    val sortOrder: Int = 0,
    val displayValues: Set<HabitDisplayValue> = setOf(HabitDisplayValue.LAST_TIME),
    val totalPeriodMonths: Int? = null,
    val goalDurationDays: Int = 90,
    val goalStartMode: GoalStartMode = GoalStartMode.LAST_RECORDED,
    val goalStartDateEpochDay: Long? = null,
    val goalCompletionDateEpochDay: Long? = null,
) {
    val color: Color get() = Color(colorArgb)
}

/** [completed] is the phone's own mark that this event (occurrence) is done. */
data class CalendarEvent(val id: Long, val date: LocalDate, val title: String, val time: String?, val completed: Boolean = false)

enum class UpcomingKind { TASK, EVENT }

data class UpcomingItem(
    val id: String,
    val title: String,
    val due: LocalDate,
    val kind: UpcomingKind,
    val repeat: String? = null,
)

enum class DreamStatus { DRAFT, SAVED }

data class Dream(
    val id: Long,
    val date: LocalDate,
    val title: String,
    val text: String,
    val status: DreamStatus,
)

data class ExerciseSet(val reps: Int?, val weightKg: Double?)

data class LoggedExercise(
    val name: String,
    val sets: List<ExerciseSet>,
    val plannedWeight: String = "",
    val plannedSets: String = "",
    val notes: String = "",
) {
    val volumeKg: Double get() = sets.sumOf { (it.reps ?: 0) * (it.weightKg ?: 0.0) }
}

data class WorkoutSession(
    val id: Long,
    val date: LocalDate,
    val label: String?,
    val durationMin: Int?,
    val exercises: List<LoggedExercise>,
    val runSummary: String? = null,
) {
    val title: String get() = label?.takeIf { it.isNotBlank() } ?: if (runSummary != null) "Run" else "Workout"
    val volumeKg: Double get() = exercises.sumOf { it.volumeKg }
}
