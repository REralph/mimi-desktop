package com.example.a2desktop

import com.example.a2desktop.data.DesktopStore
import com.example.a2desktop.data.JobsStore
import com.example.a2desktop.data.sampleJobsStore
import com.example.a2desktop.data.sampleSnapshot
import com.example.a2desktop.link.LinkAddress
import com.example.a2desktop.link.LinkController
import com.example.a2desktop.link.LinkResult
import com.example.a2desktop.link.LinkStorage
import com.example.a2desktop.link.SnapshotSource
import com.example.a2desktop.link.StoredPairing
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

// `Mimi.exe --demo` (from a terminal: `gradlew run --args=--demo`) opens the whole app filled with the invented sample data
// in data/SampleData.kt and a pretend phone — for anyone without the Mimi phone app, such as someone trying the download
// from GitHub. It reads and writes nothing in %APPDATA%\a2-desktop (only a crash would leave its usual crash.log entry):
// every store is memory-only, the pretend pairing lives in a temporary folder deleted on exit, and anything that would be
// sent to a phone just says that isn't available.

/** The command-line switch that opens the demo instead of the real app. */
internal const val DEMO_ARG = "--demo"

internal fun isDemo(args: Array<String>): Boolean = args.any { it.equals(DEMO_ARG, ignoreCase = true) }

/** An address and pairing key that only ever reach [SamplePhone]: nothing is sent over the network. */
internal const val SAMPLE_PHONE_ADDRESS = "192.168.1.23:47321"
internal const val SAMPLE_PHONE_KEY = "ABCDEFGHJKLMNPQRSTUV"

/** What the demo window runs on in place of the real stores. */
internal class DemoSetup(scope: CoroutineScope, workContext: CoroutineContext = Dispatchers.IO) {
    val store = DesktopStore()

    /** Holds the pretend pairing and the last "synced" sample snapshot while the demo runs. */
    val storageDir: File = Files.createTempDirectory("mimi-demo").toFile().also { dir ->
        Runtime.getRuntime().addShutdownHook(Thread { dir.deleteRecursively() })
    }

    val link = LinkController(
        store,
        LinkStorage(storageDir).also { it.savePairing(StoredPairing(SAMPLE_PHONE_ADDRESS, SAMPLE_PHONE_KEY)) },
        SamplePhone("Sample phone"),
        scope,
        workContext,
    )

    /** The Jobs page with an invented, already-read mailbox. */
    val jobs: JobsStore = sampleJobsStore()
}

/** Answers like a phone that is serving the sample snapshot. Used by the demo and by PreviewRenderer. */
internal class SamplePhone(private val deviceName: String) : SnapshotSource {
    private val json = Json { encodeDefaults = true }

    override fun fetch(address: LinkAddress, pairingKey: String): LinkResult {
        val today = LocalDate.now()
        val snapshot = sampleSnapshot(today).copy(
            deviceName = deviceName,
            dateOfBirthEpochDay = today.minusYears(28).minusMonths(4).minusDays(8).toEpochDay(),
        )
        return LinkResult.Ok(snapshot, json.encodeToString(snapshot))
    }
}
