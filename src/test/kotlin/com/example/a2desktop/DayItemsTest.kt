package com.example.a2desktop

import com.example.a2desktop.data.CalendarEmojiStore
import com.example.a2desktop.data.DayEmoji
import com.example.a2desktop.data.DayItem
import com.example.a2desktop.model.CalendarEvent
import com.example.a2desktop.model.UpcomingItem
import com.example.a2desktop.model.UpcomingKind
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the Selected day card lists for a day (and the month grid draws its emoji from): the phone's events and Upcoming list,
 * repeating ones on the dates they fall on — shown here with the phone's real shape (the snapshot of 2 Nov 2026).
 */
class DayItemsTest {
    // Today is Friday 6 November 2026.
    private val today = LocalDate.of(2026, 11, 6)
    private fun nov(day: Int) = LocalDate.of(2026, 11, day)
    private val oct12 = LocalDate.of(2026, 10, 12)
    private val oct17 = LocalDate.of(2026, 10, 17)

    // The three upcoming lines the phone had: two weekly tasks, long overdue, and a haircut every 14 days.
    private val waterChange = UpcomingItem("task-2", "10% Water Change", oct12, UpcomingKind.TASK, "Every week")
    private val bedCover = UpcomingItem("task-4", "Change Bed Cover", oct17, UpcomingKind.TASK, "Every week")
    private val haircutUpcoming = UpcomingItem("event-38", "Haircut", nov(10), UpcomingKind.EVENT, "Every 14 days")
    private val upcoming = listOf(waterChange, bedCover, haircutUpcoming)

    private val haircut = CalendarEvent(38, nov(10), "Haircut", null)
    private val fishEvent = CalendarEvent(18, nov(2), "👽", "09:00", completed = true)
    private val events = listOf(haircut, fishEvent)

    private fun items(store: CalendarEmojiStore, date: LocalDate) = store.itemsOn(date, events, upcoming, today)
    private fun titles(store: CalendarEmojiStore, date: LocalDate) = items(store, date).map { it.title }

    @Test fun anEventOnItsDateIsListedWithItsKindTimeAndRepeat() {
        val store = CalendarEmojiStore(null)
        assertEquals(
            listOf(DayItem("Haircut", UpcomingKind.EVENT, null, "Every 14 days", done = false, doneOn = null, projected = false, ref = "event-38")),
            items(store, nov(10)),
        )
        assertEquals(
            listOf(DayItem("👽", UpcomingKind.EVENT, "09:00", null, done = true, doneOn = null, projected = false, ref = "event-18")),
            items(store, nov(2)),
            "a completed event on the phone is done, with its time",
        )
    }

    @Test fun aTaskIsListedOnItsDueDate() {
        val store = CalendarEmojiStore(null)
        assertEquals(
            listOf(DayItem("10% Water Change", UpcomingKind.TASK, null, "Every week", done = false, doneOn = null, projected = false, ref = "task-2")),
            items(store, oct12),
        )
        assertEquals(listOf("Change Bed Cover"), titles(store, oct17))
    }

    @Test fun eachItemCarriesThePhonesIdForItSoItCanBeEditedOrDeleted() {
        val store = CalendarEmojiStore(null)
        assertEquals(listOf("event-38"), items(store, nov(10)).map { it.ref }, "an event on its date")
        assertEquals(listOf("task-2"), items(store, oct12).map { it.ref }, "a task on its due date")
        assertEquals(listOf("event-18"), items(store, nov(2)).map { it.ref }, "a completed event of the phone's list")
        assertEquals(listOf("task-2"), items(store, nov(9)).map { it.ref }, "a repeat to come is the same task")
        assertEquals(listOf("event-38"), items(store, nov(24)).map { it.ref })
        store.complete("Haircut", nov(10), on = nov(4))
        assertEquals(listOf<String?>(null), items(store, nov(4)).map { it.ref }, "something known only from a done tick has no id on the phone")
    }

    @Test fun aDayWithNothingOnItHasNoItems() {
        assertTrue(items(CalendarEmojiStore(null), nov(5)).isEmpty())
    }

    @Test fun eachDaysListIsItsOwnAndNothingCarriesOverFromAnotherDay() {
        val store = CalendarEmojiStore(null)
        assertEquals(listOf("👽"), titles(store, nov(2)))
        assertTrue(titles(store, nov(3)).isEmpty())
        assertEquals(listOf("Haircut"), titles(store, nov(10)))
        assertEquals(listOf("👽"), titles(store, nov(2)), "and back again")
    }

    @Test fun aRepeatingTaskAppearsOnTheDatesItFallsOnAfterToday() {
        val store = CalendarEmojiStore(null)
        // 12 Oct + 7 days: 19, 26 Oct, 2 Nov (already past), then Monday 9 Nov, 16 Nov, 23 Nov…
        assertEquals(listOf(DayItem("10% Water Change", UpcomingKind.TASK, null, "Every week", done = false, doneOn = null, projected = true, ref = "task-2")), items(store, nov(9)))
        assertEquals(listOf("10% Water Change"), titles(store, nov(16)))
        assertEquals(listOf("10% Water Change"), titles(store, nov(23)))
        assertEquals(listOf("Change Bed Cover"), titles(store, nov(7)), "the bed cover is on Saturdays: 17 Oct + 3 weeks")
        assertTrue(titles(store, nov(8)).isEmpty(), "not on the days between")
    }

    @Test fun weeksMissedBetweenTheDueDateAndTodayAreNotAddedAsNewOnes() {
        val store = CalendarEmojiStore(null)
        assertEquals(listOf("10% Water Change"), titles(store, oct12), "the overdue one stays on its due date")
        assertTrue(titles(store, LocalDate.of(2026, 10, 19)).isEmpty())
        assertTrue(titles(store, nov(2)).none { it.contains("Water") }, "2 Nov was a Monday, already past")
    }

    @Test fun aRepeatingEventAppearsOnItsLaterDatesToo() {
        val store = CalendarEmojiStore(null)
        assertEquals(listOf(DayItem("Haircut", UpcomingKind.EVENT, null, "Every 14 days", done = false, doneOn = null, projected = true, ref = "event-38")), items(store, nov(24)))
        assertEquals(listOf("Haircut"), titles(store, LocalDate.of(2026, 12, 8)))
        assertTrue(titles(store, nov(17)).isEmpty(), "a week after is not a fortnight after")
    }

    @Test fun aTaskPinnedToAWeekdayHasNoRepeatsWorkedOutBecauseThePhoneDoesNotSayHowManyWeeks() {
        val tuesdays = UpcomingItem("task-9", "Bins", nov(10), UpcomingKind.TASK, "Tuesday")
        val list = CalendarEmojiStore(null).itemsOn(nov(17), emptyList(), listOf(tuesdays), today)
        assertTrue(list.isEmpty())
        assertEquals(listOf("Bins"), CalendarEmojiStore(null).itemsOn(nov(10), emptyList(), listOf(tuesdays), today).map { it.title }, "its own date still shows")
    }

    @Test fun aOneTimeTaskIsNotRepeated() {
        val once = UpcomingItem("task-9", "Renew passport", nov(10), UpcomingKind.TASK, "One-time")
        assertTrue(CalendarEmojiStore(null).itemsOn(nov(17), emptyList(), listOf(once), today).isEmpty())
    }

    @Test fun somethingTickedOffHereMovesToTheDayItWasDoneAndMarksItDone() {
        val store = CalendarEmojiStore(null)
        store.complete("Haircut", nov(10), on = nov(4)) // the haircut had on Wednesday 4 November
        assertTrue(titles(store, nov(10)).isEmpty(), "gone from the day it was due")
        assertEquals(
            listOf(DayItem("Haircut", UpcomingKind.EVENT, null, "Every 14 days", done = true, doneOn = nov(4), projected = false)),
            items(store, nov(4)),
            "on the 4th, done, and still known to repeat",
        )
        // The haircut had on the 4th, not the 10th it was due: the next is a fortnight after the day it was really done.
        assertEquals(listOf("Haircut"), titles(store, nov(18)), "the fortnight after the 4th is still to come")
        assertTrue(titles(store, nov(24)).isEmpty(), "and the 24th, a fortnight after the 10th it was due, is not where it falls any more")
    }

    private val bedEvery6 = UpcomingItem("task-4", "Change Bed Cover", oct17, UpcomingKind.TASK, "Every 6 days")

    /** The days from [from] to [to] (November) that [store] shows [bedEvery6] on as a repeat still to come, when today is the 9th. */
    private fun shadowDays(store: CalendarEmojiStore, from: Int = 10, to: Int = 30): List<Int> =
        (from..to).filter { day -> store.itemsOn(nov(day), emptyList(), listOf(bedEvery6), nov(9)).any { it.projected } }

    @Test fun onceTickedOffTheNextOnesCountFromTheDayItWasDoneNotFromTheDateTheyWereDue() {
        val store = CalendarEmojiStore(null)
        // Due on 17 October, every 6 days: 23 Oct, 29 Oct, 4 Nov, 10 Nov, 16 Nov, 22 Nov, 28 Nov.
        assertEquals(listOf(10, 16, 22, 28), shadowDays(store), "counted from the phone's date until it is ticked")
        store.complete("Change Bed Cover", oct17, on = nov(8)) // done on the 8th
        // From the 8th: 14 Nov, 20 Nov, 26 Nov: the shadow has left the 10th.
        assertEquals(listOf(14, 20, 26), shadowDays(store), "counted from the 8th it was done")
        // Taking the tick back puts the old count back.
        store.undo("Change Bed Cover", oct17)
        assertEquals(listOf(10, 16, 22, 28), shadowDays(store))
    }

    @Test fun somethingTickedOffEarlyIsNextDueThatManyDaysAfterTheDayItWasReallyDone() {
        val store = CalendarEmojiStore(null)
        val weekly = UpcomingItem("task-5", "Water plants", nov(12), UpcomingKind.TASK, "Every week")
        store.complete("Water plants", nov(12), on = nov(9)) // due on the 12th, done on the 9th
        val shown = (10..30).filter { day -> store.itemsOn(nov(day), emptyList(), listOf(weekly), nov(9)).any { it.projected } }
        assertEquals(listOf(16, 23, 30), shown, "a week after the 9th, not after the 12th (19, 26)")
    }

    @Test fun aTickOnAnotherOccurrenceDoesNotMoveTheCount() {
        val store = CalendarEmojiStore(null)
        store.complete("Change Bed Cover", oct17.minusDays(6), on = nov(8)) // a tick for the occurrence before the one now due
        assertEquals(listOf(10, 16, 22, 28), shadowDays(store), "only a tick on the occurrence due now moves it")
    }

    @Test fun theCountForOneThingDoesNotTouchAnother() {
        val store = CalendarEmojiStore(null)
        store.complete("Change Bed Cover", oct17, on = nov(8))
        val others = store.itemsOn(nov(16), emptyList(), listOf(waterChange), today).filter { it.projected }.map { it.title }
        assertEquals(listOf("10% Water Change"), others, "the weekly water change, not ticked, is still counted from its own date (every 7 days from 12 Oct)")
        assertEquals(oct17, store.countsFrom(waterChange.copy(title = "Something else", due = oct17)), "a thing with nothing ticked counts from its due date")
        assertEquals(nov(8), store.countsFrom(bedEvery6), "and one that was ticked, from the day it was done")
    }

    @Test fun aTaskTickedOffEarlyMovesToItsDoneDayToo() {
        val store = CalendarEmojiStore(null)
        store.complete("10% Water Change", oct12, on = nov(4))
        assertTrue(titles(store, oct12).isEmpty(), "gone from the day it was due")
        assertEquals(
            listOf(DayItem("10% Water Change", UpcomingKind.TASK, null, "Every week", done = true, doneOn = nov(4), projected = false)),
            items(store, nov(4)),
        )
    }

    @Test fun somethingDoneWithNoDueDateIsListedOnItsDayAsDone() {
        val store = CalendarEmojiStore(null)
        store.complete("Dentist", null, on = nov(3))
        assertEquals(listOf(DayItem("Dentist", null, null, null, done = true, doneOn = nov(3), projected = false, repeatable = true)), items(store, nov(3)), "a tick with nothing behind it can be made to repeat")
    }

    @Test fun timedEventsComeFirstInTimeOrderThenTheAllDayOnesThenTasks() {
        val store = CalendarEmojiStore(null)
        val day = nov(12)
        val list = store.itemsOn(
            day,
            listOf(CalendarEvent(1, day, "All day thing", null), CalendarEvent(2, day, "Late", "18:30"), CalendarEvent(3, day, "Early", "08:15")),
            listOf(UpcomingItem("task-1", "A task", day, UpcomingKind.TASK)),
            today,
        )
        assertEquals(listOf("Early", "Late", "All day thing", "A task"), list.map { it.title })
        assertEquals(listOf("08:15", "18:30", null, null), list.map { it.time })
    }

    @Test fun theMonthGridAndTheCardAgreeBecauseTheEmojiAreDrawnFromTheSameList() {
        val store = CalendarEmojiStore(null).also { it.setEmoji("10% Water Change", "💧"); it.setEmoji("Haircut", "✂️") }
        val list = items(store, nov(9))
        assertEquals(listOf(DayEmoji("💧", done = false)), store.emojiMarks(list), "a day to come is shaded")
        store.complete("Haircut", nov(10), on = nov(4))
        assertEquals(listOf(DayEmoji("✂️", done = true)), store.emojiMarks(items(store, nov(4))))
        assertEquals(store.emojiMarks(items(store, nov(18))), store.emojisOn(nov(18), events, upcoming, today))
        assertEquals(listOf(DayEmoji("✂️", done = false)), store.emojiMarks(items(store, nov(18))), "a fortnight after the 4th, shaded")
    }

    @Test fun anEventWithoutAnEmojiOnALaterDateKeepsTheSquareMarker() {
        val store = CalendarEmojiStore(null)
        assertTrue(store.hasPlainEvent(items(store, nov(24))), "the repeating haircut has no emoji yet")
        store.setEmoji("Haircut", "✂️")
        assertTrue(!store.hasPlainEvent(items(store, nov(24))))
    }
}
