package com.example.a2desktop

import com.example.a2desktop.data.CalendarEmojiStore
import com.example.a2desktop.data.DayEmoji
import com.example.a2desktop.model.CalendarEvent
import com.example.a2desktop.model.UpcomingItem
import com.example.a2desktop.model.UpcomingKind
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The emoji kept for the Calendar's events and tasks, what is ticked off as done, and what a day then shows. */
class CalendarEmojiStoreTest {
    private val dir: File = Files.createTempDirectory("a2-emoji").toFile()
    private val file = File(dir, "calendar-emojis.json")

    @AfterTest fun cleanUp() {
        dir.deleteRecursively()
    }

    private val nov4 = LocalDate.of(2026, 11, 4)
    private val nov10 = LocalDate.of(2026, 11, 10)

    private fun event(title: String, date: LocalDate, completed: Boolean = false) = CalendarEvent(1, date, title, null, completed)
    private fun task(title: String, due: LocalDate) = UpcomingItem("task-1", title, due, UpcomingKind.TASK)

    /** The emoji on [date], with the 4th of November as today. */
    private fun CalendarEmojiStore.on(date: LocalDate, events: List<CalendarEvent>, upcoming: List<UpcomingItem>) = emojisOn(date, events, upcoming, nov4)

    // ---- the emoji of a title ----

    @Test fun anEmojiBelongsToATitleWhateverTheCapitalsAndSpacing() {
        val store = CalendarEmojiStore(null)
        store.setEmoji("Haircut", "✂️")
        assertEquals("✂️", store.emojiFor("haircut"))
        assertEquals("✂️", store.emojiFor("  HAIRCUT "))
        assertNull(store.emojiFor("Haircut 2"))
    }

    @Test fun theLastChoiceForATitleWinsAndNullTakesItBack() {
        val store = CalendarEmojiStore(null)
        store.setEmoji("Haircut", "✂️")
        store.setEmoji("haircut", "💇")
        assertEquals("💇", store.emojiFor("Haircut"))
        assertEquals(1, store.items.size, "one entry per title")
        store.setEmoji("Haircut", null)
        assertNull(store.emojiFor("Haircut"))
        store.setEmoji("   ", "🐠")
        assertTrue(store.items.isEmpty(), "a blank title is ignored")
    }

    @Test fun anEmojiRemembersTheTitleItWasLastUsedFor() {
        val store = CalendarEmojiStore(null)
        store.setEmoji("Haircut", "✂️")
        store.setEmoji("Water change", "💧")
        assertEquals("Haircut", store.titleFor("✂️"))
        assertEquals("Water change", store.titleFor("💧"))
        assertNull(store.titleFor("🐠"))
    }

    @Test fun eachPickCountsTowardsHowFrequentlyAnEmojiIsUsed() {
        val store = CalendarEmojiStore(null)
        store.setEmoji("Haircut", "✂️")
        store.setEmoji("Water change", "💧")
        store.setEmoji("Trim", "✂️")
        assertEquals(mapOf("💧" to 1, "✂️" to 2), store.uses)
        assertEquals(listOf("💧", "✂️"), store.uses.keys.toList(), "the most recently picked is last")
    }

    @Test fun withNoEmojiChosenTheOneTypedIntoTheTitleIsShown() {
        val store = CalendarEmojiStore(null)
        assertEquals(listOf("🐠", "🚰"), store.displayFor("HairCut, 🐠🚰"))
        assertTrue(store.displayFor("Haircut").isEmpty())
        store.setEmoji("HairCut, 🐠🚰", "✂️")
        assertEquals(listOf("✂️"), store.displayFor("HairCut, 🐠🚰"), "a chosen emoji beats the typed ones")
    }

    @Test fun anEventWithNoEmojiKeepsTheSquareMarkerAndOneWithAnEmojiDoesNot() {
        val store = CalendarEmojiStore(null)
        fun plain(vararg events: CalendarEvent) = store.hasPlainEvent(store.itemsOn(nov10, events.toList(), emptyList(), nov4))
        val dentist = event("Dentist", nov10)
        assertTrue(plain(dentist), "no emoji: still the square")
        store.setEmoji("Dentist", "🦷")
        assertFalse(plain(dentist), "an emoji instead")
        assertFalse(plain(event("🐠🚰", nov10)), "an emoji typed into the title counts")
        assertTrue(plain(dentist, event("Car service", nov10)), "one without is enough")
        assertFalse(plain(), "a day with nothing on it")
        assertFalse(store.hasPlainEvent(store.itemsOn(nov10, emptyList(), listOf(task("Bins", nov10)), nov4)), "a task without an emoji gets no square")
    }

    // ---- what is done ----

    @Test fun anOccurrenceIsDoneOnTheDayItWasTickedOffAndTakingTheTickBackUndoesIt() {
        val store = CalendarEmojiStore(null)
        assertFalse(store.isDone("Haircut", nov10))
        store.complete("Haircut", nov10, on = nov4)
        assertTrue(store.isDone("haircut", nov10))
        assertEquals(nov4, store.doneOn("Haircut", nov10))
        assertFalse(store.isDone("Haircut", nov10.plusDays(14)), "the next occurrence is a different one")
        store.undo("Haircut", nov10)
        assertFalse(store.isDone("Haircut", nov10))
        assertTrue(store.done.isEmpty())
    }

    @Test fun renamingSomethingMovesTheTickOnItsOccurrenceAndNoOther() {
        val store = CalendarEmojiStore(null)
        store.complete("Haircut", nov10, on = nov4)
        store.complete("Haircut", nov10.plusDays(14), on = nov10.plusDays(14))
        store.complete("Haircut", null, on = nov4.minusDays(14)) // a tick on something with no due date
        store.renameDone("Haircut", "Trim", nov10)
        assertEquals(nov4, store.doneOn("Trim", nov10), "the occurrence that was renamed keeps its tick")
        assertNull(store.doneOn("Haircut", nov10), "under the new title only")
        assertTrue(store.isDone("Haircut", nov10.plusDays(14)), "another occurrence of the old title is untouched")
        assertEquals(3, store.done.size)
        store.renameDone("Haircut", "haircut", nov10.plusDays(14)) // only the capitals changed: nothing to move
        assertEquals("Haircut", store.done.first { it.dueEpochDay == nov10.plusDays(14).toEpochDay() }.title)
    }

    @Test fun theMovedTickIsSavedToo() {
        val store = CalendarEmojiStore(file)
        store.complete("Haircut", nov10, on = nov4)
        store.renameDone("Haircut", "Trim", nov10)
        assertEquals(nov4, CalendarEmojiStore(file).doneOn("Trim", nov10))
    }

    @Test fun tickingTheSameOccurrenceAgainMovesItsDoneDayInsteadOfAddingAnother() {
        val store = CalendarEmojiStore(null)
        store.complete("Haircut", nov10, on = nov4)
        store.complete("Haircut", nov10, on = nov4.plusDays(1))
        assertEquals(1, store.done.size)
        assertEquals(nov4.plusDays(1), store.doneOn("Haircut", nov10))
    }

    @Test fun somethingDoneWithNoDueDateIsRememberedOncePerDay() {
        val store = CalendarEmojiStore(null)
        store.complete("Haircut", null, on = nov4)
        store.complete("haircut", null, on = nov4)
        store.complete("Haircut", null, on = nov4.plusDays(14))
        assertEquals(2, store.done.size)
    }

    // ---- what a day shows ----

    @Test fun somethingNotDoneYetIsShadedOnItsDueDate() {
        val store = CalendarEmojiStore(null).also { it.setEmoji("Haircut", "✂️"); it.setEmoji("Water change", "💧") }
        assertEquals(listOf(DayEmoji("✂️", done = false)), store.on(nov10, listOf(event("Haircut", nov10)), emptyList()))
        assertEquals(listOf(DayEmoji("💧", done = false)), store.on(nov10, emptyList(), listOf(task("Water change", nov10))))
    }

    @Test fun anEventMarkedCompletedOnThePhoneIsInFullColour() {
        val store = CalendarEmojiStore(null).also { it.setEmoji("Haircut", "✂️") }
        assertEquals(listOf(DayEmoji("✂️", done = true)), store.on(nov4, listOf(event("Haircut", nov4, completed = true)), emptyList()))
    }

    @Test fun somethingDoneEarlyMovesToTheDayItWasDoneAndLeavesItsDueDate() {
        // Due on the 10th, had on the 4th: full colour on the 4th, and nothing left on the 10th.
        val store = CalendarEmojiStore(null).also { it.setEmoji("Haircut", "✂️") }
        val due = listOf(event("Haircut", nov10))
        assertEquals(listOf(DayEmoji("✂️", false)), store.on(nov10, due, emptyList()))
        store.complete("Haircut", nov10, on = nov4)
        assertTrue(store.on(nov10, due, emptyList()).isEmpty(), "gone from its due date")
        assertEquals(listOf(DayEmoji("✂️", true)), store.on(nov4, emptyList(), emptyList()), "full colour where it was done")
    }

    @Test fun somethingMarkedDoneWithNoDueDateShowsInFullColourOnItsDay() {
        val store = CalendarEmojiStore(null).also { it.setEmoji("Haircut", "✂️") }
        store.complete("Haircut", null, on = nov4)
        assertEquals(listOf(DayEmoji("✂️", true)), store.on(nov4, emptyList(), emptyList()))
        assertTrue(store.on(nov4.plusDays(1), emptyList(), emptyList()).isEmpty(), "only on that day")
    }

    @Test fun aTitleWithNoEmojiShowsNothingEvenWhenDone() {
        val store = CalendarEmojiStore(null)
        store.complete("Dentist", nov10, on = nov10)
        assertTrue(store.on(nov10, listOf(event("Dentist", nov10)), emptyList()).isEmpty())
    }

    @Test fun theEmojiTypedIntoATitleShowsOnItsDayLikeAChosenOne() {
        val store = CalendarEmojiStore(null)
        val fish = event("🐠🚰", nov4, completed = true)
        assertEquals(listOf(DayEmoji("🐠", true), DayEmoji("🚰", true)), store.on(nov4, listOf(fish), emptyList()))
    }

    // ---- saved on this PC ----

    @Test fun everythingIsSavedAndComesBack() {
        val store = CalendarEmojiStore(file)
        store.setEmoji("Haircut", "✂️")
        store.setEmoji("Water change", "💧")
        store.complete("Haircut", nov10, on = nov4)
        store.complete("Haircut", null, on = nov4.minusDays(14))
        val back = CalendarEmojiStore(file)
        assertEquals(store.items, back.items)
        assertEquals(store.uses, back.uses)
        assertEquals(store.done, back.done)
        assertEquals("💧", back.emojiFor("water change"))
        assertEquals(nov4, back.doneOn("Haircut", nov10))
        store.undo("Haircut", nov10)
        assertFalse(CalendarEmojiStore(file).isDone("Haircut", nov10), "and a change is saved too")
    }

    @Test fun aMissingOrDamagedFileJustMeansNothingChosenYet() {
        assertTrue(CalendarEmojiStore(file).items.isEmpty())
        file.writeText("not json")
        val store = CalendarEmojiStore(file)
        assertTrue(store.items.isEmpty() && store.done.isEmpty() && store.uses.isEmpty())
        assertNull(CalendarEmojiStore(null).emojiFor("Haircut"))
    }

    @Test fun aFileWrittenByHandInTheSameShapeIsRead() {
        // The haircut had on Wednesday 4 November 2026, as it was first put on the Calendar.
        file.writeText("""{"items":[{"title":"Haircut","emoji":"✂️"}],"uses":{"✂️":1},"done":[{"title":"Haircut","doneEpochDay":${nov4.toEpochDay()}}]}""")
        val store = CalendarEmojiStore(file)
        assertEquals("✂️", store.emojiFor("Haircut"))
        assertEquals(listOf(DayEmoji("✂️", true)), store.on(nov4, emptyList(), emptyList()))
    }
}
