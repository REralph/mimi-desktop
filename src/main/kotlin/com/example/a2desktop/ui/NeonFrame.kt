package com.example.a2desktop.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.jetbrains.skia.FilterBlurMode
import org.jetbrains.skia.MaskFilter
import org.jetbrains.skia.Paint
import org.jetbrains.skia.PaintMode
import org.jetbrains.skia.RRect

/**
 * How bright the whole neon frame is, from 0 (gone) to 1 (as bright as it was first drawn). It was turned down to half, then 10% lower
 * again (0.5 x 0.9): the glow and the tube are each drawn at full strength into one layer, and the layer is then shown at this fraction,
 * so everything is exactly this much as bright (not just each part's own strength, which would change how they add up).
 */
internal const val NEON_BRIGHTNESS = 0.45f

/** The red of the neon tube: strong and a little pink, like a red neon sign. */
internal val NeonRed = Color(0xFFFF1F2E)

/** What a neon tube looks like at its hottest, in the middle of the line: a very pale pink. */
internal val NeonCore = Color(0xFFFFD2CC)

/**
 * The glow of a red neon frame, drawn *behind* the thing it frames and a little outside its edges as well as inside them (so put it before
 * any `clip`): a wide soft halo and a tighter, brighter one, both centred on the edge. With [enabled] false it does nothing.
 */
internal fun Modifier.neonGlow(corner: Dp, enabled: Boolean = true): Modifier = if (!enabled) this else drawBehind {
    val radius = corner.toPx()
    val reach = 40.dp.toPx() // how far outside the edges the glow can spill: the layer must be big enough to hold it
    drawIntoCanvas { canvas ->
        canvas.saveLayer(Rect(-reach, -reach, size.width + reach, size.height + reach), androidx.compose.ui.graphics.Paint().apply { alpha = NEON_BRIGHTNESS })
        // (stroke width, blur spread, strength) of each layer, dp: the wide one is the haze, the narrow one the bright rim.
        listOf(Triple(12.dp, 11.dp, 0.80f), Triple(6.dp, 4.dp, 0.95f)).forEach { (width, spread, strength) ->
            Paint().use { paint ->
                paint.isAntiAlias = true
                paint.mode = PaintMode.STROKE
                paint.strokeWidth = width.toPx()
                paint.color = NeonRed.copy(alpha = strength).toArgb()
                paint.maskFilter = MaskFilter.makeBlur(FilterBlurMode.NORMAL, spread.toPx())
                canvas.nativeCanvas.drawRRect(RRect.makeXYWH(0f, 0f, size.width, size.height, radius), paint)
            }
        }
        canvas.restore()
    }
}

/**
 * The tube itself: a thin crisp red line along the edge with a paler, hotter line inside it, drawn over the content (inside the `clip`, so
 * it is the last thing drawn within the shape). With [enabled] false it does nothing.
 */
internal fun Modifier.neonLine(corner: Dp, enabled: Boolean = true): Modifier = if (!enabled) this else drawWithContent {
    drawContent()
    val radius = corner.toPx()
    val outer = 3.dp.toPx()
    val inner = 1.4.dp.toPx()
    drawIntoCanvas { it.saveLayer(Rect(0f, 0f, size.width, size.height), androidx.compose.ui.graphics.Paint().apply { alpha = NEON_BRIGHTNESS }) }
    drawRoundRect(
        NeonRed, topLeft = Offset(outer / 2, outer / 2), size = Size(size.width - outer, size.height - outer),
        cornerRadius = CornerRadius(radius - outer / 2), style = Stroke(outer),
    )
    drawRoundRect(
        NeonCore, topLeft = Offset(outer / 2, outer / 2), size = Size(size.width - outer, size.height - outer),
        cornerRadius = CornerRadius(radius - outer / 2), style = Stroke(inner),
    )
    drawIntoCanvas { it.restore() }
}
