package com.example.a2desktop.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import com.example.a2desktop.link.AddEventChange
import com.example.a2desktop.link.AddTaskChange
import com.example.a2desktop.link.CareChange
import com.example.a2desktop.link.LINK_FEATURE_CARE_STAMPS
import com.example.a2desktop.link.DeleteEventChange
import com.example.a2desktop.link.DeleteTaskChange
import com.example.a2desktop.link.LinkCare
import com.example.a2desktop.link.LinkDream
import com.example.a2desktop.link.PendingChange
import com.example.a2desktop.link.SetHabitChange
import com.example.a2desktop.link.UpdateEventChange
import com.example.a2desktop.link.UpdateTaskChange
import com.example.a2desktop.link.LinkSnapshot
import com.example.a2desktop.logic.CareState
import com.example.a2desktop.logic.computeHabitStreaks
import com.example.a2desktop.logic.currentHabitStreakLength
import com.example.a2desktop.logic.habitDisplayLine
import com.example.a2desktop.logic.habitsOnDate
import com.example.a2desktop.logic.WorkSchedule
import com.example.a2desktop.logic.buildWorkSchedule
import com.example.a2desktop.logic.streakConflictingIds
import com.example.a2desktop.logic.totalForPeriod
import com.example.a2desktop.model.CalendarEvent
import com.example.a2desktop.model.Dream
import com.example.a2desktop.model.DreamStatus
import com.example.a2desktop.model.ExerciseSet
import com.example.a2desktop.model.GoalStartMode
import com.example.a2desktop.model.Habit
import com.example.a2desktop.model.HabitDisplayValue
import com.example.a2desktop.model.LoggedExercise
import com.example.a2desktop.model.UpcomingItem
import com.example.a2desktop.model.UpcomingKind
import com.example.a2desktop.model.WorkoutSession
import java.time.LocalDate

/** One line of the Calendar's Information card — the habit (for its colour) and its finished text. */
data class InfoLine(val habit: Habit, val text: String)

/** Where what's on screen came from. */
enum class DataSource {
    /** Nothing yet: the desktop has not received anything from a phone, and shows no data at all. */
    NONE,

    /** Made-up demo data, used only for layout previews and tests — the real app never loads it. */
    SAMPLE,

    /** The phone's data (the last sync, or its cached copy): the truth, shown read-only. */
    PHONE,
}

/**
 * All of the desktop app's state, in memory. It starts EMPTY — nothing is shown until the phone has
 * sent its data, so the desktop can never show habits or numbers the phone doesn't have. Everything
 * on screen then comes from one [LinkSnapshot], the phone's ([source] = PHONE, read-only: the phone
 * is the source of truth and the desktop never edits it). Demo data exists only through
 * [loadSampleData], for previews and tests. Both go through [applySnapshot], so there is one code
 * path to trust.
 */
class DesktopStore(today: LocalDate = LocalDate.now()) {
    var today by mutableStateOf(today)
        private set
    var habits by mutableStateOf<List<Habit>>(emptyList())
        private set
    /** Habits actually RECORDED per date (never includes the derived streak days). */
    val logs = mutableStateMapOf<LocalDate, Set<Long>>()
    var earliestTracked by mutableStateOf<LocalDate?>(null)
        private set
    val events = mutableStateListOf<CalendarEvent>()
    val upcoming = mutableStateListOf<UpcomingItem>()
    val dreams = mutableStateListOf<Dream>()
    val sessions = mutableStateListOf<WorkoutSession>()
    var dateOfBirth by mutableStateOf<LocalDate?>(null)
        private set

    /**
     * The skin-care and supplements record the phone holds (the phone keeps it; the two Calendar cards show it), or null from a phone
     * app that doesn't keep one yet — then those cards keep their own record on this PC, as before.
     */
    var care by mutableStateOf<LinkCare?>(null)
        private set

    /** What the phone app announced it can do (its last snapshot's [com.example.a2desktop.link.LinkSnapshot.features]); empty until a phone has answered. */
    var phoneFeatures by mutableStateOf<List<String>>(emptyList())
        private set

    /** Whether the phone keeps when each thing in the care record last changed, so it can be told when a waiting change was made. */
    val phoneStampsCare: Boolean get() = LINK_FEATURE_CARE_STAMPS in phoneFeatures

    /** When the user works — the start/end times the phone shares from their Fourth Better app. */
    var workSchedule by mutableStateOf(WorkSchedule.EMPTY)
        private set

    /** Where the data on screen came from — see [DataSource]. */
    var source by mutableStateOf(DataSource.NONE)
        private set

    /** True once data came from the phone: every edit control is then disabled. */
    val readOnly: Boolean get() = source == DataSource.PHONE

    /** Only demo data can be tinkered with; the phone's can't, and with no data there is nothing to. */
    private val editable: Boolean get() = source == DataSource.SAMPLE
    /** The phone's name when the data came from it, else null (sample data). */
    var deviceName by mutableStateOf<String?>(null)
        private set
    /** When the snapshot was taken on the phone. */
    var snapshotTakenMillis by mutableStateOf<Long?>(null)
        private set

    /** Fills the app with made-up demo data (previews and tests only — never the real app). */
    fun loadSampleData() {
        applySnapshot(sampleSnapshot(today), fromPhone = false)
    }

    /** Back to nothing: no habits, no data, [source] = NONE. */
    fun clear() {
        Snapshot.withMutableSnapshot {
            habits = emptyList()
            logs.clear()
            earliestTracked = null
            events.clear()
            upcoming.clear()
            dreams.clear()
            sessions.clear()
            dateOfBirth = null
            care = null
            phoneFeatures = emptyList()
            workSchedule = WorkSchedule.EMPTY
            source = DataSource.NONE
            deviceName = null
            snapshotTakenMillis = null
        }
    }

    fun refreshToday() {
        val now = LocalDate.now()
        if (now != today) today = now
    }

    val streakHabit: Habit? get() = habits.firstOrNull { it.isStreak }

    /** Replaces everything on screen with [snapshot], atomically (the UI never sees half of it). */
    fun applySnapshot(snapshot: LinkSnapshot, fromPhone: Boolean = true) {
        val newHabits = snapshot.habits.map { h ->
            Habit(
                id = h.id,
                emoji = h.emoji,
                name = h.name,
                colorArgb = h.colorArgb,
                isStreak = h.isStreakHabit,
                linkedToCalendar = h.linkedToCalendar,
                breaksStreakOfHabitId = h.breaksStreakOfHabitId,
                showInInformation = h.showInInformation,
                sortOrder = h.sortOrder,
                displayValues = h.displayValues.mapNotNull { runCatching { HabitDisplayValue.valueOf(it) }.getOrNull() }.toSet(),
                totalPeriodMonths = h.totalPeriodMonths,
                goalDurationDays = h.goalDurationDays,
                goalStartMode = runCatching { GoalStartMode.valueOf(h.goalStartMode) }.getOrDefault(GoalStartMode.LAST_RECORDED),
                goalStartDateEpochDay = h.goalStartDateEpochDay,
                goalCompletionDateEpochDay = h.goalCompletionDateEpochDay,
            )
        }
        val newLogs = HashMap<LocalDate, Set<Long>>()
        snapshot.habitLogs.forEach { log ->
            log.days.forEach { epochDay ->
                val date = LocalDate.ofEpochDay(epochDay)
                newLogs[date] = (newLogs[date] ?: emptySet()) + log.habitId
            }
        }
        val newEvents = snapshot.events.map { e ->
            CalendarEvent(
                id = e.id,
                date = LocalDate.ofEpochDay(e.dateEpochDay),
                title = e.title,
                time = e.startMinuteOfDay.takeIf { it >= 0 }?.let { "%02d:%02d".format(it / 60, it % 60) },
                completed = e.completed,
            )
        }
        val newUpcoming = snapshot.upcoming.map {
            UpcomingItem(it.id, it.title, LocalDate.ofEpochDay(it.dueEpochDay), if (it.kind == "EVENT") UpcomingKind.EVENT else UpcomingKind.TASK, it.repeatLabel)
        }
        val newDreams = snapshot.dreams.map {
            Dream(it.id, LocalDate.ofEpochDay(it.dateEpochDay), it.title, it.description, if (it.status == "DRAFT") DreamStatus.DRAFT else DreamStatus.SAVED)
        }
        val newSessions = snapshot.workouts.map { w ->
            WorkoutSession(
                id = w.id,
                date = LocalDate.ofEpochDay(w.dateEpochDay),
                label = w.label,
                durationMin = w.durationSeconds?.let { it / 60 },
                exercises = w.exercises.map { ex ->
                    LoggedExercise(ex.name, ex.sets.map { ExerciseSet(it.reps, it.weightKg) }, ex.plannedWeight, ex.plannedSets, ex.notes)
                },
                runSummary = w.runSummary,
            )
        }.sortedByDescending { it.date }

        Snapshot.withMutableSnapshot {
            habits = newHabits
            logs.clear(); logs.putAll(newLogs)
            earliestTracked = snapshot.earliestTrackedEpochDay?.let(LocalDate::ofEpochDay)
            events.clear(); events.addAll(newEvents)
            upcoming.clear(); upcoming.addAll(newUpcoming)
            dreams.clear(); dreams.addAll(newDreams)
            sessions.clear(); sessions.addAll(newSessions)
            dateOfBirth = snapshot.dateOfBirthEpochDay?.let(LocalDate::ofEpochDay)
            workSchedule = buildWorkSchedule(snapshot.workShifts, snapshot.workCoveredDays)
            care = snapshot.care
            phoneFeatures = snapshot.features
            source = if (fromPhone) DataSource.PHONE else DataSource.SAMPLE
            deviceName = if (fromPhone) snapshot.deviceName else null
            snapshotTakenMillis = if (fromPhone) snapshot.generatedAtMillis else null
        }
    }

    /** What the Calendar shows for [date]: recorded habits plus the derived streak state. */
    fun habitsOn(date: LocalDate): Set<Long> = habitsOnDate(date, logs[date].orEmpty(), habits, earliestTracked, today)

    // ---- Sample-mode editing (all of these do nothing once data comes from the phone) ----

    fun toggleHabit(date: LocalDate, habitId: Long) {
        if (!editable) return
        val current = logs[date].orEmpty()
        logs[date] = if (habitId in current) current - habitId else current + habitId
    }

    fun newDream(): Dream? {
        if (!editable) return null
        val dream = Dream((dreams.maxOfOrNull { it.id } ?: 0) + 1, today, "", "", DreamStatus.DRAFT)
        dreams.add(dream)
        return dream
    }

    fun saveDream(dream: Dream) {
        if (!editable) return
        val index = dreams.indexOfFirst { it.id == dream.id }
        if (index >= 0) dreams[index] = dream
    }

    /** The phone recorded or cleared a habit on [date]: show exactly the habits it now holds for that day. */
    fun applyHabitDay(date: LocalDate, habitIds: Set<Long>) {
        if (source != DataSource.PHONE) return
        if (habitIds.isEmpty()) logs.remove(date) else logs[date] = habitIds
    }

    /** The phone made a skin-care or supplements change the desktop sent: show the record as the phone now holds it. */
    fun applyCare(record: LinkCare) {
        if (source != DataSource.PHONE) return
        care = record
    }

    /**
     * Shows [changes] — made on this PC while the phone could not be reached, and not yet given to it — on top of what is on screen, so
     * the Calendar looks as the user left it: a deleted event stays gone, a new one is there (under a temporary negative id), a changed
     * one is changed. Called after every snapshot is applied (a snapshot is the phone's copy, which doesn't have them yet) and whenever
     * a new change is made. Does nothing for sample data.
     */
    fun applyPending(changes: List<PendingChange>) {
        if (source != DataSource.PHONE || changes.isEmpty()) return
        Snapshot.withMutableSnapshot {
            for (change in changes) when (change) {
                is AddEventChange -> {
                    val r = change.request
                    val date = LocalDate.ofEpochDay(r.dateEpochDay)
                    events.removeAll { it.id == change.tempId }
                    upcoming.removeAll { it.id == "event-${change.tempId}" }
                    events.add(CalendarEvent(change.tempId, date, r.title.trim(), minuteText(r.startMinuteOfDay), false))
                    upcoming.add(UpcomingItem("event-${change.tempId}", r.title.trim(), date, UpcomingKind.EVENT, eventRepeatLabel(r.repeatIntervalDays)))
                }
                is UpdateEventChange -> {
                    val r = change.request
                    val date = LocalDate.ofEpochDay(r.dateEpochDay)
                    events.indexOfFirst { it.id == r.id }.takeIf { it >= 0 }?.let { events[it] = events[it].copy(title = r.title.trim(), date = date, time = minuteText(r.startMinuteOfDay)) }
                    upcoming.indexOfFirst { it.id == "event-${r.id}" }.takeIf { it >= 0 }?.let {
                        upcoming[it] = upcoming[it].copy(title = r.title.trim(), due = date, repeat = if (r.keepRepeat) upcoming[it].repeat else eventRepeatLabel(r.repeatIntervalDays))
                    }
                }
                is DeleteEventChange -> {
                    events.removeAll { it.id == change.request.id }
                    upcoming.removeAll { it.id == "event-${change.request.id}" }
                }
                is AddTaskChange -> {
                    val r = change.request
                    upcoming.removeAll { it.id == "task-${change.tempId}" }
                    upcoming.add(UpcomingItem("task-${change.tempId}", r.name.trim(), LocalDate.ofEpochDay(r.dueEpochDay), UpcomingKind.TASK, taskRepeatLabel(r.repeatUnit, r.repeatInterval)))
                }
                is UpdateTaskChange -> {
                    val r = change.request
                    upcoming.indexOfFirst { it.id == "task-${r.id}" }.takeIf { it >= 0 }?.let {
                        upcoming[it] = upcoming[it].copy(
                            title = r.name.trim(),
                            due = LocalDate.ofEpochDay(r.dueEpochDay),
                            repeat = if (r.keepRepeat) upcoming[it].repeat else taskRepeatLabel(r.repeatUnit, r.repeatInterval),
                        )
                    }
                }
                is DeleteTaskChange -> upcoming.removeAll { it.id == "task-${change.request.id}" }
                // A skin-care or supplements change waiting for the phone is made on the phone's record as shown here, in order.
                is CareChange -> care = care?.let { CareState.fromWire(it).apply(change.request.commands).toWire() }
                is SetHabitChange -> {
                    val date = LocalDate.ofEpochDay(change.request.dateEpochDay)
                    val now = logs[date].orEmpty()
                    val next = if (change.request.logged) now + change.request.habitId else now - change.request.habitId
                    if (next.isEmpty()) logs.remove(date) else logs[date] = next
                }
            }
        }
    }

    private fun minuteText(minute: Int): String? = minute.takeIf { it >= 0 }?.let { "%02d:%02d".format(it / 60, it % 60) }

    /** The phone's wording for how an event repeats: "Every 14 days"; nothing for a one-off. */
    private fun eventRepeatLabel(days: Int?): String? = days?.takeIf { it > 0 }?.let { if (it == 1) "Every day" else "Every $it days" }

    /** The phone's wording for how a task repeats: "One-time", "Every week", "Every 2 months"… */
    private fun taskRepeatLabel(unit: String, interval: Int): String {
        val word = when (unit) { "DAYS" -> "day"; "WEEKS" -> "week"; "MONTHS" -> "month"; "YEARS" -> "year"; else -> return "One-time" }
        return if (interval <= 1) "Every $word" else "Every $interval ${word}s"
    }

    /** The phone saved a dream the desktop sent: show the phone's own saved copy of it. */
    fun applyDreamFromPhone(dream: LinkDream) {
        if (source != DataSource.PHONE) return
        val updated = Dream(dream.id, LocalDate.ofEpochDay(dream.dateEpochDay), dream.title, dream.description, if (dream.status == "DRAFT") DreamStatus.DRAFT else DreamStatus.SAVED)
        val index = dreams.indexOfFirst { it.id == updated.id }
        if (index >= 0) dreams[index] = updated else dreams.add(updated)
    }

    fun enterDateOfBirth(date: LocalDate) {
        if (!editable) return
        dateOfBirth = date
    }

    // ---- Derived numbers (the same maths as the phone's HabitStatsTracker) ----

    /** Every date from the first tracked day to today with the streak habit derived, plus any later recorded dates. */
    private fun derivedByDate(): Map<LocalDate, Set<Long>> {
        val result = HashMap<LocalDate, Set<Long>>(logs)
        val start: LocalDate? = earliestTracked
        if (start != null) {
            var day: LocalDate = start
            while (!day.isAfter(today)) {
                result[day] = habitsOn(day)
                day = day.plusDays(1)
            }
        }
        return result
    }

    fun infoLines(): List<InfoLine> {
        val derived = derivedByDate()
        val streak = streakHabit
        val streakHabitDays = if (streak == null) emptyList() else derived.filter { (d, ids) -> streak.id in ids && !d.isAfter(today) }.keys.sorted()
        return habits
            .filter { it.showInInformation }
            .sortedWith(compareBy({ it.sortOrder }, { it.id }))
            .map { habit ->
                val recorded = if (habit.id == streak?.id) streakHabitDays else logs.filterValues { habit.id in it }.keys.sorted()
                val streak = if (habit.isStreak) {
                    val conflicting = habits.streakConflictingIds(habit.id)
                    currentHabitStreakLength(computeHabitStreaks(derived, habit.id, conflicting), derived, habit.id, conflicting)
                } else {
                    0
                }
                InfoLine(
                    habit,
                    habitDisplayLine(
                        emoji = habit.emoji,
                        displayValues = habit.displayValues,
                        goalDurationDays = habit.goalDurationDays,
                        goalStartMode = habit.goalStartMode,
                        goalStartDateEpochDay = habit.goalStartDateEpochDay,
                        goalCompletionDateEpochDay = habit.goalCompletionDateEpochDay,
                        streakDays = streak,
                        lastDate = recorded.maxOrNull(),
                        total = totalForPeriod(recorded, today, habit.totalPeriodMonths),
                        totalPeriodMonths = habit.totalPeriodMonths,
                        today = today,
                    ),
                )
            }
    }
}
