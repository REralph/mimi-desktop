package com.example.a2desktop

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import com.example.a2desktop.data.DataSource
import com.example.a2desktop.data.DesktopStore
import com.example.a2desktop.data.sampleSnapshot
import com.example.a2desktop.link.LinkAddress
import com.example.a2desktop.link.LinkController
import com.example.a2desktop.link.LinkResult
import com.example.a2desktop.link.LinkStorage
import com.example.a2desktop.link.SnapshotSource
import com.example.a2desktop.link.StoredPairing
import com.example.a2desktop.logic.ClipboardAccess
import com.example.a2desktop.logic.ClipboardRead
import com.example.a2desktop.theme.A2Theme
import com.example.a2desktop.ui.A2App
import com.example.a2desktop.ui.Page
import java.nio.file.Files
import java.time.LocalDate
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Drives the real UI with no window: hundreds of random clicks and scrolls on every page, with
 * sample data and with (fake) phone data, plus phone failures. Any exception thrown while composing,
 * laying out or handling an input event fails the test — the closest thing to "does it crash when
 * somebody clicks around?" that can run unattended.
 */
@OptIn(ExperimentalComposeUiApi::class)
class UiSmokeTest {
    private val width = 1360
    private val height = 860
    private val json = Json { encodeDefaults = true }

    private class Phone(var fail: Boolean = false) : SnapshotSource {
        private val json = Json { encodeDefaults = true }
        override fun fetch(address: LinkAddress, pairingKey: String): LinkResult {
            if (fail) return LinkResult.Failed("Couldn't reach the phone")
            val snapshot = sampleSnapshot(LocalDate.now()).copy(deviceName = "Smoke phone")
            return LinkResult.Ok(snapshot, json.encodeToString(snapshot))
        }
    }

    /** An in-memory clipboard, so a test never reads or overwrites the real one. */
    private class FakeClipboard(var content: ClipboardRead = ClipboardRead.Empty) : ClipboardAccess {
        val writes = mutableListOf<String>()
        override fun read() = content
        override fun write(text: String): Boolean {
            writes += text
            content = ClipboardRead.Text(text)
            return true
        }
    }

    private fun storage() = LinkStorage(Files.createTempDirectory("a2-smoke").toFile())

    private fun click(scene: ImageComposeScene, at: Offset, time: Long) {
        val buttons = PointerButtons(isPrimaryPressed = true)
        scene.sendPointerEvent(PointerEventType.Move, at)
        scene.sendPointerEvent(PointerEventType.Press, at, buttons = buttons, button = PointerButton.Primary)
        scene.render(time)
        scene.sendPointerEvent(PointerEventType.Release, at, button = PointerButton.Primary)
        scene.render(time + 16_000_000)
    }

    private fun hammer(
        label: String, store: DesktopStore, link: LinkController, page: Page, seed: Int, clicks: Int,
        clipboard: ClipboardAccess = FakeClipboard(),
    ) {
        val scene = ImageComposeScene(width = width, height = height, density = Density(1f)) {
            A2Theme { A2App(store, link, page, clipboard) }
        }
        try {
            val rng = Random(seed)
            var time = 0L
            scene.render(time)
            repeat(clicks) { i ->
                time += 50_000_000
                val at = Offset(rng.nextInt(0, width).toFloat(), rng.nextInt(0, height).toFloat())
                try {
                    if (i % 7 == 0) {
                        scene.sendPointerEvent(PointerEventType.Scroll, at, scrollDelta = Offset(0f, rng.nextInt(-6, 7).toFloat()))
                        scene.render(time)
                    } else {
                        click(scene, at, time)
                    }
                } catch (e: Throwable) {
                    throw AssertionError("$label: crashed on action #$i at $at: ${e::class.simpleName}: ${e.message}", e)
                }
            }
        } finally {
            scene.close()
        }
    }

    @Test fun everyPageSurvivesRandomClicksWithSampleData() {
        Page.entries.forEachIndexed { index, page ->
            val store = DesktopStore().also { it.loadSampleData() }
            val link = LinkController(store, storage(), Phone(), CoroutineScope(Job()), Dispatchers.Unconfined)
            hammer("sample/${page.name}", store, link, page, seed = 100 + index, clicks = 220)
        }
        assertTrue(true)
    }

    @Test fun everyPageSurvivesRandomClicksWithPhoneData() {
        Page.entries.forEachIndexed { index, page ->
            val store = DesktopStore()
            val s = storage().also { it.savePairing(StoredPairing("192.168.1.23:47321", "ABCDEFGHJKLMNPQRSTUV")) }
            val link = LinkController(store, s, Phone(), CoroutineScope(Job()), Dispatchers.Unconfined)
            hammer("phone/${page.name}", store, link, page, seed = 200 + index, clicks = 220)
        }
    }

    @Test fun anUnreachablePhoneAndEmptyDataDoNotBreakAnyPage() {
        Page.entries.forEachIndexed { index, page ->
            val store = DesktopStore()
            val phone = Phone()
            val s = storage().also { it.savePairing(StoredPairing("192.168.1.23:47321", "ABCDEFGHJKLMNPQRSTUV")) }
            val link = LinkController(store, s, phone, CoroutineScope(Job()), Dispatchers.Unconfined)
            // A phone that has nothing at all yet: no habits, events, dreams or workouts.
            store.applySnapshot(sampleSnapshot(store.today).copy(
                habits = emptyList(), habitLogs = emptyList(), events = emptyList(), upcoming = emptyList(),
                dreams = emptyList(), workouts = emptyList(), earliestTrackedEpochDay = null, dateOfBirthEpochDay = null,
            ))
            phone.fail = true
            link.syncNow()
            hammer("empty/${page.name}", store, link, page, seed = 300 + index, clicks = 120)
        }
    }

    @Test fun theNoDataPagesAndTheFirstPairingScreenSurviveClicking() {
        // Never paired, nothing received: every data page is the "no data yet" card, Phone is the form.
        Page.entries.forEachIndexed { index, page ->
            val store = DesktopStore()
            val link = LinkController(store, storage(), Phone(), CoroutineScope(Job()), Dispatchers.Unconfined)
            assertEquals(DataSource.NONE, store.source)
            hammer("nodata/${page.name}", store, link, page, seed = 400 + index, clicks = 120)
        }
        // Paired but the phone is off and nothing was cached: the card offers Sync now.
        val phone = Phone(fail = true)
        val s = storage().also { it.savePairing(StoredPairing("192.168.1.23:47321", "ABCDEFGHJKLMNPQRSTUV")) }
        val store = DesktopStore()
        val link = LinkController(store, s, phone, CoroutineScope(Job()), Dispatchers.Unconfined)
        assertEquals(DataSource.NONE, store.source)
        hammer("paired-offline", store, link, Page.CALENDAR, seed = 500, clicks = 120)
    }

    @Test fun theDreamsCopyPasteButtonsAndDialogsSurviveBeingClickedEverywhere() {
        // Clipboard holds a titled correction, so Paste reaches its confirmation dialog and Undo.
        val clipboard = FakeClipboard(ClipboardRead.Text("Title: Corrected title\n\nA corrected description."))
        val store = DesktopStore().also { it.loadSampleData() }
        val link = LinkController(store, storage(), Phone(), CoroutineScope(Job()), Dispatchers.Unconfined)
        val scene = ImageComposeScene(width = width, height = height, density = Density(1f)) {
            A2Theme { A2App(store, link, Page.DREAMS, clipboard) }
        }
        try {
            var time = 0L
            scene.render(time)
            fun tap(x: Int, y: Int) { time += 60_000_000; click(scene, Offset(x.toFloat(), y.toFloat()), time) }
            // Sweep the editor's button row at several heights, then the middle of the window where
            // dialogs and their buttons appear, over and over so every combination gets exercised.
            repeat(3) {
                for (y in listOf(700, 720, 740, 763, 781, 800)) for (x in 440..1300 step 14) tap(x, y)
                for (y in 380..600 step 20) for (x in 480..900 step 40) tap(x, y)
            }
            assertTrue(clipboard.writes.isNotEmpty(), "Copy was never reached by the sweep")
            assertTrue(clipboard.writes.first().startsWith("Title: "), clipboard.writes.first())
        } finally {
            scene.close()
        }
    }

    @Test fun theDreamsButtonsOnThePhonesCopyCopyInTheLabelledFormatAndCannotChangeAnythingWithoutAPhoneToSaveTo() {
        val clipboard = FakeClipboard()
        val store = DesktopStore()
        val s = storage().also { it.savePairing(StoredPairing("192.168.1.23:47321", "ABCDEFGHJKLMNPQRSTUV")) }
        // This controller has no way to reach a phone's saving (the default writer always fails).
        val link = LinkController(store, s, Phone(), CoroutineScope(Job()), Dispatchers.Unconfined)
        assertTrue(store.readOnly)
        val before = store.dreams.toList()
        val scene = ImageComposeScene(width = width, height = height, density = Density(1f)) {
            A2Theme { A2App(store, link, Page.DREAMS, clipboard) }
        }
        try {
            var time = 0L
            scene.render(time)
            for (y in listOf(740, 763, 781)) for (x in 440..1300 step 14) { time += 60_000_000; click(scene, Offset(x.toFloat(), y.toFloat()), time) }
            assertEquals(before, store.dreams.toList(), "a failed save must never change the dreams on screen")
            assertTrue(clipboard.writes.isNotEmpty(), "Copy should work on the phone's dreams")
            val copied = clipboard.writes.first()
            assertTrue(copied.startsWith("Title: ") && "\n\nDescription:\n" in copied, copied)
        } finally {
            scene.close()
        }
    }
}
