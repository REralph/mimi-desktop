package com.example.a2desktop.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.a2desktop.logic.DayPick
import com.example.a2desktop.logic.SmartDate
import com.example.a2desktop.logic.WorkSchedule
import com.example.a2desktop.logic.displayEmojis
import com.example.a2desktop.logic.isProjectedOccurrence
import com.example.a2desktop.logic.isSmartProjected
import com.example.a2desktop.logic.parseRepeatLabel
import com.example.a2desktop.logic.shownDue
import com.example.a2desktop.logic.smartRepeat
import com.example.a2desktop.logic.titleKey
import com.example.a2desktop.model.CalendarEvent
import com.example.a2desktop.model.UpcomingItem
import com.example.a2desktop.model.UpcomingKind
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.LocalDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** The emoji chosen for everything called [title] (so every haircut, every water change, shows the same one). */
@Serializable
data class EmojiItem(val title: String, val emoji: String)

/**
 * Something done: [title] was done on [doneEpochDay]. [dueEpochDay] is the day it was due when it was ticked off from the
 * Upcoming list (null for one just marked on the day it happened).
 */
@Serializable
data class DoneMark(val title: String, val dueEpochDay: Long? = null, val doneEpochDay: Long)

/**
 * What is saved: the emoji per title (oldest first), how often each emoji was picked, what has been done, and the smart day chosen for
 * repeating tasks ([days]: [titleKey] to the [DayPick]'s name; a title with no entry falls on its plain date).
 */
@Serializable
internal data class CalendarEmojiFile(
    val items: List<EmojiItem> = emptyList(),
    val uses: Map<String, Int> = emptyMap(),
    val done: List<DoneMark> = emptyList(),
    val days: Map<String, String> = emptyMap(),
)

/** One emoji on a day of the Calendar: [done] means full colour, otherwise it is shaded (not done yet). */
data class DayEmoji(val emoji: String, val done: Boolean)

/**
 * One event or task on a day of the Calendar, and how it stands. [kind] is null only for something known just from having been
 * ticked off. [time] is an event's start ("14:30"), null for an all-day event or a task; [repeat] is the phone's label for how it
 * repeats. [done]: the phone has the event completed, or it was ticked off here on [doneOn]. [projected]: this occurrence is
 * worked out from the repeat (the phone only sends the next date), not yet a date the phone holds. [ref] is the phone's own id for
 * the event or task behind it ("event-38", "task-2" — the Upcoming list's ids) so it can be edited or deleted; a repeat to come
 * carries its item's id, and something known only from having been ticked off has none. [repeatable]: it is only a tick kept on this PC,
 * with nothing of that name on the phone's Upcoming list, so the row offers to make it repeat (a new repeating task is added on the phone).
 */
data class DayItem(
    val title: String,
    val kind: UpcomingKind?,
    val time: String?,
    val repeat: String?,
    val done: Boolean,
    val doneOn: LocalDate?,
    val projected: Boolean,
    val ref: String? = null,
    val repeatable: Boolean = false,
)

private const val KEEP_ITEMS = 300
private const val KEEP_DONE = 2000

/**
 * What this PC keeps about the Calendar's events and tasks that the phone has no place for: their emoji, which of them are done, and
 * which day a repeating task should land on (see [DayPick]). It is kept on this PC only, in one small file (`calendar-emojis.json`
 * beside the pairing): nothing here is sent to the phone. An emoji belongs to a TITLE ("Haircut"), found whatever the capitals, so it shows on every occurrence of
 * that thing; when no emoji was chosen for a title, any emoji already typed into the title is used.
 * [file] null keeps it in memory only (previews and tests); a missing or unreadable file just means nothing chosen yet.
 */
class CalendarEmojiStore(private val file: File? = null) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val loaded: CalendarEmojiFile = file?.let { f -> runCatching { json.decodeFromString<CalendarEmojiFile>(f.readText()) }.getOrNull() } ?: CalendarEmojiFile()

    /** The emoji chosen for each title, oldest first (the last one for a title wins). */
    var items by mutableStateOf(loaded.items)
        private set

    /** How many times each emoji has been picked here, oldest first. */
    var uses by mutableStateOf(loaded.uses)
        private set

    /** Everything marked done, with the day it was done. */
    var done by mutableStateOf(loaded.done)
        private set

    /** The smart day chosen per title ([titleKey] to a [DayPick] name); a title that isn't here falls on its plain date. */
    var days by mutableStateOf(loaded.days)
        private set

    private fun save() {
        val f = file ?: return
        runCatching {
            f.parentFile?.mkdirs()
            val temp = File(f.parentFile, f.name + ".tmp")
            temp.writeText(json.encodeToString(CalendarEmojiFile(items, uses, done, days)))
            Files.move(temp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    // ---- the emoji of a title ----

    /** The emoji chosen for [title], or null if none was. */
    fun emojiFor(title: String): String? = items.lastOrNull { titleKey(it.title) == titleKey(title) }?.emoji

    /** The title an emoji was last chosen for, so picking a frequent emoji can fill the title in. */
    fun titleFor(emoji: String): String? = items.lastOrNull { it.emoji == emoji }?.title

    /** What to show for [title]: the emoji chosen for it, else the emoji in the title itself, else nothing. */
    fun displayFor(title: String): List<String> = displayEmojis(title, emojiFor(title))

    /** Chooses [emoji] for everything called [title] (null takes the choice back, so only an emoji typed in the title shows). */
    @Synchronized
    fun setEmoji(title: String, emoji: String?) {
        val key = titleKey(title)
        if (key.isEmpty()) return
        val others = items.filterNot { titleKey(it.title) == key }
        items = if (emoji == null) others else (others + EmojiItem(title.trim(), emoji)).takeLast(KEEP_ITEMS)
        if (emoji != null) uses = (uses - emoji + (emoji to ((uses[emoji] ?: 0) + 1))) // re-added last: the most recent
        save()
    }

    // ---- which day a repeating task lands on ----

    /** The smart day chosen for [title], or [DayPick.ANY] (its plain date) when none was. */
    fun dayPickFor(title: String): DayPick = DayPick.fromName(days[titleKey(title)])

    /** Chooses [pick] for everything called [title]; [DayPick.ANY] takes the choice back. */
    @Synchronized
    fun setDayPick(title: String, pick: DayPick) {
        val key = titleKey(title)
        if (key.isEmpty()) return
        val next = if (pick == DayPick.ANY) days - key else days + (key to pick.name)
        if (next == days) return
        days = next
        save()
    }

    /** [from] was renamed [to]: its smart day goes with it. */
    @Synchronized
    fun renameDayPick(from: String, to: String) {
        val old = titleKey(from)
        val new = titleKey(to)
        val pick = days[old] ?: return
        if (old == new || new.isEmpty()) return
        days = days - old + (new to pick)
        save()
    }

    /**
     * Where [item] is shown on the Upcoming list: its own due date, or — for a task or event that repeats a week or more and has a smart
     * day — the day of that kind inside the period it falls in (see [shownDue]). The done tick still belongs to [UpcomingItem.due], the
     * phone's own date.
     */
    fun shownDue(item: UpcomingItem, today: LocalDate, schedule: WorkSchedule): SmartDate =
        shownDue(dayPickFor(item.title), item.due, item.repeat, today, schedule)

    // ---- what is done ----

    private fun matches(mark: DoneMark, title: String, due: LocalDate) = titleKey(mark.title) == titleKey(title) && mark.dueEpochDay == due.toEpochDay()

    /** Whether the occurrence of [title] due on [due] has been ticked off. */
    fun isDone(title: String, due: LocalDate): Boolean = done.any { matches(it, title, due) }

    /** The day the occurrence of [title] due on [due] was done, or null if it hasn't been. */
    fun doneOn(title: String, due: LocalDate): LocalDate? = done.lastOrNull { matches(it, title, due) }?.let { LocalDate.ofEpochDay(it.doneEpochDay) }

    /** Ticks off [title] (due on [due], or null for one not on the Upcoming list) as done on [on]. */
    @Synchronized
    fun complete(title: String, due: LocalDate?, on: LocalDate) {
        if (titleKey(title).isEmpty()) return
        val without = if (due == null) done.filterNot { titleKey(it.title) == titleKey(title) && it.dueEpochDay == null && it.doneEpochDay == on.toEpochDay() }
        else done.filterNot { matches(it, title, due) }
        done = (without + DoneMark(title.trim(), due?.toEpochDay(), on.toEpochDay())).takeLast(KEEP_DONE)
        save()
    }

    /**
     * [from] was renamed [to]: the tick on its occurrence due on [due] goes with it, so it doesn't come back as not done. Ticks on other
     * occurrences (and on other things that happen to share the old title) stay as they are.
     */
    @Synchronized
    fun renameDone(from: String, to: String, due: LocalDate) {
        if (titleKey(from) == titleKey(to) || titleKey(to).isEmpty() || done.none { matches(it, from, due) }) return
        done = done.map { if (matches(it, from, due)) it.copy(title = to.trim()) else it }
        save()
    }

    /** Takes back the tick on the occurrence of [title] due on [due]. */
    @Synchronized
    fun undo(title: String, due: LocalDate) {
        if (!isDone(title, due)) return
        done = done.filterNot { matches(it, title, due) }
        save()
    }

    // ---- what the Calendar shows ----

    /**
     * Everything on [date], from the phone's [events] and [upcoming] list (never a separate list of its own):
     *  - the events dated that day and the tasks due that day (for a task with a smart day, the day it is moved to inside its period);
     *  - later occurrences of repeating events and tasks that fall on it ([projected]) — the dates after the next due one, stepped as
     *    the phone steps them, shown only for days after [today] — except that once the occurrence due now has been ticked off here, they
     *    count from the day it was DONE ([countsFrom]): done on the 8th and repeating every 6 days, the next is the 14th;
     *  - one ticked off here is not listed on its due date but on the day it was DONE, as done — so a haircut due on the 10th and
     *    had on the 4th is on the 4th, not the 10th;
     *  - something marked done with no due date is listed, as done, on its day.
     * An event the phone has completed is done. Events first (the timed ones by time, then the all-day ones in the phone's order),
     * then tasks, then repeats to come, then what was done.
     */
    fun itemsOn(date: LocalDate, events: List<CalendarEvent>, upcoming: List<UpcomingItem>, today: LocalDate, schedule: WorkSchedule = WorkSchedule.EMPTY): List<DayItem> {
        val items = ArrayList<DayItem>()
        // An event on the Upcoming list that repeats a week or more can have a smart day, so it is listed where that puts it; any other
        // event (a one-off, a completed one) is listed on its own date.
        fun eventOnList(event: CalendarEvent) = upcoming.firstOrNull { it.id == "event-${event.id}" }
        fun eventDay(event: CalendarEvent) = eventOnList(event)?.let { shownDue(it, today, schedule).date } ?: event.date
        events.filter { eventDay(it) == date }.sortedBy { it.time ?: "99:99" }.forEach { event ->
            if (done.none { matches(it, event.title, event.date) }) {
                items += DayItem(event.title, UpcomingKind.EVENT, event.time, eventOnList(event)?.repeat, event.completed, null, false, "event-${event.id}")
            }
        }
        upcoming.filter { it.kind == UpcomingKind.TASK && shownDue(it, today, schedule).date == date }.forEach { task ->
            if (done.none { matches(it, task.title, task.due) }) items += DayItem(task.title, UpcomingKind.TASK, null, task.repeat, false, null, false, task.id)
        }
        upcoming.forEach { item ->
            val repeat = parseRepeatLabel(item.repeat) ?: return@forEach
            // A task or event with a smart day lands on that kind of day inside each later period; anything else, on the plain step.
            val pick = dayPickFor(item.title)
            val from = countsFrom(item)
            val projected = if (pick != DayPick.ANY && smartRepeat(item.repeat) != null) isSmartProjected(pick, from, repeat, today, date, schedule)
            else isProjectedOccurrence(from, repeat, today, date)
            if (projected) items += DayItem(item.title, item.kind, null, item.repeat, false, null, true, item.id)
        }
        done.filter { it.doneEpochDay == date.toEpochDay() }.forEach { mark ->
            val known = upcoming.firstOrNull { titleKey(it.title) == titleKey(mark.title) }
            val kind = known?.kind ?: if (events.any { titleKey(it.title) == titleKey(mark.title) }) UpcomingKind.EVENT else null
            // Nothing on the phone's list is behind this tick, so it can be turned into something that repeats ([DayItem.repeatable]).
            items += DayItem(mark.title, kind, null, known?.repeat, true, date, false, repeatable = known == null)
        }
        return items
    }

    /**
     * The day the repeats after [item] are counted from: the day its current occurrence (the one due on [UpcomingItem.due]) was ticked
     * off here, if it has been — so something done a few days early or late is next due that many days after the day it was really done —
     * else the phone's own due date, stepped as the phone steps it. The phone isn't told of the tick, so its date doesn't move.
     */
    fun countsFrom(item: UpcomingItem): LocalDate = doneOn(item.title, item.due) ?: item.due

    /** The emoji to draw for [items] (a day's [itemsOn]): shaded while not done, full colour once done. A title with no emoji adds none. */
    fun emojiMarks(items: List<DayItem>): List<DayEmoji> = items.flatMap { item -> displayFor(item.title).map { DayEmoji(it, item.done) } }

    /** The emoji on [date] — see [itemsOn] for what is on a day and [emojiMarks] for how it is drawn. */
    fun emojisOn(date: LocalDate, events: List<CalendarEvent>, upcoming: List<UpcomingItem>, today: LocalDate, schedule: WorkSchedule = WorkSchedule.EMPTY): List<DayEmoji> =
        emojiMarks(itemsOn(date, events, upcoming, today, schedule))

    /** Whether any event among [items] has no emoji to show (chosen or typed into its title): those keep the small square marker on the day. */
    fun hasPlainEvent(items: List<DayItem>): Boolean = items.any { it.kind == UpcomingKind.EVENT && displayFor(it.title).isEmpty() }

    companion object {
        /** The real file, next to the pairing in `%APPDATA%\a2-desktop`. */
        fun default(): CalendarEmojiStore =
            CalendarEmojiStore(File(File(System.getenv("APPDATA") ?: System.getProperty("user.home"), "a2-desktop"), "calendar-emojis.json"))
    }
}
