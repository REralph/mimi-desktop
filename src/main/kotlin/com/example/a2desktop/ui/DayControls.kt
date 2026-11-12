package com.example.a2desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.a2desktop.logic.DoseState
import com.example.a2desktop.model.Habit

// Small controls shared by the Calendar's cards: the faded/solid shades of the marks on the month grid, a hover label,
// the bare-emoji habit button and the round tick.

/** How strong a planned (still to come) mark is drawn on the Calendar, against a ticked one. */
internal const val PLANNED_SHADE = 0.45f

/** A dose nobody ticked (it counts as taken by default): drawn just a little softer than one that was ticked. */
internal const val PRESUMED_SHADE = 0.8f

/** How strongly a vitamin mark is drawn on the Calendar's days for where that dose stands. */
internal fun DoseState.markStrength(): Float = when (this) {
    DoseState.TAKEN -> 1f
    DoseState.PRESUMED -> PRESUMED_SHADE
    DoseState.PLANNED -> PLANNED_SHADE
    DoseState.NOT_TAKEN, DoseState.NONE -> 0f
}

/** A small dark label that appears when the pointer rests on [content]. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Tip(text: String, content: @Composable () -> Unit) {
    TooltipArea(
        tooltip = {
            Box(Modifier.background(Color(0xFF2B2B38), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 4.dp)) {
                Text(text, style = MaterialTheme.typography.labelMedium)
            }
        },
        delayMillis = 300,
        content = content,
    )
}

/** A habit as only its emoji (its name appears when the pointer rests on it); filled in the habit's colour when recorded that day. */
@Composable
internal fun EmojiButton(habit: Habit, on: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Tip(habit.name) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(shape)
                .background(if (on) habit.color.copy(alpha = 0.35f) else Color.White.copy(alpha = 0.06f))
                .border(if (on) 1.5.dp else 0.dp, if (on) habit.color else Color.Transparent, shape)
                .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            Text(habit.emoji, fontSize = 17.sp, lineHeight = 19.sp, modifier = Modifier.alpha(if (on) 1f else 0.75f))
        }
    }
}

/**
 * A small pencil: opens the choices for the evening it sits on. It is a little smaller than [TickButton] (so a week of rows stays short)
 * and sits just before it. The pencil is drawn as strokes (not as a text glyph) so it looks the same whatever fonts the PC has.
 */
@Composable
internal fun EditButton(tip: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val ink = Color.White.copy(alpha = 0.75f)
    Tip(tip) {
        Box(
            modifier = modifier
                .size(EDIT_BUTTON_DP.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.06f))
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(10.dp)) {
                val w = size.width
                val h = size.height
                val stroke = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                // the pencil, slanting up to the right, and the line it writes on
                drawPath(Path().apply { moveTo(w * 0.80f, h * 0.10f); lineTo(w * 0.30f, h * 0.60f); lineTo(w * 0.22f, h * 0.78f); lineTo(w * 0.40f, h * 0.70f); lineTo(w * 0.90f, h * 0.20f); close() }, ink, style = stroke)
                drawPath(Path().apply { moveTo(w * 0.05f, h * 0.96f); lineTo(w * 0.60f, h * 0.96f) }, ink, style = stroke)
            }
        }
    }
}

/**
 * The small "repeats" symbol shown beside the title of something that repeats: a circle of arrow (↻). Hovering it says how
 * ([tip], such as "Every 2 weeks"), so the row itself needs no words for it. Drawn as strokes, not as a text glyph, so it looks the
 * same whatever fonts the PC has.
 */
@Composable
internal fun RepeatMark(tip: String, modifier: Modifier = Modifier) {
    val ink = MaterialTheme.colorScheme.onSurfaceVariant
    Tip(tip) {
        Canvas(modifier.size(REPEAT_MARK_DP.dp)) {
            val w = size.width
            val centre = Offset(w / 2f, w / 2f)
            val radius = w * 0.36f
            val line = 1.5.dp.toPx()
            // An open circle, running clockwise from the top-right round to the top-left ...
            drawArc(
                color = ink, startAngle = -50f, sweepAngle = 262f, useCenter = false,
                topLeft = Offset(centre.x - radius, centre.y - radius),
                size = Size(radius * 2, radius * 2),
                style = Stroke(width = line, cap = StrokeCap.Round),
            )
            // ... with an arrowhead where it ends, pointing the way it runs.
            val end = Math.toRadians(-50.0 + 262.0)
            val tip2 = Offset(centre.x + radius * Math.cos(end).toFloat(), centre.y + radius * Math.sin(end).toFloat())
            val along = Offset(-Math.sin(end).toFloat(), Math.cos(end).toFloat()) // clockwise
            val out = Offset(Math.cos(end).toFloat(), Math.sin(end).toFloat()) // away from the centre
            val head = w * 0.24f
            drawPath(
                Path().apply {
                    moveTo(tip2.x + along.x * head, tip2.y + along.y * head)
                    lineTo(tip2.x + out.x * head * 0.8f, tip2.y + out.y * head * 0.8f)
                    lineTo(tip2.x - out.x * head * 0.8f, tip2.y - out.y * head * 0.8f)
                    close()
                },
                ink,
            )
        }
    }
}

/** The width and height of the [RepeatMark]. */
internal const val REPEAT_MARK_DP = 14

/** The width and height of the round [TickButton]. */
internal const val TICK_BUTTON_DP = 24

/** The width and height of the round [EditButton]. */
internal const val EDIT_BUTTON_DP = 18

/**
 * A round tick: an empty ring until it is ticked, then solid green with a ✓. Tapping it ticks it, and tapping a ticked one
 * takes the tick back. [presumed] draws it as a soft green ✓ instead — not ticked, but counted as taken (a day from before
 * ticking began). The ✓ is drawn as strokes (not as a text glyph) so it looks the same whatever fonts the PC has.
 */
@Composable
internal fun TickButton(done: Boolean, tip: String, modifier: Modifier = Modifier, presumed: Boolean = false, onClick: () -> Unit) {
    val ink = when {
        done -> Color(0xFF14141C)
        presumed -> GoodColor
        else -> GoodColor.copy(alpha = 0.55f)
    }
    Tip(tip) {
        Box(
            modifier = modifier
                .size(TICK_BUTTON_DP.dp)
                .clip(CircleShape)
                .background(if (done) GoodColor else if (presumed) GoodColor.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.08f))
                .border(1.dp, if (done) Color.Transparent else GoodColor.copy(alpha = if (presumed) 0.55f else 0.6f), CircleShape)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(11.dp)) {
                val w = size.width
                val h = size.height
                drawPath(
                    Path().apply { moveTo(w * 0.05f, h * 0.55f); lineTo(w * 0.38f, h * 0.88f); lineTo(w * 0.95f, h * 0.15f) },
                    ink,
                    style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
            }
        }
    }
}
