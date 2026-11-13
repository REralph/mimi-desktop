package com.example.a2desktop

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import com.example.a2desktop.data.DesktopStore
import com.example.a2desktop.link.LinkAddress
import com.example.a2desktop.link.LinkController
import com.example.a2desktop.link.LinkResult
import com.example.a2desktop.link.LinkStorage
import com.example.a2desktop.link.SnapshotSource
import com.example.a2desktop.logic.ClipboardAccess
import com.example.a2desktop.logic.ClipboardRead
import com.example.a2desktop.theme.A2Theme
import com.example.a2desktop.ui.A2App
import com.example.a2desktop.ui.DevGroup
import com.example.a2desktop.ui.DevMode
import com.example.a2desktop.ui.Page
import com.example.a2desktop.ui.devClipboardText
import com.example.a2desktop.ui.placeBadges
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Image

/**
 * Developer mode: numbered labels on every group of information. What matters most is that turning it on
 * changes nothing underneath — the labels are a separate layer — so that is checked pixel by pixel.
 */
@OptIn(ExperimentalComposeUiApi::class)
class DevModeTest {
    private class FakeClipboard : ClipboardAccess {
        val writes = mutableListOf<String>()
        override fun read() = ClipboardRead.Empty
        override fun write(text: String): Boolean {
            writes += text
            return true
        }
    }

    private class NoPhone : SnapshotSource {
        override fun fetch(address: LinkAddress, pairingKey: String) = LinkResult.Failed("no phone in tests")
    }

    private class Rig(val scene: ImageComposeScene, val dev: DevMode, val clipboard: FakeClipboard) {
        var time = 0L

        /** A few frames, so the position reports, the labels and the effects behind them have all settled. */
        fun settle(): Image {
            var image: Image? = null
            repeat(6) {
                time += 50_000_000
                image = scene.render(time)
            }
            return image!!
        }

        fun click(at: Offset) {
            time += 100_000_000
            scene.sendPointerEvent(PointerEventType.Move, at)
            scene.sendPointerEvent(PointerEventType.Press, at, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
            scene.render(time)
            scene.sendPointerEvent(PointerEventType.Release, at, button = PointerButton.Primary)
            scene.render(time + 16_000_000)
            time += 16_000_000
        }
    }

    private fun rig(page: Page, devOn: Boolean, height: Int = 860): Rig {
        val store = DesktopStore().also {
            it.loadSampleData()
            it.enterDateOfBirth(it.today.minusYears(28).minusMonths(4).minusDays(8))
        }
        val dir = Files.createTempDirectory("a2-dev").toFile()
        val link = LinkController(store, LinkStorage(dir), NoPhone(), CoroutineScope(Job()), Dispatchers.Unconfined)
        val dev = DevMode().also { it.enabled = devOn }
        val clipboard = FakeClipboard()
        val scene = ImageComposeScene(width = 1360, height = height, density = Density(1f)) {
            A2Theme { A2App(store, link, page, clipboard, dev = dev) }
        }
        return Rig(scene, dev, clipboard)
    }

    // ---- the numbers ----

    @Test fun aLabelCopiesItsNumberItsPageAndItsName() {
        assertEquals("PC1.5 — Calendar › Selected day card", devClipboardText("1.5", "Selected day card"))
        assertEquals("PC1.5.2 — Calendar › Morning (AM) line", devClipboardText("1.5.2", "Morning (AM) line"))
        assertEquals("PC4.2 — Skin care › Your products", devClipboardText("4.2", "Your products"))
        assertEquals("PC0.1 — App › Navigation bar", devClipboardText("0.1", "Navigation bar"))
        assertEquals("PC6.2 — Phone link › Connection card", devClipboardText("6.2", "Connection card"))
    }

    @Test fun everyNumberInTheSourceIsUniqueAndKeepsOneName() {
        val root = File("src/main/kotlin/com/example/a2desktop/ui")
        val pattern = Regex("""devRef\("(\d+(?:\.\d+)+)",\s*"([^"]+)"[^)]*\)""")
        val seen = mutableMapOf<String, Pair<String, String>>() // id -> (name, file)
        var count = 0
        root.listFiles { f -> f.extension == "kt" }!!.forEach { file ->
            pattern.findAll(file.readText()).forEach { m ->
                count++
                val (id, name) = m.destructured
                val earlier = seen[id]
                if (earlier != null) {
                    assertEquals(earlier.first, name, "$id is used for two different names")
                    assertEquals(earlier.second, file.name, "$id is used in two different files")
                } else {
                    seen[id] = name to file.name
                }
            }
        }
        assertTrue(count >= 40, "expected the groups to be marked, found $count")
        // Every number starts with its page's digit, in the order of the pages in the top bar.
        val pageDigits = setOf("0", "1", "2", "3", "4", "5", "6", "7", "8")
        seen.keys.forEach { assertTrue(it.substringBefore('.') in pageDigits, "$it is not on a known page") }
    }

    // ---- where the labels go ----

    private val area = Rect(Offset.Zero, Size(1000f, 800f))

    private fun group(id: String, left: Float, top: Float, width: Float = 200f, height: Float = 100f) =
        DevGroup(id, "Group $id", Rect(Offset(left, top), Size(width, height)))

    @Test fun aLabelSitsJustAboveItsGroupAndNotOnIt() {
        val placed = placeBadges(listOf(group("1.5", 300f, 200f)), area, 16f, { 120f }).single()
        assertTrue(placed.y + placed.height <= 200f, "ends at or above the group's top edge")
        assertEquals(306f, placed.x, "just inside the group's left edge")
    }

    @Test fun labelsThatWouldLandOnEachOtherAreMovedApartAndStayInsideTheWindow() {
        val groups = listOf(group("1.5", 100f, 200f), group("1.5.1", 100f, 200f), group("1.5.2", 100f, 200f), group("2.1", 900f, 2f))
        val placed = placeBadges(groups, area, 16f, { 150f })
        for (i in placed.indices) for (j in i + 1 until placed.size) {
            val a = Rect(Offset(placed[i].x, placed[i].y), Size(placed[i].width, placed[i].height))
            val b = Rect(Offset(placed[j].x, placed[j].y), Size(placed[j].width, placed[j].height))
            assertFalse(a.overlaps(b), "${placed[i].id} overlaps ${placed[j].id}")
        }
        placed.forEach {
            assertTrue(it.x >= 0f && it.x + it.width <= 1000f, "${it.id} is inside the window sideways")
            assertTrue(it.y >= 0f && it.y + it.height <= 800f, "${it.id} is inside the window vertically")
        }
    }

    @Test fun aPartInsideABlockGetsASmallNumberOnlyLabelAtTheRightEndOfItsTopEdge() {
        val block = group("1.4", 100f, 200f, width = 400f, height = 300f)
        val part = group("1.4.2", 100f, 260f, width = 400f, height = 40f)
        val placed = placeBadges(listOf(block, part), area, 16f, { 40f }).associateBy { it.id }
        val label = placed.getValue("1.4.2")
        assertEquals("PC1.4.2", label.text)
        assertTrue(label.fullText.contains("Group 1.4.2"), "pointing at it says what it is")
        assertEquals(498f, label.x + label.width, "ends at the part's right edge")
        assertTrue(label.y < 260f + 16f, "at the top edge of the part, not down in its text")
        assertEquals("PC1.4 Group 1.4", placed.getValue("1.4").text, "a whole block shows number and name")
    }

    @Test fun aLabelDoesNotLandOnANeighbouringGroupWhenThereIsAnotherSpot() {
        // 1.3 sits right under 1.2 with a 3-pixel gap: "just above 1.3" would be on top of 1.2's content.
        val above = group("1.2", 100f, 100f, width = 600f, height = 40f)
        val below = group("1.3", 100f, 143f, width = 600f, height = 300f)
        val placed = placeBadges(listOf(above, below), area, 16f, { 120f }).associateBy { it.id }
        val r = placed.getValue("1.3")
        val rect = Rect(Offset(r.x, r.y), Size(r.width, r.height))
        assertFalse(rect.overlaps(above.bounds), "1.3's label is on top of the neighbouring group 1.2")
    }

    @Test fun numbersAreOrderedAsNumbersNotAsText() {
        val placed = placeBadges(listOf(group("1.10", 0f, 100f), group("1.2", 400f, 100f), group("1.5.1", 700f, 100f), group("1.5", 200f, 100f)), area, 16f, { 50f })
        assertEquals(listOf("1.2", "1.5", "1.5.1", "1.10"), placed.map { it.id })
    }

    // ---- on the real screens ----

    private val expected = mapOf(
        Page.CALENDAR to setOf("0.1", "0.2", "1.1", "1.1.1", "1.2", "1.4", "1.4.3", "1.4.7", "1.5", "1.6", "1.8", "1.8.1"),
        Page.GYM to setOf("0.1", "0.2", "2.1", "2.2", "2.3", "2.4"),
        Page.DREAMS to setOf("0.1", "0.2", "3.1", "3.2", "3.3", "3.4", "3.5", "3.6"),
        Page.SKIN_CARE to setOf("0.1", "0.2", "4.1", "4.2", "4.3", "4.4", "4.5", "4.6"),
        Page.LIFE to setOf("0.1", "0.2", "5.1", "5.3", "5.4", "5.5"),
        Page.PHONE to setOf("0.1", "0.2", "6.1", "6.2", "6.3"),
        Page.JOBS to setOf("0.1", "0.2", "7.1", "7.1.1", "7.2", "7.8"),
    )

    @Test fun everyPageLabelsItsGroupsWhenDeveloperModeIsOn() {
        expected.forEach { (page, ids) ->
            val r = rig(page, devOn = true, height = 3000) // tall, so nothing is scrolled out of view
            try {
                r.settle()
                val missing = ids - r.dev.groups.keys
                assertTrue(missing.isEmpty(), "${page.name}: no label for $missing (have ${r.dev.groups.keys.sorted()})")
                ids.forEach { assertNotNull(r.dev.badgeBounds[it], "${page.name}: $it has no drawn label") }
            } finally {
                r.scene.close()
            }
        }
    }

    @Test fun theStatusBarAndTheCalendarsLegendAreGone() {
        // 0.3 (the bar along the bottom) and 1.3 (the key under the month grid) were removed; their numbers are not reused.
        Page.entries.forEach { page ->
            val r = rig(page, devOn = true, height = 3000)
            try {
                r.settle()
                assertTrue("0.3" !in r.dev.groups.keys, "${page.name}: the status bar is back")
                assertTrue("1.3" !in r.dev.groups.keys, "${page.name}: the legend is back")
            } finally {
                r.scene.close()
            }
        }
    }

    @Test fun theCalendarHasThreeColumnsSkinCareAndSupplementsOnTheLeftTheGridInTheMiddleHabitsAndTasksOnTheRight() {
        val r = rig(Page.CALENDAR, devOn = true, height = 3000)
        try {
            r.settle()
            val bounds = { id: String -> r.dev.groups.getValue(id).bounds }
            // Left to right: the Skin care and Supplements cards, the month grid, the Habits and Tasks cards.
            assertTrue(bounds("1.5").right <= bounds("1.2").left && bounds("1.8").right <= bounds("1.2").left, "Skin care and Supplements are left of the month grid")
            assertTrue(bounds("1.2").right <= bounds("1.6").left && bounds("1.2").right <= bounds("1.4").left, "the month grid is left of the Habits and Tasks cards")
            // Top to bottom inside each side column.
            assertTrue(bounds("1.5").top < bounds("1.8").top, "Skin care above Supplements")
            assertTrue(bounds("1.6").top < bounds("1.4").top, "Habits above Tasks")
            val bar = bounds("0.1")
            assertTrue(bounds("1.5").top > bar.bottom && bounds("1.6").top > bar.bottom, "both side columns start under the top bar")
            assertTrue(bar.top < 1f && bar.left < 1f && bar.width >= 1359f, "the navigation bar runs across the very top (${bar})")
            assertTrue("1.7" !in r.dev.groups.keys, "the Upcoming card is gone: its list is part of the Tasks card (1.4.7); its number is not reused")
        } finally {
            r.scene.close()
        }
    }

    @Test fun theTasksCardIsTheDateTheDaysTableTheHabitEmojisAndThenTheUpcomingList() {
        val r = rig(Page.CALENDAR, devOn = true, height = 3000)
        try {
            r.settle()
            // 1.4.1 (streak line), 1.4.2 (work times line) and 1.4.4 (the old Events list) were removed; their numbers are not reused. The
            // day's events and tasks are back as a table, 1.4.6, and the Upcoming list (the old card 1.7) is the part 1.4.7.
            assertTrue(listOf("1.4.1", "1.4.2", "1.4.4").none { it in r.dev.groups.keys }, "a removed part of the Tasks card is back")
            // The habits are one row of emoji-only buttons, 34 dp square: named chips (names, or the old caption) would be wider and wrap.
            val buttons = r.dev.groups.getValue("1.4.3").bounds
            assertEquals(34f, buttons.height, 0.5f, "the habits are one row of emoji buttons")
            val card = r.dev.groups.getValue("1.4").bounds
            val table = r.dev.groups.getValue("1.4.6").bounds
            val upcoming = r.dev.groups.getValue("1.4.7").bounds
            // Top to bottom: the "Tasks" header and the date, the table (two things are on today), the habits, the Upcoming list.
            assertTrue(table.top > card.top + 40f && table.top < card.top + 110f, "the table is under the header and the date (${table.top - card.top} dp down)")
            assertTrue(buttons.top > table.bottom, "the habits are under the table")
            assertTrue(upcoming.top > buttons.bottom, "the Upcoming list is under the habits")
            assertTrue(upcoming.bottom <= card.bottom && card.bottom - upcoming.bottom < 30f, "nothing under the Upcoming list but the card's own padding (${card.bottom - upcoming.bottom} dp)")
            assertTrue("1.4.6.1" in r.dev.groups && "1.4.6.2" in r.dev.groups && "1.4.6.3" !in r.dev.groups, "today's two things are the table's two rows")
            assertTrue("1.4.7.1" in r.dev.groups, "and the Upcoming list has its rows")
        } finally {
            r.scene.close()
        }
    }

    @Test fun theDailyCheckInAndTheOldCheckInCardAreGoneAndTheGridFillsTheColumn() {
        val r = rig(Page.CALENDAR, devOn = true)
        try {
            r.settle()
            // PC1.13 (the Daily Check-in, with its parts) and PC1.12 (the Check-in card before it) were removed; their numbers are not reused.
            assertTrue(r.dev.groups.keys.none { it == "1.12" || it.startsWith("1.12.") || it == "1.13" || it.startsWith("1.13.") }, "a Check-in is back: ${r.dev.groups.keys.sorted()}")
            val grid = r.dev.groups.getValue("1.2").bounds
            assertEquals(836f, grid.bottom, 1f, "nothing sits under the month grid, so it runs to the page's bottom margin")
        } finally {
            r.scene.close()
        }
    }

    @Test fun withDeveloperModeOffNothingIsReportedOrDrawn() {
        val r = rig(Page.CALENDAR, devOn = false)
        try {
            r.settle()
            assertTrue(r.dev.groups.isEmpty())
            assertTrue(r.dev.badgeBounds.isEmpty())
        } finally {
            r.scene.close()
        }
    }

    private fun pixels(image: Image): Bitmap = Bitmap.makeFromImage(image)

    @Test fun turningItOnChangesNothingUnderneathExceptTheLabelsAndTheSwitchItself() {
        for (page in listOf(Page.CALENDAR, Page.SKIN_CARE, Page.GYM, Page.DREAMS)) {
            val off = rig(page, devOn = false)
            val on = rig(page, devOn = true)
            try {
                val before = pixels(off.settle())
                val after = pixels(on.settle())
                assertEquals(before.width, after.width)
                // The only places allowed to differ: the drawn labels, and the switch's own spot in the top bar.
                val allowed = on.dev.badgeBounds.values.map { it.inflate(3f) } + listOf(on.dev.groups.getValue("0.2").bounds.inflate(3f))
                var outside = 0
                var firstBad: String? = null
                for (y in 0 until before.height) for (x in 0 until before.width) {
                    if (before.getColor(x, y) != after.getColor(x, y) && allowed.none { it.contains(Offset(x.toFloat(), y.toFloat())) }) {
                        outside++
                        if (firstBad == null) firstBad = "($x,$y)"
                    }
                }
                assertEquals(0, outside, "${page.name}: $outside pixels changed outside the labels, first at $firstBad")
            } finally {
                off.scene.close()
                on.scene.close()
            }
        }
    }

    @Test fun clickingALabelCopiesItsReference() {
        val r = rig(Page.CALENDAR, devOn = true)
        try {
            r.settle()
            val badge = r.dev.badgeBounds.getValue("1.6") // the Habits card, now first in the right-hand column (the Skin care card is below the fold)
            r.click(badge.center)
            assertEquals(listOf("PC1.6 — Calendar › Habits card"), r.clipboard.writes)
            val another = r.dev.badgeBounds.getValue("1.2")
            r.click(another.center)
            assertEquals("PC1.2 — Calendar › Month grid", r.clipboard.writes.last())
        } finally {
            r.scene.close()
        }
    }

    @Test fun theSwitchAboveThePhoneTurnsItOnAndOffAndCopyAllListsEveryLabelInOrder() {
        val r = rig(Page.CALENDAR, devOn = true, height = 3000) // tall enough that the cards under the Habits card are all on screen
        try {
            r.settle()
            val toggle = r.dev.groups.getValue("0.2").bounds
            // "Copy all" is the button beside the switch (to its right) while developer mode is on.
            r.click(Offset(toggle.right - 36f, toggle.center.y))
            val list = r.clipboard.writes.last().lines()
            assertTrue(list.first().startsWith("PC0.1 — App › Navigation bar"), list.first())
            assertTrue("PC1.5 — Calendar › Skin care card" in list)
            assertTrue("PC1.6 — Calendar › Habits card" in list)
            val numbers = list.map { it.removePrefix("PC").substringBefore(" ").split('.').map(String::toInt) }
            assertEquals(numbers.sortedWith { a, b -> compareLists(a, b) }, numbers, "listed in number order")

            // The switch itself: off, then on again.
            r.click(Offset(toggle.left + 26f, toggle.top + 12f)) // the switch, at the left of the group
            r.settle()
            assertFalse(r.dev.enabled, "the switch turns it off")
            assertTrue(r.dev.groups.isEmpty())
            r.click(Offset(toggle.left + 26f, toggle.top + 12f))
            r.settle()
            assertTrue(r.dev.enabled, "and on again")
            assertTrue(r.dev.groups.isNotEmpty())
        } finally {
            r.scene.close()
        }
    }

    private fun compareLists(a: List<Int>, b: List<Int>): Int {
        for (i in 0 until minOf(a.size, b.size)) if (a[i] != b[i]) return a[i].compareTo(b[i])
        return a.size.compareTo(b.size)
    }
}
