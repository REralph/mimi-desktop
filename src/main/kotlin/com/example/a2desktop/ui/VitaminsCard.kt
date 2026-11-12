package com.example.a2desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.a2desktop.data.VitaminStore
import com.example.a2desktop.logic.B6_PER_TABLET_MG
import com.example.a2desktop.logic.DoseState
import com.example.a2desktop.logic.MAGNESIUM_PER_TABLET_MG
import com.example.a2desktop.logic.MAX_SOFTGEL_DAYS
import com.example.a2desktop.logic.ORGAN_CAPSULE_G
import com.example.a2desktop.logic.ORGAN_LABEL_CAPSULES
import com.example.a2desktop.logic.NutrientCheck
import com.example.a2desktop.logic.Slot
import com.example.a2desktop.logic.Supplement
import com.example.a2desktop.logic.Verdict
import com.example.a2desktop.logic.VitaminMark
import com.example.a2desktop.logic.VitaminEntry
import com.example.a2desktop.logic.VitaminSettings
import com.example.a2desktop.logic.checkDay
import com.example.a2desktop.logic.vitaminEntries
import com.example.a2desktop.logic.formatAmount
import com.example.a2desktop.logic.nextSoftgelDay
import com.example.a2desktop.logic.softgelWeekdays
import com.example.a2desktop.logic.weeklyAverageVitaminD
import com.example.a2desktop.logic.weeklyAverageZinc
import com.example.a2desktop.theme.GlassCard
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private val vitaminDayFmt = DateTimeFormatter.ofPattern("EEE d MMM")

/** The colour of the vitamin markers on the Calendar's days: outlined, so they can't be mistaken for the filled skin-care letters. */
internal val VitaminInk = Color(0xFFE6E6F0)

/** A small outlined code — "Zn" on a zinc day, "AA" on a training day — on the Calendar's days and in its legend. */
@Composable
internal fun VitaminChip(mark: VitaminMark, modifier: Modifier = Modifier, strength: Float = 1f) {
    Box(
        modifier = modifier
            .height(14.dp)
            .border(1.dp, VitaminInk.copy(alpha = 0.85f * strength), RoundedCornerShape(4.dp))
            .padding(horizontal = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(mark.code, fontSize = 9.sp, lineHeight = 11.sp, fontWeight = FontWeight.Bold, color = VitaminInk.copy(alpha = strength), maxLines = 1, softWrap = false)
    }
}

private val zincDayFmt = DateTimeFormatter.ofPattern("EEE d MMM")

internal val GoodColor = Color(0xFF6FCF97)
private val CloseColor = Color(0xFFF2C94C)
internal val OverColor = Color(0xFFEB5757)

private fun Verdict.color(): Color = when (severity) {
    0 -> GoodColor
    1 -> CloseColor
    else -> OverColor
}

private fun Verdict.icon(): String = when (severity) {
    0 -> "✓"
    1 -> "⚠"
    else -> "⛔"
}

/** What a supplement's tick says when the pointer rests on it, by where that dose stands. */
private fun tickTip(state: DoseState): String = when (state) {
    DoseState.TAKEN -> "Taken — tap to take the tick back"
    DoseState.PRESUMED -> "Counted as taken (a day from before ticking began) — tap to tick it"
    DoseState.NOT_TAKEN -> "Not taken — tap if you did"
    else -> "Tap when you've taken it"
}

/**
 * The number after 1.8.1 in a supplement's tick label in developer mode. Each supplement has its own, so a new one has to be
 * given one here (the compiler insists) and the old ones keep theirs.
 */
private fun Supplement.tickNumber(): Int = when (this) {
    Supplement.VITAMIN_D3 -> 1
    Supplement.VITAMIN_D3_4000 -> 2
    Supplement.VITAMIN_K2 -> 3
    Supplement.CREATINE -> 4
    Supplement.BEEF_ORGANS -> 5
    Supplement.ZINC -> 6
    Supplement.MAGNESIUM_B6 -> 7
    Supplement.OMEGA_3 -> 8
    Supplement.AMINO_ACIDS -> 9
    Supplement.L_THEANINE_LEMON_BALM -> 10 // added after the amino acids (9), so it takes the next number
}

/** What a supplement's row says on the card: its name, and nothing about how much — how many to take is not shown. */
internal fun supplementRowText(entry: VitaminEntry): String = entry.supplement.short

/**
 * The Calendar's Supplements card (it was called Vitamins): what to take and when, for the day selected — by the time of day
 * (Morning, Midday, Evening, and Training for the amino acids), each by its name only, never how much — and where each is ticked off as it is
 * taken, today or late for an earlier day (a day still to come has no ticks). A tick means taken; from the day ticking began
 * (see [VitaminStore.tickFrom]) a supplement with no tick counts as not taken once its day has passed, and zinc is planned
 * again around a missed dose; the days before that keep counting as taken. Zinc is listed on the days it is scheduled, with its next
 * day below when it isn't. Only something over a published limit is called out; "More" opens the rest — the day's
 * amounts against the limits, the choices (zinc every day, magnesium tablets, how many days a week the 4,000 IU vitamin D
 * softgel, how many beef organ capsules) and the notes. [trainedDays] are the
 * days a workout was recorded (from the phone), which is when the amino acids are taken. Lives on this PC only — see
 * [VitaminStore].
 */
@Composable
fun VitaminsCard(vitamins: VitaminStore, today: LocalDate, selected: LocalDate, trainedDays: Set<LocalDate>) {
    val settings = vitamins.settings
    val zinc = remember(today, settings, vitamins.marks, vitamins.trackedFrom, vitamins.tickFrom) { vitamins.zincPlan(today) }
    val trained: Boolean? = if (selected <= today) selected in trainedDays else null
    val entries = remember(selected, today, settings, trained, zinc, vitamins.marks, vitamins.tickFrom) {
        vitaminEntries(selected, today, settings, trained, zinc, vitamins.marksOn(selected), vitamins.tickFrom)
    }
    // A day still to come can't be ticked: nothing is recorded for a dose that hasn't happened.
    val tickable = !selected.isAfter(today)
    // The safety check counts what is scheduled (the tablets in the plan), not what was ticked.
    val checks = remember(entries) { checkDay(entries.map { it.line }) }
    val over = checks.filter { it.verdict.severity == 2 }.map { it.nutrient.label }
    var showMore by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme

    GlassCard(modifier = Modifier.fillMaxWidth().devRef("1.8", "Supplements card"), contentPadding = 14.dp) {
        CardTitle("💊 Supplements") {
            Text(
                if (selected == today) "TODAY" else selected.format(vitaminDayFmt),
                style = MaterialTheme.typography.labelSmall,
                color = colors.primary,
                fontWeight = FontWeight.Bold,
            )
        }

        Column(modifier = Modifier.fillMaxWidth().devRef("1.8.1", "What to take and when"), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Slot.entries.forEach { slot ->
                val inSlot = entries.filter { it.supplement.slot == slot && it.state.isOnTheDay }
                if (inSlot.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.Top) {
                        Text(
                            slot.label.substringBefore(',').uppercase(Locale.ROOT),
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.onSurfaceVariant,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.width(72.dp).padding(top = 5.dp),
                        )
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            inSlot.forEach { entry ->
                                val notTaken = entry.state == DoseState.NOT_TAKEN
                                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        supplementRowText(entry),
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (notTaken) colors.onSurfaceVariant else colors.onSurface,
                                    )
                                    Box(Modifier.weight(1f))
                                    if (tickable) {
                                        TickButton(
                                            done = entry.state == DoseState.TAKEN,
                                            presumed = entry.state == DoseState.PRESUMED,
                                            tip = tickTip(entry.state),
                                            modifier = Modifier.devRef("1.8.1.${entry.supplement.tickNumber()}", "${entry.supplement.short} tick"),
                                        ) { vitamins.setMark(selected, entry.supplement, if (entry.state == DoseState.TAKEN) null else true) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (entries.none { it.supplement == Supplement.ZINC && it.state.isOnTheDay }) {
                val next = zinc.nextPlanned(maxOf(selected, today))
                if (!settings.zincEveryDay && next != null) {
                    Text("Zinc: next ${next.format(zincDayFmt)}", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
                }
            }
            if (entries.none { it.supplement == Supplement.VITAMIN_D3_4000 && it.state.isOnTheDay }) {
                nextSoftgelDay(maxOf(selected, today), settings)?.let { next ->
                    Text("${Supplement.VITAMIN_D3_4000.short}: next ${next.format(zincDayFmt)}", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                }
            }
        }

        vitamins.problem?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.error, modifier = Modifier.padding(top = 6.dp)) }
        if (over.isNotEmpty()) {
            Text("⛔ Over the limit: ${over.joinToString(", ")}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = OverColor, modifier = Modifier.padding(top = 6.dp))
        }

        TextButton(onClick = { showMore = !showMore }, modifier = Modifier.padding(top = 2.dp)) {
            Text(if (showMore) "Less" else "More", style = MaterialTheme.typography.bodySmall)
        }
        if (showMore) {
            // The check: this day's total from the supplements against each limit.
            Column(modifier = Modifier.fillMaxWidth().devRef("1.8.2", "Safety check"), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("Safety check — this day's amounts", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                if (checks.isEmpty()) {
                    Text("Nothing from supplements on this day.", style = MaterialTheme.typography.bodySmall)
                }
                checks.forEach { CheckRow(it) }
                Text(
                    "Counts the tablets only, not what you eat. Zinc averages ${"%.1f".format(Locale.ROOT, weeklyAverageZinc(settings))} mg a day over a week with these settings.",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                )
            }
            HorizontalDivider(color = colors.onSurface.copy(alpha = 0.08f), modifier = Modifier.padding(vertical = 8.dp))
            Choices(vitamins, settings, modifier = Modifier.devRef("1.8.4", "Vitamin choices"))
            Column(verticalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.padding(top = 8.dp)) {
                VNote("Vitamin D: the NHS advises 10 µg (400 IU) a day in autumn and winter and says not to take more than 100 µg (4,000 IU) a day; too much over a long time can raise the calcium in the body, and anyone with a medical condition that may stop them taking this much should ask a doctor. The 4,000 IU softgel is that whole limit in one softgel, so on its days it takes the place of the 10 µg tablet — never both (together 110 µg, over the limit). By default that is 3 days a week, about 1,900 IU a day on average; October to March, like the tablet.")
                VNote("Vitamin K2 (MK-7, 200 µg): the NHS says adults need about 1 µg for each kilogram of body weight a day and that 1 mg (1,000 µg) or less a day of supplements is unlikely to cause harm — this tablet is a fifth of that. Vitamin K works against warfarin, so if you take warfarin or another blood thinner, ask your clinic or GP before taking it.")
                VNote("Omega-3 fish oil: a serving of 2 softgels is 2,000 mg of fish oil with 660 mg EPA + 440 mg DHA, 1.1 g together. EFSA found no safety concern at up to 5 g a day of EPA + DHA from supplements; the US FDA says 3 g, which is what the check uses. Ask first if you take a blood thinner. Check your pack says fish oil, not cod liver oil, which would add vitamin A and D that this check doesn't count.")
                VNote("Beef organs (liver, kidney, heart): 500 mg a capsule; Hirsch suggests building up to 6 a day (3 g). The vitamin A and copper counted here come from Hirsch's liver-only label per 6 capsules (138 µg vitamin A, 1.4 mg copper) — I couldn't read the organ-mix label, and it has less liver, so these are ceilings. It starts at 3 capsules, half the label serving. The NHS says 1.5 mg a day of vitamin A from food and supplements together is the most to count on, to eat liver no more than once a week, not to take vitamin A supplements if you eat liver weekly, and to avoid liver in pregnancy; the check doesn't count liver you eat.")
                VNote("Zinc: the NHS says no more than 25 mg a day from supplements, because more can lower the copper your body absorbs. One tablet is exactly 25 mg — the whole limit, before any zinc from food (the older EU limit of 25 mg counts food too). That is why the plan has three doses in any 7 days, never on back-to-back days — Monday, Wednesday and Friday when every dose is taken — about 10.7 mg a day on average. Every scheduled dose counts as taken, so the plan stays put. Switch it to every day only if a pharmacist or doctor says so.")
                VNote("Magnesium + B6: the NHS says 400 mg or less a day of magnesium from supplements is unlikely to harm (more can cause diarrhoea), and no more than 10 mg a day of vitamin B6. Listings for this Solgar tablet give about 133 mg magnesium and 8.3 mg B6 each, and the UK label dose is 3 tablets — that is 400 mg magnesium (the limit) and 25 mg B6, two and a half times the NHS figure; the label itself warns about tingling and numbness from long-term B6. One tablet keeps B6 under 10 mg. Check the numbers on your own pack.")
                VNote("Creatine: 3–5 g a day is the usual dose and the ISSN found no harm in healthy adults. Drink water with it, and ask a doctor first if you have kidney or liver problems.")
                VNote("Amino acids with BioPerine: no limit applies at 2800 mg. The black-pepper extract can raise how much of whatever you take with it is absorbed, medicines included — so take it away from the zinc, and ask a pharmacist if you take any medicine.")
                VNote("L-Theanine with Lemon Balm (Lamberts, vegan): nothing from it is counted in the check above — there is no NHS limit to measure it against — and the amount on your pack isn't known here, so take it as the pack says. It is a calming one, which is why it sits with the evening. If you take medicines (sedatives especially), ask a pharmacist or doctor first.")
                VNote("General information from the NHS and the sports-nutrition literature, not medical advice. If you take medicines, are pregnant or have a health condition, ask a pharmacist or doctor.")
            }
        }
    }
}

@Composable
private fun CheckRow(check: NutrientCheck) {
    val color = check.verdict.color()
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(check.nutrient.label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(82.dp))
            Text(
                formatAmount(check.nutrient, check.amount) + (check.nutrient.limit?.let { " of ${formatAmount(check.nutrient, it)}" } ?: " (usual 3–5 g)"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text("${check.verdict.icon()} ${check.verdict.label}", style = MaterialTheme.typography.labelSmall, color = color, fontWeight = FontWeight.SemiBold)
        }
        // A bar: how much of the limit (full = the limit).
        Box(Modifier.fillMaxWidth().padding(top = 2.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.08f))) {
            Box(Modifier.fillMaxWidth(check.fraction.coerceIn(0.02, 1.0).toFloat()).height(4.dp).background(color, RoundedCornerShape(2.dp)))
        }
        Text(check.nutrient.rule, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f), modifier = Modifier.padding(top = 1.dp))
    }
}

/** The choices: zinc every day (off), how many magnesium + B6 tablets, the 4,000 IU softgel days and the beef organ capsules. */
@Composable
private fun Choices(vitamins: VitaminStore, settings: VitaminSettings, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Your choices", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Zinc every day", style = MaterialTheme.typography.bodySmall)
                Text(
                    if (settings.zincEveryDay) "On: 25 mg every day — the full NHS limit every day, with nothing spare for food."
                    else "Off: three a week, never back to back — and the plan moves if you miss one.",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (settings.zincEveryDay) CloseColor else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = settings.zincEveryDay, onCheckedChange = { vitamins.setZincEveryDay(it) }, modifier = Modifier.scale(0.8f))
        }
        StepperRow(
            title = "Magnesium + B6 tablets a day",
            detail = "${settings.tablets} = ${"%.0f".format(Locale.ROOT, settings.tablets * MAGNESIUM_PER_TABLET_MG)} mg magnesium + ${"%.1f".format(Locale.ROOT, settings.tablets * B6_PER_TABLET_MG)} mg B6" +
                if (settings.tablets == 3) " — the UK label dose" else "",
            detailColor = if (settings.tablets * B6_PER_TABLET_MG > 10.0) OverColor else MaterialTheme.colorScheme.onSurfaceVariant,
            value = settings.tablets,
            range = 1..3,
            onChange = { vitamins.setMagnesiumTablets(it) },
        )
        val days = settings.softgelDaysPerWeek
        val weekdays = softgelWeekdays(days).sortedBy { it.value }.joinToString(", ") { it.getDisplayName(TextStyle.SHORT, Locale.UK) }
        StepperRow(
            title = "Vitamin D 4,000 IU softgel days a week",
            detail = "$days ($weekdays), October to March: the softgel instead of the 10 µg tablet, about ${"%.0f".format(Locale.ROOT, weeklyAverageVitaminD(settings) * 40.0)} IU a day on average. Each of those days is the whole NHS limit.",
            detailColor = MaterialTheme.colorScheme.onSurfaceVariant,
            value = days,
            range = 1..MAX_SOFTGEL_DAYS,
            onChange = { vitamins.setSoftgelDays(it) },
            modifier = Modifier.devRef("1.8.4.1", "4,000 IU softgel days"),
        )
        StepperRow(
            title = "Beef organ capsules a day",
            detail = "${settings.organs} = ${"%.1f".format(Locale.ROOT, settings.organs * ORGAN_CAPSULE_G)} g" +
                if (settings.organs == ORGAN_LABEL_CAPSULES) " — the label serving" else " (the label serving is $ORGAN_LABEL_CAPSULES)",
            detailColor = MaterialTheme.colorScheme.onSurfaceVariant,
            value = settings.organs,
            range = 1..ORGAN_LABEL_CAPSULES,
            onChange = { vitamins.setOrganCapsules(it) },
            modifier = Modifier.devRef("1.8.4.2", "Beef organ capsules"),
        )
    }
}

/** A choice with a − and a + around its number: [title], what it comes to ([detail]), and the value kept within [range]. */
@Composable
private fun StepperRow(title: String, detail: String, detailColor: Color, value: Int, range: IntRange, onChange: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodySmall)
            Text(detail, style = MaterialTheme.typography.labelSmall, color = detailColor)
        }
        StepperButton("−", enabled = value > range.first) { onChange(value - 1) }
        Text("$value", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, modifier = Modifier.width(24.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        StepperButton("+", enabled = value < range.last) { onChange(value + 1) }
    }
}

@Composable
private fun StepperButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(26.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(Color.White.copy(alpha = if (enabled) 0.12f else 0.04f))
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
    }
}

@Composable
private fun VNote(text: String) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
