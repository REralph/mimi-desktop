package com.example.a2desktop.logic

import com.example.a2desktop.link.LinkCare
import com.example.a2desktop.link.LinkCareCommand
import com.example.a2desktop.link.LinkCustomProduct
import com.example.a2desktop.link.LinkSkinCare
import com.example.a2desktop.link.LinkVitamins
import java.time.LocalDate
import kotlinx.serialization.Serializable

// COPY of the phone project's care/CareState.kt (only the package line and the package names in the imports differ). Keep the two in
// step (the interop test fails if they drift).
//
// What is DONE with the skin-care routine and the supplements — the record the two Calendar cards show and the ticks that are made on
// them — as plain values with the changes that can be made to it. The record lives on the PHONE (the source of truth); the desktop
// asks for a change with a [LinkCareCommand], the phone makes it with these same functions and answers with what it now holds, and
// both apps then draw the same cards from the same record with the same planners ([planRoutine], [planZinc]). Plain functions of
// plain data (no database, no screen, no network), so they are unit-tested. Every change SETS something rather than flips it, so a
// request that arrives twice does no harm.

/** Only the most recent days are kept — the planner looks back a week, the rest is just clutter. */
const val CARE_KEEP_DAYS = 120

/** More than this many extra products is a typo or a paste gone wrong, not a shelf. */
const val CARE_MAX_CUSTOM_PRODUCTS = 60

/** A product the user has beyond the four the plan knows about: it is listed on the Skin care page, never scheduled. */
@Serializable
data class CustomProduct(val name: String, val note: String = "")

private fun Set<LocalDate>.toggled(date: LocalDate) = if (date in this) this - date else this + date

private fun Set<LocalDate>.recent(): List<Long> = map { it.toEpochDay() }.sortedDescending().take(CARE_KEEP_DAYS).sorted()

private fun earliest(a: LocalDate?, b: LocalDate?): LocalDate? = listOfNotNull(a, b).minOrNull()

/** The skin-care record: what was used and ticked each evening, the day the routine began and the day confirming began, and the extra products. */
data class SkinState(
    val log: SkinCareLog = SkinCareLog(),
    /** The day the routine was first looked at: it is replayed from here. */
    val startedOn: LocalDate? = null,
    /** The day confirming began: from here an evening that isn't confirmed drops off; before it, unticked days count as followed. */
    val confirmFrom: LocalDate? = null,
    val products: List<CustomProduct> = emptyList(),
) {
    /** Ticks a morning's routine off (or takes it back): [done] is how it should end up. */
    fun setMorning(date: LocalDate, done: Boolean): SkinState =
        if ((date in log.morningsDone) == done) this else copy(log = log.copy(morningsDone = log.morningsDone.toggled(date)))

    /**
     * Ticks an evening off ([done] true) or takes the tick back, and remembers WHAT that evening was ([kind]: an exfoliating night
     * with the Findley treatment or the toner, a niacinamide or Vitamin C night, or a rest night), so the history can say what was
     * really used. An exfoliating kind is also recorded as an exfoliating night — and removed again on un-ticking — because that
     * record is what the next nights are spaced from, and what the two products take turns by.
     */
    fun setEvening(date: LocalDate, kind: EveningKind, done: Boolean): SkinState {
        val now = log
        if ((date in now.eveningsDone) == done) return this
        return copy(
            log = if (done) now.copy(
                eveningsDone = now.eveningsDone + date,
                eveningsSkipped = now.eveningsSkipped - date,
                glycolicNights = if (kind.exfoliation) now.glycolicNights + date else now.glycolicNights,
                tonerNights = when (kind) {
                    EveningKind.TONER -> now.tonerNights + date
                    EveningKind.GLYCOLIC -> now.tonerNights - date
                    else -> now.tonerNights
                },
                // An exfoliating night is already known from the two sets above; only the other kinds need remembering.
                eveningKinds = if (kind.exfoliation) now.eveningKinds - date else now.eveningKinds + (date to kind),
            ) else now.copy(
                eveningsDone = now.eveningsDone - date,
                glycolicNights = now.glycolicNights - date,
                tonerNights = now.tonerNights - date,
                eveningKinds = now.eveningKinds - date,
            ),
        )
    }

    /**
     * Says NO to [date]'s evening ([skipped] true) or takes that back. A no un-records the evening if it was ticked — nothing of
     * it is remembered — and the plan counts it as not done from now on, even for tonight. Taking it back leaves it unanswered.
     */
    fun setEveningSkipped(date: LocalDate, skipped: Boolean): SkinState {
        val now = log
        return copy(
            log = if (skipped) now.copy(
                eveningsSkipped = now.eveningsSkipped + date,
                eveningsDone = now.eveningsDone - date,
                glycolicNights = now.glycolicNights - date,
                tonerNights = now.tonerNights - date,
                eveningKinds = now.eveningKinds - date,
            ) else now.copy(eveningsSkipped = now.eveningsSkipped - date),
        )
    }

    /**
     * Remembers [today] as the day the routine began, the first time this is called — the plan is replayed from that day — and,
     * separately, as the day confirming began.
     */
    fun ensureStarted(today: LocalDate): SkinState =
        if (startedOn != null && confirmFrom != null) this else copy(startedOn = startedOn ?: today, confirmFrom = confirmFrom ?: today)

    /**
     * Records what was ACTUALLY used on [date]'s evening: [kind] — replacing whatever was recorded or assumed — or, with null,
     * forgets the record so the day goes back to following the plan. Whatever is recorded also counts as done.
     */
    fun setUsed(date: LocalDate, kind: EveningKind?): SkinState {
        val now = log
        val cleared = now.copy(
            eveningsSkipped = now.eveningsSkipped - date,
            eveningsDone = now.eveningsDone - date,
            glycolicNights = now.glycolicNights - date,
            tonerNights = now.tonerNights - date,
            eveningKinds = now.eveningKinds - date,
        )
        return copy(
            log = when {
                kind == null -> cleared
                kind.exfoliation -> cleared.copy(
                    eveningsDone = cleared.eveningsDone + date,
                    glycolicNights = cleared.glycolicNights + date,
                    tonerNights = if (kind == EveningKind.TONER) cleared.tonerNights + date else cleared.tonerNights,
                )
                else -> cleared.copy(eveningsDone = cleared.eveningsDone + date, eveningKinds = cleared.eveningKinds + (date to kind))
            },
        )
    }

    /**
     * Chooses what [date]'s evening should be — [kind], on any day, one still to come as well as tonight — or, with null, takes the choice
     * back so the day follows the plan again. It does not say the evening was done (the tick does that); the plan keeps the evening as
     * chosen and works out the nights after it from it. An evening that was already ticked off is changed to what was really used
     * ([setUsed]) — it stays ticked, as the other product.
     */
    fun setChosen(date: LocalDate, kind: EveningKind?): SkinState {
        val now = log
        val withChoice = when {
            kind == null -> if (date in now.chosenKinds) copy(log = now.copy(chosenKinds = now.chosenKinds - date)) else this
            now.chosenKinds[date] == kind -> this
            else -> copy(log = now.copy(chosenKinds = now.chosenKinds + (date to kind)))
        }
        return if (kind != null && withChoice.log.doneOn(date) && withChoice.log.usedOn(date) != kind) withChoice.setUsed(date, kind) else withChoice
    }

    /** For when an exfoliant was used (or wasn't) on a night other than the plan said; [exfoliant] is which one. */
    fun setExfoliationNight(date: LocalDate, used: Boolean, exfoliant: Product = Product.GLYCOLIC): SkinState {
        val now = log
        return copy(
            log = if (used) now.copy(
                glycolicNights = now.glycolicNights + date,
                tonerNights = if (exfoliant == Product.TONER) now.tonerNights + date else now.tonerNights - date,
                eveningKinds = now.eveningKinds - date,
            ) else now.copy(glycolicNights = now.glycolicNights - date, tonerNights = now.tonerNights - date),
        )
    }

    /** Adds a product to the list. A blank name, or one already listed (any capitals), is ignored. */
    fun addProduct(name: String, note: String = ""): SkinState {
        val clean = name.trim()
        if (clean.isEmpty() || products.size >= CARE_MAX_CUSTOM_PRODUCTS || products.any { it.name.equals(clean, ignoreCase = true) }) return this
        return copy(products = products + CustomProduct(clean, note.trim()))
    }

    fun removeProduct(name: String): SkinState = copy(products = products.filterNot { it.name == name })

    /** Makes the change a [LinkCareCommand] asks for (an op that isn't a skin-care one, or isn't understood, changes nothing). */
    fun apply(cmd: LinkCareCommand): SkinState {
        val date = cmd.date?.let(LocalDate::ofEpochDay)
        fun kindOf() = cmd.kind?.let { name -> EveningKind.entries.firstOrNull { it.name == name } }
        return when (cmd.op) {
            "skin.setMorning" -> if (date != null && cmd.flag != null) setMorning(date, cmd.flag) else this
            "skin.setEvening" -> if (date != null && cmd.flag != null) kindOf()?.let { setEvening(date, it, cmd.flag) } ?: this else this
            "skin.setSkipped" -> if (date != null && cmd.flag != null) setEveningSkipped(date, cmd.flag) else this
            "skin.start" -> if (date != null) ensureStarted(date) else this
            "skin.setUsed" -> if (date != null && (cmd.kind == null || kindOf() != null)) setUsed(date, kindOf()) else this
            "skin.setChosen" -> if (date != null && (cmd.kind == null || kindOf() != null)) setChosen(date, kindOf()) else this
            "skin.setExfoliation" -> {
                val product = cmd.name?.let { name -> Product.entries.firstOrNull { it.name == name } } ?: Product.GLYCOLIC
                if (date != null && cmd.flag != null) setExfoliationNight(date, cmd.flag, product) else this
            }
            "skin.addProduct" -> cmd.name?.let { addProduct(it, cmd.note.orEmpty()) } ?: this
            "skin.removeProduct" -> cmd.name?.let { removeProduct(it) } ?: this
            else -> this
        }
    }

    /**
     * This record with [incoming] — another copy of it, such as the desktop's own from before the two were joined — folded in,
     * once. What this one already says wins on a clash: a day it has anything about keeps only what it says; the days only the
     * other knows come across; the routine and confirming begin on the earlier of the two days.
     */
    fun mergedWith(incoming: SkinState): SkinState {
        val mine = log
        val theirs = incoming.log
        val touched = mine.eveningsDone + mine.eveningsSkipped + mine.glycolicNights + mine.tonerNights + mine.eveningKinds.keys
        return SkinState(
            log = SkinCareLog(
                glycolicNights = mine.glycolicNights + (theirs.glycolicNights - touched),
                morningsDone = mine.morningsDone + theirs.morningsDone,
                eveningsDone = mine.eveningsDone + (theirs.eveningsDone - touched),
                tonerNights = mine.tonerNights + (theirs.tonerNights - touched),
                eveningKinds = (theirs.eveningKinds - touched) + mine.eveningKinds,
                eveningsSkipped = mine.eveningsSkipped + (theirs.eveningsSkipped - touched),
                chosenKinds = theirs.chosenKinds + mine.chosenKinds,
            ),
            startedOn = earliest(startedOn, incoming.startedOn),
            confirmFrom = earliest(confirmFrom, incoming.confirmFrom),
            products = (products + incoming.products.filter { p -> products.none { it.name.equals(p.name, ignoreCase = true) } }).take(CARE_MAX_CUSTOM_PRODUCTS),
        )
    }

    /** The record as it travels and is stored: epoch days, and only the most recent [CARE_KEEP_DAYS] days of each list. */
    fun toWire(): LinkSkinCare = LinkSkinCare(
        glycolicNights = log.glycolicNights.recent(),
        morningsDone = log.morningsDone.recent(),
        eveningsDone = log.eveningsDone.recent(),
        tonerNights = log.tonerNights.recent(),
        eveningKinds = log.eveningKinds.entries.sortedByDescending { it.key }.take(CARE_KEEP_DAYS).associate { it.key.toEpochDay() to it.value.name },
        eveningsSkipped = log.eveningsSkipped.recent(),
        chosenKinds = log.chosenKinds.entries.sortedByDescending { it.key }.take(CARE_KEEP_DAYS).associate { it.key.toEpochDay() to it.value.name },
        startedOn = startedOn?.toEpochDay(),
        confirmFrom = confirmFrom?.toEpochDay(),
        customProducts = products.map { LinkCustomProduct(it.name, it.note) },
    )

    companion object {
        fun fromWire(doc: LinkSkinCare): SkinState = SkinState(
            log = SkinCareLog(
                glycolicNights = doc.glycolicNights.map(LocalDate::ofEpochDay).toSet(),
                morningsDone = doc.morningsDone.map(LocalDate::ofEpochDay).toSet(),
                eveningsDone = doc.eveningsDone.map(LocalDate::ofEpochDay).toSet(),
                tonerNights = doc.tonerNights.map(LocalDate::ofEpochDay).toSet(),
                eveningsSkipped = doc.eveningsSkipped.map(LocalDate::ofEpochDay).toSet(),
                // A name this version doesn't know is just skipped.
                eveningKinds = doc.eveningKinds.mapNotNull { (day, name) -> EveningKind.entries.firstOrNull { it.name == name }?.let { LocalDate.ofEpochDay(day) to it } }.toMap(),
                chosenKinds = doc.chosenKinds.mapNotNull { (day, name) -> EveningKind.entries.firstOrNull { it.name == name }?.let { LocalDate.ofEpochDay(day) to it } }.toMap(),
            ),
            startedOn = doc.startedOn?.let(LocalDate::ofEpochDay),
            confirmFrom = doc.confirmFrom?.let(LocalDate::ofEpochDay),
            products = doc.customProducts.map { CustomProduct(it.name, it.note) },
        )
    }
}

/** The supplements record: the choices on the card, the days ticking and tracking began, and what was said about each supplement each day. */
data class VitaminState(
    val settings: VitaminSettings = VitaminSettings(),
    /** What was said, by day and supplement: true = taken, false = not taken. A day or supplement not in here was never touched. */
    val marks: Map<LocalDate, Map<Supplement, Boolean>> = emptyMap(),
    /** The day ticking began: from here an unticked supplement counts as not taken once its day has passed. */
    val tickFrom: LocalDate? = null,
    /** The day confirming began (the first day the app ran with it). */
    val trackedFrom: LocalDate? = null,
) {
    /**
     * Sets [supplement] on [date] to taken (true), not taken (false), or forgets what was said (null). Only that supplement on only
     * that date changes — every other day and vitamin is left exactly as it was.
     */
    fun setMark(date: LocalDate, supplement: Supplement, taken: Boolean?): VitaminState {
        val day = marks[date].orEmpty()
        val nextDay = if (taken == null) day - supplement else day + (supplement to taken)
        return copy(marks = if (nextDay.isEmpty()) marks - date else marks + (date to nextDay))
    }

    /** Forgets everything said about [date]: the day goes back to untouched, every scheduled vitamin presumed taken. */
    fun resetDay(date: LocalDate): VitaminState = if (date in marks) copy(marks = marks - date) else this

    fun setZincEveryDay(on: Boolean): VitaminState = copy(settings = settings.copy(zincEveryDay = on))

    /** Solgar magnesium + B6 tablets a day, kept between 1 and 3. */
    fun setMagnesiumTablets(count: Int): VitaminState = copy(settings = settings.copy(magnesiumTablets = count.coerceIn(1, 3)))

    /** Days a week (1 to 3) the 4,000 IU vitamin D softgel takes the place of the 10 µg tablet, October to March. */
    fun setSoftgelDays(days: Int): VitaminState = copy(settings = settings.copy(softgelDays = days.coerceIn(1, MAX_SOFTGEL_DAYS)))

    /** Hirsch beef organ capsules a day, kept between 1 and 6 (the label's serving). */
    fun setOrganCapsules(count: Int): VitaminState = copy(settings = settings.copy(organCapsules = count.coerceIn(1, ORGAN_LABEL_CAPSULES)))

    /** Remembers [today] as the day tracking began and, separately, as the day ticking began — each the first time it is found missing. */
    fun ensureStarted(today: LocalDate): VitaminState =
        if (trackedFrom != null && tickFrom != null) this else copy(trackedFrom = trackedFrom ?: today, tickFrom = tickFrom ?: today)

    /** Makes the change a [LinkCareCommand] asks for (an op that isn't a supplements one, or isn't understood, changes nothing). */
    fun apply(cmd: LinkCareCommand): VitaminState {
        val date = cmd.date?.let(LocalDate::ofEpochDay)
        return when (cmd.op) {
            "vit.setMark" -> {
                val supplement = cmd.supplement?.let { name -> Supplement.entries.firstOrNull { it.name == name } }
                if (date != null && supplement != null) setMark(date, supplement, cmd.flag) else this
            }
            "vit.resetDay" -> if (date != null) resetDay(date) else this
            "vit.zincEveryDay" -> cmd.flag?.let { setZincEveryDay(it) } ?: this
            "vit.magnesium" -> cmd.count?.let { setMagnesiumTablets(it) } ?: this
            "vit.softgelDays" -> cmd.count?.let { setSoftgelDays(it) } ?: this
            "vit.organs" -> cmd.count?.let { setOrganCapsules(it) } ?: this
            "vit.start" -> if (date != null) ensureStarted(date) else this
            else -> this
        }
    }

    /**
     * This record with [incoming] folded in, once (see [SkinState.mergedWith]): what this one says wins on a clash; a choice still at
     * its default takes the other's; ticking and tracking begin on the earlier day.
     */
    fun mergedWith(incoming: VitaminState): VitaminState {
        val defaults = VitaminSettings()
        val merged = HashMap<LocalDate, Map<Supplement, Boolean>>()
        (incoming.marks.keys + marks.keys).forEach { day -> merged[day] = incoming.marks[day].orEmpty() + marks[day].orEmpty() }
        return VitaminState(
            settings = VitaminSettings(
                zincEveryDay = if (settings.zincEveryDay == defaults.zincEveryDay) incoming.settings.zincEveryDay else settings.zincEveryDay,
                magnesiumTablets = if (settings.magnesiumTablets == defaults.magnesiumTablets) incoming.settings.magnesiumTablets else settings.magnesiumTablets,
                softgelDays = if (settings.softgelDays == defaults.softgelDays) incoming.settings.softgelDays else settings.softgelDays,
                organCapsules = if (settings.organCapsules == defaults.organCapsules) incoming.settings.organCapsules else settings.organCapsules,
            ),
            marks = merged.filterValues { it.isNotEmpty() },
            tickFrom = earliest(tickFrom, incoming.tickFrom),
            trackedFrom = earliest(trackedFrom, incoming.trackedFrom),
        )
    }

    fun toWire(): LinkVitamins = LinkVitamins(
        zincEveryDay = settings.zincEveryDay,
        magnesiumTablets = settings.tablets,
        softgelDays = settings.softgelDaysPerWeek,
        organCapsules = settings.organs,
        trackedFrom = trackedFrom?.toEpochDay(),
        tickFrom = tickFrom?.toEpochDay(),
        marks = marks.entries.sortedBy { it.key }.associate { (day, byVitamin) -> day.toEpochDay() to byVitamin.entries.associate { it.key.name to it.value } },
    )

    companion object {
        fun fromWire(doc: LinkVitamins): VitaminState = VitaminState(
            settings = VitaminSettings(
                zincEveryDay = doc.zincEveryDay,
                magnesiumTablets = doc.magnesiumTablets.coerceIn(1, 3),
                softgelDays = doc.softgelDays.coerceIn(1, MAX_SOFTGEL_DAYS),
                organCapsules = doc.organCapsules.coerceIn(1, ORGAN_LABEL_CAPSULES),
            ),
            marks = doc.marks.mapNotNull { (day, byName) ->
                // A name this version doesn't know is just skipped.
                val known = byName.mapNotNull { (name, taken) -> Supplement.entries.firstOrNull { it.name == name }?.let { it to taken } }.toMap()
                if (known.isEmpty()) null else LocalDate.ofEpochDay(day) to known
            }.toMap(),
            tickFrom = doc.tickFrom?.let(LocalDate::ofEpochDay),
            trackedFrom = doc.trackedFrom?.let(LocalDate::ofEpochDay),
        )
    }
}

/** Both records together: everything the Skin care and Supplements cards need besides the day, the work times and the sun. */
data class CareState(val skin: SkinState = SkinState(), val vitamins: VitaminState = VitaminState()) {
    /** Makes the changes in [commands], in order. */
    fun apply(commands: List<LinkCareCommand>): CareState = commands.fold(this) { state, cmd ->
        when {
            cmd.op.startsWith("skin.") -> state.copy(skin = state.skin.apply(cmd))
            cmd.op.startsWith("vit.") -> state.copy(vitamins = state.vitamins.apply(cmd))
            else -> state
        }
    }

    /** This with [incoming] folded in, once (see [SkinState.mergedWith]). */
    fun mergedWith(incoming: CareState): CareState = CareState(skin.mergedWith(incoming.skin), vitamins.mergedWith(incoming.vitamins))

    fun toWire(): LinkCare = LinkCare(skin.toWire(), vitamins.toWire())

    companion object {
        fun fromWire(doc: LinkCare): CareState = CareState(SkinState.fromWire(doc.skin), VitaminState.fromWire(doc.vitamins))
    }
}

// ---- which of two changes wins ----
// The phone keeps, for each THING in the record (an evening, a supplement on a day, a setting…), when it last changed. A change the desktop
// made while the phone was out of reach carries the moment it was made, and the phone ignores it for a thing whose own copy changed later,
// so the most recent change on either side is the one kept. A thing is named by a key: [careKeysOf] says which a command touches,
// [careChangedKeys] which two records really differ in.

private fun <T> Set<T>.differsFrom(other: Set<T>): Set<T> = (this - other) + (other - this)

/** The keys a command touches. A command that always applies (the day the routine began) has none. */
fun careKeysOf(cmd: LinkCareCommand): List<String> {
    val day = cmd.date
    return when (cmd.op) {
        "skin.setMorning" -> if (day != null) listOf("skin:morning:$day") else emptyList()
        "skin.setEvening", "skin.setSkipped", "skin.setUsed", "skin.setChosen", "skin.setExfoliation" -> if (day != null) listOf("skin:day:$day") else emptyList()
        "skin.addProduct", "skin.removeProduct" -> cmd.name?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }?.let { listOf("skin:product:$it") }.orEmpty()
        "vit.setMark" -> if (day != null && cmd.supplement != null) listOf("vit:mark:$day:${cmd.supplement}") else emptyList()
        // Clearing a day touches every supplement's mark on it, so it clashes with a later tick on any of them.
        "vit.resetDay" -> if (day != null) Supplement.entries.map { "vit:mark:$day:${it.name}" } else emptyList()
        "vit.zincEveryDay" -> listOf("vit:setting:zincEveryDay")
        "vit.magnesium" -> listOf("vit:setting:magnesium")
        "vit.softgelDays" -> listOf("vit:setting:softgelDays")
        "vit.organs" -> listOf("vit:setting:organs")
        else -> emptyList()
    }
}

/** The keys of everything that really differs between [before] and [after] — what a change did, whoever made it. */
fun careChangedKeys(before: CareState, after: CareState): Set<String> {
    val keys = LinkedHashSet<String>()
    val b = before.skin.log
    val a = after.skin.log
    b.morningsDone.differsFrom(a.morningsDone).forEach { keys += "skin:morning:${it.toEpochDay()}" }
    // Everything about one evening — its tick, its kind, a no, the product chosen for it, the exfoliating nights — is one thing.
    val eveningDays = b.eveningsDone + b.eveningsSkipped + b.glycolicNights + b.tonerNights + b.eveningKinds.keys + b.chosenKinds.keys +
        a.eveningsDone + a.eveningsSkipped + a.glycolicNights + a.tonerNights + a.eveningKinds.keys + a.chosenKinds.keys
    eveningDays.filter { d ->
        (d in b.eveningsDone) != (d in a.eveningsDone) || (d in b.eveningsSkipped) != (d in a.eveningsSkipped) ||
            (d in b.glycolicNights) != (d in a.glycolicNights) || (d in b.tonerNights) != (d in a.tonerNights) ||
            b.eveningKinds[d] != a.eveningKinds[d] || b.chosenKinds[d] != a.chosenKinds[d]
    }.forEach { keys += "skin:day:${it.toEpochDay()}" }
    before.skin.products.map { it.name.lowercase() }.toSet().differsFrom(after.skin.products.map { it.name.lowercase() }.toSet())
        .forEach { keys += "skin:product:$it" }
    val bm = before.vitamins.marks
    val am = after.vitamins.marks
    (bm.keys + am.keys).forEach { day ->
        val was = bm[day].orEmpty()
        val now = am[day].orEmpty()
        (was.keys + now.keys).filter { was[it] != now[it] }.forEach { supplement -> keys += "vit:mark:${day.toEpochDay()}:${supplement.name}" }
    }
    val bs = before.vitamins.settings
    val ns = after.vitamins.settings
    if (bs.zincEveryDay != ns.zincEveryDay) keys += "vit:setting:zincEveryDay"
    if (bs.magnesiumTablets != ns.magnesiumTablets) keys += "vit:setting:magnesium"
    if (bs.softgelDays != ns.softgelDays) keys += "vit:setting:softgelDays"
    if (bs.organCapsules != ns.organCapsules) keys += "vit:setting:organs"
    return keys
}
