package com.example.a2desktop

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import com.example.a2desktop.data.CalendarEmojiStore
import com.example.a2desktop.data.CareSync
import com.example.a2desktop.data.DesktopStore
import com.example.a2desktop.data.SkinCareStore
import com.example.a2desktop.data.VitaminStore
import com.example.a2desktop.data.sampleSnapshot
import com.example.a2desktop.link.EditResult
import com.example.a2desktop.link.EditWriter
import com.example.a2desktop.link.LinkAddress
import com.example.a2desktop.link.LinkController
import com.example.a2desktop.link.LinkEditReply
import com.example.a2desktop.link.LinkCareRequest
import com.example.a2desktop.link.LinkEventCreate
import com.example.a2desktop.link.LinkEventDelete
import com.example.a2desktop.link.LinkEventUpdate
import com.example.a2desktop.link.LinkHabitEdit
import com.example.a2desktop.link.LinkResult
import com.example.a2desktop.link.LinkStorage
import com.example.a2desktop.link.LinkTaskCreate
import com.example.a2desktop.model.UpcomingKind
import com.example.a2desktop.model.UpcomingItem
import com.example.a2desktop.logic.DayPick
import com.example.a2desktop.link.LinkTaskDelete
import com.example.a2desktop.link.LinkTaskUpdate
import com.example.a2desktop.link.SnapshotSource
import com.example.a2desktop.link.StoredPairing
import com.example.a2desktop.theme.A2Theme
import com.example.a2desktop.logic.CareState
import com.example.a2desktop.logic.EveningKind
import com.example.a2desktop.logic.Supplement
import com.example.a2desktop.ui.A2App
import com.example.a2desktop.ui.DevMode
import com.example.a2desktop.ui.LEFT_COLUMN_DP
import com.example.a2desktop.ui.Page
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * The Calendar's new controls, clicked and typed into for real in a headless window with the phone's
 * data on screen: pick a day, tap a habit, add an event, add a task.
 */
@OptIn(ExperimentalComposeUiApi::class)
class CalendarEditsUiTest {
    private val dir: File = Files.createTempDirectory("a2-cal-edits").toFile()
    private val json = Json { encodeDefaults = true }
    private val dummy = java.awt.Canvas()

    // A fixed "today" so the month grid is laid out exactly as in the dreams_linked/calendar_linked previews.
    private val today = LocalDate.of(2026, 10, 15)
    private val oct27 = LocalDate.of(2026, 10, 27)

    @AfterTest fun cleanUp() {
        dir.deleteRecursively()
    }

    private class Phone(private val json: Json, private val today: LocalDate) : SnapshotSource {
        /** Habits the phone now holds on days it accepted: a real phone's next snapshot has them. */
        val recorded = mutableMapOf<LocalDate, Set<Long>>()

        /** The skin-care and supplements record this phone keeps, or null for an older phone app that doesn't keep one. */
        @Volatile var care: CareState? = null

        override fun fetch(address: LinkAddress, pairingKey: String): LinkResult {
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
                care = care?.toWire(),
            )
            return LinkResult.Ok(snapshot, json.encodeToString(snapshot))
        }
    }

    private class Editor(private val phone: Phone) : EditWriter {
        val sent = mutableListOf<Pair<String, String>>()
        var reply: EditResult = EditResult.Done(LinkEditReply(ok = true))

        /** When set, what a care request is answered with instead of making it. */
        var careReply: EditResult? = null

        /** When set, this phone is an older app that doesn't know the L-theanine supplement: it ignores any change to it, as the real one does. */
        @Volatile var oldPhone = false
        override fun send(address: LinkAddress, pairingKey: String, path: String, bodyJson: String): EditResult {
            sent += path to bodyJson
            if (path == "/v1/care") {
                careReply?.let { return it }
                val request = Json.decodeFromString<LinkCareRequest>(bodyJson)
                var record = phone.care ?: return EditResult.Failed("This phone doesn't keep that.", phoneRefused = true)
                request.mergeCare?.let { record = record.mergedWith(CareState.fromWire(it)) }
                record = record.apply(if (oldPhone) request.commands.filterNot { it.supplement == "L_THEANINE_LEMON_BALM" } else request.commands)
                phone.care = record
                return EditResult.Done(LinkEditReply(ok = true, care = record.toWire()))
            }
            val result = reply
            if (result is EditResult.Done && result.reply.habitIds != null) {
                phone.recorded[LocalDate.ofEpochDay(result.reply.dateEpochDay!!)] = result.reply.habitIds!!.toSet()
            }
            return result
        }
    }

    private class Rig(val store: DesktopStore, val link: LinkController, val editor: Editor, val scene: ImageComposeScene, val dev: DevMode = DevMode()) {
        var time = 0L
    }

    private fun rig(
        skin: SkinCareStore = SkinCareStore(null), dev: DevMode = DevMode(), vitamins: VitaminStore = VitaminStore(null), emojis: CalendarEmojiStore = CalendarEmojiStore(null),
        width: Int = 1360, height: Int = 860, phoneCare: CareState? = null, careSync: CareSync = CareSync(null),
    ): Rig {
        val store = DesktopStore(today)
        val storage = LinkStorage(dir).also { it.savePairing(StoredPairing("192.168.1.23:47321", "ABCDEFGHJKLMNPQRSTUV")) }
        val phone = Phone(json, today).also { it.care = phoneCare }
        val editor = Editor(phone)
        val link = LinkController(store, storage, phone, CoroutineScope(Job()), Dispatchers.Unconfined, editor = editor)
        val scene = ImageComposeScene(width = width, height = height, density = Density(1f)) {
            A2Theme { A2App(store, link, Page.CALENDAR, skinCare = skin, vitamins = vitamins, emojis = emojis, dev = dev, careSync = careSync) }
        }
        scene.render(0)
        return Rig(store, link, editor, scene, dev)
    }

    private fun Rig.click(at: Offset) = click(at.x, at.y)

    /**
     * Scrolls the right-hand page (the wheel, with the pointer over it) until the group [id] is fully on screen and
     * returns where it is — so a test finds things by name, not by pixel, wherever the cards happen to be stacked.
     * Developer mode is only on while looking; the positions stay true after it is off.
     */
    private fun Rig.reveal(id: String): Rect {
        val wasOn = dev.enabled
        dev.enabled = true
        try {
            repeat(80) {
                repeat(3) { time += 50_000_000; scene.render(time) }
                val bounds = dev.groups[id]?.bounds
                // Fully inside the scrolling area, not just peeking in: a group cut off by the edge reports only the part
                // that shows (its bottom exactly on the edge), so keep clear of the edges (24 to 836 here).
                if (bounds != null && bounds.top >= 30f && bounds.bottom <= 830f) return bounds
                // The right-hand column and the left-hand one (Skin care, Supplements) each scroll on their own: wheel over both.
                scene.sendPointerEvent(PointerEventType.Scroll, Offset(1130f, 300f), scrollDelta = Offset(0f, 3f))
                scene.sendPointerEvent(PointerEventType.Scroll, Offset(200f, 300f), scrollDelta = Offset(0f, 3f))
            }
            error("could not scroll $id into view")
        } finally {
            if (!wasOn) {
                dev.toggle()
                // Let the number labels go before anything is clicked: until the next frame they would still catch the pointer.
                repeat(2) { time += 50_000_000; scene.render(time) }
            }
        }
    }

    /** Scrolls the right-hand page by [notches] wheel notches (positive = down). */
    private fun Rig.scrollPage(notches: Float) {
        scene.sendPointerEvent(PointerEventType.Scroll, Offset(1130f, 300f), scrollDelta = Offset(0f, notches))
        repeat(3) { time += 50_000_000; scene.render(time) }
    }

    /** The centre of a button inside a group, [dx] across and [dy] down from its top-left corner. */
    private fun Rect.at(dx: Float, dy: Float) = Offset(left + dx, top + dy)

    private fun Rig.click(x: Float, y: Float) {
        time += 100_000_000
        val at = Offset(x, y)
        scene.sendPointerEvent(PointerEventType.Move, at)
        scene.sendPointerEvent(PointerEventType.Press, at, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
        scene.render(time)
        scene.sendPointerEvent(PointerEventType.Release, at, button = PointerButton.Primary)
        scene.render(time + 16_000_000)
        repeat(12) { time += 50_000_000; scene.render(time) } // let a dialog finish animating
    }

    /** Compose's own AWT-to-Compose key conversion (what the real window runs), reached by reflection. */
    private fun composeKeyEvent(awt: java.awt.event.KeyEvent): KeyEvent {
        val internal = Class.forName("androidx.compose.ui.input.key.KeyEvent_desktopKt")
            .getMethod("toComposeEvent", java.awt.event.KeyEvent::class.java).invoke(null, awt)
        return KeyEvent::class.java.getMethod("box-impl", Any::class.java).invoke(null, internal) as KeyEvent
    }

    /** Types [text] one character at a time as real AWT KEY_TYPED events. */
    private fun Rig.type(text: String) {
        for (c in text) {
            val awt = java.awt.event.KeyEvent(dummy, java.awt.event.KeyEvent.KEY_TYPED, time / 1_000_000, 0, java.awt.event.KeyEvent.VK_UNDEFINED, c)
            scene.sendKeyEvent(composeKeyEvent(awt))
            time += 16_000_000
            scene.render(time)
        }
    }

    /** One Backspace in the box that has the focus. */
    private fun Rig.typeBackspace() {
        val awt = java.awt.event.KeyEvent(dummy, java.awt.event.KeyEvent.KEY_PRESSED, time / 1_000_000, 0, java.awt.event.KeyEvent.VK_BACK_SPACE, java.awt.event.KeyEvent.CHAR_UNDEFINED)
        scene.sendKeyEvent(composeKeyEvent(awt))
        time += 16_000_000
        scene.render(time)
        val up = java.awt.event.KeyEvent(dummy, java.awt.event.KeyEvent.KEY_RELEASED, time / 1_000_000, 0, java.awt.event.KeyEvent.VK_BACK_SPACE, java.awt.event.KeyEvent.CHAR_UNDEFINED)
        scene.sendKeyEvent(composeKeyEvent(up))
        time += 16_000_000
        scene.render(time)
    }

    private val gymId get() = sampleSnapshot(today).habits.first { it.name == "Gym" }.id


    @Test fun pickingAnotherDayAndTappingAHabitRecordsItOnThatDayOnThePhone() {
        val r = rig()
        try {
            r.click(oct27Cell) // the 27th in the month grid
            r.click(r.reveal("1.4.3").at(17f, 17f)) // the Gym emoji button (the first) in the day's card
            val (path, body) = r.editor.sent.single()
            assertEquals("/v1/habit", path)
            assertEquals(LinkHabitEdit(gymId, oct27.toEpochDay(), logged = true), Json.decodeFromString<LinkHabitEdit>(body))
        } finally {
            r.scene.close()
        }
    }

    @Test fun tappingARecordedHabitAgainClearsIt() {
        val r = rig()
        try {
            r.editor.reply = EditResult.Done(LinkEditReply(ok = true, dateEpochDay = oct27.toEpochDay(), habitIds = listOf(gymId)))
            r.click(oct27Cell)
            val gym = r.reveal("1.4.3").at(17f, 17f)
            r.click(gym) // record Gym on the 27th: the phone says it now holds Gym that day
            assertEquals(setOf(gymId), r.store.logs[oct27])
            r.editor.reply = EditResult.Done(LinkEditReply(ok = true, dateEpochDay = oct27.toEpochDay(), habitIds = emptyList()))
            r.click(gym) // tap it again
            val second = Json.decodeFromString<LinkHabitEdit>(r.editor.sent.last().second)
            assertFalse(second.logged, "the second tap must ask the phone to clear it")
        } finally {
            r.scene.close()
        }
    }


    @Test fun thereIsOneAddTaskButtonAndNoAddEventButton() {
        val r = rig()
        try {
            val row = r.reveal("1.4.5")
            val corner = Offset(145f, 32f) // the Calendar's blue spot in the top bar: dimmed by the dialog's backdrop whenever a form is open
            val plain = r.pixelAt(corner)
            // Where the old second button ("Add task") was: nothing is there any more, so nothing opens.
            r.click(row.at(177f, 18f))
            assertEquals(plain, r.pixelAt(corner), "no form opened where the second button used to be")
            // The one button is at the left, and it is the task form it opens.
            r.click(row.at(57f, 18f))
            assertTrue(r.pixelAt(corner) != plain, "the form is open")
            r.click(680f, TASK_NAME); r.type("Water change")
            r.click(895f, ADD_TASK)
            assertEquals("/v1/task", r.editor.sent.single().first)
        } finally {
            r.scene.close()
        }
    }

    @Test fun aTaskTypedIntoTheFormIsSentWithItsNameAndDueDate() {
        val r = rig()
        try {
            r.click(r.reveal("1.4.5").at(57f, 18f)) // Add task
            r.click(680f, TASK_NAME); r.type("Water change")
            r.click(895f, ADD_TASK) // Add task (confirm)
            val (path, body) = r.editor.sent.single()
            assertEquals("/v1/task", path)
            assertEquals(LinkTaskCreate("Water change", today.toEpochDay(), 1, "NONE"), Json.decodeFromString<LinkTaskCreate>(body).copy(requestId = null))
        } finally {
            r.scene.close()
        }
    }

    @Test fun aTasksRepeatChoicesAreStillThere() {
        val r = rig()
        try {
            r.click(r.reveal("1.4.5").at(57f, 18f)) // Add task
            r.click(680f, TASK_NAME); r.type("Water change")
            r.click(665f, TASK_REPEAT_CHIPS) // Weeks
            r.click(895f, ADD_TASK_REPEATING)
            assertEquals(LinkTaskCreate("Water change", today.toEpochDay(), 1, "WEEKS"), Json.decodeFromString<LinkTaskCreate>(r.editor.sent.single().second).copy(requestId = null))
        } finally {
            r.scene.close()
        }
    }


    @Test fun theConfirmButtonDoesNothingWhileTheFormIsIncomplete() {
        val r = rig()
        try {
            r.click(r.reveal("1.4.5").at(57f, 18f)) // Add task, with no name typed
            r.click(895f, ADD_TASK) // confirm
            assertTrue(r.editor.sent.isEmpty(), "a task with no name must never be sent")
        } finally {
            r.scene.close()
        }
    }


    @Test fun whenThePhoneSaysNoTheFormStaysOpenAndNothingIsAddedLocally() {
        val r = rig()
        try {
            r.editor.reply = EditResult.Failed("That date is out of range.", phoneRefused = true)
            val upcomingBefore = r.store.upcoming.toList()
            r.click(r.reveal("1.4.5").at(57f, 18f))
            r.click(680f, TASK_NAME); r.type("Optician")
            r.click(895f, ADD_TASK)
            assertEquals(1, r.editor.sent.size, "the request was made")
            assertEquals(upcomingBefore, r.store.upcoming.toList())
            // The form is still open: a second press of confirm sends again (it would not exist if it had closed).
            r.click(895f, ADD_TASK)
            assertEquals(2, r.editor.sent.size)
        } finally {
            r.scene.close()
        }
    }

    // ---- the skin-care tick, in the Skin care card on the right (15 Oct 2026, a Thursday, is a glycolic night) ----

    private fun Int.red() = (this shr 16) and 0xFF
    private fun Int.green() = (this shr 8) and 0xFF

    /** What is on screen now. */
    private fun Rig.bitmap(): org.jetbrains.skia.Bitmap {
        repeat(3) { time += 50_000_000; scene.render(time) }
        return org.jetbrains.skia.Bitmap.makeFromImage(scene.render(time + 50_000_000))
    }

    /** The colour at [at] in what is on screen now. */
    private fun Rig.pixelAt(at: Offset): Int = bitmap().getColor(at.x.toInt(), at.y.toInt())

    /** Developer mode on, with a few frames so every group has reported where it is. */
    private fun Rig.devOn() { dev.enabled = true; repeat(4) { time += 50_000_000; scene.render(time) } }

    /** Developer mode off, with frames to let the number labels go before anything is clicked (until then they would catch the pointer). */
    private fun Rig.devOff() { dev.enabled = false; repeat(2) { time += 50_000_000; scene.render(time) } }

    /** A spot inside the round tick, above the ✓ drawn at its middle: where its fill (or its empty ring's dark) shows. */
    private fun Rect.fill() = Offset(center.x, center.y - 8f)

    // The month grid at 1360 x 860 is the middle column (24 + LEFT_COLUMN_DP + 24 = 348 px in, 180 down — under the 64 px top bar — and
    // ends at 836): cells about 74 wide and 104 tall, 6 px apart. [cellRect] works the spots out.

    /** The Monday of the grid's third row used for "an earlier day": 12 October, three days before today. */
    private val oct12Cell get() = cellRect(LocalDate.of(2026, 10, 12)).center

    /** 5 October, the Monday of the second row: the day the routine began. */
    private val oct5Cell get() = cellRect(LocalDate.of(2026, 10, 5)).center

    /** 27 October, twelve days from today. */
    private val oct27Cell get() = cellRect(LocalDate.of(2026, 10, 27)).center

    /** A saved skin-care file where the routine began on [startedOn] and confirming on [confirmFrom]. */
    private fun skinStartedOn(startedOn: LocalDate, confirmFrom: LocalDate, extra: String = ""): SkinCareStore {
        val file = File(dir, "skincare.json")
        file.writeText("""{"startedOn":${startedOn.toEpochDay()},"confirmFrom":${confirmFrom.toEpochDay()}$extra}""")
        return SkinCareStore(file)
    }

    @Test fun tickingTonightInTheSkinCareCardConfirmsItAndTappingTheTickAgainTakesItBack() {
        val skin = SkinCareStore(null)
        val r = rig(skin)
        try {
            assertFalse(skin.log.doneOn(today))
            r.click(r.reveal("1.5.5").center) // the tick on tonight's row
            assertTrue(skin.log.doneOn(today), "confirmed")
            assertEquals(setOf(today), skin.log.glycolicNights, "as a glycolic night, which is what the plan had")
            r.click(r.reveal("1.5.5").center) // the tick again
            assertFalse(skin.log.doneOn(today), "taken back")
            assertTrue(skin.log.glycolicNights.isEmpty())
            assertTrue(skin.log.eveningsSkipped.isEmpty(), "taking it back is not a no: there is no no")
            assertTrue(r.editor.sent.isEmpty(), "skin care never goes to the phone")
        } finally {
            r.scene.close()
        }
    }

    @Test fun theTickIsAnEmptyRingUntilItIsTickedAndThenSolidGreen() {
        val r = rig()
        try {
            val empty = r.pixelAt(r.reveal("1.5.5").fill())
            assertTrue(empty.green() - empty.red() < 30, "not ticked: no green fill yet")
            r.click(r.reveal("1.5.5").center)
            val ticked = r.pixelAt(r.reveal("1.5.5").fill())
            assertTrue(ticked.green() - ticked.red() > 60, "ticked: solid green")
            r.click(r.reveal("1.5.5").center)
            val again = r.pixelAt(r.reveal("1.5.5").fill())
            assertTrue(again.green() - again.red() < 30, "and empty again once the tick is taken back")
        } finally {
            r.scene.close()
        }
    }

    @Test fun theDailyCheckInIsGoneAndTonightsTickIsInTheSkinCareCard() {
        val r = rig()
        try {
            r.reveal("1.5") // the Habits and Tasks cards are above it now, so scroll the Skin care card into view
            r.devOn()
            assertTrue(r.dev.groups.keys.none { it == "1.13" || it.startsWith("1.13.") }, "the Daily Check-in (PC1.13) is gone")
            assertTrue("1.5.5" in r.dev.groups, "tonight's tick is in the Skin care card")
            assertTrue("1.5.6" !in r.dev.groups, "and no earlier day is shown while today is selected")
        } finally {
            r.scene.close()
        }
    }

    @Test fun anEarlierDayIsShownInTheCardWithItsOwnTickAndCanBeTickedLate() {
        // Confirming began on the 6th, the routine on the 5th: the 12th was never ticked, so it has dropped off.
        val oct12 = LocalDate.of(2026, 10, 12)
        val skin = skinStartedOn(LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 6))
        val r = rig(skin)
        try {
            r.click(oct12Cell)
            assertFalse(skin.log.doneOn(oct12))
            r.click(r.reveal("1.5.6").center) // the tick of the 12th's row, shown at the top of the card
            assertTrue(skin.log.doneOn(oct12), "the evening is recorded for the 12th")
            assertFalse(skin.log.doneOn(today), "and nothing else")
            r.click(r.reveal("1.5.6").center) // and taken back again
            assertFalse(skin.log.doneOn(oct12))
        } finally {
            r.scene.close()
        }
    }

    @Test fun aDayBeforeConfirmingBeganAndADayStillToComeHaveNoTickOfTheirOwn() {
        val skin = skinStartedOn(LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 14))
        val r = rig(skin)
        try {
            r.click(oct5Cell) // the 5th, before confirming began: unticked days then count as followed, so nothing to tick
            r.reveal("1.5") // scroll the Skin care card into view (the Habits and Tasks cards are above it), so every row it has is on screen
            r.devOn()
            assertTrue("1.5.6" !in r.dev.groups, "no tick for a day before confirming began")
            r.devOff()
            r.click(oct27Cell) // twelve days from now
            r.reveal("1.5")
            r.devOn()
            assertTrue("1.5.6" !in r.dev.groups, "no tick for a day still to come")
            assertTrue("1.5.5" in r.dev.groups, "tonight's tick is still there")
            assertTrue(skin.log.eveningsDone.isEmpty() && skin.log.glycolicNights.isEmpty(), "nothing was recorded")
        } finally {
            r.scene.close()
        }
    }

    @Test fun aRestNightHasNoTickBecauseThereIsNothingToDo() {
        // Glycolic was used last night, so tonight is a blank rest night.
        val yesterday = today.minusDays(1)
        val skin = skinStartedOn(LocalDate.of(2026, 10, 5), yesterday, ""","glycolicNights":[${yesterday.toEpochDay()}],"eveningsDone":[${yesterday.toEpochDay()}]""")
        val r = rig(skin)
        try {
            r.devOn()
            assertTrue("1.5.5" !in r.dev.groups, "nothing to tick on a rest night")
        } finally {
            r.scene.close()
        }
    }

    // ---- choosing the product for an evening (the pencil beside the tick) ----

    private fun Rig.replanned(skin: SkinCareStore) =
        com.example.a2desktop.logic.planRoutine(today, skin.startedOn, 6, skin.log, store.workSchedule, com.example.a2desktop.logic.SeasonalUv, skin.confirmFrom)

    @Test fun everyEveningHasAPencilBesideItsTickEvenARestNightAndOneStillToCome() {
        val yesterday = today.minusDays(1)
        val skin = skinStartedOn(LocalDate.of(2026, 10, 5), yesterday, ""","glycolicNights":[${yesterday.toEpochDay()}],"eveningsDone":[${yesterday.toEpochDay()}]""")
        val r = rig(skin, height = 2400) // tall, so every row of the card is in view
        try {
            r.devOn()
            assertTrue("1.5.5" !in r.dev.groups, "tonight is a rest night, so no tick")
            (1..7).forEach { assertTrue("1.5.7.$it" in r.dev.groups, "a pencil on row $it") }
            val pencil = r.dev.groups["1.5.7.1"]!!.bounds
            val rowTwo = r.dev.groups["1.5.7.2"]!!.bounds
            assertEquals(pencil.left, rowTwo.left, 0.5f, "the pencils line up down the card")
        } finally {
            r.scene.close()
        }
    }

    @Test fun thePencilOpensTheListAndChoosingAProductSetsTonightAndPlansTheNextDaysAgain() {
        val skin = SkinCareStore(null)
        val r = rig(skin)
        try {
            val before = r.replanned(skin)
            assertEquals(EveningKind.GLYCOLIC, before[0].eveningKind, "the plan has glycolic tonight")
            assertFalse(before[1].glycolicTonight)
            r.click(r.reveal("1.5.7.1").center)
            r.devOn()
            EveningKind.entries.indices.forEach { assertTrue("1.5.8.${it + 1}" in r.dev.groups, "a choice for ${EveningKind.entries[it]}") }
            assertTrue("1.5.8.0" !in r.dev.groups, "nothing chosen yet, so no way back to the plan")
            r.devOff()
            r.click(r.reveal("1.5.8.3").center) // Niacinamide
            assertEquals(EveningKind.CALM, skin.log.chosenKinds[today])
            assertFalse(skin.log.doneOn(today), "choosing is not ticking")
            assertTrue(r.editor.sent.isEmpty(), "with no phone record it stays on this PC")
            val after = r.replanned(skin)
            assertEquals(EveningKind.CALM, after[0].eveningKind)
            assertTrue(after[1].glycolicTonight, "the glycolic moved to tomorrow: the next days were planned again")
        } finally {
            r.scene.close()
        }
    }

    @Test fun onceChosenTheListMarksItAndOffersToFollowThePlanAgain() {
        val skin = SkinCareStore(null)
        val r = rig(skin)
        try {
            r.click(r.reveal("1.5.7.1").center)
            r.click(r.reveal("1.5.8.2").center) // Glycolic toner
            assertEquals(EveningKind.TONER, skin.log.chosenKinds[today])
            r.click(r.reveal("1.5.7.1").center)
            r.devOn()
            assertTrue("1.5.8.0" in r.dev.groups, "now there is a way back")
            r.devOff()
            r.click(r.reveal("1.5.8.0").center)
            assertTrue(skin.log.chosenKinds.isEmpty(), "back to the plan")
            assertEquals(EveningKind.GLYCOLIC, r.replanned(skin)[0].eveningKind)
        } finally {
            r.scene.close()
        }
    }

    @Test fun anEveningStillToComeCanBeChosenAndIsNotTicked() {
        val skin = SkinCareStore(null)
        val r = rig(skin)
        try {
            val third = today.plusDays(2)
            r.click(r.reveal("1.5.7.3").center)
            r.click(r.reveal("1.5.8.6").center) // Rest night
            assertEquals(EveningKind.REST, skin.log.chosenKinds[third])
            assertFalse(skin.log.doneOn(third))
            assertEquals(EveningKind.REST, r.replanned(skin)[2].eveningKind)
        } finally {
            r.scene.close()
        }
    }

    @Test fun choosingForAnEveningAlreadyTickedChangesWhatWasUsedAndKeepsItTicked() {
        val skin = SkinCareStore(null)
        val r = rig(skin)
        try {
            r.click(r.reveal("1.5.5").center) // ticked as the plan's glycolic
            assertEquals(setOf(today), skin.log.glycolicNights)
            r.click(r.reveal("1.5.7.1").center)
            r.click(r.reveal("1.5.8.3").center) // really it was niacinamide
            assertTrue(skin.log.doneOn(today), "still ticked")
            assertEquals(EveningKind.CALM, skin.log.usedOn(today))
            assertTrue(skin.log.glycolicNights.isEmpty(), "and no longer counted as an exfoliating night")
        } finally {
            r.scene.close()
        }
    }

    @Test fun whenThePhoneKeepsTheRecordTheChoiceGoesToThePhoneAndComesBack() {
        val skin = SkinCareStore(null)
        val sync = CareSync(null)
        val r = rig(skin = skin, phoneCare = CareState(), careSync = sync)
        try {
            r.click(oct27Cell) // a few frames, so the first sync and the joining have happened
            r.select(today)
            r.click(r.reveal("1.5.7.1").center)
            r.click(r.reveal("1.5.8.4").center) // Vitamin C
            val request = Json.decodeFromString<LinkCareRequest>(r.editor.sent.last { it.first == "/v1/care" }.second)
            assertEquals("skin.setChosen", request.commands.single().op)
            assertEquals("BRIGHTEN", request.commands.single().kind)
            assertEquals(EveningKind.BRIGHTEN, skin.log.chosenKinds[today], "shown once the phone has answered with its record")
        } finally {
            r.scene.close()
        }
    }

    // ---- the habit emojis in the Selected day card ----

    @Test fun theSelectedDayCardHasAnEmojiForEveryHabitThatCanBeRecordedAndNoneForSober() {
        val r = rig()
        try {
            val row = r.reveal("1.4.3")
            val recordable = r.store.habits.filter { !it.isSober }
            assertTrue(recordable.size >= 3)
            // 34 dp wide buttons 8 dp apart, from the group's left edge: the last one is the same distance along, and nothing follows it.
            val first = row.left + 17f
            val last = Offset(first + (recordable.size - 1) * 42f, row.top + 17f)
            r.click(last)
            assertEquals(recordable.last().id, Json.decodeFromString<LinkHabitEdit>(r.editor.sent.single().second).habitId)
            r.click(Offset(first + recordable.size * 42f, row.top + 17f)) // where a further button would be
            assertEquals(1, r.editor.sent.size, "there is no button after the last habit")
        } finally {
            r.scene.close()
        }
    }

    // ---- the Selected day card: the day's table, then its habits ----

    private val oct26 = LocalDate.of(2026, 10, 26)
    private val oct15 = LocalDate.of(2026, 10, 15)
    private val oct16 = LocalDate.of(2026, 10, 16)
    private val oct17 = LocalDate.of(2026, 10, 17)
    private val oct18 = LocalDate.of(2026, 10, 18)
    private val oct21 = LocalDate.of(2026, 10, 21)
    private val oct23 = LocalDate.of(2026, 10, 23)
    private val oct28 = LocalDate.of(2026, 10, 28)
    private val oct30 = LocalDate.of(2026, 10, 30)

    /** The titles of the rows in the Selected day card's table right now (read from the sample data's day, not from the screen). */
    private fun Rig.tableRows(): Int {
        devOn()
        try {
            return generateSequence(1) { it + 1 }.takeWhile { "1.4.6.$it" in dev.groups }.count()
        } finally {
            devOff()
        }
    }

    private fun Rig.select(date: LocalDate) = click(cellRect(date).center)

    // The sample phone (today = Thursday 15 October 2026): Pick up parcel (event, all day) and Water plants (a task) today; Change bed
    // sheets (a task, every 2 weeks) on the 16th; Dentist 14:30 on the 17th; Team call 11:00 on the 19th; Pay rent (every 1 month) on
    // the 21st; Birthday dinner 19:30 on the 24th; Renew gym membership (one-time) on the 27th; Car service 09:00 on the 2nd.

    @Test fun selectingADayListsExactlyWhatIsOnThatDayAndAnotherDayReplacesIt() {
        val r = rig()
        try {
            assertEquals(2, r.tableRows(), "today: the parcel and the plants")
            r.select(oct17)
            assertEquals(1, r.tableRows(), "the 17th: the dentist")
            r.select(oct16)
            assertEquals(1, r.tableRows(), "the 16th: the bed sheets — the dentist is gone")
            r.select(oct18)
            assertEquals(0, r.tableRows(), "the 18th: nothing is scheduled")
            r.select(today)
            assertEquals(2, r.tableRows(), "and back to today")
        } finally {
            r.scene.close()
        }
    }

    @Test fun aDayWithNothingOnItIsJustOneShortLine() {
        val r = rig()
        try {
            r.select(oct18)
            r.devOn()
            val table = r.dev.groups.getValue("1.4.6").bounds
            assertTrue(table.height < 40f, "\"Nothing scheduled\" is a single line (${table.height} dp)")
            r.devOff()
            r.select(oct17) // one row: its own height, with no "Nothing scheduled" line above it
            r.devOn()
            val one = r.dev.groups.getValue("1.4.6").bounds.height
            assertTrue(one in 36f..58f, "one row of the table is about 46 dp tall ($one dp)")
            r.devOff()
            r.select(today) // two rows
            r.devOn()
            val two = r.dev.groups.getValue("1.4.6").bounds.height
            assertTrue(two > one + 30f && two < 2 * one + 4f, "two things make it two rows tall ($two dp against $one dp for one)")
            r.devOff()
        } finally {
            r.scene.close()
        }
    }

    @Test fun pastAndFutureDaysListWhatIsOnThem() {
        val r = rig()
        try {
            r.select(LocalDate.of(2026, 10, 13))
            assertEquals(1, r.tableRows(), "a past day: the car service")
            r.select(LocalDate.of(2026, 10, 24))
            assertEquals(1, r.tableRows(), "a day to come: the birthday dinner")
            r.select(LocalDate.of(2026, 10, 27))
            assertEquals(1, r.tableRows(), "a one-time task on its due day")
            r.select(LocalDate.of(2026, 10, 19))
            assertEquals(1, r.tableRows(), "the team call")
        } finally {
            r.scene.close()
        }
    }

    @Test fun repeatingItemsAppearOnTheDaysTheyFallOnAndNotOnTheOthers() {
        val r = rig()
        try {
            r.select(oct21)
            assertEquals(1, r.tableRows(), "Pay rent is due on the 21st")
            r.select(oct30)
            assertEquals(1, r.tableRows(), "the bed sheets every 2 weeks from the 16th: the 30th")
            r.select(oct23)
            assertEquals(0, r.tableRows(), "a week after the 16th is not a fortnight")
            r.select(LocalDate.of(2026, 11, 6))
            assertEquals(0, r.tableRows(), "and the 6th of November is no fortnight either")
        } finally {
            r.scene.close()
        }
    }

    @Test fun aRepeatingItemsEmojiIsOnEachOfItsDaysInTheMonthGridAndNotBetween() {
        val emojis = CalendarEmojiStore(null)
        val r = rig(emojis = emojis)
        try {
            val none30 = r.bitmap().middleOf(oct30)
            val none23 = r.bitmap().middleOf(oct23)
            emojis.setEmoji("Change bed sheets", "🛏️")
            val bitmap = r.bitmap()
            // On the 16th the cell's Zn mark gives way to an emoji (the work times take two lines in a cell this narrow), so that day is checked in
            // the data the grid draws from; the 30th (beyond the rota the sample has) shows the picture.
            assertEquals(listOf("🛏️"), emojis.emojisOn(oct16, r.store.events, r.store.upcoming, today, r.store.workSchedule).map { it.emoji }, "on the day it is due")
            assertTrue(bitmap.middleOf(oct30) - none30 > 2000, "and on the day it comes round again")
            assertTrue(kotlin.math.abs(bitmap.middleOf(oct23) - none23) < 500, "but not on the days between")
        } finally {
            r.scene.close()
        }
    }

    @Test fun anEventTappedInTheTableCanChangeItsEmojiAndTheMonthGridFollows() {
        val emojis = CalendarEmojiStore(null)
        val r = rig(emojis = emojis)
        try {
            val before = r.bitmap().middleOf(oct17)
            r.select(oct17)
            r.click(r.reveal("1.4.6.1").let { Offset(it.left + 14f, it.center.y) }) // the dentist's emoji slot
            r.click(PICKER_SEARCH); r.type("tooth")
            r.click(PICKER_FIRST)
            assertEquals("🦷", emojis.emojiFor("Dentist"))
            assertTrue(r.bitmap().middleOf(oct17) - before > 2000, "the tooth is on the 17th in the month grid")
            assertTrue(r.editor.sent.isEmpty(), "and the phone was not asked anything")
        } finally {
            r.scene.close()
        }
    }

    @Test fun theDateComesFirstThenTheTableThenTheHabits() {
        val dev = DevMode().also { it.enabled = true }
        val r = rig(dev = dev)
        try {
            repeat(4) { r.time += 50_000_000; r.scene.render(r.time) }
            val card = r.dev.groups.getValue("1.4").bounds
            val table = r.dev.groups.getValue("1.4.6").bounds
            val habits = r.dev.groups.getValue("1.4.3").bounds
            val buttons = r.dev.groups.getValue("1.4.5").bounds
            assertTrue(table.top > card.top + 20f, "the date heads the card, the table is under it")
            assertTrue(habits.top >= table.bottom, "the habits are under the table")
            assertTrue(buttons.top >= habits.bottom, "then the Add task button")
            assertTrue(table.left >= card.left && table.right <= card.right && habits.right <= card.right, "all inside the card")
        } finally {
            r.scene.close()
        }
    }

    @Test fun theCardStaysTidyInASmallerWindow() {
        val dev = DevMode().also { it.enabled = true }
        val r = rig(dev = dev, width = 1100, height = 700)
        try {
            repeat(4) { r.time += 50_000_000; r.scene.render(r.time) }
            val card = r.dev.groups.getValue("1.4").bounds
            val table = r.dev.groups.getValue("1.4.6").bounds
            val row = r.dev.groups.getValue("1.4.6.1").bounds
            assertTrue(card.right in 1040f..(1100f - 24f), "the card still ends at the page's right edge (${card.right})")
            assertTrue(table.width > 300f && row.width == table.width, "its rows are as wide as the table (${row.width} of ${table.width})")
            assertTrue(r.dev.groups.getValue("1.4.5").bounds.bottom < 700f, "and the day, its habits and the Add task button fit in the shorter window")
        } finally {
            r.scene.close()
        }
    }

    // ---- the habits of the selected day: recorded per day, restored, and never touching another day ----

    /** Whether the first habit button of the selected day is lit (its coloured outline is drawn only when recorded that day). */
    private fun Rig.gymLit(): Boolean {
        val box = reveal("1.4.3")
        val c = pixelAt(Offset(box.left + 1f, box.top + 17f))
        return maxOf(c.red(), c.green(), c and 0xFF) > 90
    }

    @Test fun aHabitIsRecordedOnTheSelectedDayOnlyAndRestoredWhenYouComeBack() {
        val r = rig()
        try {
            val otherHabit = r.store.habits.first { !it.isSober && it.id != gymId }.id
            val before28 = r.store.logs[oct28].orEmpty()
            fun phoneHolds(date: LocalDate, vararg ids: Long) {
                r.editor.reply = EditResult.Done(LinkEditReply(ok = true, dateEpochDay = date.toEpochDay(), habitIds = ids.toList()))
            }
            fun tapGym() = r.click(r.reveal("1.4.3").at(17f, 17f))

            r.select(oct27)
            phoneHolds(oct27, gymId)
            tapGym()
            assertEquals(setOf(gymId), r.store.logs[oct27], "recorded on the 27th")
            assertTrue(r.gymLit(), "and lit")

            r.select(oct28)
            assertEquals(before28, r.store.logs[oct28].orEmpty(), "the 28th is untouched")
            phoneHolds(oct28, gymId)
            tapGym()
            assertEquals(setOf(gymId), r.store.logs[oct28])
            assertEquals(setOf(gymId), r.store.logs[oct27], "and the 27th is as it was")

            r.select(oct27)
            assertTrue(r.gymLit(), "come back to the 27th: still recorded")
            phoneHolds(oct27) // the phone now holds nothing for that day
            tapGym()
            assertTrue(r.store.logs[oct27].orEmpty().isEmpty(), "unticked by mistake: cleared")
            assertFalse(r.gymLit())
            assertEquals(setOf(gymId), r.store.logs[oct28], "the 28th is still recorded")

            r.select(oct28)
            assertTrue(r.gymLit(), "and shown again on the 28th")
            // The phone decides what a day holds (a linked habit can clear another): the card shows what it answers, not what was asked.
            phoneHolds(oct28, otherHabit)
            tapGym()
            assertEquals(setOf(otherHabit), r.store.logs[oct28])
            assertFalse(r.gymLit())
            assertEquals(listOf("/v1/habit", "/v1/habit", "/v1/habit", "/v1/habit"), r.editor.sent.map { it.first }, "one request per tap, nothing else")
        } finally {
            r.scene.close()
        }
    }

    // ---- one item per title: adding what is already on the Upcoming list is not allowed, it is marked done instead ----
    // With a name that is already upcoming ("Dentist", Sat 17 Oct in the sample) a notice sits under the name: on today's task form "Mark it done on"
    // is at (524, 477) and "Add task" at (895, 641); on a form for a day to come there is no button.

    @Test fun anEventWhoseTitleIsAlreadyUpcomingIsNotAddedAgainAsATaskAndCanBeMarkedDoneOnTheDayInstead() {
        val emojis = CalendarEmojiStore(null)
        val r = rig(emojis = emojis)
        try {
            r.click(r.reveal("1.4.5").at(57f, 18f))
            r.click(680f, TASK_NAME); r.type("dentist") // not the same capitals: still the same thing
            r.click(895f, 641f) // Add task: it is not offered
            assertTrue(r.editor.sent.isEmpty(), "a second Dentist must not be sent to the phone")
            r.click(524f, 477f) // Mark it done on Thu 15 Oct
            assertEquals(today, emojis.doneOn("Dentist", oct17), "the Dentist that is due on the 17th is now done today")
            assertTrue(r.editor.sent.isEmpty(), "and still nothing was added")
        } finally {
            r.scene.close()
        }
    }

    @Test fun markingItDoneOnAnEarlierDayRecordsThatDayNotToday() {
        val emojis = CalendarEmojiStore(null)
        val r = rig(emojis = emojis)
        try {
            val oct12 = LocalDate.of(2026, 10, 12)
            r.click(oct12Cell)
            r.click(r.reveal("1.4.5").at(57f, 18f))
            r.click(680f, TASK_NAME); r.type("Dentist")
            r.click(524f, 477f) // Mark it done on Mon 12 Oct
            assertEquals(oct12, emojis.doneOn("Dentist", oct17), "the haircut-style tick lands on the day that was selected")
        } finally {
            r.scene.close()
        }
    }

    @Test fun onADayStillToComeTheDuplicateIsRefusedAndThereIsNothingToMarkDone() {
        val emojis = CalendarEmojiStore(null)
        val r = rig(emojis = emojis)
        try {
            r.click(oct27Cell)
            r.click(r.reveal("1.4.5").at(57f, 18f))
            r.click(680f, TASK_NAME); r.type("Dentist")
            r.click(895f, 641f) // Add task
            r.click(524f, 477f) // where "Mark it done" is on today's form: nothing is there
            assertTrue(r.editor.sent.isEmpty(), "not sent")
            assertTrue(emojis.done.isEmpty(), "and nothing was marked done on a day that has not come")
        } finally {
            r.scene.close()
        }
    }

    @Test fun aTaskWhoseNameIsAlreadyUpcomingIsNotAddedAgainEither() {
        val emojis = CalendarEmojiStore(null)
        val r = rig(emojis = emojis)
        try {
            r.click(r.reveal("1.4.5").at(57f, 18f)) // Add task
            r.click(680f, TASK_NAME); r.type("Water plants")
            r.click(895f, 641f) // Add task
            assertTrue(r.editor.sent.isEmpty(), "a second Water plants must not be sent")
            r.click(524f, 477f) // Mark it done on Thu 15 Oct
            assertEquals(today, emojis.doneOn("Water plants", today), "the plants that are due today are done today")
            assertTrue(r.editor.sent.isEmpty())
        } finally {
            r.scene.close()
        }
    }

    @Test fun tappingTheFrequentHaircutEmojiWhenAHaircutIsAlreadyUpcomingMarksThatOneDoneInsteadOfAddingAnother() {
        val emojis = CalendarEmojiStore(null).also { it.setEmoji("Haircut", "✂️") }
        val r = rig(emojis = emojis)
        try {
            val oct20 = LocalDate.of(2026, 10, 20)
            r.store.upcoming.add(com.example.a2desktop.model.UpcomingItem("event-38", "Haircut", oct20, com.example.a2desktop.model.UpcomingKind.EVENT, "Every 14 days"))
            r.click(r.reveal("1.4.5").at(57f, 18f))
            r.click(QUICK_FIRST) // ✂️ fills in "Haircut", which is already upcoming
            r.click(895f, 641f) // Add task
            assertTrue(r.editor.sent.isEmpty(), "no second haircut")
            r.click(524f, 477f)
            assertEquals(today, emojis.doneOn("Haircut", oct20), "the haircut due on the 20th, marked done today")
        } finally {
            r.scene.close()
        }
    }

    // ---- Edit / Delete of what the phone holds ----
    // Each row of the Selected day table and of the Upcoming card has a small pen (group .2 of the row) at its right edge. The window it opens (1360 x 860):
    // (Every window ends with the "Mark as done" part, 192 px tall, so the fields sit 96 px higher and the buttons 96 px lower than without it.)
    //  - an event the phone doesn't say how it repeats ("Pick up parcel"): title (680, 266), date (680, 340), time (680, 434), Delete (770, 729), Save (906, 729);
    //  - an event on the Upcoming list ("Dentist", 17 Oct): title (680, 242), date (680, 316), time (680, 410), repeat (680, 484), Delete (770, 753), Save (906, 753);
    //  - a task with a repeat the desktop can't read ("Water plants", "Weekly"): name (680, 303), Delete (770, 692), Save (906, 692);
    //  - a task with a repeat it can ("Change bed sheets", every 2 weeks): name (680, 199), Delete (770, 797), Save (906, 797) — it also has the "Which day?" part, 78 px tall;
    //  - after Delete: for an event "Keep it" (765, 493) and "Delete" (840, 493); for a task "Delete" (902, 503).
    // Typing goes to the end of the box when it is clicked at its right-hand end (x 900).

    private fun Rig.editLink(group: String) = click(reveal("$group.2").center) // the small pen at the right of a row

    @Test fun editingAnEventSendsAnUpdateForThatEventsIdAndLeavesItsUnknownRepeatAlone() {
        val r = rig()
        try {
            r.editLink("1.4.6.1") // Pick up parcel: event 5, today, all day
            r.click(900f, 266f); r.type(" now")
            r.click(906f, 729f) // Save
            val (path, body) = r.editor.sent.single()
            assertEquals("/v1/event/update", path)
            assertEquals(LinkEventUpdate(5, "Pick up parcel now", today.toEpochDay(), -1, null, keepRepeat = true), Json.decodeFromString<LinkEventUpdate>(body).copy(madeAtMillis = 0))
        } finally {
            r.scene.close()
        }
    }

    @Test fun anEventOnTheUpcomingListShowsItsTimeAndRepeatAndSendsBothBackChanged() {
        val r = rig()
        try {
            r.select(oct17)
            r.editLink("1.4.6.1") // Dentist: event 2, 14:30, no repeat
            r.click(680f, 484f); r.type("14") // repeat every 14 days (the form grows: "Which day?" appears, and Save is 39 px lower)
            r.click(906f, 789f)
            val update = Json.decodeFromString<LinkEventUpdate>(r.editor.sent.single().second).copy(madeAtMillis = 0)
            assertEquals(LinkEventUpdate(2, "Dentist", oct17.toEpochDay(), 14 * 60 + 30, 14, keepRepeat = false), update, "its time is kept, the repeat is sent, and it is not 'keep'")
        } finally {
            r.scene.close()
        }
    }

    @Test fun theDateAndTimeCanBeChangedAndAnEmptyTimeMeansAllDay() {
        val r = rig()
        try {
            r.select(oct17)
            r.editLink("1.4.6.1")
            r.click(900f, 316f); repeat(10) { r.typeBackspace() }; r.type("20/10/2026") // the date box: clear it and type another
            r.click(900f, 410f); repeat(5) { r.typeBackspace() } // the time box: empty
            r.click(906f, 753f)
            val update = Json.decodeFromString<LinkEventUpdate>(r.editor.sent.single().second).copy(madeAtMillis = 0)
            assertEquals(LocalDate.of(2026, 10, 20).toEpochDay(), update.dateEpochDay)
            assertEquals(-1, update.startMinuteOfDay)
        } finally {
            r.scene.close()
        }
    }

    @Test fun aDateThatIsNotARealDayDisablesSave() {
        val r = rig()
        try {
            r.select(oct17)
            r.editLink("1.4.6.1")
            r.click(900f, 316f); repeat(10) { r.typeBackspace() }; r.type("31/02/2026")
            r.click(906f, 753f)
            assertTrue(r.editor.sent.isEmpty(), "31 February is not sent")
        } finally {
            r.scene.close()
        }
    }

    @Test fun deletingAnEventAsksFirstAndThenSendsTheDeleteForThatEventsId() {
        val r = rig()
        try {
            r.editLink("1.4.6.1") // Pick up parcel: event 5
            r.click(770f, 729f) // Delete
            assertTrue(r.editor.sent.isEmpty(), "nothing is sent until it is confirmed")
            r.click(765f, 493f) // Keep it
            assertTrue(r.editor.sent.isEmpty(), "Keep it changes nothing")
            r.click(770f, 729f) // Delete again
            r.click(840f, 493f) // Delete (confirm)
            val (path, body) = r.editor.sent.single()
            assertEquals("/v1/event/delete", path)
            assertEquals(LinkEventDelete(5), Json.decodeFromString<LinkEventDelete>(body).copy(madeAtMillis = 0))
        } finally {
            r.scene.close()
        }
    }

    @Test fun editingATaskWhoseRepeatTheDesktopCannotReadLeavesItsRepeatAlone() {
        val r = rig()
        try {
            r.editLink("1.4.6.2") // Water plants: task 1, labelled "Weekly"
            r.click(900f, 303f); r.type(" now")
            r.click(906f, 692f)
            val (path, body) = r.editor.sent.single()
            assertEquals("/v1/task/update", path)
            assertEquals(LinkTaskUpdate(1, "Water plants now", today.toEpochDay(), 1, "NONE", keepRepeat = true), Json.decodeFromString<LinkTaskUpdate>(body).copy(madeAtMillis = 0))
        } finally {
            r.scene.close()
        }
    }

    @Test fun editingATaskWithAReadableRepeatSendsItsRepeatBack() {
        val r = rig()
        try {
            r.select(oct16)
            r.editLink("1.4.6.1") // Change bed sheets: task 2, every 2 weeks
            r.click(906f, 797f)
            val update = Json.decodeFromString<LinkTaskUpdate>(r.editor.sent.single().second).copy(madeAtMillis = 0)
            assertEquals(LinkTaskUpdate(2, "Change bed sheets", oct16.toEpochDay(), 2, "WEEKS", keepRepeat = false), update)
        } finally {
            r.scene.close()
        }
    }

    @Test fun deletingATaskSendsTheDeleteForThatTasksId() {
        val r = rig()
        try {
            r.select(oct16)
            r.editLink("1.4.6.1")
            r.click(770f, 797f) // Delete
            r.click(902f, 503f) // Delete (confirm): this window is taller, its text being longer
            val (path, body) = r.editor.sent.single()
            assertEquals("/v1/task/delete", path)
            assertEquals(LinkTaskDelete(2), Json.decodeFromString<LinkTaskDelete>(body).copy(madeAtMillis = 0))
        } finally {
            r.scene.close()
        }
    }

    @Test fun theUpcomingCardsRowsCanBeEditedToo() {
        val r = rig()
        try {
            r.editLink("1.4.7.1") // Water plants, the first upcoming item
            r.click(906f, 692f)
            assertEquals("/v1/task/update", r.editor.sent.single().first)
            assertEquals(1L, Json.decodeFromString<LinkTaskUpdate>(r.editor.sent.single().second).copy(madeAtMillis = 0).id)
        } finally {
            r.scene.close()
        }
    }

    @Test fun whenThePhoneSaysNoTheEditWindowStaysOpenWithItsReason() {
        val r = rig()
        try {
            r.editor.reply = EditResult.Failed("That event is no longer on the phone. Sync, then try again.", phoneRefused = true)
            r.editLink("1.4.6.1")
            r.click(906f, 729f)
            assertEquals(1, r.editor.sent.size)
            r.click(906f, 729f) // the window is still there: pressing Save again sends again
            assertEquals(2, r.editor.sent.size)
        } finally {
            r.scene.close()
        }
    }

    @Test fun renamingAnItemToAnotherThingsTitleIsNotAllowed() {
        val r = rig()
        try {
            r.store.upcoming.add(com.example.a2desktop.model.UpcomingItem("task-9", "Pick up parcel now", today, com.example.a2desktop.model.UpcomingKind.TASK, "One-time"))
            r.editLink("1.4.6.1")
            r.click(900f, 266f); r.type(" now") // the title becomes "Pick up parcel now", which is already on the list
            r.click(906f, 729f)
            assertTrue(r.editor.sent.isEmpty(), "two things with one title are not allowed")
        } finally {
            r.scene.close()
        }
    }

    @Test fun anItemWhoseTitleIsSharedCanStillBeEditedWithoutRenamingIt() {
        val r = rig()
        try {
            // The real phone had two Haircuts for a while: editing one of them (not its title) must still work.
            r.store.upcoming.add(com.example.a2desktop.model.UpcomingItem("task-9", "Pick up parcel", today, com.example.a2desktop.model.UpcomingKind.TASK, "One-time"))
            r.editLink("1.4.6.1")
            r.click(906f, 729f)
            assertEquals(1, r.editor.sent.size)
        } finally {
            r.scene.close()
        }
    }

    @Test fun renamingCarriesTheEmojiAndTheDoneTickToTheNewTitle() {
        val emojis = CalendarEmojiStore(null).also { it.setEmoji("Water plants", "💧") }
        val oct12 = LocalDate.of(2026, 10, 12)
        emojis.complete("Water plants", today, on = oct12) // ticked off on the 12th: it leaves the table, but stays in the Upcoming card
        val r = rig(emojis = emojis)
        try {
            r.editLink("1.4.7.1")
            r.click(900f, 349f); r.type(" now") // (the window of something already done is shorter: its "Mark as done" part is one line)
            r.click(906f, 647f)
            assertEquals("💧", emojis.emojiFor("Water plants now"), "the emoji comes along")
            assertEquals(oct12, emojis.doneOn("Water plants now", today), "and so does its done tick")
            assertNull(emojis.doneOn("Water plants", today), "which is no longer on the old title")
        } finally {
            r.scene.close()
        }
    }

    @Test fun renamingAnEventAlsoCarriesTheEmojiAndTheDoneTick() {
        val emojis = CalendarEmojiStore(null).also { it.setEmoji("Dentist", "🦷") }
        val oct12 = LocalDate.of(2026, 10, 12)
        emojis.complete("Dentist", oct17, on = oct12)
        val r = rig(emojis = emojis)
        try {
            r.editLink("1.4.7.3") // Dentist, the third upcoming item (after Water plants and Change bed sheets)
            r.click(900f, 288f); r.type(" x")
            r.click(906f, 708f)
            assertEquals("/v1/event/update", r.editor.sent.single().first)
            assertEquals("🦷", emojis.emojiFor("Dentist x"))
            assertEquals(oct12, emojis.doneOn("Dentist x", oct17))
            assertNull(emojis.doneOn("Dentist", oct17))
        } finally {
            r.scene.close()
        }
    }

    @Test fun savingWithoutChangingTheEmojiDoesNotCountAsUsingItAgain() {
        val emojis = CalendarEmojiStore(null).also { it.setEmoji("Pick up parcel", "📦") }
        val r = rig(emojis = emojis)
        try {
            r.editLink("1.4.6.1")
            r.click(906f, 729f)
            assertEquals(mapOf("📦" to 1), emojis.uses, "the emoji was only picked once")
        } finally {
            r.scene.close()
        }
    }

    // ---- the repeat symbol and the pen on the rows ----

    @Test fun whatRepeatsHasTheRepeatSymbolAfterItsTitleAndNothingElseHasOne() {
        val r = rig()
        try {
            // Today's table: Pick up parcel (an event, once) and Water plants (a task, "Weekly").
            r.reveal("1.4.6.2.1") // present: Water plants repeats
            r.devOn()
            assertTrue("1.4.6.2.1" in r.dev.groups, "Water plants repeats: it has the symbol")
            assertTrue("1.4.6.1.1" !in r.dev.groups, "Pick up parcel happens once: no symbol")
            r.devOff()
            // The Upcoming list: Water plants (Weekly), Change bed sheets (Every 2 weeks), Dentist (once), Team call (once), Pay rent (Every 1 month),
            // Renew gym membership ("One-time": a task done once is not a repeat).
            r.reveal("1.4.7.6")
            r.devOn()
            val withSymbol = (1..6).filter { "1.4.7.$it.1" in r.dev.groups }
            assertEquals(listOf(1, 2, 5), withSymbol, "the three that repeat, and not the one-offs")
            r.devOff()
        } finally {
            r.scene.close()
        }
    }

    @Test fun everyRowHasASmallPenForEditingAndNoEditWords() {
        val r = rig()
        try {
            r.reveal("1.4.6.2.2")
            r.devOn()
            assertTrue("1.4.6.1.2" in r.dev.groups && "1.4.6.2.2" in r.dev.groups, "a pen on each row of the day's table")
            r.devOff()
            r.reveal("1.4.7.6")
            r.devOn()
            assertTrue((1..6).all { "1.4.7.$it.2" in r.dev.groups }, "and on each row of the Upcoming list")
            val pen = r.dev.groups.getValue("1.4.7.1.2").bounds
            assertTrue(pen.width <= 28f && pen.height <= 20f, "it is a small pen (with its gap before it), not a word (${pen.width} x ${pen.height})")
            r.devOff()
        } finally {
            r.scene.close()
        }
    }

    // ---- "Mark as done" in the edit window: any day up to today ----
    // The window of Water plants (a task, not yet done) opened from the Upcoming list: the "Done on" box (680, 519), the chips Today (459, 598) and
    // Yesterday (549, 598), "Mark done" (652, 598). Once it is done the part is one line shorter: "Undo" is at (905, 553).

    @Test fun aTaskCanBeMarkedDoneYesterdayFromItsEditWindowAndTheTickCanBeTakenBack() {
        val emojis = CalendarEmojiStore(null)
        val r = rig(emojis = emojis)
        try {
            val yesterday = today.minusDays(1)
            r.editLink("1.4.7.1") // Water plants, due today
            r.click(549f, 598f) // Yesterday
            r.click(652f, 598f) // Mark done
            assertEquals(yesterday, emojis.doneOn("Water plants", today), "done yesterday")
            assertTrue(emojis.itemsOn(yesterday, r.store.events, r.store.upcoming, today).any { it.title == "Water plants" && it.done }, "and shown as done on that day")
            assertTrue(r.editor.sent.isEmpty(), "the phone is not asked anything")
            r.click(905f, 553f) // Undo
            assertNull(emojis.doneOn("Water plants", today), "taken back")
        } finally {
            r.scene.close()
        }
    }

    @Test fun anyEarlierDayCanBeTypedAndAFutureDayCannot() {
        val emojis = CalendarEmojiStore(null)
        val r = rig(emojis = emojis)
        try {
            r.editLink("1.4.7.1")
            r.click(900f, 519f); repeat(10) { r.typeBackspace() }; r.type("20/10/2026") // five days from now
            r.click(652f, 598f)
            assertTrue(emojis.done.isEmpty(), "a day that hasn't come yet can't be the day it was done")
            r.click(900f, 519f); repeat(10) { r.typeBackspace() }; r.type("03/10/2026") // twelve days back
            r.click(652f, 598f)
            assertEquals(LocalDate.of(2026, 10, 3), emojis.doneOn("Water plants", today), "an earlier day works")
        } finally {
            r.scene.close()
        }
    }

    @Test fun anEventOnTheListCanBeMarkedDoneOnAnEarlierDayToo() {
        val emojis = CalendarEmojiStore(null)
        val r = rig(emojis = emojis)
        try {
            r.editLink("1.4.7.3") // Dentist, an event on 17 October with a repeat box: Save is at (906, 753), so the chips are at y 659
            r.click(549f, 659f) // Yesterday
            r.click(652f, 659f) // Mark done
            assertEquals(today.minusDays(1), emojis.doneOn("Dentist", oct17))
        } finally {
            r.scene.close()
        }
    }

    @Test fun markingDoneInTheEditWindowSavesNothingElseAndTheWindowStaysOpen() {
        val emojis = CalendarEmojiStore(null)
        val r = rig(emojis = emojis)
        try {
            r.editLink("1.4.7.1")
            r.click(459f, 598f) // Today
            r.click(652f, 598f) // Mark done
            assertEquals(today, emojis.doneOn("Water plants", today))
            r.click(906f, 647f) // Save: the window is still open, now in its shorter, done form
            assertEquals("/v1/task/update", r.editor.sent.single().first, "Save still sends the (unchanged) task, as before")
        } finally {
            r.scene.close()
        }
    }

    // ---- smart days: which day of each period a repeating task lands on ----
    // The sample rota (15 October is "today"): work 15th (late), 16th (late), 17th (early); off 18th and 19th; work 20th (early), 21st (late);
    // off 22nd and 23rd; work 24th, 25th, 26th; off 27th and 28th. So "a day off" from the 16th is the 18th.

    /** Taps the repeat symbol of [row] on the Upcoming list, then the menu item for [pick]. */
    private fun Rig.pickDayFromTheSymbol(row: String, pick: DayPick) {
        click(reveal("$row.1").center)
        click(reveal("$row.1.${pick.ordinal}").center)
    }

    @Test fun tappingTheRepeatSymbolOffersEveryKindOfDayAndChoosingOneMovesTheTaskToIt() {
        val emojis = CalendarEmojiStore(null).also { it.setEmoji("Change bed sheets", "🛏️") }
        val r = rig(emojis = emojis)
        try {
            val before18 = r.bitmap().middleOf(oct18)
            r.click(r.reveal("1.4.7.2.1").center) // Change bed sheets: every 2 weeks, due 16 October
            r.devOn()
            val offered = DayPick.entries.filter { "1.4.7.2.1.${it.ordinal}" in r.dev.groups }
            assertEquals(DayPick.entries.toList(), offered, "every kind of day is in the list")
            r.devOff()
            r.click(r.reveal("1.4.7.2.1.${DayPick.DAY_OFF.ordinal}").center)
            assertEquals(DayPick.DAY_OFF, emojis.dayPickFor("Change bed sheets"))
            assertEquals(oct18, emojis.shownDue(r.store.upcoming.first { it.title == "Change bed sheets" }, today, r.store.workSchedule).date, "the first day off from the 16th")
            assertTrue(r.bitmap().middleOf(oct18) - before18 > 2000, "its emoji is now on the 18th in the month grid")
            assertTrue(emojis.emojisOn(oct16, r.store.events, r.store.upcoming, today, r.store.workSchedule).isEmpty(), "and no longer on the 16th")
            assertTrue(r.editor.sent.isEmpty(), "the phone is not asked anything: it keeps its own date")
            assertEquals(oct16, r.store.upcoming.first { it.title == "Change bed sheets" }.due)
        } finally {
            r.scene.close()
        }
    }

    @Test fun theUpcomingListIsOrderedByTheDayEachLandsOn() {
        val emojis = CalendarEmojiStore(null)
        val r = rig(emojis = emojis)
        try {
            r.pickDayFromTheSymbol("1.4.7.2", DayPick.DAY_OFF) // Change bed sheets moves from the 16th to the 18th
            // The list was Water plants (15th), Change bed sheets (16th), Dentist (17th) …: now Dentist is second and the sheets third.
            r.editLink("1.4.7.3")
            r.click(906f, 797f) // Save (the window of a task with a readable repeat)
            assertEquals("/v1/task/update", r.editor.sent.single().first)
            assertEquals(2L, Json.decodeFromString<LinkTaskUpdate>(r.editor.sent.single().second).copy(madeAtMillis = 0).id, "the third row is Change bed sheets (task 2)")
        } finally {
            r.scene.close()
        }
    }

    @Test fun aRepeatThatTheDesktopCannotReadIsTakenAsAWeekAndCanBeGivenASmartDayToo() {
        val emojis = CalendarEmojiStore(null)
        val r = rig(emojis = emojis)
        try {
            r.pickDayFromTheSymbol("1.4.7.1", DayPick.LAST_WORK_DAY) // Water plants, "Weekly", due 15 October
            assertEquals(DayPick.LAST_WORK_DAY, emojis.dayPickFor("Water plants"))
            // From the 15th (a week to move in) the last work day before days off is the 17th (the 18th and 19th are off).
            assertEquals(LocalDate.of(2026, 10, 17), emojis.shownDue(r.store.upcoming.first { it.title == "Water plants" }, today, r.store.workSchedule).date)
        } finally {
            r.scene.close()
        }
    }

    @Test fun onlyATaskOrEventWithARepeatOfAWeekOrMoreCanBeTappedForASmartDay() {
        val emojis = CalendarEmojiStore(null)
        val r = rig(emojis = emojis)
        try {
            val far = today.plusDays(30)
            r.store.upcoming.add(UpcomingItem("event-38", "Haircut", far, UpcomingKind.EVENT, "Every 14 days"))
            r.store.upcoming.add(UpcomingItem("task-9", "Stretch", far.plusDays(1), UpcomingKind.TASK, "Every 3 days"))
            r.store.upcoming.add(UpcomingItem("event-40", "Quick check", far.plusDays(2), UpcomingKind.EVENT, "Every 3 days"))
            // The three new rows are the last three of the list (7th, 8th and 9th): an event every 14 days, a task every 3 days, an event every 3 days.
            for (row in listOf("1.4.7.8", "1.4.7.9")) {
                r.click(r.reveal("$row.1").center)
                r.devOn()
                assertTrue(r.dev.groups.keys.none { it.startsWith("$row.1.") }, "$row: nothing opens (a smart day needs a repeat of a week or more)")
                r.devOff()
            }
            assertTrue(emojis.days.isEmpty())
            r.click(r.reveal("1.4.7.7.1").center) // the event that repeats every 14 days
            r.devOn()
            assertEquals(DayPick.entries.toList(), DayPick.entries.filter { "1.4.7.7.1.${it.ordinal}" in r.dev.groups }, "an event that repeats every two weeks is offered every kind of day")
            r.devOff()
        } finally {
            r.scene.close()
        }
    }

    @Test fun theAddFormOffersWhichDayOnlyForARepeatOfAWeekOrMoreAndKeepsTheChoice() {
        val emojis = CalendarEmojiStore(null)
        val r = rig(emojis = emojis)
        try {
            r.click(r.reveal("1.4.5").at(57f, 18f)) // Add task
            r.devOn()
            assertTrue("1.4.10.0" !in r.dev.groups, "no repeat: no smart day")
            r.devOff()
            r.click(680f, TASK_NAME); r.type("Water change")
            r.click(589f, TASK_REPEAT_CHIPS) // Days: "every 1 days" is too short to move anywhere
            r.devOn()
            assertTrue("1.4.10.0" !in r.dev.groups, "every day: no smart day")
            r.devOff()
            r.click(665f, 456f) // Weeks (the form got taller, so the chips moved up)
            r.devOn()
            assertTrue("1.4.10.0" in r.dev.groups, "every week: the choice is there")
            r.devOff()
            r.click(r.reveal("1.4.10.0").center) // the button
            r.click(r.reveal("1.4.10.1.${DayPick.DAY_OFF.ordinal}").center) // On a day off
            r.click(895f, ADD_TASK_REPEATING)
            val body = Json.decodeFromString<LinkTaskCreate>(r.editor.sent.single().second).copy(requestId = null)
            assertEquals(LinkTaskCreate("Water change", today.toEpochDay(), 1, "WEEKS"), body, "the phone gets the plain date and repeat, as always")
            assertEquals(DayPick.DAY_OFF, emojis.dayPickFor("Water change"), "the choice is kept here")
        } finally {
            r.scene.close()
        }
    }

    @Test fun theEditFormShowsTheChoiceKeepsItWhenSavedAndLeavesAnUnreadableRepeatAlone() {
        val emojis = CalendarEmojiStore(null).also { it.setDayPick("Change bed sheets", DayPick.FIRST_DAY_OFF) }
        val r = rig(emojis = emojis)
        try {
            r.select(oct18) // with a smart day the task is on the 18th (the first day off from the 16th), not the 16th
            r.editLink("1.4.6.1") // Change bed sheets: every 2 weeks
            r.click(r.reveal("1.4.10.0").center) // the button, showing the choice already made
            r.click(r.reveal("1.4.10.1.${DayPick.LAST_WORK_DAY.ordinal}").center)
            r.click(906f, 797f) // Save
            assertEquals(DayPick.LAST_WORK_DAY, emojis.dayPickFor("Change bed sheets"))
            val update = Json.decodeFromString<LinkTaskUpdate>(r.editor.sent.single().second).copy(madeAtMillis = 0)
            assertEquals(LinkTaskUpdate(2, "Change bed sheets", oct16.toEpochDay(), 2, "WEEKS", keepRepeat = false), update, "the phone's date and repeat are unchanged")
        } finally {
            r.scene.close()
        }
        val r2 = rig(emojis = CalendarEmojiStore(null))
        try {
            r2.editLink("1.4.6.2") // Water plants: "Weekly", which the PC can't read, so the form can't change its repeat — nor offer a smart day
            r2.devOn()
            assertTrue("1.4.10.0" !in r2.dev.groups)
        } finally {
            r2.scene.close()
        }
    }

    @Test fun savingAnEditWithoutTouchingWhichDayStoresNothing() {
        val emojis = CalendarEmojiStore(null)
        val r = rig(emojis = emojis)
        try {
            r.select(oct16)
            r.editLink("1.4.6.1")
            r.click(906f, 797f)
            assertTrue(emojis.days.isEmpty(), "the plain date is the default and is not written down")
        } finally {
            r.scene.close()
        }
    }

    @Test fun renamingATaskCarriesItsSmartDay() {
        val emojis = CalendarEmojiStore(null).also { it.setDayPick("Change bed sheets", DayPick.DAY_OFF) }
        val r = rig(emojis = emojis)
        try {
            r.select(oct18) // with a smart day the task is on the 18th, not the 16th
            r.editLink("1.4.6.1")
            r.click(900f, 199f); r.type(" now")
            r.click(906f, 797f)
            assertEquals(DayPick.DAY_OFF, emojis.dayPickFor("Change bed sheets now"))
            assertEquals(DayPick.ANY, emojis.dayPickFor("Change bed sheets"))
        } finally {
            r.scene.close()
        }
    }

    // ---- an event can have a smart day too, and something done once can be made to repeat ----
    // (Event 38, "Haircut", repeats every 14 days from Saturday 24 October, a work day: the rota's first day off after it is Tuesday 27th.)

    private val oct24 = LocalDate.of(2026, 10, 24)

    /** A Haircut event on the phone that repeats every 14 days, next due [on]; it joins the Upcoming list and the phone's events. */
    private fun Rig.addRepeatingHaircut(on: LocalDate) {
        store.events.add(com.example.a2desktop.model.CalendarEvent(38, on, "Haircut", null))
        store.upcoming.add(UpcomingItem("event-38", "Haircut", on, UpcomingKind.EVENT, "Every 14 days"))
    }

    private val EVENT_SAVE_REPEATING = 789f // Save of the event window once it has a repeat of a week or more: "Which day?" is in it
    private val EVENT_SAVE_WITH_DAY = 809f // the same once a day has been chosen: its two lines of words make it 40 px taller

    @Test fun aRepeatingEventMovesToItsSmartDayJustLikeATask() {
        val emojis = CalendarEmojiStore(null).also { it.setEmoji("Haircut", "✂️") }
        val r = rig(emojis = emojis)
        try {
            r.addRepeatingHaircut(oct24)
            // The Upcoming list goes by the day each lands on: Water plants, Change bed sheets, Dentist, Team call, Pay rent, the haircut (24th), Renew gym (27th).
            val row = "1.4.7.6"
            r.click(r.reveal("$row.1").center)
            r.devOn()
            assertEquals(DayPick.entries.toList(), DayPick.entries.filter { "$row.1.${it.ordinal}" in r.dev.groups }, "every kind of day is offered for an event too")
            r.devOff()
            r.click(r.reveal("$row.1.${DayPick.DAY_OFF.ordinal}").center)
            val item = r.store.upcoming.first { it.id == "event-38" }
            assertEquals(DayPick.DAY_OFF, emojis.dayPickFor("Haircut"))
            assertEquals(oct27, emojis.shownDue(item, today, r.store.workSchedule).date, "the first day off after the 24th")
            assertTrue(emojis.emojisOn(oct24, r.store.events, r.store.upcoming, today, r.store.workSchedule).isEmpty(), "not on the 24th any more")
            assertEquals(listOf("✂️"), emojis.emojisOn(oct27, r.store.events, r.store.upcoming, today, r.store.workSchedule).map { it.emoji }, "but on the 27th")
            assertEquals(oct24, item.due, "the phone's own date is untouched")
            assertTrue(r.editor.sent.isEmpty(), "and the phone is not asked anything")
        } finally {
            r.scene.close()
        }
    }

    @Test fun theEventEditFormOffersWhichDayForARepeatOfAWeekOrMoreAndKeepsTheChoice() {
        val emojis = CalendarEmojiStore(null)
        val r = rig(emojis = emojis)
        try {
            r.addRepeatingHaircut(oct24)
            r.editLink("1.4.7.6") // Haircut: every 14 days
            r.click(r.reveal("1.4.11.0").center) // "Which day?" button
            r.click(r.reveal("1.4.11.1.${DayPick.DAY_OFF.ordinal}").center)
            r.click(906f, EVENT_SAVE_WITH_DAY)
            val update = Json.decodeFromString<LinkEventUpdate>(r.editor.sent.single().second).copy(madeAtMillis = 0)
            assertEquals(LinkEventUpdate(38, "Haircut", oct24.toEpochDay(), -1, 14, keepRepeat = false), update, "the phone gets its plain date and repeat, as always")
            assertEquals(DayPick.DAY_OFF, emojis.dayPickFor("Haircut"), "the choice is kept here")
        } finally {
            r.scene.close()
        }
    }

    @Test fun theEventEditFormShowsWhichDayOnlyOnceTheRepeatIsAWeekOrMore() {
        val r = rig()
        try {
            r.select(oct17)
            r.editLink("1.4.6.1") // Dentist: event 2, 14:30, no repeat
            r.devOn()
            assertTrue("1.4.11.0" !in r.dev.groups, "no repeat: no smart day")
            r.devOff()
            r.click(680f, 484f); r.type("3") // every 3 days: too short to move anywhere
            r.devOn()
            assertTrue("1.4.11.0" !in r.dev.groups, "every 3 days: none")
            r.devOff()
            r.click(680f, 484f); r.typeBackspace(); r.type("14")
            r.devOn()
            assertTrue("1.4.11.0" in r.dev.groups, "every 14 days: the choice is there")
            r.devOff()
        } finally {
            r.scene.close()
        }
        val r2 = rig()
        try {
            r2.editLink("1.4.6.1") // Pick up parcel: event 5, which is not on the Upcoming list, so how it repeats is unknown
            r2.devOn()
            assertTrue("1.4.11.0" !in r2.dev.groups, "an event whose repeat the PC doesn't know can't be given a smart day")
        } finally {
            r2.scene.close()
        }
    }

    @Test fun anEventEditSavedWithoutTouchingWhichDayStoresNothingAndRenamingCarriesTheChoice() {
        val none = CalendarEmojiStore(null)
        val r = rig(emojis = none)
        try {
            r.addRepeatingHaircut(oct24)
            r.editLink("1.4.7.6")
            r.click(906f, EVENT_SAVE_REPEATING)
            assertEquals(1, r.editor.sent.size, "it was saved")
            assertTrue(none.days.isEmpty(), "the plain date is the default and is not written down")
        } finally {
            r.scene.close()
        }
        val emojis = CalendarEmojiStore(null).also { it.setDayPick("Haircut", DayPick.DAY_OFF) }
        val r2 = rig(emojis = emojis)
        try {
            r2.addRepeatingHaircut(oct24)
            r2.select(oct27) // with a smart day the haircut is on the 27th, not the 24th
            r2.editLink("1.4.6.1")
            r2.click(900f, 184f); r2.type(" now")
            r2.click(906f, EVENT_SAVE_WITH_DAY)
            assertEquals(DayPick.DAY_OFF, emojis.dayPickFor("Haircut now"))
            assertEquals(DayPick.ANY, emojis.dayPickFor("Haircut"))
        } finally {
            r2.scene.close()
        }
    }

    // "Make it repeat": the small pen on a row that is only a tick kept on this PC. The window's fields are found from where its contents start
    // (its developer-mode group, PC1.4.12) so they stay right however tall the window is: the repeat chips are 80 px down, the "Every how
    // many" box 150, "Next one due" 224. Repeat it is the last lit button.
    private fun Rect.daysChip() = Offset(left + 31f, top + 80f)
    private fun Rect.weeksChip() = Offset(left + 107f, top + 80f)
    private fun Rect.everyBox() = Offset(left + 130f, top + 150f)
    private fun Rect.nextDueBox() = Offset(left + 476f, top + 224f)

    /** Presses Repeat it. */
    private fun Rig.repeatIt() = click(litButtons().last())

    /** The Haircut ticked off on Monday 12 October with nothing of that name on the phone's list: what the real calendar has for Wednesday 4 November. */
    private fun haircutDoneOn12th() = CalendarEmojiStore(null).also { it.setEmoji("Haircut", "✂️"); it.complete("Haircut", null, on = oct12) }

    private fun Rig.sentEvent() = Json.decodeFromString<LinkEventCreate>(editor.sent.single().second).copy(requestId = null)

    @Test fun aRowThatIsOnlyATickHasAPenAndItOffersToMakeItRepeatEveryTwoWeeksOnADayOff() {
        val emojis = haircutDoneOn12th()
        val r = rig(emojis = emojis)
        try {
            r.select(oct12)
            assertEquals(1, r.tableRows())
            r.editLink("1.4.6.1") // the pen on the Haircut row
            r.devOn()
            assertTrue("1.4.12" in r.dev.groups, "the Make it repeat window opened, not the edit one")
            assertTrue("1.4.12.1.0" in r.dev.groups, "with its Which day? choice")
            r.devOff()
            r.repeatIt()
            assertEquals("/v1/event", r.editor.sent.single().first, "an all-day event that repeats is added on the phone (the phone lists a task only 3 days ahead, an event 30)")
            // Done on the 12th, every two weeks: the next one is due on the 26th, all day, every 14 days — the plain date and repeat the phone is given.
            assertEquals(LinkEventCreate("Haircut", oct26.toEpochDay(), -1, 14), r.sentEvent())
            assertEquals(DayPick.DAY_OFF, emojis.dayPickFor("Haircut"), "and it is to land on a day off, which is kept here")
            assertTrue(emojis.done.any { it.title == "Haircut" && it.doneEpochDay == oct12.toEpochDay() }, "the tick it was made from is left alone")
            // The 26th is a work day; the rota's first day off after it is the 27th.
            val added = UpcomingItem("event-9", "Haircut", oct26, UpcomingKind.EVENT, "Every 14 days")
            assertEquals(oct27, emojis.shownDue(added, today, r.store.workSchedule).date)
        } finally {
            r.scene.close()
        }
    }

    @Test fun theNewEventShowsOnTheCalendarOnItsDayOffAsSoonAsItIsAdded() {
        // The phone is out of reach (the change is kept and shown here at once): the haircut is on the Calendar on the 27th, not the plain 26th.
        val emojis = haircutDoneOn12th()
        val r = rig(emojis = emojis)
        try {
            r.editor.reply = EditResult.Failed("offline", unreachable = true)
            r.select(oct12); r.editLink("1.4.6.1")
            r.repeatIt()
            assertEquals("Haircut", r.store.upcoming.single { it.kind == UpcomingKind.EVENT && it.title == "Haircut" }.title, "it is on the Upcoming list at once")
            val schedule = r.store.workSchedule
            assertTrue(emojis.emojisOn(oct26, r.store.events, r.store.upcoming, today, schedule).isEmpty(), "not on the 26th, a work day")
            assertEquals(listOf("✂️"), emojis.emojisOn(oct27, r.store.events, r.store.upcoming, today, schedule).map { it.emoji }, "but on the 27th, the first day off")
            r.select(oct27)
            assertEquals(2, r.tableRows(), "and the day's table lists it, beside Renew gym membership which is due that day")
        } finally {
            r.scene.close()
        }
    }

    @Test fun theMakeItRepeatFormTakesAnotherRepeatAnotherDayAndAnotherChoice() {
        // Every 14 DAYS instead of 2 weeks (the form shrinks while the repeat is too short, then grows back): the same 14.
        val r1 = rig(emojis = haircutDoneOn12th())
        try {
            r1.select(oct12); r1.editLink("1.4.6.1")
            r1.click(r1.reveal("1.4.12").daysChip())
            r1.click(r1.reveal("1.4.12").everyBox()); r1.typeBackspace(); r1.type("14")
            r1.repeatIt()
            assertEquals(LinkEventCreate("Haircut", oct26.toEpochDay(), -1, 14), r1.sentEvent())
        } finally {
            r1.scene.close()
        }
        // Every 3 WEEKS: 21 days, due three weeks after the 12th.
        val r4 = rig(emojis = haircutDoneOn12th())
        try {
            r4.select(oct12); r4.editLink("1.4.6.1")
            r4.click(r4.reveal("1.4.12").everyBox()); r4.typeBackspace(); r4.type("3")
            r4.repeatIt()
            assertEquals(LinkEventCreate("Haircut", oct12.plusDays(21).toEpochDay(), -1, 21), r4.sentEvent())
        } finally {
            r4.scene.close()
        }
        // A day typed by hand wins over the one worked out.
        val r2 = rig(emojis = haircutDoneOn12th())
        try {
            r2.select(oct12); r2.editLink("1.4.6.1")
            r2.click(r2.reveal("1.4.12").nextDueBox()); repeat(10) { r2.typeBackspace() }; r2.type("28/10/2026")
            r2.repeatIt()
            assertEquals(LinkEventCreate("Haircut", oct28.toEpochDay(), -1, 14), r2.sentEvent())
        } finally {
            r2.scene.close()
        }
        // Another choice of day is kept — and one already kept for the title is what the form starts with.
        val e3 = haircutDoneOn12th().also { it.setDayPick("Haircut", DayPick.LAST_WORK_DAY) }
        val r3 = rig(emojis = e3)
        try {
            r3.select(oct12); r3.editLink("1.4.6.1")
            r3.click(r3.reveal("1.4.12.1.0").center)
            r3.click(r3.reveal("1.4.12.1.1.${DayPick.EARLY_START.ordinal}").center)
            r3.repeatIt()
            assertEquals(DayPick.EARLY_START, e3.dayPickFor("Haircut"))
        } finally {
            r3.scene.close()
        }
    }

    @Test fun aRepeatTooShortForASmartDayHasNoChoiceAndStoresNone() {
        val emojis = haircutDoneOn12th()
        val r = rig(emojis = emojis)
        try {
            r.select(oct12); r.editLink("1.4.6.1")
            r.click(r.reveal("1.4.12").daysChip())
            r.click(r.reveal("1.4.12").everyBox()); r.typeBackspace(); r.type("3")
            r.devOn()
            assertTrue("1.4.12.1.0" !in r.dev.groups, "every 3 days: no smart day")
            r.devOff()
            r.repeatIt()
            assertEquals(LinkEventCreate("Haircut", oct15.toEpochDay(), -1, 3), r.sentEvent())
            assertTrue(emojis.days.isEmpty())
        } finally {
            r.scene.close()
        }
    }

    /**
     * The buttons of the open window that are lit (enabled), found from the screen: the text of the lowest row of text in the theme's
     * primary colour, one spot per button (its middle). A button that is disabled is drawn dim, so it isn't found.
     */
    private fun Rig.litButtons(): List<Offset> {
        val bmp = bitmap()
        fun primary(c: Int) = Math.abs(c.red() - 174) < 28 && Math.abs(c.green() - 187) < 28 && (c and 0xFF) > 225
        var lowest = -1
        for (y in 300 until 850) for (x in 560 until 1010) if (primary(bmp.getColor(x, y))) lowest = maxOf(lowest, y)
        if (lowest < 0) return emptyList()
        val xs = mutableListOf<Int>()
        for (y in (lowest - 14)..lowest) for (x in 560 until 1010) if (primary(bmp.getColor(x, y))) xs += x
        val clusters = mutableListOf<MutableList<Int>>()
        xs.sorted().forEach { x -> if (clusters.isEmpty() || x - clusters.last().last() > 30) clusters += mutableListOf(x) else clusters.last() += x }
        return clusters.map { Offset(((it.first() + it.last()) / 2).toFloat(), lowest - 7f) }
    }

    @Test fun theMakeItRepeatFormStaysOpenWhenThePhoneSaysNo() {
        val emojis = haircutDoneOn12th()
        val r = rig(emojis = emojis)
        try {
            r.select(oct12); r.editLink("1.4.6.1")
            r.editor.reply = EditResult.Failed("The phone is full of haircuts.", phoneRefused = true)
            r.repeatIt()
            assertEquals(1, r.editor.sent.size)
            assertTrue(emojis.days.isEmpty(), "nothing is kept when the phone said no")
            r.click(r.litButtons().last()) // the window is still open (with the phone's reason): pressing Repeat it again sends again
            assertEquals(2, r.editor.sent.size)
        } finally {
            r.scene.close()
        }
    }

    @Test fun theMakeItRepeatFormRefusesATitleThePhoneAlreadyHas() {
        val emojis = haircutDoneOn12th()
        val r = rig(emojis = emojis)
        try {
            r.select(oct12); r.editLink("1.4.6.1")
            assertEquals(2, r.litButtons().size, "Cancel and Repeat it are both lit to start with")
            // The phone has a Haircut after all (it came in with a sync): it is one item with its own repeat, so nothing more is added.
            r.store.upcoming.add(UpcomingItem("event-9", "Haircut", oct26, UpcomingKind.EVENT, "Every 14 days"))
            assertEquals(1, r.litButtons().size, "only Cancel: Repeat it is dim")
            r.click(r.litButtons().single())
            assertTrue(r.editor.sent.isEmpty())
        } finally {
            r.scene.close()
        }
    }

    @Test fun aTickOfSomethingTheListAlreadyHasGetsNoMakeItRepeatPenAndAnEverydayRowIsUnchanged() {
        val emojis = CalendarEmojiStore(null).also { it.complete("Dentist", oct17, on = oct16) }
        val r = rig(emojis = emojis)
        try {
            r.select(oct16) // Change bed sheets (a task due then), and the Dentist tick: the phone's own list has a Dentist, so it repeats on its own
            r.devOn()
            assertTrue("1.4.6.1.2" in r.dev.groups, "the task has its pen")
            assertTrue("1.4.6.2.2" !in r.dev.groups, "the tick has none: there is nothing to make repeat")
            r.devOff()
        } finally {
            r.scene.close()
        }
    }


    // ---- the Tasks card ----

    @Test fun theTasksHeaderOpensAndClosesTheCombinedViewAndSelectingADayStillUpdatesIt() {
        val r = rig()
        try {
            r.devOn()
            val card = r.dev.groups.getValue("1.4").bounds // taller than the window now, so read where it is rather than scrolling to all of it
            r.devOff()
            assertEquals(2, r.tableRows(), "open to start with: today's two things")
            r.devOn()
            assertTrue("1.4.7" in r.dev.groups, "with the Upcoming list in the same card")
            r.devOff()
            r.click(Offset(card.left + 60f, card.top + 28f)) // the "Tasks" header
            r.devOn()
            assertTrue("1.4.6" !in r.dev.groups && "1.4.7" !in r.dev.groups, "closed: neither the day nor the Upcoming list shows")
            assertTrue("1.4" in r.dev.groups, "but the card is still there")
            r.devOff()
            r.click(Offset(card.left + 60f, card.top + 28f))
            r.select(oct17)
            assertEquals(1, r.tableRows(), "opened again, and the day picked in the meantime is the one shown")
        } finally {
            r.scene.close()
        }
    }

    @Test fun theUpcomingListInsideTheTasksCardIsTheSameChronologicalListWithTheSameTicks() {
        val emojis = CalendarEmojiStore(null)
        val r = rig(emojis = emojis)
        try {
            r.reveal("1.4.7") // the whole Upcoming list on screen (the Habits card above pushes it down)
            r.devOn()
            val rows = generateSequence(1) { it + 1 }.takeWhile { "1.4.7.$it" in r.dev.groups }.count()
            r.devOff()
            assertEquals(r.store.upcoming.size, rows, "every upcoming item has its row")
            r.click(r.reveal("1.4.7.1").let { Offset(it.left + 10f, it.center.y) }) // tick the first: Water plants, due today
            assertEquals(today, emojis.doneOn("Water plants", today), "ticking works as it did")
        } finally {
            r.scene.close()
        }
    }

    // ---- today's red neon frame ----

    @Test fun todaysCellHasARedNeonFrameWithAGlowAndNoOtherDayDoes() {
        // Today (Thursday 15 October) is the grid's fourth column in its third row; the 6 px gap to the cell before it (14 October) is just to the
        // left of its edge. The gap between 12 and 13 October has nothing special.
        val r = rig()
        val today = cellRect(this.today)
        val y = today.center.y
        val plainGapX = cellRect(LocalDate.of(2026, 10, 12)).right + 3f
        try {
            for (selectedAnotherDay in listOf(false, true)) {
                if (selectedAnotherDay) r.click(oct27Cell) // today need not be the selected day: the frame is for today
                val glow = r.pixelAt(Offset(today.left - 4f, y))
                // The frame is at 45% of its first brightness (it was half, then 10% lower): there the glow was red 145 over green 16 (a difference of about
                // 130); measured now it is 58 (65 at half), so this would not pass at the old strength.
                assertTrue(glow.red() - glow.green() in 52..63, "a red glow, at 45% strength, spills into the gap beside today's cell (red ${glow.red()}, green ${glow.green()}; another day selected: $selectedAnotherDay)")
                val plain = r.pixelAt(Offset(plainGapX, y))
                assertTrue(plain.red() - plain.green() < 15, "no glow in a gap away from today (red ${plain.red()}, green ${plain.green()})")
                // The tube is 3 px wide with a paler line in its middle; the cell's edge is not on a whole pixel, so look along its first few pixels
                // for the palest (the greenest) one.
                val tube = (0..4).map { r.pixelAt(Offset(today.left + it, y)) }.maxByOrNull { it.green() }!!
                // At full brightness the middle of the tube was red 255, green 210; at 45% it is a softer pale pink over the dark cell. Measured: green 110
                // or 111 (red 153 or 163, by whether today is the selected day); at half it was green 119 or 120 (red 165 or 176) — so green tells them apart.
                assertTrue(tube.red() in 145..170 && tube.green() in 104..115, "a pale line, at 45% brightness, runs along today's edge (red ${tube.red()}, green ${tube.green()})")
                val otherEdge = r.pixelAt(Offset(today.left - 8f, y)) // inside 14 October's cell, at its right edge
                assertTrue(otherEdge.green() < 100, "the day before has no such line (green ${otherEdge.green()})")
            }
        } finally {
            r.scene.close()
        }
    }

    // ---- the skin-care and supplements record lives on the phone ----

    private val oct12 = LocalDate.of(2026, 10, 12)

    @Test fun whenThePhoneKeepsTheRecordThePcShowsItAndAtickIsSentToThePhoneNotMadeHere() {
        val vitamins = VitaminStore(null)
        val skin = SkinCareStore(null)
        val phoneCare = CareState(vitamins = com.example.a2desktop.logic.VitaminState().setMark(today, com.example.a2desktop.logic.Supplement.CREATINE, true).ensureStarted(oct12))
        val sync = CareSync(null)
        val r = rig(skin = skin, vitamins = vitamins, phoneCare = phoneCare, careSync = sync)
        try {
            r.click(oct27Cell) // a few frames, so the first sync and the joining have happened
            r.select(today)
            assertEquals(true, vitamins.markOn(today, com.example.a2desktop.logic.Supplement.CREATINE), "the phone's tick is what the PC shows")
            assertTrue(sync.isMerged("Test Phone"), "this PC's earlier record was joined to the phone's, once")
            val joining = Json.decodeFromString<LinkCareRequest>(r.editor.sent.first { it.first == "/v1/care" }.second)
            assertTrue(joining.commands.isEmpty() && joining.mergeCare != null, "as one request carrying that earlier record")
            val before = r.editor.sent.count { it.first == "/v1/care" }
            r.click(r.reveal("1.8.1.3").let { Offset(it.center.x, it.center.y) }) // tick Vitamin K2
            val request = Json.decodeFromString<LinkCareRequest>(r.editor.sent.last { it.first == "/v1/care" }.second)
            assertEquals(before + 1, r.editor.sent.count { it.first == "/v1/care" }, "one request for the tick")
            assertEquals(listOf("vit.setMark"), request.commands.map { it.op })
            assertEquals("VITAMIN_K2", request.commands.single().supplement)
            assertEquals(true, request.commands.single().flag)
            assertEquals(true, vitamins.markOn(today, com.example.a2desktop.logic.Supplement.VITAMIN_K2), "shown once the phone has answered with its record")
        } finally {
            r.scene.close()
        }
    }

    @Test fun theLTheanineWithLemonBalmIsInTheEveningListAndItsTickGoesToThePhoneByName() {
        val vitamins = VitaminStore(null)
        val phoneCare = CareState(vitamins = com.example.a2desktop.logic.VitaminState().ensureStarted(oct12))
        val r = rig(vitamins = vitamins, phoneCare = phoneCare, careSync = CareSync(null))
        try {
            r.click(oct27Cell) // a few frames, so the first sync has happened
            r.select(today)
            r.devOn()
            assertTrue("1.8.1.10" in r.dev.groups, "it has its own number, 10, after the amino acids' 9: ${r.dev.groups.keys.filter { it.startsWith("1.8.1") }.sorted()}")
            r.devOff()
            r.click(r.reveal("1.8.1.10").center)
            val request = Json.decodeFromString<LinkCareRequest>(r.editor.sent.last { it.first == "/v1/care" }.second)
            assertEquals("vit.setMark", request.commands.single().op)
            assertEquals("L_THEANINE_LEMON_BALM", request.commands.single().supplement, "the phone is told its name")
            assertEquals(true, request.commands.single().flag)
            assertEquals(true, vitamins.markOn(today, com.example.a2desktop.logic.Supplement.L_THEANINE_LEMON_BALM), "shown once the phone has answered with its record")
        } finally {
            r.scene.close()
        }
    }

    @Test fun aTickOnASupplementTheOldPhoneDoesNotKnowStaysTickedAndCanBeTakenOffAgain() {
        val vitamins = VitaminStore(null)
        val phoneCare = CareState(vitamins = com.example.a2desktop.logic.VitaminState().ensureStarted(oct12))
        val r = rig(vitamins = vitamins, phoneCare = phoneCare, careSync = CareSync(null))
        try {
            r.editor.oldPhone = true
            r.click(oct27Cell) // a few frames, so the first sync has happened
            r.select(today)
            val calm = com.example.a2desktop.logic.Supplement.L_THEANINE_LEMON_BALM
            val empty = r.pixelAt(r.reveal("1.8.1.10").fill())
            assertTrue(empty.green() - empty.red() < 30, "not ticked to start with")
            r.click(r.reveal("1.8.1.10").center)
            assertEquals(true, vitamins.markOn(today, calm), "ticked, though the phone's answer left it out")
            val ticked = r.pixelAt(r.reveal("1.8.1.10").fill())
            assertTrue(ticked.green() - ticked.red() > 60, "and drawn solid green")
            assertNull(phoneCare.vitamins.marks[today]?.get(calm), "the old phone really doesn't hold it")
            // The phone's record arriving again (a sync) doesn't take it away.
            r.link.syncNow()
            repeat(4) { r.time += 50_000_000; r.scene.render(r.time) }
            assertEquals(true, vitamins.markOn(today, calm), "still ticked after the phone's record came round again")
            // Tapping it again takes it off.
            r.click(r.reveal("1.8.1.10").center)
            assertNull(vitamins.markOn(today, calm), "taken off")
            val cleared = r.pixelAt(r.reveal("1.8.1.10").fill())
            assertTrue(cleared.green() - cleared.red() < 30, "and an empty ring again")
            assertTrue(vitamins.held.isEmpty(), "nothing is left held")
            // The other supplements are the phone's as before: a tick on creatine is held by the phone, not here.
            r.click(r.reveal("1.8.1.4").center)
            assertEquals(true, vitamins.markOn(today, com.example.a2desktop.logic.Supplement.CREATINE))
            assertTrue(vitamins.held.isEmpty(), "creatine is the phone's: nothing held here")
        } finally {
            r.scene.close()
        }
    }

    @Test fun anEveningTickIsSentToThePhoneToo() {
        val skin = SkinCareStore(null)
        val r = rig(skin = skin, phoneCare = CareState(), careSync = CareSync(null))
        try {
            r.click(oct27Cell)
            r.select(today)
            r.click(r.reveal("1.5.5").let { Offset(it.center.x, it.center.y) }) // tonight's tick in the Skin care card
            val request = Json.decodeFromString<LinkCareRequest>(r.editor.sent.last { it.first == "/v1/care" }.second)
            assertEquals("skin.setEvening", request.commands.single().op)
            assertEquals(true, request.commands.single().flag)
            assertTrue(skin.log.doneOn(today), "and the evening is ticked once the phone's record comes back")
        } finally {
            r.scene.close()
        }
    }

    @Test fun anOlderPhoneAppThatKeepsNoRecordLeavesTheCardsKeepingTheirOwnAsBefore() {
        val vitamins = VitaminStore(null)
        val r = rig(vitamins = vitamins) // no care in the snapshot
        try {
            r.click(oct27Cell)
            r.select(today)
            r.click(r.reveal("1.8.1.3").let { Offset(it.center.x, it.center.y) })
            assertEquals(true, vitamins.markOn(today, com.example.a2desktop.logic.Supplement.VITAMIN_K2), "made here at once")
            assertTrue(r.editor.sent.none { it.first == "/v1/care" }, "and nothing was sent")
        } finally {
            r.scene.close()
        }
    }

    @Test fun aSkinCareAndASupplementTickMadeWhileThePhoneIsOutOfReachAreStillMadeAndGoWhenItIsBack() {
        val vitamins = VitaminStore(null)
        val skin = SkinCareStore(null)
        val sync = CareSync(null).also { it.markMerged("Test Phone") } // already joined, so only the ticks are sent
        val r = rig(skin = skin, vitamins = vitamins, phoneCare = CareState(), careSync = sync)
        try {
            r.click(oct27Cell) // a few frames, so the first sync has happened
            r.select(today)
            r.editor.careReply = EditResult.Failed("Couldn't reach the phone.", unreachable = true) // the phone drops off the network
            r.click(r.reveal("1.5.5").center) // tonight's skin-care tick
            assertTrue(skin.log.doneOn(today), "the skin-care tick is made, though the phone can't be reached")
            r.click(r.reveal("1.8.1.3").center) // Vitamin K2
            assertEquals(true, vitamins.markOn(today, com.example.a2desktop.logic.Supplement.VITAMIN_K2), "and so is the supplement tick")
            assertEquals(2, r.link.pendingCount, "both wait for the phone")
            val skinTick = r.pixelAt(r.reveal("1.5.5").fill())
            assertTrue(skinTick.green() - skinTick.red() > 60, "the tick is drawn solid green")
            // The phone is back.
            r.editor.careReply = null
            r.link.syncNow()
            repeat(4) { r.time += 50_000_000; r.scene.render(r.time) }
            assertEquals(0, r.link.pendingCount, "delivered")
            assertTrue(skin.log.doneOn(today), "still ticked, now the phone's record")
            assertEquals(true, vitamins.markOn(today, com.example.a2desktop.logic.Supplement.VITAMIN_K2))
            // And tapping a tick off while out of reach works too.
            r.editor.careReply = EditResult.Failed("Couldn't reach the phone.", unreachable = true)
            r.link.syncNow()
            r.click(r.reveal("1.5.5").center)
            assertFalse(skin.log.doneOn(today), "taken off while out of reach")
        } finally {
            r.scene.close()
        }
    }

    @Test fun whenTheCardCannotReachThePhoneTheTickIsNotMadeAndTheCardSaysWhy() {
        val vitamins = VitaminStore(null)
        val sync = CareSync(null).also { it.markMerged("Test Phone") } // already joined, so only the tick is sent
        val r = rig(vitamins = vitamins, phoneCare = CareState(), careSync = sync)
        try {
            r.click(oct27Cell)
            r.select(today)
            r.editor.careReply = EditResult.Failed("The phone couldn't be reached — nothing was changed.")
            r.click(r.reveal("1.8.1.3").let { Offset(it.center.x, it.center.y) })
            assertNull(vitamins.markOn(today, com.example.a2desktop.logic.Supplement.VITAMIN_K2), "nothing is ticked that the phone didn't take")
            assertEquals("The phone couldn't be reached — nothing was changed.", vitamins.problem)
        } finally {
            r.scene.close()
        }
    }

    // ---- where things are ----

    @Test fun theMonthGridFillsTheLeftColumnNowThatNothingSitsUnderIt() {
        val dev = DevMode().also { it.enabled = true }
        val r = rig(dev = dev)
        try {
            repeat(4) { r.time += 50_000_000; r.scene.render(r.time) }
            val grid = r.dev.groups.getValue("1.2").bounds
            assertEquals(836f, grid.bottom, 1f, "the grid runs down to the page's bottom margin (860 - 24)")
        } finally {
            r.scene.close()
        }
    }

    @Test fun theVitaminsCardChoicesChangeTheSoftgelDaysAndTheOrganCapsulesAndStayInRange() {
        val vitamins = VitaminStore(null)
        val r = rig(vitamins = vitamins)
        try {
            r.reveal("1.8.1")
            r.scrollPage(6f) // a little further, so "More" under the list is well inside the page
            val list = r.reveal("1.8.1")
            r.click(Offset(list.left + 28f, list.bottom + 22f)) // "More"
            assertEquals(3, vitamins.settings.organs, "starts at half the label serving")
            fun plus(id: String) = r.click(r.reveal(id).let { Offset(it.right - 13f, it.center.y) })
            fun minus(id: String) = r.click(r.reveal(id).let { Offset(it.right - 63f, it.center.y) })
            plus("1.8.4.2")
            plus("1.8.4.2")
            assertEquals(5, vitamins.settings.organs)
            minus("1.8.4.2")
            assertEquals(4, vitamins.settings.organs)
            repeat(5) { plus("1.8.4.2") }
            assertEquals(6, vitamins.settings.organs, "never beyond the label's six")
            assertEquals(3, vitamins.settings.softgelDaysPerWeek, "starts at three a week")
            plus("1.8.4.1")
            assertEquals(3, vitamins.settings.softgelDaysPerWeek, "never beyond three a week")
            minus("1.8.4.1")
            assertEquals(2, vitamins.settings.softgelDaysPerWeek)
            repeat(3) { minus("1.8.4.1") }
            assertEquals(1, vitamins.settings.softgelDaysPerWeek, "never below one")
            assertTrue(r.editor.sent.isEmpty(), "vitamins never go to the phone")
        } finally {
            r.scene.close()
        }
    }

    // ---- the supplements card's ticks (its dev numbers are 1.8.1.n: 4 is creatine) ----

    private val creatineTick = "1.8.1.4"

    @Test fun tickingASupplementInTheCardRecordsItOnThatDayAndTappingTheTickAgainTakesItBack() {
        val vitamins = VitaminStore(null)
        val r = rig(vitamins = vitamins)
        try {
            assertTrue(vitamins.marks.isEmpty())
            val before = r.pixelAt(r.reveal(creatineTick).fill())
            assertTrue(before.green() - before.red() < 30, "not ticked: an empty ring (ticking began today, so today isn't counted as taken)")
            r.click(r.reveal(creatineTick).center)
            assertEquals(mapOf(today to mapOf(Supplement.CREATINE to true)), vitamins.marks, "ticked: recorded for today and only creatine")
            val ticked = r.pixelAt(r.reveal(creatineTick).fill())
            assertTrue(ticked.green() - ticked.red() > 60, "ticked: solid green")
            r.click(r.reveal(creatineTick).center)
            assertTrue(vitamins.marks.isEmpty(), "taken back: nothing is left on the day")
            assertTrue(r.editor.sent.isEmpty(), "supplements never go to the phone")
        } finally {
            r.scene.close()
        }
    }

    @Test fun aDayStillToComeHasNoTicksAndNothingIsRecordedForIt() {
        val vitamins = VitaminStore(null)
        val r = rig(vitamins = vitamins)
        try {
            r.click(oct27Cell)
            r.reveal("1.8.1") // the Tasks card above it is tall now: scroll the Supplements list into view
            r.devOn()
            assertTrue(r.dev.groups.keys.none { it.startsWith("1.8.1.") }, "no tick on a day to come: ${r.dev.groups.keys.filter { it.startsWith("1.8.1") }}")
            assertTrue("1.8.1" in r.dev.groups, "though its supplements are listed")
            assertTrue(vitamins.marks.isEmpty())
        } finally {
            r.scene.close()
        }
    }

    @Test fun aDayFromBeforeTickingBeganCanBeTickedLate() {
        val vitamins = VitaminStore(null)
        val r = rig(vitamins = vitamins)
        try {
            val oct12 = LocalDate.of(2026, 10, 12)
            r.click(oct12Cell)
            r.click(r.reveal(creatineTick).center) // presumed taken until now; ticking makes it a real tick
            assertEquals(mapOf(oct12 to mapOf(Supplement.CREATINE to true)), vitamins.marks)
        } finally {
            r.scene.close()
        }
    }

    @Test fun anUntickedEarlierDayAfterTickingBeganShowsAnEmptyTickAndCanBeTickedLate() {
        val oct12 = LocalDate.of(2026, 10, 12)
        val file = File(dir, "vitamins.json")
        file.writeText("""{"trackedFrom":${LocalDate.of(2026, 10, 5).toEpochDay()},"tickFrom":${LocalDate.of(2026, 10, 5).toEpochDay()}}""")
        val vitamins = VitaminStore(file)
        val r = rig(vitamins = vitamins)
        try {
            r.click(oct12Cell)
            val missed = r.pixelAt(r.reveal(creatineTick).fill())
            assertTrue(missed.green() - missed.red() < 30, "never ticked once ticking had begun: not taken, an empty ring")
            r.click(r.reveal(creatineTick).center)
            assertEquals(true, vitamins.markOn(oct12, Supplement.CREATINE))
            val late = r.pixelAt(r.reveal(creatineTick).fill())
            assertTrue(late.green() - late.red() > 60, "ticked late: solid green")
            assertEquals(null, vitamins.markOn(today, Supplement.CREATINE), "today is untouched")
        } finally {
            r.scene.close()
        }
    }

    // ---- emoji on events and tasks: the forms, the Upcoming card and the Calendar's days ----
    // The task form (1360 x 860). The emoji row is at the top: the emoji box (x 472), the ＋ button (545) and the quick emoji after it (the first
    // at 590), all at y 330; then the name (y 404), the repeat chips (491) and "Add task" (895, 622; 659 with a repeat's extra box).
    // The picker: its search box (680, 232), the first emoji of the list or the search result (483, 310), "No emoji" (785, 689).
    private val EMOJI_BOX = Offset(472f, 334f)
    private val PLUS = Offset(545f, 330f)
    private val QUICK_FIRST = Offset(590f, 330f)
    private val TASK_NAME = 404f
    private val ADD_TASK = 622f
    private val TASK_REPEAT_CHIPS = 491f
    private val ADD_TASK_REPEATING = 698f // a repeat of a week or more has the "Which day?" part too: 39 px lower than it was
    private val PICKER_SEARCH = Offset(680f, 232f)
    private val PICKER_FIRST = Offset(483f, 310f)
    private val PICKER_TYPE_BOX = Offset(680f, 612f)
    private val PICKER_NO_EMOJI = Offset(785f, 689f)

    /** Opens the picker with the ＋, searches for [word] and picks the first emoji found. */
    private fun Rig.pickBySearch(word: String) {
        click(PLUS)
        click(PICKER_SEARCH); type(word)
        click(PICKER_FIRST)
    }

    @Test fun anEmojiPickedWithThePlusIsKeptForThatTitleAndTheTitleSentToThePhoneIsUnchanged() {
        val emojis = CalendarEmojiStore(null)
        val r = rig(emojis = emojis)
        try {
            r.click(r.reveal("1.4.5").at(57f, 18f)) // Add task
            r.pickBySearch("scissors") // ＋, then search: ✂️ is the first found
            r.click(680f, TASK_NAME); r.type("Haircut")
            r.click(895f, ADD_TASK)
            val (path, body) = r.editor.sent.single()
            assertEquals("/v1/task", path)
            assertEquals(LinkTaskCreate("Haircut", today.toEpochDay(), 1, "NONE"), Json.decodeFromString<LinkTaskCreate>(body).copy(requestId = null), "no emoji goes to the phone")
            assertEquals("✂️", emojis.emojiFor("Haircut"), "the emoji is kept here, for that title")
            assertEquals(mapOf("✂️" to 1), emojis.uses)
            assertFalse(emojis.isDone("Haircut", today), "today's event isn't done yet")
        } finally {
            r.scene.close()
        }
    }

    @Test fun thePickerCanBeBrowsedAsWellAsSearched() {
        val emojis = CalendarEmojiStore(null).also { it.setEmoji("Dentist", "🦷") } // one frequent emoji, so the groups start at a known place
        val r = rig(emojis = emojis)
        try {
            r.click(r.reveal("1.4.5").at(57f, 18f))
            r.click(PLUS)
            r.click(483f, 517f) // the first emoji of "Health and body": ✂️
            r.click(680f, TASK_NAME); r.type("Trim")
            r.click(895f, ADD_TASK)
            assertEquals("✂️", emojis.emojiFor("Trim"))
        } finally {
            r.scene.close()
        }
    }

    @Test fun anEmojiCanStillBeTypedStraightIntoTheEmojiBox() {
        val emojis = CalendarEmojiStore(null)
        val r = rig(emojis = emojis)
        try {
            r.click(r.reveal("1.4.5").at(57f, 18f))
            r.click(EMOJI_BOX); r.type("a⭐") // a letter is ignored, the emoji is taken
            r.click(680f, TASK_NAME); r.type("Favourite")
            r.click(895f, ADD_TASK)
            assertEquals("⭐", emojis.emojiFor("Favourite"))
            assertEquals("Favourite", Json.decodeFromString<LinkTaskCreate>(r.editor.sent.single().second).copy(requestId = null).name, "and the phone still gets the plain title")
        } finally {
            r.scene.close()
        }
    }

    @Test fun anEmojiCanStillBeTypedOrPastedIntoThePickerToo() {
        val emojis = CalendarEmojiStore(null)
        val r = rig(emojis = emojis)
        try {
            r.click(r.reveal("1.4.5").at(57f, 18f))
            r.click(PLUS)
            r.click(PICKER_TYPE_BOX); r.type("⭐")
            repeat(12) { r.time += 50_000_000; r.scene.render(r.time) } // the picker closes by itself: let it go before clicking on
            r.click(680f, TASK_NAME); r.type("Favourite")
            r.click(895f, ADD_TASK)
            assertEquals("⭐", emojis.emojiFor("Favourite"))
        } finally {
            r.scene.close()
        }
    }

    @Test fun theMostUsedEmojiAreOneTapAwayInTheFormAndTappingTheChosenOneTakesItBack() {
        val emojis = CalendarEmojiStore(null).also { it.setEmoji("Haircut", "✂️") }
        val r = rig(emojis = emojis)
        try {
            r.click(r.reveal("1.4.5").at(57f, 18f))
            r.click(QUICK_FIRST) // ✂️ is the most used: the title is empty, so "Haircut" is filled in
            r.click(QUICK_FIRST) // and tapping it again takes the emoji back (the title stays)
            r.click(895f, ADD_TASK)
            assertEquals("Haircut", Json.decodeFromString<LinkTaskCreate>(r.editor.sent.single().second).copy(requestId = null).name)
            assertNull(emojis.emojiFor("Haircut"), "no emoji was kept: it was taken back before adding")
        } finally {
            r.scene.close()
        }
    }

    @Test fun aFrequentEmojiFillsInTheTitleItWasUsedFor() {
        val emojis = CalendarEmojiStore(null).also { it.setEmoji("Haircut", "✂️") }
        val r = rig(emojis = emojis)
        try {
            r.click(r.reveal("1.4.5").at(57f, 18f)) // Add task
            r.click(QUICK_FIRST) // the frequent ✂️: the title is empty, so "Haircut" is filled in
            r.click(895f, ADD_TASK) // no typing
            val (_, body) = r.editor.sent.single()
            assertEquals("Haircut", Json.decodeFromString<LinkTaskCreate>(body).copy(requestId = null).name)
            assertEquals(mapOf("✂️" to 2), emojis.uses, "and it counts as used again")
        } finally {
            r.scene.close()
        }
    }


    @Test fun anEventAddedOnAnEarlierDayIsMarkedDoneOnThatDay() {
        val oct12 = LocalDate.of(2026, 10, 12)
        val emojis = CalendarEmojiStore(null)
        val r = rig(emojis = emojis)
        try {
            r.click(oct12Cell)
            r.click(r.reveal("1.4.5").at(57f, 18f)) // Add task on the 12th: "already done" starts ticked for an earlier day
            r.pickBySearch("scissors")
            r.click(680f, TASK_NAME); r.type("Optician")
            r.click(895f, ADD_TASK)
            assertEquals(oct12, emojis.doneOn("Optician", oct12), "ticked off as done on the 12th")
            assertEquals("✂️", emojis.emojiFor("Optician"), "with the emoji picked")
        } finally {
            r.scene.close()
        }
    }

    @Test fun tickingAnUpcomingItemMarksItDoneOnTheSelectedDayAndTickingAgainTakesItBack() {
        val emojis = CalendarEmojiStore(null)
        val r = rig(emojis = emojis)
        try {
            fun tick() = r.click(r.reveal("1.4.7.1").let { Offset(it.left + 10f, it.center.y) }) // the first item: "Water plants", due today
            tick()
            assertEquals(today, emojis.doneOn("Water plants", today), "done on the selected day, which starts as today")
            tick()
            assertNull(emojis.doneOn("Water plants", today), "ticked again: taken back")
            r.click(oct12Cell) // an earlier day
            tick()
            assertEquals(LocalDate.of(2026, 10, 12), emojis.doneOn("Water plants", today), "done on the 12th, the selected day")
            tick()
            r.click(oct27Cell) // a day still to come
            tick()
            assertEquals(today, emojis.doneOn("Water plants", today), "a day to come can't be the day it was done: today")
            assertTrue(r.editor.sent.isEmpty(), "the phone isn't told")
        } finally {
            r.scene.close()
        }
    }


    @Test fun tickingARepeatingItemMovesItsShadowToCountFromTheDayItWasDone() {
        val oct29 = LocalDate.of(2026, 10, 29)
        val oct30 = LocalDate.of(2026, 10, 30)
        val emojis = CalendarEmojiStore(null).also { it.setEmoji("Change bed sheets", "🛏️") }
        val r = rig(emojis = emojis)
        try {
            // "Change bed sheets" is due tomorrow (16 October) and repeats every 2 weeks: its shadows are on the 30th, then 13 November.
            val before29 = r.bitmap().middleOf(oct29)
            val before30 = r.bitmap().middleOf(oct30)
            assertTrue(emojis.emojisOn(oct30, r.store.events, r.store.upcoming, today).isNotEmpty(), "a shadow on the 30th to start with")
            r.click(r.reveal("1.4.7.2").let { Offset(it.left + 10f, it.center.y) }) // tick it: done today, the 15th
            assertEquals(today, emojis.doneOn("Change bed sheets", oct16))
            val after = r.bitmap()
            // Two weeks after the 15th is the 29th: the shadow arrives there and leaves the 30th.
            assertEquals(listOf("🛏️"), emojis.emojisOn(oct29, r.store.events, r.store.upcoming, today).map { it.emoji })
            assertTrue(emojis.emojisOn(oct30, r.store.events, r.store.upcoming, today).isEmpty(), "none on the 30th any more")
            assertTrue(after.middleOf(oct29) - before29 > 2000, "drawn on the 29th in the month grid (+${after.middleOf(oct29) - before29})")
            assertTrue(before30 - after.middleOf(oct30) > 2000, "and gone from the 30th (-${before30 - after.middleOf(oct30)})")
            r.click(r.reveal("1.4.7.2").let { Offset(it.left + 10f, it.center.y) }) // tick it again: taken back
            assertTrue(emojis.emojisOn(oct30, r.store.events, r.store.upcoming, today).isNotEmpty(), "the old count comes back")
            assertTrue(r.editor.sent.isEmpty(), "the phone isn't told")
        } finally {
            r.scene.close()
        }
    }

    @Test fun tappingAnUpcomingItemsEmojiChoosesItsEmojiFromThePicker() {
        val emojis = CalendarEmojiStore(null).also { it.setEmoji("Haircut", "✂️") } // so ✂️ is frequent
        val r = rig(emojis = emojis)
        try {
            val row = r.reveal("1.4.7.1")
            r.click(Offset(row.left + 40f, row.center.y)) // the emoji slot of "Water plants"
            r.click(PICKER_SEARCH); r.type("bed")
            r.click(PICKER_FIRST) // 🛏️, the first emoji found
            assertEquals("🛏️", emojis.emojiFor("Water plants"))
            val again = r.reveal("1.4.7.1")
            r.click(Offset(again.left + 40f, again.center.y))
            r.click(PICKER_NO_EMOJI) // now it has one, "No emoji" takes it back
            assertNull(emojis.emojiFor("Water plants"), "No emoji clears it")
        } finally {
            r.scene.close()
        }
    }

    // The grid is the middle of three columns: 24 + LEFT_COLUMN_DP (Skin care, Supplements) + 24 in, and 24 + 410 (Habits, Tasks) + 24 from the right edge.
    private val gridLeft = 24f + LEFT_COLUMN_DP + 24f
    private val cellWidth = ((1360 - 24 - LEFT_COLUMN_DP - 24 - 24 - 410 - 24) - 6 * 6) / 7f

    /** The height of a month-grid cell at 1360 x 860: from 180 (under the 64 px top bar and the month's heading) down to 836, six rows, 6 apart. */
    private val cellHeight = (836f - 180f - 5 * 6f) / 6f

    /** Where [date] is in October 2026's month grid (it starts on Monday 28 September; cells are about 65 wide and 104 tall, 6 apart). */
    private fun cellRect(date: LocalDate): Rect {
        val days = java.time.temporal.ChronoUnit.DAYS.between(LocalDate.of(2026, 9, 28), date).toInt()
        val left = gridLeft + (days % 7) * (cellWidth + 6f)
        val top = 180f + (days / 7) * (cellHeight + 6f)
        return Rect(left, top, left + cellWidth, top + cellHeight)
    }

    /** How much light is in the middle of [date]'s cell, where the emoji go (the sum of each pixel's brightest channel). */
    private fun org.jetbrains.skia.Bitmap.middleOf(date: LocalDate): Long {
        val cell = cellRect(date)
        var total = 0L
        // (From 50 to 64 px down: below the work times and above the Zn / AA marks, which come back once the emoji is gone, so only the emoji changes.)
        for (x in (cell.left + 6f).toInt() until (cell.right - 6f).toInt()) for (y in (cell.top + 50f).toInt() until (cell.top + 64f).toInt()) {
            val c = getColor(x, y)
            total += maxOf(c.red(), c.green(), c and 0xFF)
        }
        return total
    }

    @Test fun anEmojiShowsInTheMiddleOfItsDaysCellShadedUntilDoneAndThenInFullColourOnTheDayItWasDone() {
        val oct17 = LocalDate.of(2026, 10, 17) // the sample "Dentist" event
        val oct13 = LocalDate.of(2026, 10, 13)
        val emojis = CalendarEmojiStore(null)
        val r = rig(emojis = emojis)
        try {
            val none17 = r.bitmap().middleOf(oct17)
            val none13 = r.bitmap().middleOf(oct13)
            emojis.setEmoji("Dentist", "🦷")
            val shaded = r.bitmap().middleOf(oct17) - none17
            assertTrue(shaded > 2000, "a shaded emoji appears on the 17th (+$shaded)")
            emojis.complete("Dentist", oct17, on = oct17)
            val full = r.bitmap().middleOf(oct17) - none17
            assertTrue(full > 2 * shaded, "done: much stronger than shaded (shaded +$shaded, full +$full)")
            emojis.complete("Dentist", oct17, on = oct13) // done early, on the 13th
            val bitmap = r.bitmap()
            assertTrue(kotlin.math.abs(bitmap.middleOf(oct17) - none17) < shaded / 5, "gone from its due date")
            assertTrue(bitmap.middleOf(oct13) - none13 > shaded, "and in full colour on the day it was done (+${bitmap.middleOf(oct13) - none13})")
        } finally {
            r.scene.close()
        }
    }

    @Test fun theSkinCareAndVitaminsCardsOnTheLeftAreShortLists() {
        val dev = DevMode().also { it.enabled = true }
        val r = rig(dev = dev, height = 2400) // tall, so both cards are fully in view
        try {
            repeat(4) { r.time += 50_000_000; r.scene.render(r.time) }
            val skin = r.dev.groups.getValue("1.5").bounds
            val vitamins = r.dev.groups.getValue("1.8").bounds
            assertTrue(skin.height < 260f, "the skin-care card is a short list (${skin.height} dp)")
            assertTrue(vitamins.height < 360f, "the supplements card is short (${vitamins.height} dp)")
            assertTrue(skin.right < 400f && vitamins.right < 400f, "both are in the left-hand column (${skin.right}, ${vitamins.right})")
            assertTrue(skin.bottom < vitamins.top, "the Skin care card first, the Supplements card under it")
            assertTrue(skin.top < 100f, "the Skin care card is at the very top of the column, just under the top bar (${skin.top})")
            assertTrue(listOf("1.5.1", "1.5.2", "1.5.3", "1.5.4", "1.8.2", "1.8.3", "1.8.4", "1.8.5").none { it in r.dev.groups }, "the long versions are gone from view")
        } finally {
            r.scene.close()
        }
    }
}
