package com.example.a2desktop

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.example.a2desktop.logic.EveningKind
import com.example.a2desktop.logic.SeasonalUv
import com.example.a2desktop.logic.SkinCareLog
import com.example.a2desktop.logic.UvLookup
import com.example.a2desktop.logic.WorkSchedule
import com.example.a2desktop.logic.WorkShift
import com.example.a2desktop.logic.forecastUv
import com.example.a2desktop.logic.planRoutine
import com.example.a2desktop.logic.seasonalUv
import com.example.a2desktop.theme.A2Theme
import com.example.a2desktop.ui.EVENINGS_AHEAD_DAYS
import com.example.a2desktop.ui.EVENINGS_PAST_DAYS
import com.example.a2desktop.ui.EveningsList
import com.example.a2desktop.ui.eveningRows
import com.example.a2desktop.ui.todayIndex
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The evenings list: what it holds (the days before today, then the plan), what each line says, and how it scrolls. */
@OptIn(ExperimentalComposeUiApi::class)
class EveningsListTest {
    private val today = LocalDate.of(2026, 10, 23)

    /** The routine began 10 days ago unless said otherwise. */
    private fun rows(
        log: SkinCareLog = SkinCareLog(),
        work: WorkSchedule = WorkSchedule.EMPTY,
        day: LocalDate = today,
        startedOn: LocalDate? = day.minusDays(10),
        uv: UvLookup = SeasonalUv,
        confirmFrom: LocalDate? = null,
    ) = eveningRows(day, log, work, planRoutine(day, startedOn, EVENINGS_AHEAD_DAYS - 1, log, work, uv, confirmFrom), uv)

    // ---- what is in it ----

    @Test fun itHoldsTheEarlierDaysThenTodayThenTheDaysAhead() {
        val r = rows()
        assertEquals(EVENINGS_PAST_DAYS + EVENINGS_AHEAD_DAYS, r.size)
        assertEquals(EVENINGS_PAST_DAYS, todayIndex(r), "today comes right after the earlier days")
        assertEquals(today, r[todayIndex(r)].date)
        assertTrue(r.take(EVENINGS_PAST_DAYS).all { it.isPast && it.date < today })
        assertTrue(r.drop(EVENINGS_PAST_DAYS + 1).all { !it.isPast && it.date > today })
        assertEquals(r.map { it.date }.sorted(), r.map { it.date }, "oldest first, one line per day")
        assertEquals(r.size, r.map { it.date }.toSet().size)
    }

    @Test fun aDayNobodyTickedSinceTheRoutineBeganShowsThePlanAsFollowedNotAsNotTicked() {
        val byDay = rows().associateBy { it.date }
        for (back in 1..10) {
            val row = byDay.getValue(today.minusDays(back.toLong()))
            assertNotNull(row.kind, "${row.date}: the plan, taken as followed")
            assertTrue(row.assumed, "${row.date} is assumed")
            assertFalse(row.done, "${row.date} wasn't ticked")
        }
    }

    @Test fun aPastEveningNobodyConfirmedAfterConfirmingBeganIsMissedNotAssumed() {
        val byDay = rows(confirmFrom = today.minusDays(5)).associateBy { it.date }
        for (back in 6..10) assertTrue(byDay.getValue(today.minusDays(back.toLong())).assumed, "${today.minusDays(back.toLong())} is from before confirming began")
        val after = (1..5).map { byDay.getValue(today.minusDays(it.toLong())) }
        assertTrue(after.any { it.missed }, "some of the last five evenings had products on and were never confirmed")
        after.forEach { row ->
            assertFalse(row.assumed, "${row.date}: not assumed any more")
            if (row.missed) assertFalse(row.done)
            if (row.kind == EveningKind.REST) assertFalse(row.missed, "${row.date}: a rest night has nothing to confirm")
        }
        assertFalse(byDay.getValue(today).missed, "today can still be confirmed")
    }

    @Test fun anEveningTheUserSaidNoToIsSkippedEvenTonightAndBeforeConfirmingBegan() {
        // A no to tonight and to the ten days before it, all from before confirming began (confirmFrom is today, so none would be missed otherwise).
        val said = (0..10).map { today.minusDays(it.toLong()) }.toSet()
        val byDay = rows(log = SkinCareLog(eveningsSkipped = said), confirmFrom = today).associateBy { it.date }
        val withProducts = said.map { byDay.getValue(it) }.filter { it.kind != null && it.kind != EveningKind.REST }
        assertTrue(withProducts.size >= 5, "plenty of those evenings had products on (${withProducts.size})")
        withProducts.forEach { assertTrue(it.missed && it.skipped && !it.done && !it.assumed, "${it.date}: dropped, and said so") }
        said.map { byDay.getValue(it) }.filter { it.kind == EveningKind.REST }.forEach { assertFalse(it.missed || it.skipped, "${it.date}: a rest night has nothing to say no to") }
        assertTrue(byDay.values.filter { it.date !in said }.none { it.skipped }, "no other day is skipped")
    }

    @Test fun aDayBeforeTheRoutineBeganWithNothingRecordedShowsNothing() {
        val row = rows().single { it.date == today.minusDays(11) }
        assertNull(row.kind)
        assertFalse(row.assumed || row.done)
    }

    @Test fun whatTheUserRecordedWinsOverThePlanAndCountsAsDone() {
        val log = SkinCareLog(
            glycolicNights = setOf(today.minusDays(2), today.minusDays(9)),
            tonerNights = setOf(today.minusDays(9)),
            eveningsDone = setOf(today.minusDays(1), today.minusDays(2), today.minusDays(14)),
            eveningKinds = mapOf(today.minusDays(1) to EveningKind.BOTH, today.minusDays(5) to EveningKind.REST, today.minusDays(14) to EveningKind.BRIGHTEN),
        )
        val byDay = rows(log).associateBy { it.date }
        assertEquals(EveningKind.BOTH, byDay.getValue(today.minusDays(1)).kind)
        assertEquals(EveningKind.GLYCOLIC, byDay.getValue(today.minusDays(2)).kind)
        assertEquals(EveningKind.TONER, byDay.getValue(today.minusDays(9)).kind)
        assertEquals(EveningKind.REST, byDay.getValue(today.minusDays(5)).kind)
        assertTrue(byDay.getValue(today.minusDays(1)).done && byDay.getValue(today.minusDays(2)).done)
        assertFalse(byDay.getValue(today.minusDays(1)).assumed)
        // Recorded from before the routine began still shows.
        assertEquals(EveningKind.BRIGHTEN, byDay.getValue(today.minusDays(14)).kind)
        assertTrue(byDay.getValue(today.minusDays(14)).done)
    }

    @Test fun earlierDaysShowTheWorkTheyHad() {
        val yesterday = today.minusDays(1)
        val work = WorkSchedule(mapOf(yesterday to listOf(WorkShift(yesterday, LocalTime.of(12, 30), LocalTime.of(21, 0), false))), setOf(yesterday, today.minusDays(2)))
        val byDay = rows(work = work).associateBy { it.date }
        assertEquals("12:30–21:00", byDay.getValue(yesterday).work)
        assertEquals("Day off", byDay.getValue(today.minusDays(2)).work)
        assertEquals("—", byDay.getValue(today.minusDays(3)).work)
    }

    @Test fun todayAndLaterShowThePlanAndTodayShowsTickedOnceTicked() {
        val planned = rows().single { it.isToday }
        assertNotNull(planned.kind)
        assertFalse(planned.done)
        assertNotNull(planned.why)
        val ticked = rows(SkinCareLog(eveningsDone = setOf(today), eveningKinds = mapOf(today to EveningKind.BRIGHTEN))).single { it.isToday }
        assertEquals(EveningKind.BRIGHTEN, ticked.kind)
        assertTrue(ticked.done)
    }

    // ---- UV and temperature ----

    @Test fun eachLineShowsThatDaysUvAndHighestTemperatureInCelsiusFromTheForecast() {
        val uv = UvLookup { date -> if (date >= today.minusDays(14)) forecastUv(2.6, 14.4) else seasonalUv(date) }
        val byDay = rows(uv = uv).associateBy { it.date }
        listOf(today.minusDays(3), today, today.plusDays(4)).forEach {
            assertEquals("UV 3", byDay.getValue(it).uv, "$it")
            assertEquals("14°C", byDay.getValue(it).temp, "$it")
            assertTrue(byDay.getValue(it).uvIsForecast)
        }
        assertEquals("—", byDay.getValue(today.minusDays(20)).uv, "older than the forecast reaches: nothing is invented")
        assertEquals("—", byDay.getValue(today.minusDays(20)).temp)
    }

    @Test fun withNoTownTheUvIsAnHonestGuessAndThereIsNoTemperature() {
        val byDay = rows().associateBy { it.date }
        assertEquals("UV low", byDay.getValue(today).uv, "October's usual level, shown as a guess")
        assertFalse(byDay.getValue(today).uvIsForecast)
        assertEquals("—", byDay.getValue(today).temp)
        assertEquals("—", byDay.getValue(today.minusDays(2)).uv, "and nothing at all for an old day")
    }

    // ---- how it scrolls ----
    // The list sits inside a page that scrolls, as it does on the Calendar: the mouse wheel must scroll THAT page,
    // and the list must move only by its own bar and buttons.

    private class Rig(val scene: ImageComposeScene, val list: LazyListState, val page: ScrollState) {
        var time = 0L
        fun settle(frames: Int = 6) = repeat(frames) { time += 50_000_000; scene.render(time) }
        fun wheel(at: Offset, down: Float) {
            scene.sendPointerEvent(PointerEventType.Scroll, at, scrollDelta = Offset(0f, down))
            settle()
        }
        fun click(at: Offset) {
            time += 100_000_000
            scene.sendPointerEvent(PointerEventType.Move, at)
            scene.sendPointerEvent(PointerEventType.Press, at, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
            scene.render(time)
            scene.sendPointerEvent(PointerEventType.Release, at, button = PointerButton.Primary)
            scene.render(time + 16_000_000)
            time += 16_000_000
            settle(24) // the list animates to where it is going
        }
        fun drag(from: Offset, to: Offset) {
            time += 100_000_000
            scene.sendPointerEvent(PointerEventType.Move, from)
            scene.sendPointerEvent(PointerEventType.Press, from, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
            scene.render(time)
            val steps = 8
            for (i in 1..steps) {
                val p = Offset(from.x + (to.x - from.x) * i / steps, from.y + (to.y - from.y) * i / steps)
                time += 16_000_000
                scene.sendPointerEvent(PointerEventType.Move, p, buttons = PointerButtons(isPrimaryPressed = true))
                scene.render(time)
            }
            scene.sendPointerEvent(PointerEventType.Release, to, button = PointerButton.Primary)
            settle()
        }
    }

    // Where things are in the 420 x 300 test window (found by looking at a render): the buttons on the header, the bar's thumb.
    private val upButton = Offset(339f, 11f)
    private val downButton = Offset(365f, 11f)
    private val todayButton = Offset(400f, 11f)
    private val inTheList = Offset(150f, 100f)

    private fun rig(day: () -> LocalDate): Rig {
        val list = LazyListState() // starts at the very top, i.e. the oldest day: the list itself must bring it to today
        val page = ScrollState(0)
        val scene = ImageComposeScene(width = 420, height = 300, density = Density(1f)) {
            A2Theme {
                Surface {
                    val shown = day()
                    Column(Modifier.verticalScroll(page)) {
                        EveningsList(rows = rows(day = shown), today = shown, selected = null, height = 200.dp, showReason = false, state = list)
                        Spacer(Modifier.height(600.dp)) // plenty more page below, like Habits and Upcoming
                    }
                }
            }
        }
        return Rig(scene, list, page)
    }

    @Test fun itOpensAtTodayByItselfWithTheEarlierDaysAboveIt() {
        val r = rig { today }
        try {
            r.settle()
            assertEquals(EVENINGS_PAST_DAYS, r.list.firstVisibleItemIndex, "today is the first line showing")
            assertEquals(0, r.list.firstVisibleItemScrollOffset)
            assertTrue(r.list.canScrollBackward, "the earlier days are there above, to scroll up to")
            assertTrue(r.list.canScrollForward, "and the days ahead below")
        } finally {
            r.scene.close()
        }
    }

    @Test fun theMouseWheelOverTheListScrollsThePageAroundItNotTheList() {
        val r = rig { today }
        try {
            r.settle()
            assertEquals(0, r.page.value)
            r.wheel(inTheList, down = 5f)
            assertTrue(r.page.value > 0, "the page moved with the pointer over the list (now at ${r.page.value})")
            assertEquals(EVENINGS_PAST_DAYS, r.list.firstVisibleItemIndex, "and the list stayed on today")
            val moved = r.page.value
            r.wheel(Offset(150f, 280f), down = 5f) // and over the page below the list, as always
            assertTrue(r.page.value > moved)
            r.wheel(inTheList, down = -50f) // wheel up over the list brings the page back
            assertEquals(0, r.page.value)
            assertEquals(EVENINGS_PAST_DAYS, r.list.firstVisibleItemIndex)
        } finally {
            r.scene.close()
        }
    }

    @Test fun theBarOnTheRightCanBeDraggedToSeeEarlierDays() {
        val r = rig { today }
        try {
            r.settle()
            val start = r.list.firstVisibleItemIndex
            r.drag(Offset(416f, 178f), Offset(416f, 60f)) // the thumb is near the bottom of the bar; pull it up
            assertTrue(r.list.firstVisibleItemIndex < start - 5, "moved up to earlier days (from $start to ${r.list.firstVisibleItemIndex})")
            assertEquals(0, r.page.value, "dragging the list's bar never scrolls the page")
        } finally {
            r.scene.close()
        }
    }

    @Test fun theUpAndDownButtonsMoveTheListAndTodayBringsItBack() {
        val r = rig { today }
        try {
            r.settle()
            r.click(upButton)
            assertTrue(r.list.firstVisibleItemIndex < EVENINGS_PAST_DAYS, "▲ goes to earlier days (now ${r.list.firstVisibleItemIndex})")
            val up = r.list.firstVisibleItemIndex
            r.click(upButton)
            assertTrue(r.list.firstVisibleItemIndex < up, "and again")
            val beforeDown = r.list.firstVisibleItemIndex
            r.click(downButton)
            assertTrue(r.list.firstVisibleItemIndex > beforeDown, "▼ goes towards later days (from $beforeDown to ${r.list.firstVisibleItemIndex})")
            r.click(todayButton)
            assertEquals(EVENINGS_PAST_DAYS, r.list.firstVisibleItemIndex, "Today jumps straight back")
        } finally {
            r.scene.close()
        }
    }

    @Test fun whenTheDayChangesItScrollsBackToTheNewTodayEvenIfYouHadScrolledUp() {
        var day by mutableStateOf(today)
        val r = rig { day }
        try {
            r.settle()
            r.click(upButton)
            r.click(upButton)
            assertTrue(r.list.firstVisibleItemIndex < EVENINGS_PAST_DAYS)
            day = today.plusDays(1) // midnight passes
            r.settle()
            assertEquals(EVENINGS_PAST_DAYS, r.list.firstVisibleItemIndex, "back at today, which is now the 24th")
            assertEquals(0, r.list.firstVisibleItemScrollOffset)
        } finally {
            r.scene.close()
        }
    }
}