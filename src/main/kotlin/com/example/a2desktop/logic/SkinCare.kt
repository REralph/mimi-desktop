package com.example.a2desktop.logic

import java.time.LocalDate
import java.time.Month
import java.time.temporal.ChronoUnit
import java.util.Locale

// The skin-care routine behind the Skin care card, the Calendar's evening markers and the Skin care
// page on the desktop: what to put on tonight, spaced out around the user's work shifts and the sun.
// The user uses products in the EVENINGS only; mornings stay light (just the moisturiser), and some
// evenings are deliberately BLANK (nothing active on) so the skin gets time to recover.
// Everything here is a plain function of plain data (no database, no Android, no network), so it is
// unit-tested on the development machine. The sun comes in through [UvLookup]: a real forecast when the
// user has set a town (see data/Weather.kt), the seasonal estimate when not.
//
// What the rules are based on (checked online, not guessed):
//  - FDA: AHAs such as glycolic acid can raise the skin's sensitivity to the sun while they are used and
//    for up to a week afterwards, so daily sunscreen is advised throughout (fda.gov, "Alpha Hydroxy Acids").
//  - AAD, Cleveland Clinic, Harvard Health: exfoliate at most 2-3 times a week, because skin needs time to
//    repair between sessions; stinging, redness, flaking or tightness mean stop and let it recover.
//  - The Ordinary's own guidance for the Glycolic Acid 7% Exfoliating Toner: evenings, start at 2-3 times a
//    week, patch test, don't combine with other direct acids in the same routine, sunscreen and limited sun
//    while using it and for a week after.
//  - WHO: sun protection is advised from UV index 3 upwards, none is needed below that; 3-5 is moderate,
//    6-7 is high (who.int, "The ultraviolet (UV) index").
//  - UK sun: strongest 11am-3pm, and UV index 3 or more is typical from about April to September
//    (Met Office / NHS); the UK's index practically never goes above 8.
//  - The user's own products: Findley glycolic + vitamin C treatment and The Ordinary Glycolic Acid 7% toner
//    (both exfoliating, so they share one allowance and never share a night); The Ordinary Niacinamide 10% +
//    Zinc 1%; Minimalist Vitamin C 10% (listed as a morning serum, moved to evenings because the user only
//    uses products then); CeraVe Moisturising Cream.
//  - NO source gives an exact number of rest nights. The rest rules below are a deliberately cautious design
//    choice, not a medical standard.
// General information, not medical advice.

/** The user's products, with the short name the Calendar row shows. */
enum class Product(val label: String, val short: String) {
    GLYCOLIC("Findley Glycolic acid + Vitamin C", "Glycolic + Vit C"),
    TONER("The Ordinary Glycolic Acid 7% Exfoliating Toner", "Glycolic toner"),
    VITAMIN_C("Minimalist Vitamin C 10%", "Vitamin C"),
    NIACINAMIDE("The Ordinary Niacinamide 10% + Zinc 1%", "Niacinamide"),
    MOISTURISER("CeraVe Moisturising Cream", "Moisturiser"),

    /** Not one of the user's listed products — advised because the FDA recommends daily sunscreen with AHAs. */
    SUNSCREEN("Sunscreen SPF 30+ (not in your list)", "SPF"),
}

/** How strong the sun is (the WHO bands): Low is below index 3, Moderate 3-5, High 6 and over. */
enum class UvTier(val label: String) { LOW("low"), MODERATE("moderate"), HIGH("high") }

/** Where a [UvReading] came from: a real forecast for the user's town, or the seasonal estimate. */
enum class UvBasis { FORECAST, SEASON }

/**
 * The sun on one day: the forecast UV index when there is one, and always the WHO band. [tempMaxC] is the day's
 * highest temperature in °C when a forecast (or the recent past) is known, else null.
 */
data class UvReading(val index: Double?, val tier: UvTier, val basis: UvBasis, val tempMaxC: Double? = null) {
    val needsProtection: Boolean get() = tier != UvTier.LOW

    /** "UV 5" for a forecast, "UV low" for the seasonal estimate. */
    fun short(): String = index?.let { "UV ${Math.round(it)}" } ?: "UV ${tier.label}"
}

/** The WHO band for a UV index (the index is reported to the nearest whole number). */
fun uvTierOf(index: Double): UvTier = when (Math.round(index)) {
    in Long.MIN_VALUE..2L -> UvTier.LOW
    in 3L..5L -> UvTier.MODERATE
    else -> UvTier.HIGH
}

fun forecastUv(index: Double, tempMaxC: Double? = null) = UvReading(index, uvTierOf(index), UvBasis.FORECAST, tempMaxC)

/**
 * A seasonal ESTIMATE of the UK's typical clear-day UV for [date] — not a forecast (a cloudy day is
 * lower). April and September are usually moderate, May to August high, the rest low.
 */
fun uvTier(date: LocalDate): UvTier = when (date.month) {
    Month.MAY, Month.JUNE, Month.JULY, Month.AUGUST -> UvTier.HIGH
    Month.APRIL, Month.SEPTEMBER -> UvTier.MODERATE
    else -> UvTier.LOW
}

fun seasonalUv(date: LocalDate) = UvReading(null, uvTier(date), UvBasis.SEASON)

/** The sun on a given day — however it is known. */
fun interface UvLookup {
    fun on(date: LocalDate): UvReading
}

/** The seasonal estimate for every day: what the planner uses until a forecast is available. */
val SeasonalUv = UvLookup { seasonalUv(it) }

/** The tunable rules, in one place. */
object SkinCareRules {
    /** Fewest days between two exfoliation nights (3 = a night on, two nights off) — never back to back. */
    const val MIN_DAYS_BETWEEN_GLYCOLIC = 3

    /**
     * Most exfoliation nights in any 7, the Findley treatment and the toner COUNTED TOGETHER (both are glycolic
     * acid). The AAD, Cleveland Clinic and Harvard Health say at most 2-3 a week for one exfoliant; with two
     * glycolic products this keeps to the low end.
     */
    const val MAX_GLYCOLIC_PER_WEEK = 2

    /** UK sun is strongest between 11 and 15; this widens that a little either side (10:00-16:00). */
    const val PEAK_SUN_START_MINUTE = 10 * 60
    const val PEAK_SUN_END_MINUTE = 16 * 60

    /** Time to get to and from work, the only time the user is really outdoors. */
    const val COMMUTE_MINUTES = 30
}

/**
 * The user's actual record: nights an exfoliant really was used ([glycolicNights], whichever product), the
 * ones among them that were the toner rather than the Findley treatment ([tonerNights]), which AM/PM
 * routines were ticked off, and — for the evenings ticked off that were NOT an exfoliating night — which
 * kind of evening it was ([eveningKinds]: niacinamide, Vitamin C or a rest night), so the history can say
 * what was really used rather than guess.
 */
data class SkinCareLog(
    val glycolicNights: Set<LocalDate> = emptySet(),
    val morningsDone: Set<LocalDate> = emptySet(),
    val eveningsDone: Set<LocalDate> = emptySet(),
    val tonerNights: Set<LocalDate> = emptySet(),
    val eveningKinds: Map<LocalDate, EveningKind> = emptyMap(),
    /** Evenings the user said NO to (the ✗ the Check-in card used to have; nothing sets one now, earlier answers still count): the plan drops them straight away, even tonight. */
    val eveningsSkipped: Set<LocalDate> = emptySet(),
    /**
     * Evenings whose product the user CHOSE (the edit button on the Skin care card), on any day — one still to come as well as tonight —
     * without saying it was done. The plan keeps a chosen evening exactly as chosen, and plans the nights after it from it (so a chosen
     * exfoliating night is followed by a rest night, and the next exfoliating night is spaced from it), instead of changing it by itself.
     * What was actually ticked off on that evening still wins.
     */
    val chosenKinds: Map<LocalDate, EveningKind> = emptyMap(),
) {
    /** What was used on [date]'s evening, if the user recorded it: an exfoliant, or the serum/rest kind they ticked. */
    fun usedOn(date: LocalDate): EveningKind? = when {
        date in glycolicNights -> if (date in tonerNights) EveningKind.TONER else EveningKind.GLYCOLIC
        else -> eveningKinds[date]
    }

    /** Whether that evening was ticked off (or recorded as an exfoliating night). */
    fun doneOn(date: LocalDate): Boolean = date in eveningsDone || date in glycolicNights
}

/**
 * What tonight is: an exfoliating night (the treatment or the toner), a serum night (niacinamide, Vitamin C, or
 * BOTH together — the heavier night), or a blank rest night.
 */
enum class EveningKind(val label: String) {
    GLYCOLIC("Findley glycolic"),
    TONER("Glycolic toner"),
    CALM("Niacinamide"),
    BRIGHTEN("Vitamin C"),
    BOTH("Vit C + Niacinamide"),
    REST("Rest night");

    val exfoliation: Boolean get() = this == GLYCOLIC || this == TONER
    val serum: Boolean get() = this == CALM || this == BRIGHTEN || this == BOTH
}

/** The products an evening of this [kind] puts on, in the order they go on. A rest night is just the moisturiser, if wanted. */
fun eveningProducts(kind: EveningKind): List<Product> = when (kind) {
    EveningKind.GLYCOLIC -> listOf(Product.GLYCOLIC, Product.MOISTURISER)
    EveningKind.TONER -> listOf(Product.TONER, Product.MOISTURISER)
    EveningKind.CALM -> listOf(Product.NIACINAMIDE, Product.MOISTURISER)
    EveningKind.BRIGHTEN -> listOf(Product.VITAMIN_C, Product.MOISTURISER)
    EveningKind.BOTH -> listOf(Product.VITAMIN_C, Product.NIACINAMIDE, Product.MOISTURISER)
    EveningKind.REST -> listOf(Product.MOISTURISER)
}

/** How the sun is described in a reason: "UV 5 is forecast" or "the sun is moderate this time of year". */
private fun sunPhrase(uv: UvReading) = when (uv.basis) {
    UvBasis.FORECAST -> "${uv.short()} is forecast"
    UvBasis.SEASON -> "UV is usually ${uv.tier.label} this time of year"
}

/** Why glycolic is wrong for a night, or null if tomorrow carries no sun risk. */
private fun sunRiskTomorrow(tomorrow: LocalDate, work: WorkSchedule, uv: UvReading): String? {
    if (!uv.needsProtection) return null
    val shift = work.shiftOn(tomorrow)
    return when {
        shift != null && goesOutInPeakSun(shift) ->
            "You work tomorrow (${shift.start}–${shift.end}) and ${sunPhrase(uv)} — skip the glycolic"
        shift == null && !work.isOff(tomorrow) ->
            "Not sure about tomorrow's work and ${sunPhrase(uv)} — skipping the glycolic to be safe"
        else -> null
    }
}

/** Whether getting to work, working and getting home overlaps the peak-sun hours of the shift's day. */
internal fun goesOutInPeakSun(shift: WorkShift): Boolean {
    val leaves = shift.start.hour * 60 + shift.start.minute - SkinCareRules.COMMUTE_MINUTES
    val back = shift.end.hour * 60 + shift.end.minute + SkinCareRules.COMMUTE_MINUTES + if (shift.endsNextDay) 24 * 60 else 0
    return leaves < SkinCareRules.PEAK_SUN_END_MINUTE && back > SkinCareRules.PEAK_SUN_START_MINUTE
}

/** One day of the routine: this morning (kept light) and tonight's products, and why. */
data class DayRoutine(
    val date: LocalDate,
    /** Always just the moisturiser — the user keeps mornings light. */
    val morning: List<Product>,
    /** Tonight's products; on a rest night just the moisturiser (nothing active). */
    val evening: List<Product>,
    val eveningKind: EveningKind,
    /** The exfoliating product tonight would use (or did): the Findley treatment and the toner take turns. */
    val exfoliant: Product,
    /** The morning after an exfoliating night: the skin is more sun-sensitive, so sunscreen is worth it. */
    val morningAfterGlycolic: Boolean,
    /** Sunscreen is advised this morning (glycolic last night, or the sun is at 3+) — the user has none yet. */
    val sunscreenAdvised: Boolean,
    /** In plain words, why tonight is what it is. */
    val why: String,
    val shift: WorkShift?,
    val isOff: Boolean,
    val uvToday: UvReading,
    val uvTomorrow: UvReading,
    /**
     * An evening with products on that counts as NOT done — a past one, from the day confirming began, that was never
     * confirmed, or any one (tonight too) the user answered ✗ — so it leaves nothing behind for the nights after it to be
     * spaced from ([eveningKind] is what the plan had for it, kept so it can still be confirmed late). A rest night is
     * never missed: there is nothing to confirm.
     */
    val missed: Boolean = false,
) {
    /** Glycolic acid is on tonight — either product. */
    val glycolicTonight: Boolean get() = eveningKind.exfoliation
    val restTonight: Boolean get() = eveningKind == EveningKind.REST
}

/**
 * The first day the routine is replayed from: when the user began (a day is remembered the first time the routine
 * is looked at), and never more than a year back.
 */
fun routineStart(startedOn: LocalDate?, today: LocalDate): LocalDate = maxOf(startedOn ?: today, today.minusDays(365))

/**
 * The whole routine from its start through [ahead] days after [today]. Days before [confirmFrom] the user didn't tick
 * count as FOLLOWED — the plan carries on from them — unless they recorded something different, so tonight's answer is
 * the same whenever it is looked at. From [confirmFrom] on, a past evening only counts if it was confirmed (see
 * [DayRoutine.missed]): one that wasn't is dropped and the nights after it are planned without it. With [confirmFrom]
 * null nothing is ever dropped.
 */
fun planRoutine(
    today: LocalDate,
    startedOn: LocalDate?,
    ahead: Int,
    log: SkinCareLog,
    work: WorkSchedule,
    uv: UvLookup = SeasonalUv,
    confirmFrom: LocalDate? = null,
): List<DayRoutine> {
    val start = routineStart(startedOn, today)
    return planSkinCare(start, (ChronoUnit.DAYS.between(start, today) + ahead + 1).toInt().coerceAtLeast(1), log, work, uv, confirmFrom, today)
}

/** The serum that follows [last] one: niacinamide, then Vitamin C, then BOTH (the heavier night), then round again. */
private fun nextSerum(last: EveningKind?, date: LocalDate): EveningKind = when (last) {
    EveningKind.CALM -> EveningKind.BRIGHTEN
    EveningKind.BRIGHTEN -> EveningKind.BOTH
    EveningKind.BOTH -> EveningKind.CALM
    else -> if (date.toEpochDay() % 2 == 0L) EveningKind.BRIGHTEN else EveningKind.CALM
}

/**
 * The routine for [days] days from [from]. An exfoliating night (glycolic acid, the Findley treatment or the
 * toner — they take turns and count together) goes on when all of these hold: tomorrow carries no sun risk
 * (see [sunRiskTomorrow]; the sun comes from [uv]); it is at least [SkinCareRules.MIN_DAYS_BETWEEN_GLYCOLIC]
 * days since the last one; and fewer than [SkinCareRules.MAX_GLYCOLIC_PER_WEEK] in the past 6 nights. History
 * comes from [log] (what was really used); nights the log says nothing about are assumed to follow the plan, so
 * the spacing carries on correctly. A night the user recorded — an exfoliating night, or a serum or rest night —
 * is taken as it was, and the nights after it follow from it. When [confirmFrom] and [today] are given, a night from
 * [confirmFrom] up to (not including) [today] that the log doesn't show as done is MISSED, and so is a night the user
 * answered ✗ ([SkinCareLog.eveningsSkipped]): the plan doesn't count it (no exfoliation or serum is remembered from
 * it), so the nights after it are chosen as if it hadn't been planned.
 *
 * Every other night is a serum night (niacinamide, Vitamin C, or both together as the heavier night, taking turns)
 * or a BLANK rest night: the night after an exfoliating night is always blank, and so is the night after a serum
 * night — so some evenings have nothing active on. Every evening that has anything on ends with the moisturiser.
 */
fun planSkinCare(
    from: LocalDate,
    days: Int,
    log: SkinCareLog,
    work: WorkSchedule,
    uv: UvLookup = SeasonalUv,
    confirmFrom: LocalDate? = null,
    today: LocalDate? = null,
): List<DayRoutine> {
    val used = log.glycolicNights.toMutableSet()
    val toner = log.tonerNights.toMutableSet()
    // The serum nights so far: what the user recorded (even from before [from]), then what the plan puts on.
    val serums = log.eveningKinds.filterValues { it.serum }.toMutableMap()
    return (0 until days).map { offset ->
        val date = from.plusDays(offset.toLong())
        val unconfirmed = confirmFrom != null && today != null && !date.isBefore(confirmFrom) && date.isBefore(today)
        val missed = (unconfirmed || date in log.eveningsSkipped) && !log.doneOn(date)
        val tomorrow = date.plusDays(1)
        val yesterday = date.minusDays(1)
        val last = used.filter { it.isBefore(date) }.maxOrNull()
        val sinceLast = last?.let { ChronoUnit.DAYS.between(it, date) }
        val inLastSix = used.count { !it.isBefore(date.minusDays(6)) && it.isBefore(date) }
        val uvToday = uv.on(date)
        val uvTomorrow = uv.on(tomorrow)
        val risk = sunRiskTomorrow(tomorrow, work, uvTomorrow)

        // The two glycolic products take turns, starting with the Findley treatment.
        val alreadyUsed = date in used
        // What the user ticked off (a serum or rest night), or — failing that — the evening they chose themselves (any kind but an exfoliant here).
        val chosen = log.chosenKinds[date]
        val recorded = log.eveningKinds[date]?.takeIf { !it.exfoliation } ?: chosen?.takeIf { !it.exfoliation }
        val chosenExfoliant = chosen?.takeIf { it.exfoliation && !alreadyUsed && date !in log.eveningKinds }
        val exfoliant = when {
            alreadyUsed -> if (date in toner) Product.TONER else Product.GLYCOLIC
            chosenExfoliant != null -> if (chosenExfoliant == EveningKind.TONER) Product.TONER else Product.GLYCOLIC
            last == null -> Product.GLYCOLIC
            last in toner -> Product.GLYCOLIC
            else -> Product.TONER
        }

        val exfoliate: Boolean
        var whyNot = ""
        var whyYes = ""
        when {
            alreadyUsed -> { exfoliate = true; whyYes = "Glycolic used tonight" }
            chosenExfoliant != null -> {
                exfoliate = true
                // The user's choice stands; say plainly if the plan would have held back (so it is a choice made knowingly).
                val heldBack = when {
                    risk != null -> risk
                    sinceLast != null && sinceLast < SkinCareRules.MIN_DAYS_BETWEEN_GLYCOLIC ->
                        "the last glycolic was ${if (sinceLast == 1L) "last night" else "$sinceLast nights ago"}"
                    inLastSix >= SkinCareRules.MAX_GLYCOLIC_PER_WEEK -> "that's already $inLastSix exfoliating nights this week"
                    else -> null
                }
                whyYes = "You chose ${chosenExfoliant.label.lowercase()} for this evening" + (heldBack?.let { " — the plan would have held back: $it" } ?: "")
                if (!missed) { // a chosen night counts for the nights after it, as a used one does
                    used += date
                    if (exfoliant == Product.TONER) toner += date
                }
            }
            recorded != null -> { exfoliate = false; whyNot = (if (date in log.eveningKinds) "You recorded " else "You chose ") + recorded.label.lowercase() + " for this evening" }
            risk != null -> { exfoliate = false; whyNot = risk }
            sinceLast != null && sinceLast < SkinCareRules.MIN_DAYS_BETWEEN_GLYCOLIC -> {
                exfoliate = false
                whyNot = "No glycolic tonight — the last one was ${if (sinceLast == 1L) "last night" else "$sinceLast nights ago"}"
            }
            inLastSix >= SkinCareRules.MAX_GLYCOLIC_PER_WEEK -> {
                exfoliate = false
                whyNot = "No glycolic tonight — that's already $inLastSix exfoliating nights this week (the treatment and the toner count together)"
            }
            else -> {
                exfoliate = true
                val shiftTomorrow = work.shiftOn(tomorrow)
                val forecast = if (uvTomorrow.basis == UvBasis.FORECAST) " (${uvTomorrow.short()} forecast)" else ""
                whyYes = when {
                    !uvTomorrow.needsProtection && uvTomorrow.basis == UvBasis.FORECAST ->
                        "${uvTomorrow.short()} forecast tomorrow — low, a good night for glycolic"
                    !uvTomorrow.needsProtection -> "UV is low tomorrow — a good night for glycolic"
                    work.isOff(tomorrow) -> "Day off tomorrow$forecast — a good night for glycolic"
                    shiftTomorrow != null -> "Your shift tomorrow (${shiftTomorrow.start}–${shiftTomorrow.end}) is out of the peak sun$forecast — fine for glycolic"
                    else -> "A good night for glycolic"
                }
                if (!missed) { // a night that was never confirmed leaves nothing for the later nights to be spaced from
                    used += date
                    if (exfoliant == Product.TONER) toner += date
                }
            }
        }

        val kind = when {
            exfoliate -> if (exfoliant == Product.TONER) EveningKind.TONER else EveningKind.GLYCOLIC
            recorded != null -> recorded
            yesterday in used || yesterday in serums -> EveningKind.REST
            else -> nextSerum(serums.filterKeys { it.isBefore(date) }.maxByOrNull { it.key }?.value, date)
        }
        if (kind.serum && !missed) serums[date] = kind

        val afterGlycolic = yesterday in used
        DayRoutine(
            date = date,
            morning = listOf(Product.MOISTURISER),
            evening = eveningProducts(kind),
            eveningKind = kind,
            exfoliant = exfoliant,
            morningAfterGlycolic = afterGlycolic,
            sunscreenAdvised = afterGlycolic || uvToday.needsProtection,
            why = when {
                kind.exfoliation -> whyYes
                recorded != null -> whyNot
                kind == EveningKind.REST ->
                    if (yesterday in used) "Rest night — you exfoliated last night, so nothing active tonight"
                    else "Rest night — a break after last night's serum, nothing active tonight"
                kind == EveningKind.BOTH -> "$whyNot. Both serums together tonight — your heavier night."
                else -> whyNot
            },
            shift = work.shiftOn(date),
            isOff = work.isOff(date),
            uvToday = uvToday,
            uvTomorrow = uvTomorrow,
            missed = missed && kind != EveningKind.REST, // a rest night has nothing to put on, so nothing to miss
        )
    }
}
/** Why a sunscreen is worth it this morning, or null if it isn't. The user doesn't have one in their list. */
fun sunscreenReason(day: DayRoutine): String? = when {
    !day.sunscreenAdvised -> null
    day.morningAfterGlycolic && day.uvToday.needsProtection -> "Glycolic last night and ${sunPhrase(day.uvToday)}"
    day.morningAfterGlycolic -> "Glycolic last night makes skin more sun-sensitive"
    else -> sunPhrase(day.uvToday).replaceFirstChar { it.uppercase(Locale.ROOT) }
}
