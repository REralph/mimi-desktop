package com.example.a2desktop

import com.example.a2desktop.logic.DayRoutine
import com.example.a2desktop.logic.EveningKind
import com.example.a2desktop.logic.Product
import com.example.a2desktop.logic.SkinCareLog
import com.example.a2desktop.logic.UvBasis
import com.example.a2desktop.logic.UvLookup
import com.example.a2desktop.logic.UvTier
import com.example.a2desktop.logic.WorkSchedule
import com.example.a2desktop.logic.WorkShift
import com.example.a2desktop.logic.forecastUv
import com.example.a2desktop.logic.goesOutInPeakSun
import com.example.a2desktop.logic.planRoutine
import com.example.a2desktop.logic.planSkinCare
import com.example.a2desktop.logic.routineStart
import com.example.a2desktop.logic.seasonalUv
import com.example.a2desktop.logic.sunscreenReason
import com.example.a2desktop.logic.uvTier
import com.example.a2desktop.logic.uvTierOf
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** When each product goes on in the evening — and which evenings are left blank — around the user's shifts and the sun. */
class SkinCarePlannerTest {
    private fun shift(date: LocalDate, from: String, to: String, nextDay: Boolean = false) =
        WorkShift(date, LocalTime.parse(from), LocalTime.parse(to), nextDay)

    private fun schedule(shifts: List<WorkShift>, covered: List<LocalDate> = emptyList()) =
        WorkSchedule(shifts.groupBy { it.date }, covered.toSet())

    // A low-sun stretch: 19 Oct 2026 is a Monday and October counts as low.
    private val oct19 = LocalDate.of(2026, 10, 19)

    // A summer week (UV high): Mon 15 June 2026.
    private val jun15 = LocalDate.of(2026, 6, 15)

    private val noWork = WorkSchedule.EMPTY

    private fun DayRoutine.isSerum() = eveningKind.serum

    /** The serum that comes after [kind] in the cycle: niacinamide, Vitamin C, both together, round again. */
    private fun after(kind: EveningKind) = when (kind) {
        EveningKind.CALM -> EveningKind.BRIGHTEN
        EveningKind.BRIGHTEN -> EveningKind.BOTH
        else -> EveningKind.CALM
    }

    // ---- the sun, by season and by forecast ----

    @Test fun theSeasonalSunMatchesTheUkPatternFromTheWhoBands() {
        assertEquals(UvTier.LOW, uvTier(LocalDate.of(2026, 1, 15)))
        assertEquals(UvTier.LOW, uvTier(LocalDate.of(2026, 3, 31)))
        assertEquals(UvTier.MODERATE, uvTier(LocalDate.of(2026, 4, 1)))
        assertEquals(UvTier.HIGH, uvTier(LocalDate.of(2026, 5, 1)))
        assertEquals(UvTier.HIGH, uvTier(LocalDate.of(2026, 8, 31)))
        assertEquals(UvTier.MODERATE, uvTier(LocalDate.of(2026, 9, 1)))
        assertEquals(UvTier.LOW, uvTier(LocalDate.of(2026, 10, 1)))
        assertEquals(UvTier.LOW, uvTier(LocalDate.of(2026, 12, 25)))
    }

    @Test fun aForecastIndexFallsIntoTheWhoBandsAfterRoundingToAWholeNumber() {
        assertEquals(UvTier.LOW, uvTierOf(0.2))
        assertEquals(UvTier.LOW, uvTierOf(2.4))
        assertEquals(UvTier.MODERATE, uvTierOf(2.5), "reported as 3")
        assertEquals(UvTier.MODERATE, uvTierOf(5.4))
        assertEquals(UvTier.HIGH, uvTierOf(5.5), "reported as 6")
        assertEquals(UvTier.HIGH, uvTierOf(8.0))
        assertEquals("UV 3", forecastUv(2.6).short())
        assertEquals("UV moderate", seasonalUv(LocalDate.of(2026, 4, 10)).short())
        assertEquals(UvBasis.FORECAST, forecastUv(1.0).basis)
        assertEquals(UvBasis.SEASON, seasonalUv(oct19).basis)
    }

    @Test fun aShiftIsOutInThePeakSunWhenGettingThereWorkingOrComingHomeTouchesTenToFour() {
        val d = oct19
        assertTrue(goesOutInPeakSun(shift(d, "12:30", "21:00")), "12:30-21:00")
        assertTrue(goesOutInPeakSun(shift(d, "08:00", "16:30")), "08:00-16:30")
        assertTrue(goesOutInPeakSun(shift(d, "14:00", "22:30")), "14:00-22:30: leaves 13:30")
        assertFalse(goesOutInPeakSun(shift(d, "17:00", "23:00")), "17:00-23:00: leaves 16:30, after the peak")
        assertFalse(goesOutInPeakSun(shift(d, "06:00", "09:00")), "06:00-09:00: home by 09:30, before the peak")
        assertTrue(goesOutInPeakSun(shift(d, "04:00", "10:00")), "04:00-10:00: home 10:30, just inside")
        assertFalse(goesOutInPeakSun(shift(d, "22:00", "06:00", nextDay = true)), "22:00-06:00 overnight: out only at night")
    }

    // ---- how often the glycolic acid goes on ----

    @Test fun exfoliatingNightsAreSpacedAtLeastThreeDaysApartAndNeverBackToBack() {
        val plan = planSkinCare(oct19, 21, SkinCareLog(), noWork)
        val nights = plan.filter { it.glycolicTonight }.map { it.date.toEpochDay() - oct19.toEpochDay() }
        assertEquals(listOf(0L, 3L, 7L, 10L, 14L, 17L), nights)
        nights.zipWithNext().forEach { (a, b) -> assertTrue(b - a >= 3, "$a then $b") }
    }

    @Test fun neverMoreThanTwoExfoliatingNightsInAnySevenWithBothGlycolicProductsCountedTogether() {
        val plan = planSkinCare(oct19, 28, SkinCareLog(), noWork)
        for (start in 0..21) {
            val inWeek = plan.drop(start).take(7).count { it.glycolicTonight }
            assertEquals(2, inWeek, "window from day $start has $inWeek")
        }
    }

    @Test fun anExfoliantYesterdayMeansTonightIsABlankRestNight() {
        val log = SkinCareLog(glycolicNights = setOf(oct19.minusDays(1)))
        val tonight = planSkinCare(oct19, 1, log, noWork).single()
        assertFalse(tonight.glycolicTonight)
        assertEquals(EveningKind.REST, tonight.eveningKind)
        assertTrue(tonight.why.contains("last night"), tonight.why)
        assertTrue(planSkinCare(oct19.plusDays(2), 1, log, noWork).single().glycolicTonight) // 3 nights on
    }

    @Test fun anIrregularHistoryNeverGetsAThirdNightInAWeek() {
        val log = SkinCareLog(glycolicNights = setOf(oct19.minusDays(5), oct19.minusDays(3), oct19.minusDays(2)))
        assertFalse(planSkinCare(oct19, 1, log, noWork).single().glycolicTonight)
    }

    @Test fun theTonerAndTheTreatmentShareOneAllowanceSoTwoDifferentOnesInAWeekStopTheThird() {
        // The toner 5 nights ago and the Findley treatment 3 nights ago: spaced far enough, but already two this week.
        val log = SkinCareLog(glycolicNights = setOf(oct19.minusDays(5), oct19.minusDays(3)), tonerNights = setOf(oct19.minusDays(5)))
        val tonight = planSkinCare(oct19, 1, log, noWork).single()
        assertFalse(tonight.glycolicTonight, tonight.why)
        assertTrue(tonight.why.contains("count together") || tonight.eveningKind == EveningKind.REST, tonight.why)
    }

    @Test fun aNightTheUserHasLoggedAsGlycolicAlwaysShowsAsGlycolicAndALoggedTonerShowsAsTheToner() {
        val treatment = planSkinCare(oct19, 1, SkinCareLog(glycolicNights = setOf(oct19)), noWork).single()
        assertEquals(EveningKind.GLYCOLIC, treatment.eveningKind)
        assertEquals(listOf(Product.GLYCOLIC, Product.MOISTURISER), treatment.evening)
        val toner = planSkinCare(oct19, 1, SkinCareLog(glycolicNights = setOf(oct19), tonerNights = setOf(oct19)), noWork).single()
        assertEquals(EveningKind.TONER, toner.eveningKind)
        assertEquals(listOf(Product.TONER, Product.MOISTURISER), toner.evening)
    }

    @Test fun theTreatmentAndTheTonerTakeTurnsStartingWithTheTreatment() {
        val kinds = planSkinCare(oct19, 28, SkinCareLog(), noWork).filter { it.glycolicTonight }.map { it.eveningKind }
        assertEquals(listOf(EveningKind.GLYCOLIC, EveningKind.TONER), kinds.take(2))
        kinds.zipWithNext().forEach { (a, b) -> assertTrue(a != b, "$a then $b: the two products should alternate") }
    }

    @Test fun theNextExfoliantIsTheOtherOneFromTheLastLoggedNight() {
        val afterTreatment = planSkinCare(oct19, 1, SkinCareLog(glycolicNights = setOf(oct19.minusDays(3))), noWork).single()
        assertEquals(EveningKind.TONER, afterTreatment.eveningKind)
        val afterToner = planSkinCare(oct19, 1, SkinCareLog(glycolicNights = setOf(oct19.minusDays(3)), tonerNights = setOf(oct19.minusDays(3))), noWork).single()
        assertEquals(EveningKind.GLYCOLIC, afterToner.eveningKind)
        assertEquals(Product.TONER, afterTreatment.exfoliant)
    }

    // ---- blank nights ----

    @Test fun theMorningIsAlwaysJustTheMoisturiser() {
        val log = SkinCareLog(glycolicNights = setOf(oct19.minusDays(1)))
        planSkinCare(oct19, 30, log, noWork).forEach {
            assertEquals(listOf(Product.MOISTURISER), it.morning, "${it.date}")
        }
    }

    @Test fun theNightAfterAnExfoliatingNightIsBlankAndSoIsTheNightAfterASerumNightUnlessAnExfoliatingNightIsDue() {
        for ((start, work) in listOf(oct19 to noWork, jun15 to noWork)) {
            val plan = planSkinCare(start, 40, SkinCareLog(), work)
            plan.zipWithNext().forEach { (a, b) ->
                if (a.glycolicTonight) assertTrue(b.restTonight, "${b.date} follows an exfoliating night")
                if (a.isSerum()) assertTrue(b.restTonight || b.glycolicTonight, "${b.date} follows a serum night")
            }
        }
    }

    @Test fun thereAreAtLeastThreeBlankNightsInAnySevenAndNeverThreeActiveNightsInARow() {
        for ((start, work) in listOf(oct19 to noWork, jun15 to noWork)) {
            val plan = planSkinCare(start, 42, SkinCareLog(), work)
            for (i in 0..plan.size - 7) {
                assertTrue(plan.drop(i).take(7).count { it.restTonight } >= 3, "week from ${plan[i].date} has fewer than 3 blank nights")
            }
            for (i in 0..plan.size - 3) {
                assertTrue(plan.drop(i).take(3).any { it.restTonight }, "three active nights in a row from ${plan[i].date}")
            }
        }
    }

    @Test fun aBlankNightHasNothingActiveAndEveryOtherNightHasOneActiveThenTheMoisturiser() {
        val plan = planSkinCare(oct19, 40, SkinCareLog(), noWork)
        plan.forEach {
            when {
                it.restTonight -> assertEquals(listOf(Product.MOISTURISER), it.evening, "${it.date}")
                else -> {
                    // One active then the moisturiser — or, on the heavier night, both serums then the moisturiser.
                    assertEquals(if (it.eveningKind == EveningKind.BOTH) 3 else 2, it.evening.size, "${it.date}")
                    assertEquals(Product.MOISTURISER, it.evening.last())
                }
            }
            if (it.glycolicTonight) assertFalse(Product.VITAMIN_C in it.evening || Product.NIACINAMIDE in it.evening)
            assertFalse(Product.GLYCOLIC in it.morning || Product.TONER in it.morning || Product.VITAMIN_C in it.morning || Product.NIACINAMIDE in it.morning)
        }
    }

    @Test fun theSerumNightsGoRoundNiacinamideThenVitaminCThenBothTogether() {
        // Summer, work unknown: no exfoliating nights at all, so it is serum, blank, serum, blank…
        val plan = planSkinCare(jun15, 28, SkinCareLog(), noWork)
        assertTrue(plan.none { it.glycolicTonight })
        val serums = plan.filter { it.isSerum() }.map { it.eveningKind }
        serums.zipWithNext().forEach { (a, b) -> assertEquals(after(a), b, "$a should be followed by ${after(a)}") }
        assertEquals(setOf(EveningKind.CALM, EveningKind.BRIGHTEN, EveningKind.BOTH), serums.toSet(), "the heavier night comes round too")
        assertTrue(plan.count { it.restTonight } >= 12, "about every other night is blank")
    }

    @Test fun theHeavierNightHasBothSerumsAndIsStillFollowedByABlankNight() {
        val plan = planSkinCare(oct19, 60, SkinCareLog(), noWork)
        val heavy = plan.filter { it.eveningKind == EveningKind.BOTH }
        assertTrue(heavy.isNotEmpty(), "some heavier nights are planned")
        heavy.forEach {
            assertEquals(listOf(Product.VITAMIN_C, Product.NIACINAMIDE, Product.MOISTURISER), it.evening)
            assertFalse(it.glycolicTonight)
            assertTrue(it.why.contains("heavier night"), it.why)
        }
        plan.zipWithNext().filter { (a, _) -> a.eveningKind == EveningKind.BOTH }.forEach { (_, next) ->
            assertTrue(next.restTonight || next.glycolicTonight, "${next.date} follows a heavier night")
        }
        assertTrue(plan.windowed(7).all { week -> week.count { it.restTonight } >= 3 }, "still at least 3 blank nights in any 7")
    }

    @Test fun afterAnExfoliatingNightTheNextEveningsAreBlankThenASerumThenBlankAgainUntilTheNextIsAllowed() {
        // Findley glycolic on Friday 19 June; the next week is daytime shifts Mon-Fri, so exfoliating is held back until the next Friday.
        val friday = LocalDate.of(2026, 6, 19)
        val covered = (1..8).map { friday.plusDays(it.toLong()) } // Sat 20 .. Sat 27
        val shifts = (22..26).map { shift(LocalDate.of(2026, 6, it), "12:30", "21:00") }
        val plan = planSkinCare(friday.plusDays(1), 7, SkinCareLog(glycolicNights = setOf(friday)), schedule(shifts, covered))
        val kinds = plan.map { it.eveningKind }
        assertEquals(EveningKind.REST, kinds[0])
        assertTrue(plan[1].isSerum())
        assertEquals(EveningKind.REST, kinds[2])
        assertEquals(after(kinds[1]), kinds[3], "the serums go round in order")
        assertEquals(EveningKind.REST, kinds[4])
        assertEquals(after(kinds[3]), kinds[5])
        assertEquals(EveningKind.TONER, kinds[6], "Friday is safe again, and it is the toner's turn after the treatment")
    }

    @Test fun sunscreenIsAdvisedTheMorningAfterGlycolicAndWhenTheSunIsStrong() {
        val after = planSkinCare(oct19, 1, SkinCareLog(glycolicNights = setOf(oct19.minusDays(1))), noWork).single()
        assertTrue(after.morningAfterGlycolic && after.sunscreenAdvised)
        assertTrue(sunscreenReason(after)!!.contains("Glycolic last night"), sunscreenReason(after))

        val plainOctober = planSkinCare(oct19.plusDays(1), 1, SkinCareLog(glycolicNights = setOf(oct19.minusDays(1))), noWork).single()
        assertFalse(plainOctober.sunscreenAdvised)
        assertNull(sunscreenReason(plainOctober))

        val sunnyJune = planSkinCare(jun15, 1, SkinCareLog(), noWork).single()
        assertTrue(sunnyJune.sunscreenAdvised, "the sun is high in June")
        assertNotNull(sunscreenReason(sunnyJune))
    }

    // ---- the sun and the work schedule ----

    @Test fun inSummerNoGlycolicTheNightBeforeADaytimeShift() {
        val tuesday = jun15.plusDays(1)
        val work = schedule(listOf(shift(tuesday, "12:30", "21:00")), covered = listOf(jun15, tuesday))
        val monNight = planSkinCare(jun15, 1, SkinCareLog(), work).single()
        assertFalse(monNight.glycolicTonight)
        assertTrue(monNight.why.contains("You work tomorrow") && monNight.why.contains("high"), monNight.why)
        assertEquals(Product.MOISTURISER, monNight.evening.last())
    }

    @Test fun inSummerGlycolicIsFineTheNightBeforeADayOff() {
        val tuesday = jun15.plusDays(1)
        val work = schedule(emptyList(), covered = listOf(jun15, tuesday))
        val monNight = planSkinCare(jun15, 1, SkinCareLog(), work).single()
        assertTrue(monNight.glycolicTonight, monNight.why)
        assertTrue(monNight.why.contains("Day off tomorrow"), monNight.why)
    }

    @Test fun inSummerAnEveningShiftThatMissesThePeakSunDoesNotBlockGlycolic() {
        val tuesday = jun15.plusDays(1)
        val work = schedule(listOf(shift(tuesday, "17:00", "23:00")), covered = listOf(jun15, tuesday))
        assertTrue(planSkinCare(jun15, 1, SkinCareLog(), work).single().glycolicTonight)
    }

    @Test fun inSummerWhenTomorrowsWorkIsUnknownItErsOnTheSideOfSkippingGlycolic() {
        val monNight = planSkinCare(jun15, 1, SkinCareLog(), noWork).single()
        assertFalse(monNight.glycolicTonight)
        assertTrue(monNight.why.contains("Not sure"), monNight.why)
    }

    @Test fun inLowSunMonthsWorkDoesNotMatterForGlycolic() {
        val tuesday = oct19.plusDays(1)
        val work = schedule(listOf(shift(tuesday, "08:00", "16:30")), covered = listOf(oct19, tuesday))
        assertTrue(planSkinCare(oct19, 1, SkinCareLog(), work).single().glycolicTonight)
    }

    @Test fun aSkippedGlycolicNightPushesTheNextOneToTheNextSafeNight() {
        // Mon-Fri daytime shifts and Sat/Sun off, in June: the only safe nights are Fri (Sat off) and Sat (Sun off),
        // and those are back to back, so it is Friday only: one exfoliating night a week, never before a workday.
        val week = (0..13).map { jun15.plusDays(it.toLong()) }
        val shifts = week.filter { it.dayOfWeek.value <= 5 }.map { shift(it, "12:30", "21:00") }
        val work = schedule(shifts, covered = week + jun15.plusDays(14))
        val plan = planSkinCare(jun15, 14, SkinCareLog(), work)
        val glycolicDays = plan.filter { it.glycolicTonight }.map { it.date.dayOfWeek.value }
        assertEquals(listOf(5, 5), glycolicDays) // both Fridays (day 5), nothing else
        plan.filter { it.glycolicTonight }.forEach { night ->
            assertTrue(work.shiftOn(night.date.plusDays(1)) == null, "never before a workday")
        }
    }

    @Test fun theRoutineCarriesTodaysShiftSoTheScreenCanShowIt() {
        val work = schedule(listOf(shift(oct19, "12:30", "21:00")), covered = listOf(oct19))
        val today = planSkinCare(oct19, 1, SkinCareLog(), work).single()
        assertEquals(LocalTime.of(12, 30), today.shift!!.start)
        assertFalse(today.isOff)
    }

    // ---- a real forecast instead of the seasonal estimate ----

    private fun forecast(vararg days: Pair<LocalDate, Double>): UvLookup {
        val known = days.toMap()
        return UvLookup { date -> known[date]?.let(::forecastUv) ?: seasonalUv(date) }
    }

    @Test fun aForecastOfStrongSunBlocksGlycolicBeforeADaytimeShiftEvenInOctober() {
        val tuesday = oct19.plusDays(1)
        val work = schedule(listOf(shift(tuesday, "08:00", "16:30")), covered = listOf(oct19, tuesday))
        val night = planSkinCare(oct19, 1, SkinCareLog(), work, forecast(tuesday to 4.0)).single()
        assertFalse(night.glycolicTonight)
        assertTrue(night.why.contains("UV 4 is forecast"), night.why)
        assertEquals(UvBasis.FORECAST, night.uvTomorrow.basis)
    }

    @Test fun aForecastOfLowSunAllowsGlycolicBeforeADaytimeShiftEvenInJune() {
        val tuesday = jun15.plusDays(1)
        val work = schedule(listOf(shift(tuesday, "12:30", "21:00")), covered = listOf(jun15, tuesday))
        val night = planSkinCare(jun15, 1, SkinCareLog(), work, forecast(tuesday to 1.6)).single()
        assertTrue(night.glycolicTonight, night.why)
        assertTrue(night.why.contains("UV 2 forecast tomorrow"), night.why)
    }

    @Test fun aDayTheForecastDoesNotCoverFallsBackToTheSeasonalEstimate() {
        val night = planSkinCare(jun15, 1, SkinCareLog(), noWork, forecast(jun15.plusDays(5) to 1.0)).single()
        assertEquals(UvBasis.SEASON, night.uvTomorrow.basis)
        assertFalse(night.glycolicTonight, "June, work unknown: skipped as before")
    }

    // ---- what the user recorded, and a plan that is the same whenever it is looked at ----

    @Test fun aSerumRecordedForTonightIsTakenAsItWasEvenWhenThePlanWantedGlycolicAndTomorrowIsBlank() {
        // Nothing recorded and an easy October: tonight would be glycolic.
        assertTrue(planSkinCare(oct19, 1, SkinCareLog(), noWork).single().glycolicTonight)
        // The user says they used Vitamin C instead.
        val log = SkinCareLog(eveningsDone = setOf(oct19), eveningKinds = mapOf(oct19 to EveningKind.BRIGHTEN))
        val plan = planSkinCare(oct19, 3, log, noWork)
        assertEquals(EveningKind.BRIGHTEN, plan[0].eveningKind)
        assertFalse(plan[0].glycolicTonight)
        assertTrue(plan[0].why.contains("You recorded"), plan[0].why)
        // An exfoliating night may follow a serum night when one is due (and here one is) — it is not forced blank.
        assertTrue(plan[1].restTonight || plan[1].glycolicTonight, "the next night is blank or the due exfoliating night")
    }

    @Test fun theNightAfterARecordedSerumIsBlankWhenNoExfoliatingNightIsDue() {
        // Summer with unknown work: no exfoliating night can be due, so the night after a recorded serum is blank.
        val log = SkinCareLog(eveningsDone = setOf(jun15), eveningKinds = mapOf(jun15 to EveningKind.BOTH))
        val plan = planSkinCare(jun15, 3, log, noWork)
        assertEquals(EveningKind.BOTH, plan[0].eveningKind)
        assertEquals(EveningKind.REST, plan[1].eveningKind)
        assertEquals(EveningKind.CALM, plan[2].eveningKind, "and after both serums the cycle goes back to niacinamide")
    }

    @Test fun aRecordedSerumDecidesWhichSerumComesNext() {
        // Summer with unknown work: no glycolic, so serum nights only. Niacinamide on the 12th means Vitamin C is next.
        val log = SkinCareLog(eveningKinds = mapOf(jun15.minusDays(3) to EveningKind.CALM))
        assertEquals(EveningKind.BRIGHTEN, planSkinCare(jun15, 1, log, noWork).single().eveningKind)
        val after = SkinCareLog(eveningKinds = mapOf(jun15.minusDays(3) to EveningKind.BRIGHTEN))
        assertEquals(EveningKind.BOTH, planSkinCare(jun15, 1, after, noWork).single().eveningKind)
    }

    @Test fun aRecordedRestNightIsLeftBlankEvenIfAnExfoliantWasDue() {
        val log = SkinCareLog(eveningKinds = mapOf(oct19 to EveningKind.REST))
        val tonight = planSkinCare(oct19, 1, log, noWork).single()
        assertEquals(EveningKind.REST, tonight.eveningKind)
        assertEquals(listOf(Product.MOISTURISER), tonight.evening)
    }

    @Test fun anUntickedRoutineGivesTheSameAnswerForADayWhenevenItIsLookedAt() {
        // The user never ticks anything. Looking at the routine each day from the 19th must not "start again from
        // nothing" (which would say glycolic every day): each day's evening is what the one plan said it would be.
        val start = oct19
        val whole = planRoutine(start.plusDays(12), start, 3, SkinCareLog(), noWork).associateBy { it.date }
        for (offset in 0..12) {
            val today = start.plusDays(offset.toLong())
            val seenThatDay = planRoutine(today, start, 3, SkinCareLog(), noWork).first { it.date == today }
            assertEquals(whole.getValue(today).eveningKind, seenThatDay.eveningKind, "${today}")
        }
        val kinds = (0..12).map { whole.getValue(start.plusDays(it.toLong())) }
        assertTrue(kinds.count { it.glycolicTonight } in 3..4, "two a week, not one a day")
        kinds.zipWithNext().forEach { (a, b) -> assertFalse(a.glycolicTonight && b.glycolicTonight, "back to back on ${b.date}") }
    }

    // ---- confirming: a night nobody confirmed drops off and the plan moves on ----

    private val oct21 = oct19.plusDays(2)

    @Test fun aPastNightThatWasNeverConfirmedIsMissedAndTheNightsAfterItArePlannedWithoutIt() {
        // As before, the 19th (glycolic) is taken as followed, so by the 21st it is only two nights ago: no glycolic.
        val assumed = planRoutine(oct21, oct19, 0, SkinCareLog(), noWork)
        assertTrue(assumed[0].glycolicTonight && !assumed[0].missed)
        assertFalse(assumed.last().glycolicTonight, "two nights after one, so not yet")
        // Confirming has begun and nobody confirmed the 19th: it counts as not done, so the 21st can take the glycolic.
        val strict = planRoutine(oct21, oct19, 0, SkinCareLog(), noWork, confirmFrom = oct19)
        assertTrue(strict[0].missed && strict[0].glycolicTonight, "the plan had glycolic, nobody confirmed it")
        assertTrue(strict[1].missed, "and nothing was confirmed on the 20th either")
        assertTrue(strict.last().glycolicTonight, "so the plan has moved on: tonight is a glycolic night after all")
        assertFalse(strict.last().missed, "tonight isn't missed — it can still be confirmed")
    }

    @Test fun aConfirmedNightCountsAsBeforeAndIsNotMissed() {
        val log = SkinCareLog(glycolicNights = setOf(oct19), eveningsDone = setOf(oct19))
        val strict = planRoutine(oct21, oct19, 0, log, noWork, confirmFrom = oct19)
        assertFalse(strict[0].missed)
        assertEquals(EveningKind.GLYCOLIC, strict[0].eveningKind)
        assertFalse(strict.last().glycolicTonight, "two nights after a confirmed one: not yet")
    }

    @Test fun nightsBeforeConfirmingBeganAreStillTakenAsFollowed() {
        val strict = planRoutine(oct21, oct19, 0, SkinCareLog(), noWork, confirmFrom = oct21)
        assertTrue(strict.none { it.missed })
        assertEquals(planRoutine(oct21, oct19, 0, SkinCareLog(), noWork).map { it.eveningKind }, strict.map { it.eveningKind }, "exactly the old plan")
    }

    @Test fun aRestNightIsNeverMissedBecauseThereIsNothingToConfirm() {
        val log = SkinCareLog(glycolicNights = setOf(oct19), eveningsDone = setOf(oct19))
        val strict = planRoutine(oct21, oct19, 0, log, noWork, confirmFrom = oct19)
        assertEquals(EveningKind.REST, strict[1].eveningKind, "the night after an exfoliating night is blank")
        assertFalse(strict[1].missed)
    }

    @Test fun todayAndTheDaysAheadAreNeverMissed() {
        val strict = planRoutine(oct21, oct19, 10, SkinCareLog(), noWork, confirmFrom = oct19)
        assertTrue(strict.filter { !it.date.isBefore(oct21) }.none { it.missed })
    }

    @Test fun sayingNoToTonightMakesItMissedAtOnceAndTheNightsAfterItAreReplanned() {
        // The 19th is a glycolic night, so the 20th is a blank rest night …
        val yes = planRoutine(oct19, oct19, 3, SkinCareLog(), noWork, confirmFrom = oct19)
        assertTrue(yes[0].glycolicTonight && !yes[0].missed)
        assertEquals(EveningKind.REST, yes[1].eveningKind)
        // … but with a no to the 19th — answered tonight, not waiting for tomorrow — nothing was used, so the 20th is free for it.
        val no = planRoutine(oct19, oct19, 3, SkinCareLog(eveningsSkipped = setOf(oct19)), noWork, confirmFrom = oct19)
        assertTrue(no[0].missed && no[0].glycolicTonight, "the plan had glycolic, and the answer was no")
        assertTrue(no[1].glycolicTonight && !no[1].missed, "so the plan has moved on: glycolic tomorrow instead")
    }

    @Test fun aNoCountsEvenBeforeConfirmingBeganOrWithNoConfirmDay() {
        val log = SkinCareLog(eveningsSkipped = setOf(oct19))
        assertTrue(planRoutine(oct19, oct19, 1, log, noWork).first().missed, "with no confirm day")
        assertTrue(planRoutine(oct21, oct19, 0, log, noWork, confirmFrom = oct21).first().missed, "and for a day from before confirming began")
    }

    @Test fun anEveningThatWasDoneIsNeverMissedWhateverElseIsRecordedForIt() {
        val log = SkinCareLog(glycolicNights = setOf(oct19), eveningsDone = setOf(oct19), eveningsSkipped = setOf(oct19))
        assertFalse(planRoutine(oct19, oct19, 0, log, noWork, confirmFrom = oct19).single().missed)
    }

    @Test fun aRestNightHasNothingToSayNoTo() {
        val log = SkinCareLog(glycolicNights = setOf(oct19), eveningsDone = setOf(oct19), eveningsSkipped = setOf(oct19.plusDays(1)))
        val rest = planRoutine(oct19.plusDays(1), oct19, 0, log, noWork, confirmFrom = oct19).last()
        assertEquals(EveningKind.REST, rest.eveningKind)
        assertFalse(rest.missed)
    }

    @Test fun withNoConfirmDayNothingIsEverMissed() {
        assertTrue(planRoutine(oct21, oct19, 5, SkinCareLog(), noWork, confirmFrom = null).none { it.missed })
        assertTrue(planSkinCare(oct19, 5, SkinCareLog(), noWork).none { it.missed })
    }

    @Test fun anUnconfirmedSerumNightDoesNotMakeTheNextNightARestNight() {
        // A summer week with a shift every day: no glycolic, so the nights are serum, rest, serum, rest …
        val days = (0..7).map { jun15.plusDays(it.toLong()) }
        val work = schedule(days.map { shift(it, "12:30", "21:00") }, days)
        val jun19 = jun15.plusDays(4)
        val followed = planRoutine(jun19, jun15, 0, SkinCareLog(), work).filter { it.date < jun19 }
        assertTrue(followed.any { it.eveningKind == EveningKind.REST }, "taken as followed, a serum night is followed by a blank one")
        // Confirming has begun and none of those serum nights were confirmed: a serum night that didn't happen needs no rest after it.
        val strict = planRoutine(jun19, jun15, 0, SkinCareLog(), work, confirmFrom = jun15).filter { it.date < jun19 }
        assertTrue(strict.all { it.eveningKind.serum && it.missed }, "every one is a serum night, and none was confirmed")
    }

    @Test fun aSerumNightThatWasConfirmedStillMakesTheNextNightARestNight() {
        val days = (0..7).map { jun15.plusDays(it.toLong()) }
        val work = schedule(days.map { shift(it, "12:30", "21:00") }, days)
        val serum = planRoutine(jun15, jun15, 0, SkinCareLog(), work).single().eveningKind
        assertTrue(serum.serum)
        val log = SkinCareLog(eveningsDone = setOf(jun15), eveningKinds = mapOf(jun15 to serum))
        val next = planRoutine(jun15.plusDays(1), jun15, 0, log, work, confirmFrom = jun15).last()
        assertEquals(EveningKind.REST, next.eveningKind)
    }

    @Test fun theRoutineStartsWhenItWasFirstLookedAtAndNeverMoreThanAYearBack() {
        assertEquals(oct19, routineStart(null, oct19))
        assertEquals(oct19.minusDays(10), routineStart(oct19.minusDays(10), oct19))
        assertEquals(oct19.minusDays(365), routineStart(oct19.minusYears(3), oct19))
    }

    @Test fun theReasonsTalkAboutUvNotAboutTheSun() {
        val low = planSkinCare(oct19, 1, SkinCareLog(), noWork).single()
        assertTrue(low.why.startsWith("UV is low tomorrow"), low.why)
        val summer = planSkinCare(jun15, 1, SkinCareLog(), schedule(listOf(shift(jun15.plusDays(1), "12:30", "21:00")), listOf(jun15, jun15.plusDays(1)))).single()
        assertTrue(summer.why.contains("UV is usually high this time of year"), summer.why)
    }

    @Test fun todaysForecastDecidesTheSunscreenAdviceNotTheSeason() {
        val sunnyToday = planSkinCare(oct19, 1, SkinCareLog(), noWork, forecast(oct19 to 5.0)).single()
        assertTrue(sunnyToday.sunscreenAdvised)
        assertTrue(sunscreenReason(sunnyToday)!!.contains("UV 5 is forecast"), sunscreenReason(sunnyToday))
        val dullToday = planSkinCare(jun15, 1, SkinCareLog(), noWork, forecast(jun15 to 1.0)).single()
        assertFalse(dullToday.sunscreenAdvised, "a grey June day is below UV 3")
    }
}
