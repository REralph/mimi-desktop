package com.example.a2desktop

import com.example.a2desktop.link.LinkCareCommand
import com.example.a2desktop.link.LinkSkinCare
import com.example.a2desktop.link.LinkVitamins
import com.example.a2desktop.logic.CARE_KEEP_DAYS
import com.example.a2desktop.logic.CareState
import com.example.a2desktop.logic.CustomProduct
import com.example.a2desktop.logic.EveningKind
import com.example.a2desktop.logic.Product
import com.example.a2desktop.logic.SkinState
import com.example.a2desktop.logic.Supplement
import com.example.a2desktop.logic.VitaminSettings
import com.example.a2desktop.logic.VitaminState
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The skin-care and supplements record, and the changes that can be made to it — the code both apps share. */
class CareStateTest {
    private val nov6 = LocalDate.of(2026, 11, 6)
    private val nov7 = nov6.plusDays(1)
    private fun day(d: LocalDate) = d.toEpochDay()

    // ---- skin care ----

    @Test fun anEveningIsTickedAndUntickedBySettingItNotFlippingIt() {
        val ticked = SkinState().setEvening(nov6, EveningKind.GLYCOLIC, true)
        assertTrue(ticked.log.doneOn(nov6))
        assertTrue(nov6 in ticked.log.glycolicNights, "an exfoliating night is remembered as one")
        assertEquals(ticked, ticked.setEvening(nov6, EveningKind.GLYCOLIC, true), "ticking it again changes nothing")
        val back = ticked.setEvening(nov6, EveningKind.GLYCOLIC, false)
        assertFalse(back.log.doneOn(nov6))
        assertEquals(back, back.setEvening(nov6, EveningKind.GLYCOLIC, false), "un-ticking twice changes nothing")
    }

    @Test fun theKindOfEveningIsRemembered() {
        val toner = SkinState().setEvening(nov6, EveningKind.TONER, true)
        assertEquals(EveningKind.TONER, toner.log.usedOn(nov6))
        assertTrue(nov6 in toner.log.tonerNights)
        val serum = SkinState().setEvening(nov6, EveningKind.CALM, true)
        assertEquals(EveningKind.CALM, serum.log.usedOn(nov6))
        assertTrue(nov6 !in serum.log.glycolicNights)
    }

    @Test fun sayingNoDropsATickAndTakingItBackLeavesTheEveningUnanswered() {
        val skipped = SkinState().setEvening(nov6, EveningKind.GLYCOLIC, true).setEveningSkipped(nov6, true)
        assertFalse(skipped.log.doneOn(nov6))
        assertTrue(nov6 in skipped.log.eveningsSkipped)
        assertTrue(nov6 !in skipped.setEveningSkipped(nov6, false).log.eveningsSkipped)
    }

    @Test fun theRoutineAndConfirmingBeginOnTheFirstDayTheyAreStartedAndNeverMove() {
        val started = SkinState().ensureStarted(nov6)
        assertEquals(nov6, started.startedOn)
        assertEquals(nov6, started.confirmFrom)
        assertEquals(started, started.ensureStarted(nov7), "a second start changes nothing")
    }

    @Test fun whatWasUsedCanBeRecordedAndForgotten() {
        val used = SkinState().setUsed(nov6, EveningKind.BOTH)
        assertEquals(EveningKind.BOTH, used.log.usedOn(nov6))
        assertTrue(used.log.doneOn(nov6))
        assertNull(used.setUsed(nov6, null).log.usedOn(nov6))
        assertEquals(EveningKind.TONER, SkinState().setExfoliationNight(nov6, true, Product.TONER).log.usedOn(nov6))
    }

    @Test fun productsAreAddedOnceWhateverTheCapitalsAndRemoved() {
        val s = SkinState().addProduct("  Retinol ", " night ").addProduct("RETINOL").addProduct("   ")
        assertEquals(listOf(CustomProduct("Retinol", "night")), s.products)
        assertTrue(s.removeProduct("Retinol").products.isEmpty())
    }

    // ---- supplements ----

    @Test fun aSupplementIsMarkedTakenNotTakenOrForgottenOnOneDayOnly() {
        val v = VitaminState().setMark(nov6, Supplement.CREATINE, true).setMark(nov7, Supplement.ZINC, false)
        assertEquals(true, v.marks[nov6]?.get(Supplement.CREATINE))
        assertEquals(false, v.marks[nov7]?.get(Supplement.ZINC))
        val forgotten = v.setMark(nov6, Supplement.CREATINE, null)
        assertTrue(nov6 !in forgotten.marks, "a day with nothing left is untouched")
        assertEquals(false, forgotten.marks[nov7]?.get(Supplement.ZINC), "and the other day is as it was")
        assertEquals(v, v.setMark(nov6, Supplement.CREATINE, true), "setting it again changes nothing")
        assertTrue(nov6 !in v.resetDay(nov6).marks)
    }

    @Test fun theChoicesStayInTheirRanges() {
        val v = VitaminState().setMagnesiumTablets(9).setSoftgelDays(0).setOrganCapsules(40).setZincEveryDay(true)
        assertEquals(VitaminSettings(zincEveryDay = true, magnesiumTablets = 3, softgelDays = 1, organCapsules = 6), v.settings)
    }

    @Test fun trackingAndTickingBeginOnceAndStay() {
        val v = VitaminState().ensureStarted(nov6)
        assertEquals(nov6, v.tickFrom)
        assertEquals(nov6, v.trackedFrom)
        assertEquals(v, v.ensureStarted(nov7))
    }

    // ---- the commands the desktop sends ----

    private fun cmd(op: String, date: LocalDate? = null, kind: String? = null, name: String? = null, flag: Boolean? = null, count: Int? = null, supplement: String? = null, note: String? = null) =
        LinkCareCommand(op, date?.let(::day), kind, name, note, flag, count, supplement)

    @Test fun everyCommandMakesItsChange() {
        val s = CareState().apply(
            listOf(
                cmd("skin.start", nov6),
                cmd("skin.setEvening", nov6, kind = "GLYCOLIC", flag = true),
                cmd("skin.setMorning", nov6, flag = true),
                cmd("skin.setSkipped", nov7, flag = true),
                cmd("skin.addProduct", name = "Retinol", note = "night"),
                cmd("vit.start", nov6),
                cmd("vit.setMark", nov6, supplement = "CREATINE", flag = true),
                cmd("vit.zincEveryDay", flag = true),
                cmd("vit.magnesium", count = 2),
                cmd("vit.softgelDays", count = 2),
                cmd("vit.organs", count = 4),
            ),
        )
        assertEquals(nov6, s.skin.startedOn)
        assertTrue(s.skin.log.doneOn(nov6) && nov6 in s.skin.log.morningsDone && nov7 in s.skin.log.eveningsSkipped)
        assertEquals(listOf(CustomProduct("Retinol", "night")), s.skin.products)
        assertEquals(true, s.vitamins.marks[nov6]?.get(Supplement.CREATINE))
        assertEquals(VitaminSettings(zincEveryDay = true, magnesiumTablets = 2, softgelDays = 2, organCapsules = 4), s.vitamins.settings)
        val undone = s.apply(listOf(cmd("skin.setEvening", nov6, kind = "GLYCOLIC", flag = false), cmd("skin.removeProduct", name = "Retinol"), cmd("vit.setMark", nov6, supplement = "CREATINE"), cmd("vit.resetDay", nov6)))
        assertFalse(undone.skin.log.doneOn(nov6))
        assertTrue(undone.skin.products.isEmpty())
        assertTrue(undone.vitamins.marks.isEmpty())
    }

    @Test fun aCommandSentTwiceDoesTheSameAsOnce() {
        val once = CareState().apply(listOf(cmd("skin.setEvening", nov6, kind = "CALM", flag = true), cmd("vit.setMark", nov6, supplement = "ZINC", flag = true)))
        assertEquals(once, once.apply(listOf(cmd("skin.setEvening", nov6, kind = "CALM", flag = true), cmd("vit.setMark", nov6, supplement = "ZINC", flag = true))))
    }

    @Test fun aCommandThatIsNotUnderstoodChangesNothing() {
        val start = CareState().apply(listOf(cmd("skin.start", nov6)))
        listOf(
            cmd("skin.fly"), cmd("vit.fly"), cmd("other"), cmd("skin.setEvening", nov6, kind = "MARS", flag = true), cmd("skin.setEvening", kind = "GLYCOLIC", flag = true),
            cmd("skin.setEvening", nov6, kind = "GLYCOLIC"), cmd("vit.setMark", nov6, supplement = "UNOBTAINIUM", flag = true), cmd("vit.magnesium"), cmd("skin.setUsed", nov6, kind = "MARS"),
        ).forEach { assertEquals(start, start.apply(listOf(it)), it.toString()) }
    }

    // ---- what travels and what is stored ----

    @Test fun theRecordSurvivesTheTripToTheWireAndBack() {
        val s = CareState(
            SkinState().ensureStarted(nov6).setEvening(nov6, EveningKind.TONER, true).setEvening(nov7, EveningKind.CALM, true).setMorning(nov6, true).setEveningSkipped(nov6.minusDays(1), true).addProduct("Retinol"),
            VitaminState().ensureStarted(nov6).setMark(nov6, Supplement.ZINC, true).setMark(nov6, Supplement.CREATINE, false).setSoftgelDays(2),
        )
        assertEquals(s, CareState.fromWire(s.toWire()))
    }

    @Test fun onlyTheMostRecentDaysAreKeptAndUnknownNamesAreSkipped() {
        var skin = SkinState()
        for (i in 0 until CARE_KEEP_DAYS + 30) skin = skin.setMorning(nov6.minusDays(i.toLong()), true)
        assertEquals(CARE_KEEP_DAYS, skin.toWire().morningsDone.size)
        assertEquals(day(nov6), skin.toWire().morningsDone.max(), "the newest are the ones kept")
        val odd = SkinState.fromWire(LinkSkinCare(eveningKinds = mapOf(day(nov6) to "MARS", day(nov7) to "CALM")))
        assertEquals(mapOf(nov7 to EveningKind.CALM), odd.log.eveningKinds)
        val v = VitaminState.fromWire(LinkVitamins(marks = mapOf(day(nov6) to mapOf("UNOBTAINIUM" to true), day(nov7) to mapOf("ZINC" to true))))
        assertEquals(setOf(nov7), v.marks.keys)
    }

    // ---- joining the desktop's old record to the phone's, once ----

    @Test fun theDesktopsEarlierHistoryComesAcrossAndTheRoutineKeepsItsEarlierStart() {
        // The desktop began the routine on 5 Oct; the phone's own card only started today.
        val desktop = SkinState(startedOn = LocalDate.of(2026, 10, 5), confirmFrom = LocalDate.of(2026, 10, 11))
            .setEvening(LocalDate.of(2026, 10, 12), EveningKind.GLYCOLIC, true).setEvening(LocalDate.of(2026, 10, 20), EveningKind.CALM, true)
        val phone = SkinState().ensureStarted(nov7)
        val merged = phone.mergedWith(desktop)
        assertEquals(LocalDate.of(2026, 10, 5), merged.startedOn)
        assertEquals(LocalDate.of(2026, 10, 11), merged.confirmFrom)
        assertTrue(merged.log.doneOn(LocalDate.of(2026, 10, 12)) && merged.log.doneOn(LocalDate.of(2026, 10, 20)))
        assertEquals(EveningKind.CALM, merged.log.usedOn(LocalDate.of(2026, 10, 20)))
    }

    @Test fun whatThePhoneAlreadySaysAboutADayWinsOverTheDesktop() {
        val phone = SkinState().setEvening(nov6, EveningKind.CALM, true)
        val desktop = SkinState().setEveningSkipped(nov6, true).setEvening(nov7, EveningKind.GLYCOLIC, true)
        val merged = phone.mergedWith(desktop)
        assertEquals(EveningKind.CALM, merged.log.usedOn(nov6))
        assertTrue(nov6 !in merged.log.eveningsSkipped, "the desktop's no for a day the phone ticked is dropped")
        assertTrue(merged.log.doneOn(nov7), "a day only the desktop knows about comes across")
        assertEquals(merged, merged.mergedWith(desktop), "merging the same record again changes nothing")
    }

    @Test fun aDayThePhoneSaidNoToIsNotTickedByTheDesktopsEarlierRecord() {
        val phone = SkinState().setEveningSkipped(nov6, true)
        val desktop = SkinState().setEvening(nov6, EveningKind.GLYCOLIC, true)
        val merged = phone.mergedWith(desktop)
        assertFalse(merged.log.doneOn(nov6), "the phone's no stands")
        assertTrue(nov6 in merged.log.eveningsSkipped)
        assertTrue(nov6 !in merged.log.glycolicNights, "and nothing of the desktop's tick for that day comes across")
    }

    @Test fun aMorningIsSetNotFlipped() {
        val once = SkinState().setMorning(nov6, true)
        assertTrue(nov6 in once.setMorning(nov6, true).log.morningsDone, "ticking an already ticked morning leaves it ticked")
        assertTrue(nov6 !in once.setMorning(nov6, false).setMorning(nov6, false).log.morningsDone, "and un-ticking twice leaves it unticked")
    }

    @Test fun theSupplementsRecordMergesTheSameWay() {
        val desktop = VitaminState(tickFrom = nov6.minusDays(10), trackedFrom = nov6.minusDays(12), settings = VitaminSettings(softgelDays = 2, organCapsules = 5))
            .setMark(nov6, Supplement.ZINC, false).setMark(nov7, Supplement.CREATINE, true)
        val phone = VitaminState(settings = VitaminSettings(organCapsules = 4)).setMark(nov6, Supplement.ZINC, true).ensureStarted(nov7)
        val merged = phone.mergedWith(desktop)
        assertEquals(true, merged.marks[nov6]?.get(Supplement.ZINC), "the phone's tick wins")
        assertEquals(true, merged.marks[nov7]?.get(Supplement.CREATINE), "the desktop's other day comes across")
        assertEquals(nov6.minusDays(10), merged.tickFrom)
        assertEquals(nov6.minusDays(12), merged.trackedFrom)
        assertEquals(4, merged.settings.organCapsules, "a choice the phone changed stays")
        assertEquals(2, merged.settings.softgelDaysPerWeek, "a choice still at its default takes the desktop's")
    }
}
