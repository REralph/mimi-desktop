package com.example.a2desktop

import com.example.a2desktop.logic.upcomingWithTitle
import com.example.a2desktop.model.UpcomingItem
import com.example.a2desktop.model.UpcomingKind
import com.example.a2desktop.ui.alreadyOnListText
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Finding what is already on the Upcoming list under a title, and what the forms say about it. */
class AlreadyOnListTest {
    private val nov6 = LocalDate.of(2026, 11, 6)
    private val nov10 = LocalDate.of(2026, 11, 10)

    // Your two haircuts on the phone: the one every 14 days, and the stray one-time one added on the 6th.
    private val repeating = UpcomingItem("event-38", "Haircut", nov10, UpcomingKind.EVENT, "Every 14 days")
    private val stray = UpcomingItem("event-39", "Haircut", nov6, UpcomingKind.EVENT, null)
    private val water = UpcomingItem("task-2", "10% Water Change", LocalDate.of(2026, 10, 12), UpcomingKind.TASK, "Every week")
    private val list = listOf(water, repeating, stray)

    @Test fun aTitleIsFoundWhateverTheCapitalsAndSpacingSoonestFirst() {
        assertEquals(listOf(stray, repeating), upcomingWithTitle("haircut", list))
        assertEquals(listOf(stray, repeating), upcomingWithTitle("  HAIRCUT ", list))
        assertEquals(listOf(water), upcomingWithTitle("10%  water change", list))
    }

    @Test fun aTitleNotOnTheListOrABlankOneFindsNothing() {
        assertTrue(upcomingWithTitle("Dentist", list).isEmpty())
        assertTrue(upcomingWithTitle("Hair", list).isEmpty(), "only the whole title counts")
        assertTrue(upcomingWithTitle("", list).isEmpty())
        assertTrue(upcomingWithTitle("   ", list).isEmpty())
    }

    @Test fun theNoticeSaysWhatIsThereHowItRepeatsAndWhenItIsNextDue() {
        assertEquals("“Haircut” is already on your list (Event · Every 14 days · next Tue 10 Nov).", alreadyOnListText(listOf(repeating)))
        assertEquals("“Haircut” is already on your list (Event · next Fri 6 Nov).", alreadyOnListText(listOf(stray)))
        assertEquals("“10% Water Change” is already on your list (Task · Every week · next Mon 12 Oct).", alreadyOnListText(listOf(water)))
    }

    @Test fun whenThereAreSeveralTheNoticeSaysSoAndNamesTheSoonest() {
        assertEquals("“Haircut” is already on your list (Event · next Fri 6 Nov) and 1 more with this name.", alreadyOnListText(listOf(stray, repeating)))
    }
}
