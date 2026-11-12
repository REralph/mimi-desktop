package com.example.a2desktop

import com.example.a2desktop.data.DataSource
import com.example.a2desktop.data.DesktopStore
import com.example.a2desktop.data.sampleSnapshot
import com.example.a2desktop.link.DreamSaveResult
import com.example.a2desktop.link.DreamWriter
import com.example.a2desktop.link.LinkAddress
import com.example.a2desktop.link.LinkController
import com.example.a2desktop.link.LinkDream
import com.example.a2desktop.link.LinkDreamEdit
import com.example.a2desktop.link.LinkResult
import com.example.a2desktop.link.LinkStorage
import com.example.a2desktop.link.SnapshotSource
import com.example.a2desktop.link.StoredPairing
import com.example.a2desktop.model.DreamStatus
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

/** Saving a dream from the desktop: what the controller sends, what it shows afterwards, and when it re-syncs. */
class DreamSaveTest {
    private val dir: File = Files.createTempDirectory("a2-dream-save").toFile()
    private val json = Json { encodeDefaults = true }
    private val today = LocalDate.of(2026, 10, 14)

    @AfterTest fun cleanUp() {
        dir.deleteRecursively()
    }

    private class CountingPhone(private val json: Json, private val today: LocalDate) : SnapshotSource {
        var fetches = 0
        /** Dreams the phone has saved: a real phone's next snapshot contains them. */
        val saved = mutableMapOf<Long, LinkDream>()
        override fun fetch(address: LinkAddress, pairingKey: String): LinkResult {
            fetches++
            val base = sampleSnapshot(today)
            val snapshot = base.copy(deviceName = "Test Phone", dreams = base.dreams.map { saved[it.id] ?: it })
            return LinkResult.Ok(snapshot, json.encodeToString(snapshot))
        }
    }

    private class RecordingWriter(var reply: (LinkDreamEdit) -> DreamSaveResult) : DreamWriter {
        val edits = mutableListOf<LinkDreamEdit>()
        override fun save(address: LinkAddress, pairingKey: String, edit: LinkDreamEdit): DreamSaveResult {
            edits += edit
            return reply(edit)
        }
    }

    private class Setup(val store: DesktopStore, val link: LinkController, val phone: CountingPhone, val writer: RecordingWriter)

    private fun paired(reply: (LinkDreamEdit) -> DreamSaveResult = { DreamSaveResult.Failed("unused") }): Setup {
        val store = DesktopStore(today)
        val storage = LinkStorage(dir).also { it.savePairing(StoredPairing("192.168.1.23:47321", "ABCDEFGHJKLMNPQRSTUV")) }
        val phone = CountingPhone(json, today)
        val writer = RecordingWriter { edit -> reply(edit).also { if (it is DreamSaveResult.Saved) phone.saved[it.dream.id] = it.dream } }
        val link = LinkController(store, storage, phone, CoroutineScope(Job()), Dispatchers.Unconfined, writer)
        assertEquals(DataSource.PHONE, store.source)
        return Setup(store, link, phone, writer)
    }

    private fun editOf(s: Setup, title: String, text: String): LinkDreamEdit {
        val dream = s.store.dreams.first()
        return LinkDreamEdit(dream.id, dream.title, dream.text, title, text)
    }

    private fun savedCopy(edit: LinkDreamEdit) = LinkDream(edit.id, today.toEpochDay(), edit.title, edit.description, "SAVED")

    @Test fun aSavedDreamShowsThePhonesOwnSavedCopyAndTriggersAFreshSync() {
        val s = paired { DreamSaveResult.Saved(savedCopy(it)) }
        val fetchesBefore = s.phone.fetches
        val edit = editOf(s, "Corrected title", "A corrected description.")
        var outcome: DreamSaveResult? = null
        s.link.saveDream(edit) { outcome = it }

        assertEquals(listOf(edit), s.writer.edits)
        assertTrue(outcome is DreamSaveResult.Saved)
        val shown = s.store.dreams.first { it.id == edit.id }
        assertEquals("Corrected title", shown.title)
        assertEquals("A corrected description.", shown.text)
        assertEquals(DreamStatus.SAVED, shown.status)
        assertTrue(s.link.reachable)
        assertFalse(s.link.savingDream)
        assertEquals(fetchesBefore + 1, s.phone.fetches, "a successful save should be followed by a fresh sync")
    }

    @Test fun theEditCarriesWhatThePhoneLastShowedSoThePhoneCanSpotAStaleDesktop() {
        val s = paired { DreamSaveResult.Saved(savedCopy(it)) }
        val original = s.store.dreams.first()
        s.link.saveDream(editOf(s, "New", "New text")) { }
        val sent = s.writer.edits.single()
        assertEquals(original.id, sent.id)
        assertEquals(original.title, sent.baseTitle)
        assertEquals(original.text, sent.baseDescription)
    }

    @Test fun whenThePhoneRefusesNothingChangesOnTheDesktopAndItCatchesUpWithTheNewPhoneData() {
        val s = paired { DreamSaveResult.Failed("This dream was changed on the phone.", phoneRefused = true) }
        val before = s.store.dreams.toList()
        val fetchesBefore = s.phone.fetches
        var outcome: DreamSaveResult? = null
        s.link.saveDream(editOf(s, "Mine", "My text")) { outcome = it }

        assertEquals("This dream was changed on the phone.", (outcome as DreamSaveResult.Failed).message)
        assertEquals(before, s.store.dreams.toList())
        assertTrue(s.link.reachable, "the phone answered, so it is reachable")
        assertEquals(fetchesBefore + 1, s.phone.fetches, "a refusal means the desktop is stale: sync")
    }

    @Test fun whenThePhoneCannotBeReachedNothingChangesAndTheUserIsTold() {
        val s = paired { DreamSaveResult.Failed("Couldn't reach the phone") }
        val before = s.store.dreams.toList()
        val fetchesBefore = s.phone.fetches
        var outcome: DreamSaveResult? = null
        s.link.saveDream(editOf(s, "Mine", "My text")) { outcome = it }

        assertTrue(outcome is DreamSaveResult.Failed)
        assertEquals(before, s.store.dreams.toList())
        assertFalse(s.link.reachable)
        assertEquals(fetchesBefore, s.phone.fetches, "no point asking again straight away")
        assertFalse(s.link.savingDream, "the Save button must come back after a failure")
    }

    @Test fun aWriterThatBlowsUpIsReportedNotThrown() {
        val s = paired { error("disk on fire") }
        var outcome: DreamSaveResult? = null
        s.link.saveDream(editOf(s, "Mine", "My text")) { outcome = it }
        assertTrue(outcome is DreamSaveResult.Failed)
        assertFalse(s.link.savingDream)
    }

    @Test fun withNoPhonePairedThereIsNowhereToSaveAndTheWriterIsNeverCalled() {
        val store = DesktopStore(today).also { it.loadSampleData() }
        val writer = RecordingWriter { DreamSaveResult.Saved(savedCopy(it)) }
        val link = LinkController(store, LinkStorage(dir), CountingPhone(json, today), CoroutineScope(Job()), Dispatchers.Unconfined, writer)
        var outcome: DreamSaveResult? = null
        link.saveDream(LinkDreamEdit(1, "", "", "x", "y")) { outcome = it }
        assertTrue(outcome is DreamSaveResult.Failed)
        assertTrue(writer.edits.isEmpty())
    }

    @Test fun aPhoneDreamIsOnlyEverReplacedInPhoneDataNeverInDemoData() {
        val demo = DesktopStore(today).also { it.loadSampleData() }
        val before = demo.dreams.toList()
        demo.applyDreamFromPhone(LinkDream(before.first().id, today.toEpochDay(), "Sneaky", "Sneaky", "SAVED"))
        assertEquals(before, demo.dreams.toList())
    }

    @Test fun aPhoneAppFromBeforeDreamSavingExistedIsExplainedNotShownAsAHttpCode() {
        // Stands in for the old phone app: its server answers any POST with 405.
        val old = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        old.createContext("/") { exchange ->
            exchange.sendResponseHeaders(405, -1)
            exchange.close()
        }
        old.start()
        try {
            val result = com.example.a2desktop.link.LinkClient().save(
                LinkAddress("127.0.0.1", old.address.port), "ABCDEFGHJKLMNPQRSTUV", LinkDreamEdit(1, "a", "b", "c", "d"),
            )
            assertTrue(result is DreamSaveResult.Failed)
            val message = (result as DreamSaveResult.Failed).message
            assertTrue("too old" in message && "HTTP" !in message, message)
            assertFalse(result.phoneRefused)
        } finally {
            old.stop(0)
        }
    }
}
