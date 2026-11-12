package com.example.a2desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.a2desktop.data.SkinCareStore
import com.example.a2desktop.logic.DayRoutine
import com.example.a2desktop.logic.EveningKind
import com.example.a2desktop.logic.Product
import com.example.a2desktop.logic.UvBasis
import com.example.a2desktop.logic.UvLookup
import com.example.a2desktop.logic.UvReading
import com.example.a2desktop.logic.WorkSchedule
import com.example.a2desktop.logic.eveningProducts
import com.example.a2desktop.logic.planRoutine
import com.example.a2desktop.theme.GlassCard
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private val dayFmt = DateTimeFormatter.ofPattern("EEE d MMM")
private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")

/** The colours of the evening markers on the Calendar and on the Skin care page. */
internal val GlycolicColor = Color(0xFFEF8FA8)
internal val TonerColor = Color(0xFFB993F5)
internal val CalmColor = Color(0xFF63C7C1)
internal val BrightenColor = Color(0xFFF2B866)
internal val BothColor = Color(0xFFA3C77B)
internal val RestColor = Color(0xFF9AA0B4)

internal fun EveningKind.color(): Color = when (this) {
    EveningKind.GLYCOLIC -> GlycolicColor
    EveningKind.TONER -> TonerColor
    EveningKind.CALM -> CalmColor
    EveningKind.BRIGHTEN -> BrightenColor
    EveningKind.BOTH -> BothColor
    EveningKind.REST -> RestColor
}

/** A short code in a small coloured square: G(lycolic treatment), T(oner), N(iacinamide), C (Vitamin C), NC (both serums), R(est). */
internal fun EveningKind.letter(): String = when (this) {
    EveningKind.GLYCOLIC -> "G"
    EveningKind.TONER -> "T"
    EveningKind.CALM -> "N"
    EveningKind.BRIGHTEN -> "C"
    EveningKind.BOTH -> "NC"
    EveningKind.REST -> "R"
}

/** What an evening's row says: the products in order, or that nothing active goes on. */
internal fun eveningText(kind: EveningKind): String =
    if (kind == EveningKind.REST) "Rest night — nothing active" else eveningProducts(kind).joinToString(" · ") { it.short }

private fun List<Product>.short() = joinToString(" · ") { it.short }

/** A small coloured code that says which product an evening is — on the Calendar's days, in the lists and in the legend. */
@Composable
internal fun EveningChip(kind: EveningKind, modifier: Modifier = Modifier, strength: Float = 1f) {
    Box(
        modifier = modifier.size(if (kind == EveningKind.BOTH) 24.dp else 16.dp, 16.dp).background(kind.color().copy(alpha = 0.9f * strength), RoundedCornerShape(4.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(kind.letter(), fontSize = if (kind == EveningKind.BOTH) 9.sp else 10.sp, lineHeight = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF101018).copy(alpha = strength.coerceAtLeast(0.6f)))
    }
}

/** How many evenings the Calendar's Skin care card lists: this week, from today. */
internal const val SKIN_SCHEDULE_DAYS = 7

/**
 * The Calendar's Skin care card: the next week of evenings, each as its day and what goes on, so it is plain which days
 * skincare is to be done (a rest night is a quiet line: nothing to do) — and the one place the routine is confirmed.
 *
 * A night with something to do has a round tick at its end, from tonight back (a night still to come has none). Tick it
 * once you've done it; tap a ticked night to take the tick back. A night with no tick was not done, and the plan moves on
 * without it (see [planRoutine]): tonight shows faded on the Calendar until it is ticked, and one that passes unticked drops
 * off the next day, so the nights after it are planned again.
 *
 * The day selected on the Calendar is picked out. When that is an earlier day (from the day confirming began) it is also
 * shown as a row at the top, so a tick that was forgotten can still be given late. The Skin care page (left menu) keeps the
 * long version with the reasons, the weather and the notes. Lives on this PC only — see [SkinCareStore].
 */
@Composable
fun SkinCareCard(skin: SkinCareStore, work: WorkSchedule, uv: UvLookup, today: LocalDate, selected: LocalDate) {
    val log = skin.log
    val confirmFrom = skin.confirmFrom
    val routine = remember(today, skin.startedOn, confirmFrom, log, work, uv) {
        planRoutine(today, skin.startedOn, SKIN_SCHEDULE_DAYS - 1, log, work, uv, confirmFrom)
    }
    val week = routine.filter { !it.date.isBefore(today) }.take(SKIN_SCHEDULE_DAYS)
    // An earlier day picked on the Calendar, from the day confirming began (before that nothing needs confirming).
    val earlier = routine.firstOrNull { it.date == selected && selected.isBefore(today) && confirmFrom != null && !selected.isBefore(confirmFrom) }

    GlassCard(modifier = Modifier.fillMaxWidth().devRef("1.5", "Skin care card"), contentPadding = 14.dp) {
        CardTitle("🧴 Skin care")
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            listOfNotNull(earlier).plus(week).forEachIndexed { index, day -> ScheduleRow(day, skin, today, selected, index + 1) }
        }
        skin.problem?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 6.dp)) }
    }
}

/**
 * One evening of the schedule: its day, what goes on (or a quiet "rest"), a pencil to choose another product for it (see
 * [EveningChoices]) and, if it can have been done by now, its tick.
 */
@Composable
private fun ScheduleRow(day: DayRoutine, skin: SkinCareStore, today: LocalDate, selected: LocalDate, position: Int) {
    val colors = MaterialTheme.colorScheme
    val log = skin.log
    val kind = log.usedOn(day.date) ?: day.eveningKind
    val rest = kind == EveningKind.REST
    val dropped = day.missed
    val done = log.doneOn(day.date)
    // Something to do, and not still to come: it can be ticked.
    val tickable = !rest && !day.date.isAfter(today)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (day.date == selected) Color.White.copy(alpha = 0.08f) else Color.Transparent, RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (day.date == today) "Today" else day.date.format(dayFmt),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (day.date == today) FontWeight.Bold else FontWeight.Normal,
            color = if (rest) colors.onSurfaceVariant else colors.onSurface,
            modifier = Modifier.width(92.dp),
        )
        if (rest) {
            Text("rest", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant.copy(alpha = 0.6f), modifier = Modifier.weight(1f))
        } else {
            EveningChip(kind, strength = if (dropped) 0.3f else 1f)
            Text(
                if (dropped) "${kind.label} — skipped" else kind.label,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = if (dropped) colors.onSurfaceVariant else colors.onSurface,
                modifier = Modifier.padding(start = 8.dp).weight(1f),
            )
        }
        var choosing by remember { mutableStateOf(false) }
        Box {
            EditButton(
                tip = if (rest) "Change this evening — put a product on it" else "Change the product for this evening",
                modifier = Modifier.devRef("1.5.7.$position", "Skin care edit (evening $position)"),
            ) { choosing = true }
            EveningChoices(
                expanded = choosing,
                date = day.date,
                current = kind,
                chosen = day.date in log.chosenKinds,
                onChoose = { skin.setChosen(day.date, it); choosing = false },
                onClose = { choosing = false },
            )
        }
        Spacer(Modifier.width(6.dp))
        if (tickable) {
            TickButton(
                done = done,
                tip = when {
                    done -> "Done — tap to take the tick back"
                    day.date == today -> "Tap when you've done tonight's routine"
                    else -> "Not done — tap if you did it"
                },
                modifier = Modifier.devRef(if (day.date == today) "1.5.5" else "1.5.6", if (day.date == today) "Skin care tick (tonight)" else "Skin care tick (earlier day)"),
            ) { skin.toggleEvening(day.date, day.eveningKind) }
        } else {
            Spacer(Modifier.width(TICK_BUTTON_DP.dp)) // keeps the pencils in a straight line down the card
        }
    }
}

/**
 * The drop-down list of what an evening can be: each product evening (the Findley treatment, the toner, niacinamide, Vitamin C, both
 * serums) and a rest night — the one it is now ticked ✓ — and, once a choice has been made, "Follow the plan" to take it back. Picking
 * one sets that evening and the plan works out the nights after it again at once ([SkinCareStore.setChosen]).
 */
@Composable
internal fun EveningChoices(expanded: Boolean, date: LocalDate, current: EveningKind, chosen: Boolean, onChoose: (EveningKind?) -> Unit, onClose: () -> Unit) {
    DropdownMenu(expanded = expanded, onDismissRequest = onClose) {
        Text(
            "Evening of " + date.format(dayFmt),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
        EveningKind.entries.forEach { kind ->
            DropdownMenuItem(
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        EveningChip(kind)
                        Column(modifier = Modifier.padding(start = 10.dp)) {
                            Text(kind.label, fontWeight = if (kind == current) FontWeight.Bold else FontWeight.Normal)
                            Text(eveningText(kind), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (kind == current) Text("  ✓", fontWeight = FontWeight.Bold)
                    }
                },
                onClick = { onChoose(kind) },
                modifier = Modifier.devRef("1.5.8.${kind.ordinal + 1}", "Skin care choice (${kind.label})"),
            )
        }
        if (chosen) {
            DropdownMenuItem(
                text = { Text("Follow the plan again", color = MaterialTheme.colorScheme.primary) },
                onClick = { onChoose(null) },
                modifier = Modifier.devRef("1.5.8.0", "Skin care choice (follow the plan)"),
            )
        }
    }
}

/** "Work 12:30–21:00 · UV 2 · 14°C": the day's work and weather in plain words ("UV low (a guess)" until a town is set). */
internal fun infoLine(work: WorkSchedule, date: LocalDate, reading: UvReading, isPast: Boolean): String {
    val shift = work.shiftOn(date)
    val workText = when {
        shift != null -> "Work ${shift.start.format(timeFmt)}–${shift.end.format(timeFmt)}" + if (shift.endsNextDay) " (+1)" else ""
        work.isOff(date) -> "Day off"
        else -> "Work unknown"
    }
    val weather = when {
        reading.basis == UvBasis.FORECAST -> reading.short() + (reading.tempMaxC?.let { " · ${it.roundToInt()}°C" } ?: "")
        isPast -> "" // nothing is known about the weather on an old day
        else -> "${reading.short()} (a guess)"
    }
    return listOf(workText, weather).filter { it.isNotEmpty() }.joinToString("  ·  ")
}
