package com.example.a2desktop

import com.example.a2desktop.data.CareSender
import com.example.a2desktop.data.VitaminStore
import com.example.a2desktop.link.EditResult
import com.example.a2desktop.link.LinkCareCommand
import com.example.a2desktop.link.LinkEditReply
import com.example.a2desktop.logic.Supplement
import com.example.a2desktop.logic.VitaminState
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A tick on a supplement the phone's app doesn't know yet (one added on this PC first) is thrown away by that phone, so its answer never
 * holds it. The PC keeps such a tick itself, shows it, offers it to the phone again later, and lets go of it once the phone has it.
 */
class HeldTicksTest {
    private val dir: File = Files.createTempDirectory("a2-held").toFile()
    private val file = File(dir, "vitamins.json")
    private val day = LocalDate.of(2026, 11, 9)
    private val calm = Supplement.L_THEANINE_LEMON_BALM

    @AfterTest fun cleanUp() {
        dir.deleteRecursively()
    }

    /** A phone: holds a record, applies what it is sent, answers with the record — like the app's own link does. [knowsCalm] false is the older app. */
    private inner class FakePhone(var knowsCalm: Boolean) {
        var record = VitaminState().ensureStarted(day.minusDays(30))
        var requests = 0
        var unreachable = false
        lateinit var store: VitaminStore

        val sender = CareSender { commands, onDone ->
            requests++
            if (unreachable) {
                onDone(EditResult.Failed("The phone can't be reached.", unreachable = true))
            } else {
                val usable = if (knowsCalm) commands else commands.filterNot { it.supplement == calm.name }
                record = usable.fold(record) { state, command -> state.apply(command) }
                val answer = record.toWire()
                onDone(EditResult.Done(LinkEditReply(ok = true, care = com.example.a2desktop.link.LinkCare(vitamins = answer))))
                store.applyRemote(answer) // what the app does when the phone's record arrives
            }
        }
    }

    private fun storeWith(phone: FakePhone, clock: () -> Long = { 0L }): VitaminStore =
        VitaminStore(file, clock).also {
            phone.store = it
            it.sender = phone.sender
            it.applyRemote(phone.record.toWire())
        }

    @Test fun aTickTheOldPhoneDropsIsKeptHereAndShown() {
        val phone = FakePhone(knowsCalm = false)
        val store = storeWith(phone)
        store.setMark(day, calm, true)
        assertEquals(true, store.markOn(day, calm), "shown")
        assertEquals(mapOf(day to mapOf(calm to true)), store.held, "kept here")
        assertNull(phone.record.marks[day]?.get(calm), "the old phone doesn't hold it")
        assertEquals(true, store.marksOn(day)[calm])
    }

    @Test fun tappingItAgainTakesItOffAndNothingIsLeftHeld() {
        val phone = FakePhone(knowsCalm = false)
        val store = storeWith(phone)
        store.setMark(day, calm, true)
        store.setMark(day, calm, null)
        assertNull(store.markOn(day, calm))
        assertTrue(store.held.isEmpty())
    }

    @Test fun theTickSurvivesTheAppBeingClosedAndOpenedAgain() {
        val phone = FakePhone(knowsCalm = false)
        storeWith(phone).setMark(day, calm, true)
        val again = VitaminStore(file) // a fresh start: nothing but the file
        assertEquals(true, again.markOn(day, calm), "read back from the file")
        assertEquals(mapOf(day to mapOf(calm to true)), again.held)
    }

    @Test fun theOtherSupplementsStayTheCopyOfThePhonesAndAreNeverHeld() {
        val phone = FakePhone(knowsCalm = false)
        val store = storeWith(phone)
        store.setMark(day, Supplement.CREATINE, true)
        assertEquals(true, store.markOn(day, Supplement.CREATINE))
        assertTrue(store.held.isEmpty(), "the phone holds a tick it knows: nothing is kept here")
        assertEquals(true, phone.record.marks[day]?.get(Supplement.CREATINE))
    }

    @Test fun aPhoneThatKnowsTheSupplementJustHoldsItAndNothingIsKeptHere() {
        val phone = FakePhone(knowsCalm = true)
        val store = storeWith(phone)
        store.setMark(day, calm, true)
        assertEquals(true, store.markOn(day, calm))
        assertTrue(store.held.isEmpty(), "the phone's answer holds it")
        assertEquals(true, phone.record.marks[day]?.get(calm))
        store.setMark(day, calm, null)
        assertNull(store.markOn(day, calm), "taken off on the phone, so off here")
    }

    @Test fun onceThePhoneIsUpdatedTheHeldTicksAreOfferedAndLetGo() {
        var now = 1_000_000L
        val phone = FakePhone(knowsCalm = false)
        val store = storeWith(phone, clock = { now })
        store.setMark(day, calm, true)
        store.setMark(day.minusDays(1), calm, true)
        assertEquals(2, store.held.values.sumOf { it.size })
        phone.knowsCalm = true // the phone's app is updated
        now += VitaminStore.RESEND_EVERY_MILLIS + 1
        store.applyRemote(phone.record.toWire()) // the next time the phone's record comes round
        assertEquals(true, phone.record.marks[day]?.get(calm), "the phone has been given the tick")
        assertEquals(true, phone.record.marks[day.minusDays(1)]?.get(calm), "and the earlier one")
        assertTrue(store.held.isEmpty(), "so nothing is held here any more")
        assertEquals(true, store.markOn(day, calm), "and it is still shown, now the phone's")
    }

    @Test fun theOldPhoneIsOfferedTheTicksAtMostOnceEveryHalfHour() {
        var now = 1_000_000L
        val phone = FakePhone(knowsCalm = false)
        val store = storeWith(phone, clock = { now })
        store.setMark(day, calm, true)
        val after = phone.requests // the tick itself was the offer
        repeat(5) { now += 60_000; store.applyRemote(phone.record.toWire()) } // five more minutes of the phone's record coming round
        assertEquals(after, phone.requests, "not offered again straight away")
        now += VitaminStore.RESEND_EVERY_MILLIS
        store.applyRemote(phone.record.toWire())
        assertEquals(after + 1, phone.requests, "offered again once half an hour has gone by")
        repeat(5) { now += 60_000; store.applyRemote(phone.record.toWire()) }
        assertEquals(after + 1, phone.requests, "and then left alone for the next half hour")
        assertEquals(true, store.markOn(day, calm), "all the while it stays ticked")
    }

    @Test fun heldTicksAreOfferedToThePhoneAsTheAppStarts() {
        val phone = FakePhone(knowsCalm = false)
        storeWith(phone).setMark(day, calm, true) // ticked in an earlier run, while the phone didn't know it
        phone.knowsCalm = true // the phone's app was updated since
        val phone2Store = VitaminStore(file) // the app starts again
        phone.store = phone2Store
        phone2Store.sender = phone.sender
        phone2Store.applyRemote(phone.record.toWire()) // the first record from the phone
        assertEquals(true, phone.record.marks[day]?.get(calm), "given to the phone at once")
        assertTrue(phone2Store.held.isEmpty())
    }

    @Test fun whenThePhoneSaysSomethingElseAboutItThePhoneWins() {
        val phone = FakePhone(knowsCalm = false)
        val store = storeWith(phone)
        store.setMark(day, calm, true)
        phone.knowsCalm = true
        phone.record = phone.record.setMark(day, calm, false) // on the updated phone it was said no to
        store.applyRemote(phone.record.toWire())
        assertEquals(false, store.markOn(day, calm), "the phone's no")
        assertTrue(store.held.isEmpty())
    }

    @Test fun aTickThePhoneCouldNotBeToldAboutIsNotMadeAndNothingIsHeld() {
        val phone = FakePhone(knowsCalm = false)
        val store = storeWith(phone)
        phone.unreachable = true
        store.setMark(day, calm, true)
        assertNull(store.markOn(day, calm), "not made: the phone is the record")
        assertTrue(store.held.isEmpty())
        assertTrue(store.problem != null, "and it says why")
    }

    @Test fun withNoPhoneRecordATickIsMadeHereAsBeforeAndHoldsNothing() {
        val store = VitaminStore(file)
        store.setMark(day, calm, true)
        assertEquals(true, store.markOn(day, calm))
        assertTrue(store.held.isEmpty(), "there is no phone to be held back from")
        store.setMark(day, calm, null)
        assertFalse(store.isTouched(day))
    }
}
