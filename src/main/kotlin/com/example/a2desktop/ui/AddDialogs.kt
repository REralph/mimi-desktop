package com.example.a2desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.example.a2desktop.data.CalendarEmojiStore
import com.example.a2desktop.link.EditResult
import com.example.a2desktop.link.LinkController
import com.example.a2desktop.link.LinkTaskCreate
import com.example.a2desktop.logic.DayPick
import com.example.a2desktop.logic.Repeat
import com.example.a2desktop.logic.SMART_MIN_DAYS
import com.example.a2desktop.logic.WorkSchedule
import com.example.a2desktop.logic.approxDays
import com.example.a2desktop.logic.snapDay
import com.example.a2desktop.logic.upcomingWithTitle
import com.example.a2desktop.logic.windowDays
import com.example.a2desktop.model.UpcomingItem
import com.example.a2desktop.model.UpcomingKind
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

private val dialogDateFmt = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy")
private val shortDateFmt = DateTimeFormatter.ofPattern("EEE d MMM")

/** How many of the most used emoji a form offers for one-tap choosing (the ＋ picker lists them all and more). */
private const val QUICK_EMOJIS = 6

/** The repeat a form's choice stands for (every [interval] days, weeks, months or years), or null for "doesn't repeat" or a count that isn't valid. */
internal fun taskRepeatSpec(repeat: TaskRepeat, interval: Int?): Repeat? {
    if (interval == null) return null
    val unit = when (repeat) {
        TaskRepeat.NEVER -> return null
        TaskRepeat.DAYS -> ChronoUnit.DAYS
        TaskRepeat.WEEKS -> ChronoUnit.WEEKS
        TaskRepeat.MONTHS -> ChronoUnit.MONTHS
        TaskRepeat.YEARS -> ChronoUnit.YEARS
    }
    return Repeat(interval, unit)
}

/** A repeat count 1..[max]; 0 for blank (no repeat); null when it isn't a valid count. */
internal fun parseRepeatCount(text: String, max: Int): Int? {
    val t = text.trim()
    if (t.isEmpty()) return 0
    val n = t.toIntOrNull() ?: return null
    return if (n in 1..max) n else null
}

/** How a task repeats, as offered in the form; [unit] is the phone's RepeatUnit name. */
internal enum class TaskRepeat(val label: String, val unit: String) {
    NEVER("Doesn't repeat", "NONE"),
    DAYS("Days", "DAYS"),
    WEEKS("Weeks", "WEEKS"),
    MONTHS("Months", "MONTHS"),
    YEARS("Years", "YEARS"),
}

/**
 * What the user picked in a form's emoji picker: nothing yet (so the emoji already remembered for that title shows), an emoji,
 * or "no emoji". Shared by the add and edit forms; an edit form starts with the emoji the item has now ([initial]), so renaming
 * it carries its emoji to the new title.
 */
internal class EmojiPick(initial: String? = null) {
    var picked by mutableStateOf(initial)
        private set
    var none by mutableStateOf(false)
        private set

    /** The emoji in force for something called [title]: the one picked, else the one remembered for that title. */
    fun current(title: String, emojis: CalendarEmojiStore): String? = if (none) null else picked ?: emojis.emojiFor(title)

    /** Picks [emoji] (null = no emoji). Returns the title that emoji was last used for when [title] is still empty, so a frequent emoji fills it in. */
    fun choose(emoji: String?, title: String, emojis: CalendarEmojiStore): String? {
        if (emoji == null) {
            picked = null
            none = true
            return null
        }
        picked = emoji
        none = false
        return if (title.isBlank()) emojis.titleFor(emoji) else null
    }

    /** Like [save], but only when the choice differs from what [title] has now — an edit that leaves the emoji alone doesn't count as using it again. */
    fun saveIfChanged(title: String, emojis: CalendarEmojiStore) {
        if (current(title, emojis) != emojis.emojiFor(title)) save(title, emojis)
    }

    /** Remembers the choice for [title] once the phone has taken the item. */
    fun save(title: String, emojis: CalendarEmojiStore) {
        val emoji = current(title, emojis)
        if (none) emojis.setEmoji(title, null) else if (emoji != null) emojis.setEmoji(title, emoji)
    }
}

/**
 * What the forms say when the title typed is already on the Upcoming list ([same], soonest first): such a thing is ONE item with
 * its own repeat (or none), so it is not added a second time — its kind, how it repeats and when it is next due, as a sentence.
 */
internal fun alreadyOnListText(same: List<UpcomingItem>): String {
    val first = same.first()
    val what = listOfNotNull(if (first.kind == UpcomingKind.EVENT) "Event" else "Task", first.repeat, "next ${first.due.format(shortDateFmt)}").joinToString(" · ")
    val more = same.size - 1
    return "“${first.title}” is already on your list ($what)" + (if (more > 0) " and $more more with this name" else "") + "."
}

/** The notice itself: what is already there, and — for today or an earlier day — one button to mark that thing done on [date] instead of adding it again. */
@Composable
private fun AlreadyOnListNotice(same: List<UpcomingItem>, date: LocalDate, today: LocalDate, onMarkDone: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.12f)).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(alreadyOnListText(same), style = MaterialTheme.typography.bodyMedium)
        if (!date.isAfter(today)) {
            TextButton(onClick = onMarkDone, contentPadding = PaddingValues(0.dp)) { Text("Mark it done on ${date.format(shortDateFmt)}") }
        } else {
            Text("It is kept once: tick it off in Upcoming when it is done.", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The "already done" tick of the forms for today or an earlier day: such a thing is shown in full colour on its day. */
@Composable
private fun AlreadyDoneRow(checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text("Already done — show it in full colour", style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * "Add a task" due on [date]: an emoji (typed, picked from the ones already in use, or chosen with the ＋ picker), a name and how it
 * repeats — not at all, or every so many days, weeks, months or years. This one form is how everything is added from here (it used to be
 * two: events and tasks): a task is something to do, once or every so often, and it joins the Upcoming list. The task is sent to the
 * phone, which checks and adds it itself — the form stays open (with the phone's reason) if the phone says no or can't be reached, and
 * closes once the phone has made the change. The emoji, and "already done" for today or an earlier day, are kept on this PC only (see
 * [CalendarEmojiStore]); picking a frequent emoji fills the name in.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AddTaskDialog(date: LocalDate, today: LocalDate, link: LinkController, emojis: CalendarEmojiStore, frequent: List<String>, existing: List<UpcomingItem>, schedule: WorkSchedule, onClose: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf(TaskRepeat.NEVER) }
    var every by remember { mutableStateOf("1") }
    var dayPick by remember { mutableStateOf(DayPick.ANY) }
    var problem by remember { mutableStateOf<String?>(null) }
    val pick = remember { EmojiPick() }
    var alreadyDone by remember { mutableStateOf(date.isBefore(today)) }

    val interval = if (repeat == TaskRepeat.NEVER) 1 else parseRepeatCount(every, 999)?.takeIf { it > 0 }
    // A repeat of a week or more can be given a smart day (a day off, the day before work…): it needs room to move within.
    val repeatSpec = taskRepeatSpec(repeat, interval)
    val canPick = repeatSpec != null && approxDays(repeatSpec) >= SMART_MIN_DAYS
    // Something with this name is already on the list: it is one item with its own repeat, so it is not added again.
    val same = upcomingWithTitle(name, existing)
    val valid = name.isNotBlank() && interval != null && same.isEmpty()

    AlertDialog(
        onDismissRequest = { if (!link.editing) onClose() },
        title = { Text("Add a task") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Due ${date.format(dialogDateFmt)}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                EmojiField(
                    chosen = pick.current(name, emojis),
                    frequent = frequent.take(QUICK_EMOJIS),
                    onChoose = { emoji -> pick.choose(emoji, name, emojis)?.let { name = it }; problem = null },
                    title = name.trim().ifEmpty { null },
                )
                OutlinedTextField(
                    value = name, onValueChange = { name = it; problem = null },
                    label = { Text("Task") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                if (same.isNotEmpty()) {
                    AlreadyOnListNotice(same, date, today, onMarkDone = {
                        pick.save(name.trim(), emojis)
                        emojis.complete(same.first().title, same.first().due, on = date)
                        onClose()
                    })
                }
                Text("Repeat", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    TaskRepeat.entries.forEach { choice ->
                        FilterChip(selected = repeat == choice, onClick = { repeat = choice; problem = null }, label = { Text(choice.label) })
                    }
                }
                if (repeat != TaskRepeat.NEVER) {
                    OutlinedTextField(
                        value = every, onValueChange = { every = it; problem = null },
                        label = { Text("Every how many ${repeat.label.lowercase()}?") }, singleLine = true,
                        isError = interval == null, modifier = Modifier.width(260.dp),
                    )
                }
                if (canPick) {
                    DayPickField(
                        dayPick, { dayPick = it; problem = null },
                        preview = if (dayPick == DayPick.ANY) null else firstOneText(snapDay(dayPick, date, windowDays(date, repeatSpec!!), today, schedule), date),
                        refBase = "1.4.10",
                    )
                }
                if (!date.isAfter(today) && same.isEmpty()) AlreadyDoneRow(alreadyDone) { alreadyDone = it }
                problem?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid && !link.editing,
                onClick = {
                    problem = null
                    link.addTask(LinkTaskCreate(name.trim(), date.toEpochDay(), interval ?: 1, repeat.unit)) { result ->
                        when (result) {
                            is EditResult.Done -> {
                                pick.save(name.trim(), emojis)
                                if (canPick) emojis.setDayPick(name.trim(), dayPick)
                                if (alreadyDone && !date.isAfter(today)) emojis.complete(name.trim(), date, date)
                                onClose()
                            }
                            is EditResult.Failed -> problem = result.message
                        }
                    }
                },
            ) { Text(if (link.editing) "Adding…" else "Add task") }
        },
        dismissButton = { TextButton(enabled = !link.editing, onClick = onClose) { Text("Cancel") } },
    )
}
