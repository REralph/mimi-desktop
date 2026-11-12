package com.example.a2desktop

import com.example.a2desktop.link.AddEventChange
import com.example.a2desktop.link.AddTaskChange
import com.example.a2desktop.link.CareChange
import com.example.a2desktop.link.LinkCareCommand
import com.example.a2desktop.link.LinkCareRequest
import com.example.a2desktop.link.careChangeLabel
import com.example.a2desktop.link.DeleteEventChange
import com.example.a2desktop.link.DeleteTaskChange
import com.example.a2desktop.link.LinkEventCreate
import com.example.a2desktop.link.LinkEventDelete
import com.example.a2desktop.link.LinkEventUpdate
import com.example.a2desktop.link.LinkHabitEdit
import com.example.a2desktop.link.LinkTaskCreate
import com.example.a2desktop.link.LinkTaskDelete
import com.example.a2desktop.link.LinkTaskUpdate
import com.example.a2desktop.link.Outbox
import com.example.a2desktop.link.OutboxRules
import com.example.a2desktop.link.PendingChange
import com.example.a2desktop.link.SetHabitChange
import com.example.a2desktop.link.UpdateEventChange
import com.example.a2desktop.link.UpdateTaskChange
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

/** The list of changes made here that the phone has not been given yet: how new changes fold into it, and that it survives closing the app. */
class OutboxTest {
    private val dir: File = Files.createTempDirectory("a2-outbox").toFile()

    @AfterTest fun cleanUp() {
        dir.deleteRecursively()
    }

    private var n = 0
    private fun id() = "c${n++}"

    private fun addEvent(temp: Long, title: String = "Haircut", day: Long = 100) = AddEventChange(id(), 1_000, temp, LinkEventCreate(title, day, -1, null, "r$temp"))
    private fun updateEvent(eventId: Long, title: String = "Changed", keepRepeat: Boolean = false) =
        UpdateEventChange(id(), 2_000, LinkEventUpdate(eventId, title, 101, 600, 7, keepRepeat, 2_000))
    private fun deleteEvent(eventId: Long) = DeleteEventChange(id(), 3_000, LinkEventDelete(eventId, 3_000), "Haircut")
    private fun addTask(temp: Long) = AddTaskChange(id(), 1_000, temp, LinkTaskCreate("Water", 100, 2, "WEEKS", "t$temp"))
    private fun updateTask(taskId: Long, name: String = "Water now", keepRepeat: Boolean = false) =
        UpdateTaskChange(id(), 2_000, LinkTaskUpdate(taskId, name, 105, 3, "DAYS", keepRepeat, 2_000))
    private fun deleteTask(taskId: Long) = DeleteTaskChange(id(), 3_000, LinkTaskDelete(taskId, 3_000), "Water")
    private fun habit(habitId: Long, day: Long, logged: Boolean) = SetHabitChange(id(), 1_000, LinkHabitEdit(habitId, day, logged), "Gym")

    private fun care(vararg commands: LinkCareCommand, at: Long = 5_000) = CareChange(id(), at, LinkCareRequest(commands.toList(), null, at))
    private fun tick(day: Long, on: Boolean = true) = LinkCareCommand("skin.setEvening", day, kind = "CALM", flag = on)

    private fun fold(vararg changes: PendingChange): List<PendingChange> = changes.fold(emptyList()) { queue, change -> OutboxRules.enqueue(queue, change) }

    // ---- skin care and supplements ----

    @Test fun aWaitingCareChangeThatALaterOneSaysAgainIsReplacedByIt() {
        val first = care(tick(100, true), at = 1_000)
        val second = care(tick(100, false), at = 2_000)
        assertEquals(listOf<PendingChange>(second), fold(first, second), "tick then un-tick: only the un-tick is kept, with its own time")
    }

    @Test fun careChangesToDifferentThingsAreAllKept() {
        val a = care(tick(100))
        val b = care(tick(101))
        val c = care(LinkCareCommand("vit.setMark", 100, supplement = "ZINC", flag = true))
        assertEquals(listOf<PendingChange>(a, b, c), fold(a, b, c))
    }

    @Test fun aCareChangeWithTwoCommandsIsOnlyReplacedWhenALaterOneSaysBothAgain() {
        val both = care(tick(100), LinkCareCommand("vit.setMark", 100, supplement = "ZINC", flag = true))
        val oneOnly = care(tick(100, false))
        assertEquals(listOf(both, oneOnly), fold(both, oneOnly), "the zinc tick is not said again, so the earlier change stays")
        val bothAgain = care(tick(100, false), LinkCareCommand("vit.setMark", 100, supplement = "ZINC", flag = null))
        assertEquals(listOf<PendingChange>(bothAgain), fold(both, bothAgain))
    }

    @Test fun careChangesNeverTouchOtherKindsOfChange() {
        val event = addEvent(-1)
        val habit = habit(5, 100, true)
        val c = care(tick(100))
        assertEquals(listOf(event, habit, c), fold(event, habit, c))
        assertEquals(listOf(event, habit, c), fold(event, c, habit).let { listOf(it[0], it[2], it[1]) })
    }

    @Test fun careChangesAreWrittenToTheFileAndReadBack() {
        val file = File(dir, "outbox.json")
        val change = care(tick(100), at = 7_000)
        Outbox(file).enqueue(change)
        val back = Outbox(file).changes.single() as CareChange
        assertEquals(change, back)
        assertEquals(7_000, back.request.madeAtMillis)
        assertEquals("Skin care change", back.label)
    }

    @Test fun theLabelSaysWhichRecordAndHowMany() {
        assertEquals("Skin care change", careChangeLabel(listOf(tick(1))))
        assertEquals("Supplements change", careChangeLabel(listOf(LinkCareCommand("vit.setMark", 1, supplement = "ZINC", flag = true))))
        assertEquals("Skin care and supplements change (2)", careChangeLabel(listOf(tick(1), LinkCareCommand("vit.resetDay", 1))))
    }

    // ---- folding ----

    @Test fun changesAreKeptInTheOrderTheyWereMade() {
        val a = addEvent(-1)
        val b = deleteEvent(7)
        val c = addTask(-2)
        assertEquals(listOf(a, b, c), fold(a, b, c))
    }

    @Test fun deletingSomethingAddedWhileOffCancelsTheAddAndSendsNothing() {
        assertTrue(fold(addEvent(-1), deleteEvent(-1)).isEmpty())
        assertTrue(fold(addTask(-2), deleteTask(-2)).isEmpty())
    }

    @Test fun deletingSomethingAddedAndThenChangedWhileOffCancelsBothToo() {
        assertTrue(fold(addEvent(-1), updateEvent(-1), deleteEvent(-1)).isEmpty())
    }

    @Test fun changingSomethingAddedWhileOffChangesTheAddInsteadOfQueueingAnUpdate() {
        val queue = fold(addEvent(-1, "Haircut", 100), updateEvent(-1, "Dentist", keepRepeat = false))
        val only = queue.single() as AddEventChange
        assertEquals("Dentist", only.request.title)
        assertEquals(101, only.request.dateEpochDay)
        assertEquals(600, only.request.startMinuteOfDay)
        assertEquals(7, only.request.repeatIntervalDays)
        assertEquals("r-1", only.request.requestId, "still the same request, so a retry can't add it twice")
    }

    @Test fun keepingTheRepeatOfSomethingAddedWhileOffKeepsWhatItWasAddedWith() {
        val queue = fold(AddEventChange(id(), 1, -1, LinkEventCreate("Gym", 100, -1, 14, "x")), updateEvent(-1, "Gym", keepRepeat = true))
        assertEquals(14, (queue.single() as AddEventChange).request.repeatIntervalDays)
        val tasks = fold(addTask(-3), updateTask(-3, "Water now", keepRepeat = true))
        val task = tasks.single() as AddTaskChange
        assertEquals("Water now", task.request.name)
        assertEquals(2, task.request.repeatInterval)
        assertEquals("WEEKS", task.request.repeatUnit)
    }

    @Test fun changingSomethingThePhoneHoldsTwiceKeepsOnlyTheLastChange() {
        val first = updateEvent(5, "One")
        val second = updateEvent(5, "Two")
        assertEquals(listOf<PendingChange>(second), fold(first, second))
        assertEquals(2, fold(updateEvent(5), updateEvent(6)).size, "different things are separate")
    }

    @Test fun deletingSomethingThePhoneHoldsReplacesEarlierChangesToItAndAnEarlierDelete() {
        val del = deleteEvent(5)
        assertEquals(listOf<PendingChange>(del), fold(updateEvent(5), updateEvent(5, "More"), del))
        val again = deleteEvent(5)
        assertEquals(listOf<PendingChange>(again), fold(del, again))
        val task = deleteTask(2)
        assertEquals(listOf<PendingChange>(task), fold(updateTask(2), task))
    }

    @Test fun anEventAndATaskWithTheSameNumberAreDifferentThings() {
        val ev = deleteEvent(2)
        val tk = deleteTask(2)
        assertEquals(listOf<PendingChange>(ev, tk), fold(ev, tk))
    }

    @Test fun recordingAHabitOnADayReplacesTheEarlierChangeToThatHabitOnThatDayOnly() {
        val on = habit(1, 100, true)
        val off = habit(1, 100, false)
        val other = habit(2, 100, true)
        val nextDay = habit(1, 101, true)
        assertEquals(listOf<PendingChange>(other, nextDay, off), fold(on, other, nextDay, off))
    }

    // ---- temporary ids ----

    @Test fun aNewTemporaryIdIsBelowZeroAndNeverOneAlreadyWaiting() {
        val box = Outbox(null)
        assertEquals(-1L, box.nextTempId())
        box.enqueue(addEvent(-1))
        assertEquals(-2L, box.nextTempId())
        box.enqueue(addTask(-2))
        assertEquals(-3L, box.nextTempId())
        box.remove(box.changes.first().id)
        box.remove(box.changes.first().id)
        assertEquals(-1L, box.nextTempId(), "once everything has gone to the phone the numbers start again")
    }

    // ---- saved to disk ----

    @Test fun whatIsWaitingSurvivesClosingTheApp() {
        val file = File(dir, "outbox.json")
        val box = Outbox(file)
        val changes = listOf(addEvent(-1), updateEvent(5), deleteEvent(6), addTask(-2), updateTask(2), deleteTask(3), habit(1, 100, true))
        changes.forEach(box::enqueue)
        box.addNote("Delete event “Haircut”: it was changed on the phone later.")

        val reopened = Outbox(file)
        assertEquals(box.changes, reopened.changes)
        assertEquals(7, reopened.changes.size)
        assertEquals(listOf("Delete event “Haircut”: it was changed on the phone later."), reopened.notes)
    }

    @Test fun aChangeSentAndRemovedStaysGoneAfterReopening() {
        val file = File(dir, "outbox.json")
        val box = Outbox(file)
        val a = addEvent(-1)
        val b = deleteEvent(6)
        box.enqueue(a); box.enqueue(b)
        box.remove(a.id)
        assertEquals(listOf<PendingChange>(b), Outbox(file).changes)
    }

    @Test fun dismissedNotesStayDismissed() {
        val file = File(dir, "outbox.json")
        val box = Outbox(file)
        box.addNote("one")
        box.clearNotes()
        assertTrue(Outbox(file).notes.isEmpty())
    }

    @Test fun aDamagedFileIsSetAsideAndTheAppCarriesOnEmpty() {
        val file = File(dir, "outbox.json")
        file.writeText("{ this is not json")
        val box = Outbox(file)
        assertTrue(box.changes.isEmpty())
        assertTrue(File(dir, "outbox.json.unreadable").exists(), "kept for a look")
        box.enqueue(deleteEvent(6))
        assertEquals(1, Outbox(file).changes.size, "and it saves again")
    }

    @Test fun onlyTheMostRecentNotesAreKept() {
        val box = Outbox(null)
        repeat(30) { box.addNote("note $it") }
        assertEquals(20, box.notes.size)
        assertEquals("note 29", box.notes.last())
    }

    @Test fun eachChangeSendsToTheSamePathTheLiveEditWould() {
        val json = Json { encodeDefaults = true }
        assertEquals("/v1/event", addEvent(-1).path)
        assertEquals("/v1/event/update", updateEvent(5).path)
        assertEquals("/v1/event/delete", deleteEvent(5).path)
        assertEquals("/v1/task", addTask(-1).path)
        assertEquals("/v1/task/update", updateTask(2).path)
        assertEquals("/v1/task/delete", deleteTask(2).path)
        assertEquals("/v1/habit", habit(1, 100, true).path)
        assertEquals(json.encodeToString(LinkEventDelete.serializer(), LinkEventDelete(6, 3_000)), deleteEvent(6).bodyJson(json))
    }

    @Test fun theWordsForEachChangeNameWhatItIs() {
        assertEquals("Add event “Haircut”", addEvent(-1).label)
        assertEquals("Delete event “Haircut”", deleteEvent(6).label)
        assertEquals("Delete task “Water”", deleteTask(3).label)
        assertEquals("Record Gym", habit(1, 100, true).label)
        assertEquals("Clear Gym", habit(1, 100, false).label)
    }
}
