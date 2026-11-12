package com.example.a2desktop

import com.example.a2desktop.data.DesktopStore
import com.example.a2desktop.data.sampleSnapshot
import com.example.a2desktop.link.LinkAddress
import com.example.a2desktop.link.LinkController
import com.example.a2desktop.link.LinkInput
import com.example.a2desktop.link.LinkResult
import com.example.a2desktop.link.LinkStorage
import com.example.a2desktop.link.SnapshotSource
import java.nio.file.Files
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class LinkInputTest {
    private val pc = listOf("192.168.0.15")

    @Test fun findsTheAddressInsideWhateverWasPasted() {
        val cases = mapOf(
            "192.168.0.16:47321" to "192.168.0.16:47321",
            "  192.168.0.16  " to "192.168.0.16:47321", // no port: the default one
            "[12:30 pm, 15/10/2026] Alex: 192.168.0.16:47321" to "192.168.0.16:47321",
            "‎192.168.0.16:47321‏" to "192.168.0.16:47321", // invisible direction marks
            "192.168.0.16：47321" to "192.168.0.16:47321", // full-width colon
            "http://192.168.0.16:47321/" to "192.168.0.16:47321",
        )
        cases.forEach { (input, expected) -> assertEquals(listOf(expected), LinkInput.addresses(input, pc).map { it.toString() }, input) }
    }

    @Test fun keepsEveryAddressTheYourPhoneShowsAndPutsTheOneOnThisNetworkFirst() {
        val both = "10.113.172.202:47321\n192.168.0.16:47321"
        assertEquals(listOf("192.168.0.16:47321", "10.113.172.202:47321"), LinkInput.addresses(both, pc).map { it.toString() })
        // …and with nothing to compare against, the order given is kept.
        assertEquals(listOf("10.113.172.202:47321", "192.168.0.16:47321"), LinkInput.addresses(both, emptyList()).map { it.toString() })
        assertEquals(1, LinkInput.addresses("192.168.0.16:47321 192.168.0.16:47321", pc).size) // no duplicates
    }

    @Test fun ignoresAnythingThatIsNotOnTheLocalNetworkOrNotAnAddress() {
        assertTrue(LinkInput.addresses("8.8.8.8:47321", pc).isEmpty())
        assertTrue(LinkInput.addresses("192.168.0.300", pc).isEmpty())
        assertTrue(LinkInput.addresses("192.168.0.16:0", pc).isEmpty())
        assertTrue(LinkInput.addresses("my phone", pc).isEmpty())
        assertTrue(LinkInput.addresses("", pc).isEmpty())
    }

    @Test fun findsThePairingKeyInsideWhateverWasPasted() {
        val expected = "QWERTYUPASDFGHJKZXCV"
        listOf(
            "QWER-TYUP-ASDF-GHJK-ZXCV",
            "qwer-tyup-asdf-ghjk-zxcv",
            "QWER TYUP ASDF GHJK ZXCV",
            "QWERTYUPASDFGHJKZXCV",
            "[12:31 pm, 15/10/2026] Alex: QWER-TYUP-ASDF-GHJK-ZXCV",
            "‎QWER-TYUP-ASDF-GHJK-ZXCV‎",
            "  QWER–TYUP–ASDF–GHJK–ZXCV  ", // en dashes, as some apps "fix" hyphens
        ).forEach { assertEquals(expected, LinkInput.pairingKey(it), it) }
    }

    @Test fun rejectsAKeyThatIsNotOne() {
        listOf("", "TOO-SHORT", "QWER-TYUP-ASDF-GHJK", "OOOO-0000-1111-IIII-LLLL", "hello there").forEach { assertNull(LinkInput.pairingKey(it), it) }
    }

    // ---- The controller tries every address ----

    private class Phone(private val answers: (String) -> Boolean) : SnapshotSource {
        val tried = mutableListOf<String>()
        override fun fetch(address: LinkAddress, pairingKey: String): LinkResult {
            tried += address.toString()
            if (!answers(address.host)) return LinkResult.Failed("Couldn't reach the phone at $address.")
            val snapshot = sampleSnapshot(LocalDate.now())
            return LinkResult.Ok(snapshot, Json { encodeDefaults = true }.encodeToString(snapshot))
        }
    }

    private fun controller(phone: Phone): Triple<LinkController, DesktopStore, LinkStorage> {
        val storage = LinkStorage(Files.createTempDirectory("a2-input").toFile())
        val store = DesktopStore()
        return Triple(LinkController(store, storage, phone, CoroutineScope(Job()), Dispatchers.Unconfined), store, storage)
    }

    @Test fun theFirstAddressThatAnswersIsTheOneSaved() {
        val phone = Phone { it.startsWith("192.168.") }
        val (link, store, storage) = controller(phone)
        assertNull(link.connect("192.168.0.16:47321 10.113.172.202:47321", "QWER-TYUP-ASDF-GHJK-ZXCV"))
        assertEquals("192.168.0.16:47321", link.pairing?.address)
        assertEquals("192.168.0.16:47321", storage.loadPairing()?.address)
        assertTrue(store.readOnly)
        // Once one answers the others aren't pestered.
        val ordered = LinkInput.addresses("10.113.172.202:47321 192.168.0.16:47321")
        assertFalse(ordered.isEmpty())
    }

    @Test fun whenNoAddressAnswersTheMessageSaysWhichWereTriedAndNothingIsSaved() {
        val phone = Phone { false }
        val (link, _, storage) = controller(phone)
        assertNull(link.connect("192.168.0.16:47321\n10.113.172.202:47321", "QWER-TYUP-ASDF-GHJK-ZXCV"))
        assertEquals(2, phone.tried.size)
        assertNull(link.pairing)
        assertNull(storage.loadPairing())
        val message = assertNotNull(link.error)
        assertTrue("192.168.0.16:47321" in message && "10.113.172.202:47321" in message, message)
    }

    @Test fun anUnreadableAddressOrKeyExplainsWhatItSawAndContactsNobody() {
        val phone = Phone { true }
        val (link, _, _) = controller(phone)
        val addressProblem = assertNotNull(link.connect("my phone", "QWER-TYUP-ASDF-GHJK-ZXCV"))
        assertTrue("my phone" in addressProblem && "192.168." in addressProblem, addressProblem)
        val keyProblem = assertNotNull(link.connect("192.168.0.16", "nope"))
        assertTrue("nope" in keyProblem && "pairing key" in keyProblem, keyProblem)
        assertTrue(phone.tried.isEmpty())
    }
}
