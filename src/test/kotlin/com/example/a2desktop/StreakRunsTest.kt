package com.example.a2desktop

import com.example.a2desktop.logic.runEndsInYear
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The streak number for the Month Overview: where each run of consecutive days ends in a year, and how long it was. */
class StreakRunsTest {
    private fun d(m: Int, day: Int, year: Int = 2026) = LocalDate.of(year, m, day)
    private fun on(vararg ranges: Pair<LocalDate, LocalDate>): (LocalDate) -> Boolean =
        { date -> ranges.any { (from, to) -> !date.isBefore(from) && !date.isAfter(to) } }

    @Test fun eachRunEndsOnItsLastDayWithItsLength() {
        val ends = runEndsInYear(2026, on(d(3, 1) to d(3, 4), d(3, 10) to d(3, 10), d(5, 30) to d(6, 2)))
        assertEquals(mapOf(d(3, 4) to 4, d(3, 10) to 1, d(6, 2) to 4), ends)
    }

    @Test fun aRunThatBeganInTheYearBeforeCountsItsEarlierDays() {
        val ends = runEndsInYear(2026, on(d(12, 25, 2025) to d(1, 5)))
        assertEquals(mapOf(d(1, 5) to 12), ends, "7 days in December (25–31) and 5 in January")
    }

    @Test fun aRunThatGoesOnPastNewYearsEveHasNoEndInThatYear() {
        assertTrue(runEndsInYear(2026, on(d(12, 20) to d(1, 3, 2027))).isEmpty())
        assertEquals(mapOf(d(1, 3, 2027) to 15), runEndsInYear(2027, on(d(12, 20) to d(1, 3, 2027))), "and ends in the next, whole")
    }

    @Test fun aRunEndingOnNewYearsEveIsListedWhenTheNextDayBreaksIt() {
        assertEquals(mapOf(d(12, 31) to 3), runEndsInYear(2026, on(d(12, 29) to d(12, 31))))
    }

    @Test fun noRunsGivesNothingAndADayAloneIsOne() {
        assertTrue(runEndsInYear(2026) { false }.isEmpty())
        assertEquals(mapOf(d(7, 4) to 1), runEndsInYear(2026, on(d(7, 4) to d(7, 4))))
    }

    @Test fun aGapOfOneDaySplitsARun() {
        assertEquals(mapOf(d(2, 3) to 3, d(2, 7) to 3), runEndsInYear(2026, on(d(2, 1) to d(2, 3), d(2, 5) to d(2, 7))))
    }

    @Test fun aRunThatNeverStopsDoesNotLoopForever() {
        val ends = runEndsInYear(2026) { true }
        assertTrue(ends.isEmpty(), "it never ends, so no year has an end")
    }
}
