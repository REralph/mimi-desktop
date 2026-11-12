package com.example.a2desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.a2desktop.data.DataSource
import com.example.a2desktop.data.DesktopStore
import com.example.a2desktop.data.CalendarEmojiStore
import com.example.a2desktop.data.CareSender
import com.example.a2desktop.data.CareSync
import com.example.a2desktop.data.JobsStore
import com.example.a2desktop.data.SkinCareStore
import com.example.a2desktop.data.VitaminStore
import com.example.a2desktop.data.WeatherStore
import com.example.a2desktop.link.EditResult
import com.example.a2desktop.link.LinkController
import com.example.a2desktop.logic.CareState
import com.example.a2desktop.logic.ClipboardAccess
import com.example.a2desktop.logic.SystemClipboard
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay

enum class Page(val label: String, val glyph: String) {
    CALENDAR("Calendar", "📅"),
    GYM("Gym", "🏋️"),
    DREAMS("Dreams", "🌙"),
    SKIN_CARE("Skin care", "🧴"),
    LIFE("Life", "⏳"),
    JOBS("Jobs", "💼"),
    PHONE("Phone", "📱"),
}

private val clockFmt = DateTimeFormatter.ofPattern("HH:mm")
private val dayClockFmt = DateTimeFormatter.ofPattern("d MMM HH:mm")

/** "14:32" today, "13 Oct 14:32" otherwise. */
fun clockText(millis: Long, today: java.time.LocalDate = java.time.LocalDate.now()): String {
    val time = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
    return time.format(if (time.toLocalDate() == today) clockFmt else dayClockFmt)
}

/** The whole desktop app: a navigation bar along the top and the current page under it. */
@Composable
fun A2App(
    store: DesktopStore,
    link: LinkController,
    initialPage: Page = Page.CALENDAR,
    clipboard: ClipboardAccess = SystemClipboard,
    // What has been ticked off in the skin-care routine. Kept in memory only unless the app passes the real one.
    skinCare: SkinCareStore = remember { SkinCareStore() },
    // The UV forecast for the skin-care plan. With no service (the default here) it never touches the internet.
    weather: WeatherStore = remember { WeatherStore() },
    // What the user chose on the Vitamins card (zinc every day or not, magnesium tablets). Memory-only unless the app passes the real one.
    vitamins: VitaminStore = remember { VitaminStore() },
    // The emoji on the Calendar's events and tasks, and what is ticked off as done. Memory-only unless the app passes the real one.
    emojis: CalendarEmojiStore = remember { CalendarEmojiStore() },
    // Developer mode (the switch above Phone): numbered labels on every group of information. Off at start.
    dev: DevMode = remember { DevMode() },
    // Which phone this PC's own earlier skin-care and supplements record has been joined to. Memory-only unless the app passes the real one.
    careSync: CareSync = remember { CareSync() },
    // The jobs being applied for and what the emails say about them. Memory-only, with no Gmail, unless the app passes the real one.
    jobs: JobsStore = remember { JobsStore() },
    // Whether the Jobs page opens on its Connect Gmail view (reached from the button in the Jobs header; used by previews and tests).
    initialConnectOpen: Boolean = false,
) {
    var page by remember { mutableStateOf(initialPage) }
    // The Connect Gmail view belongs to Jobs: it is opened from the Jobs page header, and any tap in the top bar goes back to the Jobs table.
    var connectOpen by remember { mutableStateOf(initialConnectOpen) }

    // The skin-care and supplements record lives on the PHONE (so both apps show the same thing): while the phone sends it, the two
    // stores show it and send their changes there. The first time this PC meets a phone that keeps it, this PC's own earlier record is
    // folded into the phone's — once, after a copy of the files is kept — so nothing already recorded is lost. A phone app that doesn't
    // keep the record yet leaves the stores as they were, keeping their own on this PC.
    var joining by remember { mutableStateOf(false) }
    var joinedCount by remember { mutableStateOf(0) } // counts the joinings that went through, so the effect runs again even when the phone's record came back unchanged
    LaunchedEffect(store.care, store.deviceName, joining, joinedCount) {
        val record = store.care
        val device = store.deviceName
        if (record == null || device == null) {
            skinCare.sender = null
            vitamins.sender = null
        } else if (!careSync.isMerged(device)) {
            if (!joining) {
                joining = true
                skinCare.keepCopyBeforePhone()
                vitamins.keepCopyBeforePhone()
                link.sendCare(emptyList(), CareState(skinCare.state, vitamins.state).toWire()) { result ->
                    if (result is EditResult.Done) {
                        careSync.markMerged(device)
                        joinedCount++
                    }
                    joining = false
                }
            }
        } else {
            val sender = CareSender { commands, onDone -> link.sendCare(commands, null, onDone) }
            skinCare.sender = sender
            vitamins.sender = sender
            skinCare.applyRemote(record.skin)
            vitamins.applyRemote(record.vitamins)
        }
    }

    // The skin-care routine counts from the first day it is looked at, and confirming (tonight's routine, the zinc
    // dose) begins that day too — an evening or dose that isn't confirmed drops off the next day — so remember it.
    LaunchedEffect(store.today) {
        skinCare.ensureStarted(store.today)
        vitamins.ensureStarted(store.today)
    }

    // The forecast, refreshed quietly: once as the app opens (only if the one held is older than a few hours)
    // and every half hour after. Does nothing until the user has chosen a town on the Skin care page.
    LaunchedEffect(weather) {
        while (true) {
            weather.refresh()
            delay(30 * 60_000L)
        }
    }

    // The jobs folder is looked at again every few seconds, whatever page is showing, so a job folder you add to it turns up on the Jobs
    // page (numbered) by itself. Nothing is redrawn when nothing changed.
    LaunchedEffect(jobs) { jobs.watchFolders() }

    // Keep "today" current and the phone's data fresh without anyone pressing anything: every 15 s
    // while the phone answers, and every 8 s while it doesn't (the phone keeps Desktop link on in the
    // background, but may be asleep, off the Wi-Fi or still starting up) so the connection comes
    // back by itself the moment the phone can be reached — and whatever was changed here meanwhile
    // goes across with it. The first sync happens as the app opens.
    LaunchedEffect(Unit) {
        while (true) {
            delay(if (link.reachable) 15_000 else 8_000)
            store.refreshToday()
            if (link.pairing != null) link.refreshQuietly()
        }
    }

    // A Surface (not just a background) so every Text that doesn't set its own colour inherits the
    // light "on background" colour instead of Compose's default black.
    CompositionLocalProvider(LocalDevMode provides dev) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onBackground,
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                Column(modifier = Modifier.fillMaxSize()) {
                    NavBar(current = page, linkReachable = link.reachable, dev = dev, clipboard = clipboard, onSelect = { connectOpen = false; page = it })
                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        when {
                            page == Page.PHONE -> PhonePage(store, link)
                            // Skin care lives on this PC: it needs nothing from the phone (work times, if any, just help).
                            page == Page.SKIN_CARE -> SkinCarePage(skinCare, weather, store.workSchedule, store.today)
                            // Jobs lives on this PC too: its folders and Gmail need nothing from the phone.
                            page == Page.JOBS ->
                                if (connectOpen) ConnectPage(jobs, onBack = { connectOpen = false })
                                else JobsPage(jobs, onOpenConnect = { connectOpen = true })
                            // Nothing has come from the phone yet: say so, rather than show anything made up.
                            store.source == DataSource.NONE -> NoDataPage(page, link, onOpenPhonePage = { page = Page.PHONE })
                            page == Page.CALENDAR -> CalendarPage(store, link, skinCare, weather, vitamins, emojis)
                            page == Page.GYM -> GymPage(store)
                            page == Page.DREAMS -> DreamsPage(store, clipboard, link)
                            page == Page.LIFE -> LifePage(store)
                        }
                    }
                }
                // The numbered labels: a separate layer above everything, so they can't move what is underneath.
                if (dev.enabled) DevOverlay(dev, clipboard)
            }
        }
    }
}

/** The bar along the top of every page: the name at the left, the pages after it, and at the right the Dev mode switch and Phone. */
@Composable
private fun NavBar(current: Page, linkReachable: Boolean, dev: DevMode, clipboard: ClipboardAccess, onSelect: (Page) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(NAV_BAR_DP.dp)
            .devRef("0.1", "Navigation bar")
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f))
            .border(1.dp, Color.White.copy(alpha = 0.06f))
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.padding(end = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Mimi", fontSize = 24.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Text("desktop", fontSize = 10.sp, lineHeight = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Page.entries.filter { it != Page.PHONE }.forEach { NavItem(it, it == current) { onSelect(it) } }
        }
        DevToggle(dev, clipboard)
        Spacer(Modifier.width(12.dp))
        NavItem(Page.PHONE, current == Page.PHONE, badge = if (linkReachable) CONNECTED_GREEN else null) { onSelect(Page.PHONE) }
    }
}

/** The height of the navigation bar along the top. */
internal const val NAV_BAR_DP = 64

private val CONNECTED_GREEN = Color(0xFF6FCF97)

@Composable
private fun NavItem(page: Page, selected: Boolean, badge: Color? = null, onClick: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Box(contentAlignment = Alignment.TopEnd) {
        Column(
            modifier = Modifier
                .width(72.dp)
                .clip(shape)
                .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                .clickable(onClick = onClick)
                .padding(vertical = 5.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(page.glyph, fontSize = 20.sp, lineHeight = 24.sp)
            Text(
                page.label,
                fontSize = 11.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (badge != null) Dot(badge, size = 9.dp, modifier = Modifier.padding(top = 6.dp, end = 10.dp))
    }
}
