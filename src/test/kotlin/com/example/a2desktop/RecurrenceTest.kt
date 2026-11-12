package com.example.a2desktop

import com.example.a2desktop.logic.Repeat
import com.example.a2desktop.logic.isProjectedOccurrence
import com.example.a2desktop.logic.isRepeatOccurrence
import com.example.a2desktop.logic.parseRepeatLabel
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Which dates a repeating event or task falls on, worked out from the phone's next due date and its repeat label. */
class RecurrenceTest {
    @Test fun theLabelsThePhoneSendsAreRead() {
        assertEquals(Repeat(1, ChronoUnit.WEEKS), parseRepeatLabel("Every week"))
        assertEquals(Repeat(1, ChronoUnit.DAYS), parseRepeatLabel("Every day"))
        assertEquals(Repeat(14, ChronoUnit.DAYS), parseRepeatLabel("Every 14 days"))
        assertEquals(Repeat(2, ChronoUnit.WEEKS), parseRepeatLabel("Every 2 weeks"))
        assertEquals(Repeat(3, ChronoUnit.MONTHS), parseRepeatLabel("Every 3 months"))
        assertEquals(Repeat(1, ChronoUnit.MONTHS), parseRepeatLabel("Every month"))
        assertEquals(Repeat(1, ChronoUnit.YEARS), parseRepeatLabel("Every year"))
        assertEquals(Repeat(2, ChronoUnit.YEARS), parseRepeatLabel(" every 2 Years "))
    }

    @Test fun aLabelThatDoesNotSayHowOftenIsNotGuessed() {
        // A weekly task pinned to a weekday is sent as just "Tuesday": it could be every week or every other.
        listOf(null, "", "One-time", "Tuesday", "Every", "Every 0 days", "Every fortnight", "Daily").forEach { assertNull(parseRepeatLabel(it), it.toString()) }
    }

    private val due = LocalDate.of(2026, 11, 10) // a Tuesday

    @Test fun everyFourteenDaysFallsOnEveryFourteenthDayAfterTheDueDate() {
        val repeat = Repeat(14, ChronoUnit.DAYS)
        assertTrue(isRepeatOccurrence(due, repeat, LocalDate.of(2026, 11, 24)))
        assertTrue(isRepeatOccurrence(due, repeat, LocalDate.of(2026, 12, 8)))
        assertFalse(isRepeatOccurrence(due, repeat, LocalDate.of(2026, 11, 17)), "a week on is not a fortnight on")
        assertFalse(isRepeatOccurrence(due, repeat, LocalDate.of(2026, 11, 25)))
    }

    @Test fun theDueDateItselfAndEarlierDaysAreNotLaterOccurrences() {
        val repeat = Repeat(7, ChronoUnit.DAYS)
        assertFalse(isRepeatOccurrence(due, repeat, due), "the due date is the item itself, not a repeat")
        assertFalse(isRepeatOccurrence(due, repeat, due.minusDays(7)), "and nothing is worked out backwards")
    }

    @Test fun everyWeekAndEveryNWeeksStepInWholeWeeks() {
        assertTrue(isRepeatOccurrence(due, Repeat(1, ChronoUnit.WEEKS), LocalDate.of(2026, 11, 17)))
        assertTrue(isRepeatOccurrence(due, Repeat(2, ChronoUnit.WEEKS), LocalDate.of(2026, 11, 24)))
        assertFalse(isRepeatOccurrence(due, Repeat(2, ChronoUnit.WEEKS), LocalDate.of(2026, 11, 17)))
    }

    @Test fun monthsStepFromThePreviousDateAsThePhoneDoes() {
        val repeat = Repeat(1, ChronoUnit.MONTHS)
        val jan31 = LocalDate.of(2027, 1, 31)
        // 31 Jan + 1 month = 28 Feb, and the next step is from 28 Feb: 28 Mar (not 31 Mar), exactly as the phone's nextOccurrence.
        assertTrue(isRepeatOccurrence(jan31, repeat, LocalDate.of(2027, 2, 28)))
        assertTrue(isRepeatOccurrence(jan31, repeat, LocalDate.of(2027, 3, 28)))
        assertFalse(isRepeatOccurrence(jan31, repeat, LocalDate.of(2027, 3, 31)))
        assertTrue(isRepeatOccurrence(due, Repeat(3, ChronoUnit.MONTHS), LocalDate.of(2027, 2, 10)))
        assertFalse(isRepeatOccurrence(due, Repeat(3, ChronoUnit.MONTHS), LocalDate.of(2027, 1, 10)))
    }

    @Test fun yearsStepInWholeYears() {
        assertTrue(isRepeatOccurrence(due, Repeat(1, ChronoUnit.YEARS), LocalDate.of(2027, 11, 10)))
        assertTrue(isRepeatOccurrence(due, Repeat(2, ChronoUnit.YEARS), LocalDate.of(2028, 11, 10)))
        assertFalse(isRepeatOccurrence(due, Repeat(2, ChronoUnit.YEARS), LocalDate.of(2027, 11, 10)))
    }

    @Test fun onlyDaysAfterTodayAreShownAsComingUp() {
        val weekly = Repeat(1, ChronoUnit.WEEKS)
        val overdue = LocalDate.of(2026, 10, 12) // a weekly task nobody has completed since
        val today = LocalDate.of(2026, 11, 6)
        assertTrue(isProjectedOccurrence(overdue, weekly, today, LocalDate.of(2026, 11, 9)), "the next Monday after today")
        assertTrue(isProjectedOccurrence(overdue, weekly, today, LocalDate.of(2026, 11, 16)))
        assertFalse(isProjectedOccurrence(overdue, weekly, today, LocalDate.of(2026, 11, 2)), "a week already missed is not a new one")
        assertFalse(isProjectedOccurrence(overdue, weekly, today, LocalDate.of(2026, 10, 19)))
        assertFalse(isProjectedOccurrence(overdue, weekly, today, today), "today is the item's own business, not a repeat")
    }

    @Test fun aDayFarAheadIsWorkedOutQuickly() {
        val far = LocalDate.of(2126, 11, 10)
        assertTrue(isProjectedOccurrence(due, Repeat(1, ChronoUnit.DAYS), LocalDate.of(2026, 11, 6), far))
        assertTrue(isProjectedOccurrence(due, Repeat(1, ChronoUnit.YEARS), LocalDate.of(2026, 11, 6), far))
    }
}
