package com.example.a2desktop.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.a2desktop.link.EditResult
import com.example.a2desktop.link.LinkCareCommand
import com.example.a2desktop.link.LinkSkinCare
import com.example.a2desktop.logic.CARE_MAX_CUSTOM_PRODUCTS
import com.example.a2desktop.logic.CustomProduct
import com.example.a2desktop.logic.EveningKind
import com.example.a2desktop.logic.Product
import com.example.a2desktop.logic.SkinCareLog
import com.example.a2desktop.logic.SkinState
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.LocalDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** What is saved: epoch days, so the file is plain and easy to read. */
@Serializable
internal data class SkinCareFile(
    val glycolicNights: List<Long> = emptyList(),
    val morningsDone: List<Long> = emptyList(),
    val eveningsDone: List<Long> = emptyList(),
    val customProducts: List<CustomProduct> = emptyList(),
    /** The exfoliating nights (in [glycolicNights]) that were the toner rather than the Findley treatment. */
    val tonerNights: List<Long> = emptyList(),
    /** Epoch day -> [EveningKind] name, for evenings ticked off that were a serum or rest night. */
    val eveningKinds: Map<Long, String> = emptyMap(),
    /** The day the routine was first looked at: it is replayed from here. */
    val startedOn: Long? = null,
    /** The day confirming began: from here an evening that isn't confirmed drops off; before it, unticked days count as followed. */
    val confirmFrom: Long? = null,
    /** Evenings answered "no" (the ✗ the Check-in card used to have). Nothing sets one any more, but earlier answers still count. */
    val eveningsSkipped: List<Long> = emptyList(),
    /** Epoch day -> [EveningKind] name, for evenings whose product the user chose with the edit button. */
    val chosenKinds: Map<Long, String> = emptyMap(),
)

internal fun SkinState.toFile(): SkinCareFile = toWire().let { w ->
    SkinCareFile(
        glycolicNights = w.glycolicNights, morningsDone = w.morningsDone, eveningsDone = w.eveningsDone,
        customProducts = products, tonerNights = w.tonerNights, eveningKinds = w.eveningKinds,
        startedOn = w.startedOn, confirmFrom = w.confirmFrom, eveningsSkipped = w.eveningsSkipped, chosenKinds = w.chosenKinds,
    )
}

internal fun SkinCareFile.toState(): SkinState = SkinState.fromWire(
    LinkSkinCare(
        glycolicNights = glycolicNights, morningsDone = morningsDone, eveningsDone = eveningsDone, tonerNights = tonerNights,
        eveningKinds = eveningKinds, eveningsSkipped = eveningsSkipped, chosenKinds = chosenKinds, startedOn = startedOn, confirmFrom = confirmFrom,
        customProducts = customProducts.map { com.example.a2desktop.link.LinkCustomProduct(it.name, it.note) },
    ),
)

/**
 * What has actually been done with the skin-care routine: which evenings glycolic was really used (so the spacing follows what
 * happened, not just what was planned), which evenings were ticked off, and any extra products the user listed. It is the same record
 * the phone's Skin care card shows, and it lives on the PHONE: while the phone keeps it ([sender] set — see [CareSender]) a change is
 * sent there and shown when the phone answers with what it now holds ([applyRemote]), so both apps always show the same thing. With no
 * phone record to follow (an older phone app, or previews and tests) a change is made here at once and kept in one small file on this
 * PC (`skincare.json` beside the pairing) — which is also kept up to date from the phone as a copy, and was the only copy before the
 * phone kept one. (Mornings are no longer ticked — the user keeps them light — but older ticks in the file are kept as they were.)
 *
 * [file] null keeps it in memory only (previews and tests). A missing or unreadable file just means "nothing recorded yet"; it never
 * stops the app opening.
 */
class SkinCareStore(private val file: File? = null) {
    private val json = Json { ignoreUnknownKeys = true }
    private val loaded: SkinCareFile = file?.let { f -> runCatching { json.decodeFromString<SkinCareFile>(f.readText()) }.getOrNull() } ?: SkinCareFile()

    /** The whole record. */
    var state by mutableStateOf(loaded.toState())
        private set

    val log: SkinCareLog get() = state.log

    /** The day the routine began, or null until it has first been looked at (see [ensureStarted]). */
    val startedOn: LocalDate? get() = state.startedOn

    /**
     * The day confirming began, or null until [ensureStarted]. From this day an evening has to be confirmed (it shows faded until
     * then and drops off the next day); the days before it keep counting as followed, so the history from before confirming existed
     * doesn't vanish.
     */
    val confirmFrom: LocalDate? get() = state.confirmFrom

    /** The user's own extra products, in the order they were added. */
    val customProducts: List<CustomProduct> get() = state.products

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
            temp.writeText(json.encodeToString(state.toFile()))
            Files.move(temp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /** What the phone now holds: shown as it is, and kept in the file as a copy. */
    @Synchronized
    fun applyRemote(doc: LinkSkinCare) {
        val next = SkinState.fromWire(doc)
        problem = null
        if (next != state) {
            state = next
            save()
        }
    }

    /** Keeps a copy of the file as it is (`skincare.json.before-phone`, once) before the phone's record first replaces this one. */
    fun keepCopyBeforePhone() {
        val f = file ?: return
        val copy = File(f.parentFile, f.name + ".before-phone")
        if (f.exists() && !copy.exists()) runCatching { Files.copy(f.toPath(), copy.toPath()) }
    }

    /** Makes a change: sent to the phone when it keeps the record (nothing is shown until it answers), otherwise made here at once. */
    private fun change(command: LinkCareCommand, local: (SkinState) -> SkinState) {
        val send = sender
        if (send == null) {
            state = local(state)
            save()
        } else {
            problem = null
            send.send(listOf(command)) { result -> if (result is EditResult.Failed) problem = result.message }
        }
    }

    private fun command(op: String, date: LocalDate? = null, kind: String? = null, name: String? = null, note: String? = null, flag: Boolean? = null) =
        LinkCareCommand(op, date?.toEpochDay(), kind, name, note, flag)

    /** Ticks a morning's routine off, or un-ticks it. */
    @Synchronized
    fun toggleMorning(date: LocalDate) {
        val done = date !in log.morningsDone
        change(command("skin.setMorning", date, flag = done)) { it.setMorning(date, done) }
    }

    /**
     * Ticks an evening off, or un-ticks it, and remembers WHAT that evening was ([kind]: an exfoliating night with the Findley
     * treatment or the toner, a niacinamide or Vitamin C night, or a rest night), so the history can say what was really used. An
     * exfoliating kind is also recorded as an exfoliating night — and removed again on un-ticking — because that record is what the next
     * nights are spaced from, and what the two products take turns by.
     */
    @Synchronized
    fun toggleEvening(date: LocalDate, kind: EveningKind) {
        val done = date !in log.eveningsDone
        change(command("skin.setEvening", date, kind = kind.name, flag = done)) { it.setEvening(date, kind, done) }
    }

    /**
     * Says NO to [date]'s evening (no button does this any more; it is kept, with the answers already saved, so they still count) (or
     * takes that back with [skipped] false). A no un-records the evening if it was ticked — nothing of it is remembered — and the plan
     * counts it as not done from now on, even for tonight, so the nights after it are planned again straight away.
     */
    @Synchronized
    fun setEveningSkipped(date: LocalDate, skipped: Boolean) {
        change(command("skin.setSkipped", date, flag = skipped)) { it.setEveningSkipped(date, skipped) }
    }

    /**
     * Remembers [today] as the day the routine began, the first time this is called — the plan is replayed from that day — and,
     * separately, as the day confirming began (see [confirmFrom]; a routine begun before that existed starts confirming the first
     * time it runs with it).
     */
    @Synchronized
    fun ensureStarted(today: LocalDate) {
        if (startedOn != null && confirmFrom != null) return
        change(command("skin.start", today)) { it.ensureStarted(today) }
    }

    /**
     * Records what was ACTUALLY used on [date]'s evening: [kind] (an exfoliating night with either product, a niacinamide, Vitamin C
     * or both night, or a rest night) — replacing whatever was recorded or assumed — or, with null, forgets the record so the day goes
     * back to following the plan. Whatever is recorded also counts as done, and the nights after it follow from it.
     */
    @Synchronized
    fun setUsed(date: LocalDate, kind: EveningKind?) {
        change(command("skin.setUsed", date, kind = kind?.name)) { it.setUsed(date, kind) }
    }

    /**
     * Chooses what [date]'s evening is ([kind]) — on any day, one still to come as well as tonight — or, with null, goes back to what the
     * plan says. It isn't a tick: the plan keeps the evening as chosen and works out the nights after it from it, so the next days'
     * routine changes at once.
     */
    @Synchronized
    fun setChosen(date: LocalDate, kind: EveningKind?) {
        change(command("skin.setChosen", date, kind = kind?.name)) { it.setChosen(date, kind) }
    }

    /** For when an exfoliant was used (or wasn't) on a night other than the plan said; [exfoliant] is which one. */
    @Synchronized
    fun setExfoliationNight(date: LocalDate, used: Boolean, exfoliant: Product = Product.GLYCOLIC) {
        change(command("skin.setExfoliation", date, name = exfoliant.name, flag = used)) { it.setExfoliationNight(date, used, exfoliant) }
    }

    /** Adds a product to the user's list. A blank name, or one already listed (any capitals), is ignored. */
    @Synchronized
    fun addProduct(name: String, note: String = "") {
        val clean = name.trim()
        if (clean.isEmpty() || customProducts.size >= CARE_MAX_CUSTOM_PRODUCTS || customProducts.any { it.name.equals(clean, ignoreCase = true) }) return
        change(command("skin.addProduct", name = clean, note = note.trim())) { it.addProduct(clean, note) }
    }

    /** Takes a product off the user's list. */
    @Synchronized
    fun removeProduct(name: String) {
        change(command("skin.removeProduct", name = name)) { it.removeProduct(name) }
    }

    companion object {
        /** The real file, next to the pairing in `%APPDATA%\a2-desktop`. */
        fun default(): SkinCareStore =
            SkinCareStore(File(File(System.getenv("APPDATA") ?: System.getProperty("user.home"), "a2-desktop"), "skincare.json"))
    }
}
