package com.example.a2desktop

import com.example.a2desktop.data.VitaminStore
import com.example.a2desktop.logic.B6_PER_TABLET_MG
import com.example.a2desktop.logic.DayVitaminMark
import com.example.a2desktop.logic.DoseState
import com.example.a2desktop.logic.MAGNESIUM_PER_TABLET_MG
import com.example.a2desktop.logic.Nutrient
import com.example.a2desktop.logic.Slot
import com.example.a2desktop.logic.Supplement
import com.example.a2desktop.logic.Verdict
import com.example.a2desktop.logic.VitaminMark
import com.example.a2desktop.logic.VitaminSettings
import com.example.a2desktop.logic.checkDay
import com.example.a2desktop.logic.dosesOn
import com.example.a2desktop.logic.isVitaminDSeason
import com.example.a2desktop.logic.nextSoftgelDay
import com.example.a2desktop.logic.planZinc
import com.example.a2desktop.logic.supplementState
import com.example.a2desktop.logic.verdictFor
import com.example.a2desktop.logic.vitaminEntries
import com.example.a2desktop.logic.vitaminMarksFor
import com.example.a2desktop.logic.weekOf
import com.example.a2desktop.logic.weeklyAverageVitaminD
import com.example.a2desktop.logic.weeklyAverageZinc
import com.example.a2desktop.logic.zincTakenOn
import com.example.a2desktop.logic.worstSeverity
import java.io.File
import java.nio.file.Files
import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** What is taken on which day, and whether each day's amounts are inside the NHS limits. */
class SupplementsTest {
    private val dir: File = Files.createTempDirectory("a2-vit").toFile()
    private val file = File(dir, "vitamins.json")

    @AfterTest fun cleanUp() {
        dir.deleteRecursively()
    }

    // 2026-10-26 is a Monday; 2026-10-28 a Wednesday; 2026-10-30 a Friday; 2026-10-27 a Tuesday.
    private val monday = LocalDate.of(2026, 10, 26)
    private val tuesday = monday.plusDays(1)
    private val defaults = VitaminSettings()

    private fun taken(date: LocalDate, settings: VitaminSettings = defaults, trained: Boolean? = false) =
        dosesOn(date, settings, trained).filter { it.taken }.map { it.supplement }

    // ---- which days ----

    @Test fun vitaminDIsForOctoberToMarchOnly() {
        listOf(10, 11, 12, 1, 2, 3).forEach { assertTrue(isVitaminDSeason(LocalDate.of(2026, it, 15)), "month $it") }
        listOf(4, 5, 6, 7, 8, 9).forEach { assertFalse(isVitaminDSeason(LocalDate.of(2026, it, 15)), "month $it") }
        assertTrue(Supplement.VITAMIN_D3 in taken(LocalDate.of(2026, 10, 1)))
        assertTrue(Supplement.VITAMIN_D3 in taken(LocalDate.of(2027, 3, 30)), "a Tuesday: the 10 µg tablet day")
        assertFalse(Supplement.VITAMIN_D3 in taken(LocalDate.of(2027, 4, 1)))
        assertTrue(dosesOn(LocalDate.of(2027, 7, 1), defaults, false).first { it.supplement == Supplement.VITAMIN_D3 }.note.contains("October"))
    }

    @Test fun creatineAndMagnesiumAreEveryDay() {
        (0..13).map { monday.plusDays(it.toLong()) }.forEach {
            assertTrue(Supplement.CREATINE in taken(it), "$it")
            assertTrue(Supplement.MAGNESIUM_B6 in taken(it), "$it")
        }
    }

    @Test fun zincIsMondayWednesdayFridayUnlessTheUserTakesItEveryDay() {
        val week = weekOf(monday)
        assertEquals(
            setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY),
            week.filter { Supplement.ZINC in taken(it) }.map { it.dayOfWeek }.toSet(),
        )
        assertEquals(7, week.count { Supplement.ZINC in taken(it, VitaminSettings(zincEveryDay = true)) })
    }

    @Test fun theAminoAcidsAreForTrainingDaysOnly() {
        assertTrue(Supplement.AMINO_ACIDS in taken(monday, trained = true))
        assertFalse(Supplement.AMINO_ACIDS in taken(monday, trained = false))
        assertFalse(Supplement.AMINO_ACIDS in taken(monday, trained = null), "a day still to come isn't assumed to be a training day")
        assertTrue(dosesOn(monday, defaults, null).first { it.supplement == Supplement.AMINO_ACIDS }.note.contains("train"))
    }

    // ---- the amounts, against the limits ----

    @Test fun theDefaultsAreWithinTheNhsLimitsOnEveryDayOfTheWeek() {
        for (day in weekOf(monday)) {
            val checks = checkDay(dosesOn(day, defaults, true))
            assertTrue(checks.none { it.verdict == Verdict.OVER }, "$day: ${checks.filter { it.verdict == Verdict.OVER }}")
        }
    }

    @Test fun vitaminDAt400IuIsATenthOfTheLimit() {
        val check = checkDay(dosesOn(tuesday, defaults, false)).first { it.nutrient == Nutrient.VITAMIN_D } // Monday is a 4,000 IU day
        assertEquals(10.0, check.amount, 1e-9)
        assertEquals(Verdict.WITHIN, check.verdict)
        assertEquals(0.1, check.fraction, 1e-9)
    }

    @Test fun oneZincTabletIsExactlyTheNhsLimitSoItIsAtTheLimitNotOverIt() {
        val check = checkDay(dosesOn(monday, defaults, false)).first { it.nutrient == Nutrient.ZINC }
        assertEquals(25.0, check.amount, 1e-9)
        assertEquals(Verdict.CLOSE, check.verdict, "the whole of the limit, but not above it")
        assertTrue(checkDay(dosesOn(tuesday, defaults, false)).none { it.nutrient == Nutrient.ZINC }, "and nothing on a day it isn't taken")
    }

    @Test fun zincAveragesAboutTenAndAHalfMgADayOnThreeDaysAWeekAndTwentyFiveOnSeven() {
        assertEquals(75.0 / 7.0, weeklyAverageZinc(defaults), 1e-9)
        assertEquals(25.0, weeklyAverageZinc(VitaminSettings(zincEveryDay = true)), 1e-9)
    }

    @Test fun oneMagnesiumAndB6TabletKeepsBothInsideTheNhsLimits() {
        val checks = checkDay(dosesOn(tuesday, defaults, false))
        val mg = checks.first { it.nutrient == Nutrient.MAGNESIUM }
        val b6 = checks.first { it.nutrient == Nutrient.VITAMIN_B6 }
        assertEquals(MAGNESIUM_PER_TABLET_MG, mg.amount, 1e-9)
        assertEquals(Verdict.WITHIN, mg.verdict)
        assertEquals(B6_PER_TABLET_MG, b6.amount, 1e-9)
        assertTrue(b6.amount < 10.0)
        assertEquals(Verdict.CLOSE, b6.verdict, "8.3 of 10 mg: inside, but close")
    }

    @Test fun theUkLabelDoseOfThreeTabletsIsAtTheMagnesiumLimitAndWellOverTheB6Limit() {
        val label = VitaminSettings(magnesiumTablets = 3)
        val checks = checkDay(dosesOn(tuesday, label, false))
        val mg = checks.first { it.nutrient == Nutrient.MAGNESIUM }
        val b6 = checks.first { it.nutrient == Nutrient.VITAMIN_B6 }
        assertEquals(400.0, mg.amount, 1e-9)
        assertEquals(Verdict.CLOSE, mg.verdict, "400 mg is the NHS limit exactly")
        assertEquals(25.0, b6.amount, 1e-9)
        assertEquals(Verdict.OVER, b6.verdict, "25 mg of B6 is two and a half times the NHS 10 mg")
        assertEquals(2, worstSeverity(checks))
    }

    @Test fun twoTabletsAlreadyGoOverTheB6Limit() {
        val b6 = checkDay(dosesOn(tuesday, VitaminSettings(magnesiumTablets = 2), false)).first { it.nutrient == Nutrient.VITAMIN_B6 }
        assertEquals(Verdict.OVER, b6.verdict)
    }

    @Test fun fiveGramsOfCreatineIsTheUsualDoseAndMoreIsFlagged() {
        val check = checkDay(dosesOn(monday, defaults, false)).first { it.nutrient == Nutrient.CREATINE }
        assertEquals(Verdict.USUAL, check.verdict)
        assertEquals(Verdict.ABOVE_USUAL, verdictFor(Nutrient.CREATINE, 8.0))
        assertEquals(Verdict.USUAL, verdictFor(Nutrient.CREATINE, 3.0))
    }

    @Test fun theVerdictBoundariesAreAtEightyPercentAndTheLimit() {
        assertEquals(Verdict.WITHIN, verdictFor(Nutrient.MAGNESIUM, 319.0))
        assertEquals(Verdict.CLOSE, verdictFor(Nutrient.MAGNESIUM, 320.0))
        assertEquals(Verdict.CLOSE, verdictFor(Nutrient.MAGNESIUM, 400.0))
        assertEquals(Verdict.OVER, verdictFor(Nutrient.MAGNESIUM, 400.1))
        assertEquals(Verdict.WITHIN, verdictFor(Nutrient.VITAMIN_D, 10.0))
        assertEquals(Verdict.OVER, verdictFor(Nutrient.VITAMIN_D, 101.0))
    }

    @Test fun theLimitsAreTheOnesTheNhsPublishes() {
        assertEquals(100.0, Nutrient.VITAMIN_D.limit)
        assertEquals(25.0, Nutrient.ZINC.limit)
        assertEquals(400.0, Nutrient.MAGNESIUM.limit)
        assertEquals(10.0, Nutrient.VITAMIN_B6.limit)
        assertEquals(1000.0, Nutrient.VITAMIN_K.limit)
        assertEquals(1500.0, Nutrient.VITAMIN_A.limit)
        assertEquals(10.0, Nutrient.COPPER.limit)
        assertEquals(3.0, Nutrient.OMEGA_3.limit, "the FDA's 3 g, the more careful of it and EFSA's 5 g")
        assertTrue(Nutrient.entries.filter { it.limit != null }.all { it.rule.startsWith("NHS") || it.rule.startsWith("EFSA") }, "each limit says where it comes from")
    }

    @Test fun aSummerDayHasNoVitaminDOrZincChecksOnlyTheDailyOnes() {
        val summerTuesday = LocalDate.of(2027, 7, 6) // no vitamin D, not a zinc day; the daily ones are still taken
        val checks = checkDay(dosesOn(summerTuesday, defaults, false))
        assertEquals(
            setOf(Nutrient.VITAMIN_K, Nutrient.VITAMIN_A, Nutrient.OMEGA_3, Nutrient.COPPER, Nutrient.CREATINE, Nutrient.MAGNESIUM, Nutrient.VITAMIN_B6),
            checks.map { it.nutrient }.toSet(),
        )
        assertTrue(checkDay(emptyList()).isEmpty())
        assertEquals(0, worstSeverity(emptyList()))
    }

    @Test fun theWeekRunsMondayToSunday() {
        val week = weekOf(LocalDate.of(2026, 10, 28)) // a Wednesday
        assertEquals(LocalDate.of(2026, 10, 26), week.first())
        assertEquals(LocalDate.of(2026, 11, 1), week.last())
        assertEquals(7, week.size)
        assertEquals(week, weekOf(week.last()), "the same week from any day in it")
    }

    // ---- the 4,000 IU vitamin D softgel, vitamin K2, omega-3 fish oil and the beef organs ----

    private fun yearFrom(start: LocalDate) = (0 until 366).map { start.plusDays(it.toLong()) }

    @Test fun theSoftgelTakesThePlaceOfTheTabletOnItsDaysAndIsNeverTakenBesideIt() {
        // By default 3 days a week (Monday, Wednesday, Friday), October to March.
        val week = weekOf(monday)
        assertEquals(
            setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY),
            week.filter { Supplement.VITAMIN_D3_4000 in taken(it) }.map { it.dayOfWeek }.toSet(),
        )
        week.forEach { day ->
            val both = Supplement.VITAMIN_D3 in taken(day) && Supplement.VITAMIN_D3_4000 in taken(day)
            assertFalse(both, "$day has both vitamin D products")
            assertTrue(Supplement.VITAMIN_D3 in taken(day) || Supplement.VITAMIN_D3_4000 in taken(day), "$day has some vitamin D in winter")
        }
        assertTrue(dosesOn(monday, defaults, false).first { it.supplement == Supplement.VITAMIN_D3 }.note.contains("never both"), "the tablet says why it is skipped")
    }

    @Test fun theSoftgelDaysFollowTheChoiceOneTwoOrThreeAWeek() {
        mapOf(
            1 to setOf(DayOfWeek.MONDAY),
            2 to setOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY),
            3 to setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY),
        ).forEach { (perWeek, days) ->
            val settings = VitaminSettings(softgelDays = perWeek)
            assertEquals(days, weekOf(monday).filter { Supplement.VITAMIN_D3_4000 in taken(it, settings) }.map { it.dayOfWeek }.toSet(), "$perWeek a week")
            assertEquals(7 - perWeek, weekOf(monday).count { Supplement.VITAMIN_D3 in taken(it, settings) }, "the tablet on the other days")
        }
        assertEquals(3, VitaminSettings(softgelDays = 99).softgelDaysPerWeek)
        assertEquals(1, VitaminSettings(softgelDays = 0).softgelDaysPerWeek)
    }

    @Test fun neitherVitaminDProductIsTakenInSpringAndSummer() {
        (4..9).forEach { month ->
            weekOf(LocalDate.of(2027, month, 14)).forEach { day ->
                assertFalse(Supplement.VITAMIN_D3 in taken(day), "$day")
                assertFalse(Supplement.VITAMIN_D3_4000 in taken(day), "$day")
            }
        }
        assertTrue(dosesOn(LocalDate.of(2027, 7, 5), defaults, false).first { it.supplement == Supplement.VITAMIN_D3_4000 }.note.contains("October"))
    }

    @Test fun vitaminDNeverGoesOverTheNhsLimitOnAnyDayOfTheYearWhateverTheChoice() {
        for (perWeek in 1..3) for (day in yearFrom(LocalDate.of(2026, 10, 1))) {
            val check = checkDay(dosesOn(day, VitaminSettings(softgelDays = perWeek), false)).firstOrNull { it.nutrient == Nutrient.VITAMIN_D }
            if (check != null) assertTrue(check.amount <= 100.0 + 1e-9, "$day ($perWeek a week): ${check.amount} µg")
        }
    }

    @Test fun aSoftgelDayIsAtTheLimitNotOverItAndATabletDayIsATenthOfIt() {
        val softgel = checkDay(dosesOn(monday, defaults, false)).first { it.nutrient == Nutrient.VITAMIN_D }
        assertEquals(100.0, softgel.amount, 1e-9)
        assertEquals(Verdict.CLOSE, softgel.verdict, "4,000 IU is the whole of the NHS limit — flagged as close, not over")
        val tablet = checkDay(dosesOn(tuesday, defaults, false)).first { it.nutrient == Nutrient.VITAMIN_D }
        assertEquals(10.0, tablet.amount, 1e-9)
        assertEquals(Verdict.WITHIN, tablet.verdict)
    }

    @Test fun theWeeklyAverageOfVitaminDIsAboutHalfTheLimitAtThreeDaysAWeek() {
        assertEquals((3 * 100.0 + 4 * 10.0) / 7.0, weeklyAverageVitaminD(defaults), 1e-9)
        assertTrue(weeklyAverageVitaminD(defaults) * 40.0 < 2000.0, "under 2,000 IU a day on average")
        assertEquals((100.0 + 6 * 10.0) / 7.0, weeklyAverageVitaminD(VitaminSettings(softgelDays = 1)), 1e-9)
    }

    @Test fun theNextSoftgelDayIsFoundEvenFromTheMiddleOfSummer() {
        assertEquals(LocalDate.of(2026, 10, 28), nextSoftgelDay(tuesday, defaults), "from Tuesday it is Wednesday")
        assertEquals(monday, nextSoftgelDay(monday, defaults), "a softgel day is its own next day")
        assertEquals(LocalDate.of(2026, 10, 2), nextSoftgelDay(LocalDate.of(2026, 7, 1), defaults), "summer has none: the first Friday of October")
    }

    @Test fun vitaminK2IsOneTabletEveryDayOfTheYearAtAFifthOfTheNhsLimit() {
        yearFrom(LocalDate.of(2026, 10, 1)).forEach { assertTrue(Supplement.VITAMIN_K2 in taken(it), "$it") }
        val check = checkDay(dosesOn(monday, defaults, false)).first { it.nutrient == Nutrient.VITAMIN_K }
        assertEquals(200.0, check.amount, 1e-9)
        assertEquals(0.2, check.fraction, 1e-9)
        assertEquals(Verdict.WITHIN, check.verdict)
        assertEquals(Slot.MORNING, Supplement.VITAMIN_K2.slot)
        assertTrue(dosesOn(monday, defaults, false).first { it.supplement == Supplement.VITAMIN_K2 }.note.contains("warfarin"), "the blood-thinner warning is on it")
    }

    @Test fun omega3IsOneServingOfTwoSoftgelsEveryDayAndWellInsideTheLimit() {
        yearFrom(LocalDate.of(2026, 10, 1)).forEach { assertTrue(Supplement.OMEGA_3 in taken(it), "$it") }
        val line = dosesOn(monday, defaults, false).first { it.supplement == Supplement.OMEGA_3 }
        assertTrue(line.amount.startsWith("2 softgels") && line.amount.contains("660 mg EPA") && line.amount.contains("440 mg DHA"), line.amount)
        val check = checkDay(listOf(line)).single()
        assertEquals(1.1, check.amount, 1e-9, "EPA + DHA together, in grams")
        assertEquals(Verdict.WITHIN, check.verdict)
        assertEquals(Slot.EVENING, Supplement.OMEGA_3.slot)
        assertTrue(line.note.contains("blood thinner"))
    }

    @Test fun theBeefOrgansStartAtHalfTheLabelServingAndScaleWithTheChoice() {
        val half = dosesOn(monday, defaults, false).first { it.supplement == Supplement.BEEF_ORGANS }
        assertEquals("3 capsules (1.5 g)", half.amount)
        assertEquals(69.0, half.nutrients.getValue(Nutrient.VITAMIN_A), 1e-9, "half of Hirsch's 138 µg RE per 6 capsules")
        assertEquals(0.7, half.nutrients.getValue(Nutrient.COPPER), 1e-9, "half of 1.4 mg")
        val label = dosesOn(monday, VitaminSettings(organCapsules = 6), false).first { it.supplement == Supplement.BEEF_ORGANS }
        assertEquals("6 capsules (3 g)", label.amount)
        assertEquals(138.0, label.nutrients.getValue(Nutrient.VITAMIN_A), 1e-9)
        assertEquals(1.4, label.nutrients.getValue(Nutrient.COPPER), 1e-9)
        (1..6).forEach { n ->
            val checks = checkDay(listOf(dosesOn(monday, VitaminSettings(organCapsules = n), false).first { it.supplement == Supplement.BEEF_ORGANS }))
            assertTrue(checks.all { it.verdict == Verdict.WITHIN }, "$n capsules: $checks")
        }
        assertEquals(3, defaults.organs)
        assertEquals(6, VitaminSettings(organCapsules = 99).organs)
        assertEquals(1, VitaminSettings(organCapsules = 0).organs)
        assertEquals(Slot.MORNING, Supplement.BEEF_ORGANS.slot)
    }

    @Test fun everyChoiceOfTheNewItemsIsInsideEveryLimitOnEveryDayOfTheYear() {
        for (zincDaily in listOf(false, true)) for (perWeek in 1..3) for (capsules in 1..6) {
            val settings = VitaminSettings(zincEveryDay = zincDaily, softgelDays = perWeek, organCapsules = capsules)
            for (day in yearFrom(LocalDate.of(2026, 10, 1))) {
                val over = checkDay(dosesOn(day, settings, true)).filter { it.verdict == Verdict.OVER }
                assertTrue(over.isEmpty(), "$day $settings: $over")
            }
        }
    }

    @Test fun theMorningHoldsTheNewFatSolubleOnesAndTheEveningTheFishOil() {
        val morning = Supplement.entries.filter { it.slot == Slot.MORNING }.toSet()
        assertEquals(setOf(Supplement.VITAMIN_D3, Supplement.VITAMIN_D3_4000, Supplement.VITAMIN_K2, Supplement.CREATINE, Supplement.BEEF_ORGANS), morning)
        assertEquals(setOf(Supplement.MAGNESIUM_B6, Supplement.OMEGA_3, Supplement.L_THEANINE_LEMON_BALM), Supplement.entries.filter { it.slot == Slot.EVENING }.toSet())
    }

    @Test fun theLTheanineWithLemonBalmIsAnEveryDayEveningOneThatAddsNothingToTheSafetyCheck() {
        val calm = Supplement.L_THEANINE_LEMON_BALM
        assertEquals("Lamberts L-Theanine with Lemon Balm Vegan", calm.label)
        assertEquals("L-Theanine + Lemon Balm", calm.short, "what the names-only card shows")
        assertEquals(Slot.EVENING, calm.slot)
        yearFrom(LocalDate.of(2026, 10, 1)).forEach { assertTrue(calm in taken(it), "$it: every day of the year") }
        val line = dosesOn(monday, defaults, false).first { it.supplement == calm }
        assertTrue(line.nutrients.isEmpty(), "no nutrient is counted: the check has no limit for it")
        assertFalse(line.amount.contains(Regex("[0-9]")), "no dose is claimed — the pack's isn't known: ${line.amount}")
        val others = dosesOn(monday, defaults, false).filter { it.supplement != calm }
        assertEquals(checkDay(others), checkDay(dosesOn(monday, defaults, false)), "the day's checks are the same with it as without it")
        assertEquals(1, Supplement.entries.count { it.label.contains("Theanine") }, "one entry")
    }

    // ---- ticking: a tick means taken; from the day ticking began, no tick means not taken once the day has passed ----

    private val tickBegan = monday // 26 Oct 2026
    private val wednesday = monday.plusDays(2)
    private val thursday = monday.plusDays(3)

    @Test fun beforeTickingBeganAnUntouchedDoseIsStillPresumedTakenAsItAlwaysWas() {
        assertEquals(DoseState.PRESUMED, supplementState(tickBegan.minusDays(1), thursday, scheduled = true, mark = null, tickFrom = tickBegan))
        assertEquals(DoseState.PRESUMED, supplementState(tickBegan.minusDays(30), thursday, scheduled = true, mark = null, tickFrom = tickBegan))
        assertEquals(DoseState.PRESUMED, supplementState(tickBegan.plusDays(1), thursday, scheduled = true, mark = null, tickFrom = null), "no tick day at all: the old rule")
    }

    @Test fun fromTheDayTickingBeganAnUntickedDoseIsPlannedTodayAndNotTakenOnceTheDayHasPassed() {
        assertEquals(DoseState.NOT_TAKEN, supplementState(tickBegan, thursday, true, null, tickBegan), "the first day, long gone")
        assertEquals(DoseState.NOT_TAKEN, supplementState(wednesday, thursday, true, null, tickBegan), "yesterday")
        assertEquals(DoseState.PLANNED, supplementState(thursday, thursday, true, null, tickBegan), "today is still going: not ticked yet is not 'not taken'")
        assertEquals(DoseState.PLANNED, supplementState(thursday.plusDays(1), thursday, true, null, tickBegan), "a day to come")
        assertFalse(DoseState.NOT_TAKEN.countsAsTaken)
        assertFalse(DoseState.PLANNED.countsAsTaken)
    }

    @Test fun aTickAlwaysMakesItTakenAndADoseNotOnThePlanStaysNone() {
        listOf(tickBegan.minusDays(3), wednesday, thursday).forEach {
            assertEquals(DoseState.TAKEN, supplementState(it, thursday, true, true, tickBegan), "$it")
        }
        assertEquals(DoseState.NOT_TAKEN, supplementState(wednesday, thursday, true, false, tickBegan), "an old explicit no is still a no")
        assertEquals(DoseState.NONE, supplementState(wednesday, thursday, scheduled = false, mark = null, tickFrom = tickBegan))
        assertEquals(DoseState.PLANNED, supplementState(thursday.plusDays(2), thursday, true, null, tickBegan), "a day to come is never recorded")
    }

    @Test fun theEntriesFollowTheSameRuleForEverySupplementOnTheDay() {
        val plan = planZinc(thursday, monday, emptySet(), defaults, tickFrom = tickBegan)
        val today = vitaminEntries(thursday, thursday, defaults, false, plan, emptyMap(), tickBegan).filter { it.scheduledByDefault }
        assertTrue(today.isNotEmpty() && today.all { it.state == DoseState.PLANNED }, "today, nothing ticked yet: ${today.map { it.supplement to it.state }}")
        val yesterday = vitaminEntries(wednesday, thursday, defaults, false, plan, emptyMap(), tickBegan).filter { it.scheduledByDefault }
        assertTrue(yesterday.all { it.state == DoseState.NOT_TAKEN }, "yesterday, nothing ticked: ${yesterday.map { it.supplement to it.state }}")
        val ticked = vitaminEntries(wednesday, thursday, defaults, false, plan, mapOf(Supplement.CREATINE to true), tickBegan).associateBy { it.supplement }
        assertEquals(DoseState.TAKEN, ticked.getValue(Supplement.CREATINE).state)
        assertEquals(DoseState.NOT_TAKEN, ticked.getValue(Supplement.OMEGA_3).state, "only the ticked one")
        val planBefore = planZinc(thursday, monday, emptySet(), defaults, tickFrom = thursday) // ticking began today, so yesterday is history
        val before = vitaminEntries(wednesday, thursday, defaults, false, planBefore, emptyMap(), thursday).filter { it.scheduledByDefault }
        assertTrue(before.all { it.state == DoseState.PRESUMED }, "a day before ticking began is still presumed")
    }

    @Test fun anUntickedZincDayOnceTickingHasBegunIsMissedAndTheNextDayTakesItsPlace() {
        // Monday ticked, Wednesday never ticked, today is Thursday: Wednesday's dose was missed, so Thursday is the next zinc day.
        val plan = planZinc(thursday, monday, setOf(monday), defaults, tickFrom = tickBegan)
        assertEquals(DoseState.TAKEN, plan.stateOn(monday))
        assertEquals(DoseState.NONE, plan.stateOn(monday.plusDays(1)), "the day after a dose")
        assertEquals(DoseState.NOT_TAKEN, plan.stateOn(wednesday), "no tick, no dose")
        assertEquals(DoseState.PLANNED, plan.stateOn(thursday), "so today is a zinc day, still to be ticked")
        assertEquals(DoseState.NONE, plan.stateOn(thursday.plusDays(1)), "and never back to back")
        assertEquals(thursday, plan.nextPlanned(thursday))
    }

    @Test fun whenEveryZincDoseIsTickedItStaysMondayWednesdayFriday() {
        val plan = planZinc(thursday, monday, setOf(monday, wednesday), defaults, tickFrom = tickBegan)
        assertEquals(DoseState.TAKEN, plan.stateOn(wednesday))
        assertEquals(DoseState.NONE, plan.stateOn(thursday), "the day after a dose")
        assertEquals(DoseState.PLANNED, plan.stateOn(thursday.plusDays(1)), "Friday")
    }

    @Test fun zincBeforeTickingBeganKeepsTheOldPresumedPattern() {
        val plan = planZinc(thursday, monday, emptySet(), defaults, tickFrom = thursday)
        assertEquals(DoseState.PRESUMED, plan.stateOn(monday))
        assertEquals(DoseState.PRESUMED, plan.stateOn(wednesday))
        assertEquals(DoseState.NONE, plan.stateOn(thursday), "Wednesday counted as taken, so Thursday is not a zinc day")
    }

    @Test fun theDayTickingBeganIsRememberedOnceAndKeptInTheFile() {
        val store = VitaminStore(file)
        assertEquals(null, store.tickFrom)
        store.ensureStarted(thursday)
        assertEquals(thursday, store.tickFrom)
        assertEquals(thursday, store.trackedFrom)
        store.ensureStarted(thursday.plusDays(9))
        assertEquals(thursday, store.tickFrom, "the first day stands")
        assertEquals(thursday, VitaminStore(file).tickFrom, "and it is saved")
    }

    @Test fun anOldFileWithoutATickDayGetsTodayAndKeepsItsMarksAndItsTrackingDay() {
        val old = monday.toEpochDay()
        file.writeText("""{"trackedFrom":$old,"marks":{"$old":{"ZINC":true}}}""")
        val store = VitaminStore(file)
        assertEquals(null, store.tickFrom)
        store.ensureStarted(thursday)
        assertEquals(thursday, store.tickFrom, "history before today stays as it was")
        assertEquals(monday, store.trackedFrom)
        assertEquals(true, store.markOn(monday, Supplement.ZINC))
        assertEquals(thursday, VitaminStore(file).tickFrom)
        assertEquals(monday, VitaminStore(file).trackedFrom)
    }

    // ---- the zinc plan: worked out day by day; an untouched day counts as taken ----

    /** The days in [start] .. [start] + [days] - 1 on which zinc counts as taken under [plan]. */
    private fun doseDays(plan: com.example.a2desktop.logic.ZincPlan, start: LocalDate, days: Int) =
        (0 until days).map { start.plusDays(it.toLong()) }.filter { plan.stateOn(it).countsAsTaken }.toSet()

    private fun assertSpaced(taken: Set<LocalDate>) {
        taken.forEach { day ->
            assertFalse(day.minusDays(1) in taken, "$day is the day after another dose")
            val inWeek = (0..6).count { day.minusDays(it.toLong()) in taken }
            assertTrue(inWeek <= 3, "$day is the ${inWeek}th dose in 7 days")
        }
    }

    @Test fun withNothingSaidTheZincPlanIsMondayWednesdayAndFridayAndEveryDayCountsAsTaken() {
        val plan = planZinc(monday.plusDays(34), monday, emptySet(), defaults)
        val taken = doseDays(plan, monday, 35)
        assertEquals(15, taken.size, "three a week for five weeks")
        assertEquals(setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY), taken.map { it.dayOfWeek }.toSet())
        assertTrue(taken.all { plan.stateOn(it) == DoseState.PRESUMED }, "nobody ticked them: presumed, not ticked")
    }

    @Test fun startingMidWeekCarriesOnTheOldPatternWithoutAJump() {
        val thursday = LocalDate.of(2026, 10, 29)
        val plan = planZinc(thursday, thursday, emptySet(), defaults)
        assertEquals(DoseState.NONE, plan.stateOn(thursday), "Wednesday was a zinc day, so Thursday is not")
        val next = generateSequence(thursday) { plan.nextPlanned(it.plusDays(1)) }.drop(1).take(4).toList()
        assertEquals(listOf(30, 2, 4, 6), next.map { it.dayOfMonth }, "Friday, then Monday, Wednesday, Friday")
    }

    @Test fun aDoseSaidNotToHaveBeenTakenMovesTheWholePlanOn() {
        val fri = LocalDate.of(2026, 10, 30)
        val mon = LocalDate.of(2026, 11, 2)
        val tue = LocalDate.of(2026, 11, 3)
        // Friday was ticked; Monday 2 November was said not to have been taken.
        val plan = planZinc(tue, LocalDate.of(2026, 10, 29), setOf(fri), defaults, skipped = setOf(mon))
        assertEquals(DoseState.NOT_TAKEN, plan.stateOn(mon))
        val next = generateSequence(tue) { plan.nextPlanned(it.plusDays(1)) }.take(5).toList()
        assertEquals(listOf(3, 5, 7, 10, 12), next.map { it.dayOfMonth }, "Tuesday now, then Thursday, Saturday — the pattern has moved")
        assertEquals(setOf(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY, DayOfWeek.SATURDAY), next.map { it.dayOfWeek }.toSet())
    }

    @Test fun aDayNobodyTouchedIsNotAMissedDoseSoThePlanDoesNotMove() {
        // The same week as above, but Monday 2 November was never touched: it counts as taken, so Wednesday and Friday follow.
        val plan = planZinc(LocalDate.of(2026, 11, 3), LocalDate.of(2026, 10, 29), setOf(LocalDate.of(2026, 10, 30)), defaults)
        assertEquals(DoseState.PRESUMED, plan.stateOn(LocalDate.of(2026, 11, 2)))
        val next = generateSequence(LocalDate.of(2026, 11, 3)) { plan.nextPlanned(it.plusDays(1)) }.drop(1).take(3).toList()
        assertEquals(listOf(4, 6, 9), next.map { it.dayOfMonth }, "Wednesday, Friday, Monday: the pattern didn't move")
    }

    @Test fun sayingNoToTodaysDoseMovesThePlanOnAtOnce() {
        // Monday is a zinc day. A no to it, answered on Monday itself, gives Tuesday, Thursday, Saturday — no waiting for the day to pass.
        val no = planZinc(monday, monday, emptySet(), defaults, skipped = setOf(monday))
        assertEquals(DoseState.NOT_TAKEN, no.stateOn(monday))
        val next = generateSequence(monday) { no.nextPlanned(it.plusDays(1)) }.drop(1).take(3).toList()
        assertEquals(listOf(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY, DayOfWeek.SATURDAY), next.map { it.dayOfWeek })
        assertEquals(DoseState.PRESUMED, planZinc(monday, monday, emptySet(), defaults).stateOn(monday), "without the no it counts as taken")
    }

    @Test fun aNoOnTheEveryDaySettingDropsOnlyThatDay() {
        val every = VitaminSettings(zincEveryDay = true)
        val plan = planZinc(monday, monday, emptySet(), every, skipped = setOf(monday.plusDays(2)))
        assertEquals(DoseState.NOT_TAKEN, plan.stateOn(monday.plusDays(2)))
        assertEquals(DoseState.PLANNED, plan.stateOn(monday.plusDays(1)))
        assertEquals(DoseState.PLANNED, plan.stateOn(monday.plusDays(3)))
        assertEquals(DoseState.PRESUMED, plan.stateOn(monday), "today, untouched")
    }

    @Test fun aNoAtTheEdgeOfTheOldPatternFreesTheFirstDayAfterIt() {
        // Tracking starts on a Thursday, straight after the old Wednesday dose, so Thursday isn't a zinc day — unless Wednesday is said not to have happened.
        val thursday = LocalDate.of(2026, 10, 29)
        val wednesday = thursday.minusDays(1)
        assertEquals(DoseState.NONE, planZinc(thursday, thursday, emptySet(), defaults).stateOn(thursday))
        assertEquals(DoseState.PRESUMED, planZinc(thursday, thursday, emptySet(), defaults, skipped = setOf(wednesday)).stateOn(thursday))
    }

    @Test fun aDoseTakenOnAnOffDayIsRespectedByTheDaysAfterIt() {
        val wed = LocalDate.of(2026, 10, 28)
        val plan = planZinc(wed, monday, setOf(monday, tuesday), defaults) // Tuesday was an extra
        assertEquals(DoseState.NONE, plan.stateOn(wed), "the day after a dose")
        assertEquals(DoseState.NONE, plan.stateOn(LocalDate.of(2026, 10, 29)), "and a fourth dose in a week")
        assertEquals(LocalDate.of(2026, 10, 30), plan.nextPlanned(wed))
        assertEquals(DoseState.TAKEN, plan.stateOn(tuesday))
    }

    @Test fun theSpacingRulesHoldWhateverIsSaidNoTo() {
        val random = java.util.Random(42)
        repeat(6) {
            val no = (0 until 60).filter { random.nextInt(10) < 3 }.map { monday.plusDays(it.toLong()) }.toSet()
            val start = monday.plusDays(random.nextInt(7).toLong())
            val doses = doseDays(planZinc(start.plusDays(59), start, emptySet(), defaults, skipped = no), start, 60)
            assertSpaced(doses)
            assertTrue(doses.size >= 12, "a plan that keeps moving on still has doses (${doses.size})")
        }
    }

    @Test fun everyDaySettingSchedulesEveryDayAndATickMakesItTaken() {
        val every = VitaminSettings(zincEveryDay = true)
        val plan = planZinc(monday, monday, setOf(monday), every)
        assertEquals(DoseState.TAKEN, plan.stateOn(monday))
        assertTrue((1..30).all { plan.stateOn(monday.plusDays(it.toLong())) == DoseState.PLANNED })
    }

    @Test fun aPastDayIsPresumedTakenUnlessSaidOtherwiseAndTodayToo() {
        val wed = LocalDate.of(2026, 10, 28)
        val plan = planZinc(wed, monday, setOf(monday), defaults)
        assertEquals(DoseState.TAKEN, plan.stateOn(monday), "a past dose that was ticked")
        assertEquals(DoseState.NONE, plan.stateOn(tuesday), "the day after it is not a zinc day")
        assertEquals(DoseState.PRESUMED, plan.stateOn(wed), "today: nobody said, so it counts as taken")
        assertEquals(DoseState.PLANNED, plan.stateOn(wed.plusDays(2)), "a day to come is only planned")
        assertEquals(DoseState.TAKEN, planZinc(wed, monday, setOf(monday, wed), defaults).stateOn(wed), "and ticked once it is")
    }

    @Test fun daysBeforeTrackingBeganFollowTheOldPatternAndCountAsTakenToo() {
        val plan = planZinc(LocalDate.of(2026, 11, 5), LocalDate.of(2026, 11, 5), emptySet(), defaults)
        assertEquals(DoseState.PRESUMED, plan.stateOn(LocalDate.of(2026, 11, 2)), "a Monday before tracking began")
        assertEquals(DoseState.NONE, plan.stateOn(LocalDate.of(2026, 11, 3)), "a Tuesday")
    }

    @Test fun theNextPlannedDayLeavesOutADoseAlreadyTickedOrSaidNoTo() {
        val wed = LocalDate.of(2026, 10, 28)
        assertEquals(LocalDate.of(2026, 10, 30), planZinc(wed, monday, setOf(monday, wed), defaults).nextPlanned(wed), "today is ticked, so Friday is next")
        assertEquals(LocalDate.of(2026, 10, 29), planZinc(wed, monday, emptySet(), defaults, skipped = setOf(wed)).nextPlanned(wed), "today is said no to, so the next open day is Thursday")
    }

    // ---- where each dose stands, and the default of "taken" ----

    @Test fun whatTheUserSaidWinsAndAnUntouchedScheduledDoseIsPresumedTakenOrPlanned() {
        val today = LocalDate.of(2026, 11, 4)
        assertEquals(DoseState.TAKEN, supplementState(today, today, scheduled = false, mark = true), "ticked, even if it wasn't on the plan")
        assertEquals(DoseState.NOT_TAKEN, supplementState(today, today, scheduled = true, mark = false))
        assertEquals(DoseState.PRESUMED, supplementState(today, today, scheduled = true, mark = null), "today")
        assertEquals(DoseState.PRESUMED, supplementState(today.minusDays(9), today, scheduled = true, mark = null), "a past day")
        assertEquals(DoseState.PLANNED, supplementState(today.plusDays(1), today, scheduled = true, mark = null), "a day to come is not presumed taken")
        assertEquals(DoseState.NONE, supplementState(today, today, scheduled = false, mark = null))
        assertTrue(DoseState.TAKEN.countsAsTaken && DoseState.PRESUMED.countsAsTaken)
        assertFalse(DoseState.NOT_TAKEN.countsAsTaken || DoseState.PLANNED.countsAsTaken || DoseState.NONE.countsAsTaken)
    }

    private val noon = LocalDate.of(2026, 11, 4) // a Wednesday, in the vitamin D season, with zinc scheduled

    private fun entries(date: LocalDate, today: LocalDate, marks: Map<Supplement, Boolean> = emptyMap(), trained: Boolean? = false, settings: VitaminSettings = defaults) =
        vitaminEntries(date, today, settings, trained, planZinc(today, LocalDate.of(2026, 10, 29), emptySet(), settings), marks).associateBy { it.supplement }

    @Test fun anUntouchedDayPresumesEverythingScheduledTakenAndNothingElse() {
        val e = entries(noon, noon)
        assertEquals(DoseState.NONE, e.getValue(Supplement.VITAMIN_D3).state, "Wednesday is a 4,000 IU day, which takes the tablet's place")
        assertEquals(DoseState.PRESUMED, e.getValue(Supplement.VITAMIN_D3_4000).state)
        assertEquals(DoseState.PRESUMED, e.getValue(Supplement.VITAMIN_K2).state)
        assertEquals(DoseState.PRESUMED, e.getValue(Supplement.BEEF_ORGANS).state)
        assertEquals(DoseState.PRESUMED, e.getValue(Supplement.OMEGA_3).state)
        assertEquals(DoseState.PRESUMED, e.getValue(Supplement.CREATINE).state)
        assertEquals(DoseState.PRESUMED, e.getValue(Supplement.ZINC).state, "Wednesday is a zinc day")
        assertEquals(DoseState.PRESUMED, e.getValue(Supplement.MAGNESIUM_B6).state)
        assertEquals(DoseState.NONE, e.getValue(Supplement.AMINO_ACIDS).state, "no workout that day")
        assertTrue(e.values.filter { it.state != DoseState.NONE }.all { it.state.countsAsTaken }, "never read as nothing taken")
    }

    @Test fun sayingOneVitaminWasNotTakenLeavesTheRestPresumedAndWorksOnZincToo() {
        val e = entries(noon, noon, marks = mapOf(Supplement.VITAMIN_D3 to false))
        assertEquals(DoseState.NOT_TAKEN, e.getValue(Supplement.VITAMIN_D3).state)
        assertEquals(DoseState.PRESUMED, e.getValue(Supplement.CREATINE).state)
        val zincTicked = vitaminEntries(noon, noon, defaults, false, planZinc(noon, noon.minusDays(6), setOf(noon), defaults), mapOf(Supplement.ZINC to true)).associateBy { it.supplement }
        assertEquals(DoseState.TAKEN, zincTicked.getValue(Supplement.ZINC).state)
    }

    @Test fun aVitaminNotOnThePlanCanStillBeTickedAsAnExtraAndKnowsItWasNotScheduled() {
        val e = entries(LocalDate.of(2026, 11, 3), noon, marks = mapOf(Supplement.AMINO_ACIDS to true))
        assertEquals(DoseState.TAKEN, e.getValue(Supplement.AMINO_ACIDS).state)
        assertFalse(e.getValue(Supplement.AMINO_ACIDS).scheduledByDefault, "ticking it again should forget it, not say no")
        assertTrue(e.getValue(Supplement.CREATINE).scheduledByDefault)
    }

    @Test fun aDayToComeIsPlannedNotPresumedTaken() {
        val e = entries(noon.plusDays(2), noon, trained = null)
        assertEquals(DoseState.PLANNED, e.getValue(Supplement.CREATINE).state)
        assertTrue(e.values.none { it.state.countsAsTaken }, "nothing about a day to come counts as taken")
    }

    @Test fun vitaminDIsOnlyOnThePlanInTheAutumnAndWinter() {
        assertEquals(DoseState.NONE, entries(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 1)).getValue(Supplement.VITAMIN_D3).state)
        assertEquals(DoseState.PRESUMED, entries(LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 1)).getValue(Supplement.VITAMIN_D3).state)
    }

    @Test fun aTrainingDayPutsTheAminoAcidsOnThePlan() {
        assertEquals(DoseState.PRESUMED, entries(noon, noon, trained = true).getValue(Supplement.AMINO_ACIDS).state)
    }

    @Test fun marksShowZincAndTheAminoAcidsByWhereTheyStand() {
        assertEquals(listOf(DayVitaminMark(VitaminMark.ZINC, DoseState.TAKEN)), vitaminMarksFor(DoseState.TAKEN, DoseState.NONE))
        assertEquals(listOf(DayVitaminMark(VitaminMark.ZINC, DoseState.PRESUMED)), vitaminMarksFor(DoseState.PRESUMED, DoseState.NONE))
        assertEquals(listOf(DayVitaminMark(VitaminMark.ZINC, DoseState.PLANNED)), vitaminMarksFor(DoseState.PLANNED, DoseState.NONE))
        assertTrue(vitaminMarksFor(DoseState.NONE, DoseState.NONE).isEmpty())
        assertTrue(vitaminMarksFor(DoseState.NOT_TAKEN, DoseState.NOT_TAKEN).isEmpty(), "a dose said no to has no mark")
        assertEquals(
            listOf(DayVitaminMark(VitaminMark.ZINC, DoseState.PLANNED), DayVitaminMark(VitaminMark.AMINO, DoseState.PRESUMED)),
            vitaminMarksFor(DoseState.PLANNED, DoseState.PRESUMED),
        )
    }

    @Test fun theDaysListFollowsThePlanForZinc() {
        val withZinc = dosesOn(tuesday, defaults, false, zinc = true)
        assertTrue(withZinc.first { it.supplement == Supplement.ZINC }.taken)
        assertTrue(checkDay(withZinc).any { it.nutrient == Nutrient.ZINC })
        val without = dosesOn(monday, defaults, false, zinc = false)
        assertFalse(without.first { it.supplement == Supplement.ZINC }.taken, "a Monday the plan has moved off")
        assertTrue(checkDay(without).none { it.nutrient == Nutrient.ZINC })
    }

    @Test fun theWeeklyPatternIsStillMondayWednesdayFridayOrEveryDay() {
        assertEquals(listOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY), weekOf(monday).filter { zincTakenOn(it, defaults) }.map { it.dayOfWeek })
        assertTrue(weekOf(monday).all { zincTakenOn(it, VitaminSettings(zincEveryDay = true)) })
    }

    // ---- what the user said is remembered, per date ----

    @Test fun whatIsSaidAboutOneDayIsSavedAndNeverTouchesAnotherDay() {
        val store = VitaminStore(file)
        assertTrue(store.marks.isEmpty())
        store.ensureStarted(monday)
        store.ensureStarted(monday.plusDays(5))
        assertEquals(monday, store.trackedFrom, "the first day is kept")
        assertTrue(store.marks.isEmpty(), "starting to track writes no vitamin marks — untouched days stay untouched")
        store.setMark(monday, Supplement.ZINC, true)
        store.setMark(monday, Supplement.VITAMIN_D3, false)
        store.setMark(tuesday, Supplement.ZINC, false)
        val reopened = VitaminStore(file)
        assertEquals(mapOf(Supplement.ZINC to true, Supplement.VITAMIN_D3 to false), reopened.marksOn(monday))
        assertEquals(mapOf(Supplement.ZINC to false), reopened.marksOn(tuesday))
        assertTrue(reopened.marksOn(monday.plusDays(2)).isEmpty(), "a day nobody touched has nothing")
        assertEquals(monday, reopened.trackedFrom)
        assertEquals(defaults, reopened.settings, "the choices are untouched")
        store.setMark(monday, Supplement.VITAMIN_D3, true)
        assertEquals(mapOf(Supplement.ZINC to false), store.marksOn(tuesday), "changing Monday left Tuesday alone")
    }

    @Test fun forgettingAMarkOrAWholeDayBringsBackThePresumption() {
        val store = VitaminStore(file)
        store.setMark(monday, Supplement.CREATINE, false)
        store.setMark(monday, Supplement.ZINC, true)
        store.setMark(monday, Supplement.CREATINE, null)
        assertEquals(mapOf(Supplement.ZINC to true), store.marksOn(monday))
        assertTrue(store.isTouched(monday))
        store.resetDay(monday)
        assertFalse(store.isTouched(monday))
        assertTrue(VitaminStore(file).marks.isEmpty(), "and the file has nothing for it")
        store.resetDay(tuesday) // an untouched day: nothing happens
    }

    @Test fun theZincHelpersAreTheSameMarksAndAYesAndANoReplaceEachOther() {
        val store = VitaminStore(file)
        store.setZincTaken(monday, true)
        store.setZincSkipped(monday, true)
        assertTrue(store.zincTaken.isEmpty(), "a no takes the yes back")
        assertEquals(setOf(monday), store.zincSkipped)
        assertEquals(setOf(monday), VitaminStore(file).zincSkipped, "and is saved")
        assertEquals(DoseState.NOT_TAKEN, store.zincPlan(monday).stateOn(monday))
        store.setZincTaken(monday, true)
        assertTrue(store.zincSkipped.isEmpty(), "a yes cancels the no")
        store.setZincSkipped(monday, true)
        store.setZincSkipped(monday, false)
        assertTrue(store.zincSkipped.isEmpty() && store.zincTaken.isEmpty(), "taking a no back leaves the day untouched")
        assertTrue(VitaminStore(file).marks.isEmpty())
    }

    @Test fun theOldZincOnlyRecordsAreKeptAsMarksAndMovedAcrossOnTheNextSave() {
        val old = monday.toEpochDay()
        file.writeText("""{"zincTaken":[$old],"zincSkipped":[${old + 2}],"trackedFrom":$old,"zincEveryDay":false,"magnesiumTablets":2}""")
        val store = VitaminStore(file)
        assertEquals(mapOf(Supplement.ZINC to true), store.marksOn(monday), "a zinc dose that was ticked before")
        assertEquals(mapOf(Supplement.ZINC to false), store.marksOn(monday.plusDays(2)), "and one that was said no to")
        assertEquals(2, store.settings.tablets)
        store.setMark(tuesday, Supplement.CREATINE, true) // any save writes the new format
        val text = file.readText()
        assertTrue("zincTaken" !in text && "zincSkipped" !in text, "the old lists are no longer written: $text")
        val reopened = VitaminStore(file)
        assertEquals(mapOf(Supplement.ZINC to true), reopened.marksOn(monday), "nothing was lost")
        assertEquals(mapOf(Supplement.ZINC to false), reopened.marksOn(monday.plusDays(2)))
        assertEquals(mapOf(Supplement.CREATINE to true), reopened.marksOn(tuesday))
        reopened.setZincTaken(monday, false) // taking an old tick back must stick after a restart
        assertTrue(VitaminStore(file).marksOn(monday).isEmpty())
    }

    @Test fun aMarkWithANameThisVersionDoesNotKnowIsJustSkipped() {
        file.writeText("""{"marks":{"${monday.toEpochDay()}":{"ZINC":true,"PROTEIN_SHAKE":true}}}""")
        assertEquals(mapOf(Supplement.ZINC to true), VitaminStore(file).marksOn(monday))
    }

    // ---- what the user chose is remembered ----

    @Test fun theChoicesAreSavedAndKeptInRange() {
        val store = VitaminStore(file)
        assertEquals(defaults, store.settings)
        store.setZincEveryDay(true)
        store.setMagnesiumTablets(3)
        store.setSoftgelDays(2)
        store.setOrganCapsules(6)
        assertEquals(VitaminSettings(zincEveryDay = true, magnesiumTablets = 3, softgelDays = 2, organCapsules = 6), VitaminStore(file).settings, "saved")
        store.setMagnesiumTablets(9)
        assertEquals(3, store.settings.tablets)
        store.setMagnesiumTablets(0)
        assertEquals(1, store.settings.tablets)
        store.setSoftgelDays(9)
        assertEquals(3, store.settings.softgelDaysPerWeek, "never more than three 4,000 IU days a week")
        assertEquals(3, store.settings.softgelDays, "the stored number itself is kept in range, not just read back clamped")
        store.setSoftgelDays(0)
        assertEquals(1, store.settings.softgelDaysPerWeek)
        store.setOrganCapsules(99)
        assertEquals(6, store.settings.organs, "never more than the label's six")
        assertEquals(6, store.settings.organCapsules, "the stored number itself is kept in range, not just read back clamped")
        store.setOrganCapsules(0)
        assertEquals(1, store.settings.organs)
        assertEquals(1, VitaminStore(file).settings.organs, "and the kept-in-range value is what is saved")
    }

    @Test fun anOldFileWithoutTheNewChoicesGetsTheirDefaults() {
        file.writeText("""{"zincEveryDay":true,"magnesiumTablets":2}""")
        val settings = VitaminStore(file).settings
        assertEquals(VitaminSettings(zincEveryDay = true, magnesiumTablets = 2), settings)
        assertEquals(3, settings.softgelDaysPerWeek)
        assertEquals(3, settings.organs)
    }

    @Test fun aMissingOrDamagedFileJustMeansTheDefaults() {
        assertEquals(defaults, VitaminStore(file).settings)
        file.writeText("not json")
        assertEquals(defaults, VitaminStore(file).settings)
        file.writeText("""{"zincEveryDay":true,"magnesiumTablets":99}""")
        assertEquals(VitaminSettings(zincEveryDay = true, magnesiumTablets = 3), VitaminStore(file).settings, "an out-of-range number is brought back in")
        file.writeText("""{"softgelDays":9,"organCapsules":0}""")
        assertEquals(VitaminSettings(softgelDays = 3, organCapsules = 1), VitaminStore(file).settings, "so are the new two")
        assertNotNull(VitaminStore(null).settings)
    }
}
