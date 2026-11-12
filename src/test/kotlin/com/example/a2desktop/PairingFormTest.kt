package com.example.a2desktop

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.KeyEvent
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
import com.example.a2desktop.theme.A2Theme
import com.example.a2desktop.ui.A2App
import com.example.a2desktop.ui.Page
import java.nio.file.Files
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Does what a person does on the Phone page: open "Enter address and key instead", click the address box, type or paste, click the key box,
 * type or paste, click Connect and sync — with real mouse and keyboard events fed to the real screen —
 * and checks the result. This is the test that catches "I entered it and it won't connect".
 */
@OptIn(ExperimentalComposeUiApi::class)
class PairingFormTest {
    private val dummy = java.awt.Canvas()
    private val width = 1360
    private val height = 860

    /** A phone that answers only on its Wi-Fi address — like the real one: its 10.x address is the
     * mobile-data side, which the PC can't reach. */
    private class WifiOnlyPhone : SnapshotSource {
        val attempts = mutableListOf<String>()
        var lastKey: String? = null
        override fun fetch(address: LinkAddress, pairingKey: String): LinkResult {
            attempts += address.toString()
            lastKey = pairingKey
            if (!address.host.startsWith("192.168.")) return LinkResult.Failed("Couldn't reach the phone at $address.")
            val snapshot = sampleSnapshot(LocalDate.now()).copy(deviceName = "Typed-in phone")
            return LinkResult.Ok(snapshot, Json { encodeDefaults = true }.encodeToString(snapshot))
        }
    }

    /** Compose's own AWT→Compose key conversion (what the real window runs), reached by reflection
     * because it is `internal` in source but public in the compiled library. */
    private fun composeKeyEvent(awt: java.awt.event.KeyEvent): KeyEvent {
        val internal = Class.forName("androidx.compose.ui.input.key.KeyEvent_desktopKt")
            .getMethod("toComposeEvent", java.awt.event.KeyEvent::class.java).invoke(null, awt)
        return KeyEvent::class.java.getMethod("box-impl", Any::class.java).invoke(null, internal) as KeyEvent
    }

    /** Types [text] one character at a time as real AWT KEY_TYPED events — what the Windows keyboard
     * delivers to the app's window. */
    private fun type(scene: ImageComposeScene, text: String, startTime: Long): Long {
        var time = startTime
        for (c in text) {
            val awt = java.awt.event.KeyEvent(dummy, java.awt.event.KeyEvent.KEY_TYPED, time / 1_000_000, 0, java.awt.event.KeyEvent.VK_UNDEFINED, c)
            scene.sendKeyEvent(composeKeyEvent(awt))
            time += 16_000_000
            scene.render(time)
        }
        return time
    }

    private fun click(scene: ImageComposeScene, x: Float, y: Float, time: Long) {
        val at = Offset(x, y)
        scene.sendPointerEvent(PointerEventType.Move, at)
        scene.sendPointerEvent(PointerEventType.Press, at, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
        scene.render(time)
        scene.sendPointerEvent(PointerEventType.Release, at, button = PointerButton.Primary)
        scene.render(time + 16_000_000)
    }

    /** Fills the form the way a person would and presses Connect. [keyBoxY] / [connectY] are where
     * those controls sit once the address box has grown to hold what was typed. */
    private fun fillAndConnect(
        addressInput: String, keyInput: String, keyBoxY: Float, connectY: Float,
    ): Triple<WifiOnlyPhone, LinkController, DesktopStore> {
        val store = DesktopStore()
        val phone = WifiOnlyPhone()
        val storage = LinkStorage(Files.createTempDirectory("a2-form").toFile())
        val link = LinkController(store, storage, phone, CoroutineScope(Job()), Dispatchers.Unconfined)
        val scene = ImageComposeScene(width = width, height = height, density = Density(1f)) {
            A2Theme { A2App(store, link, Page.PHONE) }
        }
        try {
            var time = 0L
            scene.render(time)
            time += 50_000_000; click(scene, 155f, 529f, time) // "Enter address and key instead" (the page shows the QR code first)
            time += 50_000_000; click(scene, 318f, 588f, time) // the address box
            time = type(scene, addressInput, time + 50_000_000)
            time += 50_000_000; click(scene, 318f, keyBoxY, time) // the key box
            time = type(scene, keyInput, time + 50_000_000)
            time += 50_000_000; click(scene, 128f, connectY, time) // Connect and sync
        } finally {
            scene.close()
        }
        return Triple(phone, link, store)
    }

    @Test fun typingTheWifiAddressAndKeyThenClickingConnectPairsThePhone() {
        val (phone, link, store) = fillAndConnect("192.168.0.16:47321", "QWER-TYUP-ASDF-GHJK-ZXCV", keyBoxY = 663f, connectY = 732f)
        assertEquals(listOf("192.168.0.16:47321"), phone.attempts)
        assertEquals("QWER-TYUP-ASDF-GHJK-ZXCV".replace("-", ""), phone.lastKey)
        assertEquals("192.168.0.16:47321", link.pairing?.address)
        assertEquals(DataSource.PHONE, store.source)
        assertEquals("Typed-in phone", store.deviceName)
    }

    @Test fun pastingBothAddressesWithChatLeftoversStillFindsTheOneThatWorks() {
        // Exactly the kind of text copied out of a chat: names, times, an invisible direction mark,
        // the unreachable mobile-data address FIRST — and the key with a prefix.
        val (phone, link, store) = fillAndConnect(
            addressInput = "[12:30 pm, 15/10/2026] Alex: ‎10.113.172.202:47321 ‎192.168.0.16:47321",
            keyInput = "[12:31 pm, 15/10/2026] Alex: ‎QWER-TYUP-ASDF-GHJK-ZXCV",
            keyBoxY = 663f, connectY = 732f, // the card is wide enough now for the long pasted line to fit on one line, so these are as for a short one
        )
        // The address on this PC's own network is tried first, so the unreachable one is usually never needed.
        assertEquals("192.168.0.16:47321", phone.attempts.last(), "the last address tried is the one that answered")
        assertEquals("QWER-TYUP-ASDF-GHJK-ZXCV".replace("-", ""), phone.lastKey)
        assertEquals("192.168.0.16:47321", link.pairing?.address, "pairs with the address that answered")
        assertNotNull(store.deviceName)
        assertEquals(DataSource.PHONE, store.source)
    }
}
