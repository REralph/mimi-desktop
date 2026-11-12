package com.example.a2desktop.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import java.awt.Cursor
import java.awt.MouseInfo
import java.awt.Point
import java.awt.Rectangle
import java.awt.Window

/**
 * Which sides of the window a resize handle moves. The window has no native frame any more (the
 * bar is the app's own), so the edges and corners are thin invisible handles that do the dragging.
 */
enum class ResizeEdge(val left: Boolean, val right: Boolean, val top: Boolean, val bottom: Boolean, val cursor: Int) {
    N(false, false, true, false, Cursor.N_RESIZE_CURSOR),
    S(false, false, false, true, Cursor.S_RESIZE_CURSOR),
    W(true, false, false, false, Cursor.W_RESIZE_CURSOR),
    E(false, true, false, false, Cursor.E_RESIZE_CURSOR),
    NW(true, false, true, false, Cursor.NW_RESIZE_CURSOR),
    NE(false, true, true, false, Cursor.NE_RESIZE_CURSOR),
    SW(true, false, false, true, Cursor.SW_RESIZE_CURSOR),
    SE(false, true, false, true, Cursor.SE_RESIZE_CURSOR),
}

/**
 * The window's new bounds after dragging [edge] by ([dx], [dy]) from [start]: the sides being
 * dragged move with the mouse, the opposite sides stay exactly where they were, and the window never
 * gets smaller than [minWidth] x [minHeight] (it stops shrinking instead of sliding away).
 */
fun resizedBounds(start: Rectangle, edge: ResizeEdge, dx: Int, dy: Int, minWidth: Int, minHeight: Int): Rectangle {
    var x = start.x
    var y = start.y
    var width = start.width
    var height = start.height
    if (edge.right) width = maxOf(minWidth, start.width + dx)
    if (edge.left) {
        width = maxOf(minWidth, start.width - dx)
        x = start.x + start.width - width
    }
    if (edge.bottom) height = maxOf(minHeight, start.height + dy)
    if (edge.top) {
        height = maxOf(minHeight, start.height - dy)
        y = start.y + start.height - height
    }
    return Rectangle(x, y, width, height)
}

/**
 * Dragging this area moves [window]. Like the resize handles it measures the mouse against the
 * SCREEN (the area moves along with the window, so its own coordinates can't be used), and it starts
 * from the very first press so the window doesn't lag behind the cursor by the drag threshold.
 */
fun Modifier.dragsWindow(window: Window): Modifier = pointerInput(window) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val startMouse = MouseInfo.getPointerInfo()?.location ?: return@awaitEachGesture
        val startLocation = window.location
        drag(down.id) {
            val now = MouseInfo.getPointerInfo()?.location ?: return@drag
            window.setLocation(startLocation.x + now.x - startMouse.x, startLocation.y + now.y - startMouse.y)
        }
    }
}

/** Invisible resize handles around the window's edge — put them over the content, in a full-size Box. */
@Composable
fun BoxScope.ResizeHandles(window: Window) {
    val edge = 5.dp
    val corner = 11.dp
    Handle(window, ResizeEdge.N, Modifier.align(Alignment.TopCenter).fillMaxWidth().height(edge))
    Handle(window, ResizeEdge.S, Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(edge))
    Handle(window, ResizeEdge.W, Modifier.align(Alignment.CenterStart).fillMaxHeight().width(edge))
    Handle(window, ResizeEdge.E, Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(edge))
    Handle(window, ResizeEdge.NW, Modifier.align(Alignment.TopStart).size(corner))
    Handle(window, ResizeEdge.NE, Modifier.align(Alignment.TopEnd).size(corner))
    Handle(window, ResizeEdge.SW, Modifier.align(Alignment.BottomStart).size(corner))
    Handle(window, ResizeEdge.SE, Modifier.align(Alignment.BottomEnd).size(corner))
}

@Composable
private fun Handle(window: Window, edge: ResizeEdge, modifier: Modifier) {
    Box(
        modifier = modifier
            .pointerHoverIcon(PointerIcon(Cursor(edge.cursor)))
            .pointerInput(edge) {
                // Measured against the SCREEN position of the mouse, not the handle's own coordinates
                // (the handle moves as the window resizes, which would make the drag jitter).
                var startMouse = Point()
                var startBounds = Rectangle()
                detectDragGestures(
                    onDragStart = {
                        startMouse = MouseInfo.getPointerInfo()?.location ?: Point()
                        startBounds = window.bounds
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        val now = MouseInfo.getPointerInfo()?.location ?: return@detectDragGestures
                        val min = window.minimumSize
                        window.bounds = resizedBounds(startBounds, edge, now.x - startMouse.x, now.y - startMouse.y, min.width, min.height)
                    },
                )
            },
    )
}
