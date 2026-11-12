package com.example.a2desktop.ui

import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.a2desktop.logic.DayRoutine
import com.example.a2desktop.logic.EveningKind
import com.example.a2desktop.logic.SkinCareLog
import com.example.a2desktop.logic.UvBasis
import com.example.a2desktop.logic.UvLookup
import com.example.a2desktop.logic.WorkSchedule
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

private val rowDayFmt = DateTimeFormatter.ofPattern("EEE d MMM")
private val rowTimeFmt = DateTimeFormatter.ofPattern("HH:mm")

/** How many earlier days the list reaches back, and how many days ahead it plans. */
internal const val EVENINGS_PAST_DAYS = 30
internal const val EVENINGS_AHEAD_DAYS = 14

/**
 * One line of the evenings list. [kind] is what that evening was or is: what the user recorded, else what the plan
 * says (a day before the routine began, with nothing recorded, has none). [done] means the user ticked it or
 * recorded it; a PAST day from before confirming began that wasn't is [assumed] — the plan, taken as followed; one
 * from after it that wasn't is [missed] — dropped, and the plan has moved on without it.
 */
internal data class EveningRow(
    val date: LocalDate,
    val isToday: Boolean,
    val isPast: Boolean,
    val work: String,
    /** "UV 3" from a forecast, "UV low" as a seasonal guess, "—" when nothing is known (an old day before the forecast reaches). */
    val uv: String,
    val uvIsForecast: Boolean,
    /** The day's highest temperature, "14°C", or "—". */
    val temp: String,
    val kind: EveningKind?,
    val done: Boolean,
    val assumed: Boolean,
    val why: String?,
    /** A past evening that was never confirmed (from the day confirming began): it has dropped off, so it shows no product. */
    val missed: Boolean = false,
    /** The user answered ✗ to it: also dropped ([missed]), and said so as "Skipped" rather than "Not recorded". */
    val skipped: Boolean = false,
)

private fun workShort(work: WorkSchedule, date: LocalDate): String {
    val shift = work.shiftOn(date)
    return when {
        shift != null -> "${shift.start.format(rowTimeFmt)}–${shift.end.format(rowTimeFmt)}"
        work.isOff(date) -> "Day off"
        else -> "—"
    }
}

/**
 * The evenings, oldest first: the [pastDays] days before [today], then today and the days after it. [plan] is the
 * whole routine from its start (see planRoutine), so a day the user didn't tick shows what the plan said, as
 * assumed; a day they recorded shows what they recorded. Each line also carries that day's UV and temperature.
 */
internal fun eveningRows(
    today: LocalDate,
    log: SkinCareLog,
    work: WorkSchedule,
    plan: List<DayRoutine>,
    uv: UvLookup,
    pastDays: Int = EVENINGS_PAST_DAYS,
): List<EveningRow> {
    val byDay = plan.associateBy { it.date }
    val last = plan.maxOfOrNull { it.date } ?: today
    val first = today.minusDays(pastDays.toLong())
    return generateSequence(first) { it.plusDays(1) }.takeWhile { !it.isAfter(last) }.map { date ->
        val routine = byDay[date]
        val isPast = date.isBefore(today)
        val kind = log.usedOn(date) ?: routine?.eveningKind
        val done = log.doneOn(date)
        val missed = routine?.missed == true
        val reading = uv.on(date)
        EveningRow(
            date = date,
            isToday = date == today,
            isPast = isPast,
            work = workShort(work, date),
            uv = when {
                reading.basis == UvBasis.FORECAST -> reading.short()
                isPast -> "—"
                else -> reading.short() // a seasonal guess: "UV low"
            },
            uvIsForecast = reading.basis == UvBasis.FORECAST,
            temp = reading.tempMaxC?.let { "${it.roundToInt()}°C" } ?: "—",
            kind = kind,
            done = done,
            assumed = isPast && !done && kind != null && !missed,
            why = if (isPast) null else routine?.why,
            missed = missed,
            skipped = missed && date in log.eveningsSkipped,
        )
    }.toList()
}

/** Where today's line is in [rows]. */
internal fun todayIndex(rows: List<EveningRow>): Int = rows.indexOfFirst { it.isToday }.coerceAtLeast(0)

/** How many days the ▲ and ▼ buttons move the list. */
private const val STEP_DAYS = 5

/**
 * The evenings as a scrolling list with today at the top: the days before it are above, the days ahead below. It
 * jumps back to today whenever the date changes, so each day it opens at the new today by itself.
 *
 * The list has its OWN scrolling — the bar on its right edge, the ▲ ▼ buttons, and "Today" — and deliberately does
 * NOT take the mouse wheel: the wheel always scrolls the page the list sits in, so the list never traps it (the
 * page can still be scrolled with the pointer anywhere over it). [height] is fixed, so it can sit inside a page
 * that itself scrolls.
 */
@Composable
internal fun EveningsList(
    rows: List<EveningRow>,
    today: LocalDate,
    selected: LocalDate?,
    height: Dp,
    showReason: Boolean,
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(initialFirstVisibleItemIndex = todayIndex(rows)),
) {
    val atToday = todayIndex(rows)
    LaunchedEffect(today) { state.scrollToItem(atToday) }
    val scope = rememberCoroutineScope()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // The same column widths as the lines below.
            Text("Day", style = MaterialTheme.typography.labelSmall, color = muted, modifier = Modifier.width(DAY_WIDTH))
            Text("Work", style = MaterialTheme.typography.labelSmall, color = muted, modifier = Modifier.width(WORK_WIDTH))
            Text("UV", style = MaterialTheme.typography.labelSmall, color = muted, modifier = Modifier.width(UV_WIDTH))
            Text("Temp", style = MaterialTheme.typography.labelSmall, color = muted, modifier = Modifier.width(TEMP_WIDTH))
            Text("Evening", style = MaterialTheme.typography.labelSmall, color = muted, maxLines = 1, softWrap = false)
            Spacer(Modifier.weight(1f))
            // The list's own controls: a few days up, a few days down, and back to today.
            StepButton("▲", 22) { scope.launch { state.animateScrollToItem((state.firstVisibleItemIndex - STEP_DAYS).coerceAtLeast(0)) } }
            StepButton("▼", 22) { scope.launch { state.animateScrollToItem((state.firstVisibleItemIndex + STEP_DAYS).coerceAtMost(rows.lastIndex)) } }
            StepButton("Today", 40) { scope.launch { state.animateScrollToItem(atToday) } }
        }
        Box(modifier = Modifier.fillMaxWidth().height(height).padding(top = 2.dp)) {
            // userScrollEnabled = false: the mouse wheel is left for the page. The bar and buttons still move the list.
            LazyColumn(state = state, userScrollEnabled = false, modifier = Modifier.fillMaxSize().padding(end = 12.dp)) {
                itemsIndexed(rows, key = { _, row -> row.date.toEpochDay() }) { _, row ->
                    EveningLine(row, bold = row.isToday || row.date == selected, showReason = showReason)
                }
            }
            VerticalScrollbar(
                adapter = rememberScrollbarAdapter(state),
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                style = ScrollbarStyle(
                    minimalHeight = 28.dp,
                    thickness = 8.dp,
                    shape = RoundedCornerShape(4.dp),
                    hoverDurationMillis = 200,
                    unhoverColor = Color.White.copy(alpha = 0.40f),
                    hoverColor = Color.White.copy(alpha = 0.75f),
                ),
            )
        }
        Text(
            buildString {
                append("Opens at today. Drag the bar on the right (or use ▲ ▼) for earlier days; the mouse wheel scrolls the page. ")
                append("✓ = you confirmed it; without ✓, a day from before confirming began counts as followed, a later one shows Not recorded. ")
                if (rows.none { it.uvIsForecast }) append("Add your town on the Skin care page for real UV and °C.")
            },
            style = MaterialTheme.typography.labelSmall,
            color = muted.copy(alpha = 0.75f),
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

private val DAY_WIDTH = 76.dp
private val WORK_WIDTH = 72.dp
private val UV_WIDTH = 40.dp
private val TEMP_WIDTH = 32.dp

/** A small button on the list's header. */
@Composable
private fun StepButton(label: String, widthDp: Int, onClick: () -> Unit) {
    Box(modifier = Modifier.padding(start = 4.dp), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .width(widthDp.dp)
                .height(20.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color.White.copy(alpha = 0.10f))
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, softWrap = false)
        }
    }
}

@Composable
private fun EveningLine(row: EveningRow, bold: Boolean, showReason: Boolean) {
    val colors = MaterialTheme.colorScheme
    val kind = row.kind
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (row.isToday) Modifier.background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(6.dp)) else Modifier)
            .padding(vertical = 3.dp, horizontal = 2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (row.isToday) "Today" else row.date.format(rowDayFmt),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
                color = if (row.isPast) colors.onSurfaceVariant else colors.onSurface,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.width(DAY_WIDTH),
            )
            Text(row.work, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip, modifier = Modifier.width(WORK_WIDTH))
            Text(
                row.uv,
                style = MaterialTheme.typography.labelSmall,
                color = if (row.uvIsForecast) colors.onSurface else colors.onSurfaceVariant.copy(alpha = 0.6f),
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.width(UV_WIDTH),
            )
            Text(row.temp, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant, maxLines = 1, softWrap = false, modifier = Modifier.width(TEMP_WIDTH))
            when {
                kind == null -> Text("—", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant.copy(alpha = 0.5f))
                row.missed -> Text(if (row.skipped) "Skipped" else "Not recorded", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant.copy(alpha = 0.5f), maxLines = 1, softWrap = false)
                kind == EveningKind.REST -> {
                    Box(Modifier.width(28.dp))
                    Text("Rest night", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant, maxLines = 1, softWrap = false)
                }
                else -> {
                    // A past day nobody ticked is the plan taken as followed (a little softer); a confirmed one is solid.
                    Box(Modifier.width(28.dp)) { EveningChip(kind, strength = if (row.done) 1f else if (row.assumed) 0.8f else 0.55f) }
                    Text(
                        kind.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (kind.exfoliation) colors.primary else colors.onSurface,
                        fontWeight = if (kind.exfoliation) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
            if (row.done) Text("✓", style = MaterialTheme.typography.labelSmall, color = colors.primary, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 6.dp))
        }
        if (showReason && row.why != null && !row.isPast) {
            Text(row.why, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant, modifier = Modifier.padding(start = DAY_WIDTH, top = 1.dp))
        }
    }
}
