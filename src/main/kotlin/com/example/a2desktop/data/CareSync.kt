package com.example.a2desktop.data

import com.example.a2desktop.link.EditResult
import com.example.a2desktop.link.LinkCareCommand
import java.io.File

/**
 * Where the skin-care and supplements stores send a change when the phone keeps the record (see [SkinCareStore], [VitaminStore]):
 * the phone makes it with the shared code and answers with the whole record, which the app then shows. [onDone] is told how it went.
 */
fun interface CareSender {
    fun send(commands: List<LinkCareCommand>, onDone: (EditResult) -> Unit)
}

/**
 * Remembers which phone the desktop's own earlier skin-care and supplements record has already been folded into (by its device
 * name), so that happens once and not on every start. Nothing here is ever deleted: the desktop's own files stay as they were.
 * [file] null keeps it in memory only (previews and tests).
 */
class CareSync(private val file: File? = null) {
    private var done: String? = file?.let { f -> runCatching { f.readText().trim() }.getOrNull()?.ifEmpty { null } }

    /** Whether the earlier record has already been joined to the phone called [device]. */
    fun isMerged(device: String): Boolean = done == device

    fun markMerged(device: String) {
        done = device
        file?.let { f -> runCatching { f.parentFile?.mkdirs(); f.writeText(device) } }
    }

    companion object {
        /** The real file, next to the pairing in `%APPDATA%\a2-desktop`. */
        fun default(): CareSync = CareSync(File(File(System.getenv("APPDATA") ?: System.getProperty("user.home"), "a2-desktop"), "care-merged.txt"))
    }
}
