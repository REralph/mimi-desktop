package com.example.a2desktop.logic

import java.time.LocalDate

// What the edit forms accept in their day and time boxes. Plain functions of plain text, unit-tested.

private val DAY_FIRST = Regex("""^(\d{1,2})[/.\-](\d{1,2})[/.\-](\d{2}|\d{4})$""")
private val YEAR_FIRST = Regex("""^(\d{4})-(\d{1,2})-(\d{1,2})$""")

/**
 * A day typed as 10/11/2026, 10.11.2026, 10-11-2026, 10/11/26 (20xx) or 2026-11-10 — the day first, as in the UK; null when it
 * isn't a real day (31/02/2026, blank, words).
 */
fun parseDayInput(text: String): LocalDate? {
    val t = text.trim()
    val (year, month, day) = YEAR_FIRST.matchEntire(t)?.destructured?.let { (y, m, d) -> Triple(y.toInt(), m.toInt(), d.toInt()) }
        ?: DAY_FIRST.matchEntire(t)?.destructured?.let { (d, m, y) -> Triple(if (y.length == 2) 2000 + y.toInt() else y.toInt(), m.toInt(), d.toInt()) }
        ?: return null
    return runCatching { LocalDate.of(year, month, day) }.getOrNull()
}

/** A day as the edit forms show it: 10/11/2026. */
fun formatDayInput(date: LocalDate): String = "%02d/%02d/%04d".format(date.dayOfMonth, date.monthValue, date.year)

/** Minutes after midnight for "14:30" / "9:05" / "14.30"; -1 for blank (all day); null when it isn't a time. */
fun parseTimeOfDay(text: String): Int? {
    val t = text.trim()
    if (t.isEmpty()) return -1
    val match = Regex("""^(\d{1,2})[:.](\d{2})$""").matchEntire(t) ?: return null
    val hours = match.groupValues[1].toInt()
    val minutes = match.groupValues[2].toInt()
    return if (hours in 0..23 && minutes in 0..59) hours * 60 + minutes else null
}
