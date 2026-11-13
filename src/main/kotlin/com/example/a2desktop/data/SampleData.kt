package com.example.a2desktop.data

import com.example.a2desktop.link.LinkDream
import com.example.a2desktop.link.LinkEvent
import com.example.a2desktop.link.LinkExercise
import com.example.a2desktop.link.LinkHabit
import com.example.a2desktop.link.LinkHabitLog
import com.example.a2desktop.link.LinkSet
import com.example.a2desktop.link.LinkSnapshot
import com.example.a2desktop.link.LinkUpcoming
import com.example.a2desktop.link.LinkWorkShift
import com.example.a2desktop.link.LinkWorkout
import java.time.LocalDate
import kotlin.random.Random

/**
 * A made-up snapshot in exactly the shape the phone sends, shown until the first real sync. Using
 * the same shape means sample data and phone data go through one code path (DesktopStore.applySnapshot).
 * Habit names are neutral on purpose — none of this is anyone's real data.
 */
fun sampleSnapshot(today: LocalDate): LinkSnapshot {
    val rng = Random(7)
    fun day(offset: Int) = today.minusDays(offset.toLong()).toEpochDay()

    val streak = 1L; val gym = 2L; val takeaway = 3L; val reading = 4L; val meditation = 5L; val lateNight = 6L
    fun habit(
        id: Long, emoji: String, name: String, color: Long, streak: Boolean = false, linked: Boolean = true,
        values: List<String>, months: Int? = null, goalStart: String = "LAST_RECORDED", goalDays: Int = 90, order: Int,
    ) = LinkHabit(
        id = id, emoji = emoji, name = name, nickname = name, colorArgb = color.toInt(), isStreakHabit = streak,
        linkedToCalendar = linked, showInInformation = true, sortOrder = order, displayValues = values,
        totalPeriodMonths = months, goalStartMode = goalStart, goalDurationDays = goalDays,
    )
    val habits = listOf(
        habit(streak, "🔥", "Daily streak", 0xFF6EC6F5, streak = true, values = listOf("STREAK", "TOTAL"), months = 2, goalStart = "CURRENT_STREAK", order = 0),
        habit(gym, "🏋️", "Gym", 0xFFF2C94C, values = listOf("LAST_TIME", "TOTAL"), months = 3, order = 1),
        habit(takeaway, "🍕", "Takeaway", 0xFFF2994A, values = listOf("LAST_TIME", "GOAL_PROGRESS"), goalDays = 60, order = 2),
        habit(reading, "📖", "Reading", 0xFF6FCF97, linked = false, values = listOf("LAST_TIME", "TOTAL"), order = 3),
        habit(meditation, "🧘", "Meditation", 0xFFBB6BD9, linked = false, values = listOf("LAST_TIME", "TOTAL"), months = 1, order = 4),
        habit(lateNight, "🌙", "Late night", 0xFFEB5757, values = listOf("LAST_TIME", "TOTAL"), order = 5),
    )

    // Random history up to 3 days ago; the last few days are fixed so the demo is stable.
    val days = HashMap<Long, MutableList<Long>>()
    fun record(id: Long, epochDay: Long) = days.getOrPut(id) { mutableListOf() }.add(epochDay)
    for (offset in 74 downTo 3) {
        if (rng.nextDouble() < 0.34) record(gym, day(offset))
        if (rng.nextDouble() < 0.07) record(takeaway, day(offset))
        if (rng.nextDouble() < 0.05) record(lateNight, day(offset))
        if (rng.nextDouble() < 0.50) record(reading, day(offset))
        if (rng.nextDouble() < 0.40) record(meditation, day(offset))
    }
    record(gym, day(2)); record(reading, day(2)); record(meditation, day(1))

    fun set(reps: Int, kg: Double) = LinkSet(kg, reps)
    fun exercise(name: String, vararg sets: LinkSet) = LinkExercise(name, sets = sets.toList())
    fun workout(id: Long, offset: Int, label: String, minutes: Int, vararg exercises: LinkExercise) =
        LinkWorkout(id, day(offset), label, minutes * 60, exercises.toList())

    return LinkSnapshot(
        generatedAtMillis = System.currentTimeMillis(),
        todayEpochDay = today.toEpochDay(),
        deviceName = "Sample data",
        earliestTrackedEpochDay = day(74),
        // A made-up rota for previews and tests: a few daytime shifts over the next two weeks, days off between.
        workShifts = listOf(0, 1, 2, 5, 6, 9, 10, 11).map { offset ->
            LinkWorkShift(today.plusDays(offset.toLong()).toEpochDay(), if (offset % 3 == 2) 8 * 60 else 12 * 60 + 30, if (offset % 3 == 2) 16 * 60 + 30 else 21 * 60)
        },
        workCoveredDays = (0..13).map { today.plusDays(it.toLong()).toEpochDay() },
        habits = habits,
        habitLogs = days.map { (id, list) -> LinkHabitLog(id, list.sorted()) },
        events = listOf(
            LinkEvent(1, "Car service", day(2), 9 * 60),
            LinkEvent(2, "Dentist", today.plusDays(2).toEpochDay(), 14 * 60 + 30),
            LinkEvent(3, "Team call", today.plusDays(4).toEpochDay(), 11 * 60),
            LinkEvent(4, "Birthday dinner", today.plusDays(9).toEpochDay(), 19 * 60 + 30),
            LinkEvent(5, "Pick up parcel", today.toEpochDay(), -1),
        ),
        upcoming = listOf(
            LinkUpcoming("task-1", "Water plants", today.toEpochDay(), "TASK", "Weekly"),
            LinkUpcoming("task-2", "Change bed sheets", today.plusDays(1).toEpochDay(), "TASK", "Every 2 weeks"),
            LinkUpcoming("event-2", "Dentist", today.plusDays(2).toEpochDay(), "EVENT"),
            LinkUpcoming("event-3", "Team call", today.plusDays(4).toEpochDay(), "EVENT"),
            LinkUpcoming("task-3", "Pay rent", today.plusDays(6).toEpochDay(), "TASK", "Every 1 month"),
            LinkUpcoming("task-4", "Renew gym membership", today.plusDays(12).toEpochDay(), "TASK", "One-time"),
        ),
        dreams = listOf(
            LinkDream(1, day(1), "Flying over a lake", "I was gliding low over a very still lake at dawn. The water was glassy and I could see my own reflection getting larger as I descended, but I never touched it.", "SAVED"),
            LinkDream(2, day(3), "The old house", "Back in a childhood house, but the rooms were in the wrong order. Every door opened onto a room I half remembered.", "SAVED"),
            LinkDream(3, day(6), "Train that never stopped", "Draft: sitting on a train that kept passing the same station. Someone", "DRAFT"),
            LinkDream(4, day(11), "Exam I forgot about", "Walked into a hall and realised the exam was today. The pen kept running out of ink.", "SAVED"),
        ),
        workouts = listOf(
            workout(1, 2, "Push A", 62,
                exercise("Bench Press", set(8, 70.0), set(8, 70.0), set(6, 72.5), set(6, 72.5)),
                exercise("Overhead Press", set(10, 40.0), set(8, 42.5), set(8, 42.5)),
                exercise("Incline Dumbbell Press", set(12, 26.0), set(10, 28.0), set(10, 28.0)),
                exercise("Triceps Pushdown", set(15, 30.0), set(12, 35.0), set(12, 35.0))),
            workout(2, 5, "Pull A", 58,
                exercise("Barbell Row", set(8, 65.0), set(8, 65.0), set(8, 67.5)),
                exercise("Pull-up", set(8, 0.0), set(7, 0.0), set(6, 0.0)),
                exercise("Face Pull", set(15, 25.0), set(15, 25.0), set(15, 27.5)),
                exercise("Hammer Curl", set(12, 16.0), set(10, 18.0), set(10, 18.0))),
            workout(3, 7, "Legs", 71,
                exercise("Back Squat", set(6, 100.0), set(6, 100.0), set(5, 105.0), set(5, 105.0)),
                exercise("Romanian Deadlift", set(8, 80.0), set(8, 80.0), set(8, 85.0)),
                exercise("Leg Press", set(12, 160.0), set(12, 170.0), set(10, 180.0)),
                exercise("Calf Raise", set(15, 60.0), set(15, 60.0), set(15, 65.0))),
            workout(4, 9, "Push A", 60,
                exercise("Bench Press", set(8, 67.5), set(8, 67.5), set(7, 70.0), set(6, 70.0)),
                exercise("Overhead Press", set(10, 40.0), set(8, 40.0), set(8, 42.5)),
                exercise("Triceps Pushdown", set(15, 30.0), set(12, 32.5), set(12, 32.5))),
            workout(5, 12, "Pull A", 55,
                exercise("Barbell Row", set(8, 62.5), set(8, 65.0), set(8, 65.0)),
                exercise("Pull-up", set(7, 0.0), set(6, 0.0), set(6, 0.0)),
                exercise("Hammer Curl", set(12, 16.0), set(10, 16.0), set(10, 18.0))),
            workout(6, 14, "Legs", 68,
                exercise("Back Squat", set(6, 97.5), set(6, 97.5), set(5, 102.5)),
                exercise("Leg Press", set(12, 150.0), set(12, 160.0), set(10, 170.0))),
        ),
    )
}
