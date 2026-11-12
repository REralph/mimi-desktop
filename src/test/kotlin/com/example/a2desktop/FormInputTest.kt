package com.example.a2desktop

import com.example.a2desktop.logic.Repeat
import com.example.a2desktop.logic.RepeatState
import com.example.a2desktop.logic.eventRepeatState
import com.example.a2desktop.logic.formatDayInput
import com.example.a2desktop.logic.parseDayInput
import com.example.a2desktop.logic.parseTimeOfDay
import com.example.a2desktop.logic.taskRepeatState
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** What the edit forms accept in their day and time boxes, and what the desktop knows of how an existing item repeats. */
class FormInputTest {
    private val nov10 = LocalDate.of(2026, 11, 10)

    @Test fun aDayIsReadDayFirstInTheCommonWays() {
        assertEquals(nov10, parseDayInput("10/11/2026"))
        assertEquals(nov10, parseDayInput(" 10.11.2026 "))
        assertEquals(nov10, parseDayInput("10-11-2026"))
        assertEquals(nov10, parseDayInput("2026-11-10"))
        assertEquals(nov10, parseDayInput("10/11/26"), "a two-digit year is 20xx")
        assertEquals(LocalDate.of(2026, 1, 5), parseDayInput("5/1/2026"), "5 January, not 1 May: the day comes first")
    }

    @Test fun aDayThatIsNotRealIsRejected() {
        listOf("31/02/2026", "32/01/2026", "10/13/2026", "0/1/2026", "", "   ", "tomorrow", "10/11", "2026/11/10", "10/11/202").forEach { assertNull(parseDayInput(it), it) }
        assertEquals(LocalDate.of(2028, 2, 29), parseDayInput("29/02/2028"), "a real leap day")
        assertNull(parseDayInput("29/02/2027"))
    }

    @Test fun aDayIsShownTheWayItIsTyped() {
        assertEquals("10/11/2026", formatDayInput(nov10))
        assertEquals("05/01/2026", formatDayInput(LocalDate.of(2026, 1, 5)))
        assertEquals(nov10, parseDayInput(formatDayInput(nov10)))
    }

    @Test fun aBlankTimeMeansAllDay() = assertEquals(-1, parseTimeOfDay("   "))

    @Test fun aTimeIsMinutesAfterMidnight() {
        assertEquals(870, parseTimeOfDay("14:30"))
        assertEquals(545, parseTimeOfDay("9:05"))
        assertEquals(0, parseTimeOfDay("00:00"))
        assertEquals(1439, parseTimeOfDay("23:59"))
        assertEquals(870, parseTimeOfDay(" 14.30 "))
    }

    @Test fun anythingThatIsNotATimeIsRejected() {
        listOf("24:00", "12:60", "1430", "noon", "14:3", "-1:00", "14:30pm").forEach { assertNull(parseTimeOfDay(it), it) }
    }

    @Test fun anEventOnTheUpcomingListKnowsItsRepeatOrThatItHasNone() {
        assertEquals(RepeatState(true, Repeat(14, ChronoUnit.DAYS)), eventRepeatState("Every 14 days", onUpcomingList = true))
        assertEquals(RepeatState(true, null), eventRepeatState(null, onUpcomingList = true), "on the list with no label: a one-off")
    }

    @Test fun anEventNotOnTheUpcomingListOrWithALabelItCannotReadHasAnUnknownRepeat() {
        assertEquals(false, eventRepeatState(null, onUpcomingList = false).known, "a completed one-off or a paused repeat: no label to go by")
        assertEquals(false, eventRepeatState("Every week", onUpcomingList = true).known, "events repeat in days")
        assertEquals(false, eventRepeatState("Tuesday", onUpcomingList = true).known)
    }

    @Test fun aTaskKnowsItsRepeatFromItsLabel() {
        assertEquals(RepeatState(true, Repeat(1, ChronoUnit.WEEKS)), taskRepeatState("Every week"))
        assertEquals(RepeatState(true, Repeat(2, ChronoUnit.WEEKS)), taskRepeatState("Every 2 weeks"))
        assertEquals(RepeatState(true, Repeat(3, ChronoUnit.MONTHS)), taskRepeatState("Every 3 months"))
        assertEquals(RepeatState(true, null), taskRepeatState("One-time"))
    }

    @Test fun aTaskPinnedToAWeekdayOrWithNoLabelHasAnUnknownRepeat() {
        assertEquals(false, taskRepeatState("Tuesday").known, "the weekday name leaves out how many weeks apart")
        assertEquals(false, taskRepeatState("Weekly").known)
        assertEquals(false, taskRepeatState(null).known)
    }
}
