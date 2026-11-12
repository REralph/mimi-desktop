package com.example.a2desktop

import com.example.a2desktop.data.DataSource
import com.example.a2desktop.data.DesktopStore
import com.example.a2desktop.data.sampleSnapshot
import com.example.a2desktop.link.EditResult
import com.example.a2desktop.link.EditWriter
import com.example.a2desktop.link.LinkAddress
import com.example.a2desktop.link.LinkController
import com.example.a2desktop.link.LinkEditReply
import com.example.a2desktop.link.LinkEventCreate
import com.example.a2desktop.link.LinkHabitEdit
import com.example.a2desktop.link.LinkResult
import com.example.a2desktop.link.LinkStorage
import com.example.a2desktop.link.LinkTaskCreate
import com.example.a2desktop.link.SnapshotSource
import com.example.a2desktop.link.StoredPairing
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Recording a habit on a day, adding an event, adding a task: what the controller sends, shows and re-syncs. */
class PhoneEditsTest {
    private val dir: File = Files.createTempDirectory("a2-edits").toFile()
    private val json = Json { encodeDefaults = true }
    private val today = LocalDate.of(2026, 10, 14)

    @AfterTest fun cleanUp() {
        dir.deleteRecursively()
    }

    private class Phone(private val json: Json, private val today: LocalDate) : SnapshotSource {
        var fetches = 0
        var duringFetch: (() -> Unit)? = null

        /** Habits the phone now holds on days it was told about: a real phone's next snapshot has them. */
        val recorded = mutableMapOf<LocalDate, Set<Long>>()

        override fun fetch(address: LinkAddress, pairingKey: String): LinkResult {
            fetches++
            duringFetch?.also { duringFetch = null }?.invoke()
            val base = sampleSnapshot(today)
            val days = mutableMapOf<Long, MutableSet<Long>>()
            base.habitLogs.forEach { days[it.habitId] = it.days.toMutableSet() }
            recorded.forEach { (date, ids) ->
                days.values.forEach { it.remove(date.toEpochDay()) }
                ids.forEach { days.getOrPut(it) { mutableSetOf() }.add(date.toEpochDay()) }
            }
            val snapshot = base.copy(
                deviceName = "Test Phone",
                habitLogs = days.map { (habit, set) -> com.example.a2desktop.link.LinkHabitLog(habit, set.sorted()) },
            )
            return LinkResult.Ok(snapshot, json.encodeToString(snapshot))
        }
    }

    private class Editor(var reply: (String, String) -> EditResult) : EditWriter {
        val sent = mutableListOf<Pair<String, String>>()
        override fun send(address: LinkAddress, pairingKey: String, path: String, bodyJson: String): EditResult {
            sent += path to bodyJson
            return reply(path, bodyJson)
        }
    }

    private class Rig(val store: DesktopStore, val link: LinkController, val phone: Phone, val editor: Editor)

    private fun rig(reply: (String, String) -> EditResult = { _, _ -> EditResult.Failed("unused") }): Rig {
        val store = DesktopStore(today)
        val storage = LinkStorage(dir).also { it.savePairing(StoredPairing("192.168.1.23:47321", "ABCDEFGHJKLMNPQRSTUV")) }
        val phone = Phone(json, today)
        val editor = Editor { path, body ->
            reply(path, body).also { result ->
                // The phone stores what it accepted before it answers.
                if (result is EditResult.Done && result.reply.habitIds != null) {
                    phone.recorded[LocalDate.ofEpochDay(result.reply.dateEpochDay!!)] = result.reply.habitIds!!.toSet()
                }
            }
        }
        val link = LinkController(store, storage, phone, CoroutineScope(Job()), Dispatchers.Unconfined, editor = editor)
        assertEquals(DataSource.PHONE, store.source)
        return Rig(store, link, phone, editor)
    }

    private val gym get() = sampleSnapshot(today).habits.first { it.name == "Gym" }
    private val oct27 = LocalDate.of(2026, 10, 27)

    @Test fun recordingAHabitSendsItsDayAndShowsWhatThePhoneAnswers() {
        val r = rig { _, _ -> EditResult.Done(LinkEditReply(ok = true, dateEpochDay = oct27.toEpochDay(), habitIds = listOf(gym.id))) }
        var outcome: EditResult? = null
        r.link.setHabit(oct27, gym.id, logged = true) { outcome = it }

        val (path, body) = r.editor.sent.single()
        assertEquals("/v1/habit", path)
        assertEquals(LinkHabitEdit(gym.id, oct27.toEpochDay(), true), Json.decodeFromString<LinkHabitEdit>(body))
        assertTrue(outcome is EditResult.Done)
        assertEquals(setOf(gym.id), r.store.logs[oct27])
        assertTrue(r.link.reachable)
        assertFalse(r.link.editing)
    }

    @Test fun whatThePhoneAnswersWinsEvenWhenItClearedAnotherHabitAsWell() {
        // Recording a habit the phone has linked to another also clears the other: show the phone's result.
        val other = sampleSnapshot(today).habits.first { it.name == "Drink" }
        val r = rig { _, _ -> EditResult.Done(LinkEditReply(ok = true, dateEpochDay = oct27.toEpochDay(), habitIds = listOf(other.id))) }
        r.store.applyHabitDay(oct27, setOf(gym.id))
        r.link.setHabit(oct27, other.id, logged = true)
        assertEquals(setOf(other.id), r.store.logs[oct27])
    }

    @Test fun clearingTheLastHabitOfADayLeavesNoRecord() {
        val r = rig { _, _ -> EditResult.Done(LinkEditReply(ok = true, dateEpochDay = oct27.toEpochDay(), habitIds = emptyList())) }
        r.store.applyHabitDay(oct27, setOf(gym.id))
        r.link.setHabit(oct27, gym.id, logged = false)
        assertFalse(oct27 in r.store.logs.keys)
    }

    @Test fun aSuccessfulChangeIsFollowedByAFreshSync() {
        val r = rig { _, _ -> EditResult.Done(LinkEditReply(ok = true)) }
        val before = r.phone.fetches
        r.link.addEvent(LinkEventCreate("Dentist", oct27.toEpochDay(), 870, null))
        assertEquals(before + 1, r.phone.fetches)
    }

    @Test fun aSyncAlreadyRunningCannotSwallowTheOneAfterAChange() {
        // The running sync may have read the phone BEFORE the change, so another must follow it.
        val r = rig()
        val before = r.phone.fetches
        r.phone.duringFetch = { r.link.syncNow() } // someone asks while this sync is in flight
        r.link.syncNow()
        assertEquals(before + 2, r.phone.fetches)
    }

    @Test fun whenThePhoneRefusesNothingChangesHereAndItCatchesUp() {
        val r = rig { _, _ -> EditResult.Failed("Sober is worked out automatically.", phoneRefused = true) }
        val before = r.store.logs.toMap()
        val fetches = r.phone.fetches
        var outcome: EditResult? = null
        r.link.setHabit(oct27, gym.id, logged = true) { outcome = it }
        assertEquals("Sober is worked out automatically.", (outcome as EditResult.Failed).message)
        assertEquals(before, r.store.logs.toMap())
        assertTrue(r.link.reachable)
        assertEquals(fetches + 1, r.phone.fetches)
    }

    @Test fun whenThePhoneCannotBeReachedNothingChangesAndTheControlsComeBack() {
        val r = rig { _, _ -> EditResult.Failed("Could not reach the phone") }
        val before = r.store.logs.toMap()
        var outcome: EditResult? = null
        r.link.setHabit(oct27, gym.id, logged = true) { outcome = it }
        assertTrue(outcome is EditResult.Failed)
        assertEquals(before, r.store.logs.toMap())
        assertFalse(r.link.reachable)
        assertFalse(r.link.editing)
    }

    @Test fun eventsAndTasksGoToTheirOwnPathsWithTheirOwnFields() {
        val r = rig { _, _ -> EditResult.Done(LinkEditReply(ok = true)) }
        r.link.addEvent(LinkEventCreate("Dentist", oct27.toEpochDay(), 870, 14))
        r.link.addTask(LinkTaskCreate("Water change", oct27.toEpochDay(), 2, "WEEKS"))
        assertEquals(listOf("/v1/event", "/v1/task"), r.editor.sent.map { it.first })
        assertEquals(LinkEventCreate("Dentist", oct27.toEpochDay(), 870, 14), Json.decodeFromString<LinkEventCreate>(r.editor.sent[0].second).copy(requestId = null))
        assertEquals(LinkTaskCreate("Water change", oct27.toEpochDay(), 2, "WEEKS"), Json.decodeFromString<LinkTaskCreate>(r.editor.sent[1].second).copy(requestId = null))
    }

    @Test fun anEditorThatBlowsUpIsReportedNotThrown() {
        val r = rig { _, _ -> error("disk on fire") }
        var outcome: EditResult? = null
        r.link.addTask(LinkTaskCreate("x", oct27.toEpochDay())) { outcome = it }
        assertTrue(outcome is EditResult.Failed)
        assertFalse(r.link.editing)
    }

    @Test fun withNoPhonePairedThereIsNowhereToSendAnything() {
        val store = DesktopStore(today).also { it.loadSampleData() }
        val editor = Editor { _, _ -> EditResult.Done(LinkEditReply(ok = true)) }
        val link = LinkController(store, LinkStorage(dir), Phone(json, today), CoroutineScope(Job()), Dispatchers.Unconfined, editor = editor)
        var outcome: EditResult? = null
        link.setHabit(oct27, 1, true) { outcome = it }
        assertTrue(outcome is EditResult.Failed)
        assertTrue(editor.sent.isEmpty())
    }

    @Test fun aPhoneDaySetIsOnlyEverAppliedToPhoneDataNeverToDemoData() {
        val demo = DesktopStore(today).also { it.loadSampleData() }
        val before = demo.logs.toMap()
        demo.applyHabitDay(oct27, setOf(1))
        assertEquals(before, demo.logs.toMap())
    }
}
