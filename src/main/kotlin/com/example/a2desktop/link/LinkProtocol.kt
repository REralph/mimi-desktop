package com.example.a2desktop.link

// COPY of the phone project's link/LinkProtocol.kt (only the package line differs). Keep the two in step (the interop test fails if they drift).

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

// The data the phone hands to the desktop app ("Desktop link"). The phone is the source of truth:
// this is a read-only snapshot of what is on the phone right now. The one thing that travels the
// other way is what the user does on the desktop — save a dream (LinkDreamEdit), record a habit on a
// day (LinkHabitEdit), add an event (LinkEventCreate) or a task (LinkTaskCreate): the phone checks it,
// applies it itself and answers with what it now holds. Plain data only (no Android or
// Room types), so the desktop project can hold an identical copy of this file — keep the two in
// step; the desktop's interop test fails if they drift apart.
//
// Dates are epoch days (LocalDate.toEpochDay), colours are ARGB Ints, enums travel as their names.
// Unknown JSON keys are ignored by the desktop, so adding fields here later is backwards compatible.

const val LINK_PROTOCOL_VERSION = 1
const val LINK_DEFAULT_PORT = 47321

/**
 * A feature a phone app announces in [LinkSnapshot.features]: it understands [LinkCareRequest.madeAtMillis] and keeps for each thing in the
 * skin-care and supplements record when it last changed, so of two changes (one on the phone, one made on the desktop while it was out of
 * reach) the later is the one kept. The desktop only sends the time to a phone that says so: an older phone app refuses a request with a
 * field it doesn't know.
 */
const val LINK_FEATURE_CARE_STAMPS = "care.stamps"

@Serializable
data class LinkSnapshot(
    val protocol: Int = LINK_PROTOCOL_VERSION,
    val generatedAtMillis: Long,
    val todayEpochDay: Long,
    val deviceName: String,
    /** From the Life in Months page; null when the user hasn't entered one. Never assumed. */
    val dateOfBirthEpochDay: Long? = null,
    /** Earliest date anything was tracked (habit records or check-ins) — where the automatic Sober
     * days start. Null when nothing has ever been tracked. */
    val earliestTrackedEpochDay: Long? = null,
    val habits: List<LinkHabit> = emptyList(),
    val habitLogs: List<LinkHabitLog> = emptyList(),
    val events: List<LinkEvent> = emptyList(),
    val upcoming: List<LinkUpcoming> = emptyList(),
    val dreams: List<LinkDream> = emptyList(),
    val workouts: List<LinkWorkout> = emptyList(),
    /** When the user works, as shared by their Fourth Better app (start/end times only). Empty when
     * Fourth Better isn't there or hasn't read a rota. */
    val workShifts: List<LinkWorkShift> = emptyList(),
    /** Days a rota read has covered, so a covered day with no shift can show as OFF. */
    val workCoveredDays: List<Long> = emptyList(),
    /** The skin-care and supplements records the phone holds; null from a phone app that doesn't keep them yet. */
    val care: LinkCare? = null,
    /** What this phone app can do beyond the basics, by name (see [LINK_FEATURE_CARE_STAMPS]); empty from an older app. */
    val features: List<String> = emptyList(),
)

/** One working shift: the day it starts on and the wall-clock start/end (minutes after midnight).
 * [endsNextDay] is true for a shift that runs past midnight. */
@Serializable
data class LinkWorkShift(
    val dateEpochDay: Long,
    val startMinuteOfDay: Int,
    val endMinuteOfDay: Int,
    val endsNextDay: Boolean = false,
)

@Serializable
data class LinkHabit(
    val id: Long,
    val emoji: String,
    val name: String,
    val nickname: String,
    val colorArgb: Int,
    /** The designated Sober/streak habit — see the phone's withEffectiveSoberDesignation. */
    val isStreakHabit: Boolean,
    val breaksStreakOfHabitId: Long? = null,
    val linkedToCalendar: Boolean,
    val showInInformation: Boolean,
    val sortOrder: Int,
    /** [HabitDisplayValue] names. */
    val displayValues: List<String>,
    val totalPeriodMonths: Int? = null,
    val goalDurationDays: Int = 90,
    /** [GoalStartMode] name. */
    val goalStartMode: String = "LAST_RECORDED",
    val goalStartDateEpochDay: Long? = null,
    val goalCompletionDateEpochDay: Long? = null,
)

/** Every day one habit was recorded. */
@Serializable
data class LinkHabitLog(val habitId: Long, val days: List<Long>)

@Serializable
data class LinkEvent(
    val id: Long,
    val title: String,
    val dateEpochDay: Long,
    /** Minutes after midnight, or -1 for an all-day event. */
    val startMinuteOfDay: Int,
    val completed: Boolean = false,
)

/** One line of the phone's combined Upcoming list, already worked out by the phone (so the desktop
 * never has to re-implement task recurrence). */
@Serializable
data class LinkUpcoming(
    val id: String,
    val title: String,
    val dueEpochDay: Long,
    /** "TASK" or "EVENT". */
    val kind: String,
    val repeatLabel: String? = null,
)

@Serializable
data class LinkDream(
    val id: Long,
    val dateEpochDay: Long,
    val title: String,
    val description: String,
    /** "DRAFT" or "SAVED". */
    val status: String,
)

@Serializable
data class LinkWorkout(
    val id: Long,
    val dateEpochDay: Long,
    val label: String? = null,
    val durationSeconds: Int? = null,
    val exercises: List<LinkExercise> = emptyList(),
    /** One-line summary of a run session (distance, time, pace), if this was a run. */
    val runSummary: String? = null,
)

@Serializable
data class LinkExercise(
    val name: String,
    val plannedWeight: String = "",
    val plannedSets: String = "",
    val notes: String = "",
    /** Parsed from the phone's structured set log; empty for older free-text-only rows. */
    val sets: List<LinkSet> = emptyList(),
)

@Serializable
data class LinkSet(val weightKg: Double? = null, val reps: Int? = null)

/**
 * A dream saved on the desktop, sent to the phone. [baseTitle] / [baseDescription] are what the
 * desktop last saw on the phone: if the phone's copy has changed since, the phone refuses (the
 * phone is the source of truth, and a stale desktop must never overwrite it). [title] and
 * [description] are the new text.
 */
@Serializable
data class LinkDreamEdit(
    val id: Long,
    val baseTitle: String,
    val baseDescription: String,
    val title: String,
    val description: String,
)

/** The phone's answer to a [LinkDreamEdit]: saved ([dream] is the phone's own saved copy), or why not. */
@Serializable
data class LinkDreamReply(
    val ok: Boolean,
    val message: String? = null,
    val dream: LinkDream? = null,
)

/** The desktop asks the phone to record ([logged] = true) or clear one habit on one day. */
@Serializable
data class LinkHabitEdit(
    val habitId: Long,
    val dateEpochDay: Long,
    val logged: Boolean,
)

/** The desktop asks the phone to add a calendar event. [startMinuteOfDay] is minutes after midnight,
 * or -1 for an all-day event; [repeatIntervalDays] is null for a one-off. */
@Serializable
data class LinkEventCreate(
    val title: String,
    val dateEpochDay: Long,
    val startMinuteOfDay: Int = -1,
    val repeatIntervalDays: Int? = null,
    /** Makes a request that is sent twice (the reply was lost, so the desktop tried again) add the event only once. */
    val requestId: String? = null,
)

/** The desktop asks the phone to add a task due on [dueEpochDay]. [repeatUnit] is NONE, DAYS, WEEKS,
 * MONTHS or YEARS; [repeatInterval] is ignored for NONE. */
@Serializable
data class LinkTaskCreate(
    val name: String,
    val dueEpochDay: Long,
    val repeatInterval: Int = 1,
    val repeatUnit: String = "NONE",
    /** Makes a request that is sent twice (the reply was lost, so the desktop tried again) add the task only once. */
    val requestId: String? = null,
)

/** The phone's answer to a habit, event or task request: done, or why not. For a habit request,
 * [habitIds] is every habit now recorded on [dateEpochDay] (recording one can clear a habit it is
 * linked to, so the desktop shows what the phone ended up with, not what it asked for). */
@Serializable
data class LinkEditReply(
    val ok: Boolean,
    val message: String? = null,
    val dateEpochDay: Long? = null,
    val habitIds: List<Long>? = null,
    /** For a [LinkCareRequest]: the skin-care and supplements records as the phone now holds them. */
    val care: LinkCare? = null,
)

/** The desktop asks the phone to change the event it already holds with [id] (the id the snapshot's events carry): its title, date
 * ([dateEpochDay] is its next/current date), time and repeat are replaced. Whatever else the phone keeps for it — its reminder, how
 * far ahead it shows, paused, completed — is left alone. [keepRepeat] true leaves the repeat exactly as it is and ignores
 * [repeatIntervalDays], for when the desktop doesn't know how the event repeats. */
@Serializable
data class LinkEventUpdate(
    val id: Long,
    val title: String,
    val dateEpochDay: Long,
    val startMinuteOfDay: Int = -1,
    val repeatIntervalDays: Int? = null,
    val keepRepeat: Boolean = false,
    /** When the change was made on the desktop (epoch milliseconds; 0 = not said). A change made while the phone was out of reach is
     * only applied if the phone's copy hasn't been changed since — the later change wins. */
    val madeAtMillis: Long = 0,
)

/** The desktop asks the phone to delete the event with [id], exactly as the Delete button of the phone's own event window does. */
@Serializable
data class LinkEventDelete(
    val id: Long,
    /** See [LinkEventUpdate.madeAtMillis]. */
    val madeAtMillis: Long = 0,
)

/** The desktop asks the phone to change the task it already holds with [id] (the number in the snapshot's "task-N" id): its name, its
 * outstanding due date and its repeat are replaced; its reminder, history and the rest are left alone. [keepRepeat] true leaves the
 * repeat exactly as it is and ignores [repeatInterval] and [repeatUnit]. */
@Serializable
data class LinkTaskUpdate(
    val id: Long,
    val name: String,
    val dueEpochDay: Long,
    val repeatInterval: Int = 1,
    val repeatUnit: String = "NONE",
    val keepRepeat: Boolean = false,
    /** See [LinkEventUpdate.madeAtMillis]. */
    val madeAtMillis: Long = 0,
)

/** The desktop asks the phone to delete the task with [id] — as the phone's own Delete does, by switching it off (it stays in Manage
 * Tasks with its history, ready to switch back on), never by erasing it. */
@Serializable
data class LinkTaskDelete(
    val id: Long,
    /** See [LinkEventUpdate.madeAtMillis]. */
    val madeAtMillis: Long = 0,
)

// ---- Skin care and supplements: kept ON THE PHONE (the source of truth) and drawn by both apps ----

/** A product the user has beyond the four the skin-care plan knows about. */
@Serializable
data class LinkCustomProduct(val name: String, val note: String = "")

/** The skin-care record: epoch days; [eveningKinds] maps a day to an EveningKind name. */
@Serializable
data class LinkSkinCare(
    val glycolicNights: List<Long> = emptyList(),
    val morningsDone: List<Long> = emptyList(),
    val eveningsDone: List<Long> = emptyList(),
    val tonerNights: List<Long> = emptyList(),
    val eveningKinds: Map<Long, String> = emptyMap(),
    val eveningsSkipped: List<Long> = emptyList(),
    /** Day -> EveningKind name: the evenings whose product the user chose (not necessarily done yet). */
    val chosenKinds: Map<Long, String> = emptyMap(),
    val startedOn: Long? = null,
    val confirmFrom: Long? = null,
    val customProducts: List<LinkCustomProduct> = emptyList(),
)

/** The supplements record: the choices, the days ticking and tracking began, and [marks] (day -> Supplement name -> taken). */
@Serializable
data class LinkVitamins(
    val zincEveryDay: Boolean = false,
    val magnesiumTablets: Int = 1,
    val softgelDays: Int = 3,
    val organCapsules: Int = 3,
    val trackedFrom: Long? = null,
    val tickFrom: Long? = null,
    val marks: Map<Long, Map<String, Boolean>> = emptyMap(),
)

/** Both records together: what the phone holds, sent in the snapshot and in the answer to every [LinkCareRequest]. */
@Serializable
data class LinkCare(
    val skin: LinkSkinCare = LinkSkinCare(),
    val vitamins: LinkVitamins = LinkVitamins(),
)

/**
 * One change to the record: [op] says which ("skin.setEvening", "skin.setMorning", "skin.setSkipped", "skin.start", "skin.setUsed",
 * "skin.setExfoliation", "skin.setChosen", "skin.addProduct", "skin.removeProduct", "vit.setMark", "vit.resetDay", "vit.zincEveryDay", "vit.magnesium",
 * "vit.softgelDays", "vit.organs", "vit.start"); the rest are its arguments. Every change SETS a state rather than flipping one, so a
 * request that arrives twice does no harm.
 */
@Serializable
data class LinkCareCommand(
    val op: String,
    val date: Long? = null,
    val kind: String? = null,
    val name: String? = null,
    val note: String? = null,
    val flag: Boolean? = null,
    val count: Int? = null,
    val supplement: String? = null,
)

/** The desktop asks the phone to make [commands] in order and/or, once, to fold its own earlier record [mergeCare] into the phone's. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class LinkCareRequest(
    val commands: List<LinkCareCommand> = emptyList(),
    val mergeCare: LinkCare? = null,
    /**
     * When the change was made on the desktop (epoch milliseconds). 0 means not said — and then the field is not sent at all, because an
     * older phone app doesn't know it and would refuse the request (see [LINK_FEATURE_CARE_STAMPS]). A change made while the phone was out
     * of reach is applied, for each thing it touches (see careKeysOf), only if the phone's own copy of that thing hasn't changed since:
     * the later change wins.
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val madeAtMillis: Long = 0,
)
