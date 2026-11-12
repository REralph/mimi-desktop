package com.example.a2desktop.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// Same dark palette as the phone app (ui/theme/Color.kt): pure black behind translucent glass.
val DarkBackground = Color(0xFF000000)
val DarkSurface = Color(0xFF18191C)
val DarkSurfaceVariant = Color(0xFF232429)
val DarkOnBackground = Color(0xFFF2F2F4)
val DarkOnSurfaceVariant = Color(0xFFAAAEB6)
val DarkPrimary = Color(0xFFAEBBFF)
val DarkOnPrimary = Color(0xFF141A5E)
val DarkPrimaryContainer = Color(0xFF3B4696)
val DarkOnPrimaryContainer = Color(0xFFE3E6FF)
val DarkTertiary = Color(0xFFF0AC72)

private val colorScheme = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = DarkOnPrimary,
    primaryContainer = DarkPrimaryContainer,
    onPrimaryContainer = DarkOnPrimaryContainer,
    tertiary = DarkTertiary,
    background = DarkBackground,
    onBackground = DarkOnBackground,
    surface = DarkSurface,
    onSurface = DarkOnBackground,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
)

@Composable
fun A2Theme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colorScheme, content = content)
}

/** The phone app's glass surface: translucent surface over black, thin soft border, faint top-down
 * white sheen (see the phone project's ui/theme/Glass.kt). */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(20.dp),
    alpha: Float = 0.85f,
    color: Color = MaterialTheme.colorScheme.surface,
    content: @Composable () -> Unit,
) {
    Surface(
        shape = shape,
        color = color.copy(alpha = alpha),
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 1.dp,
        shadowElevation = 6.dp,
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.10f)),
        modifier = modifier,
    ) {
        Box(
            modifier = Modifier.drawWithContent {
                drawContent()
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(Color.White.copy(alpha = 0.07f), Color.White.copy(alpha = 0f)),
                    ),
                )
            },
        ) {
            content()
        }
    }
}

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(20.dp),
    contentPadding: Dp = 16.dp,
    color: Color = MaterialTheme.colorScheme.surface,
    content: @Composable ColumnScope.() -> Unit,
) {
    GlassSurface(modifier = modifier, shape = shape, color = color) {
        Column(modifier = Modifier.padding(contentPadding), content = content)
    }
}
