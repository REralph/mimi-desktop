package com.example.a2desktop

import com.example.a2desktop.data.DataSource
import com.example.a2desktop.data.DesktopStore
import com.example.a2desktop.link.LinkAddress
import com.example.a2desktop.logic.LIFE_MONTHS
import com.example.a2desktop.logic.habitsOnDate
import com.example.a2desktop.logic.lifeProgress
import com.example.a2desktop.logic.soberConflictingIds
import com.example.a2desktop.logic.wholeMonthsLived
import com.example.a2desktop.model.Habit
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LogicTest {
    private val sober = Habit(1, "☁️", "Sober", 0, isSober = true)
    private val reading = Habit(2, "📖", "Reading", 0)
    private val running = Habit(3, "🏃", "Running", 0, linkedToCalendar = false)
    private val linkedButHidden = Habit(4, "🛑", "Slip", 0, linkedToCalendar = false, breaksStreakOfHabitId = 1)
    private val habits = listOf(sober, reading, running, linkedButHidden)
    private val today = LocalDate.of(2026, 10, 5)
    private val start = LocalDate.of(2026, 9, 1)

    private fun on(date: LocalDate, vararg recorded: Long) = habitsOnDate(date, recorded.toSet(), habits, start, today)

    @Test fun emptyPastDayAndTodayAreSober() {
        assertEquals(setOf(1L), on(LocalDate.of(2026, 9, 15)))
        assertEquals(setOf(1L), on(today))
    }

    @Test fun futureDaysAndDaysBeforeTrackingStayBlank() {
        assertEquals(emptySet(), on(today.plusDays(1)))
        assertEquals(emptySet(), on(LocalDate.of(2026, 8, 31)))
        assertEquals(emptySet(), habitsOnDate(today, emptySet(), habits, null, today)) // nothing ever tracked
    }

    @Test fun aLinkedHabitReplacesSoberAndSuppressesAnExplicitSoberRecord() {
        assertEquals(setOf(2L), on(LocalDate.of(2026, 9, 16), 2))
        assertEquals(setOf(2L), on(LocalDate.of(2026, 9, 16), 1, 2))
    }

    @Test fun aDoNotLinkHabitWithNoRelationshipDoesNotRemoveSober() =
        assertEquals(setOf(1L, 3L), on(LocalDate.of(2026, 9, 16), 3))

    @Test fun aDoNotLinkHabitExplicitlyLinkedToSoberStillConflicts() {
        assertEquals(setOf(2L, 4L), habits.soberConflictingIds(1))
        assertEquals(setOf(4L), on(LocalDate.of(2026, 9, 16), 4))
    }

    @Test fun lifeMonthsMatchThePhoneRules() {
        val p = lifeProgress(LocalDate.of(1995, 7, 20), LocalDate.of(2023, 7, 20))
        assertEquals(336, p.monthsLived)
        assertEquals(624, p.monthsRemaining)
        assertEquals(1, wholeMonthsLived(LocalDate.of(2026, 1, 31), LocalDate.of(2026, 2, 28)))
        val old = lifeProgress(LocalDate.of(1940, 5, 5), LocalDate.of(2026, 10, 12))
        assertEquals(LIFE_MONTHS, old.monthsLived)
        assertEquals(0, old.monthsRemaining)
        assertNull(old.currentMonthIndex)
    }

    @Test fun aFreshStoreHoldsNothingAtAll() {
        val store = DesktopStore(LocalDate.of(2026, 10, 14))
        assertEquals(DataSource.NONE, store.source)
        assertTrue(store.habits.isEmpty() && store.dreams.isEmpty() && store.sessions.isEmpty() && store.events.isEmpty() && store.upcoming.isEmpty())
        assertNull(store.dateOfBirth)
        assertTrue(store.infoLines().isEmpty())
        assertFalse(store.readOnly)
        // …and, with nothing there to change, none of the edit paths do anything.
        store.toggleHabit(store.today, 1)
        assertNull(store.newDream())
        store.enterDateOfBirth(LocalDate.of(1990, 1, 1))
        assertNull(store.dateOfBirth)
    }

    @Test fun sampleStoreNeverAssumesADateOfBirthAndIsEditable() {
        val store = DesktopStore(LocalDate.of(2026, 10, 14)).also { it.loadSampleData() }
        assertEquals(DataSource.SAMPLE, store.source)
        assertNull(store.dateOfBirth)
        assertFalse(store.readOnly)
        assertFalse(store.habitsOn(store.today.plusDays(1)).contains(store.soberHabit!!.id))
        assertTrue(store.soberHabit!!.id in store.habitsOn(store.today.minusDays(1)) || store.logs[store.today.minusDays(1)] != null)
        val gym = store.habits.first { it.name == "Gym" }
        val day = store.today.minusDays(40)
        val before = gym.id in store.logs[day].orEmpty()
        store.toggleHabit(day, gym.id)
        assertEquals(!before, gym.id in store.logs[day].orEmpty())
    }

    @Test fun informationLinesUseThePhonesFormat() {
        val store = DesktopStore(LocalDate.of(2026, 10, 14)).also { it.loadSampleData() }
        val lines = store.infoLines().map { it.text }
        assertTrue(lines.first().startsWith("☁️ — Streak: "), lines.first())
        assertTrue(lines.any { it.startsWith("🍷 — Last: ") && it.contains("Goal: ") }, lines.toString())
    }

    @Test fun addressesMustBeLocalNetworkIpv4Literals() {
        assertEquals(LinkAddress("192.168.1.23", 47321), LinkAddress.parse("192.168.1.23"))
        assertEquals(LinkAddress("10.0.0.5", 5000), LinkAddress.parse(" 10.0.0.5:5000 "))
        listOf("8.8.8.8", "example.com", "192.168.1", "192.168.1.300", "192.168.1.2:0", "192.168.1.2:abc", "", "172.32.0.1")
            .forEach { assertNull(LinkAddress.parse(it), it) }
        assertNotNull(LinkAddress.parse("172.16.0.1"))
        assertNotNull(LinkAddress.parse("127.0.0.1")) // this machine itself is fine
    }
}
