package com.example.a2desktop

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import com.example.a2desktop.data.DesktopStore
import com.example.a2desktop.link.LinkController
import com.example.a2desktop.link.LinkResult
import com.example.a2desktop.link.LinkStorage
import com.example.a2desktop.theme.A2Theme
import com.example.a2desktop.ui.A2App
import com.example.a2desktop.ui.LIFE_MIN_ZOOM
import com.example.a2desktop.ui.Page
import com.example.a2desktop.ui.lifeGridZoom
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import org.jetbrains.skia.EncodedImageFormat

/** The Life page's grid zooms with the window: bigger window, bigger circles; smaller, smaller. */
@OptIn(ExperimentalComposeUiApi::class)
class LifeZoomTest {
    // The grid's natural size is 736 x 520 dp (two blocks of 40 rows).
    @Test fun theNaturalSizeIsZoomOne() = assertEquals(1f, lifeGridZoom(736f, 520f), 0.0001f)

    @Test fun aBiggerSpaceZoomsInAndASmallerOneZoomsOut() {
        assertEquals(2f, lifeGridZoom(1472f, 1040f), 0.0001f)
        assertEquals(0.5f, lifeGridZoom(368f, 260f), 0.0001f)
    }

    @Test fun whicheverOfWidthAndHeightIsTighterDecidesSoNothingOverflows() {
        assertEquals(0.5f, lifeGridZoom(368f, 5000f), 0.0001f) // narrow and tall: width decides
        assertEquals(0.5f, lifeGridZoom(5000f, 260f), 0.0001f) // wide and short: height decides
    }

    @Test fun itNeverZoomsToAbsurdSizes() {
        assertEquals(4f, lifeGridZoom(100_000f, 100_000f), 0.0001f)
        assertTrue(LIFE_MIN_ZOOM in 0.5f..1f)
    }

    /** Height (px) between the first and the last visible grid pixel, below the header and stat tiles. */
    private fun gridExtent(width: Int, height: Int): Int {
        val store = DesktopStore().also {
            it.loadSampleData()
            it.enterDateOfBirth(it.today.minusYears(28).minusMonths(4).minusDays(8))
        }
        val link = LinkController(store, LinkStorage(Files.createTempDirectory("life-zoom").toFile()), { _, _ -> LinkResult.Failed("x") }, CoroutineScope(Job()), Dispatchers.Unconfined)
        val scene = ImageComposeScene(width = width, height = height, density = Density(1f)) { A2Theme { A2App(store, link, Page.LIFE) } }
        try {
            scene.render(0)
            val png = scene.render(100_000_000).encodeToData(EncodedImageFormat.PNG)!!.bytes
            val image = javax.imageio.ImageIO.read(png.inputStream())
            var first = -1
            var last = -1
            for (y in 240 until height - 40) { // below the "click a circle" line, above the status bar
                var lit = false
                for (x in (width * 0.2).toInt() until (width * 0.8).toInt()) {
                    val c = image.getRGB(x, y)
                    if (((c shr 16) and 0xFF) + ((c shr 8) and 0xFF) + (c and 0xFF) > 270) { lit = true; break }
                }
                if (lit) { if (first < 0) first = y; last = y }
            }
            assertTrue(first >= 0, "no grid drawn at ${width}x$height")
            return last - first
        } finally {
            scene.close()
        }
    }

    @Test fun theDrawnGridGrowsWithTheWindowAndShrinksWithIt() {
        val small = gridExtent(1060, 664)   // the smallest window the app allows
        val normal = gridExtent(1324, 824)  // the default window
        val big = gridExtent(2560, 1366)    // maximised on a 1440p screen
        assertTrue(small < normal && normal < big, "extents $small < $normal < $big")
        assertTrue(big > normal * 1.5, "a window twice the size should draw a clearly bigger grid ($normal -> $big)")
        // And it really fills the space: the grid takes most of the room that is left under the stat tiles.
        assertTrue(big > (1366 - 240 - 40) * 0.85, "grid only reaches $big px in a 1366 px tall window")
    }
}
