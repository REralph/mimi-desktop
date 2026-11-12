package com.example.a2desktop

import com.example.a2desktop.data.DesktopStore
import com.example.a2desktop.data.SkinCareStore
import com.example.a2desktop.link.LinkSnapshot
import com.example.a2desktop.link.LinkWorkShift
import com.example.a2desktop.logic.EveningKind
import com.example.a2desktop.logic.Product
import com.example.a2desktop.logic.WorkSchedule
import com.example.a2desktop.logic.buildWorkSchedule
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What is remembered about the skin-care routine, and how the phone's work times reach the Calendar. */
class SkinCareStoreAndWorkTest {
    private val dir: File = Files.createTempDirectory("a2-skin").toFile()
    private val file = File(dir, "skincare.json")
    private val oct20 = LocalDate.of(2026, 10, 20)

    @AfterTest fun cleanUp() {
        dir.deleteRecursively()
    }

    // ---- the skin-care log ----

    @Test fun tickingAMorningAndAnEveningIsRememberedAcrossARestart() {
        val first = SkinCareStore(file)
        first.toggleMorning(oct20)
        first.toggleEvening(oct20, EveningKind.GLYCOLIC)
        val reopened = SkinCareStore(file)
        assertEquals(setOf(oct20), reopened.log.morningsDone)
        assertEquals(setOf(oct20), reopened.log.eveningsDone)
        assertEquals(setOf(oct20), reopened.log.glycolicNights, "a glycolic evening is recorded as a glycolic night")
    }

    @Test fun untickingAGlycolicEveningTakesTheNightBackOut() {
        val skin = SkinCareStore(file)
        skin.toggleEvening(oct20, EveningKind.GLYCOLIC)
        skin.toggleEvening(oct20, EveningKind.GLYCOLIC)
        assertFalse(oct20 in skin.log.eveningsDone)
        assertFalse(oct20 in skin.log.glycolicNights)
    }

    @Test fun sayingNoToAnEveningIsSavedUnrecordsItAndAYesCancelsTheNo() {
        val skin = SkinCareStore(file)
        skin.toggleEvening(oct20, EveningKind.GLYCOLIC)
        skin.setEveningSkipped(oct20, true)
        assertFalse(skin.log.doneOn(oct20), "a no takes the yes back")
        assertTrue(skin.log.glycolicNights.isEmpty(), "and nothing of the night is remembered")
        assertEquals(setOf(oct20), SkinCareStore(file).log.eveningsSkipped, "the no is saved")
        skin.toggleEvening(oct20, EveningKind.GLYCOLIC)
        assertTrue(skin.log.eveningsSkipped.isEmpty(), "a yes cancels the no")
        assertTrue(skin.log.doneOn(oct20))
        skin.setEveningSkipped(oct20, true)
        skin.setEveningSkipped(oct20, false)
        assertTrue(skin.log.eveningsSkipped.isEmpty() && !skin.log.doneOn(oct20), "taking a no back leaves the evening unanswered")
        assertTrue(SkinCareStore(file).log.eveningsSkipped.isEmpty())
    }

    @Test fun recordingWhatWasUsedCancelsANoAndForgettingItDoesToo() {
        val skin = SkinCareStore(file)
        skin.setEveningSkipped(oct20, true)
        skin.setUsed(oct20, EveningKind.CALM)
        assertTrue(skin.log.eveningsSkipped.isEmpty())
        assertTrue(skin.log.doneOn(oct20))
        skin.setEveningSkipped(oct20, true)
        skin.setUsed(oct20, null)
        assertTrue(skin.log.eveningsSkipped.isEmpty(), "back to following the plan")
    }

    @Test fun aPlainEveningDoesNotCountAsGlycolic() {
        val skin = SkinCareStore(file)
        skin.toggleEvening(oct20, EveningKind.CALM)
        assertTrue(oct20 in skin.log.eveningsDone)
        assertTrue(skin.log.glycolicNights.isEmpty())
    }

    @Test fun tickingATonerEveningRecordsItAsTheTonerAndUntickingRemovesIt() {
        val skin = SkinCareStore(file)
        skin.toggleEvening(oct20, EveningKind.TONER)
        assertEquals(setOf(oct20), skin.log.glycolicNights)
        assertEquals(setOf(oct20), skin.log.tonerNights)
        assertEquals(setOf(oct20), SkinCareStore(file).log.tonerNights, "saved to disk")
        skin.toggleEvening(oct20, EveningKind.TONER)
        assertTrue(skin.log.glycolicNights.isEmpty() && skin.log.tonerNights.isEmpty())
    }

    @Test fun theTreatmentAndTheTonerCanBeMarkedUsedOnTheirOwnAndOneReplacesTheOther() {
        val skin = SkinCareStore(file)
        skin.setExfoliationNight(oct20, used = true, exfoliant = Product.TONER)
        assertEquals(setOf(oct20), skin.log.tonerNights)
        skin.setExfoliationNight(oct20, used = true, exfoliant = Product.GLYCOLIC)
        assertEquals(setOf(oct20), skin.log.glycolicNights)
        assertTrue(skin.log.tonerNights.isEmpty(), "it was the treatment after all")
    }

    @Test fun aNonExfoliatingEveningIsTickedWithoutBeingRecordedAsAGlycolicNight() {
        val skin = SkinCareStore(file)
        skin.toggleEvening(oct20, EveningKind.CALM)
        assertEquals(setOf(oct20), skin.log.eveningsDone)
        assertTrue(skin.log.glycolicNights.isEmpty())
    }

    @Test fun tickingASerumOrRestEveningRemembersWhichKindItWasAndUntickingForgetsIt() {
        val skin = SkinCareStore(file)
        skin.toggleEvening(oct20, EveningKind.BRIGHTEN)
        skin.toggleEvening(oct20.minusDays(1), EveningKind.REST)
        assertEquals(EveningKind.BRIGHTEN, skin.log.usedOn(oct20))
        assertEquals(EveningKind.REST, skin.log.usedOn(oct20.minusDays(1)))
        val reopened = SkinCareStore(file)
        assertEquals(EveningKind.BRIGHTEN, reopened.log.usedOn(oct20), "saved to disk")
        assertTrue(reopened.log.glycolicNights.isEmpty(), "a serum night is not a glycolic night")
        skin.toggleEvening(oct20, EveningKind.BRIGHTEN) // un-tick
        assertNull(skin.log.usedOn(oct20))
        assertFalse(skin.log.doneOn(oct20))
    }

    @Test fun anExfoliatingNightIsKnownFromTheGlycolicRecordsAndIsTheTonerOnlyWhenLoggedAsTheToner() {
        val skin = SkinCareStore(file)
        skin.toggleEvening(oct20, EveningKind.GLYCOLIC)
        skin.toggleEvening(oct20.plusDays(3), EveningKind.TONER)
        assertEquals(EveningKind.GLYCOLIC, skin.log.usedOn(oct20))
        assertEquals(EveningKind.TONER, skin.log.usedOn(oct20.plusDays(3)))
        assertTrue(skin.log.doneOn(oct20) && skin.log.doneOn(oct20.plusDays(3)))
        assertNull(skin.log.usedOn(oct20.plusDays(1)), "a day nothing was recorded for")
        assertFalse(skin.log.doneOn(oct20.plusDays(1)))
    }

    @Test fun anExfoliantMarkedWithTheButtonCountsAsUsedEvenIfTheEveningWasNotTicked() {
        val skin = SkinCareStore(file)
        skin.setExfoliationNight(oct20, used = true, exfoliant = Product.TONER)
        assertEquals(EveningKind.TONER, skin.log.usedOn(oct20))
        assertTrue(skin.log.doneOn(oct20))
    }

    @Test fun anEveningTickedBeforeKindsWereRememberedIsDoneButItsProductIsUnknown() {
        file.writeText("""{"glycolicNights":[],"morningsDone":[],"eveningsDone":[${oct20.toEpochDay()}]}""")
        val skin = SkinCareStore(file)
        assertTrue(skin.log.doneOn(oct20))
        assertNull(skin.log.usedOn(oct20), "never guessed")
    }

    @Test fun aKindNameThisVersionDoesNotKnowIsSkippedNotACrash() {
        file.writeText("""{"eveningsDone":[${oct20.toEpochDay()}],"eveningKinds":{"${oct20.toEpochDay()}":"SOMETHING_NEW","${oct20.toEpochDay() - 1}":"CALM"}}""")
        val skin = SkinCareStore(file)
        assertNull(skin.log.usedOn(oct20))
        assertEquals(EveningKind.CALM, skin.log.usedOn(oct20.minusDays(1)))
    }
    @Test fun theRoutineRemembersTheDayItBeganAndOnlyTheFirstTime() {
        val skin = SkinCareStore(file)
        assertNull(skin.startedOn)
        skin.ensureStarted(oct20)
        skin.ensureStarted(oct20.plusDays(5))
        assertEquals(oct20, skin.startedOn, "the first day is kept")
        assertEquals(oct20, SkinCareStore(file).startedOn, "and saved")
        assertEquals(oct20, skin.confirmFrom, "confirming begins the same day for a new routine")
        assertEquals(oct20, SkinCareStore(file).confirmFrom, "and is saved")
    }

    @Test fun aRoutineBegunBeforeConfirmingExistedStartsConfirmingTodayAndKeepsItsOwnStartDay() {
        // A file from before confirming: it has the day the routine began but no day confirming began.
        file.writeText("""{"startedOn":${oct20.toEpochDay()}}""")
        val skin = SkinCareStore(file)
        assertEquals(oct20, skin.startedOn)
        assertNull(skin.confirmFrom)
        skin.ensureStarted(oct20.plusDays(9))
        assertEquals(oct20, skin.startedOn, "the routine's own start is untouched")
        assertEquals(oct20.plusDays(9), skin.confirmFrom, "confirming starts the first time it runs, so the earlier days keep counting as followed")
        skin.ensureStarted(oct20.plusDays(20))
        assertEquals(oct20.plusDays(9), SkinCareStore(file).confirmFrom, "and only the first time")
    }

    @Test fun settingWhatWasUsedRecordsItAsDoneAndReplacesWhatWasThereBefore() {
        val skin = SkinCareStore(file)
        skin.setUsed(oct20, EveningKind.BRIGHTEN)
        assertEquals(EveningKind.BRIGHTEN, skin.log.usedOn(oct20))
        assertTrue(skin.log.doneOn(oct20))
        skin.setUsed(oct20, EveningKind.TONER) // changed their mind: it was the toner
        assertEquals(EveningKind.TONER, skin.log.usedOn(oct20))
        assertEquals(setOf(oct20), skin.log.tonerNights)
        assertNull(skin.log.eveningKinds[oct20], "no longer a serum night")
        skin.setUsed(oct20, EveningKind.GLYCOLIC) // no, the Findley treatment
        assertEquals(EveningKind.GLYCOLIC, skin.log.usedOn(oct20))
        assertTrue(skin.log.tonerNights.isEmpty())
        skin.setUsed(oct20, EveningKind.BOTH)
        assertEquals(EveningKind.BOTH, SkinCareStore(file).log.usedOn(oct20), "saved")
        assertTrue(skin.log.glycolicNights.isEmpty(), "and it is no longer an exfoliating night")
    }

    @Test fun settingNothingForgetsTheRecordSoTheDayFollowsThePlanAgain() {
        val skin = SkinCareStore(file)
        skin.setUsed(oct20, EveningKind.GLYCOLIC)
        skin.setUsed(oct20, null)
        assertNull(skin.log.usedOn(oct20))
        assertFalse(skin.log.doneOn(oct20))
        assertTrue(skin.log.glycolicNights.isEmpty() && skin.log.eveningsDone.isEmpty())
    }
    @Test fun glycolicCanBeMarkedUsedOrNotUsedOnItsOwn() {
        val skin = SkinCareStore(file)
        skin.setExfoliationNight(oct20, used = true)
        assertEquals(setOf(oct20), SkinCareStore(file).log.glycolicNights)
        skin.setExfoliationNight(oct20, used = false)
        assertTrue(SkinCareStore(file).log.glycolicNights.isEmpty())
    }

    @Test fun aMissingOrDamagedFileJustMeansNothingRecordedYet() {
        assertTrue(SkinCareStore(file).log.glycolicNights.isEmpty()) // no file at all
        file.writeText("this is not json {{{")
        val skin = SkinCareStore(file)
        assertTrue(skin.log.morningsDone.isEmpty())
        skin.toggleMorning(oct20) // and it can be written over
        assertEquals(setOf(oct20), SkinCareStore(file).log.morningsDone)
    }

    @Test fun withoutAFileItStaysInMemoryOnly() {
        val skin = SkinCareStore(null)
        skin.toggleMorning(oct20)
        assertEquals(setOf(oct20), skin.log.morningsDone)
        assertFalse(file.exists())
    }

    @Test fun onlyTheNewestFewMonthsAreKept() {
        val skin = SkinCareStore(file)
        (0 until 200).forEach { skin.setExfoliationNight(oct20.minusDays(it.toLong()), used = true) }
        val kept = SkinCareStore(file).log.glycolicNights
        assertEquals(120, kept.size)
        assertTrue(oct20 in kept)
        assertFalse(oct20.minusDays(150) in kept)
    }

    // ---- the products list ----

    @Test fun extraProductsAreListedSavedAndRemoved() {
        val skin = SkinCareStore(file)
        skin.addProduct("  Lip balm ", " dry lips ")
        skin.addProduct("Eye cream")
        assertEquals(listOf("Lip balm", "Eye cream"), skin.customProducts.map { it.name })
        assertEquals("dry lips", skin.customProducts[0].note)
        assertEquals(listOf("Lip balm", "Eye cream"), SkinCareStore(file).customProducts.map { it.name }, "saved to disk")
        skin.removeProduct("Lip balm")
        assertEquals(listOf("Eye cream"), SkinCareStore(file).customProducts.map { it.name })
    }

    @Test fun aBlankOrRepeatedProductIsNotAdded() {
        val skin = SkinCareStore(file)
        skin.addProduct("   ")
        skin.addProduct("Lip balm")
        skin.addProduct("lip BALM")
        assertEquals(1, skin.customProducts.size)
    }

    @Test fun productsAndTicksShareOneFileWithoutLosingEachOther() {
        val skin = SkinCareStore(file)
        skin.addProduct("Lip balm")
        skin.toggleEvening(oct20, EveningKind.GLYCOLIC)
        skin.addProduct("Eye cream")
        val reopened = SkinCareStore(file)
        assertEquals(listOf("Lip balm", "Eye cream"), reopened.customProducts.map { it.name })
        assertEquals(setOf(oct20), reopened.log.glycolicNights)
    }

    @Test fun anOlderFileWithNoProductsListStillOpens() {
        file.writeText("""{"glycolicNights":[${oct20.toEpochDay()}],"morningsDone":[],"eveningsDone":[]}""")
        val skin = SkinCareStore(file)
        assertEquals(setOf(oct20), skin.log.glycolicNights)
        assertTrue(skin.customProducts.isEmpty())
    }

    // ---- the work times ----

    @Test fun theWorkTimesFromThePhoneBecomeTheCalendarsSchedule() {
        val schedule = buildWorkSchedule(
            listOf(LinkWorkShift(oct20.toEpochDay(), 12 * 60 + 30, 21 * 60), LinkWorkShift(oct20.plusDays(1).toEpochDay(), 22 * 60, 6 * 60, endsNextDay = true)),
            listOf(oct20.toEpochDay(), oct20.plusDays(1).toEpochDay(), oct20.plusDays(2).toEpochDay()),
        )
        assertEquals(LocalTime.of(12, 30), schedule.shiftOn(oct20)!!.start)
        assertEquals(LocalTime.of(21, 0), schedule.shiftOn(oct20)!!.end)
        assertTrue(schedule.shiftOn(oct20.plusDays(1))!!.endsNextDay)
        assertTrue(schedule.isOff(oct20.plusDays(2)), "read, no shift: off")
        assertFalse(schedule.isOff(oct20.plusDays(3)), "never read: show nothing, not a wrong OFF")
    }

    @Test fun aRowThatMakesNoSenseIsSkippedNotGuessedAt() {
        val schedule = buildWorkSchedule(
            listOf(LinkWorkShift(oct20.toEpochDay(), 2000, 100), LinkWorkShift(oct20.toEpochDay(), 8 * 60, 16 * 60)),
            emptyList(),
        )
        assertEquals(1, schedule.shifts.values.sumOf { it.size })
    }

    @Test fun theStoreTakesTheWorkTimesFromTheSnapshotAndForgetsThemWhenTheOnPhoneIsForgotten() {
        val store = DesktopStore(oct20)
        store.applySnapshot(
            LinkSnapshot(
                generatedAtMillis = 1, todayEpochDay = oct20.toEpochDay(), deviceName = "Phone",
                workShifts = listOf(LinkWorkShift(oct20.toEpochDay(), 750, 1260)),
                workCoveredDays = listOf(oct20.toEpochDay(), oct20.plusDays(1).toEpochDay()),
            ),
        )
        assertEquals(LocalTime.of(12, 30), store.workSchedule.shiftOn(oct20)!!.start)
        assertTrue(store.workSchedule.isOff(oct20.plusDays(1)))
        store.clear()
        assertNull(store.workSchedule.shiftOn(oct20))
    }

    @Test fun aPhoneThatSendsNoWorkTimesLeavesTheCalendarWithoutThem() {
        val store = DesktopStore(oct20)
        store.applySnapshot(LinkSnapshot(generatedAtMillis = 1, todayEpochDay = oct20.toEpochDay(), deviceName = "Old phone"))
        assertNull(store.workSchedule.shiftOn(oct20))
        assertFalse(store.workSchedule.isOff(oct20))
        assertFalse(store.workSchedule.received, "so the Calendar can say they haven't arrived")
    }

    @Test fun workTimesCountAsReceivedOnceAnyShiftOrReadDayArrives() {
        assertFalse(WorkSchedule.EMPTY.received)
        assertTrue(buildWorkSchedule(listOf(LinkWorkShift(oct20.toEpochDay(), 480, 990)), emptyList()).received)
        assertTrue(buildWorkSchedule(emptyList(), listOf(oct20.toEpochDay())).received, "a rota read with every day off is still received")
    }
}
