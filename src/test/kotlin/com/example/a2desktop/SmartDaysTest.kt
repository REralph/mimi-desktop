package com.example.a2desktop

import com.example.a2desktop.data.CalendarEmojiStore
import com.example.a2desktop.logic.DayPick
import com.example.a2desktop.logic.Repeat
import com.example.a2desktop.logic.WorkSchedule
import com.example.a2desktop.logic.WorkShift
import com.example.a2desktop.logic.isSmartProjected
import com.example.a2desktop.logic.occurrenceOnOrBefore
import com.example.a2desktop.logic.shownDue
import com.example.a2desktop.logic.smartRepeat
import com.example.a2desktop.logic.snapDay
import com.example.a2desktop.logic.windowDays
import com.example.a2desktop.model.CalendarEvent
import com.example.a2desktop.model.UpcomingItem
import com.example.a2desktop.model.UpcomingKind
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Smart days: which day of its period a repeating task lands on, worked out from the user's rota. The rota used here is four days on and
 * three off from Monday 2 November 2026 (the rota is known up to the 30th): work 2–5, off 6–8, work 9–12, off 13–15, work 16–19, off 20–22,
 * work 23–26, off 27–29, work the 30th. The first two days of each stretch start early (08:00), the last two late (12:30).
 */
class SmartDaysTest {
    private fun nov(day: Int) = LocalDate.of(2026, 11, day)

    private val schedule: WorkSchedule = run {
        val shifts = mutableMapOf<LocalDate, List<WorkShift>>()
        val covered = mutableSetOf<LocalDate>()
        for (n in 0..28) {
            val date = nov(2).plusDays(n.toLong())
            covered += date
            if (n % 7 < 4) {
                val early = n % 7 < 2
                shifts[date] = listOf(WorkShift(date, if (early) LocalTime.of(8, 0) else LocalTime.of(12, 30), LocalTime.of(20, 0), false))
            }
        }
        WorkSchedule(shifts, covered)
    }

    private fun days(pick: DayPick) = (1..30).filter { pick.matches(nov(it), schedule) }

    @Test fun eachKindOfDayIsRecognisedInTheRota() {
        assertEquals(listOf(6, 7, 8, 13, 14, 15, 20, 21, 22, 27, 28, 29), days(DayPick.DAY_OFF))
        assertEquals(listOf(6, 13, 20, 27), days(DayPick.FIRST_DAY_OFF), "the first off day after working")
    }

    @Test fun theLastDayOffIsTheOneBeforeAShift() {
        // 8th (work on the 9th), 15th (16th), 22nd (23rd), 29th (30th is a work day): all four.
        assertEquals(listOf(8, 15, 22, 29), days(DayPick.LAST_DAY_OFF))
    }

    @Test fun workAndShiftKinds() {
        assertEquals(listOf(5, 12, 19, 26), days(DayPick.LAST_WORK_DAY), "my final shift before days off")
        assertEquals(listOf(9, 16, 23, 30), days(DayPick.FIRST_WORK_DAY), "the first shift after days off")
        assertEquals(listOf(2, 3, 4, 5, 9, 10, 11, 12, 16, 17, 18, 19, 23, 24, 25, 26, 30), days(DayPick.WORK_DAY))
        assertEquals(listOf(2, 3, 9, 10, 16, 17, 23, 24, 30), days(DayPick.EARLY_START), "shift starts before 12:00")
        assertEquals(listOf(4, 5, 11, 12, 18, 19, 25, 26), days(DayPick.LATE_START), "shift starts at 12:00 or later")
    }

    @Test fun theDayBeforeWorkIsAnyDayWithAShiftTomorrow() {
        // The first three days of each stretch (work tomorrow) and the last day off before the next one.
        assertEquals(listOf(2, 3, 4, 8, 9, 10, 11, 15, 16, 17, 18, 22, 23, 24, 25, 29), days(DayPick.DAY_BEFORE_WORK))
    }

    @Test fun aDayTheRotaDoesNotCoverIsNeitherWorkNorOff() {
        val unknown = nov(30).plusDays(5) // 5 December: nobody has read the rota that far
        DayPick.entries.filter { it != DayPick.ANY }.forEach { assertFalse(it.matches(unknown, schedule), "$it must not guess for a day beyond the rota") }
        assertFalse(DayPick.DAY_OFF.matches(nov(1), schedule), "1 November is before the rota too")
        // And the day after the last known day does not count as 'off' for the last work day.
        assertFalse(DayPick.LAST_WORK_DAY.matches(nov(30), schedule), "the 30th works, but whether the 31st is off isn't known")
        assertFalse(DayPick.FIRST_DAY_OFF.matches(LocalDate.of(2026, 12, 1), schedule))
    }

    @Test fun noRotaAtAllMeansNothingMatchesAndEverythingStaysPut() {
        val none = WorkSchedule.EMPTY
        DayPick.entries.filter { it != DayPick.ANY }.forEach { assertTrue((1..30).none { d -> it.matches(nov(d), none) }, "$it with no rota") }
        assertEquals(nov(10), snapDay(DayPick.DAY_OFF, nov(10), 7, nov(1), none).date)
        assertFalse(snapDay(DayPick.DAY_OFF, nov(10), 7, nov(1), none).matched)
    }

    @Test fun anOccurrenceMovesToTheFirstDayOfThatKindFromItsDateOn() {
        // Due Monday 9th (a work day), a week to move in: the first day off from then is Friday 13th.
        assertEquals(nov(13), snapDay(DayPick.DAY_OFF, nov(9), 7, nov(1), schedule).date)
        // Already such a day: it stays.
        assertEquals(nov(13), snapDay(DayPick.DAY_OFF, nov(13), 7, nov(1), schedule).date)
        // Never early: from the 14th the first first-day-off is the 20th, six days on.
        assertEquals(nov(20), snapDay(DayPick.FIRST_DAY_OFF, nov(14), 7, nov(1), schedule).date)
        // The plain date is no rule at all.
        assertEquals(nov(9), snapDay(DayPick.ANY, nov(9), 7, nov(1), schedule).date)
    }

    @Test fun anOccurrenceNeverMovesPastTheRoomItHas() {
        // Due the 2nd with only two days to move in (both work days): no day off there, so it stays, and says it found none.
        val result = snapDay(DayPick.DAY_OFF, nov(2), 2, nov(1), schedule)
        assertEquals(nov(2), result.date)
        assertFalse(result.matched)
        // With a week it finds the 6th.
        val found = snapDay(DayPick.DAY_OFF, nov(2), 7, nov(1), schedule)
        assertEquals(nov(6), found.date)
        assertTrue(found.matched)
    }

    @Test fun anOccurrenceAlreadyLateIsLookedForFromTodayNotInThePast() {
        // Due the 2nd with two weeks to move in; today is the 7th (a day off): "a work day" is the 9th, not the 2nd or 3rd that have gone.
        assertEquals(nov(9), snapDay(DayPick.WORK_DAY, nov(2), 14, nov(7), schedule).date)
        // Today is inside the window and is itself such a day: it is today.
        assertEquals(nov(7), snapDay(DayPick.DAY_OFF, nov(2), 14, nov(7), schedule).date)
        // The whole window is in the past: it stays on its old date (an overdue one stays where it was due), and isn't "unmatched".
        val gone = snapDay(DayPick.DAY_OFF, nov(2), 7, nov(20), schedule)
        assertEquals(nov(2), gone.date)
        assertTrue(gone.matched)
    }

    @Test fun onlyARepeatOfAWeekOrMoreCanHaveASmartDay() {
        assertEquals(Repeat(2, ChronoUnit.WEEKS), smartRepeat("Every 2 weeks"))
        assertEquals(Repeat(1, ChronoUnit.MONTHS), smartRepeat("Every month"))
        assertEquals(Repeat(7, ChronoUnit.DAYS), smartRepeat("Every 7 days"))
        assertNull(smartRepeat("Every 3 days"), "too short: there is no room to move it")
        assertNull(smartRepeat("Every day"))
        assertNull(smartRepeat("One-time"))
        assertNull(smartRepeat(null))
        assertEquals(Repeat(1, ChronoUnit.WEEKS), smartRepeat("Weekly"), "a repeat the PC can't read is taken as a week")
        assertEquals(Repeat(1, ChronoUnit.WEEKS), smartRepeat("Tuesday"))
    }

    @Test fun theRoomAnOccurrenceHasIsTheGapToTheNextOne() {
        assertEquals(7, windowDays(nov(2), Repeat(1, ChronoUnit.WEEKS)))
        assertEquals(14, windowDays(nov(2), Repeat(2, ChronoUnit.WEEKS)))
        assertEquals(30, windowDays(nov(2), Repeat(1, ChronoUnit.MONTHS)), "2 November to 2 December")
        assertEquals(28, windowDays(LocalDate.of(2027, 2, 1), Repeat(1, ChronoUnit.MONTHS)))
    }

    @Test fun theStartOfThePeriodADateIsIn() {
        val weekly = Repeat(1, ChronoUnit.WEEKS)
        assertNull(occurrenceOnOrBefore(nov(2), weekly, nov(2)), "the first one is the item's own date, not a repeat")
        assertNull(occurrenceOnOrBefore(nov(2), weekly, nov(8)), "still in the first period")
        assertEquals(nov(9), occurrenceOnOrBefore(nov(2), weekly, nov(9)))
        assertEquals(nov(16), occurrenceOnOrBefore(nov(2), weekly, nov(20)))
        assertEquals(nov(16), occurrenceOnOrBefore(nov(2), Repeat(2, ChronoUnit.WEEKS), nov(29)))
        // Months step one at a time from the previous date, as the phone does: 31 January, then 28 February, then 28 March.
        val monthly = Repeat(1, ChronoUnit.MONTHS)
        assertEquals(LocalDate.of(2027, 2, 28), occurrenceOnOrBefore(LocalDate.of(2027, 1, 31), monthly, LocalDate.of(2027, 3, 5)))
        assertEquals(LocalDate.of(2027, 3, 28), occurrenceOnOrBefore(LocalDate.of(2027, 1, 31), monthly, LocalDate.of(2027, 3, 28)))
    }

    @Test fun laterOccurrencesLandOnTheDayOfThatKindInTheirOwnPeriod() {
        val weekly = Repeat(1, ChronoUnit.WEEKS)
        // Due Monday 2nd; today is the 3rd. The periods after it start on the 9th, 16th, 23rd: the first day off in each is the 13th, 20th, 27th.
        val projected = (4..30).filter { isSmartProjected(DayPick.DAY_OFF, nov(2), weekly, nov(3), nov(it), schedule) }
        // (The period starting on the 30th can't be worked out — the rota ends that day — so it stays on its plain date.)
        assertEquals(listOf(13, 20, 27, 30), projected)
        // A period that started before today is not a new occurrence (an overdue one stays where it is): from the 9th with today the 10th.
        assertFalse(isSmartProjected(DayPick.DAY_OFF, nov(2), weekly, nov(10), nov(13), schedule))
        assertTrue(isSmartProjected(DayPick.DAY_OFF, nov(2), weekly, nov(10), nov(20), schedule))
        // The day itself is the item's own business, and a repeat too short for a smart day never projects.
        assertFalse(isSmartProjected(DayPick.DAY_OFF, nov(2), weekly, nov(3), nov(2), schedule))
        assertFalse(isSmartProjected(DayPick.DAY_OFF, nov(2), Repeat(3, ChronoUnit.DAYS), nov(3), nov(8), schedule))
    }

    @Test fun shownDueIgnoresAOneOffOrAShortRepeat() {
        assertEquals(nov(13), shownDue(DayPick.DAY_OFF, nov(9), "Every week", nov(1), schedule).date)
        assertEquals(nov(9), shownDue(DayPick.DAY_OFF, nov(9), "One-time", nov(1), schedule).date)
        assertEquals(nov(9), shownDue(DayPick.DAY_OFF, nov(9), "Every 2 days", nov(1), schedule).date)
        assertEquals(nov(9), shownDue(DayPick.DAY_OFF, nov(9), null, nov(1), schedule).date)
        assertEquals(nov(9), shownDue(DayPick.ANY, nov(9), "Every week", nov(1), schedule).date)
    }

    @Test fun aSavedNameIsReadBackAndAnUnknownOneIsThePlainDate() {
        DayPick.entries.forEach { assertEquals(it, DayPick.fromName(it.name)) }
        assertEquals(DayPick.ANY, DayPick.fromName("SOMETHING_NEW"))
        assertEquals(DayPick.ANY, DayPick.fromName(null))
    }

    // ---- kept per title, and what the Calendar lists ----

    private val bins = UpcomingItem("task-7", "Bins out", nov(9), UpcomingKind.TASK, "Every week")
    private val today = nov(3)

    private fun titles(store: CalendarEmojiStore, day: Int) = store.itemsOn(nov(day), emptyList(), listOf(bins), today, schedule).map { it.title }

    @Test fun withNoSmartDayATaskFallsOnItsPlainDates() {
        val store = CalendarEmojiStore(null)
        assertEquals(listOf("Bins out"), titles(store, 9))
        assertEquals(listOf("Bins out"), titles(store, 16))
        assertTrue(titles(store, 13).isEmpty())
    }

    @Test fun withASmartDayATaskIsListedOnTheDayItMovesToAndNotOnItsPlainDate() {
        val store = CalendarEmojiStore(null)
        store.setDayPick("Bins out", DayPick.DAY_OFF)
        assertTrue(titles(store, 9).isEmpty(), "not on the plain date, a work day")
        assertEquals(listOf("Bins out"), titles(store, 13), "its own date moved to the first day off")
        val later = store.itemsOn(nov(20), emptyList(), listOf(bins), today, schedule)
        assertEquals(listOf(true), later.map { it.projected }, "and the next one lands on a day off in its period, as a repeat to come")
        assertTrue(titles(store, 16).isEmpty(), "the plain step is not used either")
        assertEquals(listOf("Bins out"), titles(store, 27))
    }

    @Test fun theDoneTickStaysWithThePhonesOwnDateWhateverDayItIsShownOn() {
        val store = CalendarEmojiStore(null)
        store.setDayPick("Bins out", DayPick.DAY_OFF)
        store.complete("Bins out", nov(9), on = nov(8)) // ticked off as the occurrence due on the 9th, done on the 8th
        val on8 = store.itemsOn(nov(8), emptyList(), listOf(bins), today, schedule)
        assertEquals(listOf("Bins out"), on8.map { it.title })
        assertTrue(on8.single().done, "shown on the day it was done")
        assertTrue(store.itemsOn(nov(13), emptyList(), listOf(bins), today, schedule).isEmpty(), "and no longer waiting on its moved day")
    }

    // ---- events: one that repeats a week or more gets a smart day exactly as a task does ----

    private val haircut = UpcomingItem("event-3", "Haircut", nov(9), UpcomingKind.EVENT, "Every 14 days")
    private val haircutOnPhone = CalendarEvent(3, nov(9), "Haircut", "14:30")

    private fun haircutDays(store: CalendarEmojiStore, day: Int) = store.itemsOn(nov(day), listOf(haircutOnPhone), listOf(haircut), today, schedule)

    @Test fun aRepeatingEventMovesToItsSmartDayJustLikeATask() {
        val store = CalendarEmojiStore(null)
        assertEquals(nov(9), store.shownDue(haircut, today, schedule).date, "no choice: its own date")
        store.setDayPick("Haircut", DayPick.DAY_OFF)
        val shown = store.shownDue(haircut, today, schedule)
        assertEquals(nov(13), shown.date, "the first day off from the 9th (a work day)")
        assertTrue(shown.matched)
        assertEquals(nov(9), haircut.due, "the phone's own date is untouched")
        assertEquals(nov(13), store.shownDue(haircut.copy(repeat = "Every week"), today, schedule).date, "an event repeating every week moves too")
    }

    @Test fun anEventThatDoesNotRepeatAWeekOrMoreStaysOnItsDate() {
        val store = CalendarEmojiStore(null).also { it.setDayPick("Haircut", DayPick.DAY_OFF) }
        assertEquals(nov(9), store.shownDue(haircut.copy(repeat = null), today, schedule).date, "a one-off")
        assertEquals(nov(9), store.shownDue(haircut.copy(repeat = "Every 3 days"), today, schedule).date, "too short to move anywhere")
    }

    @Test fun aSmartEventIsListedOnTheDayItMovesToWithItsTimeAndItsPhoneId() {
        val store = CalendarEmojiStore(null).also { it.setDayPick("Haircut", DayPick.DAY_OFF) }
        assertTrue(haircutDays(store, 9).isEmpty(), "not on the date the phone has, a work day")
        val moved = haircutDays(store, 13).single()
        assertEquals("Haircut", moved.title)
        assertEquals("14:30", moved.time, "its time goes with it")
        assertEquals("event-3", moved.ref, "so the pen still edits the phone's event")
        assertEquals("Every 14 days", moved.repeat)
        assertFalse(moved.projected)
        // Without a choice nothing changes: it is on the 9th and not the 13th.
        val plain = CalendarEmojiStore(null)
        assertEquals(listOf("Haircut"), haircutDays(plain, 9).map { it.title })
        assertTrue(haircutDays(plain, 13).isEmpty())
    }

    @Test fun theNextOnesOfASmartEventLandOnADayOffInTheirOwnPeriod() {
        val store = CalendarEmojiStore(null).also { it.setDayPick("Haircut", DayPick.DAY_OFF) }
        // The periods after the one due on the 9th start on the 23rd: its first day off is the 27th.
        val later = haircutDays(store, 27).single()
        assertTrue(later.projected, "a repeat to come, not a date the phone holds")
        assertEquals("Haircut", later.title)
        assertTrue(haircutDays(store, 23).isEmpty(), "not on the plain step either")
    }

    @Test fun theSmartDayCountsFromTheDayItWasDoneToo() {
        val store = CalendarEmojiStore(null).also { it.setDayPick("Haircut", DayPick.DAY_OFF) }
        store.complete("Haircut", nov(9), on = nov(3)) // due on the 9th, done today, the 3rd
        // Counting from the 3rd the next period starts on the 17th; its first day off is the 20th (the 17th to 19th are work days).
        assertTrue(haircutDays(store, 20).single().projected, "the 20th")
        assertTrue(haircutDays(store, 27).isEmpty(), "not the 27th it would have been counting from the 9th")
        assertTrue(haircutDays(store, 17).isEmpty())
    }

    @Test fun anEventTickedOffIsStillTiedToThePhonesDateWhereverItIsShown() {
        val store = CalendarEmojiStore(null).also { it.setDayPick("Haircut", DayPick.DAY_OFF) }
        store.complete("Haircut", nov(9), on = nov(8))
        assertTrue(haircutDays(store, 13).isEmpty(), "the occurrence due on the 9th is done, so it is no longer waiting on its moved day")
        assertTrue(haircutDays(store, 8).single().done, "and is shown as done on the day it was done")
    }

    // ---- a tick kept only here can be turned into something that repeats ----

    @Test fun aTickWithNothingOnThePhonesListBehindItCanBeMadeToRepeatAndOneWithSomethingCannot() {
        val store = CalendarEmojiStore(null)
        store.complete("Haircut", null, on = nov(4))
        val alone = store.itemsOn(nov(4), emptyList(), emptyList(), today, schedule).single()
        assertTrue(alone.done && alone.ref == null && alone.repeatable, "just a tick on this PC: it can be made to repeat")
        val backed = store.itemsOn(nov(4), emptyList(), listOf(haircut), today, schedule).single()
        assertFalse(backed.repeatable, "the phone already has a Haircut, which repeats on its own")
        assertFalse(haircutDays(CalendarEmojiStore(null), 9).any { it.repeatable }, "a real event on the phone is edited with its own pen, not made to repeat")
    }

    @Test fun theChoiceIsKeptPerTitleWhateverTheCapitalsAndCanBeTakenBack() {
        val store = CalendarEmojiStore(null)
        assertEquals(DayPick.ANY, store.dayPickFor("Bins out"))
        store.setDayPick("Bins out", DayPick.LAST_WORK_DAY)
        assertEquals(DayPick.LAST_WORK_DAY, store.dayPickFor("  BINS  out"))
        store.setDayPick("bins out", DayPick.ANY)
        assertEquals(DayPick.ANY, store.dayPickFor("Bins out"))
        assertTrue(store.days.isEmpty(), "the plain date is not stored at all")
        store.setDayPick("", DayPick.DAY_OFF)
        assertTrue(store.days.isEmpty(), "no title, no choice")
    }

    @Test fun renamingCarriesTheChoiceAlong() {
        val store = CalendarEmojiStore(null)
        store.setDayPick("Bins out", DayPick.FIRST_DAY_OFF)
        store.renameDayPick("Bins out", "Bins and recycling")
        assertEquals(DayPick.FIRST_DAY_OFF, store.dayPickFor("Bins and recycling"))
        assertEquals(DayPick.ANY, store.dayPickFor("Bins out"))
        store.renameDayPick("Nothing", "Something") // no choice to carry
        assertEquals(DayPick.ANY, store.dayPickFor("Something"))
    }

    @Test fun theChoiceIsSavedAndAnOldFileWithoutOneStillLoads() {
        val dir = Files.createTempDirectory("a2-smart").toFile()
        try {
            val file = File(dir, "calendar-emojis.json")
            CalendarEmojiStore(file).also {
                it.setEmoji("Bins out", "🗑️")
                it.setDayPick("Bins out", DayPick.DAY_BEFORE_WORK)
            }
            val again = CalendarEmojiStore(file)
            assertEquals(DayPick.DAY_BEFORE_WORK, again.dayPickFor("Bins out"))
            assertEquals("🗑️", again.emojiFor("Bins out"), "the emoji is still there beside it")
            // A file written before smart days existed has no "days" at all.
            file.writeText("""{"items":[{"title":"Bins out","emoji":"🗑️"}],"uses":{"🗑️":1},"done":[]}""")
            val old = CalendarEmojiStore(file)
            assertEquals("🗑️", old.emojiFor("Bins out"))
            assertEquals(DayPick.ANY, old.dayPickFor("Bins out"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun theUpcomingListShowsATaskOnItsSmartDay() {
        val store = CalendarEmojiStore(null)
        assertEquals(nov(9), store.shownDue(bins, today, schedule).date)
        store.setDayPick("Bins out", DayPick.DAY_OFF)
        assertEquals(nov(13), store.shownDue(bins, today, schedule).date)
        assertTrue(store.shownDue(bins, today, schedule).matched)
        assertEquals(bins.due, nov(9), "the phone's own date is untouched")
    }
}
