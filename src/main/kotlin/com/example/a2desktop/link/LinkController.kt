package com.example.a2desktop.link

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.a2desktop.data.DesktopStore
import java.net.InetAddress
import java.time.LocalDate
import java.util.UUID
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** The code the Phone page shows for the phone to scan: the key this PC made for the pairing and the text of the QR code. */
class QrPairingSession(val key: String, val payload: String)

/**
 * Everything the Phone page and status bar need to know about the phone link, as Compose state:
 * whether a phone is paired, whether a sync is running, the last result. It owns the sync itself —
 * fetch the snapshot, cache it, hand it to the [DesktopStore] — and runs it off the UI thread via
 * [workContext] (swappable so tests run it inline). On creation it shows the cached copy of the
 * phone's data straight away, so the app is useful even before (or without) a successful sync.
 *
 * Three things keep it working without anyone looking after it:
 *  - **Finding the phone again.** The phone's address on the home network can change. When the phone does not answer where it was, the
 *    controller asks the other devices on this PC's network whether a Mimi phone with its pairing key is there ([PhoneFinder]) and
 *    carries on with the new address.
 *  - **Pairing by QR code.** [startQrPairing] makes a fresh pairing key and shows it as a code; once the phone has scanned it the
 *    controller finds the phone by itself and the pairing is saved.
 *  - **Changes while the phone is out of reach.** Adding, changing or deleting an event or task, or recording a habit, is never refused for
 *    want of the phone: the change is kept in the [Outbox] and shown on screen at once, and goes to the phone — in the order it was made,
 *    each dated with the moment it was made — the next time the phone answers. The phone keeps a later change of its own over an earlier
 *    one of the desktop's; the screen then shows what the phone now holds.
 */
class LinkController(
    private val store: DesktopStore,
    private val storage: LinkStorage,
    private val source: SnapshotSource,
    private val scope: CoroutineScope,
    private val workContext: CoroutineContext = Dispatchers.IO,
    private val writer: DreamWriter = DreamWriter { _, _, _ -> DreamSaveResult.Failed("Saving to the phone isn't available.") },
    private val editor: EditWriter = EditWriter { _, _, _, _ -> EditResult.Failed("Sending changes to the phone isn't available.") },
    private val outbox: Outbox = Outbox(null),
    private val finder: PhoneFinder? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val vpnCheck: () -> Boolean = { false },
) {
    private val json = Json { ignoreUnknownKeys = true }

    var pairing by mutableStateOf(storage.loadPairing())
        private set
    var syncing by mutableStateOf(false)
        private set
    @Volatile private var inFlight = false
    /** The last failure, in plain English; null when the last attempt worked (or none has run). */
    var error by mutableStateOf<String?>(null)
        private set
    /** True when the most recent sync succeeded, i.e. the phone is answering right now. */
    var reachable by mutableStateOf(false)
        private set
    /** True when the last attempt failed because the phone could not be reached over the network (not because it said no). */
    @Volatile private var lastFailureUnreachable = false
    /** True when the phone could not be reached and a VPN is running on this PC — a VPN can stop it reaching the home network. */
    var vpnHint by mutableStateOf(false)
        private set
    var lastSyncedMillis by mutableStateOf<Long?>(null)
        private set
    /** True while a habit, event or task change is being sent to the phone (the controls wait for it). */
    var editing by mutableStateOf(false)
        private set
    @Volatile private var pendingResync = false
    private val editJson = Json { encodeDefaults = true }

    /** True while a dream is being sent to the phone (the Save button waits for it). */
    var savingDream by mutableStateOf(false)
        private set

    /** How many changes are waiting to reach the phone. */
    var pendingCount by mutableStateOf(outbox.changes.size)
        private set

    /** What is waiting, oldest first, in a few words each. */
    var pendingLabels by mutableStateOf(outbox.changes.map { it.label })
        private set

    /** Changes the phone could not take (it had changed the same thing later, or the thing was gone), until they are dismissed. */
    var notes by mutableStateOf(outbox.notes)
        private set

    /** The pairing code on show, or null. */
    var qrSession by mutableStateOf<QrPairingSession?>(null)
        private set
    private var qrJob: Job? = null

    init {
        loadCached()
        syncNow()
    }

    private fun refreshPendingState() {
        pendingCount = outbox.changes.size
        pendingLabels = outbox.changes.map { it.label }
        notes = outbox.notes
    }

    private fun loadCached() {
        val cached = storage.loadSnapshotJson() ?: return
        // A damaged or unreadable cache must never stop the app opening: drop it and carry on (the
        // next sync rebuilds it).
        runCatching {
            val snapshot = json.decodeFromString<LinkSnapshot>(cached)
            store.applySnapshot(snapshot)
            store.applyPending(outbox.changes)
            lastSyncedMillis = snapshot.generatedAtMillis
        }.onFailure {
            CrashLog.record("Discarded an unreadable cached snapshot", it)
            storage.discardSnapshot()
        }
    }

    /**
     * Reads what the user pasted/typed — which may hold several addresses, a name and time copied
     * from a chat, invisible characters — and starts the first sync, trying each address found (the
     * one on this PC's network first) until the phone answers. Returns a problem to show, or null.
     * The pairing is only saved once the phone has actually answered.
     */
    fun connect(addressText: String, keyText: String): String? {
        val addresses = LinkInput.addresses(addressText)
        if (addresses.isEmpty()) {
            return "I couldn't find a phone address in \"${addressText.trim().take(60)}\". " +
                "It looks like 192.168.0.16:47321 — copy it from the phone's Desktop link page."
        }
        val key = LinkInput.pairingKey(keyText)
            ?: return "I couldn't find the pairing key in \"${keyText.trim().take(60)}\". " +
                "It is 20 letters and digits shown on the phone, like ABCD-EFGH-JKLM-NPQR-STUV."
        startSync(addresses, key, isNewPairing = true, quiet = false)
        return null
    }

    fun syncNow() {
        val saved = pairing ?: return
        val address = LinkAddress.parse(saved.address) ?: return
        startSync(listOf(address), saved.pairingKey, isNewPairing = false, quiet = false)
    }

    /** The automatic refresh: the same sync, but without the "Syncing…" flicker in the status bar and
     * buttons — it runs every few seconds while the phone isn't answering, until it does. */
    fun refreshQuietly() {
        val saved = pairing ?: return
        val address = LinkAddress.parse(saved.address) ?: return
        startSync(listOf(address), saved.pairingKey, isNewPairing = false, quiet = true)
    }

    private fun startSync(addresses: List<LinkAddress>, key: String, isNewPairing: Boolean, quiet: Boolean = false) {
        if (inFlight) {
            // Someone asked for a sync while another is running: show it as working, and go again as
            // soon as the running one finishes — the running one may have read the phone before the
            // change that made them ask.
            if (!quiet) {
                syncing = true
                pendingResync = true
            }
            return
        }
        inFlight = true
        if (!quiet) syncing = true
        scope.launch(workContext) {
            // Whatever goes wrong — network, a damaged reply, a full disk — the outcome is a message
            // on screen and a working "Sync now" button, never a stuck or dead app.
            try {
                var failure: LinkResult.Failed? = null
                var success: Pair<LinkAddress, LinkResult.Ok>? = null
                for (address in addresses) {
                    when (val result = source.fetch(address, key)) {
                        is LinkResult.Ok -> { success = address to result; break }
                        is LinkResult.Failed -> if (failure == null) failure = result // keep the first address's reason
                    }
                }
                // Not answering where it was: the phone's address may have changed — look for it on this network.
                if (success == null && !isNewPairing && failure?.unreachable == true) success = findAgain(addresses, key)
                if (success != null) {
                    var (address, result) = success
                    // Whatever was changed here while the phone was out of reach goes to it first, so the snapshot taken next has it all.
                    if (!isNewPairing && outbox.changes.isNotEmpty() && sendWaitingChanges(address, key)) {
                        (source.fetch(address, key) as? LinkResult.Ok)?.let { result = it }
                    }
                    acceptSnapshot(address, key, result, savePairing = isNewPairing || pairing?.address != address.toString())
                    lastFailureUnreachable = false
                    vpnHint = false
                    reachable = true
                    error = null
                } else {
                    reachable = false
                    lastFailureUnreachable = failure?.unreachable == true
                    vpnHint = lastFailureUnreachable && runCatching(vpnCheck).getOrDefault(false)
                    error = (failure?.message ?: "Couldn't reach the phone.") +
                        if (addresses.size > 1) " (Tried ${addresses.joinToString { it.toString() }}.)" else ""
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                CrashLog.record("Sync failed unexpectedly", e)
                reachable = false
                error = "Something went wrong reading the phone's data (${e.message ?: e::class.simpleName}). Try again."
            } finally {
                syncing = false
                inFlight = false
                if (pendingResync) {
                    pendingResync = false
                    refreshQuietly()
                }
            }
        }
    }

    /** Shows the phone's data (with this PC's waiting changes on top), caches it, and saves the pairing if it is new or moved. */
    private fun acceptSnapshot(address: LinkAddress, key: String, result: LinkResult.Ok, savePairing: Boolean) {
        store.applySnapshot(result.snapshot)
        store.applyPending(outbox.changes)
        if (savePairing) {
            val saved = StoredPairing(address.toString(), key)
            storage.savePairing(saved)
            pairing = saved
        }
        runCatching { storage.saveSnapshotJson(result.rawJson) } // only a cache: failing to save it isn't fatal
            .onFailure { CrashLog.record("Couldn't cache the snapshot", it) }
        lastSyncedMillis = result.snapshot.generatedAtMillis
        refreshPendingState()
    }

    /**
     * The phone did not answer at [tried]: ask the devices on this PC's network whether a Mimi phone with this PC's key is there, and
     * return the first that gives its snapshot. A phone that shows a different key fingerprint is somebody else's and is never asked.
     */
    private fun findAgain(tried: List<LinkAddress>, key: String): Pair<LinkAddress, LinkResult.Ok>? {
        val finder = finder ?: return null
        val keyId = LinkCrypto.normalizePairingKey(key)?.let { LinkCrypto.keyId(LinkCrypto.deriveKeys(it)) }
        val found = finder.search(firstHosts = tried.map { it.host })
            .filter { it.address !in tried && (it.keyId == null || it.keyId == keyId) }
        for (phone in found) {
            val result = source.fetch(phone.address, key)
            if (result is LinkResult.Ok) return phone.address to result
        }
        return null
    }

    /**
     * Gives the phone, in order, the changes made here while it was out of reach. A change the phone took leaves the list; one it
     * refused (it had changed the same thing later, or the thing is gone) leaves the list with a note saying why; if the phone stops
     * answering the rest wait. Returns true if the phone answered at least one (so the screen should be refreshed from it).
     */
    private fun sendWaitingChanges(address: LinkAddress, key: String): Boolean {
        var answered = false
        for (change in outbox.changes) {
            val result = try {
                editor.send(address, key, change.path, change.bodyJson(editJson))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                CrashLog.record("Sending a waiting change to the phone failed unexpectedly", e)
                break
            }
            when (result) {
                is EditResult.Done -> { outbox.remove(change.id); answered = true }
                is EditResult.Failed -> if (result.phoneRefused) {
                    outbox.remove(change.id)
                    outbox.addNote("${change.label}: ${result.message}")
                    answered = true
                } else {
                    break
                }
            }
        }
        refreshPendingState()
        return answered
    }

    /** Forgets the notes about refused changes (they have been read). */
    fun dismissNotes() {
        outbox.clearNotes()
        refreshPendingState()
    }

    // ---- Pairing by QR code ----

    /** Makes a fresh pairing key, shows it as a code ([qrSession]) and looks for the phone every few seconds until it has scanned it. */
    fun startQrPairing(pcName: String = pcName()) {
        stopQrPairing()
        val key = LinkCrypto.normalizePairingKey(LinkCrypto.generatePairingKey())!!
        val session = QrPairingSession(key, QrPairing.encode(key, pcName))
        qrSession = session
        qrJob = scope.launch(workContext) {
            while (isActive && qrSession === session) {
                if (tryQrPairing(session)) break
                delay(QR_SEARCH_EVERY_MILLIS)
            }
        }
    }

    fun stopQrPairing() {
        qrJob?.cancel()
        qrJob = null
        qrSession = null
    }

    /** One look for the phone that has scanned [session]'s code. True (and paired) if it was found and gave its data. */
    internal fun tryQrPairing(session: QrPairingSession): Boolean {
        val finder = finder ?: return false
        val keyId = LinkCrypto.keyId(LinkCrypto.deriveKeys(session.key))
        for (phone in finder.search(firstHosts = pairing?.let { listOf(LinkAddress.parse(it.address)?.host).filterNotNull() }.orEmpty())) {
            if (phone.keyId != keyId) continue // somebody else's phone, or one that has not scanned the code yet
            val result = source.fetch(phone.address, session.key) as? LinkResult.Ok ?: continue
            acceptSnapshot(phone.address, session.key, result, savePairing = true)
            lastFailureUnreachable = false
            vpnHint = false
            reachable = true
            error = null
            qrSession = null
            return true
        }
        return false
    }

    private fun pcName(): String =
        System.getenv("COMPUTERNAME")?.takeIf { it.isNotBlank() } ?: runCatching { InetAddress.getLocalHost().hostName }.getOrDefault("This PC")

    /**
     * Sends a dream saved on the desktop to the phone, off the UI thread, and reports the outcome to
     * [onDone] (also off the UI thread). The phone does the saving and is the source of truth: on
     * success the desktop shows the phone's own saved copy, and if the phone refuses because the dream
     * changed there, the desktop re-syncs so it catches up (the user's text stays in the editor).
     */
    fun saveDream(edit: LinkDreamEdit, onDone: (DreamSaveResult) -> Unit) {
        val saved = pairing
        val address = saved?.let { LinkAddress.parse(it.address) }
        if (saved == null || address == null) {
            onDone(DreamSaveResult.Failed("No phone is paired, so there is nowhere to save the dream. Connect your phone on the Phone page."))
            return
        }
        if (savingDream) {
            onDone(DreamSaveResult.Failed("Still saving the last change — one moment."))
            return
        }
        savingDream = true
        scope.launch(workContext) {
            val result = try {
                writer.save(address, saved.pairingKey, edit)
            } catch (e: CancellationException) {
                savingDream = false
                throw e
            } catch (e: Exception) {
                CrashLog.record("Saving a dream to the phone failed unexpectedly", e)
                DreamSaveResult.Failed("Something went wrong saving to the phone (${e.message ?: e::class.simpleName}). The dream was not saved.")
            }
            when (result) {
                is DreamSaveResult.Saved -> {
                    store.applyDreamFromPhone(result.dream)
                    reachable = true
                    error = null
                }
                is DreamSaveResult.Failed -> if (!result.phoneRefused) reachable = false
            }
            savingDream = false
            onDone(result)
            // Either way, ask the phone for its current data: the cache and screens then match it exactly.
            if (result is DreamSaveResult.Saved || (result is DreamSaveResult.Failed && result.phoneRefused)) syncNow()
        }
    }

    private fun newId() = UUID.randomUUID().toString()

    /** Records ([logged] = true) or clears [habitId] on [date] — on the phone, which applies its own link
     * rules and answers with every habit it now holds for that day; that is what the desktop then shows.
     * With the phone out of reach the change is kept and shown here, and sent when the phone is back. */
    fun setHabit(date: LocalDate, habitId: Long, logged: Boolean, onDone: (EditResult) -> Unit = {}) {
        val name = store.habits.firstOrNull { it.id == habitId }?.name ?: "habit"
        runEdit(SetHabitChange(newId(), clock(), LinkHabitEdit(habitId, date.toEpochDay(), logged), name), onDone) { reply ->
            reply.habitIds?.let { store.applyHabitDay(date, it.toSet()) }
        }
    }

    fun addEvent(request: LinkEventCreate, onDone: (EditResult) -> Unit = {}) =
        runEdit(AddEventChange(newId(), clock(), outbox.nextTempId(), request.copy(requestId = newId())), onDone)

    fun addTask(request: LinkTaskCreate, onDone: (EditResult) -> Unit = {}) =
        runEdit(AddTaskChange(newId(), clock(), outbox.nextTempId(), request.copy(requestId = newId())), onDone)

    private val careLock = Mutex()

    /**
     * Sends [commands] (changes of the skin-care and supplements record) to the phone, and/or — once, when the two are first joined —
     * folds the desktop's own earlier record [merge] into the phone's. They go one request at a time, in order, so a quick run of ticks
     * is never refused; the phone makes each with the shared code and answers with the record as it now is, which the app then shows.
     *
     * An ordinary change does not need the phone to be there: with the phone out of reach (or earlier changes still waiting, which must go
     * first) it is kept — on disk, with the moment it was made — and shown at once on top of the phone's record, reported as done, and
     * sent when the phone answers; the phone then keeps, for each thing, whichever of its own change and this one is later. Only the
     * one-time joining ([merge]) has to have the phone, and says so if it can't be reached.
     */
    fun sendCare(commands: List<LinkCareCommand>, merge: LinkCare? = null, onDone: (EditResult) -> Unit = {}) {
        val saved = pairing
        val address = saved?.let { LinkAddress.parse(it.address) }
        if (saved == null || address == null) {
            onDone(EditResult.Failed("No phone is paired, so there is nowhere to send that. Connect your phone on the Phone page."))
            return
        }
        val keepable = merge == null && commands.isNotEmpty()
        scope.launch(workContext) {
            careLock.withLock {
                val now = clock()
                // The moment is only sent to a phone that says it understands it: an older phone app refuses a request with a field it doesn't know.
                val request = LinkCareRequest(commands, merge, madeAtMillis = if (store.phoneStampsCare) now else 0)
                val change = CareChange(newId(), now, request)
                val waiting = keepable && (outbox.changes.isNotEmpty() || (!reachable && lastFailureUnreachable))
                val result: EditResult? = if (waiting) null else try {
                    editor.send(address, saved.pairingKey, "/v1/care", editJson.encodeToString(request))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    CrashLog.record("Sending a skin-care or supplements change to the phone failed unexpectedly", e)
                    EditResult.Failed("Something went wrong sending that to the phone (${e.message ?: e::class.simpleName}). Nothing was changed.")
                }
                when {
                    result is EditResult.Done -> {
                        result.reply.care?.let { store.applyCare(it) }
                        reachable = true
                        error = null
                        onDone(result)
                    }
                    keepable && (result == null || (result is EditResult.Failed && result.unreachable)) -> {
                        // The phone can't be reached (or must wait its turn): keep the change, show it, say it is done.
                        if (result != null) {
                            reachable = false
                            lastFailureUnreachable = true
                        }
                        outbox.enqueue(change)
                        store.applyPending(listOf(change))
                        refreshPendingState()
                        onDone(EditResult.Done(LinkEditReply(ok = true, message = KEPT_MESSAGE)))
                        refreshQuietly() // tries to hand it over now if the phone is in fact there
                    }
                    result is EditResult.Failed -> {
                        if (!result.phoneRefused) reachable = false
                        onDone(result)
                    }
                    else -> onDone(EditResult.Failed("Nothing was sent."))
                }
            }
        }
    }

    // Changing or deleting what the phone already holds. The phone checks each with its own rules and does it with the code its own
    // screens use; the desktop then re-syncs, so what is on screen is what the phone now has. An older phone app that doesn't know
    // these requests answers "not found" and the desktop says the phone needs updating.

    /** Changes the event the phone holds under [request].id: its title, date, time and repeat. */
    fun updateEvent(request: LinkEventUpdate, onDone: (EditResult) -> Unit = {}) {
        val now = clock()
        runEdit(UpdateEventChange(newId(), now, request.copy(madeAtMillis = now)), onDone)
    }

    /** Deletes the event with [id] from the phone. */
    fun deleteEvent(id: Long, onDone: (EditResult) -> Unit = {}) {
        val now = clock()
        val title = store.events.firstOrNull { it.id == id }?.title ?: "event"
        runEdit(DeleteEventChange(newId(), now, LinkEventDelete(id, now), title), onDone)
    }

    /** Changes the task the phone holds under [request].id: its name, due date and repeat. */
    fun updateTask(request: LinkTaskUpdate, onDone: (EditResult) -> Unit = {}) {
        val now = clock()
        runEdit(UpdateTaskChange(newId(), now, request.copy(madeAtMillis = now)), onDone)
    }

    /** Deletes the task with [id] from the phone (the phone switches it off, keeping it in Manage Tasks with its history). */
    fun deleteTask(id: Long, onDone: (EditResult) -> Unit = {}) {
        val now = clock()
        val title = store.upcoming.firstOrNull { it.id == "task-$id" }?.title ?: "task"
        runEdit(DeleteTaskChange(newId(), now, LinkTaskDelete(id, now), title), onDone)
    }

    /**
     * Makes [change] — sent to the phone straight away when it is answering, kept and shown here when it is not. [onDone] hears the
     * outcome (off the UI thread). When the phone answers, [onSuccess] shows what it answered and the desktop then syncs so the screen
     * matches the phone exactly; when it refuses, nothing changes here and the desktop syncs to catch up. When the phone cannot be
     * reached over the network — or earlier changes are still waiting, which must go first — the change is kept (see [Outbox]), shown on
     * screen at once, and reported as done: it goes to the phone, in order, the next time the phone answers.
     */
    private fun runEdit(change: PendingChange, onDone: (EditResult) -> Unit, onSuccess: (LinkEditReply) -> Unit = {}) {
        val saved = pairing
        val address = saved?.let { LinkAddress.parse(it.address) }
        if (saved == null || address == null) {
            onDone(EditResult.Failed("No phone is paired, so there is nowhere to send that. Connect your phone on the Phone page."))
            return
        }
        if (editing) {
            onDone(EditResult.Failed("Still sending the last change — one moment."))
            return
        }
        editing = true
        scope.launch(workContext) {
            val waiting = outbox.changes.isNotEmpty() || (!reachable && lastFailureUnreachable)
            val result: EditResult? = if (waiting) null else try {
                editor.send(address, saved.pairingKey, change.path, change.bodyJson(editJson))
            } catch (e: CancellationException) {
                editing = false
                throw e
            } catch (e: Exception) {
                CrashLog.record("Sending a change to the phone failed unexpectedly", e)
                EditResult.Failed("Something went wrong sending that to the phone (${e.message ?: e::class.simpleName}). Nothing was changed.")
            }
            when {
                result is EditResult.Done -> {
                    onSuccess(result.reply)
                    reachable = true
                    error = null
                    editing = false
                    onDone(result)
                    syncNow()
                }
                result is EditResult.Failed && !result.unreachable -> {
                    if (!result.phoneRefused) reachable = false
                    editing = false
                    onDone(result)
                    if (result.phoneRefused) syncNow()
                }
                else -> {
                    // The phone can't be reached (or must wait its turn): keep the change, show it, say it is done.
                    if (result != null) {
                        reachable = false
                        lastFailureUnreachable = true
                    }
                    outbox.enqueue(change)
                    store.applyPending(listOf(change))
                    refreshPendingState()
                    editing = false
                    onDone(EditResult.Done(LinkEditReply(ok = true, message = KEPT_MESSAGE)))
                    refreshQuietly() // tries to hand it over now if the phone is in fact there
                }
            }
        }
    }

    /** Forgets the phone and deletes the cached copy of its data from this PC. The app goes back to
     * showing no data at all — never to made-up data. Changes still waiting for the phone are dropped with it. */
    fun forget() {
        stopQrPairing()
        storage.clear()
        outbox.clear()
        refreshPendingState()
        pairing = null
        reachable = false
        error = null
        vpnHint = false
        lastSyncedMillis = null
        store.clear()
    }

    companion object {
        /** What the reply to a change says when it was kept for later rather than made on the phone. */
        const val KEPT_MESSAGE = "Saved here — it will reach the phone when it can be reached."
        const val QR_SEARCH_EVERY_MILLIS = 2_500L
    }
}
