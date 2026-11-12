package com.example.a2desktop

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import com.example.a2desktop.data.DesktopStore
import com.example.a2desktop.link.LinkController
import com.example.a2desktop.link.LinkResult
import com.example.a2desktop.link.LinkStorage
import com.example.a2desktop.logic.fiveYearMarkPassed
import com.example.a2desktop.logic.fiveYearMarksPassed
import com.example.a2desktop.logic.isFiveYearMark
import com.example.a2desktop.logic.lifeProgress
import com.example.a2desktop.theme.A2Theme
import com.example.a2desktop.ui.A2App
import com.example.a2desktop.ui.Page
import java.nio.file.Files
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import org.jetbrains.skia.EncodedImageFormat

/** The green five-year marks (age 5, 10 ... 75) on the desktop's Life page, same as the phone's page 0.21. */
@OptIn(ExperimentalComposeUiApi::class)
class LifeMarksTest {
    private fun d(y: Int, m: Int, day: Int) = LocalDate.of(y, m, day)

    @Test fun theMarksAreAges5To75InStepsOfFive() {
        val marked = (0..80).filter { isFiveYearMark(it) }
        assertEquals(listOf(5, 10, 15, 20, 25, 30, 35, 40, 45, 50, 55, 60, 65, 70, 75), marked)
    }

    @Test fun aMarkIsPassedExactlyWhenThatManyWholeYearsAreDone() {
        val dob = d(1998, 3, 12)
        val exactly28 = lifeProgress(dob, d(2026, 3, 12))
        assertTrue(fiveYearMarkPassed(25, exactly28))
        assertTrue(!fiveYearMarkPassed(30, exactly28))
        assertEquals(5, fiveYearMarksPassed(exactly28))
        assertEquals(5, fiveYearMarksPassed(lifeProgress(dob, d(2028, 3, 11)))) // the day before the 30th birthday
        assertEquals(6, fiveYearMarksPassed(lifeProgress(dob, d(2028, 3, 12)))) // the 30th birthday itself
    }

    @Test fun theCountMatchesTheFilledCirclesAtTheEdges() {
        val dob = d(2000, 1, 1)
        assertEquals(0, fiveYearMarksPassed(lifeProgress(dob, d(2000, 1, 1))))
        assertEquals(0, fiveYearMarksPassed(lifeProgress(dob, d(2004, 12, 31))))
        assertEquals(1, fiveYearMarksPassed(lifeProgress(dob, d(2005, 1, 1))))
        assertEquals(15, fiveYearMarksPassed(lifeProgress(dob, d(2075, 1, 1))))
        assertEquals(15, fiveYearMarksPassed(lifeProgress(dob, d(2090, 6, 1)))) // over 80: all of them, never more
    }

    @Test fun theGridDrawsGreenLinesSolidForReachedMarksAndFadedForTheOnesAhead() {
        // 28 years 4 months old: the marks at 5, 10, 15, 20, 25 are reached (5), the other 10 are ahead.
        val store = DesktopStore().also {
            it.loadSampleData()
            it.enterDateOfBirth(it.today.minusYears(28).minusMonths(4).minusDays(8))
        }
        val link = LinkController(store, LinkStorage(Files.createTempDirectory("life-marks").toFile()), { _, _ -> LinkResult.Failed("x") }, CoroutineScope(Job()), Dispatchers.Unconfined)
        val scene = ImageComposeScene(width = 1324, height = 824, density = Density(1f)) { A2Theme { A2App(store, link, Page.LIFE) } }
        try {
            scene.render(0)
            val png = scene.render(100_000_000).encodeToData(EncodedImageFormat.PNG)!!.bytes
            val image = javax.imageio.ImageIO.read(png.inputStream())
            var solidRows = 0
            var fadedRows = 0
            var otherGreenRows = 0
            for (y in 240 until image.height - 40) {
                var solid = 0
                var faded = 0
                for (x in 0 until image.width) {
                    val c = image.getRGB(x, y)
                    val r = (c shr 16) and 0xFF
                    val g = (c shr 8) and 0xFF
                    val b = c and 0xFF
                    if (g > 200 && r < 120 && b < 170) solid++               // the solid green (0x4ADE80)
                    else if (g in 70..130 && g > r + 25 && g > b + 10) faded++ // the same green at 45%
                }
                // A mark's line is a long unbroken run of green across the label and the 12 months.
                if (solid > 250) solidRows++
                if (faded > 250) fadedRows++
                if (solid in 1..249 && faded in 1..249) otherGreenRows++
            }
            // Each line is drawn 1.5 dp thick, so a line covers 1-2 pixel rows (+ anti-aliasing): 5 solid
            // lines and 10 faded ones, each seen once or twice.
            assertTrue(solidRows in 5..15, "solid green line rows: $solidRows")
            assertTrue(fadedRows in 10..30, "faded green line rows: $fadedRows")
            assertTrue(fadedRows > solidRows, "there are more marks ahead ($fadedRows rows) than behind ($solidRows rows)")
        } finally {
            scene.close()
        }
    }
}
