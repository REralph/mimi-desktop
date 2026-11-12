package com.example.a2desktop

import com.example.a2desktop.link.LinkCareCommand
import com.example.a2desktop.logic.EveningKind
import com.example.a2desktop.logic.SkinCareLog
import com.example.a2desktop.logic.SkinState
import com.example.a2desktop.logic.WorkSchedule
import com.example.a2desktop.logic.WorkShift
import com.example.a2desktop.logic.planSkinCare
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Choosing the product for an evening (the pencil on the Skin care card): the plan keeps the evening as chosen, plans the nights after it
 * from it, and the choice is part of the record both apps share.
 */
class SkinChoiceTest {
    private val oct19 = LocalDate.of(2026, 10, 19) // a Monday in the low-sun months
    private val jun15 = LocalDate.of(2026, 6, 15) // a Monday with the sun high
    private val noWork = WorkSchedule.EMPTY

    private fun plan(log: SkinCareLog, days: Int = 8, from: LocalDate = oct19, work: WorkSchedule = noWork) = planSkinCare(from, days, log, work)

    // ---- the plan keeps what was chosen ----

    @Test fun anEveningTheUserChoseIsKeptAndSaysSo() {
        val day = plan(SkinCareLog(chosenKinds = mapOf(oct19 to EveningKind.CALM)), 1).single()
        assertEquals(EveningKind.CALM, day.eveningKind, "the plan would have had glycolic tonight")
        assertTrue(day.why.contains("You chose"), day.why)
        assertFalse(day.glycolicTonight)
    }

    @Test fun withNoChoiceTheNightIsWhatThePlanSays() {
        assertEquals(EveningKind.GLYCOLIC, plan(SkinCareLog(), 1).single().eveningKind)
    }

    @Test fun anExfoliatingNightTheUserChoseIsKeptEvenWhenThePlanWouldHoldBackAndSaysWhy() {
        val shifts = WorkSchedule(mapOf(jun15.plusDays(1) to listOf(WorkShift(jun15.plusDays(1), LocalTime.of(12, 30), LocalTime.of(21, 0), false))), emptySet())
        val held = plan(SkinCareLog(), 1, jun15, shifts).single()
        assertFalse(held.glycolicTonight, "the sun is high and work is tomorrow, so the plan skips the glycolic")
        val chosen = plan(SkinCareLog(chosenKinds = mapOf(jun15 to EveningKind.GLYCOLIC)), 1, jun15, shifts).single()
        assertEquals(EveningKind.GLYCOLIC, chosen.eveningKind)
        assertTrue(chosen.why.contains("You chose") && chosen.why.contains("held back"), chosen.why)
    }

    @Test fun aChosenToneroverridesWhichGlycolicProductIsDue() {
        assertEquals(EveningKind.TONER, plan(SkinCareLog(chosenKinds = mapOf(oct19 to EveningKind.TONER)), 1).single().eveningKind)
    }

    // ---- and the nights after it follow from it ----

    @Test fun choosingASerumOnAGlycolicNightMovesTheGlycolicToTheNextNight() {
        val before = plan(SkinCareLog()).map { it.eveningKind }
        val after = plan(SkinCareLog(chosenKinds = mapOf(oct19 to EveningKind.CALM))).map { it.eveningKind }
        assertEquals(EveningKind.GLYCOLIC, before[0])
        assertEquals(EveningKind.CALM, after[0])
        assertEquals(EveningKind.GLYCOLIC, after[1], "no exfoliant had been used, so it is due tomorrow")
        assertNotEquals(before, after)
    }

    @Test fun theNightsAfterAChosenToneFollowFromItRestThenSerumThenTheOtherProduct() {
        val kinds = plan(SkinCareLog(chosenKinds = mapOf(oct19 to EveningKind.TONER)), 4).map { it.eveningKind }
        assertEquals(EveningKind.TONER, kinds[0])
        assertEquals(EveningKind.REST, kinds[1], "the night after an exfoliating night is blank")
        assertTrue(kinds[2].serum, "two nights after: a serum, still too soon for another exfoliant")
        assertEquals(EveningKind.GLYCOLIC, kinds[3], "three nights after, and the two products take turns")
    }

    @Test fun aChosenRestNightLeavesTheNextOneFreeForGlycolic() {
        val kinds = plan(SkinCareLog(chosenKinds = mapOf(oct19 to EveningKind.REST)), 3).map { it.eveningKind }
        assertEquals(EveningKind.REST, kinds[0])
        assertEquals(EveningKind.GLYCOLIC, kinds[1])
    }

    @Test fun aChosenSerumIsFollowedByTheNextExfoliationWhenThatIsDueAndOtherwiseByARestNight() {
        val log = SkinCareLog(glycolicNights = setOf(oct19.minusDays(1)), chosenKinds = mapOf(oct19.plusDays(1) to EveningKind.CALM))
        val kinds = plan(log, 4).map { it.eveningKind }
        assertEquals(EveningKind.REST, kinds[0], "the night after the glycolic last night")
        assertEquals(EveningKind.CALM, kinds[1], "chosen")
        assertEquals(EveningKind.TONER, kinds[2], "three nights since the treatment, so the toner is due")
        // with the exfoliant not due, the night after the chosen serum is blank
        val spaced = plan(SkinCareLog(glycolicNights = setOf(oct19), chosenKinds = mapOf(oct19.plusDays(1) to EveningKind.CALM)), 4).map { it.eveningKind }
        assertEquals(EveningKind.CALM, spaced[1], "chosen, though it would have been a rest night")
        assertEquals(EveningKind.REST, spaced[2], "the night after a serum, with no exfoliant due yet, is blank")
    }

    // ---- what was ticked off wins ----

    @Test fun anEveningThatWasTickedOffAsSomethingElseIsNotChangedByAChoice() {
        val ticked = SkinCareLog(glycolicNights = setOf(oct19), eveningsDone = setOf(oct19), chosenKinds = mapOf(oct19 to EveningKind.CALM))
        assertEquals(EveningKind.GLYCOLIC, plan(ticked, 1).single().eveningKind)
        val serum = SkinCareLog(eveningsDone = setOf(oct19), eveningKinds = mapOf(oct19 to EveningKind.BRIGHTEN), chosenKinds = mapOf(oct19 to EveningKind.CALM))
        assertEquals(EveningKind.BRIGHTEN, plan(serum, 1).single().eveningKind)
    }

    // ---- the record ----

    @Test fun choosingDoesNotTickTheEveningOff() {
        val state = SkinState().setChosen(oct19.plusDays(2), EveningKind.BRIGHTEN)
        assertEquals(EveningKind.BRIGHTEN, state.log.chosenKinds[oct19.plusDays(2)])
        assertFalse(state.log.doneOn(oct19.plusDays(2)))
        assertTrue(state.log.eveningsDone.isEmpty() && state.log.eveningKinds.isEmpty())
    }

    @Test fun choosingAgainReplacesTheChoiceAndNullTakesItBack() {
        val a = SkinState().setChosen(oct19, EveningKind.CALM).setChosen(oct19, EveningKind.BOTH)
        assertEquals(EveningKind.BOTH, a.log.chosenKinds[oct19])
        assertTrue(a.setChosen(oct19, null).log.chosenKinds.isEmpty())
        assertEquals(SkinState(), SkinState().setChosen(oct19, null), "taking back what was never chosen changes nothing")
    }

    @Test fun choosingForAnEveningAlreadyTickedOffChangesWhatWasUsedAndKeepsItTicked() {
        val ticked = SkinState().setEvening(oct19, EveningKind.GLYCOLIC, true)
        val changed = ticked.setChosen(oct19, EveningKind.CALM)
        assertTrue(changed.log.doneOn(oct19), "still ticked")
        assertEquals(EveningKind.CALM, changed.log.usedOn(oct19))
        assertFalse(oct19 in changed.log.glycolicNights, "no longer an exfoliating night")
        // and the other way round
        val back = changed.setChosen(oct19, EveningKind.TONER)
        assertEquals(EveningKind.TONER, back.log.usedOn(oct19))
        assertTrue(oct19 in back.log.glycolicNights && oct19 in back.log.tonerNights)
    }

    @Test fun takingTheChoiceBackLeavesAnEveningAlreadyTickedOffAsItWas() {
        val ticked = SkinState().setEvening(oct19, EveningKind.CALM, true).setChosen(oct19, EveningKind.CALM)
        assertEquals(EveningKind.CALM, ticked.setChosen(oct19, null).log.usedOn(oct19))
    }

    @Test fun theChoiceTravelsInTheSharedRecord() {
        val state = SkinState().setChosen(oct19, EveningKind.TONER).setChosen(oct19.plusDays(3), EveningKind.REST)
        assertEquals(state, SkinState.fromWire(state.toWire()))
        assertEquals(mapOf(oct19.toEpochDay() to "TONER", oct19.plusDays(3).toEpochDay() to "REST"), state.toWire().chosenKinds)
    }

    @Test fun aChoiceNameThisVersionDoesNotKnowIsSkippedNotACrash() {
        val wire = SkinState().toWire().copy(chosenKinds = mapOf(oct19.toEpochDay() to "SOMETHING_NEW", oct19.plusDays(1).toEpochDay() to "CALM"))
        assertEquals(mapOf(oct19.plusDays(1) to EveningKind.CALM), SkinState.fromWire(wire).log.chosenKinds)
    }

    @Test fun theDesktopsCommandMakesAndTakesBackAChoiceOnThePhonesRecord() {
        val choose = LinkCareCommand("skin.setChosen", oct19.toEpochDay(), kind = "BRIGHTEN")
        val made = SkinState().apply(choose)
        assertEquals(EveningKind.BRIGHTEN, made.log.chosenKinds[oct19])
        assertEquals(made, made.apply(choose), "sent twice, it is the same")
        assertTrue(made.apply(LinkCareCommand("skin.setChosen", oct19.toEpochDay(), kind = null)).log.chosenKinds.isEmpty())
        assertEquals(made, made.apply(LinkCareCommand("skin.setChosen", oct19.toEpochDay(), kind = "NOT_A_KIND")), "an unknown kind changes nothing")
    }

    @Test fun joiningTwoRecordsKeepsBothSidesChoicesAndTheExistingOneWinsOnAClash() {
        val mine = SkinState().setChosen(oct19, EveningKind.CALM)
        val theirs = SkinState().setChosen(oct19, EveningKind.BOTH).setChosen(oct19.plusDays(1), EveningKind.REST)
        val joined = mine.mergedWith(theirs)
        assertEquals(EveningKind.CALM, joined.log.chosenKinds[oct19])
        assertEquals(EveningKind.REST, joined.log.chosenKinds[oct19.plusDays(1)])
    }
}
