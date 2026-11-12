package com.example.a2desktop.logic

import com.example.a2desktop.link.LinkWorkShift
import java.time.LocalDate
import java.time.LocalTime

/**
 * One working shift as the Calendar shows it: the day it starts on and the wall-clock start and end.
 * [endsNextDay] is true for a shift that runs past midnight.
 */
data class WorkShift(val date: LocalDate, val start: LocalTime, val end: LocalTime, val endsNextDay: Boolean)

/**
 * When the user works, as the phone shares it (it reads it from the user's Fourth Better app): start and
 * end times only. [coveredDays] are the days a rota read has covered, so a covered day with no shift
 * can show as OFF; a day nobody has read shows nothing rather than a wrong "OFF".
 */
data class WorkSchedule(
    val shifts: Map<LocalDate, List<WorkShift>> = emptyMap(),
    val coveredDays: Set<LocalDate> = emptySet(),
) {
    /** The shift a Calendar cell shows for [date] — the first of the day — or null. */
    fun shiftOn(date: LocalDate): WorkShift? = shifts[date]?.firstOrNull()

    /** A day that has been read and has no shift on it. */
    fun isOff(date: LocalDate): Boolean = date in coveredDays && shifts[date].isNullOrEmpty()

    /** False until the phone has sent any work times at all (not synced since it learned to, or no rota read). */
    val received: Boolean get() = shifts.isNotEmpty() || coveredDays.isNotEmpty()

    companion object {
        val EMPTY = WorkSchedule()
    }
}

/** The phone's shared shifts turned into a [WorkSchedule]; a row that doesn't make sense is skipped. */
fun buildWorkSchedule(shifts: List<LinkWorkShift>, coveredEpochDays: List<Long>): WorkSchedule {
    val parsed = shifts.mapNotNull { s ->
        if (s.startMinuteOfDay !in 0..1439 || s.endMinuteOfDay !in 0..1439) return@mapNotNull null
        WorkShift(
            date = LocalDate.ofEpochDay(s.dateEpochDay),
            start = LocalTime.of(s.startMinuteOfDay / 60, s.startMinuteOfDay % 60),
            end = LocalTime.of(s.endMinuteOfDay / 60, s.endMinuteOfDay % 60),
            endsNextDay = s.endsNextDay,
        )
    }
    return WorkSchedule(
        shifts = parsed.groupBy { it.date }.mapValues { (_, day) -> day.sortedBy { it.start } },
        coveredDays = coveredEpochDays.map(LocalDate::ofEpochDay).toSet(),
    )
}
