package com.example.a2desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.a2desktop.data.CalendarEmojiStore
import com.example.a2desktop.data.DesktopStore
import com.example.a2desktop.link.EditResult
import com.example.a2desktop.link.LinkController
import com.example.a2desktop.link.LinkEventUpdate
import com.example.a2desktop.link.LinkEventCreate
import com.example.a2desktop.link.LinkTaskUpdate
import com.example.a2desktop.logic.DayPick
import com.example.a2desktop.logic.Repeat
import com.example.a2desktop.logic.RepeatState
import com.example.a2desktop.logic.SMART_MIN_DAYS
import com.example.a2desktop.logic.WorkSchedule
import com.example.a2desktop.logic.approxDays
import com.example.a2desktop.logic.snapDay
import com.example.a2desktop.logic.windowDays
import com.example.a2desktop.logic.eventRepeatState
import com.example.a2desktop.logic.formatDayInput
import com.example.a2desktop.logic.parseDayInput
import com.example.a2desktop.logic.parseTimeOfDay
import com.example.a2desktop.logic.taskRepeatState
import com.example.a2desktop.logic.titleKey
import com.example.a2desktop.logic.upcomingWithTitle
import com.example.a2desktop.model.UpcomingItem
import com.example.a2desktop.model.UpcomingKind
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

private val longDayFmt = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy")

/** How many of the most used emoji the edit forms offer for one-tap choosing (the same as the add forms). */
private const val QUICK_EMOJIS_EDIT = 6

/**
 * An event or task the phone holds, as the desktop knows it, ready to be edited. [ref] is the phone's own id for it ("event-38",
 * "task-2") and [id] the number in it. [repeat] says what is known of how it repeats; [repeatLabel] is the phone's wording.
 */
internal data class EditableItem(
    val ref: String,
    val id: Long,
    val kind: UpcomingKind,
    val title: String,
    val due: LocalDate,
    val time: String?,
    val repeat: RepeatState,
    val repeatLabel: String?,
)

/** The item behind [ref] as the store now has it, or null if it is no longer there (the phone deleted it, then synced). */
internal fun editableItem(ref: String, store: DesktopStore): EditableItem? {
    val id = ref.substringAfter('-', "").toLongOrNull() ?: return null
    val onList = store.upcoming.firstOrNull { it.id == ref }
    return when {
        ref.startsWith("event-") -> {
            val event = store.events.firstOrNull { it.id == id } ?: return null
            EditableItem(ref, id, UpcomingKind.EVENT, event.title, event.date, event.time, eventRepeatState(onList?.repeat, onList != null), onList?.repeat)
        }
        ref.startsWith("task-") -> {
            val task = onList ?: return null
            EditableItem(ref, id, UpcomingKind.TASK, task.title, task.due, null, taskRepeatState(task.repeat), task.repeat)
        }
        else -> null
    }
}

/**
 * Edit or delete an event or task the phone holds. The change goes to the phone, which checks it with its own rules and makes it
 * itself — the window stays open with the phone's reason if it says no or can't be reached, and closes once the phone has done it
 * (the desktop then syncs, so the Calendar shows what the phone now has). The emoji is kept on this PC; renaming something carries its
 * emoji and its done tick along. Deleting asks first.
 */
@Composable
fun EditItemDialog(ref: String, store: DesktopStore, link: LinkController, emojis: CalendarEmojiStore, frequent: List<String>, onClose: () -> Unit) {
    val item = remember(ref) { editableItem(ref, store) }
    if (item == null) {
        LaunchedEffect(ref) { onClose() }
        return
    }
    when (item.kind) {
        UpcomingKind.EVENT -> EditEventDialog(item, link, emojis, frequent.take(QUICK_EMOJIS_EDIT), store.upcoming, store.today, store.workSchedule, onClose)
        UpcomingKind.TASK -> EditTaskDialog(item, link, emojis, frequent.take(QUICK_EMOJIS_EDIT), store.upcoming, store.today, store.workSchedule, onClose)
    }
}

/** The day box: typed as day/month/year, with the weekday shown underneath so a mistyped date is obvious. */
@Composable
private fun DayField(value: String, onChange: (String) -> Unit, date: LocalDate?, label: String) {
    OutlinedTextField(
        value = value, onValueChange = onChange,
        label = { Text(label) }, singleLine = true, isError = date == null,
        supportingText = { Text(if (date == null) "Type it like 10/11/2026" else date.format(longDayFmt)) },
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * "Mark as done": says a task (or event) has been done on a day — today or any day before it, such as the day it was really done when
 * the tick was forgotten. The day is typed as day/month/year (the weekday shows underneath), or taken from **Today** / **Yesterday**.
 * It marks the occurrence due on [due] as done on that day, exactly as ticking it in the Upcoming list on that day does (so it appears
 * in full colour on that day of the Calendar); once done, the section says so and offers **Undo**. This is kept on this PC only — it does
 * not need the phone, and the phone isn't told.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MarkDoneSection(title: String, due: LocalDate, today: LocalDate, emojis: CalendarEmojiStore) {
    val doneOn = emojis.doneOn(title, due)
    var day by remember { mutableStateOf(formatDayInput(today)) }
    val date = parseDayInput(day)
    val future = date != null && date.isAfter(today)
    Column(modifier = Modifier.fillMaxWidth().devRef("1.4.8", "Mark as done"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Mark as done", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (doneOn != null) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("Done " + if (doneOn == today) "today" else doneOn.format(longDayFmt), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { emojis.undo(title, due) }) { Text("Undo") }
            }
        } else {
            DayField(day, { day = it }, date, "Done on")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                FilterChip(selected = date == today, onClick = { day = formatDayInput(today) }, label = { Text("Today") })
                FilterChip(selected = date == today.minusDays(1), onClick = { day = formatDayInput(today.minusDays(1)) }, label = { Text("Yesterday") })
                TextButton(enabled = date != null && !future, onClick = { emojis.complete(title, due, date!!) }) { Text("Mark done") }
            }
            if (future) Text("That day hasn't come yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        Text("Kept on this PC; the phone isn't told.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** What the window says before something is deleted. */
@Composable
private fun DeleteQuestion(what: String, title: String, detail: String, problem: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Delete the $what “$title” from your phone?", style = MaterialTheme.typography.bodyLarge)
        Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        problem?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    }
}

/** Saying a title is already taken, when renaming something to the title of another thing on the Upcoming list. */
@Composable
private fun TitleTaken(clash: List<UpcomingItem>) {
    if (clash.isEmpty()) return
    Text(
        "“${clash.first().title}” is already on your list, and each thing is kept once — choose another title.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
    )
}

@Composable
private fun EditEventDialog(item: EditableItem, link: LinkController, emojis: CalendarEmojiStore, frequent: List<String>, existing: List<UpcomingItem>, today: LocalDate, schedule: WorkSchedule, onClose: () -> Unit) {
    var title by remember { mutableStateOf(item.title) }
    var day by remember { mutableStateOf(formatDayInput(item.due)) }
    var time by remember { mutableStateOf(item.time.orEmpty()) }
    var repeat by remember { mutableStateOf(item.repeat.repeat?.interval?.toString().orEmpty()) }
    var dayPick by remember { mutableStateOf(emojis.dayPickFor(item.title)) }
    var problem by remember { mutableStateOf<String?>(null) }
    var confirming by remember { mutableStateOf(false) }
    val pick = remember { EmojiPick(emojis.emojiFor(item.title)) }

    val date = parseDayInput(day)
    val minute = parseTimeOfDay(time)
    val repeatDays = parseRepeatCount(repeat, 3650)
    // An event that repeats every week or more (the repeat box is in days) can be given a smart day, just like a task: a day off, the day before work…
    val repeatSpec = if (item.repeat.known && repeatDays != null && repeatDays > 0) Repeat(repeatDays, ChronoUnit.DAYS) else null
    val canPick = repeatSpec != null && approxDays(repeatSpec) >= SMART_MIN_DAYS
    // Only a NEW title can clash: the thing keeps its own title, even when another thing happens to share it.
    val renamed = titleKey(title) != titleKey(item.title)
    val clash = if (renamed) upcomingWithTitle(title, existing).filter { it.id != item.ref } else emptyList()
    val valid = title.isNotBlank() && date != null && minute != null && (!item.repeat.known || repeatDays != null) && clash.isEmpty()
    val error = MaterialTheme.colorScheme.error

    AlertDialog(
        onDismissRequest = { if (!link.editing) onClose() },
        title = { Text(if (confirming) "Delete event?" else "Edit event") },
        text = {
            if (confirming) {
                DeleteQuestion(
                    "event", item.title,
                    if (item.repeat.repeat != null) "That includes all of its repeats. It can't be undone." else "It can't be undone.",
                    problem,
                )
            } else {
                Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    EmojiField(
                        chosen = pick.current(title, emojis),
                        frequent = frequent,
                        onChoose = { emoji -> pick.choose(emoji, title, emojis); problem = null },
                        title = title.trim().ifEmpty { null },
                    )
                    OutlinedTextField(
                        value = title, onValueChange = { title = it; problem = null },
                        label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                    TitleTaken(clash)
                    DayField(day, { day = it; problem = null }, date, if (item.repeat.repeat != null) "Date (next time it happens)" else "Date")
                    OutlinedTextField(
                        value = time, onValueChange = { time = it; problem = null },
                        label = { Text("Time, like 14:30 (empty = all day)") }, singleLine = true,
                        isError = minute == null, modifier = Modifier.fillMaxWidth(),
                    )
                    if (item.repeat.known) {
                        OutlinedTextField(
                            value = repeat, onValueChange = { repeat = it; problem = null },
                            label = { Text("Repeat every … days") }, placeholder = { Text("Doesn't repeat") }, singleLine = true,
                            isError = repeatDays == null, modifier = Modifier.fillMaxWidth(),
                        )
                        if (canPick) {
                            val nominal = date ?: item.due
                            DayPickField(
                                dayPick, { dayPick = it; problem = null },
                                preview = if (dayPick == DayPick.ANY) null else firstOneText(snapDay(dayPick, nominal, windowDays(nominal, repeatSpec!!), today, schedule), nominal),
                                refBase = "1.4.11",
                            )
                        }
                    } else {
                        Text("How it repeats stays as it is on the phone.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    MarkDoneSection(item.title, item.due, today, emojis)
                    problem?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = error) }
                }
            }
        },
        confirmButton = {
            if (confirming) {
                TextButton(
                    enabled = !link.editing,
                    onClick = {
                        problem = null
                        link.deleteEvent(item.id) { result ->
                            when (result) {
                                is EditResult.Done -> onClose()
                                is EditResult.Failed -> problem = result.message
                            }
                        }
                    },
                ) { Text(if (link.editing) "Deleting…" else "Delete", color = error) }
            } else {
                TextButton(
                    enabled = valid && !link.editing,
                    onClick = {
                        problem = null
                        link.updateEvent(
                            LinkEventUpdate(item.id, title.trim(), date!!.toEpochDay(), minute!!, repeatDays?.takeIf { it > 0 }, keepRepeat = !item.repeat.known),
                        ) { result ->
                            when (result) {
                                is EditResult.Done -> {
                                    if (renamed) {
                                        emojis.renameDone(item.title, title.trim(), item.due)
                                        emojis.renameDayPick(item.title, title.trim())
                                    }
                                    pick.saveIfChanged(title.trim(), emojis)
                                    // The smart day goes with the repeat: kept while it repeats a week or more, dropped once it is set not to repeat.
                                    if (canPick) emojis.setDayPick(title.trim(), dayPick) else if (item.repeat.known && repeatDays == 0) emojis.setDayPick(title.trim(), DayPick.ANY)
                                    onClose()
                                }
                                is EditResult.Failed -> problem = result.message
                            }
                        }
                    },
                ) { Text(if (link.editing) "Saving…" else "Save") }
            }
        },
        dismissButton = {
            if (confirming) {
                TextButton(enabled = !link.editing, onClick = { confirming = false }) { Text("Keep it") }
            } else {
                Row {
                    TextButton(enabled = !link.editing, onClick = { confirming = true; problem = null }) { Text("Delete", color = error) }
                    TextButton(enabled = !link.editing, onClick = onClose) { Text("Cancel") }
                }
            }
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EditTaskDialog(item: EditableItem, link: LinkController, emojis: CalendarEmojiStore, frequent: List<String>, existing: List<UpcomingItem>, today: LocalDate, schedule: WorkSchedule, onClose: () -> Unit) {
    val known = item.repeat.known
    var name by remember { mutableStateOf(item.title) }
    var day by remember { mutableStateOf(formatDayInput(item.due)) }
    var repeat by remember {
        mutableStateOf(
            when (item.repeat.repeat?.unit) {
                ChronoUnit.DAYS -> TaskRepeat.DAYS
                ChronoUnit.WEEKS -> TaskRepeat.WEEKS
                ChronoUnit.MONTHS -> TaskRepeat.MONTHS
                ChronoUnit.YEARS -> TaskRepeat.YEARS
                else -> TaskRepeat.NEVER
            },
        )
    }
    var every by remember { mutableStateOf((item.repeat.repeat?.interval ?: 1).toString()) }
    var dayPick by remember { mutableStateOf(emojis.dayPickFor(item.title)) }
    var problem by remember { mutableStateOf<String?>(null) }
    var confirming by remember { mutableStateOf(false) }
    val pick = remember { EmojiPick(emojis.emojiFor(item.title)) }

    val date = parseDayInput(day)
    val interval = if (!known || repeat == TaskRepeat.NEVER) 1 else parseRepeatCount(every, 999)?.takeIf { it > 0 }
    // A repeat of a week or more (that the PC can read) can be given a smart day: a day off, the day before work…
    val repeatSpec = if (known) taskRepeatSpec(repeat, interval) else null
    val canPick = repeatSpec != null && approxDays(repeatSpec) >= SMART_MIN_DAYS
    val renamed = titleKey(name) != titleKey(item.title)
    val clash = if (renamed) upcomingWithTitle(name, existing).filter { it.id != item.ref } else emptyList()
    val valid = name.isNotBlank() && date != null && interval != null && clash.isEmpty()
    val error = MaterialTheme.colorScheme.error

    AlertDialog(
        onDismissRequest = { if (!link.editing) onClose() },
        title = { Text(if (confirming) "Delete task?" else "Edit task") },
        text = {
            if (confirming) {
                DeleteQuestion("task", item.title, "The phone switches it off: it stays in Manage Tasks there, with its history, and can be switched back on.", problem)
            } else {
                Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    EmojiField(
                        chosen = pick.current(name, emojis),
                        frequent = frequent,
                        onChoose = { emoji -> pick.choose(emoji, name, emojis); problem = null },
                        title = name.trim().ifEmpty { null },
                    )
                    OutlinedTextField(
                        value = name, onValueChange = { name = it; problem = null },
                        label = { Text("Task") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                    TitleTaken(clash)
                    DayField(day, { day = it; problem = null }, date, "Due")
                    if (known) {
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
                            val nominal = date ?: item.due
                            DayPickField(
                                dayPick, { dayPick = it; problem = null },
                                preview = if (dayPick == DayPick.ANY) null else firstOneText(snapDay(dayPick, nominal, windowDays(nominal, repeatSpec!!), today, schedule), nominal),
                                refBase = "1.4.10",
                            )
                        }
                    } else {
                        Text(
                            "How it repeats stays as it is on the phone" + (item.repeatLabel?.let { " ($it)." } ?: "."),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    MarkDoneSection(item.title, item.due, today, emojis)
                    problem?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = error) }
                }
            }
        },
        confirmButton = {
            if (confirming) {
                TextButton(
                    enabled = !link.editing,
                    onClick = {
                        problem = null
                        link.deleteTask(item.id) { result ->
                            when (result) {
                                is EditResult.Done -> onClose()
                                is EditResult.Failed -> problem = result.message
                            }
                        }
                    },
                ) { Text(if (link.editing) "Deleting…" else "Delete", color = error) }
            } else {
                TextButton(
                    enabled = valid && !link.editing,
                    onClick = {
                        problem = null
                        link.updateTask(
                            LinkTaskUpdate(item.id, name.trim(), date!!.toEpochDay(), interval ?: 1, repeat.unit, keepRepeat = !known),
                        ) { result ->
                            when (result) {
                                is EditResult.Done -> {
                                    if (renamed) {
                                        emojis.renameDone(item.title, name.trim(), item.due)
                                        emojis.renameDayPick(item.title, name.trim())
                                    }
                                    pick.saveIfChanged(name.trim(), emojis)
                                    // The smart day goes with the repeat: kept while it repeats a week or more, dropped once it is set not to repeat.
                                    if (canPick) emojis.setDayPick(name.trim(), dayPick) else if (known && repeat == TaskRepeat.NEVER) emojis.setDayPick(name.trim(), DayPick.ANY)
                                    onClose()
                                }
                                is EditResult.Failed -> problem = result.message
                            }
                        }
                    },
                ) { Text(if (link.editing) "Saving…" else "Save") }
            }
        },
        dismissButton = {
            if (confirming) {
                TextButton(enabled = !link.editing, onClick = { confirming = false }) { Text("Keep it") }
            } else {
                Row {
                    TextButton(enabled = !link.editing, onClick = { confirming = true; problem = null }) { Text("Delete", color = error) }
                    TextButton(enabled = !link.editing, onClick = onClose) { Text("Cancel") }
                }
            }
        },
    )
}

/**
 * "Make it repeat": opened by the small pen on a row that is only a tick kept on this PC — something done once, like a haircut had on the
 * 4th, with nothing of that name on the phone's Upcoming list ([com.example.a2desktop.data.DayItem.repeatable]). It adds an all-day EVENT
 * with that title to the phone that repeats every so many days (the phone's events repeat in days, so the choice is days or weeks): every
 * two weeks to start with, first due that long after the day it was done ([doneOn]) unless another day is typed, and with the smart day
 * "On a day off" chosen to start with, so each one lands on the best day for the rota as it becomes known (see [DayPick]). The title's
 * emoji and the tick on [doneOn] are left as they are. It is an event rather than a task because the phone only lists a task in its
 * Upcoming list (which is all the PC is sent) within 3 days of its due date, so a task due a week or two ahead would not show on this PC
 * at all until then, whereas an event is listed 30 days ahead and its date is always sent. The event goes to the phone, which adds it
 * itself — the window stays open with the phone's reason if it says no, and closes once the phone has made the change (or it is kept
 * to be sent when the phone can be reached).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RepeatItDialog(
    title: String,
    doneOn: LocalDate,
    today: LocalDate,
    link: LinkController,
    emojis: CalendarEmojiStore,
    existing: List<UpcomingItem>,
    schedule: WorkSchedule,
    onClose: () -> Unit,
) {
    var unit by remember { mutableStateOf(TaskRepeat.WEEKS) }
    var every by remember { mutableStateOf("2") }
    var typedNext by remember { mutableStateOf<String?>(null) } // null: follows the repeat — the day it was done plus one step
    var dayPick by remember { mutableStateOf(emojis.dayPickFor(title).takeIf { it != DayPick.ANY } ?: DayPick.DAY_OFF) }
    var problem by remember { mutableStateOf<String?>(null) }

    val interval = parseRepeatCount(every, if (unit == TaskRepeat.WEEKS) 520 else 3650)?.takeIf { it > 0 }
    // The phone's events repeat every so many DAYS: a number of weeks is that many sevens.
    val repeatDays = interval?.let { if (unit == TaskRepeat.WEEKS) it * 7 else it }
    val repeatSpec = repeatDays?.let { Repeat(it, ChronoUnit.DAYS) }
    val automatic = repeatDays?.let { doneOn.plusDays(it.toLong()) }
    val next = if (typedNext == null) automatic else parseDayInput(typedNext!!)
    val canPick = repeatSpec != null && approxDays(repeatSpec) >= SMART_MIN_DAYS
    // Something with this name is already on the list: it is one item with its own repeat, so it is not added again.
    val same = upcomingWithTitle(title, existing)
    val valid = interval != null && next != null && same.isEmpty()

    AlertDialog(
        onDismissRequest = { if (!link.editing) onClose() },
        title = { Text("Make it repeat") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()).devRef("1.4.12", "Make it repeat"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("“$title” was done ${doneOn.format(longDayFmt)}.", style = MaterialTheme.typography.bodyMedium)
                Text("Repeat", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(TaskRepeat.DAYS, TaskRepeat.WEEKS).forEach { choice ->
                        FilterChip(selected = unit == choice, onClick = { unit = choice; problem = null }, label = { Text(choice.label) })
                    }
                }
                OutlinedTextField(
                    value = every, onValueChange = { every = it; problem = null },
                    label = { Text("Every how many ${unit.label.lowercase()}?") }, singleLine = true,
                    isError = interval == null, modifier = Modifier.width(260.dp),
                )
                DayField(typedNext ?: automatic?.let { formatDayInput(it) }.orEmpty(), { typedNext = it; problem = null }, next, "Next one due")
                if (canPick) {
                    val nominal = next ?: doneOn
                    DayPickField(
                        dayPick, { dayPick = it; problem = null },
                        preview = if (dayPick == DayPick.ANY || next == null) null else firstOneText(snapDay(dayPick, nominal, windowDays(nominal, repeatSpec!!), today, schedule), nominal),
                        refBase = "1.4.12.1",
                    )
                }
                if (repeatDays != null) {
                    Text("It goes on your phone as an all-day event that repeats every $repeatDays days.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (same.isNotEmpty()) {
                    Text(alreadyOnListText(same), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                problem?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid && !link.editing,
                onClick = {
                    problem = null
                    link.addEvent(LinkEventCreate(title.trim(), next!!.toEpochDay(), -1, repeatDays!!)) { result ->
                        when (result) {
                            is EditResult.Done -> {
                                // The smart day is kept here for this title (the phone keeps the plain date and repeat).
                                if (canPick) emojis.setDayPick(title.trim(), dayPick)
                                onClose()
                            }
                            is EditResult.Failed -> problem = result.message
                        }
                    }
                },
            ) { Text(if (link.editing) "Adding…" else "Repeat it") }
        },
        dismissButton = { TextButton(enabled = !link.editing, onClick = onClose) { Text("Cancel") } },
    )
}
