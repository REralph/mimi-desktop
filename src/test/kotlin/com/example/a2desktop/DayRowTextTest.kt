package com.example.a2desktop

import com.example.a2desktop.data.DayItem
import com.example.a2desktop.model.UpcomingKind
import com.example.a2desktop.ui.doneNote
import com.example.a2desktop.ui.isOverdue
import com.example.a2desktop.ui.repeats
import com.example.a2desktop.ui.typedEmoji
import com.example.a2desktop.ui.whenLabel
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What a row of the Selected day card's table says, and how an emoji typed into an emoji box is read. */
class DayRowTextTest {
    private val today = LocalDate.of(2026, 11, 6)
    private val past = today.minusDays(3)
    private val future = today.plusDays(3)

    private fun item(kind: UpcomingKind?, time: String? = null, repeat: String? = null, done: Boolean = false) =
        DayItem("Thing", kind, time, repeat, done, doneOn = null, projected = false)

    @Test fun anEventSaysItsTimeOrAllDay() {
        assertEquals("14:30", item(UpcomingKind.EVENT, time = "14:30").whenLabel(future, today))
        assertEquals("All day", item(UpcomingKind.EVENT).whenLabel(future, today))
        assertEquals("09:00", item(UpcomingKind.EVENT, time = "09:00").whenLabel(past, today), "a past event still says when it was")
    }

    @Test fun aTaskIsDueOnItsDayAndOverdueOnceTheDayHasPassed() {
        assertEquals("Due", item(UpcomingKind.TASK).whenLabel(today, today))
        assertEquals("Due", item(UpcomingKind.TASK).whenLabel(future, today))
        assertEquals("Overdue", item(UpcomingKind.TASK).whenLabel(past, today))
        assertTrue(item(UpcomingKind.TASK).isOverdue(past, today))
        assertFalse(item(UpcomingKind.TASK).isOverdue(today, today), "today is not late yet")
        assertFalse(item(UpcomingKind.EVENT).isOverdue(past, today), "an event is not 'overdue'")
    }

    @Test fun somethingDoneSaysDoneWhateverItIs() {
        assertEquals("Done", item(UpcomingKind.EVENT, time = "14:30", done = true).whenLabel(past, today))
        assertEquals("Done", item(UpcomingKind.TASK, done = true).whenLabel(past, today))
        assertEquals("Done", item(null, done = true).whenLabel(past, today))
        assertFalse(item(UpcomingKind.TASK, done = true).isOverdue(past, today), "a done task is not overdue")
    }

    @Test fun aRowWordsNothingAboutWhatItIsOrHowItRepeatsItGetsTheRepeatSymbolInstead() {
        // The old line under the title ("Task · Every week") is gone: whether something comes round again is the small symbol after the title.
        assertTrue(item(UpcomingKind.TASK, repeat = "Every week").repeats())
        assertTrue(item(UpcomingKind.EVENT, repeat = "Every 14 days").repeats())
        assertTrue(item(null, repeat = "Every 2 weeks").repeats())
        assertFalse(item(UpcomingKind.TASK).repeats(), "a task done once has no symbol")
        assertFalse(item(UpcomingKind.EVENT, repeat = "").repeats(), "an empty label is no repeat")
        assertFalse(item(UpcomingKind.TASK, repeat = " ").repeats())
        assertFalse(item(UpcomingKind.TASK, repeat = "One-time").repeats(), "what the phone calls a task done once is not a repeat")
        assertTrue(item(UpcomingKind.TASK, repeat = "Weekly").repeats(), "a label the PC can't read still says it comes round")
        assertTrue(item(UpcomingKind.TASK, repeat = "Tuesday").repeats(), "so does a weekday name")
    }

    @Test fun anUpcomingRowOnlyHasALineUnderItsTitleOnceItIsDone() {
        assertNull(doneNote(null, today), "not done: nothing under the title (no 'Task', no repeat words)")
        assertEquals("Done today", doneNote(today, today))
        assertEquals("Done Tue 3 Nov", doneNote(LocalDate.of(2026, 11, 3), today))
    }

    @Test fun theNewestEmojiTypedOrPastedIsTheOneTaken() {
        assertEquals("⭐", typedEmoji("⭐"))
        assertEquals("🦷", typedEmoji("✂️🦷"), "typing another after the old one replaces it")
        assertEquals("⭐", typedEmoji("a⭐b"))
        assertNull(typedEmoji("hello"), "letters are not an emoji")
        assertNull(typedEmoji(""))
    }
}
