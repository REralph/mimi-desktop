package com.example.a2desktop

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
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
import com.example.a2desktop.logic.ClipboardAccess
import com.example.a2desktop.logic.ClipboardRead
import com.example.a2desktop.logic.formatDreamForClipboard
import com.example.a2desktop.theme.A2Theme
import com.example.a2desktop.ui.A2App
import com.example.a2desktop.ui.Page
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * The Dreams page's three buttons — Copy, Paste, Save dream, side by side — clicked for real in a
 * headless window with the phone's data on screen: Copy puts the labelled text on the clipboard,
 * Paste brings a corrected "Title: / Description:" text back, and Save dream sends it to the phone.
 */
@OptIn(ExperimentalComposeUiApi::class)
class DreamButtonsTest {
    private val dir: File = Files.createTempDirectory("a2-dream-buttons").toFile()
    private val json = Json { encodeDefaults = true }
    private val today = LocalDate.of(2026, 10, 14)

    @AfterTest fun cleanUp() {
        dir.deleteRecursively()
    }

    // Positions in the 1360 x 860 window (see the dreams_linked preview): the button row (92 px further left now the menu is along the top
    // and not down the left side), and the "Replace" button of the dialog Paste shows when it would overwrite text.
    private val copy = Offset(504f, 793f)
    private val paste = Offset(597f, 793f)
    private val save = Offset(710f, 793f)
    private val replace = Offset(887f, 503f)

    private class Phone(private val today: LocalDate, private val json: Json) : SnapshotSource {
        val saved = mutableMapOf<Long, LinkDream>()
        override fun fetch(address: LinkAddress, pairingKey: String): LinkResult {
            val base = sampleSnapshot(today)
            val snapshot = base.copy(deviceName = "Test Phone", dreams = base.dreams.map { saved[it.id] ?: it })
            return LinkResult.Ok(snapshot, json.encodeToString(snapshot))
        }
    }

    private class Writer(private val phone: Phone, private val today: LocalDate, var fail: Boolean = false) : DreamWriter {
        val edits = mutableListOf<LinkDreamEdit>()
        override fun save(address: LinkAddress, pairingKey: String, edit: LinkDreamEdit): DreamSaveResult {
            edits += edit
            if (fail) return DreamSaveResult.Failed("Couldn't reach the phone")
            val dream = LinkDream(edit.id, today.toEpochDay(), edit.title.trim(), edit.description.trim(), "SAVED")
            phone.saved[edit.id] = dream
            return DreamSaveResult.Saved(dream)
        }
    }

    private class FakeClipboard(var content: ClipboardRead = ClipboardRead.Empty) : ClipboardAccess {
        val writes = mutableListOf<String>()
        override fun read() = content
        override fun write(text: String): Boolean {
            writes += text
            content = ClipboardRead.Text(text)
            return true
        }
    }

    private class Rig(val store: DesktopStore, val writer: Writer, val clipboard: FakeClipboard, val scene: ImageComposeScene) {
        var time = 0L
        fun click(at: Offset) {
            time += 100_000_000
            scene.sendPointerEvent(PointerEventType.Move, at)
            scene.sendPointerEvent(PointerEventType.Press, at, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
            scene.render(time)
            scene.sendPointerEvent(PointerEventType.Release, at, button = PointerButton.Primary)
            scene.render(time + 16_000_000)
            repeat(12) { time += 50_000_000; scene.render(time) } // let a dialog finish animating
        }
    }

    private fun rig(clipboard: FakeClipboard, failSaves: Boolean = false): Rig {
        val store = DesktopStore(today)
        val storage = LinkStorage(dir).also { it.savePairing(StoredPairing("192.168.1.23:47321", "ABCDEFGHJKLMNPQRSTUV")) }
        val phone = Phone(today, json)
        val writer = Writer(phone, today, failSaves)
        val link = LinkController(store, storage, phone, CoroutineScope(Job()), Dispatchers.Unconfined, writer)
        val scene = ImageComposeScene(width = 1360, height = 860, density = Density(1f)) {
            A2Theme { A2App(store, link, Page.DREAMS, clipboard) }
        }
        scene.render(0)
        return Rig(store, writer, clipboard, scene)
    }

    @Test fun copyPutsTheLabelledTitleAndDescriptionOnTheClipboard() {
        val r = rig(FakeClipboard())
        try {
            val dream = r.store.dreams.first()
            r.click(copy)
            assertEquals(listOf(formatDreamForClipboard(dream.title, dream.text)), r.clipboard.writes)
            assertTrue(r.clipboard.writes.single().startsWith("Title: ${dream.title}\n\nDescription:\n${dream.text}"))
        } finally {
            r.scene.close()
        }
    }

    @Test fun pasteThenSaveSendsTheCorrectedTitleAndDescriptionToThePhone() {
        val r = rig(FakeClipboard(ClipboardRead.Text("Title: Flying over the lake\n\nDescription:\nI was gliding low over a still lake at dawn.")))
        try {
            val original = r.store.dreams.first()
            r.click(paste)
            r.click(replace) // it replaces existing text, so Paste asks first
            r.click(save)

            val sent = r.writer.edits.single()
            assertEquals(original.id, sent.id)
            assertEquals(original.title, sent.baseTitle, "what the phone last showed travels along")
            assertEquals(original.text, sent.baseDescription)
            assertEquals("Flying over the lake", sent.title)
            assertEquals("I was gliding low over a still lake at dawn.", sent.description)
            // And what shows afterwards is the phone's own saved copy.
            assertEquals("Flying over the lake", r.store.dreams.first { it.id == original.id }.title)
        } finally {
            r.scene.close()
        }
    }

    @Test fun pastingAloneSavesNothing() {
        val r = rig(FakeClipboard(ClipboardRead.Text("Title: Changed\n\nDescription:\nChanged body.")))
        try {
            val before = r.store.dreams.toList()
            r.click(paste)
            r.click(replace)
            assertTrue(r.writer.edits.isEmpty(), "Paste must never save")
            assertEquals(before, r.store.dreams.toList())
        } finally {
            r.scene.close()
        }
    }

    @Test fun whenTheSaveFailsTheDreamOnScreenIsLeftAlone() {
        val r = rig(FakeClipboard(ClipboardRead.Text("Title: Changed\n\nDescription:\nChanged body.")), failSaves = true)
        try {
            val before = r.store.dreams.toList()
            r.click(paste)
            r.click(replace)
            r.click(save)
            assertEquals(1, r.writer.edits.size, "the save was attempted")
            assertEquals(before, r.store.dreams.toList(), "a failed save must not pretend it worked")
        } finally {
            r.scene.close()
        }
    }
}
