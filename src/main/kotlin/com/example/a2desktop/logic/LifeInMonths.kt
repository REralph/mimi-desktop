package com.example.a2desktop.logic

import java.time.LocalDate
import java.time.temporal.ChronoUnit

// Same month-counting rules as the phone app's Life in Months page (0.21): month k runs from
// dob + k months up to the day before dob + (k + 1) months, always measured from the original date
// of birth (short months clamp, leap days handled by plusMonths); a month completes when the next
// one starts; remaining = 960 - completed, never negative; over 80 → everything filled.

const val LIFE_YEARS = 80
const val MONTHS_PER_YEAR = 12
const val LIFE_MONTHS = LIFE_YEARS * MONTHS_PER_YEAR

data class LifeProgress(
    val monthsLived: Int,
    val monthsRemaining: Int,
    val currentMonthIndex: Int?,
    val ageYears: Int,
    val ageMonths: Int,
    val percent: Double,
)

enum class LifeMonthState { COMPLETED, CURRENT, FUTURE }

const val FIVE_YEARS = 5

/**
 * Whether the row for age [year] opens a five-year mark: ages 5, 10, 15 … 75 (the same marks as the
 * phone's page 0.21). Age 0 is only the start of life and 80 is the end of the grid, so neither is
 * marked. The mark sits on the line between the row above and this one — the moment [year] full years
 * are done.
 */
fun isFiveYearMark(year: Int): Boolean = year in FIVE_YEARS until LIFE_YEARS && year % FIVE_YEARS == 0

/** Whether the five-year mark at age [year] has been reached: that many whole years (12 months each)
 * are completed — the same count the filled circles come from, so the two can't disagree. */
fun fiveYearMarkPassed(year: Int, progress: LifeProgress): Boolean = progress.monthsLived >= year * MONTHS_PER_YEAR

/** How many five-year marks have been reached so far, 0..15 (5 years old → 1, 28 → 5, 80+ → 15). */
fun fiveYearMarksPassed(progress: LifeProgress): Int =
    (FIVE_YEARS until LIFE_YEARS step FIVE_YEARS).count { fiveYearMarkPassed(it, progress) }

fun wholeMonthsLived(dateOfBirth: LocalDate, today: LocalDate): Int {
    if (today.isBefore(dateOfBirth)) return 0
    var k = ChronoUnit.MONTHS.between(dateOfBirth, today).toInt()
    while (!dateOfBirth.plusMonths(k + 1L).isAfter(today)) k++
    while (k > 0 && dateOfBirth.plusMonths(k.toLong()).isAfter(today)) k--
    return k
}

fun lifeProgress(dateOfBirth: LocalDate, today: LocalDate): LifeProgress {
    val completed = wholeMonthsLived(dateOfBirth, today)
    val lived = completed.coerceAtMost(LIFE_MONTHS)
    return LifeProgress(
        monthsLived = lived,
        monthsRemaining = (LIFE_MONTHS - lived).coerceAtLeast(0),
        currentMonthIndex = if (completed < LIFE_MONTHS) completed else null,
        ageYears = completed / MONTHS_PER_YEAR,
        ageMonths = completed % MONTHS_PER_YEAR,
        percent = lived * 100.0 / LIFE_MONTHS,
    )
}

fun lifeMonthStart(dateOfBirth: LocalDate, index: Int): LocalDate = dateOfBirth.plusMonths(index.toLong())

fun lifeMonthEnd(dateOfBirth: LocalDate, index: Int): LocalDate = dateOfBirth.plusMonths(index + 1L).minusDays(1)

fun lifeMonthState(index: Int, progress: LifeProgress): LifeMonthState = when {
    index < progress.monthsLived -> LifeMonthState.COMPLETED
    index == progress.currentMonthIndex -> LifeMonthState.CURRENT
    else -> LifeMonthState.FUTURE
}

fun lifeAgeText(years: Int, months: Int): String =
    "$years ${if (years == 1) "year" else "years"} and $months ${if (months == 1) "month" else "months"}"
