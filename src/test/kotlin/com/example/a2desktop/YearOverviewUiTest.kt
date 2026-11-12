package com.example.a2desktop

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import com.example.a2desktop.data.DesktopStore
import com.example.a2desktop.data.VitaminStore
import com.example.a2desktop.link.LinkAddress
import com.example.a2desktop.link.LinkController
import com.example.a2desktop.link.LinkResult
import com.example.a2desktop.link.LinkStorage
import com.example.a2desktop.link.SnapshotSource
import com.example.a2desktop.logic.computeHabitStreaks
import com.example.a2desktop.logic.runEndsInYear
import com.example.a2desktop.logic.soberConflictingIds
import com.example.a2desktop.theme.A2Theme
import com.example.a2desktop.ui.A2App
import com.example.a2desktop.ui.DevMode
import com.example.a2desktop.ui.Page
import java.nio.file.Files
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import org.jetbrains.skia.Bitmap

/**
 * The Month Overview (the phone's page 0.7) on the PC, driven for real in a headless window: the month's name and the
 * Year button open it, Back returns to the same month grid, the arrows step the year, and every square of the table
 * is painted in the colours of the habits the store holds for that date.
 */
@OptIn(ExperimentalComposeUiApi::class)
class YearOverviewUiTest {
    private val today = LocalDate.of(2026, 10, 15)

    private class Rig(val store: DesktopStore, val scene: ImageComposeScene, val dev: DevMode) {
        var time = 0L
        fun settle() { repeat(6) { time += 50_000_000; scene.render(time) } }
        fun bitmap(): Bitmap { settle(); return Bitmap.makeFromImage(scene.render(time + 1_000_000)) }
        fun group(id: String): Rect = dev.groups.getValue(id).bounds
        fun has(id: String): Boolean { settle(); return dev.groups.containsKey(id) }
        fun click(at: Offset) {
            scene.sendPointerEvent(PointerEventType.Move, at)
            scene.sendPointerEvent(PointerEventType.Press, at, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
            settle()
            scene.sendPointerEvent(PointerEventType.Release, at, button = PointerButton.Primary)
            settle()
        }
        fun hover(at: Offset) { scene.sendPointerEvent(PointerEventType.Move, at); settle() }
    }

    private fun rig(block: (Rig) -> Unit) {
        val store = DesktopStore(today).also { it.loadSampleData() }
        val link = LinkController(store, LinkStorage(Files.createTempDirectory("a2-year").toFile()), object : SnapshotSource {
            override fun fetch(address: LinkAddress, pairingKey: String) = LinkResult.Failed("no phone in tests")
        }, CoroutineScope(Job()), Dispatchers.Unconfined)
        val dev = DevMode().also { it.enabled = true }
        val scene = ImageComposeScene(width = 1360, height = 860, density = Density(1f)) {
            A2Theme { A2App(store, link, Page.CALENDAR, vitamins = VitaminStore(null), dev = dev) }
        }
        val r = Rig(store, scene, dev)
        try {
            r.settle()
            block(r)
        } finally {
            scene.close()
        }
    }

    // ---- the table's geometry, from where developer mode says the table is (PC1.10) ----
    private val dateColumn = 28f
    private fun rowHeight(table: Rect) = (table.height - 31f) / 32f
    private fun columnWidth(table: Rect) = (table.width - dateColumn - 12f) / 12f
    private fun squareLeft(table: Rect, month: Int) = table.left + dateColumn + 1f + (month - 1) * (columnWidth(table) + 1f)
    private fun squareTop(table: Rect, day: Int) = table.top + day * (rowHeight(table) + 1f)
    private fun squareCentre(table: Rect, date: LocalDate) = Offset(
        squareLeft(table, date.monthValue) + columnWidth(table) / 2f,
        squareTop(table, date.dayOfMonth) + rowHeight(table) / 2f,
    )

    private fun Bitmap.rgb(x: Float, y: Float): Int = getColor(x.toInt(), y.toInt()) and 0xFFFFFF

    private fun close(a: Int, b: Int): Boolean =
        listOf(16, 8, 0).all { shift -> kotlin.math.abs(((a shr shift) and 0xFF) - ((b shr shift) and 0xFF)) <= 3 }

    /** How many pixels of [colour] are in the table. */
    private fun Bitmap.count(table: Rect, colour: Int): Int {
        var n = 0
        for (x in table.left.toInt() until table.right.toInt()) for (y in table.top.toInt() until table.bottom.toInt()) {
            if (close(getColor(x, y) and 0xFFFFFF, colour)) n++
        }
        return n
    }

    @Test fun theMonthNameAndTheYearButtonOpenTheOverviewAndBackReturnsToTheSameMonthGrid() = rig { r ->
        assertTrue(r.has("1.2") && !r.has("1.10"), "the month grid shows to begin with")
        val before = r.bitmap()
        val grid = r.group("1.2")

        val header = r.group("1.1")
        r.click(Offset(header.left + 40f, header.top + 22f)) // the month's name, as on the phone
        assertTrue(r.has("1.10") && !r.has("1.2"), "the month's name opens the year in place of the month grid")
        r.click(r.group("1.9.4").center) // Back to calendar
        assertTrue(r.has("1.2") && !r.has("1.10"), "Back returns to the month grid")
        val after = r.bitmap()
        for (x in grid.left.toInt() until grid.right.toInt() step 3) for (y in grid.top.toInt() until grid.bottom.toInt() step 3) {
            assertEquals(before.getColor(x, y), after.getColor(x, y), "the same month grid as before at ($x, $y)")
        }

        r.click(r.group("1.1.1").center) // the Year button
        assertTrue(r.has("1.10") && !r.has("1.2"), "the Year button opens it too")
        assertTrue(r.has("1.9") && r.has("1.11"), "with its header and its colour key")
    }

    @Test fun theArrowsStepTheYearAndThisYearComesBack() = rig { r ->
        r.click(r.group("1.1.1").center)
        val sober = r.store.soberHabit!!.colorArgb and 0xFFFFFF
        val thisYear = r.bitmap().count(r.group("1.10"), sober)
        assertTrue(thisYear > 2000, "2026 has Sober days painted ($thisYear pixels)")

        r.click(r.group("1.9.1").center) // previous year: 2025, before anything was recorded
        assertEquals(0, r.bitmap().count(r.group("1.10"), sober), "2025 is empty")
        r.click(r.group("1.9.3").center) // next year twice: 2026, then 2027 (nothing recorded yet)
        r.click(r.group("1.9.3").center)
        assertEquals(0, r.bitmap().count(r.group("1.10"), sober), "2027 is empty")
        r.click(r.group("1.9.2").center) // This year
        assertEquals(thisYear, r.bitmap().count(r.group("1.10"), sober), "This year is back to 2026")
    }

    @Test fun everySquareHasTheColoursOfTheHabitsRecordedOnThatDateSideBySide() = rig { r ->
        r.click(r.group("1.1.1").center)
        val table = r.group("1.10")
        val shot = r.bitmap()
        val linked = r.store.habits.filter { it.linkedToCalendar }
        val everyColour = linked.map { it.colorArgb and 0xFFFFFF }
        var painted = 0
        var severalHabits = 0
        var year2026 = LocalDate.of(2026, 1, 1)
        while (year2026.year == 2026) {
            val date = year2026
            val habits = linked.filter { it.id in r.store.habitsOn(date) }
            val left = squareLeft(table, date.monthValue)
            val y = squareTop(table, date.dayOfMonth) + rowHeight(table) / 2f
            val width = columnWidth(table)
            if (habits.isEmpty()) {
                assertFalse(everyColour.any { close(shot.rgb(left + width / 2f, y), it) }, "$date has no habit, so no colour")
            } else {
                painted++
                if (habits.size > 1) severalHabits++
                habits.forEachIndexed { i, habit ->
                    // A lone band is sampled near its left edge: the streak number is written across the middle of a run's last square.
                    val x = if (habits.size == 1) left + 10f else left + (i + 0.5f) * width / habits.size
                    assertTrue(close(shot.rgb(x, y), habit.colorArgb and 0xFFFFFF), "$date band ${i + 1} of ${habits.size} is ${habit.name}'s colour")
                }
            }
            year2026 = date.plusDays(1)
        }
        assertTrue(painted > 40, "enough painted days to mean something ($painted)")
        assertTrue(severalHabits >= 2, "and some with several habits side by side ($severalHabits)")
        // A date that does not exist in a month stays empty: 30 February, and the 31st of September.
        listOf(LocalDate.of(2026, 3, 1) to 30, LocalDate.of(2026, 9, 1) to 31).forEach { (month, day) ->
            val x = squareLeft(table, month.monthValue) + columnWidth(table) / 2f
            val y = squareTop(table, day) + rowHeight(table) / 2f
            assertFalse(everyColour.any { close(shot.rgb(x, y), it) }, "${month.month} $day does not exist")
        }
    }

    @Test fun pointingAtADayReadsItOutInTheHeaderAndLeavingTheTableClearsIt() = rig { r ->
        r.click(r.group("1.1.1").center)
        val table = r.group("1.10")
        val header = r.group("1.9")
        val quiet = r.bitmap()
        val day = LocalDate.of(2026, 10, 12)
        r.hover(squareCentre(table, day))
        val pointing = r.bitmap()
        var headerChanged = 0
        for (x in header.left.toInt() until header.left.toInt() + 400) for (y in header.top.toInt() until header.bottom.toInt()) {
            if (quiet.getColor(x, y) != pointing.getColor(x, y)) headerChanged++
        }
        assertTrue(headerChanged > 100, "the header now reads the day out ($headerChanged pixels changed)")
        // The outline is pure white, which the grid lines (white at under half strength) never are.
        val left = squareLeft(table, day.monthValue)
        val y = squareCentre(table, day).y
        fun whiteNearLeftEdge(shot: Bitmap) = (-1..3).any { dx -> close(shot.rgb(left + dx, y), 0xFFFFFF) }
        assertTrue(whiteNearLeftEdge(pointing), "the square is outlined in white while pointed at")
        assertFalse(whiteNearLeftEdge(quiet), "and was not before")

        r.hover(Offset(5f, 5f)) // off the table
        val away = r.bitmap()
        for (x in header.left.toInt() until header.left.toInt() + 400 step 2) for (y in header.top.toInt() until header.bottom.toInt()) {
            assertEquals(quiet.getColor(x, y), away.getColor(x, y), "the header reads as before at ($x, $y)")
        }
    }

    // ---- the streak number on the last blue square of each run ----

    /** The days of 2026 that end a run of Sober days, worked out plainly from what the store shows. */
    private fun soberRunEnds(store: DesktopStore): List<LocalDate> {
        val sober = store.soberHabit!!.id
        return generateSequence(LocalDate.of(2026, 1, 1)) { it.plusDays(1) }.takeWhile { it.year == 2026 }
            .filter { sober in store.habitsOn(it) && sober !in store.habitsOn(it.plusDays(1)) }.toList()
    }

    /** The dark ink inside a square: how many pixels of it, and how wide it is (0 when there is none). */
    private class Ink(val pixels: Int, val width: Int)

    private fun Bitmap.inkIn(table: Rect, date: LocalDate): Ink {
        val left = squareLeft(table, date.monthValue)
        val top = squareTop(table, date.dayOfMonth)
        var n = 0
        var minX = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        for (x in (left + 2f).toInt() until (left + columnWidth(table) - 2f).toInt()) {
            for (y in (top + 1f).toInt() until (top + rowHeight(table) - 1f).toInt()) {
                val c = getColor(x, y)
                // The writing is near-black; the habit colours and the grid lines (grey) are all lighter.
                if (listOf(16, 8, 0).all { ((c shr it) and 0xFF) <= 0x50 }) { n++; minX = minOf(minX, x); maxX = maxOf(maxX, x) }
            }
        }
        return Ink(n, if (n == 0) 0 else maxX - minX + 1)
    }

    @Test fun onlyTheLastSquareOfEachBlueRunHasAnumberWrittenOnIt() = rig { r ->
        r.click(r.group("1.1.1").center)
        val table = r.group("1.10")
        val shot = r.bitmap()
        val sober = r.store.soberHabit!!
        val ends = soberRunEnds(r.store).toSet()
        assertTrue(ends.size >= 8, "the demo year has several runs (${ends.size})")
        var blueDays = 0
        for (day in generateSequence(LocalDate.of(2026, 1, 1)) { it.plusDays(1) }.takeWhile { it.year == 2026 }) {
            if (sober.id !in r.store.habitsOn(day)) continue
            blueDays++
            val ink = shot.inkIn(table, day).pixels
            if (day in ends) assertTrue(ink > 4, "$day ends a run, so it has a number ($ink pixels of writing)")
            else assertEquals(0, ink, "$day is blue but the run goes on, so no number")
        }
        assertTrue(blueDays > ends.size, "there are blue days that are not run ends ($blueDays)")
    }

    @Test fun aLongerRunHasMoreDigitsThanAShorterOne() = rig { r ->
        r.click(r.group("1.1.1").center)
        val table = r.group("1.10")
        val shot = r.bitmap()
        val lengths = runEndsInYear(2026) { r.store.soberHabit!!.id in r.store.habitsOn(it) }
        val twoDigits = lengths.filterValues { it >= 10 }.keys
        val oneDigit = lengths.filterValues { it < 10 }.keys
        assertTrue(twoDigits.isNotEmpty() && oneDigit.isNotEmpty(), "the demo year has a 10+ day run and shorter ones ($lengths)")
        val widest = oneDigit.maxOf { shot.inkIn(table, it).width }
        val narrowest = twoDigits.minOf { shot.inkIn(table, it).width }
        assertTrue(narrowest > widest, "a number of two digits is wider ($narrowest px) than any of one digit ($widest px)")
    }

    @Test fun theNumbersAreTheSameStreakLengthsThePhonesMathsGives() {
        val store = DesktopStore(today).also { it.loadSampleData() }
        val sober = store.soberHabit!!
        val start = store.earliestTracked!!
        val byDate = generateSequence(start) { it.plusDays(1) }.takeWhile { !it.isAfter(today) }.associateWith { store.habitsOn(it) }
        val streaks = computeHabitStreaks(byDate, sober.id, store.habits.soberConflictingIds(sober.id))
        assertTrue(streaks.size >= 8)
        val expected = streaks.filter { it.end.year == 2026 }.associate { it.end to it.lengthDays.toInt() }
        assertEquals(expected, runEndsInYear(2026) { sober.id in store.habitsOn(it) })
    }
}
