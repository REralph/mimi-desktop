package com.example.a2desktop.link

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * A change made on this PC that the phone has not been given yet — because the phone could not be reached when it was made. It is kept
 * (on disk, so closing the app loses nothing), shown on this PC at once, and sent to the phone as soon as the phone answers. Each one
 * carries the moment it was made, so the phone can tell whether its own copy was changed after it (the later change wins).
 */
@Serializable
sealed interface PendingChange {
    val id: String
    val madeAtMillis: Long

    /** Where on the phone this goes (the same request the live edit would have sent). */
    val path: String

    /** A few words saying what it is, for the Phone page. */
    val label: String

    /** The request body to send. */
    fun bodyJson(json: Json): String
}

/** Adds an event; until the phone answers it shows under the temporary (negative) [tempId]. */
@Serializable @SerialName("event.add")
data class AddEventChange(override val id: String, override val madeAtMillis: Long, val tempId: Long, val request: LinkEventCreate) : PendingChange {
    override val path get() = "/v1/event"
    override val label get() = "Add event “${request.title}”"
    override fun bodyJson(json: Json) = json.encodeToString(request)
}

@Serializable @SerialName("event.update")
data class UpdateEventChange(override val id: String, override val madeAtMillis: Long, val request: LinkEventUpdate) : PendingChange {
    override val path get() = "/v1/event/update"
    override val label get() = "Change event “${request.title}”"
    override fun bodyJson(json: Json) = json.encodeToString(request)
}

@Serializable @SerialName("event.delete")
data class DeleteEventChange(override val id: String, override val madeAtMillis: Long, val request: LinkEventDelete, val title: String) : PendingChange {
    override val path get() = "/v1/event/delete"
    override val label get() = "Delete event “$title”"
    override fun bodyJson(json: Json) = json.encodeToString(request)
}

@Serializable @SerialName("task.add")
data class AddTaskChange(override val id: String, override val madeAtMillis: Long, val tempId: Long, val request: LinkTaskCreate) : PendingChange {
    override val path get() = "/v1/task"
    override val label get() = "Add task “${request.name}”"
    override fun bodyJson(json: Json) = json.encodeToString(request)
}

@Serializable @SerialName("task.update")
data class UpdateTaskChange(override val id: String, override val madeAtMillis: Long, val request: LinkTaskUpdate) : PendingChange {
    override val path get() = "/v1/task/update"
    override val label get() = "Change task “${request.name}”"
    override fun bodyJson(json: Json) = json.encodeToString(request)
}

@Serializable @SerialName("task.delete")
data class DeleteTaskChange(override val id: String, override val madeAtMillis: Long, val request: LinkTaskDelete, val title: String) : PendingChange {
    override val path get() = "/v1/task/delete"
    override val label get() = "Delete task “$title”"
    override fun bodyJson(json: Json) = json.encodeToString(request)
}

/**
 * A skin-care or supplements change made while the phone could not be reached. The phone keeps that record, so the change is held here,
 * shown on top of the phone's record (see DesktopStore.applyPending) and sent when the phone answers. [request] carries the moment it was
 * made when the phone understands it (see [LINK_FEATURE_CARE_STAMPS]): the phone then keeps whichever of its own and this change is
 * later, for each thing the change touches.
 */
@Serializable @SerialName("care.change")
data class CareChange(override val id: String, override val madeAtMillis: Long, val request: LinkCareRequest) : PendingChange {
    override val path get() = "/v1/care"
    override val label get() = careChangeLabel(request.commands)
    override fun bodyJson(json: Json) = json.encodeToString(request)
}

/** A few words for the Phone page's list of changes waiting: which record, and how many changes. */
fun careChangeLabel(commands: List<LinkCareCommand>): String {
    val skin = commands.any { it.op.startsWith("skin.") }
    val supplements = commands.any { it.op.startsWith("vit.") }
    val what = when {
        skin && supplements -> "Skin care and supplements"
        skin -> "Skin care"
        supplements -> "Supplements"
        else -> "Care"
    }
    return "$what change" + if (commands.size > 1) " (${commands.size})" else ""
}

/** Whether two commands are about the same thing in the same way (only their on/off or count differs), so the later one replaces the earlier. */
private fun sameCareCommand(a: LinkCareCommand, b: LinkCareCommand) =
    a.op == b.op && a.date == b.date && a.kind == b.kind && a.name == b.name && a.supplement == b.supplement

@Serializable @SerialName("habit.set")
data class SetHabitChange(override val id: String, override val madeAtMillis: Long, val request: LinkHabitEdit, val habitName: String) : PendingChange {
    override val path get() = "/v1/habit"
    override val label get() = (if (request.logged) "Record " else "Clear ") + habitName
    override fun bodyJson(json: Json) = json.encodeToString(request)
}

/**
 * What a new change does to the changes already waiting. Several changes to one thing become one, and a thing created and removed again
 * while the phone was out of reach never needs to reach it at all:
 *  - changing or deleting something *added while offline* changes or cancels that add (it only has a temporary id);
 *  - changing something already on the phone replaces the earlier waiting change to it; deleting it replaces them all;
 *  - recording a habit on a day replaces the earlier waiting change to that habit on that day.
 */
object OutboxRules {
    fun enqueue(queue: List<PendingChange>, incoming: PendingChange): List<PendingChange> = when (incoming) {
        is AddEventChange, is AddTaskChange -> queue + incoming
        is UpdateEventChange -> {
            val id = incoming.request.id
            if (id < 0) queue.map { if (it is AddEventChange && it.tempId == id) it.copy(request = mergeEvent(it.request, incoming.request)) else it }
            else queue.filterNot { it is UpdateEventChange && it.request.id == id } + incoming
        }
        is DeleteEventChange -> {
            val id = incoming.request.id
            if (id < 0) queue.filterNot { (it is AddEventChange && it.tempId == id) || (it is UpdateEventChange && it.request.id == id) }
            else queue.filterNot { (it is UpdateEventChange && it.request.id == id) || (it is DeleteEventChange && it.request.id == id) } + incoming
        }
        is UpdateTaskChange -> {
            val id = incoming.request.id
            if (id < 0) queue.map { if (it is AddTaskChange && it.tempId == id) it.copy(request = mergeTask(it.request, incoming.request)) else it }
            else queue.filterNot { it is UpdateTaskChange && it.request.id == id } + incoming
        }
        is DeleteTaskChange -> {
            val id = incoming.request.id
            if (id < 0) queue.filterNot { (it is AddTaskChange && it.tempId == id) || (it is UpdateTaskChange && it.request.id == id) }
            else queue.filterNot { (it is UpdateTaskChange && it.request.id == id) || (it is DeleteTaskChange && it.request.id == id) } + incoming
        }
        // A waiting care change that a later one says again, command for command (tick, un-tick, tick…), is replaced by it.
        is CareChange -> queue.filterNot { earlier ->
            earlier is CareChange && earlier.request.commands.isNotEmpty() &&
                earlier.request.commands.all { c -> incoming.request.commands.any { sameCareCommand(c, it) } }
        } + incoming
        is SetHabitChange -> queue.filterNot {
            it is SetHabitChange && it.request.habitId == incoming.request.habitId && it.request.dateEpochDay == incoming.request.dateEpochDay
        } + incoming
    }

    private fun mergeEvent(create: LinkEventCreate, update: LinkEventUpdate) = LinkEventCreate(
        title = update.title,
        dateEpochDay = update.dateEpochDay,
        startMinuteOfDay = update.startMinuteOfDay,
        repeatIntervalDays = if (update.keepRepeat) create.repeatIntervalDays else update.repeatIntervalDays,
        requestId = create.requestId,
    )

    private fun mergeTask(create: LinkTaskCreate, update: LinkTaskUpdate) = LinkTaskCreate(
        name = update.name,
        dueEpochDay = update.dueEpochDay,
        repeatInterval = if (update.keepRepeat) create.repeatInterval else update.repeatInterval,
        repeatUnit = if (update.keepRepeat) create.repeatUnit else update.repeatUnit,
        requestId = create.requestId,
    )
}

@Serializable
private class OutboxFile(val changes: List<PendingChange> = emptyList(), val notes: List<String> = emptyList())

/**
 * The changes waiting for the phone, and the notes about changes the phone could not take (kept until read). Saved to [file] after every
 * change so closing the app, or a crash, loses nothing; a damaged file is set aside and the app carries on with an empty one. With no
 * file (tests, previews) it lives in memory only.
 */
class Outbox(private val file: File?) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var changesList: List<PendingChange>
    private var notesList: List<String>

    init {
        val loaded = load()
        changesList = loaded.changes
        notesList = loaded.notes
    }

    /** What is waiting, oldest first. */
    val changes: List<PendingChange> @Synchronized get() = changesList

    /** Things the phone could not take, in plain words. */
    val notes: List<String> @Synchronized get() = notesList

    @Synchronized
    fun enqueue(change: PendingChange) {
        changesList = OutboxRules.enqueue(changesList, change)
        save()
    }

    @Synchronized
    fun remove(id: String) {
        changesList = changesList.filterNot { it.id == id }
        save()
    }

    @Synchronized
    fun addNote(text: String) {
        notesList = (notesList + text).takeLast(MAX_NOTES)
        save()
    }

    @Synchronized
    fun clearNotes() {
        notesList = emptyList()
        save()
    }

    @Synchronized
    fun clear() {
        changesList = emptyList()
        notesList = emptyList()
        save()
    }

    /** A temporary id for something added while the phone is out of reach: below zero, and different from any waiting one. */
    @Synchronized
    fun nextTempId(): Long {
        val used = changesList.mapNotNull { (it as? AddEventChange)?.tempId ?: (it as? AddTaskChange)?.tempId }
        return minOf(used.minOrNull() ?: 0L, 0L) - 1
    }

    private fun load(): OutboxFile {
        val f = file ?: return OutboxFile()
        if (!f.exists()) return OutboxFile()
        return try {
            json.decodeFromString<OutboxFile>(f.readText())
        } catch (e: Exception) {
            // Keep the unreadable file for a look, and carry on empty rather than stop the app opening.
            CrashLog.record("Set aside an unreadable list of changes waiting for the phone", e)
            runCatching { Files.move(f.toPath(), File(f.parentFile, f.name + ".unreadable").toPath(), StandardCopyOption.REPLACE_EXISTING) }
            OutboxFile()
        }
    }

    private fun save() {
        val f = file ?: return
        runCatching {
            f.parentFile?.mkdirs()
            val temp = File(f.parentFile, f.name + ".tmp")
            temp.writeText(json.encodeToString(OutboxFile(changesList, notesList)))
            Files.move(temp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }.onFailure { CrashLog.record("Couldn't save the changes waiting for the phone", it) }
    }

    companion object {
        private const val MAX_NOTES = 20

        fun default(): Outbox = Outbox(File(LinkStorage.defaultDir(), "outbox.json"))
    }
}
