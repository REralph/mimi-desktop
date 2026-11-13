package com.example.a2desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.a2desktop.data.DesktopStore
import com.example.a2desktop.logic.runEndsInYear
import com.example.a2desktop.model.Habit
import java.time.LocalDate
import java.time.Month
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private val gridLine = Color.White.copy(alpha = 0.45f)
private val dateColumnWidth = 28.dp
private val hoverFmt = DateTimeFormatter.ofPattern("EEE d MMM yyyy")

// The header row and the 31 date rows share the height equally, so every date (and the month names) always fits.
private const val TOTAL_ROWS = 32

/**
 * Month Overview — the phone's page 0.7 on the PC: the whole year as one table, a column for each month and a row for
 * each date (1–31). A day's square is filled with a band in the colour of every calendar-linked habit recorded that
 * day (side by side when there are several), exactly as the phone paints it; a date that does not exist in that month
 * (30 February …) stays empty. The last square of every run of streak days carries a number: how many days in a row
 * that run lasted (counting days of earlier years too), so each streak's length can be read straight off the table.
 * The phone has no per-day taps here, but a mouse can hover: the date and its habits are then read out in the
 * header. [onBack] returns to the month grid; the year can be stepped with the arrows.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun YearOverview(
    store: DesktopStore,
    year: Int,
    onYearChange: (Int) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Written by the squares, read only by YearHeader: a hover must not redo the whole table.
    val hovered = remember { mutableStateOf<LocalDate?>(null) }
    val linked = store.habits.filter { it.linkedToCalendar }
    // The blue squares: streak days. The last square of each run of them says how long the run was.
    val streak = store.streakHabit?.takeIf { it.linkedToCalendar }
    val streaks = if (streak == null) emptyMap() else runEndsInYear(year) { streak.id in store.habitsOn(it) }
    Column(modifier = modifier.fillMaxSize().padding(24.dp)) {
        YearHeader(store, linked, year, hovered, onYearChange, onBack)
        Spacer(Modifier.height(14.dp))
        YearTable(
            store, linked, year, hovered, streaks, numberInk = streak?.color?.let(::inkOn) ?: Color.Black,
            modifier = Modifier.weight(1f).fillMaxWidth().devRef("1.10", "Year table"),
        )
        Spacer(Modifier.height(12.dp))
        FlowRow(
            modifier = Modifier.devRef("1.11", "Colour key"),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            linked.forEach { habit ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Dot(habit.color, size = 9.dp)
                    Text("  ${habit.name}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(
                "Habits recorded on the same day sit side by side in its square",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (streak != null) {
                Text(
                    "The number on a run's last ${streak.name} square is how many days in a row it lasted",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Dark writing on a light colour and light writing on a dark one, so a number is readable on any habit's colour. */
private fun inkOn(background: Color): Color = if (background.luminance() > 0.5f) Color(0xFF0B0B10) else Color.White

@Composable
private fun YearHeader(
    store: DesktopStore,
    linked: List<Habit>,
    year: Int,
    hovered: MutableState<LocalDate?>,
    onYearChange: (Int) -> Unit,
    onBack: () -> Unit,
) {
    val day = hovered.value
    val subtitle = if (day == null) {
        "Month Overview — hover a day to read it"
    } else {
        val ids = store.habitsOn(day)
        val names = linked.filter { it.id in ids }.joinToString(", ") { "${it.emoji} ${it.name}" }
        day.format(hoverFmt) + " — " + names.ifEmpty { "nothing recorded" }
    }
    PageHeader(title = year.toString(), subtitle = subtitle, modifier = Modifier.devRef("1.9", "Year header")) {
        IconButton(onClick = { onYearChange(year - 1) }, modifier = Modifier.devRef("1.9.1", "Previous year")) { Text("‹", fontSize = 26.sp) }
        OutlinedButton(onClick = { onYearChange(store.today.year) }, modifier = Modifier.devRef("1.9.2", "This year button")) { Text("This year") }
        IconButton(onClick = { onYearChange(year + 1) }, modifier = Modifier.devRef("1.9.3", "Next year")) { Text("›", fontSize = 26.sp) }
        Spacer(Modifier.width(8.dp))
        OutlinedButton(onClick = onBack, modifier = Modifier.devRef("1.9.4", "Back to calendar button")) { Text("Back to calendar") }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun YearTable(
    store: DesktopStore,
    linked: List<Habit>,
    year: Int,
    hovered: MutableState<LocalDate?>,
    streaks: Map<LocalDate, Int>,
    numberInk: Color,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier.onPointerEvent(PointerEventType.Exit) { hovered.value = null }) {
        val rowHeight = (maxHeight - 31.dp) / TOTAL_ROWS
        val fontSize = (rowHeight.value * 0.6f).coerceIn(8f, 13f).sp
        Column(modifier = Modifier.fillMaxSize()) {
            Row(modifier = Modifier.fillMaxWidth().weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.width(dateColumnWidth))
                VerticalLine()
                Month.entries.forEachIndexed { index, month ->
                    Text(
                        text = month.getDisplayName(TextStyle.SHORT, Locale.getDefault()),
                        fontWeight = FontWeight.Bold,
                        fontSize = fontSize,
                        lineHeight = fontSize,
                        maxLines = 1,
                        overflow = TextOverflow.Clip,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f),
                    )
                    if (index < Month.entries.lastIndex) VerticalLine()
                }
            }
            HorizontalLine()
            for (day in 1..31) {
                YearRow(store, linked, year, day, fontSize, hovered, streaks, numberInk, modifier = Modifier.fillMaxWidth().weight(1f))
                if (day < 31) HorizontalLine()
            }
        }
    }
}

@Composable
private fun YearRow(
    store: DesktopStore,
    linked: List<Habit>,
    year: Int,
    day: Int,
    fontSize: TextUnit,
    hovered: MutableState<LocalDate?>,
    streaks: Map<LocalDate, Int>,
    numberInk: Color,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = day.toString(),
            fontWeight = FontWeight.Bold,
            fontSize = fontSize,
            lineHeight = fontSize,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(dateColumnWidth),
        )
        VerticalLine()
        Month.entries.forEachIndexed { index, month ->
            val date = runCatching { LocalDate.of(year, month, day) }.getOrNull()
            val ids = date?.let { store.habitsOn(it) }.orEmpty()
            DaySquare(date, linked.filter { it.id in ids }, hovered, streaks[date], fontSize, numberInk, modifier = Modifier.weight(1f).fillMaxHeight())
            if (index < Month.entries.lastIndex) VerticalLine()
        }
    }
}

/**
 * One day's square: a band per habit recorded that day, side by side; nothing if none (or if the date doesn't exist).
 * [streakDays] is set on the last square of a run of streak days: the run's length, written across the middle of it.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun DaySquare(
    date: LocalDate?,
    habits: List<Habit>,
    hovered: MutableState<LocalDate?>,
    streakDays: Int?,
    fontSize: TextUnit,
    numberInk: Color,
    modifier: Modifier = Modifier,
) {
    val isHovered by remember(date) { derivedStateOf { date != null && hovered.value == date } }
    Box(
        modifier = modifier
            .onPointerEvent(PointerEventType.Enter) { if (date != null) hovered.value = date }
            .border(if (isHovered) 1.5.dp else 0.dp, if (isHovered) Color.White else Color.Transparent),
        contentAlignment = Alignment.Center,
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            habits.forEach { habit ->
                Box(modifier = Modifier.weight(1f).fillMaxHeight().background(habit.color))
            }
        }
        if (streakDays != null) {
            Text(
                text = streakDays.toString(),
                color = numberInk,
                fontWeight = FontWeight.Bold,
                fontSize = fontSize,
                lineHeight = fontSize,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

@Composable
private fun VerticalLine() {
    Box(modifier = Modifier.fillMaxHeight().width(1.dp).background(gridLine))
}

@Composable
private fun HorizontalLine() {
    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(gridLine))
}
