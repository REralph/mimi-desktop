package com.example.a2desktop

import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import com.example.a2desktop.data.CalendarEmojiStore
import com.example.a2desktop.data.DesktopStore
import com.example.a2desktop.link.LinkController
import com.example.a2desktop.link.LinkStorage
import com.example.a2desktop.link.StoredPairing
import com.example.a2desktop.theme.A2Theme
import com.example.a2desktop.ui.A2App
import com.example.a2desktop.ui.Page
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import org.jetbrains.skia.EncodedImageFormat

/**
 * Renders the pages to PNGs without opening a window (`./gradlew renderPreviews`) so the layout can
 * be checked on any machine. `<page>.png` shows the sample data with nothing paired; the `_linked`
 * images show the same pages as they look once a (fake) phone has synced. The sample date of birth
 * used for the Life page exists only here — the real app never assumes one.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun main(args: Array<String>) {
    val outDir = File(args.firstOrNull() ?: "build/previews").apply { mkdirs() }

    Page.entries.forEach { page ->
        val store = DesktopStore().also {
            it.loadSampleData()
            it.enterDateOfBirth(it.today.minusYears(28).minusMonths(4).minusDays(8))
        }
        val link = LinkController(store, tempStorage(), SamplePhone("Preview phone"), CoroutineScope(Job()), Dispatchers.Unconfined)
        render(File(outDir, "${page.name.lowercase()}.png"), 1360, 860) { A2Theme { A2App(store, link, page) } }
    }

    // Nothing received yet — what a data page looks like before the phone has sent anything.
    listOf(Page.CALENDAR, Page.GYM).forEach { page ->
        val store = DesktopStore()
        val link = LinkController(store, tempStorage(), SamplePhone("Preview phone"), CoroutineScope(Job()), Dispatchers.Unconfined)
        render(File(outDir, "${page.name.lowercase()}_nodata.png"), 1360, 860) { A2Theme { A2App(store, link, page) } }
    }

    // Paired: the controller finds a saved pairing, "syncs" with the fake phone, and the store goes read-only.
    listOf(Page.CALENDAR, Page.GYM, Page.DREAMS, Page.LIFE, Page.PHONE).forEach { page ->
        val store = DesktopStore()
        val storage = tempStorage().also { it.savePairing(StoredPairing("192.168.1.23:47321", "ABCDEFGHJKLMNPQRSTUV")) }
        val link = LinkController(store, storage, SamplePhone("Preview phone"), CoroutineScope(Job()), Dispatchers.Unconfined)
        render(File(outDir, "${page.name.lowercase()}_linked.png"), 1360, 860) { A2Theme { A2App(store, link, page) } }
    }

    // The Calendar with emoji on its events and tasks: shaded while still to do, full colour once done (calendar_emoji.png).
    run {
        val store = DesktopStore()
        val storage = tempStorage().also { it.savePairing(StoredPairing("192.168.1.23:47321", "ABCDEFGHJKLMNPQRSTUV")) }
        val link = LinkController(store, storage, SamplePhone("Preview phone"), CoroutineScope(Job()), Dispatchers.Unconfined)
        val today = java.time.LocalDate.now()
        val emojis = CalendarEmojiStore(null).also {
            it.setEmoji("Water plants", "💧")
            it.setEmoji("Change bed sheets", "🛏️")
            it.setEmoji("Dentist", "🦷")
            it.setEmoji("Car service", "🚗")
            it.setEmoji("Haircut", "✂️")
            it.complete("Haircut", null, today.minusDays(1)) // had yesterday: full colour
            it.complete("Car service", null, today.minusDays(2))
        }
        render(File(outDir, "calendar_emoji.png"), 1360, 860) { A2Theme { A2App(store, link, Page.CALENDAR, emojis = emojis) } }
        // Tall enough to see the whole right-hand column, Upcoming card included.
        render(File(outDir, "calendar_emoji_tall.png"), 1360, 1500) { A2Theme { A2App(store, link, Page.CALENDAR, emojis = emojis) } }
    }
    // The Jobs page: with a (fake, invented) mailbox connected and read, and before Gmail is set up (jobs_sample.png, jobs_setup.png).
    run {
        val store = DesktopStore()
        val link = LinkController(store, tempStorage(), SamplePhone("Preview phone"), CoroutineScope(Job()), Dispatchers.Unconfined)
        render(File(outDir, "jobs_sample.png"), 1360, 1500) { A2Theme { A2App(store, link, Page.JOBS, jobs = com.example.a2desktop.data.sampleJobsStore()) } }
        render(File(outDir, "jobs_notconnected.png"), 1360, 900) { A2Theme { A2App(store, link, Page.JOBS, jobs = com.example.a2desktop.data.sampleJobsStore(connected = false)) } }
        // The Connect page: connected to the (fake) mailbox, and before the one-time Gmail setup is done (connect_connected.png, connect_setup.png).
        val connected = com.example.a2desktop.data.sampleJobsStore()
        render(File(outDir, "connect_connected.png"), 1360, 860) { A2Theme { A2App(store, link, Page.JOBS, jobs = connected, initialConnectOpen = true) } }
        val setup = com.example.a2desktop.data.sampleJobsStore(connected = false, clientReady = false)
        render(File(outDir, "connect_setup.png"), 1360, 1100) { A2Theme { A2App(store, link, Page.JOBS, jobs = setup, initialConnectOpen = true) } }
        val ready = com.example.a2desktop.data.sampleJobsStore(connected = false, clientReady = true)
        render(File(outDir, "connect_ready.png"), 1360, 860) { A2Theme { A2App(store, link, Page.JOBS, jobs = ready, initialConnectOpen = true) } }
    }
    println("Rendered previews to ${outDir.absolutePath}")
}

private fun tempStorage() = LinkStorage(Files.createTempDirectory("a2-preview").toFile())

@OptIn(ExperimentalComposeUiApi::class)
private fun render(file: File, width: Int, height: Int, content: @Composable () -> Unit) {
    val scene = ImageComposeScene(width = width, height = height, density = Density(1f), content = content)
    try {
        scene.render(0L) // first pass: compose + layout
        val image = scene.render(100_000_000L) // second pass: effects settled
        val data = image.encodeToData(EncodedImageFormat.PNG) ?: error("PNG encoding failed for ${file.name}")
        file.writeBytes(data.bytes)
    } finally {
        scene.close()
    }
}
