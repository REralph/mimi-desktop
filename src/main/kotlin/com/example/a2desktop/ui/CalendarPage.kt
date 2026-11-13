package com.example.a2desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.a2desktop.data.DesktopStore
import com.example.a2desktop.data.SkinCareStore
import com.example.a2desktop.data.VitaminStore
import com.example.a2desktop.data.WeatherStore
import com.example.a2desktop.link.EditResult
import com.example.a2desktop.link.LinkController
import com.example.a2desktop.logic.EveningKind
import com.example.a2desktop.logic.UvLookup
import com.example.a2desktop.logic.DayVitaminMark
import com.example.a2desktop.logic.ZincPlan
import com.example.a2desktop.logic.Supplement
import com.example.a2desktop.logic.supplementState
import com.example.a2desktop.logic.vitaminMarksOn
import com.example.a2desktop.logic.planRoutine
import com.example.a2desktop.data.CalendarEmojiStore
import com.example.a2desktop.data.DayEmoji
import com.example.a2desktop.data.DayItem
import com.example.a2desktop.logic.emojiCounts
import com.example.a2desktop.logic.frequentEmojis
import com.example.a2desktop.logic.labelRepeats
import com.example.a2desktop.model.UpcomingItem
import com.example.a2desktop.model.UpcomingKind
import com.example.a2desktop.theme.GlassCard
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

private val monthTitleFmt = DateTimeFormatter.ofPattern("MMMM yyyy")
private val dayTitleFmt = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy")
private val shortDateFmt = DateTimeFormatter.ofPattern("EEE d MMM")

/**
 * Calendar — the phone's pages 0.1 (Main Calendar), 0.3 (Calendar Information) and 0.14 (Upcoming)
 * on one wide screen, in three columns: on the left the Skin care and Supplements cards, in the middle the month grid, and on the right
 * the Habits card and the Tasks card (the selected day — what is on it, and its habits — with the Upcoming list), instead of everything
 * being stacked below the grid.
 * Each day cell also carries the phone's work times (from Fourth Better); the skin-care routine and the vitamins are
 * planned and kept on this PC only. What is planned but not yet ticked (the tick in the Skin care card) is drawn faded on its
 * day, and a day that goes unticked drops off.
 */
@Composable
fun CalendarPage(
    store: DesktopStore,
    link: LinkController? = null,
    skinCare: SkinCareStore = remember { SkinCareStore() },
    weather: WeatherStore = remember { WeatherStore() },
    vitamins: VitaminStore = remember { VitaminStore() },
    emojis: CalendarEmojiStore = remember { CalendarEmojiStore() },
) {
    // The emoji already in use, most used first: those typed into the phone's titles and those picked here.
    val frequent = frequentEmojis(emojiCounts(store.events.map { it.title } + store.upcoming.map { it.title }), emojis.uses)
    // The days a workout was recorded (on the phone): when the amino acids are taken.
    val trainedDays = remember(store.sessions) { store.sessions.map { it.date }.toSet() }
    // When zinc is scheduled: worked out from what has been ticked and unticked, so a dose said no to moves the rest.
    val zinc = remember(store.today, vitamins.settings, vitamins.marks, vitamins.trackedFrom, vitamins.tickFrom) { vitamins.zincPlan(store.today) }
    // The sun the skin-care plan goes by: the forecast for the user's town, else the seasonal estimate.
    val forecast = weather.forecast
    val uv = remember(forecast) { weather.uvLookup() }
    var month by remember { mutableStateOf(YearMonth.from(store.today)) }
    var selected by remember { mutableStateOf(store.today) }
    // The year shown on the Month Overview (the phone's 0.7), or null while the month grid is showing.
    var overviewYear by remember { mutableStateOf<Int?>(null) }

    val year = overviewYear
    if (year != null) {
        YearOverview(store, year, onYearChange = { overviewYear = it }, onBack = { overviewYear = null })
        return
    }

    Row(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        // On the left: the skin-care days (where tonight is ticked off) and the supplement times.
        ScrollColumn(
            modifier = Modifier.width(LEFT_COLUMN_DP.dp).fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SkinCareCard(skinCare, store.workSchedule, uv, store.today, selected)
            VitaminsCard(vitamins, store.today, selected, trainedDays)
        }

        Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
            PageHeader(
                title = month.format(monthTitleFmt),
                subtitle = "Click a day to see it",
                modifier = Modifier.devRef("1.1", "Page header"),
                // As on the phone, the month's name opens the whole year; the button is the same thing, in plain sight.
                onTitleClick = { overviewYear = month.year },
            ) {
                IconButton(onClick = { month = month.minusMonths(1) }) { Text("‹", fontSize = 26.sp) }
                OutlinedButton(onClick = {
                    month = YearMonth.from(store.today)
                    selected = store.today
                }) { Text("Today") }
                IconButton(onClick = { month = month.plusMonths(1) }) { Text("›", fontSize = 26.sp) }
                OutlinedButton(
                    onClick = { overviewYear = month.year },
                    contentPadding = PaddingValues(horizontal = 14.dp),
                    modifier = Modifier.devRef("1.1.1", "Year button"),
                ) { Text("Year") }
            }
            Spacer(Modifier.height(14.dp))
            // The weekday names and the grid are one group: the names are just its header row.
            Column(modifier = Modifier.weight(1f).fillMaxWidth().devRef("1.2", "Month grid")) {
                WeekdayRow()
                Spacer(Modifier.height(6.dp))
                MonthGrid(store, month, selected, onSelect = { selected = it }, skin = skinCare, uv = uv, zinc = zinc, vitamins = vitamins, emojis = emojis, trainedDays = trainedDays, modifier = Modifier.weight(1f).fillMaxWidth())
            }
        }

        ScrollColumn(
            modifier = Modifier.width(410.dp).fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // On the right: the Habits card first, then the Tasks card — the selected day (its events and tasks, then its habits as
            // emojis) and the Upcoming list in one card.
            InformationCard(store)
            TasksCard(store, selected, link, emojis, frequent)
        }
    }
}

/**
 * The width of the column on the left of the Calendar (the Skin care and Supplements cards): as narrow as lets every row in them break
 * only between words ("Niacinamide" splits in the middle at 280), so the month grid gets the rest — it was 360.
 */
internal const val LEFT_COLUMN_DP = 300

@Composable
private fun WeekdayRow() {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        DayOfWeek.entries.forEach { day ->
            Text(
                day.getDisplayName(TextStyle.SHORT, Locale.getDefault()).uppercase(),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun MonthGrid(
    store: DesktopStore,
    month: YearMonth,
    selected: LocalDate,
    onSelect: (LocalDate) -> Unit,
    skin: SkinCareStore,
    uv: UvLookup,
    zinc: ZincPlan,
    vitamins: VitaminStore,
    emojis: CalendarEmojiStore,
    trainedDays: Set<LocalDate>,
    modifier: Modifier = Modifier,
) {
    val first = month.atDay(1)
    val gridStart = first.minusDays((first.dayOfWeek.value - 1).toLong()) // weeks start on Monday, like the phone
    // The evening's skin-care product for every day from today to the end of the grid.
    val log = skin.log
    val gridEnd = gridStart.plusDays(41)
    // The routine from the day it began, as far as the end of this grid (a day nobody ticked counts as followed).
    val plan = remember(store.today, skin.startedOn, skin.confirmFrom, gridEnd, log, store.workSchedule, uv) {
        val ahead = ChronoUnit.DAYS.between(store.today, gridEnd).toInt().coerceAtLeast(0)
        planRoutine(store.today, skin.startedOn, ahead, log, store.workSchedule, uv, skin.confirmFrom).associateBy { it.date }
    }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(6) { row ->
            Row(modifier = Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(7) { col ->
                    val date = gridStart.plusDays((row * 7 + col).toLong())
                    val dots = store.habits
                        .filter { it.linkedToCalendar && it.id in store.habitsOn(date) }
                        .map { it.color }
                    // The events and tasks of the day — the same list the Selected day card shows for it.
                    val items = emojis.itemsOn(date, store.events, store.upcoming, store.today, store.workSchedule)
                    DayCell(
                        date = date,
                        inMonth = YearMonth.from(date) == month,
                        isToday = date == store.today,
                        isSelected = date == selected,
                        dots = dots,
                        // An event with no emoji is a small square marker; one with an emoji shows the emoji instead (below).
                        hasEvent = emojis.hasPlainEvent(items),
                        // What is on the day, as emoji: shaded until it is done, full colour once it is (see CalendarEmojiStore.itemsOn).
                        emojis = emojis.emojiMarks(items),
                        // The supplements that are not every day: zinc and the amino acids (on days a workout was recorded) —
                        // solid when ticked, a little softer when only presumed, faded for a day to come, gone when said no to.
                        vitaminMarks = vitaminMarksOn(
                            date,
                            zinc,
                            supplementState(date, store.today, scheduled = !date.isAfter(store.today) && date in trainedDays, mark = vitamins.markOn(date, Supplement.AMINO_ACIDS), tickFrom = vitamins.tickFrom),
                        ),
                        // What the evening was or is: what was recorded, else the plan, faded until it is confirmed. A past day
                        // from after confirming began that wasn't confirmed has dropped off (nothing to show); one from before
                        // counts as followed. A rest night is left blank on purpose: nothing is put on, so there is nothing to mark.
                        evening = (log.usedOn(date) ?: plan[date]?.takeIf { !it.missed }?.eveningKind)?.takeIf { it != EveningKind.REST }?.let {
                            EveningMark(it, done = log.doneOn(date), assumed = date.isBefore(store.today) && !log.doneOn(date))
                        },
                        work = store.workSchedule.let { schedule ->
                            val shift = schedule.shiftOn(date)
                            when {
                                shift != null -> CellWork(shift.start.format(cellTimeFmt), shift.end.format(cellTimeFmt))
                                schedule.isOff(date) -> CellWork(off = true)
                                else -> null
                            }
                        },
                        onClick = { onSelect(date) },
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
            }
        }
    }
}

/** The skin-care product for a day's evening, and whether it has been done (solid) or is only planned (dimmer). */
private data class EveningMark(val kind: EveningKind, val done: Boolean, val assumed: Boolean = false)

/** What a day cell says about work: a shift's start and end (formatted), or OFF. */
private data class CellWork(val start: String? = null, val end: String? = null, val off: Boolean = false)

private val cellTimeFmt = java.time.format.DateTimeFormatter.ofPattern("HH:mm")

@Composable
private fun DayCell(
    date: LocalDate,
    inMonth: Boolean,
    isToday: Boolean,
    isSelected: Boolean,
    dots: List<Color>,
    hasEvent: Boolean,
    emojis: List<DayEmoji>,
    vitaminMarks: List<DayVitaminMark>,
    evening: EveningMark?,
    work: CellWork?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(14.dp)
    val fade = if (inMonth) 1f else 0.35f
    val numberSize = 24.dp
    val gap = 4.dp
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val baseStyle = LocalTextStyle.current // measure with the same font the Text will really use
    BoxWithConstraints(
        modifier = modifier
            // Today wears a red neon frame: its glow spills a little outside the cell, so it is drawn before the clip.
            .neonGlow(14.dp, enabled = isToday)
            .clip(shape)
            // As on the phone, a day with a shift gets a faint extra tint.
            .background(
                when {
                    isSelected -> colors.primaryContainer.copy(alpha = 0.35f)
                    work?.start != null -> Color.White.copy(alpha = 0.08f)
                    else -> Color.White.copy(alpha = 0.04f)
                },
            )
            // The selected day has a lilac border; today's neon line takes its place (today then shows as selected by its tint).
            .border(if (isSelected && !isToday) 1.5.dp else 0.dp, if (isSelected && !isToday) colors.primary else Color.Transparent, shape)
            .neonLine(14.dp, enabled = isToday)
            .clickable(onClick = onClick)
            .padding(7.dp),
    ) {
        // When the user works (from the phone): "start–end" on ONE line to the right of the day number, in the
        // biggest size that fits what is left of the cell's width (cells shrink with the window). Only when even
        // the smallest doesn't fit does it fall back to two short lines under the number.
        val times = work?.takeIf { it.start != null }?.let { w ->
            buildAnnotatedString {
                withStyle(SpanStyle(color = colors.primary.copy(alpha = fade), fontWeight = FontWeight.SemiBold)) { append(w.start.orEmpty()) }
                withStyle(SpanStyle(color = colors.onSurfaceVariant.copy(alpha = fade))) { append("–" + w.end.orEmpty()) }
            }
        }
        val room = maxWidth - numberSize - gap - 2.dp
        val oneLineSize = times?.let { text ->
            listOf(12.sp, 11.sp, 10.sp, 9.sp).firstOrNull { size ->
                with(density) { measurer.measure(text, baseStyle.copy(fontSize = size), softWrap = false, maxLines = 1).size.width.toDp() } <= room
            }
        }
        val offBeside = work?.off == true && with(density) {
            measurer.measure("OFF", baseStyle.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold), softWrap = false, maxLines = 1).size.width.toDp()
        } <= room
        Row(modifier = Modifier.align(Alignment.TopStart), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(numberSize).background(if (isToday) colors.primary else Color.Transparent, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    date.dayOfMonth.toString(),
                    fontSize = 14.sp,
                    lineHeight = 16.sp,
                    fontWeight = if (isToday) FontWeight.Bold else FontWeight.Medium,
                    color = if (isToday) colors.onPrimary else colors.onSurface.copy(alpha = fade),
                )
            }
            if (times != null && oneLineSize != null) {
                Spacer(Modifier.width(gap))
                Text(times, fontSize = oneLineSize, lineHeight = oneLineSize, maxLines = 1, softWrap = false)
            } else if (offBeside) {
                Spacer(Modifier.width(gap))
                Text("OFF", color = colors.onSurfaceVariant.copy(alpha = fade * 0.8f), fontSize = 10.sp, lineHeight = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
            }
        }
        // Too narrow for one line beside the number: the old stacked layout under it.
        if (work?.start != null && oneLineSize == null) {
            Column(modifier = Modifier.align(Alignment.TopStart).padding(top = numberSize + 3.dp)) {
                Text(work.start, color = colors.primary.copy(alpha = fade), fontSize = 11.sp, lineHeight = 13.sp, fontWeight = FontWeight.SemiBold)
                Text(work.end.orEmpty(), color = colors.onSurfaceVariant.copy(alpha = fade), fontSize = 11.sp, lineHeight = 13.sp)
            }
        } else if (work?.off == true && !offBeside) {
            Text(
                "OFF", color = colors.onSurfaceVariant.copy(alpha = fade * 0.8f), fontSize = 10.sp, lineHeight = 12.sp, fontWeight = FontWeight.Bold,
                maxLines = 1, softWrap = false, modifier = Modifier.align(Alignment.TopStart).padding(top = numberSize + 3.dp),
            )
        }
        // Bottom right: tonight's skin-care product (a small coloured letter) and the event marker.
        Row(
            modifier = Modifier.align(Alignment.BottomEnd),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (evening != null) EveningChip(evening.kind, strength = (if (evening.done) 1f else if (evening.assumed) 0.8f else 0.55f) * fade)
            if (hasEvent) {
                Box(Modifier.size(7.dp).background(colors.tertiary.copy(alpha = fade), RoundedCornerShape(2.dp)))
            }
        }
        Row(modifier = Modifier.align(Alignment.BottomStart), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            dots.forEach { Dot(it.copy(alpha = fade), size = 9.dp) }
        }
        // Just above the habit dots: "Zn" on a zinc day, "AA" on a training day (outlined, unlike the skin-care letters).
        // Only when the cell has the room: not in a short cell, nor where the work times had to stack on two lines.
        val twoLineTimes = work?.start != null && oneLineSize == null
        // With the times stacked on two lines (a narrow cell, as the month grid is now the middle of three columns) there is room for them only when
        // the cell is tall enough, and not when the cell also has emoji in its middle: the marks then give way, as they always did.
        if (vitaminMarks.isNotEmpty() && maxHeight >= (if (twoLineTimes) 84.dp else 58.dp) && !(twoLineTimes && emojis.isNotEmpty())) {
            Row(modifier = Modifier.align(Alignment.BottomStart).padding(bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                vitaminMarks.forEach { VitaminChip(it.mark, strength = fade * it.state.markStrength()) }
            }
        }
        // In the middle of the cell: the day's events and tasks as emoji — shaded while still to do, full colour once done.
        if (emojis.isNotEmpty()) {
            val shown = emojis.take(3)
            Row(
                modifier = Modifier.align(Alignment.CenterStart).padding(top = if (twoLineTimes) 22.dp else 8.dp),
                horizontalArrangement = Arrangement.spacedBy(1.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                shown.forEach { Text(it.emoji, fontSize = 18.sp, lineHeight = 20.sp, maxLines = 1, softWrap = false, modifier = Modifier.alpha(fade * if (it.done) 1f else SHADED_EMOJI)) }
                if (emojis.size > shown.size) Text("+${emojis.size - shown.size}", fontSize = 10.sp, color = colors.onSurfaceVariant.copy(alpha = fade), maxLines = 1, softWrap = false)
            }
        }
    }
}

/** How faint an emoji is drawn while what it stands for is not done yet (it was 0.32; raised 5%, 0.32 x 1.05, so they show ever so slightly more). */
internal const val SHADED_EMOJI = 0.336f

/**
 * The Tasks card: one card that holds the selected day and the Upcoming list. The "Tasks" header opens and closes it; open (as it
 * starts), the selected day comes first — its date, what is on it, its habits and the Add buttons — and under it the chronological
 * Upcoming list. Pick another day on the Calendar and the day part changes with it. Nothing about the events, tasks and habits
 * themselves is different from when these were two cards; this only puts them together.
 */
@Composable
private fun TasksCard(store: DesktopStore, date: LocalDate, link: LinkController?, emojis: CalendarEmojiStore, frequent: List<String>) {
    var open by remember { mutableStateOf(true) }
    GlassCard(modifier = Modifier.fillMaxWidth().devRef("1.4", "Tasks card")) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(8.dp)).clickable { open = !open },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Tasks", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(if (open) "▾" else "▸", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (open) {
            SelectedDayPart(store, date, link, emojis, frequent)
            Box(Modifier.fillMaxWidth().padding(vertical = 14.dp).height(1.dp).background(Color.White.copy(alpha = 0.08f)))
            UpcomingPart(store, link, emojis, date, frequent)
        }
    }
}

/**
 * The selected day: its date, then a table of everything on it — the events and tasks, each with its emoji and when — then the
 * habits to record on it. What is listed comes from the phone's events and Upcoming list (repeating ones included on the days they
 * fall on), the same list the month grid draws its emoji from. Pick another day and all of it changes with it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SelectedDayPart(store: DesktopStore, date: LocalDate, link: LinkController?, emojis: CalendarEmojiStore, frequent: List<String>) {
    // With the phone's data a tap is sent to the phone (it applies its own rules and answers with what
    // it now holds); this says what went wrong if it couldn't.
    var problem by remember(date) { mutableStateOf<String?>(null) }
    var addingTask by remember { mutableStateOf(false) }
    var choosing by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<String?>(null) }
    var repeating by remember { mutableStateOf<String?>(null) }
    val phoneData = store.readOnly && link != null
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(date.format(dayTitleFmt), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (date == store.today) {
                Text("TODAY", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            }
        }
        DayTable(
            emojis.itemsOn(date, store.events, store.upcoming, store.today, store.workSchedule), emojis, date, store.today,
            onEmoji = { choosing = it },
            onEdit = if (phoneData) ({ editing = it }) else null,
            onRepeat = if (phoneData) ({ repeating = it }) else null,
        )
        // The habits, each as just its emoji (its name shows when the pointer rests on it): tap one to record it on this day,
        // tap it again to clear it. The streak habit is worked out, never recorded, so it has no button.
        val recorded = store.logs[date] ?: emptySet()
        val loggable = store.habits.filter { !it.isStreak }
        if (loggable.isNotEmpty()) {
            Text("Habits", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.devRef("1.4.3", "Habit buttons", fullWidthContent = true),
            ) {
                loggable.forEach { habit ->
                    EmojiButton(
                        habit = habit,
                        on = habit.id in recorded,
                        enabled = if (phoneData) !link!!.editing else !store.readOnly,
                        onClick = {
                            if (phoneData) {
                                problem = null
                                link!!.setHabit(date, habit.id, logged = habit.id !in recorded) { result ->
                                    if (result is EditResult.Failed) problem = result.message
                                }
                            } else {
                                store.toggleHabit(date, habit.id)
                            }
                        },
                    )
                }
            }
        }
        problem?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
        }
        if (phoneData) {
            // One button: a task is either something to do once or something that comes round every so many days, weeks, months or years.
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().padding(top = 14.dp).devRef("1.4.5", "Add task button")) {
                OutlinedButton(onClick = { addingTask = true }) { Text("Add task") }
            }
        }
    }
    if (addingTask && link != null) AddTaskDialog(date, store.today, link, emojis, frequent, store.upcoming, store.workSchedule) { addingTask = false }
    choosing?.let { title ->
        EmojiPickerDialog(title, emojis.emojiFor(title), frequent, onChoose = { emojis.setEmoji(title, it) }, onClose = { choosing = null })
    }
    editing?.let { ref -> if (link != null) EditItemDialog(ref, store, link, emojis, frequent) { editing = null } }
    repeating?.let { title ->
        if (link != null) RepeatItDialog(title, date, store.today, link, emojis, store.upcoming, store.workSchedule) { repeating = null }
    }
}

/**
 * The events and tasks of a day as a small table — emoji, title (with what kind and how it repeats), and when — or "Nothing
 * scheduled". With the phone's data ([onEdit] not null) each row that is on the phone has a small pen: it opens the window to change or
 * delete it, given the phone's id for it. A row that is only a tick kept here, with nothing of that name on the phone ([DayItem.repeatable]),
 * has the same pen, opening "Make it repeat" ([onRepeat]) instead — so something done once, like a haircut, can be made to come round again.
 */
@Composable
private fun DayTable(items: List<DayItem>, emojis: CalendarEmojiStore, date: LocalDate, today: LocalDate, onEmoji: (String) -> Unit, onEdit: ((String) -> Unit)?, onRepeat: ((String) -> Unit)?) {
    Column(modifier = Modifier.fillMaxWidth().devRef("1.4.6", "Events and tasks table")) {
        if (items.isEmpty()) {
            Text("Nothing scheduled", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 4.dp))
        }
        items.forEachIndexed { index, item ->
            if (index > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.07f)))
            val ref = item.ref
            DayRow(
                item, emojis.displayFor(item.title), date, today, onEmoji = { onEmoji(item.title) },
                onEdit = when {
                    onEdit != null && ref != null -> ({ onEdit(ref) })
                    onRepeat != null && item.repeatable -> ({ onRepeat(item.title) })
                    else -> null
                },
                refBase = "1.4.6.${index + 1}",
                // Something that repeats (a task or event a week or more apart can be given a smart day by tapping the symbol).
                repeatMark = { markModifier ->
                    TaskRepeatMark(item.title, item.repeat, item.kind != null, null, null, emojis, "1.4.6.${index + 1}.1", markModifier)
                },
                modifier = Modifier.devRef("1.4.6.${index + 1}", "Day item ${index + 1}"),
            )
        }
    }
}

/** A task not done on a day that has already passed. */
internal fun DayItem.isOverdue(date: LocalDate, today: LocalDate): Boolean = !done && kind == UpcomingKind.TASK && date.isBefore(today)

/** What a row of the table says on its right: Done; an event's time, or "All day"; Overdue for a task whose day has passed; otherwise Due. */
internal fun DayItem.whenLabel(date: LocalDate, today: LocalDate): String = when {
    done -> "Done"
    kind == UpcomingKind.EVENT -> time ?: "All day"
    isOverdue(date, today) -> "Overdue"
    else -> "Due"
}

/** Whether a row gets the small repeat symbol: it comes round again (the phone's label says how, e.g. "Every week"; the symbol's tip shows it). */
internal fun DayItem.repeats(): Boolean = labelRepeats(repeat)

/** What an Upcoming row says under its title: when it was done, once it has been ticked off; nothing otherwise (no "Task", no repeat words). */
internal fun doneNote(doneOn: LocalDate?, today: LocalDate): String? =
    doneOn?.let { "Done " + if (it == today) "today" else it.format(shortDateFmt) }

@Composable
private fun DayRow(item: DayItem, emoji: List<String>, date: LocalDate, today: LocalDate, onEmoji: () -> Unit, onEdit: (() -> Unit)?, refBase: String, repeatMark: @Composable (Modifier) -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val overdue = item.isOverdue(date, today)
    Row(modifier = modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        // Its emoji: shaded until it is done, full colour after; tap to choose or change it ("＋" when it has none).
        Box(
            modifier = Modifier.defaultMinSize(minWidth = 28.dp, minHeight = 28.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onEmoji),
            contentAlignment = Alignment.Center,
        ) {
            if (emoji.isEmpty()) {
                Text("＋", fontSize = 14.sp, color = colors.onSurfaceVariant.copy(alpha = 0.45f))
            } else {
                Text(emoji.take(2).joinToString(""), fontSize = 18.sp, lineHeight = 20.sp, maxLines = 1, softWrap = false, modifier = Modifier.alpha(if (item.done) 1f else SHADED_EMOJI))
            }
        }
        // The title, and — when it comes round again — a small repeat symbol straight after it (the words are in its hover tip).
        Row(modifier = Modifier.weight(1f).padding(start = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                item.title, maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textDecoration = if (item.done) TextDecoration.LineThrough else null,
                color = if (item.done) colors.onSurfaceVariant else colors.onSurface,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (item.repeats()) repeatMark(Modifier.padding(start = 6.dp))
        }
        Text(
            item.whenLabel(date, today),
            style = MaterialTheme.typography.labelMedium,
            color = if (overdue) colors.error else colors.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp),
        )
        if (onEdit != null) RowPen(onEdit, Modifier.devRef("$refBase.2", "Edit (day item)"))
    }
}

/** The small pen at the right of a row: opens the window to change or delete that task (or event), give it an emoji, or mark it done on a day. */
@Composable
private fun RowPen(onClick: () -> Unit, modifier: Modifier = Modifier) {
    EditButton("Edit", modifier.padding(start = 8.dp), onClick)
}

@Composable
private fun InformationCard(store: DesktopStore) {
    GlassCard(modifier = Modifier.fillMaxWidth().devRef("1.6", "Habits card")) {
        CardTitle("Habits")
        store.infoLines().forEach { line ->
            Text(
                line.text,
                color = line.habit.color,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(vertical = 3.dp),
            )
        }
    }
}

/** The day a tick on the Upcoming list records: the day selected on the Calendar, or today when that day is still to come. */
internal fun completionDay(selected: LocalDate, today: LocalDate): LocalDate = if (selected.isAfter(today)) today else selected

/**
 * The Upcoming list inside the Tasks card: each item with its emoji — shaded while it is still to do, full colour once it is done — and a tick to say it is
 * done. A tick marks it done on the day selected on the Calendar (today if that day is still to come), which is where its
 * full-colour emoji then appears; tick it again to take it back. Tap the emoji to choose or change it. Done and emoji are
 * kept on this PC only (see [CalendarEmojiStore]); the phone isn't told.
 */
@Composable
private fun UpcomingPart(store: DesktopStore, link: LinkController?, emojis: CalendarEmojiStore, selected: LocalDate, frequent: List<String>) {
    var choosing by remember { mutableStateOf<UpcomingItem?>(null) }
    var editing by remember { mutableStateOf<String?>(null) }
    val phoneData = store.readOnly && link != null
    val doneDay = completionDay(selected, store.today)
    Column(modifier = Modifier.fillMaxWidth().devRef("1.4.7", "Upcoming list")) {
        Text("Upcoming", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 4.dp))
        if (store.upcoming.isEmpty()) {
            Text("Nothing coming up.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text(
                "Tick one off to mark it done " + if (doneDay == store.today) "today" else "on ${doneDay.format(shortDateFmt)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // Each is listed on the day it lands on — its own date, or for a repeating task with a smart day, the day of that kind in its period.
        store.upcoming.map { it to emojis.shownDue(it, store.today, store.workSchedule) }.sortedBy { it.second.date }.forEachIndexed { index, (item, shown) ->
            val doneOn = emojis.doneOn(item.title, item.due)
            UpcomingRow(
                item,
                emoji = emojis.displayFor(item.title),
                doneOn = doneOn,
                shownOn = shown.date,
                repeatMark = { markModifier ->
                    TaskRepeatMark(item.title, item.repeat, true, item.due, shown, emojis, "1.4.7.${index + 1}.1", markModifier)
                },
                today = store.today,
                onToggle = { if (doneOn != null) emojis.undo(item.title, item.due) else emojis.complete(item.title, item.due, doneDay) },
                onEmoji = { choosing = item },
                onEdit = if (phoneData) ({ editing = item.id }) else null,
                refBase = "1.4.7.${index + 1}",
                modifier = Modifier.devRef("1.4.7.${index + 1}", "Upcoming item ${index + 1}"),
            )
        }
    }
    choosing?.let { item ->
        EmojiPickerDialog(item.title, emojis.emojiFor(item.title), frequent, onChoose = { emojis.setEmoji(item.title, it) }, onClose = { choosing = null })
    }
    editing?.let { ref -> if (link != null) EditItemDialog(ref, store, link, emojis, frequent) { editing = null } }
}

@Composable
private fun UpcomingRow(
    item: UpcomingItem,
    emoji: List<String>,
    doneOn: LocalDate?,
    shownOn: LocalDate,
    repeatMark: @Composable (Modifier) -> Unit,
    today: LocalDate,
    onToggle: () -> Unit,
    onEmoji: () -> Unit,
    onEdit: (() -> Unit)?,
    refBase: String,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val done = doneOn != null
    Row(modifier = modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(if (done) colors.primary else Color.Transparent)
                .border(1.5.dp, if (done) colors.primary else colors.onSurfaceVariant, CircleShape)
                .clickable(onClick = onToggle),
            contentAlignment = Alignment.Center,
        ) {
            if (done) Text("✓", fontSize = 12.sp, lineHeight = 12.sp, color = colors.onPrimary, fontWeight = FontWeight.Bold)
        }
        // Its emoji: shaded until it is done, full colour after; tap to choose or change it ("＋" when it has none).
        Box(
            modifier = Modifier.padding(start = 6.dp).defaultMinSize(minWidth = 28.dp, minHeight = 28.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onEmoji),
            contentAlignment = Alignment.Center,
        ) {
            if (emoji.isEmpty()) {
                Text("＋", fontSize = 14.sp, color = colors.onSurfaceVariant.copy(alpha = 0.45f))
            } else {
                Text(emoji.take(2).joinToString(""), fontSize = 18.sp, lineHeight = 20.sp, maxLines = 1, softWrap = false, modifier = Modifier.alpha(if (done) 1f else SHADED_EMOJI))
            }
        }
        Column(modifier = Modifier.weight(1f).padding(start = 6.dp)) {
            // The title, and — for what comes round again — the small repeat symbol straight after it (the words are in its hover tip).
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    item.title,
                    textDecoration = if (done) TextDecoration.LineThrough else null,
                    color = if (done) colors.onSurfaceVariant else colors.onSurface,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (labelRepeats(item.repeat)) repeatMark(Modifier.padding(start = 6.dp))
            }
            // Only once it is ticked off is there a line under the title: when it was done.
            doneNote(doneOn, today)?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant) }
        }
        val days = ChronoUnit.DAYS.between(today, shownOn)
        Text(
            when (days) {
                0L -> "Today"
                1L -> "Tomorrow"
                else -> shownOn.format(shortDateFmt)
            },
            style = MaterialTheme.typography.labelMedium,
            color = if (days == 0L) colors.tertiary else colors.onSurfaceVariant,
        )
        if (onEdit != null) RowPen(onEdit, Modifier.devRef("$refBase.2", "Edit (upcoming item)"))
    }
}
