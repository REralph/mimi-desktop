package com.example.a2desktop.logic

import java.time.DayOfWeek
import java.time.LocalDate
import java.util.Locale

// The vitamins and supplements routine behind the Vitamins card on the desktop Calendar: what to take on a given
// day, and a safety check of the day's amounts against the published limits. Plain functions of plain data (no
// screen, no database), so it is unit-tested.
//
// Where the limits come from (looked up, not remembered):
//  - NHS, Vitamin D: 10 micrograms (400 IU) a day in autumn and winter; do not take more than 100 micrograms
//    (4,000 IU) a day; too much over a long time can raise calcium (hypercalcaemia), and anyone with a medical condition
//    that may stop them taking this much should ask a doctor. (The NHS page says nothing about combining products: the
//    limit is on the total, so two vitamin D products have to be added up.)
//  - NHS, zinc: do not take more than 25 mg a day from supplements unless a doctor advises it — more can lower
//    the copper the body absorbs. (The older EU figure of 25 mg a day counts food as well.)
//  - NHS, magnesium: 400 mg or less a day from supplements is unlikely to cause harm; more can cause diarrhoea.
//  - NHS, vitamin B6: no more than 10 mg a day from supplements unless a doctor advises it; around 200 mg a day
//    has been linked to nerve damage, and between 10 and 200 mg nobody knows how long is safe. EFSA (2023) puts
//    the upper limit for all sources at 12 mg a day.
//  - Creatine: 3-5 g a day is the usual maintenance dose; the ISSN found no harm in healthy adults (people with
//    kidney or liver problems should ask first).
//  - The black-pepper extract (BioPerine / piperine) can raise how much of other things taken with it is absorbed,
//    medicines included.
//  - The Solgar tablet: retailer listings give about 133 mg magnesium and 8.3 mg vitamin B6 per tablet, and the UK
//    label's dose is 3 tablets a day (= 400 mg magnesium and 25 mg B6), with its own warning about tingling from
//    long-term B6. The user's own pack should be checked.
//  - NHS, vitamin K: adults need about 1 microgram per kg of body weight a day; 1 mg (1,000 micrograms) or less a day of
//    supplements is unlikely to cause harm. (That page does not mention vitamin K2 or warfarin; vitamin K works against
//    warfarin, and the labels of K2 products warn about it, so anyone on warfarin asks their clinic first.)
//  - NHS, vitamin A: an average of 1.5 mg (1,500 micrograms) a day or less from food and supplements together is unlikely to
//    cause harm; liver (and liver products) no more than once a week, and no vitamin A supplements if you eat liver weekly;
//    avoid liver in pregnancy.
//  - NHS, copper: 10 mg or less a day from food, water and supplements is unlikely to cause harm (high zinc lowers the
//    copper absorbed). NHS, iron: 17 mg or less a day of supplements is unlikely to cause harm.
//  - Omega-3 (EPA + DHA): EFSA (2012) found no safety concern at up to 5 g a day from supplements and set no upper limit;
//    the US FDA says no more than 3 g a day. The check uses the lower figure, 3 g.
//  - The newer products' amounts (check your own packs): Nutravita Vitamin K2 MK-7 = 200 µg in 1 tablet (its panel, as listed
//    by retailers); the "Vitamin D 4,000 IU Maximum Strength" softgel = 4,000 IU (100 µg), one a day assumed (the listing's
//    title — its panel could not be read); Zipvit Omega 3 Fish Oil 2000 mg = a serving of 2 softgels with 660 mg EPA + 440 mg
//    DHA (the maker's page as quoted by a search, not read directly); Hirsch organic beef organs = 500 mg capsules, Hirsch
//    suggests building up to 6 a day (3 g) — the organ-mix label could not be read, so the vitamin A, copper and iron are
//    Hirsch's liver-only label per 6 capsules (138 µg RE, 1.4 mg, 0.5 mg), which is a ceiling because the mix has less liver.
// General information, not medical advice.

/** What a supplement adds up to, with the limit it is checked against. */
enum class Nutrient(val label: String, val unit: String, val limit: Double?, val usualMax: Double?, val rule: String) {
    VITAMIN_D("Vitamin D", "µg", 100.0, null, "NHS: no more than 100 µg (4,000 IU) a day"),
    VITAMIN_K("Vitamin K", "µg", 1000.0, null, "NHS: 1 mg (1,000 µg) or less a day of supplements is unlikely to cause harm"),
    VITAMIN_A("Vitamin A", "µg", 1500.0, null, "NHS: 1.5 mg (1,500 µg) or less a day from food and supplements together"),
    OMEGA_3("Omega-3 (EPA + DHA)", "g", 3.0, null, "EFSA: up to 5 g a day from supplements raises no safety concern; the FDA says 3 g (used here)"),
    ZINC("Zinc", "mg", 25.0, null, "NHS: no more than 25 mg a day from supplements"),
    COPPER("Copper", "mg", 10.0, null, "NHS: 10 mg or less a day from food, water and supplements is unlikely to cause harm"),
    MAGNESIUM("Magnesium", "mg", 400.0, null, "NHS: 400 mg or less a day from supplements is unlikely to cause harm"),
    VITAMIN_B6("Vitamin B6", "mg", 10.0, null, "NHS: no more than 10 mg a day from supplements"),
    CREATINE("Creatine", "g", null, 5.0, "Usual dose is 3–5 g a day; no harm found in healthy adults (ISSN)"),
}

/** How a day's amount compares with its limit. */
enum class Verdict(val label: String, val severity: Int) {
    WITHIN("Well within the limit", 0),
    USUAL("In the usual range", 0),
    CLOSE("At or close to the limit", 1),
    OVER("Over the limit", 2),
    ABOVE_USUAL("More than the usual dose", 1),
}

/** Roughly how close to a limit counts as "close". */
private const val CLOSE_FRACTION = 0.8

fun verdictFor(nutrient: Nutrient, amount: Double): Verdict {
    val limit = nutrient.limit
    val usual = nutrient.usualMax
    return when {
        limit != null -> when {
            amount > limit + 1e-9 -> Verdict.OVER
            amount >= limit * CLOSE_FRACTION - 1e-9 -> Verdict.CLOSE
            else -> Verdict.WITHIN
        }
        usual != null -> if (amount > usual + 1e-9) Verdict.ABOVE_USUAL else Verdict.USUAL
        else -> Verdict.WITHIN
    }
}

/** When in the day each one goes. */
enum class Slot(val label: String) {
    MORNING("Morning, with breakfast"),
    MIDDAY("Midday, with lunch"),
    EVENING("Evening, with dinner"),
    TRAINING("Around training"),
}

/** The user's supplements, in the order they are taken through the day. */
enum class Supplement(val label: String, val short: String, val slot: Slot) {
    VITAMIN_D3("Vitabiotics Ultra Vitamin D3 400 IU", "Vitamin D3", Slot.MORNING),
    VITAMIN_D3_4000("Vitamin D 4,000 IU softgel (Maximum Strength D3)", "Vitamin D3 4,000 IU", Slot.MORNING),
    VITAMIN_K2("Nutravita Vitamin K2 MK-7 200 µg", "Vitamin K2", Slot.MORNING),
    CREATINE("Creatine 5 g", "Creatine", Slot.MORNING),
    BEEF_ORGANS("Hirsch Organic Beef Organs", "Beef organs", Slot.MORNING),
    ZINC("Zipvit Zinc 25 mg (bisglycinate)", "Zinc", Slot.MIDDAY),
    MAGNESIUM_B6("Solgar Magnesium with Vitamin B6", "Magnesium + B6", Slot.EVENING),
    OMEGA_3("Zipvit Omega 3 Fish Oil 2000 mg", "Omega-3", Slot.EVENING),
    L_THEANINE_LEMON_BALM("Lamberts L-Theanine with Lemon Balm Vegan", "L-Theanine + Lemon Balm", Slot.EVENING),
    AMINO_ACIDS("Essential Amino Acids 2800 mg with BioPerine", "Amino acids", Slot.TRAINING),
}

/** What one Solgar tablet gives (retailer listings; the user's pack may differ). */
const val MAGNESIUM_PER_TABLET_MG = 400.0 / 3.0
const val B6_PER_TABLET_MG = 25.0 / 3.0

/** Vitamin D in one 4,000 IU softgel: 100 µg — the whole of the NHS daily limit, so it never goes beside the 10 µg tablet (110 µg). */
const val STRONG_D_UG = 100.0

/** Vitamin K2 (MK-7) in one Nutravita tablet. */
const val K2_UG = 200.0

/** Zipvit Omega 3 Fish Oil 2000 mg: a serving is 2 softgels = 2,000 mg fish oil with 660 mg EPA + 440 mg DHA, 1.1 g of EPA + DHA. */
const val OMEGA3_SOFTGELS_PER_SERVING = 2
const val OMEGA3_EPA_MG = 660.0
const val OMEGA3_DHA_MG = 440.0
const val OMEGA3_EPA_DHA_G = (OMEGA3_EPA_MG + OMEGA3_DHA_MG) / 1000.0

/** Hirsch organic beef organs: 500 mg a capsule, and the label's serving is 6 capsules (3 g) a day. */
const val ORGAN_CAPSULE_G = 0.5
const val ORGAN_LABEL_CAPSULES = 6

/** From Hirsch's liver-only label per 6 capsules (vitamin A 138 µg RE, copper 1.4 mg): a ceiling for the organ mix, which has less liver. */
const val ORGAN_VITAMIN_A_UG_PER_CAPSULE = 138.0 / ORGAN_LABEL_CAPSULES
const val ORGAN_COPPER_MG_PER_CAPSULE = 1.4 / ORGAN_LABEL_CAPSULES

/** Most days a week the 4,000 IU softgel can be chosen: three, so the week's average stays about half the NHS limit. */
const val MAX_SOFTGEL_DAYS = 3

/** The choices the user can change. */
data class VitaminSettings(
    /** Zinc is 25 mg a tablet — the whole of the NHS limit — so by default it is not taken every day. */
    val zincEveryDay: Boolean = false,
    /** Solgar Magnesium + B6 tablets a day. One keeps B6 under the NHS 10 mg; the UK label says 3. */
    val magnesiumTablets: Int = 1,
    /** Days a week (1–3) the 4,000 IU softgel takes the place of the 10 µg tablet, October to March. */
    val softgelDays: Int = 3,
    /** Hirsch beef organ capsules a day (1–6): half the label's serving to start with. */
    val organCapsules: Int = 3,
) {
    val tablets: Int get() = magnesiumTablets.coerceIn(1, 3)

    /** [softgelDays] kept in range. */
    val softgelDaysPerWeek: Int get() = softgelDays.coerceIn(1, MAX_SOFTGEL_DAYS)

    /** [organCapsules] kept in range. */
    val organs: Int get() = organCapsules.coerceIn(1, ORGAN_LABEL_CAPSULES)
}

/** The weekdays the 4,000 IU softgel is taken on when it is [perWeek] days a week: Monday; Monday and Thursday; Monday, Wednesday and Friday. */
fun softgelWeekdays(perWeek: Int): Set<DayOfWeek> = when (perWeek.coerceIn(1, MAX_SOFTGEL_DAYS)) {
    1 -> setOf(DayOfWeek.MONDAY)
    2 -> setOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY)
    else -> setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY)
}

/** The weekly pattern zinc starts from (and falls back to when every dose is taken): see [planZinc], which moves it when one is missed. */
val ZINC_DAYS: Set<DayOfWeek> = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY)

/** Whether zinc is taken on [date] by the plain weekly pattern: Monday, Wednesday and Friday, or every day if the user switched that on. */
fun zincTakenOn(date: LocalDate, settings: VitaminSettings): Boolean = settings.zincEveryDay || date.dayOfWeek in ZINC_DAYS

/**
 * Where one vitamin stands on one day.
 *  - [TAKEN]: the user ticked it.
 *  - [NOT_TAKEN]: the user said no to it.
 *  - [PRESUMED]: it is scheduled that day, the day is today or gone, nobody ticked it, and the day is BEFORE ticking began —
 *    so, by the old default rule, it counts as taken ([countsAsTaken]); history from before ticks existed is left as it was.
 *    From the day ticking began ("tickFrom") an unticked dose is never presumed: it is [PLANNED] while its day is still
 *    going, and [NOT_TAKEN] once the day has passed.
 *  - [PLANNED]: scheduled for today (not ticked yet) or a day still to come; nothing is recorded for it.
 *  - [NONE]: not scheduled that day.
 */
enum class DoseState {
    TAKEN, NOT_TAKEN, PRESUMED, PLANNED, NONE;

    /** Whether the dose counts as taken: ticked, or scheduled and never said otherwise. */
    val countsAsTaken: Boolean get() = this == TAKEN || this == PRESUMED

    /** Whether anything is on the plan for it that day (ticked, scheduled, or said no to). */
    val isOnTheDay: Boolean get() = this != NONE
}

/**
 * The state of one supplement on [date]: what the user said ([mark]: true = taken, false = not taken, null = nothing) wins;
 * otherwise a scheduled dose is still planned (a day to come), or — from [tickFrom], the day ticking began — planned
 * while today is still going and NOT taken once the day has passed with no tick, or, on a day before [tickFrom] (or with no
 * [tickFrom]), presumed taken as it always was.
 */
fun supplementState(date: LocalDate, today: LocalDate, scheduled: Boolean, mark: Boolean?, tickFrom: LocalDate? = null): DoseState = when {
    mark == true -> DoseState.TAKEN
    mark == false -> DoseState.NOT_TAKEN
    !scheduled -> DoseState.NONE
    date.isAfter(today) -> DoseState.PLANNED
    tickFrom != null && !date.isBefore(tickFrom) -> if (date == today) DoseState.PLANNED else DoseState.NOT_TAKEN
    else -> DoseState.PRESUMED
}

/** Most zinc doses in any 7 days: 3 x 25 mg is 75 mg a week, about 10.7 mg a day — what Monday, Wednesday and Friday gave. */
const val MAX_ZINC_DOSES_PER_WEEK = 3

/** How far ahead the zinc plan reaches. */
const val ZINC_PLAN_DAYS = 366

/**
 * When zinc is taken, worked out day by day. A day the user ticked is [DoseState.TAKEN] and a day they said no to is
 * [DoseState.NOT_TAKEN]; any other day the rules allow a dose is [DoseState.PRESUMED] (today and earlier — nobody said
 * otherwise, so it counts as taken) or [DoseState.PLANNED] (later); the rest are [DoseState.NONE].
 */
class ZincPlan internal constructor(
    private val today: LocalDate,
    private val start: LocalDate,
    private val taken: Set<LocalDate>,
    private val skipped: Set<LocalDate>,
    private val eligible: Set<LocalDate>,
    private val tickFrom: LocalDate? = null,
) {
    /**
     * Whether the rules put a dose on [date] before anything is said about that day — the day a tick or a no would be about.
     * Before the day tracking began that is the plain Monday / Wednesday / Friday pattern, whatever "zinc every day" says now:
     * switching it on doesn't repaint the past.
     */
    fun scheduledByDefault(date: LocalDate): Boolean = date in eligible || (date.isBefore(start) && zincTakenOn(date, VitaminSettings()))

    fun stateOn(date: LocalDate): DoseState = when {
        date in taken -> DoseState.TAKEN
        date in skipped -> DoseState.NOT_TAKEN
        scheduledByDefault(date) -> when {
            date.isAfter(today) -> DoseState.PLANNED
            tickFrom != null && !date.isBefore(tickFrom) -> if (date == today) DoseState.PLANNED else DoseState.NOT_TAKEN // ticking has begun: no tick, no dose
            else -> DoseState.PRESUMED
        }
        else -> DoseState.NONE
    }

    /** The first day on or after [from] that zinc is scheduled and still open (a day already ticked or said no to doesn't count), or null. */
    fun nextPlanned(from: LocalDate): LocalDate? = eligible.filter { !it.isBefore(from) && it !in taken && it !in skipped }.minOrNull()
}

/**
 * The zinc plan, replayed from [trackedFrom] (the first day this was looked at; today if there is none) through the days
 * to come. [taken] are the days the user ticked a dose and [skipped] the days they said no to. Every day is scheduled
 * if [VitaminSettings.zincEveryDay]; otherwise, going day by day, a day is scheduled when neither of these holds:
 *  - zinc was taken (or scheduled) the day before — never back to back, so the dose is spread out;
 *  - it would be the fourth dose in 7 days — three a week keeps zinc to about 10.7 mg a day on average (NHS limit
 *    25 mg a day from supplements, and one tablet is 25 mg).
 * Scheduled as early as those rules allow. A day nobody ticked counts as a dose (the default is "taken"), so with
 * nothing ticked it is Monday, Wednesday, Friday; a day the user said no to is not a dose, so the days after it are
 * scheduled again from there — a no to Monday gives Tuesday, Thursday, Saturday. The days before [trackedFrom] are taken
 * as having followed the old Monday / Wednesday / Friday pattern, so the plan carries on without a jump. (The spacing
 * is a design choice, not a medical standard.)
 *
 * From [tickFrom] (the day ticking began) a day before today that was not ticked counts like one said no to — no tick, no
 * dose — so the plan moves on from it; today, until it is ticked, still counts as a dose to come. Days before [tickFrom]
 * keep the "untouched counts as taken" rule.
 */
fun planZinc(
    today: LocalDate,
    trackedFrom: LocalDate?,
    taken: Set<LocalDate>,
    settings: VitaminSettings,
    aheadDays: Int = ZINC_PLAN_DAYS,
    skipped: Set<LocalDate> = emptySet(),
    tickFrom: LocalDate? = null,
): ZincPlan {
    val start = minOf(trackedFrom ?: today, today)
    val last = today.plusDays(aheadDays.toLong())
    val days = generateSequence(start) { it.plusDays(1) }.takeWhile { !it.isAfter(last) }
    if (settings.zincEveryDay) return ZincPlan(today, start, taken, skipped, days.toSet(), tickFrom)
    // Days no tick came for, once ticking had begun, are not doses either.
    val unticked = tickFrom?.let { from -> generateSequence(from) { it.plusDays(1) }.takeWhile { it.isBefore(today) }.filter { it !in taken }.toSet() }.orEmpty()
    val missed = skipped + unticked
    val everyDefaultDay = VitaminSettings()
    val doses = generateSequence(start.minusDays(7)) { it.plusDays(1) }.takeWhile { it.isBefore(start) }
        .filter { zincTakenOn(it, everyDefaultDay) && it !in missed }.toMutableSet()
    doses += taken
    val eligible = LinkedHashSet<LocalDate>()
    for (day in days) {
        val backToBack = day.minusDays(1) in doses
        val lastSix = (1..6).count { day.minusDays(it.toLong()) in doses }
        val allowed = !backToBack && lastSix < MAX_ZINC_DOSES_PER_WEEK
        if (allowed) eligible += day
        if (allowed && day !in taken && day !in missed) doses += day // a day ticked is already in doses; a day said no to, or never ticked, is not a dose
    }
    return ZincPlan(today, start, taken, skipped, eligible, tickFrom)
}

/**
 * The supplements that are NOT every day, marked on the Calendar's days: zinc on its days, and the amino acids on
 * days a workout was recorded. (Creatine, vitamin K2, the beef organs, magnesium + B6, the omega-3, the L-theanine with lemon
 * balm and — October to March — vitamin D are every day, so they need no mark on each one; the 4,000 IU softgel days are not marked.)
 */
enum class VitaminMark(val code: String, val label: String) {
    ZINC("Zn", "Zinc day"),
    AMINO("AA", "Amino acids — training day"),
}

/** One mark on a day, and where that dose stands ([DoseState.TAKEN], [DoseState.PRESUMED] or [DoseState.PLANNED] — never one that isn't on). */
data class DayVitaminMark(val mark: VitaminMark, val state: DoseState)

/** The marks for a day: zinc and the amino acids, each by its [DoseState] — nothing for [DoseState.NONE] or [DoseState.NOT_TAKEN]. */
fun vitaminMarksFor(zinc: DoseState, amino: DoseState): List<DayVitaminMark> =
    listOfNotNull(
        DayVitaminMark(VitaminMark.ZINC, zinc).takeIf { zinc != DoseState.NONE && zinc != DoseState.NOT_TAKEN },
        DayVitaminMark(VitaminMark.AMINO, amino).takeIf { amino != DoseState.NONE && amino != DoseState.NOT_TAKEN },
    )

/** The marks for [date] under [plan]; [amino] is the amino acids' state that day (see [supplementState]). */
fun vitaminMarksOn(date: LocalDate, plan: ZincPlan, amino: DoseState): List<DayVitaminMark> = vitaminMarksFor(plan.stateOn(date), amino)

/** Vitamin D is for October to March (the NHS advises it in autumn and winter). */
fun isVitaminDSeason(date: LocalDate): Boolean = date.monthValue >= 10 || date.monthValue <= 3

/** Whether the 4,000 IU softgel is taken on [date]: in the vitamin D season, on the weekdays [softgelWeekdays] gives. It replaces the 10 µg tablet that day. */
fun softgelTakenOn(date: LocalDate, settings: VitaminSettings): Boolean =
    isVitaminDSeason(date) && date.dayOfWeek in softgelWeekdays(settings.softgelDaysPerWeek)

/** The first day on or after [from] the 4,000 IU softgel is taken (looking a little over a year ahead), or null. */
fun nextSoftgelDay(from: LocalDate, settings: VitaminSettings): LocalDate? =
    generateSequence(from) { it.plusDays(1) }.take(400).firstOrNull { softgelTakenOn(it, settings) }

/** Average vitamin D a day, in µg, over a winter week with these settings: the softgel on its days, the 10 µg tablet on the rest. */
fun weeklyAverageVitaminD(settings: VitaminSettings): Double {
    val softgelDays = settings.softgelDaysPerWeek
    return (softgelDays * STRONG_D_UG + (7 - softgelDays) * 10.0) / 7.0
}

/** One supplement on one day: whether it is taken, how much, and why (or why not). */
data class DoseLine(
    val supplement: Supplement,
    val taken: Boolean,
    /** "1 tablet", "5 g" … — empty when not taken. */
    val amount: String,
    /** What it adds up to, per nutrient (empty when not taken). */
    val nutrients: Map<Nutrient, Double>,
    val note: String,
)

private fun trim(value: Double): String = if (value == Math.rint(value)) value.toInt().toString() else String.format(Locale.ROOT, "%.1f", value)

/**
 * Every supplement for [date], taken or not. [trained] is whether a workout was done that day (null when that isn't
 * known yet, as for a day still to come): the amino acids are for training days. [zinc] is whether zinc is taken that
 * day — by default the plain weekly pattern; the Calendar passes what the zinc plan says (see [planZinc]).
 */
fun dosesOn(date: LocalDate, settings: VitaminSettings, trained: Boolean?, zinc: Boolean = zincTakenOn(date, settings)): List<DoseLine> {
    val season = isVitaminDSeason(date)
    val softgelDay = softgelTakenOn(date, settings)
    val softgelDays = settings.softgelDaysPerWeek
    val vitaminD = when {
        !season -> DoseLine(Supplement.VITAMIN_D3, false, "", emptyMap(), "Not needed in spring and summer — starts again in October.")
        softgelDay -> DoseLine(Supplement.VITAMIN_D3, false, "", emptyMap(), "Today the 4,000 IU softgel takes its place — never both: together they would be 110 µg, over the NHS 100 µg limit.")
        else -> DoseLine(Supplement.VITAMIN_D3, true, "1 tablet (400 IU = 10 µg)", mapOf(Nutrient.VITAMIN_D to 10.0), "Every day from October to March, except the $softgelDays day${if (softgelDays == 1) "" else "s"} a week of the 4,000 IU softgel.")
    }
    val strongD = when {
        softgelDay -> DoseLine(Supplement.VITAMIN_D3_4000, true, "1 softgel (4,000 IU = 100 µg)", mapOf(Nutrient.VITAMIN_D to STRONG_D_UG), "With breakfast, with some fat in the meal. It is the whole NHS limit in one softgel, so it replaces the 10 µg tablet today.")
        season -> DoseLine(Supplement.VITAMIN_D3_4000, false, "", emptyMap(), "Not a 4,000 IU day — $softgelDays a week, the 10 µg tablet on the others.")
        else -> DoseLine(Supplement.VITAMIN_D3_4000, false, "", emptyMap(), "Not needed in spring and summer — starts again in October.")
    }
    val k2 = DoseLine(Supplement.VITAMIN_K2, true, "1 tablet (200 µg MK-7)", mapOf(Nutrient.VITAMIN_K to K2_UG), "With breakfast, with some fat in the meal. Ask first if you take warfarin or another blood thinner.")
    val creatine = DoseLine(Supplement.CREATINE, true, "5 g", mapOf(Nutrient.CREATINE to 5.0), "Any time, at the same time every day.")
    val zincLine = if (zinc) {
        DoseLine(Supplement.ZINC, true, "1 tablet (25 mg)", mapOf(Nutrient.ZINC to 25.0), "With lunch, not on an empty stomach.")
    } else {
        DoseLine(Supplement.ZINC, false, "", emptyMap(), "Not a zinc day — three days a week, never back to back, keeps it under the NHS limit.")
    }
    val capsules = settings.organs
    val organs = DoseLine(
        Supplement.BEEF_ORGANS,
        true,
        "$capsules capsule${if (capsules == 1) "" else "s"} (${trim(capsules * ORGAN_CAPSULE_G)} g)",
        mapOf(Nutrient.VITAMIN_A to capsules * ORGAN_VITAMIN_A_UG_PER_CAPSULE, Nutrient.COPPER to capsules * ORGAN_COPPER_MG_PER_CAPSULE),
        "Morning or early afternoon, with food. Hirsch's label serving is $ORGAN_LABEL_CAPSULES a day.",
    )
    val tablets = settings.tablets
    val magnesium = DoseLine(
        Supplement.MAGNESIUM_B6,
        true,
        "$tablets tablet${if (tablets == 1) "" else "s"} (${trim(tablets * MAGNESIUM_PER_TABLET_MG)} mg magnesium, ${trim(tablets * B6_PER_TABLET_MG)} mg B6)",
        mapOf(Nutrient.MAGNESIUM to tablets * MAGNESIUM_PER_TABLET_MG, Nutrient.VITAMIN_B6 to tablets * B6_PER_TABLET_MG),
        "With dinner.",
    )
    val omega3 = DoseLine(
        Supplement.OMEGA_3,
        true,
        "$OMEGA3_SOFTGELS_PER_SERVING softgels (2,000 mg fish oil: ${trim(OMEGA3_EPA_MG)} mg EPA + ${trim(OMEGA3_DHA_MG)} mg DHA)",
        mapOf(Nutrient.OMEGA_3 to OMEGA3_EPA_DHA_G),
        "With dinner. Ask first if you take a blood thinner.",
    )
    // The pack's dose isn't known here, so none is claimed; and no nutrient is counted (the safety check has no limit for it).
    val calm = DoseLine(Supplement.L_THEANINE_LEMON_BALM, true, "As the pack says", emptyMap(), "In the evening, with dinner or before bed. Take it as the pack says.")
    val amino = when (trained) {
        true -> DoseLine(Supplement.AMINO_ACIDS, true, "1 serving (2800 mg)", emptyMap(), "Training day — around the workout, or with a meal low in protein.")
        false -> DoseLine(Supplement.AMINO_ACIDS, false, "", emptyMap(), "Training days only.")
        null -> DoseLine(Supplement.AMINO_ACIDS, false, "", emptyMap(), "On the days you train.")
    }
    return listOf(vitaminD, strongD, k2, creatine, organs, zincLine, magnesium, omega3, calm, amino)
}

/** One supplement on one day for the Vitamins card: where it stands, and what it is (see [dosesOn]). */
data class VitaminEntry(val supplement: Supplement, val state: DoseState, val line: DoseLine, val scheduledByDefault: Boolean)

/**
 * All the supplements for [date], each with where it stands: what the user ticked or unticked on that day ([marks]:
 * true = taken, false = not taken — a supplement with no mark is untouched), else what is scheduled. Zinc is scheduled
 * by [zinc] (see [planZinc]); the others by [dosesOn]. Before [tickFrom] (the day ticking began) an untouched day counts
 * every scheduled supplement as [DoseState.PRESUMED] taken, as it always did; from [tickFrom] an unticked one is
 * [DoseState.PLANNED] today and [DoseState.NOT_TAKEN] once its day has gone (see [supplementState]). A day to come is
 * [DoseState.PLANNED] and never recorded. [trained] is whether a workout was recorded that day (null for a day to come).
 */
fun vitaminEntries(
    date: LocalDate,
    today: LocalDate,
    settings: VitaminSettings,
    trained: Boolean?,
    zinc: ZincPlan,
    marks: Map<Supplement, Boolean>,
    tickFrom: LocalDate? = null,
): List<VitaminEntry> {
    val zincState = zinc.stateOn(date)
    return dosesOn(date, settings, trained, zinc = zinc.scheduledByDefault(date)).map { line ->
        val state = if (line.supplement == Supplement.ZINC) zincState else supplementState(date, today, line.taken, marks[line.supplement], tickFrom)
        VitaminEntry(line.supplement, state, line, scheduledByDefault = line.taken)
    }
}

/** One nutrient's total for a day against its limit. */
data class NutrientCheck(val nutrient: Nutrient, val amount: Double, val verdict: Verdict) {
    /** How much of the limit (or the usual dose) it is, 0..1+ — for a bar. */
    val fraction: Double get() = amount / (nutrient.limit ?: nutrient.usualMax ?: amount.coerceAtLeast(1.0))
}

/** The totals of what is taken on that day, each checked against its limit. */
fun checkDay(doses: List<DoseLine>): List<NutrientCheck> =
    Nutrient.entries.mapNotNull { nutrient ->
        val total = doses.filter { it.taken }.sumOf { it.nutrients[nutrient] ?: 0.0 }
        if (total <= 0.0) null else NutrientCheck(nutrient, total, verdictFor(nutrient, total))
    }

/** The worst thing a day's checks say, 0 (all fine) to 2 (something over a limit). */
fun worstSeverity(checks: List<NutrientCheck>): Int = checks.maxOfOrNull { it.verdict.severity } ?: 0

/** Average zinc a day over a week with these settings, in mg (from the tablets alone). */
fun weeklyAverageZinc(settings: VitaminSettings): Double = (if (settings.zincEveryDay) 7 else ZINC_DAYS.size) * 25.0 / 7.0

/** Monday to Sunday of the week [date] is in. */
fun weekOf(date: LocalDate): List<LocalDate> {
    val monday = date.minusDays((date.dayOfWeek.value - 1).toLong())
    return (0..6).map { monday.plusDays(it.toLong()) }
}

fun formatAmount(nutrient: Nutrient, amount: Double): String = "${trim(amount)} ${nutrient.unit}"
