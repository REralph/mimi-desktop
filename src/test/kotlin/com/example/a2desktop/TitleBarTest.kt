package com.example.a2desktop

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import com.example.a2desktop.theme.A2Theme
import com.example.a2desktop.ui.ResizeEdge
import com.example.a2desktop.ui.TitleBar
import com.example.a2desktop.ui.resizedBounds
import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.jetbrains.skia.EncodedImageFormat

@OptIn(ExperimentalComposeUiApi::class)
class TitleBarTest {
    private val width = 1000
    private val height = 36

    private class Counts {
        var minimise = 0
        var toggle = 0
        var close = 0
    }

    private fun scene(counts: Counts, maximised: Boolean = false) = ImageComposeScene(width = width, height = height, density = Density(1f)) {
        A2Theme {
            TitleBar(
                title = "a2 Desktop",
                maximised = maximised,
                onMinimise = { counts.minimise++ },
                onToggleMaximise = { counts.toggle++ },
                onClose = { counts.close++ },
            )
        }
    }

    private fun click(scene: ImageComposeScene, x: Float, y: Float, time: Long) {
        val at = Offset(x, y)
        scene.sendPointerEvent(PointerEventType.Move, at)
        scene.sendPointerEvent(PointerEventType.Press, at, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
        scene.render(time)
        scene.sendPointerEvent(PointerEventType.Release, at, button = PointerButton.Primary)
        scene.render(time + 16_000_000)
    }

    // Buttons are 46 wide, flush right: close is the last, then maximise, then minimise.
    private val closeX = width - 23f
    private val maximiseX = width - 69f
    private val minimiseX = width - 115f

    @Test fun eachRedButtonDoesItsOwnJobAndOnlyThat() {
        val counts = Counts()
        val s = scene(counts)
        try {
            s.render(0)
            click(s, minimiseX, 18f, 100_000_000)
            assertEquals(Triple(1, 0, 0), Triple(counts.minimise, counts.toggle, counts.close))
            click(s, maximiseX, 18f, 300_000_000)
            assertEquals(Triple(1, 1, 0), Triple(counts.minimise, counts.toggle, counts.close))
            click(s, closeX, 18f, 500_000_000)
            assertEquals(Triple(1, 1, 1), Triple(counts.minimise, counts.toggle, counts.close))
        } finally {
            s.close()
        }
    }

    @Test fun theTitleAreaDoesNothingOnASingleClickAndTogglesMaximiseOnADoubleClick() {
        val counts = Counts()
        val s = scene(counts)
        try {
            s.render(0)
            click(s, 300f, 18f, 100_000_000)
            assertEquals(0, counts.toggle + counts.minimise + counts.close)
            // two quick clicks in the title area
            click(s, 300f, 18f, 400_000_000)
            Thread.sleep(120) // a human double-click: quick, but never faster than ~40 ms apart
            click(s, 300f, 18f, 600_000_000)
            assertEquals(1, counts.toggle)
            assertEquals(0, counts.minimise + counts.close)
        } finally {
            s.close()
        }
    }

    private fun pixel(png: ByteArray, x: Int, y: Int): Int {
        val image = javax.imageio.ImageIO.read(png.inputStream())
        return image.getRGB(x, y)
    }

    private fun render(counts: Counts, hoverX: Float? = null, maximised: Boolean = false): ByteArray {
        val s = scene(counts, maximised)
        try {
            s.render(0)
            hoverX?.let { s.sendPointerEvent(PointerEventType.Move, Offset(it, 18f)); s.render(50_000_000) }
            return s.render(100_000_000).encodeToData(EncodedImageFormat.PNG)!!.bytes
        } finally {
            s.close()
        }
    }

    @Test fun theBarIsDarkAndTheButtonsAreRed() {
        val png = render(Counts())
        fun channels(argb: Int) = Triple((argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF)
        // Somewhere in the middle of the bar, away from text and buttons: nearly black.
        val (r, g, b) = channels(pixel(png, 600, 10))
        assertTrue(r < 30 && g < 30 && b < 30, "bar background should be near-black, was $r,$g,$b")
        // The close cross is drawn in the red: find the reddest pixel inside the close button.
        val image = javax.imageio.ImageIO.read(png.inputStream())
        var found = false
        for (x in (width - 46)..(width - 1)) for (y in 0 until height) {
            val (pr, pg, pb) = channels(image.getRGB(x, y))
            if (pr > 200 && pg < 120 && pb < 120) found = true
        }
        assertTrue(found, "the close button's glyph should be red")
    }

    @Test fun hoveringCloseTurnsItSolidRedAndHoveringMinimiseGivesASoftWash() {
        val image = { bytes: ByteArray -> javax.imageio.ImageIO.read(bytes.inputStream()) }
        fun channels(argb: Int) = Triple((argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF)
        val closeHover = image(render(Counts(), hoverX = closeX))
        val (cr, cg, cb) = channels(closeHover.getRGB(width - 44, 3)) // a corner of the button, away from the white cross
        assertTrue(cr > 190 && cg < 90 && cb < 90, "hovered close should be solid red, was $cr,$cg,$cb")
        val minHover = image(render(Counts(), hoverX = minimiseX))
        val (mr, mg, mb) = channels(minHover.getRGB(width - 113, 3))
        assertTrue(mr > mg + 8 && mr > mb + 8 && mr < 120, "hovered minimise should be a soft red wash, was $mr,$mg,$mb")
    }

    // ---- Resizing from the edges ----

    private val start = Rectangle(100, 100, 1000, 700)

    @Test fun draggingARightOrBottomEdgeGrowsTheWindowAndLeavesTheOtherSidesAlone() {
        assertEquals(Rectangle(100, 100, 1150, 700), resizedBounds(start, ResizeEdge.E, 150, 999, 600, 400))
        assertEquals(Rectangle(100, 100, 1000, 640), resizedBounds(start, ResizeEdge.S, 999, -60, 600, 400))
        assertEquals(Rectangle(100, 100, 1050, 730), resizedBounds(start, ResizeEdge.SE, 50, 30, 600, 400))
    }

    @Test fun draggingALeftOrTopEdgeMovesThatSideAndKeepsTheOppositeSideFixed() {
        val west = resizedBounds(start, ResizeEdge.W, -80, 0, 600, 400)
        assertEquals(Rectangle(20, 100, 1080, 700), west)
        assertEquals(start.x + start.width, west.x + west.width) // right side did not move
        val north = resizedBounds(start, ResizeEdge.N, 0, -50, 600, 400)
        assertEquals(Rectangle(100, 50, 1000, 750), north)
        assertEquals(start.y + start.height, north.y + north.height)
        assertEquals(Rectangle(70, 60, 1030, 740), resizedBounds(start, ResizeEdge.NW, -30, -40, 600, 400))
    }

    @Test fun theWindowStopsAtItsMinimumSizeInsteadOfSlidingAway() {
        val shrunkFromLeft = resizedBounds(start, ResizeEdge.W, 900, 0, 600, 400)
        assertEquals(600, shrunkFromLeft.width)
        assertEquals(start.x + start.width, shrunkFromLeft.x + shrunkFromLeft.width) // still anchored on the right
        val shrunkFromTop = resizedBounds(start, ResizeEdge.N, 0, 900, 600, 400)
        assertEquals(400, shrunkFromTop.height)
        assertEquals(start.y + start.height, shrunkFromTop.y + shrunkFromTop.height)
        assertEquals(Rectangle(500, 400, 600, 400), resizedBounds(start, ResizeEdge.NW, 900, 900, 600, 400))
    }
}
