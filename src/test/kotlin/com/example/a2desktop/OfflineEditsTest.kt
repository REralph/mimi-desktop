package com.example.a2desktop

import com.example.a2desktop.data.DataSource
import com.example.a2desktop.data.DesktopStore
import com.example.a2desktop.data.sampleSnapshot
import com.example.a2desktop.link.EditResult
import com.example.a2desktop.link.EditWriter
import com.example.a2desktop.link.LINK_DEFAULT_PORT
import com.example.a2desktop.link.LINK_FEATURE_CARE_STAMPS
import com.example.a2desktop.link.LinkCareCommand
import com.example.a2desktop.link.LinkCareRequest
import com.example.a2desktop.link.LinkAddress
import com.example.a2desktop.link.LinkController
import com.example.a2desktop.link.LinkCrypto
import com.example.a2desktop.link.LinkEditReply
import com.example.a2desktop.link.LinkEvent
import com.example.a2desktop.link.LinkEventCreate
import com.example.a2desktop.link.LinkEventDelete
import com.example.a2desktop.link.LinkEventUpdate
import com.example.a2desktop.link.LinkHabitEdit
import com.example.a2desktop.link.LinkHabitLog
import com.example.a2desktop.link.LinkResult
import com.example.a2desktop.link.LinkSnapshot
import com.example.a2desktop.link.LinkStorage
import com.example.a2desktop.link.LinkTaskCreate
import com.example.a2desktop.link.LinkTaskDelete
import com.example.a2desktop.link.LinkTaskUpdate
import com.example.a2desktop.link.LinkUpcoming
import com.example.a2desktop.link.LocalSubnet
import com.example.a2desktop.link.Outbox
import com.example.a2desktop.link.PhoneFinder
import com.example.a2desktop.link.PingReply
import com.example.a2desktop.link.QrPairing
import com.example.a2desktop.link.SnapshotSource
import com.example.a2desktop.link.StoredPairing
import com.example.a2desktop.logic.CareState
import com.example.a2desktop.logic.careChangedKeys
import com.example.a2desktop.logic.careKeysOf
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

/**
 * Changes made on the PC while the phone cannot be reached: they are never refused, they show on screen at once, and they go to the phone —
 * in order, dated with when they were made — the next time it answers. The phone keeps a later change of its own; what the screen then shows
 * is what the phone holds. Also: finding the phone again when its address changes, and pairing by QR code.
 */
class OfflineEditsTest {
    private val dir: File = Files.createTempDirectory("a2-offline").toFile()
    private val json = Json { encodeDefaults = true }
    private val today = LocalDate.of(2026, 10, 14)
    private val key = "ABCDEFGHJKLMNPQRSTUV"
    private val keyId = LinkCrypto.keyId(LinkCrypto.deriveKeys(key))

    @AfterTest fun cleanUp() {
        dir.deleteRecursively()
    }

    /** A phone that can be switched on and off, keeps its own events and tasks, and applies what it is sent the way the real one does. */
    private class FakePhone(private val today: LocalDate, private val json: Json) : SnapshotSource, EditWriter {
        var online = true
        /** Only this host answers (null = any): the phone has moved to another address. */
        var host: String? = null
        val events = sampleSnapshot(today).events.toMutableList()
        val upcoming = sampleSnapshot(today).upcoming.toMutableList()
        val habitDays = mutableMapOf<Long, MutableSet<Long>>()
        val received = mutableListOf<Pair<String, String>>()

        /** The paths of the changes the phone actually took, in the order it took them. */
        val delivered = mutableListOf<String>()
        val fetchedFrom = mutableListOf<Pair<String, String>>()
        private var nextEventId = 100L

        /** Says no to a request (the reason), or null to take it. */
        var refuse: (String, String) -> String? = { _, _ -> null }

        /** Answers this many edits, then drops off the network (the Wi-Fi went mid-way). */
        var dropAfter: Int? = null

        /** The next request is applied but its answer is lost on the way back. */
        var loseNextReply = false

        /** The skin-care and supplements record this phone keeps (null: an older phone app that keeps none), and when each thing in it last changed. */
        var care: CareState? = null
        val stamps = mutableMapOf<String, Long>()

        /** Whether this phone announces that it understands the moment a waiting care change was made. */
        var stampsCare = true

        override fun fetch(address: LinkAddress, pairingKey: String): LinkResult {
            fetchedFrom += address.toString() to pairingKey
            if (!online || (host != null && address.host != host)) return LinkResult.Failed("Couldn't reach the phone at $address.", unreachable = true)
            val base = sampleSnapshot(today)
            val snapshot = base.copy(
                deviceName = "Test Phone",
                events = events.toList(),
                upcoming = upcoming.toList(),
                habitLogs = habitDays.map { (habit, days) -> LinkHabitLog(habit, days.sorted()) },
                care = care?.toWire(),
                features = if (stampsCare) listOf(LINK_FEATURE_CARE_STAMPS) else emptyList(),
            )
            return LinkResult.Ok(snapshot, json.encodeToString(snapshot))
        }

        override fun send(address: LinkAddress, pairingKey: String, path: String, bodyJson: String): EditResult {
            received += path to bodyJson
            if (!online || (host != null && address.host != host)) return EditResult.Failed("Couldn't reach the phone at $address.", unreachable = true)
            refuse(path, bodyJson)?.let { return EditResult.Failed(it, phoneRefused = true) }
            when (path) {
                "/v1/event/delete" -> {
                    val id = json.decodeFromString<LinkEventDelete>(bodyJson).id
                    events.removeAll { it.id == id }; upcoming.removeAll { it.id == "event-$id" }
                }
                "/v1/event" -> {
                    val r = json.decodeFromString<LinkEventCreate>(bodyJson)
                    val id = nextEventId++
                    events += LinkEvent(id, r.title.trim(), r.dateEpochDay, r.startMinuteOfDay)
                    upcoming += LinkUpcoming("event-$id", r.title.trim(), r.dateEpochDay, "EVENT", r.repeatIntervalDays?.let { "Every $it days" })
                }
                "/v1/event/update" -> {
                    val r = json.decodeFromString<LinkEventUpdate>(bodyJson)
                    val i = events.indexOfFirst { it.id == r.id }
                    if (i >= 0) events[i] = events[i].copy(title = r.title, dateEpochDay = r.dateEpochDay, startMinuteOfDay = r.startMinuteOfDay)
                }
                "/v1/task" -> {
                    val r = json.decodeFromString<LinkTaskCreate>(bodyJson)
                    upcoming += LinkUpcoming("task-${nextEventId++}", r.name.trim(), r.dueEpochDay, "TASK", "One-time")
                }
                "/v1/task/delete" -> upcoming.removeAll { it.id == "task-${json.decodeFromString<LinkTaskDelete>(bodyJson).id}" }
                "/v1/task/update" -> {
                    val r = json.decodeFromString<LinkTaskUpdate>(bodyJson)
                    val i = upcoming.indexOfFirst { it.id == "task-${r.id}" }
                    if (i >= 0) upcoming[i] = upcoming[i].copy(title = r.name, dueEpochDay = r.dueEpochDay)
                }
                "/v1/habit" -> {
                    val r = json.decodeFromString<LinkHabitEdit>(bodyJson)
                    val days = habitDays.getOrPut(r.habitId) { sampleSnapshot(today).habitLogs.firstOrNull { it.habitId == r.habitId }?.days?.toMutableSet() ?: mutableSetOf() }
                    if (r.logged) days += r.dateEpochDay else days -= r.dateEpochDay
                }
            }
            var reply = LinkEditReply(ok = true)
            if (path == "/v1/care") {
                // As the real phone does: a change older than the phone's own change to the same thing is left out; one that is made is stamped with when it was made.
                val request = json.decodeFromString<LinkCareRequest>(bodyJson)
                var state = care ?: CareState()
                for (cmd in request.commands) {
                    if (request.madeAtMillis > 0 && careKeysOf(cmd).any { (stamps[it] ?: 0L) > request.madeAtMillis }) continue
                    val next = state.apply(listOf(cmd))
                    careChangedKeys(state, next).forEach { stamps[it] = maxOf(if (request.madeAtMillis > 0) request.madeAtMillis else System.currentTimeMillis(), stamps[it] ?: 0L) }
                    state = next
                }
                care = state
                reply = LinkEditReply(ok = true, care = state.toWire())
            }
            dropAfter?.let { left -> if (left <= 1) { online = false; dropAfter = null } else dropAfter = left - 1 }
            if (loseNextReply) {
                loseNextReply = false
                return EditResult.Failed("Couldn't reach the phone at $address.", unreachable = true)
            }
            delivered += path
            return EditResult.Done(reply)
        }
    }

    private class Rig(val store: DesktopStore, val link: LinkController, val phone: FakePhone, val outboxFile: File, val storage: LinkStorage)

    private fun rig(
        paired: Boolean = true,
        outboxFile: File = File(dir, "outbox.json"),
        online: Boolean = true,
        finder: PhoneFinder? = null,
        vpn: Boolean = false,
        phone: FakePhone = FakePhone(today, json),
        store: DesktopStore = DesktopStore(today),
    ): Rig {
        val storage = LinkStorage(dir)
        if (paired) storage.savePairing(StoredPairing("192.168.1.23:47321", key))
        phone.online = online
        val link = LinkController(
            store, storage, phone, CoroutineScope(Job()), Dispatchers.Unconfined, editor = phone,
            outbox = Outbox(outboxFile), finder = finder, vpnCheck = { vpn },
        )
        return Rig(store, link, phone, outboxFile, storage)
    }

    private fun Rig.sentPaths() = phone.received.map { it.first }

    // ---- skin care and supplements while the phone is out of reach ----

    private val todayDay get() = today.toEpochDay()
    private val tick = LinkCareCommand("skin.setEvening", todayDay, kind = "CALM", flag = true)
    private val untick = LinkCareCommand("skin.setEvening", todayDay, kind = "CALM", flag = false)

    /** A rig whose phone keeps a care record (started, so ticks are meaningful) and has answered once. */
    private fun careRig(stampsCare: Boolean = true): Rig {
        val r = rig()
        r.phone.care = CareState()
        r.phone.stampsCare = stampsCare
        r.link.syncNow()
        return r
    }

    private fun Rig.goOffline() { phone.online = false; link.syncNow() }
    private fun Rig.careBodies() = phone.received.filter { it.first == "/v1/care" }.map { json.decodeFromString<LinkCareRequest>(it.second) }
    private fun Rig.screenHasEveningTicked() = store.care!!.skin.eveningsDone.contains(todayDay)

    @Test fun aSkinCareTickMadeWhileThePhoneIsOutOfReachShowsAtOnceAndGoesWhenThePhoneIsBack() {
        val r = careRig()
        r.goOffline()
        var outcome: EditResult? = null
        r.link.sendCare(listOf(tick)) { outcome = it }
        assertTrue(outcome is EditResult.Done, "not refused: ${(outcome as? EditResult.Failed)?.message}")
        assertEquals(LinkController.KEPT_MESSAGE, (outcome as EditResult.Done).reply.message)
        assertTrue(r.screenHasEveningTicked(), "ticked on screen at once, on top of the phone's record")
        assertEquals(1, r.link.pendingCount)
        assertEquals(listOf("Skin care change"), r.link.pendingLabels)
        assertTrue(r.careBodies().isEmpty(), "nothing is sent to a phone known to be out of reach")
        assertNull(r.phone.care!!.skin.log.eveningsDone.firstOrNull(), "and the phone knows nothing of it yet")

        r.phone.online = true
        r.link.syncNow()
        assertEquals(1, r.careBodies().size, "sent once the phone answers")
        assertEquals(0, r.link.pendingCount)
        assertTrue(r.phone.care!!.skin.log.doneOn(today), "the phone has the tick")
        assertTrue(r.screenHasEveningTicked(), "and the screen shows what the phone holds")
    }

    @Test fun aSupplementTickIsKeptAndSentToo() {
        val r = careRig()
        r.goOffline()
        r.link.sendCare(listOf(LinkCareCommand("vit.setMark", todayDay, supplement = "CREATINE", flag = true)))
        assertEquals(true, r.store.care!!.vitamins.marks[todayDay]?.get("CREATINE"))
        assertEquals(listOf("Supplements change"), r.link.pendingLabels)
        r.phone.online = true; r.link.syncNow()
        assertEquals(true, r.phone.care!!.vitamins.marks[today]?.get(com.example.a2desktop.logic.Supplement.CREATINE))
    }

    @Test fun theMomentItWasMadeIsSentToAPhoneThatSaysItUnderstandsIt() {
        val r = careRig(stampsCare = true)
        val before = System.currentTimeMillis()
        r.goOffline()
        r.link.sendCare(listOf(tick))
        r.phone.online = true; r.link.syncNow()
        val sent = r.careBodies().single()
        assertTrue(sent.madeAtMillis in before..System.currentTimeMillis(), "dated with when it was made, not when it was delivered (${sent.madeAtMillis})")
    }

    @Test fun anOlderPhoneAppThatDoesNotKnowTheMomentIsNeverSentOne() {
        val r = careRig(stampsCare = false)
        r.goOffline()
        r.link.sendCare(listOf(tick))
        r.phone.online = true; r.link.syncNow()
        val raw = r.phone.received.single { it.first == "/v1/care" }.second
        assertFalse("madeAtMillis" in raw, "the field isn't in the request at all — an older phone refuses a field it doesn't know: $raw")
        assertTrue(r.phone.care!!.skin.log.doneOn(today), "but the change is still delivered")
    }

    @Test fun severalTicksWhileOutOfReachAreAllKeptNoneRefusedAndGoInOrder() {
        val r = careRig()
        r.goOffline()
        val outcomes = mutableListOf<EditResult>()
        val tomorrow = todayDay + 1
        r.link.sendCare(listOf(tick)) { outcomes += it }
        r.link.sendCare(listOf(LinkCareCommand("vit.setMark", todayDay, supplement = "ZINC", flag = true))) { outcomes += it }
        r.link.sendCare(listOf(LinkCareCommand("skin.setEvening", tomorrow, kind = "CALM", flag = true))) { outcomes += it }
        assertEquals(3, outcomes.size)
        assertTrue(outcomes.all { it is EditResult.Done }, "none refused")
        assertEquals(3, r.link.pendingCount)
        r.phone.online = true; r.link.syncNow()
        assertEquals(listOf("skin.setEvening", "vit.setMark", "skin.setEvening"), r.careBodies().map { it.commands.single().op }, "in the order they were made")
        assertEquals(0, r.link.pendingCount)
    }

    @Test fun aTickAndItsUntickMadeWhileOutOfReachAreOneChangeNotTwo() {
        val r = careRig()
        r.goOffline()
        r.link.sendCare(listOf(tick))
        r.link.sendCare(listOf(untick))
        assertEquals(1, r.link.pendingCount, "the later one replaces the earlier")
        assertFalse(r.screenHasEveningTicked(), "and the screen shows it un-ticked")
        r.phone.online = true; r.link.syncNow()
        assertEquals(listOf(false), r.careBodies().map { it.commands.single().flag })
    }

    @Test fun aLaterChangeOnThePhoneBeatsAnOlderWaitingChangeFromThePc() {
        val r = careRig()
        r.goOffline()
        r.link.sendCare(listOf(untick)) // made on the PC, now
        // On the phone the evening was ticked LATER than that (its clock is a minute ahead of the PC's, say).
        r.phone.care = r.phone.care!!.copy(skin = r.phone.care!!.skin.setEvening(today, com.example.a2desktop.logic.EveningKind.CALM, true))
        r.phone.stamps["skin:day:$todayDay"] = System.currentTimeMillis() + 60_000
        r.phone.online = true; r.link.syncNow()
        assertTrue(r.phone.care!!.skin.log.doneOn(today), "the phone's later tick stays")
        assertTrue(r.screenHasEveningTicked(), "and that is what the PC shows once the phone has answered")
        assertEquals(0, r.link.pendingCount, "the waiting change was delivered (and left out by the phone), not kept")
    }

    @Test fun aLaterChangeFromThePcBeatsAnOlderChangeOnThePhone() {
        val r = careRig()
        r.phone.care = r.phone.care!!.copy(skin = r.phone.care!!.skin.setEvening(today, com.example.a2desktop.logic.EveningKind.CALM, true))
        r.phone.stamps["skin:day:$todayDay"] = System.currentTimeMillis() - 60_000 // ticked on the phone a minute ago
        r.link.syncNow()
        r.goOffline()
        r.link.sendCare(listOf(untick)) // the PC un-ticked it since
        r.phone.online = true; r.link.syncNow()
        assertFalse(r.phone.care!!.skin.log.doneOn(today), "the PC's later change is the one kept")
        assertFalse(r.screenHasEveningTicked())
    }

    @Test fun aWaitingCareChangeSurvivesClosingTheApp() {
        val r = careRig()
        r.goOffline()
        r.link.sendCare(listOf(tick))
        // The app is closed and opened again with the phone still out of reach: the change is read back from the file and shown on top of the cached record.
        val reopened = DesktopStore(today)
        val link2 = LinkController(reopened, r.storage, r.phone, CoroutineScope(Job()), Dispatchers.Unconfined, editor = r.phone, outbox = Outbox(r.outboxFile))
        assertEquals(1, link2.pendingCount)
        assertTrue(reopened.care!!.skin.eveningsDone.contains(todayDay), "still ticked on screen")
        r.phone.online = true; link2.syncNow()
        assertTrue(r.phone.care!!.skin.log.doneOn(today), "and it reaches the phone")
    }

    @Test fun whileThePhoneAnswersATickGoesStraightThroughAsBefore() {
        val r = careRig()
        var outcome: EditResult? = null
        r.link.sendCare(listOf(tick)) { outcome = it }
        assertTrue(outcome is EditResult.Done)
        assertNull((outcome as EditResult.Done).reply.message, "not 'kept for later': the phone made it")
        assertEquals(0, r.link.pendingCount)
        assertTrue(r.phone.care!!.skin.log.doneOn(today))
        assertTrue(r.screenHasEveningTicked())
    }

    @Test fun whenThePhoneSaysNoTheTickIsNotKeptAndIsReportedAsBefore() {
        val r = careRig()
        r.phone.refuse = { path, _ -> if (path == "/v1/care") "The phone says no." else null }
        var outcome: EditResult? = null
        r.link.sendCare(listOf(tick)) { outcome = it }
        assertEquals("The phone says no.", (outcome as EditResult.Failed).message)
        assertEquals(0, r.link.pendingCount)
        assertFalse(r.screenHasEveningTicked())
    }

    @Test fun theOneTimeJoiningOfThePcsOwnRecordStillNeedsThePhoneAndIsNotKept() {
        val r = careRig()
        r.goOffline()
        var outcome: EditResult? = null
        r.link.sendCare(emptyList(), com.example.a2desktop.logic.CareState().toWire()) { outcome = it }
        assertTrue(outcome is EditResult.Failed, "joining can't be postponed: it asks the phone to fold a record in")
        assertEquals(0, r.link.pendingCount)
    }

    // ---- deleting while the phone is out of reach ----

    @Test fun anEventDeletedWhileThePhoneIsOutOfReachIsGoneAtOnceAndGoesWhenThePhoneIsBack() {
        val r = rig()
        assertTrue(r.link.reachable)
        r.phone.online = false; r.link.syncNow()
        assertFalse(r.link.reachable)
        assertTrue(r.store.events.any { it.id == 2L }, "the Dentist is on the Calendar")

        var outcome: EditResult? = null
        r.link.deleteEvent(2) { outcome = it }
        assertTrue(outcome is EditResult.Done, "not refused: ${(outcome as? EditResult.Failed)?.message}")
        assertEquals(LinkController.KEPT_MESSAGE, (outcome as EditResult.Done).reply.message)
        assertTrue(r.store.events.none { it.id == 2L }, "gone from the Calendar")
        assertTrue(r.store.upcoming.none { it.id == "event-2" }, "and from Upcoming")
        assertEquals(1, r.link.pendingCount)
        assertEquals(listOf("Delete event “Dentist”"), r.link.pendingLabels)
        assertTrue(r.phone.received.isEmpty(), "nothing is sent to a phone known to be out of reach")

        r.phone.online = true
        r.link.syncNow()
        assertEquals(listOf("/v1/event/delete"), r.sentPaths())
        val sent = json.decodeFromString<LinkEventDelete>(r.phone.received.single().second)
        assertEquals(2L, sent.id)
        assertTrue(sent.madeAtMillis > 0, "dated with when it was made, so the phone can tell which change is later")
        assertEquals(0, r.link.pendingCount)
        assertTrue(r.phone.events.none { it.id == 2L }, "the phone deleted it")
        assertTrue(r.store.events.none { it.id == 2L }, "and the screen shows what the phone holds")
        assertTrue(r.link.reachable)
    }

    @Test fun aTaskDeletedWhileOutOfReachLeavesTheUpcomingListAtOnce() {
        val r = rig()
        r.phone.online = false; r.link.syncNow()
        r.link.deleteTask(3)
        assertTrue(r.store.upcoming.none { it.id == "task-3" })
        assertEquals(1, r.link.pendingCount)
        r.phone.online = true; r.link.syncNow()
        assertEquals(listOf("/v1/task/delete"), r.sentPaths())
        assertTrue(r.phone.upcoming.none { it.id == "task-3" })
    }

    @Test fun aDeleteMadeBeforeTheFirstSyncOfTheDayStillWorksWhenTheSendFindsNoPhone() {
        // No sync has failed yet, so the controller tries the phone first — and keeps the change when that finds nobody there.
        val r = rig()
        r.phone.online = false
        var outcome: EditResult? = null
        r.link.deleteEvent(2) { outcome = it }
        assertTrue(outcome is EditResult.Done)
        assertEquals(1, r.link.pendingCount)
        assertFalse(r.link.reachable)
        assertEquals(1, r.phone.received.size, "it did try")
        assertTrue(r.store.events.none { it.id == 2L })
    }

    // ---- adding and changing while out of reach ----

    @Test fun anEventAddedWhileOutOfReachShowsAtOnceUnderATemporaryNumber() {
        val r = rig()
        r.phone.online = false; r.link.syncNow()
        r.link.addEvent(LinkEventCreate("Optician", today.plusDays(3).toEpochDay(), 14 * 60, null))
        val shown = r.store.events.single { it.title == "Optician" }
        assertTrue(shown.id < 0, "a temporary id below zero")
        assertEquals("14:00", shown.time)
        assertNotNull(r.store.upcoming.firstOrNull { it.title == "Optician" })
        assertEquals(1, r.link.pendingCount)

        r.phone.online = true; r.link.syncNow()
        val sent = json.decodeFromString<LinkEventCreate>(r.phone.received.single().second)
        assertEquals("Optician", sent.title)
        assertNotNull(sent.requestId, "so the same add can never be made twice")
        val now = r.store.events.single { it.title == "Optician" }
        assertTrue(now.id > 0, "the phone's own id replaced the temporary one")
        assertEquals(0, r.link.pendingCount)
    }

    @Test fun somethingAddedAndThenDeletedWhileOutOfReachNeverReachesThePhone() {
        val r = rig()
        r.phone.online = false; r.link.syncNow()
        r.link.addEvent(LinkEventCreate("Oops", today.plusDays(1).toEpochDay(), -1, null))
        val temp = r.store.events.single { it.title == "Oops" }.id
        r.link.deleteEvent(temp)
        assertTrue(r.store.events.none { it.title == "Oops" })
        assertEquals(0, r.link.pendingCount)
        r.phone.online = true; r.link.syncNow()
        assertTrue(r.phone.received.isEmpty(), "nothing to send")
    }

    @Test fun somethingAddedAndThenChangedWhileOutOfReachIsAddedOnceAsChanged() {
        val r = rig()
        r.phone.online = false; r.link.syncNow()
        r.link.addTask(LinkTaskCreate("Water change", today.plusDays(2).toEpochDay(), 1, "NONE"))
        val temp = r.store.upcoming.single { it.title == "Water change" }.id.removePrefix("task-").toLong()
        r.link.updateTask(LinkTaskUpdate(temp, "Water change now", today.plusDays(4).toEpochDay(), 1, "NONE", keepRepeat = true))
        assertEquals(1, r.link.pendingCount)
        assertEquals("Water change now", r.store.upcoming.single { it.id == "task-$temp" }.title)
        r.phone.online = true; r.link.syncNow()
        assertEquals(listOf("/v1/task"), r.sentPaths())
        assertEquals("Water change now", json.decodeFromString<LinkTaskCreate>(r.phone.received.single().second).name)
    }

    @Test fun aChangeToAnEventThePhoneHoldsShowsAtOnceAndGoesLater() {
        val r = rig()
        r.phone.online = false; r.link.syncNow()
        r.link.updateEvent(LinkEventUpdate(2, "Dentist (moved)", today.plusDays(5).toEpochDay(), -1, null, keepRepeat = true))
        assertEquals("Dentist (moved)", r.store.events.single { it.id == 2L }.title)
        assertEquals(today.plusDays(5), r.store.events.single { it.id == 2L }.date)
        r.phone.online = true; r.link.syncNow()
        assertEquals("Dentist (moved)", r.phone.events.single { it.id == 2L }.title)
        assertTrue(json.decodeFromString<LinkEventUpdate>(r.phone.received.single().second).madeAtMillis > 0)
    }

    @Test fun aHabitRecordedWhileOutOfReachShowsAtOnceAndGoesLater() {
        val r = rig()
        val gym = sampleSnapshot(today).habits.first { it.name == "Gym" }
        val day = today.plusDays(1)
        r.phone.online = false; r.link.syncNow()
        r.link.setHabit(day, gym.id, logged = true)
        assertTrue(gym.id in r.store.logs[day].orEmpty())
        assertEquals(listOf("Record Gym"), r.link.pendingLabels)
        r.phone.online = true; r.link.syncNow()
        assertEquals(listOf("/v1/habit"), r.sentPaths())
        assertTrue(day.toEpochDay() in r.phone.habitDays.getValue(gym.id))
        assertTrue(gym.id in r.store.logs[day].orEmpty(), "and still shown, now from the phone")
    }

    // ---- order, refusals, retries ----

    @Test fun waitingChangesGoInTheOrderTheyWereMade() {
        val r = rig()
        r.phone.online = false; r.link.syncNow()
        r.link.deleteEvent(2)
        r.link.addTask(LinkTaskCreate("Mow lawn", today.plusDays(3).toEpochDay()))
        r.link.deleteTask(3)
        r.link.addEvent(LinkEventCreate("Concert", today.plusDays(9).toEpochDay(), 19 * 60, null))
        assertEquals(4, r.link.pendingCount)
        r.phone.online = true; r.link.syncNow()
        assertEquals(listOf("/v1/event/delete", "/v1/task", "/v1/task/delete", "/v1/event"), r.sentPaths())
        assertEquals(0, r.link.pendingCount)
    }

    @Test fun whenThePhoneKeptItsOwnLaterChangeThePhonesVersionWinsAndTheUserIsToldWhy() {
        val r = rig()
        r.phone.online = false; r.link.syncNow()
        r.link.deleteEvent(2)
        r.phone.refuse = { path, _ -> if (path == "/v1/event/delete") "The event was changed on the phone after that change was made, so the phone's version was kept." else null }
        r.phone.online = true; r.link.syncNow()
        assertEquals(0, r.link.pendingCount, "it is not retried for ever")
        assertEquals(1, r.link.notes.size)
        assertTrue(r.link.notes.single().startsWith("Delete event “Dentist”"), r.link.notes.single())
        assertTrue(r.link.notes.single().contains("phone's version was kept"))
        assertTrue(r.store.events.any { it.id == 2L }, "the Dentist is back, as the phone has it")
        r.link.dismissNotes()
        assertTrue(r.link.notes.isEmpty())
    }

    @Test fun ifThePhoneDropsOffTheNetworkHalfWayTheRestWaitInOrder() {
        val r = rig()
        r.phone.online = false; r.link.syncNow()
        r.link.deleteEvent(2)
        r.link.deleteTask(3)
        r.link.deleteEvent(3)
        r.phone.online = true
        r.phone.dropAfter = 1 // takes the first, then the Wi-Fi goes
        r.link.syncNow()
        assertEquals(2, r.link.pendingCount)
        assertEquals(listOf("Delete task “Pay rent”", "Delete event “Team call”"), r.link.pendingLabels)
        r.phone.online = true
        r.link.syncNow()
        assertEquals(0, r.link.pendingCount)
        assertEquals(listOf("/v1/event/delete", "/v1/task/delete", "/v1/event/delete"), r.phone.delivered, "each taken once, in the order they were made")
        assertTrue(r.phone.events.none { it.id == 2L || it.id == 3L })
    }

    @Test fun anAddWhoseAnswerWasLostIsSentAgainWithTheSameRequestNumberSoThePhoneCanRecogniseIt() {
        val r = rig()
        r.phone.online = false; r.link.syncNow()
        r.link.addEvent(LinkEventCreate("Optician", today.plusDays(3).toEpochDay(), -1, null))
        r.phone.online = true
        r.phone.loseNextReply = true
        r.link.syncNow()
        assertEquals(1, r.link.pendingCount, "the answer never came, so it is still waiting")
        r.link.syncNow()
        val ids = r.phone.received.map { json.decodeFromString<LinkEventCreate>(it.second).requestId }
        assertEquals(2, ids.size)
        assertNotNull(ids[0])
        assertEquals(ids[0], ids[1], "the same number both times")
        assertEquals(0, r.link.pendingCount)
    }

    @Test fun whileChangesAreWaitingASnapshotFromThePhoneDoesNotBringBackWhatWasDeleted() {
        val r = rig()
        r.phone.online = false; r.link.syncNow()
        r.link.deleteEvent(2)
        // The phone answers to being asked for its data, but its answers to changes fail (a half-working connection).
        r.phone.online = true
        r.phone.refuse = { _, _ -> null }
        val flaky = object : EditWriter {
            override fun send(address: LinkAddress, pairingKey: String, path: String, bodyJson: String) = EditResult.Failed("Couldn't reach the phone.", unreachable = true)
        }
        val store = r.store
        val link = LinkController(store, r.storage, r.phone, CoroutineScope(Job()), Dispatchers.Unconfined, editor = flaky, outbox = Outbox(r.outboxFile))
        link.syncNow()
        assertTrue(link.reachable)
        assertEquals(1, link.pendingCount)
        assertTrue(store.events.none { it.id == 2L }, "the snapshot has the Dentist, but the deletion still shows")
        assertTrue(r.phone.events.any { it.id == 2L }, "the phone still has it until the change is delivered")
    }

    // ---- surviving a restart ----

    @Test fun changesWaitingWhenTheAppClosesAreStillWaitingAndStillShowWhenItReopens() {
        val first = rig()
        first.phone.online = false; first.link.syncNow()
        first.link.deleteEvent(2)
        assertEquals(1, first.link.pendingCount)

        val reopened = rig(online = false, outboxFile = first.outboxFile) // the same PC, the phone still out of reach
        assertEquals(1, reopened.link.pendingCount)
        assertTrue(reopened.store.events.none { it.id == 2L }, "the cached copy of the phone's data has the Dentist; the waiting delete is still shown")
        reopened.phone.online = true; reopened.link.syncNow()
        assertEquals(listOf("/v1/event/delete"), reopened.sentPaths())
        assertEquals(0, reopened.link.pendingCount)
    }

    @Test fun forgettingThePhoneDropsWhatWasWaitingForIt() {
        val r = rig()
        r.phone.online = false; r.link.syncNow()
        r.link.deleteEvent(2)
        r.link.forget()
        assertEquals(0, r.link.pendingCount)
        assertTrue(Outbox(r.outboxFile).changes.isEmpty())
    }

    // ---- no phone, no pairing ----

    @Test fun withNoPhonePairedThereIsNothingToKeepTheChangeFor() {
        val r = rig(paired = false)
        var outcome: EditResult? = null
        r.link.deleteEvent(2) { outcome = it }
        assertTrue(outcome is EditResult.Failed)
        assertEquals(0, r.link.pendingCount)
    }

    // ---- why it can't be reached ----

    @Test fun aVpnRunningWhileThePhoneCannotBeReachedIsSaid() {
        val r = rig(online = false, vpn = true)
        assertTrue(r.link.vpnHint)
        r.phone.online = true; r.link.syncNow()
        assertFalse(r.link.vpnHint, "gone once the phone answers")
        assertFalse(rig(online = false, vpn = false).link.vpnHint)
    }

    // ---- finding the phone again ----

    private fun finderAt(host: String, id: String?, asked: MutableList<LinkAddress> = mutableListOf()): PhoneFinder = PhoneFinder(
        subnets = { listOf(LocalSubnet("192.168.1.5", 24)) },
        ping = { address -> synchronized(asked) { asked += address }; if (address.host == host && address.port == LINK_DEFAULT_PORT) PingReply(id) else null },
        threads = 8,
    )

    @Test fun whenThePhoneHasAnotherAddressNowTheAppFindsItAndCarriesOn() {
        val phone = FakePhone(today, json).also { it.host = "192.168.1.40" }
        val r = rig(phone = phone, finder = finderAt("192.168.1.40", keyId))
        assertTrue(r.link.reachable, "found it without being asked")
        assertEquals("192.168.1.40:47321", r.link.pairing?.address, "and now remembers where it is")
        assertEquals("192.168.1.40:47321", r.storage.loadPairing()?.address, "also on disk")
        assertEquals(DataSource.PHONE, r.store.source)
        assertEquals(key, r.storage.loadPairing()?.pairingKey)
    }

    @Test fun anotherPhoneWithAnotherKeyIsNeverSentThisPcsKey() {
        val phone = FakePhone(today, json).also { it.host = "192.168.1.40" }
        val r = rig(phone = phone, finder = finderAt("192.168.1.40", "0000000000000000"))
        assertFalse(r.link.reachable)
        assertTrue(phone.fetchedFrom.none { it.first.startsWith("192.168.1.40") }, "no key was sent to it")
        assertEquals("192.168.1.23:47321", r.link.pairing?.address, "the pairing is not moved")
    }

    @Test fun aPhoneAppThatShowsNoFingerprintYetIsStillTriedBecauseTheKeyDecides() {
        val phone = FakePhone(today, json).also { it.host = "192.168.1.40" }
        val r = rig(phone = phone, finder = finderAt("192.168.1.40", null))
        assertTrue(r.link.reachable)
    }

    @Test fun theSearchOnlyRunsWhenThePhoneCouldNotBeReachedNotWhenItSaidNo() {
        val asked = mutableListOf<LinkAddress>()
        val refusing = object : SnapshotSource {
            override fun fetch(address: LinkAddress, pairingKey: String) = LinkResult.Failed("The phone rejected the request — wrong pairing key.")
        }
        val link = LinkController(
            DesktopStore(today), LinkStorage(dir).also { it.savePairing(StoredPairing("192.168.1.23:47321", key)) }, refusing,
            CoroutineScope(Job()), Dispatchers.Unconfined, finder = finderAt("192.168.1.40", keyId, asked),
        )
        assertFalse(link.reachable)
        assertTrue(asked.isEmpty(), "a wrong key is not an address problem, so the network is not searched")
    }

    // ---- pairing by QR code ----

    @Test fun theCodeCarriesAFreshKeyAndThisPcsName() {
        val r = rig(paired = false)
        r.link.startQrPairing("DESKTOP-TEST")
        try {
            val session = assertNotNull(r.link.qrSession)
            val parsed = assertNotNull(QrPairing.parse(session.payload))
            assertEquals(session.key, parsed.key)
            assertEquals("DESKTOP-TEST", parsed.pcName)
            r.link.startQrPairing("DESKTOP-TEST")
            assertTrue(r.link.qrSession!!.key != session.key, "every showing of the code is a new key")
        } finally {
            r.link.stopQrPairing()
        }
        assertNull(r.link.qrSession)
    }

    @Test fun oncePhoneHasScannedTheCodeItIsFoundAndPaired() {
        val phone = FakePhone(today, json).also { it.host = "192.168.1.60" }
        var phoneKeyId: String? = null // what the phone shows once it has the code's key
        val finder = PhoneFinder(
            subnets = { listOf(LocalSubnet("192.168.1.5", 24)) },
            ping = { address -> if (address.host == "192.168.1.60" && address.port == LINK_DEFAULT_PORT) PingReply(phoneKeyId) else null },
            threads = 8,
        )
        val r = rig(paired = false, phone = phone, finder = finder)
        r.link.startQrPairing("DESKTOP-TEST")
        try {
            val session = r.link.qrSession!!
            assertFalse(r.link.tryQrPairing(session), "the phone has not scanned it yet")
            assertTrue(phone.fetchedFrom.isEmpty(), "and nothing was sent to it")
            assertNull(r.link.pairing)

            phoneKeyId = LinkCrypto.keyId(LinkCrypto.deriveKeys(session.key)) // the phone adopts the key
            assertTrue(r.link.tryQrPairing(session))
            assertEquals("192.168.1.60:47321", r.link.pairing?.address)
            assertEquals(session.key, r.link.pairing?.pairingKey)
            assertEquals(DataSource.PHONE, r.store.source)
            assertTrue(r.link.reachable)
            assertNull(r.link.qrSession, "the code goes away once it has done its job")
            assertEquals("192.168.1.60:47321", r.storage.loadPairing()?.address)
        } finally {
            r.link.stopQrPairing()
        }
    }

    @Test fun aPhoneThatShowsADifferentFingerprintIsNotPairedByTheCode() {
        val phone = FakePhone(today, json).also { it.host = "192.168.1.60" }
        val finder = PhoneFinder(
            subnets = { listOf(LocalSubnet("192.168.1.5", 24)) },
            ping = { address -> if (address.host == "192.168.1.60" && address.port == LINK_DEFAULT_PORT) PingReply("ffffffffffffffff") else null },
            threads = 8,
        )
        val r = rig(paired = false, phone = phone, finder = finder)
        r.link.startQrPairing("DESKTOP-TEST")
        try {
            assertFalse(r.link.tryQrPairing(r.link.qrSession!!))
            assertTrue(phone.fetchedFrom.isEmpty(), "somebody else's phone is never sent the key")
            assertNull(r.link.pairing)
        } finally {
            r.link.stopQrPairing()
        }
    }
}
