package com.example.a2desktop.link

import java.io.File
import java.time.LocalDateTime

/**
 * A plain log of anything that went wrong, kept at `%APPDATA%\a2-desktop\crash.log` (next to the
 * pairing, on this PC only), so a problem in the packaged app — which has no console — can always be
 * looked at afterwards. It never throws: logging a problem must not cause another one. The file is
 * trimmed when it grows past ~200 KB.
 */
object CrashLog {
    private const val MAX_BYTES = 200_000L

    /** Where the log lives. The `a2desktop.logDir` system property redirects it — the tests set it,
     * so the deliberate failures they cause never end up in the real log on this PC. */
    val file: File
        get() = File(System.getProperty("a2desktop.logDir")?.let(::File) ?: LinkStorage.defaultDir(), "crash.log")

    fun record(what: String, error: Throwable? = null) {
        runCatching {
            val target = file
            target.parentFile?.mkdirs()
            if (target.exists() && target.length() > MAX_BYTES) target.writeText(target.readText().takeLast(MAX_BYTES.toInt() / 2))
            target.appendText("[${LocalDateTime.now()}] $what\n${error?.stackTraceToString()?.plus("\n") ?: ""}\n")
        }
    }
}
