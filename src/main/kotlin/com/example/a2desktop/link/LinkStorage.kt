package com.example.a2desktop.link

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class StoredPairing(val address: String, val pairingKey: String)

/**
 * Remembers the paired phone (its address and the pairing key) and the last snapshot received, in
 * the user's own profile folder (`%APPDATA%\a2-desktop`), so the app opens with the phone's data
 * even when the phone isn't reachable. Both files stay on this PC and are only readable by the
 * signed-in Windows user by default; "Forget this phone" deletes them. The cached snapshot is the
 * phone's data in plain JSON — it is as private as the rest of the user's profile folder.
 */
class LinkStorage(private val dir: File = defaultDir()) {
    private val json = Json { ignoreUnknownKeys = true }
    private val pairingFile get() = File(dir, "link.json")
    private val snapshotFile get() = File(dir, "snapshot.json")

    fun loadPairing(): StoredPairing? =
        runCatching { json.decodeFromString<StoredPairing>(pairingFile.readText()) }.getOrNull()

    fun savePairing(pairing: StoredPairing) {
        dir.mkdirs()
        pairingFile.writeText(json.encodeToString(pairing))
    }

    fun loadSnapshotJson(): String? = runCatching { snapshotFile.readText() }.getOrNull()

    fun saveSnapshotJson(text: String) {
        dir.mkdirs()
        snapshotFile.writeText(text)
    }

    /** Deletes only the cached copy of the phone's data (e.g. when it turns out to be unreadable). */
    fun discardSnapshot() {
        snapshotFile.delete()
    }

    /** Deletes the pairing and the cached copy of the phone's data. */
    fun clear() {
        pairingFile.delete()
        snapshotFile.delete()
    }

    companion object {
        fun defaultDir(): File =
            File(System.getenv("APPDATA") ?: System.getProperty("user.home"), "a2-desktop")
    }
}
