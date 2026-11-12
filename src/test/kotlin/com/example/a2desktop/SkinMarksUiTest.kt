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
import com.example.a2desktop.logic.EveningKind
import com.example.a2desktop.theme.A2Theme
import com.example.a2desktop.ui.A2App
import com.example.a2desktop.ui.LEFT_COLUMN_DP
import com.example.a2desktop.ui.Page
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import org.jetbrains.skia.Bitmap

/**
 * The skin-care letters on the Calendar's days, checked on the rendered pixels. "Today" is Thursday 15 October 2026, a
 * glycolic night. A letter is faded while it is only planned, solid once confirmed; and once confirming has begun, a past
 * evening that was never confirmed has no letter at all (before confirming began, an unticked past day still shows its
 * plan, a little softer — nothing is wiped).
 */
@OptIn(ExperimentalComposeUiApi::class)
class SkinMarksUiTest {
    private val width = 1360
    private val height = 860
    private val today = LocalDate.of(2026, 10, 15)
    private val gridStart = LocalDate.of(2026, 9, 28)

    private fun render(skin: SkinCareStore): Bitmap {
        val store = DesktopStore(today).also { it.loadSampleData() }
        val link = LinkController(store, LinkStorage(Files.createTempDirectory("a2-sm").toFile()), object : SnapshotSource {
            override fun fetch(address: LinkAddress, pairingKey: String) = LinkResult.Failed("no phone in tests")
        }, CoroutineScope(Job()), Dispatchers.Unconfined)
        val scene = ImageComposeScene(width = width, height = height, density = Density(1f)) {
            A2Theme { A2App(store, link, Page.CALENDAR, skinCare = skin, vitamins = VitaminStore(null)) }
        }
        try {
            var time = 0L
            repeat(6) { time += 50_000_000; scene.render(time) }
            return Bitmap.makeFromImage(scene.render(time + 50_000_000))
        } finally {
            scene.close()
        }
    }

    private fun skinFile(startedOn: LocalDate, confirmFrom: LocalDate?): SkinCareStore {
        val file = File(Files.createTempDirectory("a2-sm-skin").toFile(), "skincare.json")
        file.writeText("""{"startedOn":${startedOn.toEpochDay()}${confirmFrom?.let { ""","confirmFrom":${it.toEpochDay()}""" } ?: ""}}""")
        return SkinCareStore(file)
    }

    // The grid is the middle of three columns: 24 + LEFT_COLUMN_DP (Skin care, Supplements) + 24 in, and 24 + 410 (Habits, Tasks) + 24 from the right edge;
    // it starts 180 down (under the 64 px top bar and the month's heading) and ends 24 above the bottom.
    private val gridLeft = 24 + LEFT_COLUMN_DP + 24
    private val cellWidth = ((width - 24 - LEFT_COLUMN_DP - 24 - 24 - 410 - 24) - 6 * 6) / 7f
    private val cellHeight = ((height - 24f) - 180f - 5 * 6f) / 6f
    private fun cellLeft(date: LocalDate) = gridLeft + (date.toEpochDay() - gridStart.toEpochDay()).toInt().mod(7) * (cellWidth + 6f)
    private fun cellTop(date: LocalDate) = 180f + ((date.toEpochDay() - gridStart.toEpochDay()).toInt() / 7) * (cellHeight + 6f)

    private fun brightness(c: Int) = maxOf((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF)

    /** The pixels of [date]'s cell where [a] and [b] differ, as the sum of how much brighter [b] is than [a] (negative if darker). */
    private fun brighter(a: Bitmap, b: Bitmap, date: LocalDate): Pair<Int, Int> {
        var changed = 0
        var gain = 0
        for (x in cellLeft(date).toInt() until (cellLeft(date) + cellWidth).toInt()) {
            for (y in cellTop(date).toInt() until (cellTop(date) + cellHeight).toInt()) {
                if (a.getColor(x, y) != b.getColor(x, y)) {
                    changed++
                    gain += brightness(b.getColor(x, y)) - brightness(a.getColor(x, y))
                }
            }
        }
        return changed to gain
    }

    private fun gridDiffersOutside(a: Bitmap, b: Bitmap, date: LocalDate): Int {
        var n = 0
        for (x in gridLeft until 902) for (y in 180 until 836) { // the middle column: the cards with the ticks are to its left
            val inside = x >= cellLeft(date) - 1 && x <= cellLeft(date) + cellWidth + 1 && y >= cellTop(date) - 1 && y <= cellTop(date) + cellHeight + 1
            if (!inside && a.getColor(x, y) != b.getColor(x, y)) n++
        }
        return n
    }

    @Test fun confirmingTonightMakesItsLetterSolid() {
        val planned = render(skinFile(today, today))
        val confirmedSkin = skinFile(today, today).also { it.toggleEvening(today, EveningKind.GLYCOLIC) }
        val confirmed = render(confirmedSkin)
        val (changed, gain) = brighter(planned, confirmed, today)
        assertTrue(changed > 20, "tonight's letter changes ($changed pixels)")
        assertTrue(gain > 0, "and gets brighter: faded to solid")
        assertTrue(gridDiffersOutside(planned, confirmed, today) == 0, "nothing else on the grid moves — the plan already had this night")
    }

    @Test fun sayingNoToTonightTakesItsLetterOffAtOnceAndTheNightsAfterAreReplanned() {
        val planned = render(skinFile(today, today))
        val refused = render(skinFile(today, today).also { it.setEveningSkipped(today, true) })
        val (changed, gain) = brighter(planned, refused, today)
        assertTrue(changed > 20, "tonight's letter changes ($changed pixels)")
        assertTrue(gain < 0, "and goes: it is no longer on the Calendar, even though today hasn't passed")
        val tomorrow = brighter(planned, refused, today.plusDays(1))
        assertTrue(tomorrow.first > 5 && tomorrow.second > 0, "tomorrow was a blank rest night after tonight's glycolic; now it has a letter (${tomorrow.first} pixels)")
    }

    @Test fun anEveningNobodyConfirmedHasNoLetterOnceConfirmingHasBegunButStillDoesBefore() {
        val before = render(skinFile(LocalDate.of(2026, 10, 5), null)) // confirming never began: unticked days are taken as followed
        val after = render(skinFile(LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 6))) // confirming began on the 6th
        var daysThatLostTheirLetter = 0
        for (offset in 1..8) { // the 7th to the 14th: past, never confirmed
            val day = LocalDate.of(2026, 10, 6).plusDays(offset.toLong())
            val (changed, gain) = brighter(before, after, day)
            if (changed > 5 && gain < 0) daysThatLostTheirLetter++
        }
        assertTrue(daysThatLostTheirLetter >= 4, "past evenings nobody confirmed have dropped off ($daysThatLostTheirLetter of the 8 days lost a letter; the rest were rest nights)")
        // The 6th itself — the first day of confirming — is also dropped, while the 5th (before it) keeps its letter.
        val fifth = brighter(before, after, LocalDate.of(2026, 10, 5))
        assertTrue(fifth.first == 0, "the 5th, before confirming began, still shows what the plan said (${fifth.first} pixels differ)")
    }
}
