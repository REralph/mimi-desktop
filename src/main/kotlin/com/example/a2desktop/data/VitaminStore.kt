package com.example.a2desktop.data

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.a2desktop.link.EditResult
import com.example.a2desktop.link.LinkCareCommand
import com.example.a2desktop.link.LinkVitamins
import com.example.a2desktop.logic.MAX_SOFTGEL_DAYS
import com.example.a2desktop.logic.ORGAN_LABEL_CAPSULES
import com.example.a2desktop.logic.Supplement
import com.example.a2desktop.logic.VitaminSettings
import com.example.a2desktop.logic.VitaminState
import com.example.a2desktop.logic.ZincPlan
import com.example.a2desktop.logic.planZinc
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.LocalDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * What is saved: the choices on the supplements card, the day confirming began, and what the user said on each day —
 * [marks]: epoch day -> supplement name -> true (taken) / false (not taken). A day with no entry is untouched. The old
 * zinc-only lists ([zincTaken], [zincSkipped]) are read as marks (so nothing already recorded is lost) and written back
 * empty once they have been moved across.
 */
@Serializable
internal data class VitaminFile(
    val zincEveryDay: Boolean = false,
    val magnesiumTablets: Int = 1,
    val zincTaken: List<Long> = emptyList(),
    val trackedFrom: Long? = null,
    val zincSkipped: List<Long> = emptyList(),
    val marks: Map<Long, Map<String, Boolean>> = emptyMap(),
    /** Days a week the 4,000 IU vitamin D softgel is taken (1-3); the default is written only when changed. */
    val softgelDays: Int = 3,
    /** Hirsch beef organ capsules a day (1-6). */
    val organCapsules: Int = 3,
    /** The day ticking began: from here an unticked supplement counts as not taken once its day has passed. */
    val tickFrom: Long? = null,
    /** Ticks made here that the phone's record doesn't hold (it doesn't know that supplement yet): epoch day -> supplement name -> value. See [VitaminStore]. */
    val heldHere: Map<Long, Map<String, Boolean>> = emptyMap(),
)

/** The marks in a file, with the old zinc lists folded in (a mark already there wins over an old list). */
internal fun VitaminFile.toMarks(): Map<Long, Map<String, Boolean>> {
    val out = HashMap<Long, MutableMap<String, Boolean>>()
    zincTaken.forEach { out.getOrPut(it) { HashMap() }[Supplement.ZINC.name] = true }
    zincSkipped.forEach { out.getOrPut(it) { HashMap() }[Supplement.ZINC.name] = false }
    marks.forEach { (day, byName) -> byName.forEach { (name, taken) -> out.getOrPut(day) { HashMap() }[name] = taken } }
    return out.filterValues { it.isNotEmpty() }
}

/** The ticks held here, as supplements and days (a name this version doesn't know is skipped). */
internal fun VitaminFile.toHeld(): Map<LocalDate, Map<Supplement, Boolean>> = heldHere.mapNotNull { (day, byName) ->
    val known = byName.mapNotNull { (name, value) -> Supplement.entries.firstOrNull { it.name == name }?.let { it to value } }.toMap()
    if (known.isEmpty()) null else LocalDate.ofEpochDay(day) to known
}.toMap()

internal fun Map<LocalDate, Map<Supplement, Boolean>>.heldToWire(): Map<Long, Map<String, Boolean>> =
    entries.sortedBy { it.key }.associate { (day, bySupplement) -> day.toEpochDay() to bySupplement.entries.associate { it.key.name to it.value } }

internal fun VitaminFile.toState(): VitaminState = VitaminState.fromWire(
    LinkVitamins(zincEveryDay, magnesiumTablets, softgelDays, organCapsules, trackedFrom, tickFrom, toMarks()),
)

internal fun VitaminState.toFile(): VitaminFile = toWire().let { w ->
    VitaminFile(
        zincEveryDay = w.zincEveryDay, magnesiumTablets = w.magnesiumTablets, softgelDays = w.softgelDays, organCapsules = w.organCapsules,
        trackedFrom = w.trackedFrom, tickFrom = w.tickFrom, marks = w.marks,
    )
}

/**
 * The choices on the Supplements card — zinc every day or not, how many Solgar magnesium + B6 tablets, how many days a week the
 * 4,000 IU vitamin D softgel and how many beef organ capsules a day — and what the user said about each supplement on each day (ticked =
 * taken, unticked = not taken). A supplement nobody said anything about on a day is simply absent: the day is untouched, and
 * untouched means every scheduled supplement is presumed taken (see [com.example.a2desktop.logic.DoseState]) — nothing is written for
 * that, and nothing for a day to come. The zinc plan is worked out from the zinc marks (see [zincPlan]).
 *
 * It is the same record the phone's Supplements card shows, and it lives on the PHONE: while the phone keeps it ([sender] set — see
 * [CareSender]) a change is sent there and shown when the phone answers with what it now holds ([applyRemote]), so both apps always
 * show the same thing. With no phone record to follow (an older phone app, or previews and tests) a change is made here at once and
 * kept in one small file on this PC (`vitamins.json` beside the pairing) — which is also kept up to date from the phone as a copy.
 * [file] null keeps it in memory only; a missing or unreadable file just means the defaults.
 *
 * A phone app that doesn't know a supplement yet (one added here first) throws away a tick on it, so its answer never holds that tick.
 * Rather than lose the tick, this PC keeps it itself ([held]) and shows it with the phone's record; it is offered to the phone again
 * ([RESEND_EVERY_MILLIS] apart, and as the app starts) and let go once the phone's record holds it or says something else about it.
 * [clock] is the time in milliseconds (a fake one in tests).
 */
class VitaminStore(private val file: File? = null, private val clock: () -> Long = System::currentTimeMillis) {
    private val json = Json { ignoreUnknownKeys = true }
    private val loaded: VitaminFile = file?.let { f -> runCatching { json.decodeFromString<VitaminFile>(f.readText()) }.getOrNull() } ?: VitaminFile()

    /** The whole record. */
    var state by mutableStateOf(loaded.toState())
        private set

    val settings: VitaminSettings get() = state.settings

    /** Ticks the user made that the phone's record doesn't hold (it doesn't know that supplement), kept here: day -> supplement -> value. */
    var held by mutableStateOf(loaded.toHeld())
        private set

    private val merged by derivedStateOf { state.marks.withHeld(held) }

    /**
     * What the user said, by day and supplement: true = taken, false = not taken. A day or supplement not in here was never touched.
     * It is the phone's record with the ticks [held] here on top.
     */
    val marks: Map<LocalDate, Map<Supplement, Boolean>> get() = merged

    /**
     * The day ticking began (the first day the app ran with the supplement ticks), or null until [ensureStarted]. From this day a
     * supplement that isn't ticked counts as not taken once its day has passed (today it is still to come); the days before it keep
     * counting as taken, so the history from before ticks existed doesn't change.
     */
    val tickFrom: LocalDate? get() = state.tickFrom

    /** The day confirming began (the first day the app ran with it), or null until [ensureStarted]. */
    val trackedFrom: LocalDate? get() = state.trackedFrom

    /** The days zinc was ticked as taken. */
    val zincTaken: Set<LocalDate> get() = marks.filterValues { it[Supplement.ZINC] == true }.keys

    /** The days zinc was said not to have been taken. */
    val zincSkipped: Set<LocalDate> get() = marks.filterValues { it[Supplement.ZINC] == false }.keys

    /** Where changes go while the phone keeps the record; null = made here. */
    var sender: CareSender? = null

    /** Why the last change sent to the phone didn't happen (it couldn't be reached, or refused), or null. */
    var problem by mutableStateOf<String?>(null)
        private set

    private fun save() {
        val f = file ?: return
        runCatching {
            f.parentFile?.mkdirs()
            val temp = File(f.parentFile, f.name + ".tmp")
            temp.writeText(json.encodeToString(state.toFile().copy(heldHere = held.heldToWire())))
            Files.move(temp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /**
     * What the phone now holds: shown as it is (with the ticks it doesn't hold, [held], on top), and kept in the file as a copy. A held
     * tick the phone now has something to say about — it holds it, or a different value — is let go; one it still lacks stays, and is
     * offered to the phone again if that hasn't been done lately.
     */
    @Synchronized
    fun applyRemote(doc: LinkVitamins) {
        val next = VitaminState.fromWire(doc)
        problem = null
        val stillHeld = held.mapNotNull { (day, bySupplement) ->
            val lacking = bySupplement.filterKeys { next.marks[day]?.get(it) == null }
            if (lacking.isEmpty()) null else day to lacking
        }.toMap()
        val heldChanged = stillHeld != held
        if (heldChanged) held = stillHeld
        if (next != state || heldChanged) {
            state = next
            save()
        }
        offerHeldAgain()
    }

    /** When the held ticks were last offered to the phone (null: not yet in this run). */
    private var lastOffered: Long? = null

    /** Gives the phone the ticks it lacks, in one request, unless that was done less than [RESEND_EVERY_MILLIS] ago (it ignores what it doesn't know). */
    private fun offerHeldAgain() {
        val send = sender ?: return
        if (held.isEmpty()) return
        val now = clock()
        lastOffered?.let { if (now - it < RESEND_EVERY_MILLIS) return }
        lastOffered = now
        val commands = held.flatMap { (day, bySupplement) ->
            bySupplement.map { (supplement, value) -> LinkCareCommand("vit.setMark", day.toEpochDay(), supplement = supplement.name, flag = value) }
        }
        send.send(commands) { }
    }

    /** What the phone's answer to a tick says about it: the phone holds it as asked (nothing to keep here), or it doesn't (so it is kept here). */
    @Synchronized
    private fun reconcile(date: LocalDate, supplement: Supplement, taken: Boolean?, answer: LinkVitamins?) {
        val phoneHolds = answer?.let { VitaminState.fromWire(it).marks[date]?.get(supplement) }
        val hold = answer != null && taken != null && phoneHolds != taken
        if (hold) lastOffered = clock() // the tick has just been offered to the phone, so it isn't offered again straight away
        setHeld(date, supplement, if (hold) taken else null)
    }

    /** Keeps (or, with a null [value], lets go of) the tick for [supplement] on [date] here. */
    @Synchronized
    private fun setHeld(date: LocalDate, supplement: Supplement, value: Boolean?) {
        val day = held[date].orEmpty()
        val nextDay = if (value == null) day - supplement else day + (supplement to value)
        val next = if (nextDay.isEmpty()) held - date else held + (date to nextDay)
        if (next == held) return
        held = next
        save()
    }

    /** Keeps a copy of the file as it is (`vitamins.json.before-phone`, once) before the phone's record first replaces this one. */
    fun keepCopyBeforePhone() {
        val f = file ?: return
        val copy = File(f.parentFile, f.name + ".before-phone")
        if (f.exists() && !copy.exists()) runCatching { Files.copy(f.toPath(), copy.toPath()) }
    }

    /** Makes a change: sent to the phone when it keeps the record (nothing is shown until it answers), otherwise made here at once. */
    private fun change(command: LinkCareCommand, local: (VitaminState) -> VitaminState) {
        val send = sender
        if (send == null) {
            state = local(state)
            save()
        } else {
            problem = null
            send.send(listOf(command)) { result -> if (result is EditResult.Failed) problem = result.message }
        }
    }

    @Synchronized
    fun setZincEveryDay(on: Boolean) {
        change(LinkCareCommand("vit.zincEveryDay", flag = on)) { it.setZincEveryDay(on) }
    }

    /** Solgar magnesium + B6 tablets a day, kept between 1 and 3. */
    @Synchronized
    fun setMagnesiumTablets(count: Int) {
        change(LinkCareCommand("vit.magnesium", count = count.coerceIn(1, 3))) { it.setMagnesiumTablets(count) }
    }

    /** Days a week (1 to 3) the 4,000 IU vitamin D softgel takes the place of the 10 µg tablet, October to March. */
    @Synchronized
    fun setSoftgelDays(days: Int) {
        change(LinkCareCommand("vit.softgelDays", count = days.coerceIn(1, MAX_SOFTGEL_DAYS))) { it.setSoftgelDays(days) }
    }

    /** Hirsch beef organ capsules a day, kept between 1 and 6 (the label's serving). */
    @Synchronized
    fun setOrganCapsules(count: Int) {
        change(LinkCareCommand("vit.organs", count = count.coerceIn(1, ORGAN_LABEL_CAPSULES))) { it.setOrganCapsules(count) }
    }

    /** Remembers [today] as the day tracking began and, separately, as the day ticking began — each the first time it is found missing. */
    @Synchronized
    fun ensureStarted(today: LocalDate) {
        if (trackedFrom != null && tickFrom != null) return
        change(LinkCareCommand("vit.start", today.toEpochDay())) { it.ensureStarted(today) }
    }

    /** What the user said about [supplement] on [date]: true = taken, false = not taken, null = nothing (untouched). */
    fun markOn(date: LocalDate, supplement: Supplement): Boolean? = marks[date]?.get(supplement)

    /** Everything said about [date], by supplement (empty for an untouched day). */
    fun marksOn(date: LocalDate): Map<Supplement, Boolean> = marks[date].orEmpty()

    /** Whether the user has said anything about any supplement on [date]. */
    fun isTouched(date: LocalDate): Boolean = marks[date]?.isNotEmpty() == true

    /**
     * Ticks [supplement] on [date] as taken (true), unticks it as not taken (false), or forgets what was said (null).
     * Only that supplement on only that date changes — every other day and supplement is left exactly as it was.
     */
    @Synchronized
    fun setMark(date: LocalDate, supplement: Supplement, taken: Boolean?) {
        val command = LinkCareCommand("vit.setMark", date.toEpochDay(), supplement = supplement.name, flag = taken)
        val send = sender
        if (send == null) {
            setHeld(date, supplement, null) // made here, so nothing is held back from a phone
            state = state.setMark(date, supplement, taken)
            save()
        } else {
            problem = null
            send.send(listOf(command)) { result ->
                when (result) {
                    is EditResult.Failed -> problem = result.message
                    // The phone applies it and answers with its record: if that doesn't hold the tick, this PC does.
                    is EditResult.Done -> reconcile(date, supplement, taken, result.reply.care?.vitamins)
                }
            }
        }
    }

    /** Forgets everything said about [date]: the day goes back to untouched, every scheduled supplement presumed taken. */
    @Synchronized
    fun resetDay(date: LocalDate) {
        if (date !in marks) return
        if (date in held) held = held - date // the day goes back to untouched here too
        change(LinkCareCommand("vit.resetDay", date.toEpochDay())) { it.resetDay(date) }
    }

    /** Ticks zinc as taken on [date], or takes the tick back. */
    fun setZincTaken(date: LocalDate, taken: Boolean) = setMark(date, Supplement.ZINC, if (taken) true else null)

    /** Says no to zinc on [date], or takes the no back. */
    fun setZincSkipped(date: LocalDate, skipped: Boolean) = setMark(date, Supplement.ZINC, if (skipped) false else null)

    /** The zinc plan from [today], from what has been ticked and unticked so far. */
    fun zincPlan(today: LocalDate): ZincPlan = planZinc(today, trackedFrom, zincTaken, settings, skipped = zincSkipped, tickFrom = tickFrom)

    companion object {
        /** How long to wait before offering the phone the ticks it lacks again (it is also done as the app starts). */
        const val RESEND_EVERY_MILLIS = 30 * 60_000L

        /** The real file, next to the pairing in `%APPDATA%\a2-desktop`. */
        fun default(): VitaminStore =
            VitaminStore(File(File(System.getenv("APPDATA") ?: System.getProperty("user.home"), "a2-desktop"), "vitamins.json"))
    }
}

/** These marks with the ticks [held] on top (a held tick wins on its day and supplement). */
internal fun Map<LocalDate, Map<Supplement, Boolean>>.withHeld(held: Map<LocalDate, Map<Supplement, Boolean>>): Map<LocalDate, Map<Supplement, Boolean>> {
    if (held.isEmpty()) return this
    val out = HashMap(this)
    held.forEach { (day, bySupplement) -> out[day] = this[day].orEmpty() + bySupplement }
    return out
}
