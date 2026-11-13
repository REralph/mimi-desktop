import com.example.a2desktop.data.DesktopStore
import com.example.a2desktop.link.DreamSaveResult
import com.example.a2desktop.link.EditResult
import com.example.a2desktop.link.LinkAddress
import com.example.a2desktop.link.LinkClient
import com.example.a2desktop.link.LinkController
import com.example.a2desktop.link.LinkDreamEdit
import com.example.a2desktop.link.LinkResult
import com.example.a2desktop.link.LinkStorage
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import com.example.a2.link.DesktopLinkServer as PhoneServer
import com.example.a2.link.LinkCrypto as PhoneCrypto
import com.example.a2.link.LinkDream as PhoneDream
import com.example.a2.link.LinkEditReply as PhoneEditReply
import com.example.a2.link.LinkDreamReply as PhoneDreamReply
import com.example.a2.link.LinkHabit as PhoneHabit
import com.example.a2.link.LinkSnapshot as PhoneSnapshot
import com.example.a2.link.LinkWorkShift as PhoneWorkShift

/**
 * Runs the phone app's REAL server code (compiled in from the phone project — see build.gradle.kts)
 * and points the desktop's client at it over a real socket. This is what proves the two separately
 * held copies of the protocol and crypto still agree.
 */
class InteropTest {
    private val phoneKey = PhoneCrypto.generatePairingKey()
    private val json = Json { encodeDefaults = true }
    private val snapshot = PhoneSnapshot(
        generatedAtMillis = 1_700_000_000_000,
        todayEpochDay = 20_000,
        deviceName = "Interop Phone",
        dateOfBirthEpochDay = 9_000,
        habits = listOf(PhoneHabit(1, "🔥", "Daily streak", "Daily streak", -1, true, null, true, true, 0, listOf("STREAK", "TOTAL"))),
        workShifts = listOf(PhoneWorkShift(20_000, 12 * 60 + 30, 21 * 60), PhoneWorkShift(20_001, 22 * 60, 6 * 60, endsNextDay = true)),
        workCoveredDays = listOf(20_000, 20_001, 20_002),
    )
    private lateinit var scope: CoroutineScope
    private lateinit var server: PhoneServer
    private var port = 0
    private val phoneSaved = mutableListOf<com.example.a2.link.LinkDreamEdit>()
    private val phoneEdits = mutableListOf<Pair<String, String>>()
    private var phoneEditReply = PhoneEditReply(ok = true, dateEpochDay = 20_753, habitIds = listOf(6))

    /** When set, answers an edit request itself (the phone's real care code, for the care test) instead of the canned [phoneEditReply]. */
    private var phoneEditHandler: ((String, String) -> String)? = null
    private var phoneAnswer: (com.example.a2.link.LinkDreamEdit) -> PhoneDreamReply =
        { PhoneDreamReply(ok = true, dream = PhoneDream(it.id, 20_000, it.title, it.description, "SAVED")) }

    @BeforeTest fun start() {
        scope = CoroutineScope(Dispatchers.IO + Job())
        server = PhoneServer(
            pairingKey = { phoneKey },
            snapshotJson = { json.encodeToString(snapshot) },
            saveDream = { edit -> phoneSaved += edit; phoneAnswer(edit) },
            applyEdit = { path, body -> phoneEdits += path to body; phoneEditHandler?.invoke(path, body) ?: json.encodeToString(phoneEditReply) },
            port = 0,
        )
        port = server.start(scope)
    }

    @AfterTest fun stop() {
        server.stop()
        scope.cancel()
    }

    @Test fun theDesktopClientGetsTheSnapshotFromThePhonesRealServer() {
        val result = LinkClient().fetch(LinkAddress("127.0.0.1", port), phoneKey)
        assertTrue(result is LinkResult.Ok, (result as? LinkResult.Failed)?.message)
        val received = (result as LinkResult.Ok).snapshot
        assertEquals("Interop Phone", received.deviceName)
        assertEquals(9_000L, received.dateOfBirthEpochDay)
        assertEquals("Daily streak", received.habits.single().name)
        assertEquals(listOf("STREAK", "TOTAL"), received.habits.single().displayValues)
    }

    @Test fun theWorkTimesTheFourthBetterRotaGivesThePhoneArriveOnTheDesktop() {
        val result = LinkClient().fetch(LinkAddress("127.0.0.1", port), phoneKey)
        assertTrue(result is LinkResult.Ok, (result as? LinkResult.Failed)?.message)
        val received = (result as LinkResult.Ok).snapshot
        assertEquals(listOf(20_000L, 20_001L), received.workShifts.map { it.dateEpochDay })
        assertEquals(750, received.workShifts[0].startMinuteOfDay)
        assertEquals(1260, received.workShifts[0].endMinuteOfDay)
        assertTrue(received.workShifts[1].endsNextDay)
        assertEquals(listOf(20_000L, 20_001L, 20_002L), received.workCoveredDays)
    }

    @Test fun aWrongKeyIsRefusedByThePhone() {
        val result = LinkClient().fetch(LinkAddress("127.0.0.1", port), PhoneCrypto.generatePairingKey())
        assertTrue(result is LinkResult.Failed)
        assertTrue((result as LinkResult.Failed).message.contains("rejected"), result.message)
    }

    @Test fun anAddressWithNothingListeningIsReportedAsUnreachable() {
        val result = LinkClient().fetch(LinkAddress("127.0.0.1", 1), phoneKey)
        assertTrue(result is LinkResult.Failed)
        assertTrue((result as LinkResult.Failed).message.contains("Couldn't reach"), result.message)
    }

    @Test fun aDreamSavedOnTheDesktopReachesThePhonesRealServerAndComesBackAsThePhonesCopy() {
        val edit = LinkDreamEdit(7, "Flying", "I was over a lake.", "Flying over a lake", "I was gliding low.")
        val result = LinkClient().save(LinkAddress("127.0.0.1", port), phoneKey, edit)
        assertTrue(result is DreamSaveResult.Saved, (result as? DreamSaveResult.Failed)?.message)
        assertEquals("Flying over a lake", (result as DreamSaveResult.Saved).dream.title)
        // The phone's server received exactly what the desktop sent, field for field.
        val received = phoneSaved.single()
        assertEquals(7L, received.id)
        assertEquals("Flying", received.baseTitle)
        assertEquals("I was over a lake.", received.baseDescription)
        assertEquals("Flying over a lake", received.title)
        assertEquals("I was gliding low.", received.description)
    }

    @Test fun aHabitAnEventAndATaskSentByTheDesktopReachThePhonesRealServer() {
        val address = LinkAddress("127.0.0.1", port)
        listOf(
            "/v1/habit" to """{"habitId":6,"dateEpochDay":20753,"logged":true}""",
            "/v1/event" to """{"title":"Haircut","dateEpochDay":20753,"startMinuteOfDay":870}""",
            "/v1/task" to """{"name":"Water change","dueEpochDay":20753,"repeatInterval":2,"repeatUnit":"WEEKS"}""",
        ).forEach { (path, body) ->
            val result = LinkClient().send(address, phoneKey, path, body)
            assertTrue(result is EditResult.Done, path + ": " + (result as? EditResult.Failed)?.message)
        }
        assertEquals(listOf("/v1/habit", "/v1/event", "/v1/task"), phoneEdits.map { it.first })
        // The phone's server received exactly the JSON the desktop sent, byte for byte.
        assertEquals("""{"habitId":6,"dateEpochDay":20753,"logged":true}""", phoneEdits[0].second)
        assertEquals(listOf(6L), ((LinkClient().send(address, phoneKey, "/v1/habit", phoneEdits[0].second)) as EditResult.Done).reply.habitIds)
    }

    @Test fun changesAndDeletesSentByTheDesktopReachThePhonesRealServerAndThePhonesOwnClassesReadThem() {
        val address = LinkAddress("127.0.0.1", port)
        val sent = listOf(
            "/v1/event/update" to json.encodeToString(com.example.a2desktop.link.LinkEventUpdate(38, "Haircut", 20_767, 870, 14)),
            "/v1/event/delete" to json.encodeToString(com.example.a2desktop.link.LinkEventDelete(39)),
            "/v1/task/update" to json.encodeToString(com.example.a2desktop.link.LinkTaskUpdate(2, "Water", 20_753, 2, "WEEKS", keepRepeat = true)),
            "/v1/task/delete" to json.encodeToString(com.example.a2desktop.link.LinkTaskDelete(4)),
        )
        sent.forEach { (path, body) ->
            val result = LinkClient().send(address, phoneKey, path, body)
            assertTrue(result is EditResult.Done, path + ": " + (result as? EditResult.Failed)?.message)
        }
        assertEquals(sent, phoneEdits.toList(), "the phone's server received exactly what the desktop sent")
        // And the PHONE's own classes read what the desktop wrote, field for field.
        assertEquals(com.example.a2.link.LinkEventUpdate(38, "Haircut", 20_767, 870, 14, false), json.decodeFromString<com.example.a2.link.LinkEventUpdate>(phoneEdits[0].second))
        assertEquals(com.example.a2.link.LinkEventDelete(39), json.decodeFromString<com.example.a2.link.LinkEventDelete>(phoneEdits[1].second))
        assertEquals(com.example.a2.link.LinkTaskUpdate(2, "Water", 20_753, 2, "WEEKS", true), json.decodeFromString<com.example.a2.link.LinkTaskUpdate>(phoneEdits[2].second))
        assertEquals(com.example.a2.link.LinkTaskDelete(4), json.decodeFromString<com.example.a2.link.LinkTaskDelete>(phoneEdits[3].second))
    }

    @Test fun aCareChangeSentByTheDesktopIsMadeByThePhonesRealCodeAndTheDesktopGetsTheRecordBack() {
        val phoneCare = java.util.concurrent.atomic.AtomicReference(com.example.a2.care.CareState())
        phoneEditHandler = { path, body ->
            assertEquals("/v1/care", path)
            val request = json.decodeFromString<com.example.a2.link.LinkCareRequest>(body)
            request.mergeCare?.let { phoneCare.set(phoneCare.get().mergedWith(com.example.a2.care.CareState.fromWire(it))) }
            phoneCare.set(phoneCare.get().apply(request.commands))
            json.encodeToString(PhoneEditReply(ok = true, care = phoneCare.get().toWire()))
        }
        val address = LinkAddress("127.0.0.1", port)
        val day = 20_763L // Friday 6 November 2026
        // The desktop's own earlier record is folded in, then a tick is made, in two requests, as the app sends them.
        val earlier = com.example.a2desktop.logic.CareState(
            com.example.a2desktop.logic.SkinState(startedOn = java.time.LocalDate.of(2026, 10, 5)).setEvening(java.time.LocalDate.of(2026, 10, 12), com.example.a2desktop.logic.EveningKind.GLYCOLIC, true),
        )
        val merged = LinkClient().send(address, phoneKey, "/v1/care", json.encodeToString(com.example.a2desktop.link.LinkCareRequest(emptyList(), earlier.toWire())))
        assertTrue(merged is EditResult.Done, (merged as? EditResult.Failed)?.message)
        val ticked = LinkClient().send(
            address, phoneKey, "/v1/care",
            json.encodeToString(com.example.a2desktop.link.LinkCareRequest(listOf(com.example.a2desktop.link.LinkCareCommand("vit.setMark", day, supplement = "CREATINE", flag = true)))),
        )
        assertTrue(ticked is EditResult.Done, (ticked as? EditResult.Failed)?.message)
        val care = (ticked as EditResult.Done).reply.care!!
        // What came back is the phone's record: the desktop's earlier history and the tick, read by the DESKTOP's own copy of the code.
        val back = com.example.a2desktop.logic.CareState.fromWire(care)
        assertEquals(java.time.LocalDate.of(2026, 10, 5), back.skin.startedOn)
        assertTrue(back.skin.log.doneOn(java.time.LocalDate.of(2026, 10, 12)))
        assertEquals(true, back.vitamins.marks[java.time.LocalDate.ofEpochDay(day)]?.get(com.example.a2desktop.logic.Supplement.CREATINE))
        assertEquals(listOf("/v1/care", "/v1/care"), phoneEdits.map { it.first })
    }

    // ---- finding the phone, pairing by code, and changes made while it was out of reach ----

    @Test fun thePhonesRealPingShowsAFingerprintOfItsKeyAndTheDesktopRecognisesIt() {
        val reply = com.example.a2desktop.link.PhonePing.ping(LinkAddress("127.0.0.1", port))
        assertNotNull(reply)
        val expected = com.example.a2desktop.link.LinkCrypto.keyId(com.example.a2desktop.link.LinkCrypto.deriveKeys(phoneKey.replace("-", "")))
        assertEquals(expected, reply.keyId)
        assertEquals(PhoneCrypto.keyId(PhoneCrypto.deriveKeys(phoneKey.replace("-", ""))), reply.keyId, "the phone's copy of the crypto agrees")
        assertFalse(
            com.example.a2desktop.link.LinkCrypto.keyId(com.example.a2desktop.link.LinkCrypto.deriveKeys(com.example.a2desktop.link.LinkCrypto.normalizePairingKey(com.example.a2desktop.link.LinkCrypto.generatePairingKey())!!)) == reply.keyId,
            "another key has another fingerprint",
        )
    }

    @Test fun theFinderFindsThePhonesRealServerAndTheControllerMovesItsPairingThere() {
        val dir = Files.createTempDirectory("a2-find").toFile()
        try {
            val storage = LinkStorage(dir).also { it.savePairing(com.example.a2desktop.link.StoredPairing("127.0.0.1:1", phoneKey.replace("-", ""))) } // where it used to be: nothing there now
            val finder = com.example.a2desktop.link.PhoneFinder(subnets = { emptyList() }, ports = port..port)
            val store = DesktopStore()
            val link = LinkController(store, storage, LinkClient(), CoroutineScope(Job()), Dispatchers.Unconfined, finder = finder)
            assertTrue(link.reachable, link.error)
            assertEquals("127.0.0.1:$port", link.pairing?.address)
            assertEquals("127.0.0.1:$port", storage.loadPairing()?.address)
            assertEquals("Interop Phone", store.deviceName)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun aDeleteMadeWhileThePhoneWasOutOfReachArrivesAtThePhonesRealServerWhenItIsBack() {
        val dir = Files.createTempDirectory("a2-queue").toFile()
        try {
            val storage = LinkStorage(dir).also { it.savePairing(com.example.a2desktop.link.StoredPairing("127.0.0.1:$port", phoneKey.replace("-", ""))) }
            val link = LinkController(
                DesktopStore(), storage, LinkClient(), CoroutineScope(Job()), Dispatchers.Unconfined, editor = LinkClient(),
                outbox = com.example.a2desktop.link.Outbox(File(dir, "outbox.json")),
            )
            assertTrue(link.reachable, link.error)

            server.stop() // the phone goes off the Wi-Fi
            link.syncNow()
            assertFalse(link.reachable)
            var outcome: EditResult? = null
            link.deleteEvent(39) { outcome = it }
            assertTrue(outcome is EditResult.Done, "not refused")
            assertEquals(1, link.pendingCount)
            assertTrue(phoneEdits.isEmpty())

            server = PhoneServer(
                pairingKey = { phoneKey }, snapshotJson = { json.encodeToString(snapshot) },
                applyEdit = { path, body -> phoneEdits += path to body; json.encodeToString(PhoneEditReply(ok = true)) },
                port = port,
            ).also { it.start(scope) } // and comes back, on the same address
            link.syncNow()
            assertTrue(link.reachable, link.error)
            assertEquals(0, link.pendingCount)
            assertEquals(listOf("/v1/event/delete"), phoneEdits.map { it.first })
            val read = json.decodeFromString<com.example.a2.link.LinkEventDelete>(phoneEdits.single().second)
            assertEquals(39L, read.id)
            assertTrue(read.madeAtMillis > 0, "dated, so the phone can compare it with its own later changes")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun anAddSentTwiceCarriesTheSameRequestNumberTheWayThePhoneReadsIt() {
        val request = com.example.a2desktop.link.LinkEventCreate("Optician", 20_760, 600, null, "req-1")
        val body = json.encodeToString(request)
        repeat(2) { assertTrue(LinkLinkClientSend(body) is EditResult.Done) }
        val read = phoneEdits.map { json.decodeFromString<com.example.a2.link.LinkEventCreate>(it.second) }
        assertEquals(listOf("req-1", "req-1"), read.map { it.requestId })
    }

    private fun LinkLinkClientSend(body: String) = LinkClient().send(LinkAddress("127.0.0.1", port), phoneKey, "/v1/event", body)

    @Test fun anEveningChosenOnTheDesktopIsMadeByThePhonesRealCodeAndComesBackToTheDesktop() {
        val phoneCare = java.util.concurrent.atomic.AtomicReference(com.example.a2.care.CareState())
        phoneEditHandler = { _, body ->
            val request = json.decodeFromString<com.example.a2.link.LinkCareRequest>(body)
            phoneCare.set(phoneCare.get().apply(request.commands))
            json.encodeToString(PhoneEditReply(ok = true, care = phoneCare.get().toWire()))
        }
        val day = java.time.LocalDate.of(2026, 11, 9)
        val result = LinkClient().send(
            LinkAddress("127.0.0.1", port), phoneKey, "/v1/care",
            json.encodeToString(com.example.a2desktop.link.LinkCareRequest(listOf(com.example.a2desktop.link.LinkCareCommand("skin.setChosen", day.toEpochDay(), kind = "BRIGHTEN")))),
        )
        assertTrue(result is EditResult.Done, (result as? EditResult.Failed)?.message)
        assertEquals(com.example.a2.care.EveningKind.BRIGHTEN, phoneCare.get().skin.log.chosenKinds[day], "the phone's own code made the choice")
        val back = com.example.a2desktop.logic.CareState.fromWire((result as EditResult.Done).reply.care!!)
        assertEquals(com.example.a2desktop.logic.EveningKind.BRIGHTEN, back.skin.log.chosenKinds[day], "and the desktop reads it back")
    }

    @Test fun whenThePhoneRefusesAnEditTheDesktopGetsItsReasonAndNothingIsApplied() {
        phoneEditReply = PhoneEditReply(ok = false, message = "The streak habit is worked out automatically.")
        val result = LinkClient().send(LinkAddress("127.0.0.1", port), phoneKey, "/v1/habit", """{"habitId":2,"dateEpochDay":20753,"logged":true}""")
        assertTrue(result is EditResult.Failed)
        result as EditResult.Failed
        assertTrue(result.phoneRefused)
        assertEquals("The streak habit is worked out automatically.", result.message)
    }

    @Test fun anEditWithTheWrongKeyNeverReachesThePhone() {
        val result = LinkClient().send(LinkAddress("127.0.0.1", port), PhoneCrypto.generatePairingKey(), "/v1/event", """{"title":"x","dateEpochDay":20753}""")
        assertTrue(result is EditResult.Failed)
        assertTrue((result as EditResult.Failed).message.contains("rejected"), result.message)
        assertTrue(phoneEdits.isEmpty())
    }

    @Test fun whenThePhoneCannotBeReachedAnEditSaysSoAndChangesNothing() {
        val result = LinkClient().send(LinkAddress("127.0.0.1", 1), phoneKey, "/v1/task", """{"name":"x","dueEpochDay":20753}""")
        assertTrue(result is EditResult.Failed)
        assertTrue((result as EditResult.Failed).message.contains("not made"), result.message)
        assertFalse(result.phoneRefused)
    }

    @Test fun whenThePhoneRefusesADreamTheDesktopGetsItsReason() {
        phoneAnswer = { PhoneDreamReply(ok = false, message = "This dream was changed on the phone since the desktop last synced.") }
        val result = LinkClient().save(LinkAddress("127.0.0.1", port), phoneKey, LinkDreamEdit(7, "a", "b", "c", "d"))
        assertTrue(result is DreamSaveResult.Failed)
        result as DreamSaveResult.Failed
        assertTrue(result.phoneRefused)
        assertEquals("This dream was changed on the phone since the desktop last synced.", result.message)
    }

    @Test fun aDreamSavedWithTheWrongKeyNeverReachesThePhone() {
        val result = LinkClient().save(LinkAddress("127.0.0.1", port), PhoneCrypto.generatePairingKey(), LinkDreamEdit(7, "a", "b", "c", "d"))
        assertTrue(result is DreamSaveResult.Failed)
        assertTrue((result as DreamSaveResult.Failed).message.contains("rejected"), result.message)
        assertFalse(result.phoneRefused)
        assertTrue(phoneSaved.isEmpty())
    }

    @Test fun aDreamSavedWhileThePhoneIsNotListeningIsReportedAndNotLost() {
        val result = LinkClient().save(LinkAddress("127.0.0.1", 1), phoneKey, LinkDreamEdit(7, "a", "b", "c", "d"))
        assertTrue(result is DreamSaveResult.Failed)
        assertTrue((result as DreamSaveResult.Failed).message.contains("not saved"), result.message)
    }

    @Test fun theWholeDesktopFlowWorksAgainstThePhonesRealServer() {
        val dir = Files.createTempDirectory("a2-interop").toFile()
        try {
            val store = DesktopStore()
            val link = LinkController(store, LinkStorage(dir), LinkClient(), CoroutineScope(Job()), Dispatchers.Unconfined)
            assertEquals(null, link.connect("127.0.0.1:$port", phoneKey))
            assertTrue(link.reachable, link.error)
            assertTrue(store.readOnly)
            assertEquals("Interop Phone", store.deviceName)
            assertEquals(listOf("Daily streak"), store.habits.map { it.name })
            assertNotNull(store.dateOfBirth)
            val firstDay = java.time.LocalDate.ofEpochDay(20_000)
            assertEquals(java.time.LocalTime.of(12, 30), store.workSchedule.shiftOn(firstDay)?.start)
            assertTrue(store.workSchedule.isOff(java.time.LocalDate.ofEpochDay(20_002)))
            // A restart: a new controller finds the saved pairing and cached data, then re-syncs.
            val restarted = DesktopStore()
            val link2 = LinkController(restarted, LinkStorage(dir), LinkClient(), CoroutineScope(Job()), Dispatchers.Unconfined)
            assertNotNull(link2.pairing)
            assertTrue(link2.reachable, link2.error)
            // And with the phone's page closed (server stopped) the cached data stays, flagged unreachable.
            server.stop()
            link2.syncNow()
            assertFalse(link2.reachable)
            assertTrue(restarted.readOnly)
            assertEquals(listOf("Daily streak"), restarted.habits.map { it.name })
            assertEquals(java.time.LocalTime.of(12, 30), restarted.workSchedule.shiftOn(firstDay)?.start, "cached work times survive too")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun theProtocolFilesInBothProjectsDeclareTheSameThings() {
        fun code(path: String) = File(path).readLines().filter { it.isNotBlank() && !it.trimStart().startsWith("//") && !it.startsWith("package ") }
        listOf("LinkProtocol.kt", "LinkCrypto.kt", "QrPairing.kt").forEach { name ->
            assertEquals(
                code("build/phone-link/com/example/a2/link/$name"),
                code("src/main/kotlin/com/example/a2desktop/link/$name"),
                "$name has drifted between the phone and desktop projects",
            )
        }
        // The skin-care and supplements code is copied between the projects too: the same planners and record on both, with only the
        // package names different.
        fun same(line: String) = line.replace("com.example.a2desktop.logic", "com.example.a2.care").replace("com.example.a2desktop.link", "com.example.a2.link")
        listOf("SkinCare.kt", "Supplements.kt", "WorkSchedule.kt", "CareState.kt").forEach { name ->
            assertEquals(
                code("build/phone-link/com/example/a2/care/$name"),
                code("src/main/kotlin/com/example/a2desktop/logic/$name").map(::same),
                "$name has drifted between the phone and desktop projects",
            )
        }
        assertEquals(
            code("build/phone-link/com/example/a2/ui/dreams/DreamClipboardLogic.kt"),
            code("src/main/kotlin/com/example/a2desktop/logic/DreamClipboardLogic.kt"),
            "DreamClipboardLogic.kt has drifted between the phone and desktop projects",
        )
    }
}
