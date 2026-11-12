package com.example.a2desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.a2desktop.data.DesktopStore
import com.example.a2desktop.logic.LIFE_MONTHS
import com.example.a2desktop.logic.LIFE_YEARS
import com.example.a2desktop.logic.LifeMonthState
import com.example.a2desktop.logic.LifeProgress
import com.example.a2desktop.logic.MONTHS_PER_YEAR
import com.example.a2desktop.logic.fiveYearMarkPassed
import com.example.a2desktop.logic.isFiveYearMark
import com.example.a2desktop.logic.lifeAgeText
import com.example.a2desktop.logic.lifeMonthEnd
import com.example.a2desktop.logic.lifeMonthStart
import com.example.a2desktop.logic.lifeMonthState
import com.example.a2desktop.logic.lifeProgress
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.floor

private val lifeDateFmt = DateTimeFormatter.ofPattern("d MMM yyyy")

// The grid's natural size (dp) at zoom 1: two blocks of 40 rows, each row an "Age N" label plus 12 cells.
private const val BASE_ROW = 13f
private const val BASE_CELL = 24f
private const val BASE_LABEL = 52f
private const val BASE_GAP = 56f
private const val BASE_WIDTH = 2 * (BASE_LABEL + MONTHS_PER_YEAR * BASE_CELL) + BASE_GAP
private const val BASE_HEIGHT = (LIFE_YEARS / 2) * BASE_ROW

/** Below this zoom the circles and labels would be too small to read, so the grid stops shrinking
 * and the area scrolls instead (only really happens in a very short window). */
internal const val LIFE_MIN_ZOOM = 0.75f
private const val LIFE_MAX_ZOOM = 4f

/**
 * How far to zoom the grid so that it fills the space it is given (dp) without overflowing either
 * way: 1.0 is the natural size, bigger when the window is bigger, smaller when it is smaller. The
 * same factor scales the circles, the row height, the column width and the "Age N" text, so the
 * picture keeps its shape — whichever of width or height is tighter decides.
 */
internal fun lifeGridZoom(availableWidthDp: Float, availableHeightDp: Float): Float =
    minOf(availableWidthDp / BASE_WIDTH, availableHeightDp / BASE_HEIGHT).coerceAtMost(LIFE_MAX_ZOOM)

/**
 * Life in Months — the phone's page 0.21. On a wide screen the 80 rows split into two side-by-side
 * halves (ages 0–39 and 40–79) so the whole life fits one screen without scrolling. The grid zooms
 * with the window ([lifeGridZoom]): drag the window bigger and the circles, rows and age labels grow
 * to fill the page; make it smaller and they shrink with it. The date of birth is never assumed: the
 * page asks for it, exactly like the phone.
 */
@Composable
fun LifePage(store: DesktopStore) {
    val dob = store.dateOfBirth
    var editing by remember { mutableStateOf(dob == null) }
    var selectedIndex by remember { mutableStateOf<Int?>(null) }

    Column(modifier = Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PageHeader("Life in Months", "An 80-year life as 960 monthly circles", modifier = Modifier.devRef("5.1", "Page header")) {
            if (dob != null && !editing && !store.readOnly) TextButton(onClick = { editing = true }) { Text("Change date of birth") }
        }
        if (store.readOnly && dob == null) {
            Text(
                "Your phone has no date of birth yet. Enter it on the phone's Life in Months page and sync again.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else if (editing) {
            DateOfBirthEntry(
                initial = dob,
                today = store.today,
                onSet = {
                    store.enterDateOfBirth(it)
                    selectedIndex = null
                    editing = false
                },
                onCancel = if (dob != null) ({ editing = false }) else null,
            )
        }
        if (dob != null) {
            val progress = lifeProgress(dob, store.today)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth().devRef("5.3", "Summary tiles")) {
                StatTile("${progress.ageYears}y ${progress.ageMonths}m", "Age", Modifier.weight(1f))
                StatTile(progress.monthsLived.toString(), "Months lived", Modifier.weight(1f))
                StatTile(progress.monthsRemaining.toString(), "Months remaining", Modifier.weight(1f))
                StatTile(String.format(Locale.getDefault(), "%.1f%%", progress.percent), "Progress", Modifier.weight(1f))
            }
            DetailLine(dob, progress, selectedIndex)
            LifeGrid(
                progress = progress,
                selectedIndex = selectedIndex,
                modifier = Modifier.weight(1f).fillMaxWidth().devRef("5.5", "Life grid"),
                onSelect = { selectedIndex = if (selectedIndex == it) null else it },
            )
        }
    }
}

/** The two halves of the life, zoomed to fill whatever space the page leaves for them. */
@Composable
private fun LifeGrid(progress: LifeProgress, selectedIndex: Int?, modifier: Modifier, onSelect: (Int) -> Unit) {
    BoxWithConstraints(modifier = modifier) {
        val fit = lifeGridZoom(maxWidth.value, maxHeight.value)
        val zoom = fit.coerceAtLeast(LIFE_MIN_ZOOM)
        val gap = (BASE_GAP * zoom).dp
        if (fit >= LIFE_MIN_ZOOM) {
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(gap, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) { LifeBlocks(progress, selectedIndex, zoom, onSelect) }
        } else {
            ScrollColumn(modifier = Modifier.fillMaxSize()) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap, Alignment.CenterHorizontally)) {
                    LifeBlocks(progress, selectedIndex, zoom, onSelect)
                }
            }
        }
    }
}

@Composable
private fun LifeBlocks(progress: LifeProgress, selectedIndex: Int?, zoom: Float, onSelect: (Int) -> Unit) {
    val half = LIFE_YEARS / 2
    LifeBlock(0, half, progress, selectedIndex, zoom, onSelect)
    LifeBlock(half, half, progress, selectedIndex, zoom, onSelect)
}

@Composable
private fun DateOfBirthEntry(initial: LocalDate?, today: LocalDate, onSet: (LocalDate) -> Unit, onCancel: (() -> Unit)?) {
    var text by remember { mutableStateOf(initial?.toString() ?: "") }
    val parsed = runCatching { LocalDate.parse(text.trim()) }.getOrNull()
    val problem = when {
        text.isBlank() -> null
        parsed == null -> "Use the format yyyy-mm-dd, e.g. 1998-03-12"
        parsed.isAfter(today) -> "That date is in the future"
        parsed.year < 1900 -> "That date is too far back"
        else -> null
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.devRef("5.2", "Date of birth entry")) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text("Date of birth (yyyy-mm-dd)") },
            isError = problem != null,
            supportingText = { Text(problem ?: "Stored only on this PC for now. Nothing is assumed until you set it.") },
            singleLine = true,
            modifier = Modifier.width(360.dp),
        )
        Button(onClick = { parsed?.let(onSet) }, enabled = parsed != null && problem == null) { Text("Set") }
        if (onCancel != null) OutlinedButton(onClick = onCancel) { Text("Cancel") }
    }
}

@Composable
private fun DetailLine(dob: LocalDate, progress: LifeProgress, selectedIndex: Int?) {
    Box(modifier = Modifier.fillMaxWidth().height(40.dp).devRef("5.4", "Selected month line"), contentAlignment = Alignment.CenterStart) {
        if (selectedIndex == null) {
            Text("Click a circle to see its month", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            val years = selectedIndex / MONTHS_PER_YEAR
            val months = selectedIndex % MONTHS_PER_YEAR
            val state = when (lifeMonthState(selectedIndex, progress)) {
                LifeMonthState.COMPLETED -> "Completed"
                LifeMonthState.CURRENT -> "Current month"
                LifeMonthState.FUTURE -> "Not yet reached"
            }
            Column {
                Text("Year $years, Month $months — Age: ${lifeAgeText(years, months)}", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "${lifeMonthStart(dob, selectedIndex).format(lifeDateFmt)} – ${lifeMonthEnd(dob, selectedIndex).format(lifeDateFmt)} · $state",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** The five-year marks' colour (the same green as on the phone's page, on this app's dark theme). */
private val FiveYearGreen = Color(0xFF4ADE80)

/**
 * [years] rows of 12 monthly circles starting at age [firstYear], with an "Age N" label per row,
 * everything sized by [zoom]. Every fifth year (age 5, 10 … 75) is marked in green, as on the phone:
 * its label, and a line running across the months at the top of the row — the point where that many
 * years are done. Marks already reached are solid, marks still ahead are faded, so counting the solid
 * lines shows how many five-year blocks have gone by.
 */
@Composable
private fun LifeBlock(firstYear: Int, years: Int, progress: LifeProgress, selectedIndex: Int?, zoom: Float, onSelect: (Int) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val density = LocalDensity.current
    // Rows are a whole number of pixels tall, so the 40 labels and the one canvas beside them can
    // never drift apart by rounding as the window is resized.
    val rowPx = floor(BASE_ROW * zoom * density.density).coerceAtLeast(1f)
    val rowHeight: Dp = with(density) { rowPx.toDp() }
    val cellWidth = (BASE_CELL * zoom).dp
    val ink = zoom.coerceIn(0.8f, 2.5f) // line thickness grows a little with the zoom, not as much
    val completed = colors.primary
    val current = colors.tertiary
    val future = colors.onSurface.copy(alpha = 0.35f)
    val currentOnSelect by rememberUpdatedState(onSelect)

    Row {
        Column(modifier = Modifier.width((BASE_LABEL * zoom).dp)) {
            repeat(years) { y ->
                val year = firstYear + y
                val mark = isFiveYearMark(year)
                val markColor = FiveYearGreen.copy(alpha = if (fiveYearMarkPassed(year, progress)) 1f else 0.45f)
                Box(
                    modifier = Modifier.height(rowHeight).fillMaxWidth().drawBehind {
                        if (mark) {
                            val thickness = 1.5.dp.toPx() * ink
                            drawLine(markColor, Offset(0f, thickness / 2f), Offset(size.width, thickness / 2f), strokeWidth = thickness)
                        }
                    },
                    contentAlignment = Alignment.CenterStart,
                ) {
                    val labelSize = (9f * zoom).coerceAtLeast(7f).sp
                    Text(
                        "Age $year",
                        fontSize = labelSize,
                        lineHeight = labelSize,
                        color = if (mark) markColor else colors.onSurfaceVariant,
                        fontWeight = if (mark) FontWeight.Bold else null,
                        maxLines = 1,
                    )
                }
            }
        }
        Canvas(
            modifier = Modifier
                .width(cellWidth * MONTHS_PER_YEAR)
                .height(rowHeight * years)
                .pointerInput(firstYear) {
                    detectTapGestures { offset ->
                        val column = (offset.x / (size.width / MONTHS_PER_YEAR)).toInt().coerceIn(0, MONTHS_PER_YEAR - 1)
                        val row = (offset.y / (size.height / years.toFloat())).toInt().coerceIn(0, years - 1)
                        currentOnSelect((firstYear + row) * MONTHS_PER_YEAR + column)
                    }
                },
        ) {
            val cell = size.width / MONTHS_PER_YEAR
            val rowPx = size.height / years
            val radius = minOf(cell * 0.36f, rowPx * 0.36f)
            for (row in 0 until years) {
                val year = firstYear + row
                if (isFiveYearMark(year)) {
                    val thickness = 1.5.dp.toPx() * ink
                    val markColor = FiveYearGreen.copy(alpha = if (fiveYearMarkPassed(year, progress)) 1f else 0.45f)
                    drawLine(markColor, Offset(0f, rowPx * row + thickness / 2f), Offset(size.width, rowPx * row + thickness / 2f), strokeWidth = thickness)
                }
                for (column in 0 until MONTHS_PER_YEAR) {
                    val index = (firstYear + row) * MONTHS_PER_YEAR + column
                    if (index >= LIFE_MONTHS) continue
                    val center = Offset(cell * (column + 0.5f), rowPx * (row + 0.5f))
                    when (lifeMonthState(index, progress)) {
                        LifeMonthState.COMPLETED -> drawCircle(completed, radius, center)
                        LifeMonthState.CURRENT -> {
                            drawCircle(current.copy(alpha = 0.28f), radius, center)
                            drawCircle(current, radius, center, style = Stroke(2.dp.toPx() * ink))
                        }
                        LifeMonthState.FUTURE -> drawCircle(future, radius, center, style = Stroke(1.dp.toPx() * ink))
                    }
                    if (index == selectedIndex) drawCircle(colors.onSurface, radius + 2.dp.toPx() * ink, center, style = Stroke(1.dp.toPx() * ink))
                }
            }
        }
    }
}
