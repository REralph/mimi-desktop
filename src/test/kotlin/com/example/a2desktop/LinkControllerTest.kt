package com.example.a2desktop

import com.example.a2desktop.data.DataSource
import com.example.a2desktop.data.DesktopStore
import com.example.a2desktop.data.sampleSnapshot
import com.example.a2desktop.link.LinkAddress
import com.example.a2desktop.link.LinkEvent
import com.example.a2desktop.link.LinkController
import com.example.a2desktop.link.LinkResult
import com.example.a2desktop.link.LinkStorage
import com.example.a2desktop.link.SnapshotSource
import com.example.a2desktop.link.StoredPairing
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import kotlin.test.AfterTest
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

class LinkControllerTest {
    private val dir: File = Files.createTempDirectory("a2-link-test").toFile()
    private val json = Json { encodeDefaults = true }
    private val today = LocalDate.of(2026, 10, 14)
    private val goodKey = "ABCD-EFGH-JKLM-NPQR-STUV"

    @AfterTest fun cleanUp() {
        dir.deleteRecursively()
    }

    private class FakeSource(var result: () -> LinkResult) : SnapshotSource {
        var calls = 0
        var lastKey: String? = null
        override fun fetch(address: LinkAddress, pairingKey: String): LinkResult {
            calls++
            lastKey = pairingKey
            return result()
        }
    }

    private fun phoneData(device: String = "Test Phone") = sampleSnapshot(today).copy(
        deviceName = device,
        habits = sampleSnapshot(today).habits.take(2),
        dateOfBirthEpochDay = 10_000,
    ).let { LinkResult.Ok(it, json.encodeToString(it)) }

    private fun controller(store: DesktopStore, source: SnapshotSource) =
        LinkController(store, LinkStorage(dir), source, CoroutineScope(Job()), Dispatchers.Unconfined)

    @Test fun startsUnpairedShowingNoDataAtAll() {
        val store = DesktopStore(today)
        val link = controller(store, FakeSource { phoneData() })
        assertNull(link.pairing)
        assertEquals(DataSource.NONE, store.source)
        assertTrue(store.habits.isEmpty())
        assertFalse(link.reachable)
    }

    @Test fun aSuccessfulFirstConnectionPairsSavesAndTurnsTheStoreReadOnly() {
        val store = DesktopStore(today)
        val source = FakeSource { phoneData() }
        val link = controller(store, source)
        assertNull(link.connect("192.168.1.23:47321", goodKey))
        assertEquals("192.168.1.23:47321", link.pairing?.address)
        assertEquals("ABCDEFGHJKLMNPQRSTUV", source.lastKey)
        assertTrue(link.reachable)
        assertTrue(store.readOnly)
        assertEquals("Test Phone", store.deviceName)
        assertEquals(2, store.habits.size)
        assertNotNull(store.dateOfBirth)
        assertNotNull(LinkStorage(dir).loadPairing())
        assertNotNull(LinkStorage(dir).loadSnapshotJson())
    }

    @Test fun aFailedFirstConnectionIsNotRemembered() {
        val store = DesktopStore(today)
        val link = controller(store, FakeSource { LinkResult.Failed("nope") })
        assertNull(link.connect("192.168.1.23", goodKey))
        assertNull(link.pairing)
        assertNull(LinkStorage(dir).loadPairing())
        assertEquals("nope", link.error)
        assertFalse(store.readOnly)
    }

    @Test fun aBadAddressOrKeyIsReportedWithoutContactingAnything() {
        val source = FakeSource { phoneData() }
        val link = controller(DesktopStore(today), source)
        assertNotNull(link.connect("example.com", goodKey))
        assertNotNull(link.connect("192.168.1.23", "SHORT"))
        assertEquals(0, source.calls)
    }

    @Test fun aFailedReconnectDoesNotReplaceAnExistingPairing() {
        val store = DesktopStore(today)
        var ok = true
        val link = controller(store, FakeSource { if (ok) phoneData() else LinkResult.Failed("down") })
        link.connect("192.168.1.23", goodKey)
        ok = false
        link.connect("192.168.1.99", "WXYZ-2345-6789-ABCD-EFGH")
        assertEquals("192.168.1.23:47321", link.pairing?.address)
        assertEquals("192.168.1.23:47321", LinkStorage(dir).loadPairing()?.address)
    }

    @Test fun anUnreachablePhoneKeepsTheLastDataOnScreen() {
        val store = DesktopStore(today)
        var ok = true
        val link = controller(store, FakeSource { if (ok) phoneData() else LinkResult.Failed("Couldn't reach the phone") })
        link.connect("192.168.1.23", goodKey)
        ok = false
        link.syncNow()
        assertFalse(link.reachable)
        assertEquals("Couldn't reach the phone", link.error)
        assertTrue(store.readOnly)
        assertEquals(2, store.habits.size) // still the phone's last data
    }

    @Test fun reopeningTheAppShowsTheCachedDataEvenIfThePhoneIsOff() {
        val first = controller(DesktopStore(today), FakeSource { phoneData("Cached Phone") })
        first.connect("192.168.1.23", goodKey)

        val store = DesktopStore(today)
        val link = controller(store, FakeSource { LinkResult.Failed("phone is off") })
        assertNotNull(link.pairing)
        assertTrue(store.readOnly)
        assertEquals("Cached Phone", store.deviceName)
        assertFalse(link.reachable)
    }

    @Test fun forgettingDeletesEverythingAndLeavesNoDataNotFakeData() {
        val store = DesktopStore(today)
        val link = controller(store, FakeSource { phoneData() })
        link.connect("192.168.1.23", goodKey)
        link.forget()
        assertNull(link.pairing)
        assertFalse(store.readOnly)
        assertNull(LinkStorage(dir).loadPairing())
        assertNull(LinkStorage(dir).loadSnapshotJson())
        assertEquals(DataSource.NONE, store.source)
        assertTrue(store.habits.isEmpty()) // nothing made up takes its place
    }

    @Test fun phoneDataCannotBeEditedOnTheDesktop() {
        val store = DesktopStore(today)
        controller(store, FakeSource { phoneData() }).connect("192.168.1.23", goodKey)
        val habit = store.habits.first { !it.isStreak }
        val day = today.minusDays(3)
        val before = store.logs[day]
        store.toggleHabit(day, habit.id)
        assertEquals(before, store.logs[day])
        assertNull(store.newDream())
        val dreamsBefore = store.dreams.toList()
        store.saveDream(dreamsBefore.first().copy(title = "changed"))
        assertEquals(dreamsBefore, store.dreams.toList())
    }

    // ---- Things that must never take the app down ----

    private fun badData(): LinkResult.Ok {
        // Decodes fine, but a date no calendar can hold — applying it throws.
        val snapshot = sampleSnapshot(today).copy(events = listOf(LinkEvent(1, "x", Long.MAX_VALUE, 0)))
        return LinkResult.Ok(snapshot, json.encodeToString(snapshot))
    }

    @Test fun aCorruptCacheDoesNotStopTheAppOpening() {
        LinkStorage(dir).savePairing(StoredPairing("192.168.1.23:47321", "ABCDEFGHJKLMNPQRSTUV"))
        File(dir, "snapshot.json").writeText("{ this is not json")
        val store = DesktopStore(today)
        controller(store, FakeSource { LinkResult.Failed("phone is off") })
        assertEquals(DataSource.NONE, store.source) // no data — and no crash
        assertNull(LinkStorage(dir).loadSnapshotJson()) // the bad cache was thrown away
    }

    @Test fun aCacheThatDecodesButCannotBeAppliedIsDiscardedToo() {
        LinkStorage(dir).saveSnapshotJson((badData()).rawJson)
        val store = DesktopStore(today)
        controller(store, FakeSource { LinkResult.Failed("phone is off") })
        assertFalse(store.readOnly)
        assertNull(LinkStorage(dir).loadSnapshotJson())
    }

    @Test fun dataThatCannotBeAppliedIsReportedNotPairedAndSyncIsNotLeftStuck() {
        val store = DesktopStore(today)
        val link = controller(store, FakeSource { badData() })
        link.connect("192.168.1.23", goodKey)
        assertFalse(link.syncing, "Sync now must stay usable")
        assertFalse(link.reachable)
        assertNotNull(link.error)
        assertNull(link.pairing) // never remembered
        assertNull(LinkStorage(dir).loadPairing())
        assertFalse(store.readOnly)
    }

    @Test fun aSourceThatThrowsIsReportedNotFatal() {
        val link = controller(DesktopStore(today), FakeSource { throw IllegalStateException("boom") })
        link.connect("192.168.1.23", goodKey)
        assertFalse(link.syncing)
        assertNotNull(link.error)
        link.syncNow() // and it can be tried again
        assertFalse(link.syncing)
    }

    @Test fun theAutomaticRefreshComesBackByItselfWhenThePhoneStartsAnsweringAgain() {
        val store = DesktopStore(today)
        var up = true
        val link = controller(store, FakeSource { if (up) phoneData() else LinkResult.Failed("down") })
        link.connect("192.168.1.23", goodKey)
        assertTrue(link.reachable)

        up = false
        link.refreshQuietly()
        assertFalse(link.reachable)
        assertEquals("down", link.error)
        assertEquals(2, store.habits.size, "the last data stays on screen while the phone is away")

        up = true
        link.refreshQuietly()
        assertTrue(link.reachable)
        assertNull(link.error)
    }

    @Test fun theAutomaticRefreshDoesNotFlickerTheSyncingIndicatorButAButtonPressDoes() {
        val store = DesktopStore(today)
        lateinit var link: LinkController
        val seen = mutableListOf<Boolean>()
        link = controller(store, FakeSource { seen += link.syncing; phoneData() })
        link.connect("192.168.1.23", goodKey) // a button press: shows "Syncing…"
        link.refreshQuietly()                  // the automatic one: stays quiet
        link.syncNow()
        assertEquals(listOf(true, false, true), seen.takeLast(3))
        assertFalse(link.syncing)
    }
}
