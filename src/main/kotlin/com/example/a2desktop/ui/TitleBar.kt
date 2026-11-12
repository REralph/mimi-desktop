package com.example.a2desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The window bar's look: near-black, with the three window buttons in red. */
val TitleBarBackground = Color(0xFF0A0A0E)
val WindowRed = Color(0xFFFF4D4D)
val WindowRedHover = Color(0xFFE5342B)
val TitleBarHeight = 36.dp
val WindowButtonWidth = 46.dp

private enum class Glyph { MINIMISE, MAXIMISE, RESTORE, CLOSE }

/**
 * The app's own window bar, drawn in place of Windows' (white) one so it can be dark with red
 * minimise / maximise / close buttons. It only does what the native bar did: the title area drags
 * the window ([draggable], supplied by the window — a no-op wrapper in previews and tests), a
 * double-click on it maximises/restores, and the three buttons act on the window.
 *
 * Close turns solid red with a white cross when hovered (the Windows convention); minimise and
 * maximise get a soft red wash.
 */
@Composable
fun TitleBar(
    title: String,
    maximised: Boolean,
    onMinimise: () -> Unit,
    onToggleMaximise: () -> Unit,
    onClose: () -> Unit,
    icon: Painter? = null,
    draggable: @Composable (@Composable () -> Unit) -> Unit = { it() },
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(TitleBarHeight)
            .background(TitleBarBackground)
            .drawBehind {
                drawLine(Color.White.copy(alpha = 0.07f), Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), strokeWidth = 1f)
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
            draggable {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) { detectTapGestures(onDoubleTap = { onToggleMaximise() }) }
                        .padding(start = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (icon != null) {
                        Image(painter = icon, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(9.dp))
                    }
                    Text(
                        title,
                        fontSize = 12.sp,
                        lineHeight = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }
        WindowButton(Glyph.MINIMISE, "Minimise", onMinimise)
        WindowButton(if (maximised) Glyph.RESTORE else Glyph.MAXIMISE, if (maximised) "Restore" else "Maximise", onToggleMaximise)
        WindowButton(Glyph.CLOSE, "Close", onClose)
    }
}

@Composable
private fun WindowButton(glyph: Glyph, description: String, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val closing = glyph == Glyph.CLOSE
    val background = when {
        !hovered -> Color.Transparent
        closing -> WindowRedHover
        else -> WindowRed.copy(alpha = 0.18f)
    }
    val ink = if (hovered && closing) Color.White else WindowRed

    Box(
        modifier = Modifier
            .size(WindowButtonWidth, TitleBarHeight)
            .background(background)
            .hoverable(source)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .semantics {
                role = Role.Button
                contentDescription = description
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(12.dp)) {
            val stroke = Stroke(width = 1.4.dp.toPx(), cap = StrokeCap.Square)
            val w = size.width
            val h = size.height
            when (glyph) {
                Glyph.MINIMISE -> drawLine(ink, Offset(0f, h / 2), Offset(w, h / 2), stroke.width, StrokeCap.Square)
                Glyph.MAXIMISE -> drawRect(ink, topLeft = Offset(0.5f, 0.5f), size = Size(w - 1f, h - 1f), style = stroke)
                Glyph.RESTORE -> {
                    // Two overlapping squares: the front one low-left, the back one peeking up-right.
                    val side = w * 0.72f
                    drawRect(ink, topLeft = Offset(0.5f, h - side - 0.5f), size = Size(side, side), style = stroke)
                    drawLine(ink, Offset(w * 0.28f, 0.5f), Offset(w - 0.5f, 0.5f), stroke.width, StrokeCap.Square)
                    drawLine(ink, Offset(w - 0.5f, 0.5f), Offset(w - 0.5f, h * 0.72f), stroke.width, StrokeCap.Square)
                    drawLine(ink, Offset(w * 0.72f, h * 0.72f), Offset(w - 0.5f, h * 0.72f), stroke.width, StrokeCap.Square)
                    drawLine(ink, Offset(w * 0.28f, 0.5f), Offset(w * 0.28f, h - side), stroke.width, StrokeCap.Square)
                }
                Glyph.CLOSE -> {
                    drawLine(ink, Offset(0f, 0f), Offset(w, h), stroke.width, StrokeCap.Square)
                    drawLine(ink, Offset(w, 0f), Offset(0f, h), stroke.width, StrokeCap.Square)
                }
            }
        }
    }
}
