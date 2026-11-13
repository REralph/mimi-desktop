package com.example.a2desktop

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.res.useResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.example.a2desktop.data.DesktopStore
import com.example.a2desktop.data.CalendarEmojiStore
import com.example.a2desktop.data.SkinCareStore
import com.example.a2desktop.data.VitaminStore
import com.example.a2desktop.data.WeatherStore
import com.example.a2desktop.link.CrashLog
import com.example.a2desktop.link.LinkClient
import com.example.a2desktop.link.LinkController
import com.example.a2desktop.link.LinkStorage
import com.example.a2desktop.theme.A2Theme
import com.example.a2desktop.ui.A2App
import com.example.a2desktop.ui.Page
import com.example.a2desktop.ui.ResizeHandles
import com.example.a2desktop.ui.TitleBar
import com.example.a2desktop.ui.dragsWindow
import java.awt.Dimension
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.Toolkit
import javax.swing.JOptionPane
import kotlin.system.exitProcess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** The size the window opens at, every time: not remembered from the last run, not changed by what is on the page. */
internal const val WINDOW_WIDTH_DP = 1360
internal const val WINDOW_HEIGHT_DP = 860

/** [WINDOW_WIDTH_DP] x [WINDOW_HEIGHT_DP], what the window's state starts from on every launch. */
internal fun openingWindowSize() = DpSize(WINDOW_WIDTH_DP.dp, WINDOW_HEIGHT_DP.dp)

fun main(args: Array<String>) {
    installCrashHandler()
    // `--demo`: invented sample data and a pretend phone, with nothing read from or saved to this PC (see DemoMode.kt).
    val demo = isDemo(args)
    application {
        val scope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
        val demoSetup = remember { if (demo) DemoSetup(scope) else null }
        val store = remember { demoSetup?.store ?: DesktopStore() }
        // Loads the paired phone's last data (if any) and, if paired, asks the phone for fresh data.
        val skinCare = remember { if (demo) SkinCareStore() else SkinCareStore.default() }
        val weather = remember { if (demo) WeatherStore() else WeatherStore.default() }
        val vitamins = remember { if (demo) VitaminStore() else VitaminStore.default() }
        val emojis = remember { if (demo) CalendarEmojiStore() else CalendarEmojiStore.default() }
        val jobs = remember { demoSetup?.jobs ?: com.example.a2desktop.data.JobsStore.default() }
        val careSync = remember { if (demo) com.example.a2desktop.data.CareSync() else com.example.a2desktop.data.CareSync.default() }
        val link = remember {
            demoSetup?.link ?: run {
                val client = LinkClient()
                LinkController(
                    store, LinkStorage(), client, scope, writer = client, editor = client,
                    outbox = com.example.a2desktop.link.Outbox.default(),
                    finder = com.example.a2desktop.link.PhoneFinder(),
                    vpnCheck = com.example.a2desktop.link.LocalNetworks::vpnActive,
                )
            }
        }
        val appTitle = if (demo) "Mimi (demo)" else "Mimi"
        val icon = remember { loadWindowIcon() }
        // Until a phone is paired there is nothing to look at, so open where pairing happens.
        val initialPage = remember { if (link.pairing == null) Page.PHONE else Page.CALENDAR }

        val windowState = rememberWindowState(size = openingWindowSize())

        // No native frame: the app draws its own dark bar with red window buttons (see TitleBar.kt),
        // and the thin handles around the edge (WindowResize.kt) do the resizing Windows would have.
        Window(
            onCloseRequest = ::exitApplication,
            title = appTitle, // still what the Windows taskbar shows
            icon = icon,
            state = windowState,
            undecorated = true,
        ) {
            window.minimumSize = Dimension(1060, 700)
            // Maximise is done by hand (remember the bounds, fill the usable area, put the bounds back)
            // rather than through the OS's maximised state, which an undecorated window restores badly.
            var maximised by remember { mutableStateOf(false) }
            var restoreBounds by remember { mutableStateOf<Rectangle?>(null) }
            val toggleMaximise = {
                if (maximised) {
                    restoreBounds?.let { window.bounds = it }
                    maximised = false
                } else {
                    restoreBounds = window.bounds
                    window.bounds = usableArea(window)
                    maximised = true
                }
            }
            A2Theme {
                Box(modifier = Modifier.fillMaxSize().border(1.dp, Color.White.copy(alpha = if (maximised) 0f else 0.14f))) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        TitleBar(
                            title = appTitle,
                            maximised = maximised,
                            onMinimise = { windowState.isMinimized = true },
                            onToggleMaximise = toggleMaximise,
                            onClose = ::exitApplication,
                            icon = icon,
                            // A maximised window stays put; the bar only drags it while it floats.
                            draggable = { content ->
                                Box(if (maximised) Modifier.fillMaxSize() else Modifier.fillMaxSize().dragsWindow(window)) { content() }
                            },
                        )
                        Box(modifier = Modifier.weight(1f).fillMaxWidth()) { A2App(store, link, initialPage, skinCare = skinCare, weather = weather, vitamins = vitamins, emojis = emojis, careSync = careSync, jobs = jobs) }
                    }
                    if (!maximised) ResizeHandles(window)
                }
            }
        }
    }
}

/**
 * The part of the screen the window is on that isn't covered by the Windows taskbar — what a
 * maximised window should fill (a frameless window maximised the usual way would cover the taskbar).
 */
private fun usableArea(window: java.awt.Window): Rectangle {
    val config = window.graphicsConfiguration ?: GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration
    val screen = config.bounds
    val inset = Toolkit.getDefaultToolkit().getScreenInsets(config)
    return Rectangle(
        screen.x + inset.left,
        screen.y + inset.top,
        screen.width - inset.left - inset.right,
        screen.height - inset.top - inset.bottom,
    )
}

/** The app icon from the bundled resources; a missing one just means the default window icon. */
private fun loadWindowIcon(): Painter? =
    runCatching { BitmapPainter(useResource("icon.png", ::loadImageBitmap)) }.getOrNull()

/**
 * Whatever happens, leave a trace. Every uncaught exception is written to the crash log. One on the
 * window's own (UI) thread leaves the window unusable, so that case tells the user what happened and
 * where the details are, then closes cleanly — rather than the app silently vanishing, which is all
 * a packaged app without a console would otherwise do. Background-thread problems (a sync, say) are
 * logged only; the app carries on.
 */
private fun installCrashHandler() {
    Thread.setDefaultUncaughtExceptionHandler { thread, error ->
        CrashLog.record("Uncaught exception on thread '${thread.name}'", error)
        if (thread.name.startsWith("AWT-EventQueue")) {
            runCatching {
                JOptionPane.showMessageDialog(
                    null,
                    "Mimi hit a problem and has to close.\n\nDetails were saved to:\n${CrashLog.file.absolutePath}",
                    "Mimi",
                    JOptionPane.ERROR_MESSAGE,
                )
            }
            exitProcess(1)
        }
    }
}
