package com.example.a2desktop

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import com.example.a2desktop.data.DesktopStore
import com.example.a2desktop.data.SkinCareStore
import com.example.a2desktop.data.VitaminStore
import com.example.a2desktop.link.LinkAddress
import com.example.a2desktop.link.LinkController
import com.example.a2desktop.link.LinkResult
import com.example.a2desktop.link.LinkStorage
import com.example.a2desktop.link.SnapshotSource
import com.example.a2desktop.theme.A2Theme
import com.example.a2desktop.ui.A2App
import com.example.a2desktop.ui.LEFT_COLUMN_DP
import com.example.a2desktop.ui.Page
import java.nio.file.Files
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import org.jetbrains.skia.Bitmap

/**
 * The "Zn" mark on the Calendar's days, checked on the rendered pixels. "Today" is Thursday 15 October 2026. Zinc was
 * last scheduled for Wednesday the 14th, so the first planned day is Friday the 16th and then Monday the 19th, Wednesday
 * the 21st … Two renders that differ by ONE thing in the vitamin store must differ only in the cells that thing touches:
 *  - ticking a planned day turns its faded mark solid, and nothing else changes;
 *  - a past zinc day carries a mark by default (untouched means taken); ticking makes it solid, saying no takes it off;
 *  - switching to "every day" schedules every day from today on, and puts nothing on the days already gone.
 */
@OptIn(ExperimentalComposeUiApi::class)
class VitaminMarksUiTest {
    private val width = 1360
    private val height = 860
    private val today = LocalDate.of(2026, 10, 15)
    private val gridStart = LocalDate.of(2026, 9, 28) // the Monday the grid of October 2026 starts on

    private fun render(configure: (VitaminStore) -> Unit = {}): Bitmap = renderWith(VitaminStore(null).also(configure))

    private fun renderWith(vitamins: VitaminStore): Bitmap {
        val store = DesktopStore(today).also { it.loadSampleData() }
        val link = LinkController(store, LinkStorage(Files.createTempDirectory("a2-vm").toFile()), object : SnapshotSource {
            override fun fetch(address: LinkAddress, pairingKey: String) = LinkResult.Failed("no phone in tests")
        }, CoroutineScope(Job()), Dispatchers.Unconfined)
        val scene = ImageComposeScene(width = width, height = height, density = Density(1f)) {
            A2Theme { A2App(store, link, Page.CALENDAR, skinCare = SkinCareStore(null), vitamins = vitamins) }
        }
        try {
            var time = 0L
            repeat(6) { time += 50_000_000; scene.render(time) }
            return Bitmap.makeFromImage(scene.render(time + 50_000_000))
        } finally {
            scene.close()
        }
    }

    // The month grid at 1360 x 860: it starts 116 px in and 116 px down and ends at 836 (the page margin), with 7 columns and 6 rows 6 px apart.
    // The grid is the middle of three columns: 24 + LEFT_COLUMN_DP (Skin care, Supplements) + 24 in, and 24 + 410 (Habits, Tasks) + 24 from the right edge;
    // it starts 180 down (under the 64 px top bar and the month's heading) and ends 24 above the bottom.
    private val cellWidth = ((width - 24 - LEFT_COLUMN_DP - 24 - 24 - 410 - 24) - 6 * 6) / 7f
    private val cellHeight = ((height - 24f) - 180f - 5 * 6f) / 6f
    private fun cellLeft(date: LocalDate) = gridLeft + (date.toEpochDay() - gridStart.toEpochDay()).toInt().mod(7) * (cellWidth + 6f)
    private fun cellTop(date: LocalDate) = 180f + ((date.toEpochDay() - gridStart.toEpochDay()).toInt() / 7) * (cellHeight + 6f)

    private val gridLeft = 24 + LEFT_COLUMN_DP + 24 // the middle column: the Skin care and Supplements cards (where the ticks are) are to its left
    private val gridRight = 902
    private val gridTop = 180
    private val gridBottom = 836

    private fun brightness(c: Int) = maxOf((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF)

    /** The month-grid pixels where [a] and [b] differ, as (x, y). */
    private fun differences(a: Bitmap, b: Bitmap): List<Pair<Int, Int>> =
        (gridLeft until gridRight).flatMap { x -> (gridTop until gridBottom).mapNotNull { y -> if (a.getColor(x, y) != b.getColor(x, y)) x to y else null } }

    private fun inCell(point: Pair<Int, Int>, date: LocalDate): Boolean =
        point.first >= cellLeft(date) - 1 && point.first <= cellLeft(date) + cellWidth + 1 &&
            point.second >= cellTop(date) - 1 && point.second <= cellTop(date) + cellHeight + 1

    @Test fun tickingAPlannedDayTurnsItsFadedMarkSolidAndNothingElseChanges() {
        val friday = LocalDate.of(2026, 10, 16)
        val planned = render()
        val ticked = render { it.setZincTaken(friday, true) }
        // Friday was already the planned dose, so ticking it doesn't move the later days.
        val diff = differences(planned, ticked)
        assertTrue(diff.size > 20, "the mark changes (${diff.size} pixels)")
        assertTrue(diff.all { inCell(it, friday) }, "and only in Friday's cell")
        assertTrue(diff.all { (x, y) -> brightness(ticked.getColor(x, y)) >= brightness(planned.getColor(x, y)) }, "going brighter: faded to solid")
    }

    @Test fun aPastZincDayShowsAMarkByDefaultBecauseUntouchedMeansTaken() {
        val monday = LocalDate.of(2026, 10, 12)
        val untouched = render()
        // The mark sits just above the habit dots in the lower left: untouched, it is already there (presumed taken).
        val chipBottom = cellTop(monday) + cellHeight - 23f
        val xs = cellLeft(monday).toInt() + 7 until cellLeft(monday).toInt() + 30
        val ys = (chipBottom - 14f).toInt() until chipBottom.toInt()
        val brightest = xs.flatMap { x -> ys.map { y -> brightness(untouched.getColor(x, y)) } }.max()
        assertTrue(brightest > 0x80, "a past zinc day carries a mark though nobody ticked it (brightest $brightest)")
        // Ticking it makes it solid, a little brighter and only there.
        val ticked = render { it.setZincTaken(monday, true) }
        val diff = differences(untouched, ticked)
        assertTrue(diff.isNotEmpty() && diff.all { inCell(it, monday) }, "ticking changes only that cell (${diff.size} pixels)")
        assertTrue(diff.all { (x, y) -> brightness(ticked.getColor(x, y)) >= brightness(untouched.getColor(x, y)) }, "presumed to ticked: softer to solid")
    }

    @Test fun sayingNoToAPastZincDayTakesItsMarkOffAndOnlyThere() {
        val monday = LocalDate.of(2026, 10, 12)
        val untouched = render()
        val no = render { it.setZincSkipped(monday, true) }
        val diff = differences(untouched, no)
        val inMonday = diff.filter { inCell(it, monday) }
        assertTrue(inMonday.size > 20, "the mark goes (${inMonday.size} pixels)")
        assertTrue(inMonday.all { (x, y) -> brightness(no.getColor(x, y)) <= brightness(untouched.getColor(x, y)) }, "going dimmer: the mark is removed")
        // A dose said no to isn't a dose, so the plan moves on from there: the days after it change, the days before it don't.
        assertTrue(diff.any { !inCell(it, monday) }, "the later days are planned again")
        assertTrue(diff.none { it.second < cellTop(monday) - 3f }, "and nothing before that day changes")
    }

    @Test fun everyDaySchedulesEveryDayFromTodayOnAndLeavesTheDaysAlreadyGoneAlone() {
        val normal = render()
        val everyDay = render { it.setZincEveryDay(true) }
        val diff = differences(normal, everyDay)
        val firstRowOfToday = cellTop(LocalDate.of(2026, 10, 12)) - 3f
        assertTrue(diff.none { it.second < firstRowOfToday }, "nothing changes on the two weeks before the 12th (every day applies from today)")
        assertTrue(diff.count { it.second > cellTop(LocalDate.of(2026, 10, 19)) - 3f } > 400, "while the days to come all gain a mark")
        // The days of the 12th to the 14th were before today: switching to every day doesn't repaint them.
        assertEquals(0, diff.count { inCell(it, LocalDate.of(2026, 10, 12)) }, "the 12th is past")
        assertEquals(0, diff.count { inCell(it, LocalDate.of(2026, 10, 13)) }, "and so is the 13th")
    }
    @Test fun anAminoAcidDayLosesItsMarkOnceTickingHasBegunUntilItIsTicked() {
        // A past training day that is not a zinc day, so only its AA chip can change.
        val trained = DesktopStore(today).also { it.loadSampleData() }.sessions.map { it.date }.toSet()
        val day = trained.filter { it >= gridStart && it.isBefore(today) && it.dayOfWeek !in setOf(java.time.DayOfWeek.MONDAY, java.time.DayOfWeek.WEDNESDAY, java.time.DayOfWeek.FRIDAY) }.max()
        val file = { java.io.File(Files.createTempDirectory("a2-vm-file").toFile(), "vitamins.json").also {
            it.writeText("""{"trackedFrom":${gridStart.toEpochDay()},"tickFrom":${gridStart.toEpochDay()}}""")
        } }
        val before = render() // ticking begins today: every earlier day is history, still presumed taken
        val missed = renderWith(VitaminStore(file())) // ticking began at the start of the grid: nothing ticked, so not taken
        val ticked = renderWith(VitaminStore(file()).also { it.setMark(day, com.example.a2desktop.logic.Supplement.AMINO_ACIDS, true) })
        fun sum(bitmap: Bitmap): Long {
            var total = 0L
            for (x in cellLeft(day).toInt() until (cellLeft(day) + cellWidth).toInt()) for (y in cellTop(day).toInt() until (cellTop(day) + cellHeight).toInt()) total += brightness(bitmap.getColor(x, y))
            return total
        }
        assertTrue(sum(missed) < sum(before), "the AA chip is gone from $day: nobody ticked it (presumed ${sum(before)}, now ${sum(missed)})")
        assertTrue(sum(ticked) > sum(missed), "and it is back when ticked (${sum(ticked)} against ${sum(missed)})")
    }
}
