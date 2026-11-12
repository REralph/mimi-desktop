package com.example.a2desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.a2desktop.data.JobAccount
import com.example.a2desktop.data.JobsStore
import com.example.a2desktop.logic.JobRow
import com.example.a2desktop.logic.JobStatus
import com.example.a2desktop.logic.Ticks
import com.example.a2desktop.logic.jobSummary
import com.example.a2desktop.logic.rowIndexAt
import com.example.a2desktop.logic.selectRange
import com.example.a2desktop.logic.ticksOf
import com.example.a2desktop.logic.toggleAll
import com.example.a2desktop.logic.visibleSelection
import com.example.a2desktop.theme.DarkSurfaceVariant
import com.example.a2desktop.theme.GlassCard
import com.example.a2desktop.theme.GlassSurface
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val statusDayFmt = DateTimeFormatter.ofPattern("d MMM yyyy")

/** The colour each status wears. */
internal fun jobStatusColor(status: JobStatus): Color = when (status) {
    JobStatus.NOT_SEEN -> Color(0xFF9AA0A6)
    JobStatus.APPLIED -> Color(0xFF7FB2FF)
    JobStatus.INTERVIEW -> Color(0xFFFFC857)
    JobStatus.OFFER -> Color(0xFF6FCF97)
    JobStatus.ACCEPTED -> Color(0xFF2DD4BF) // teal: clearly not the pale green of an offer
    JobStatus.REJECTED -> Color(0xFFFF6B7A)
}

private fun dayText(millis: Long) = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate().format(statusDayFmt)

/**
 * The Jobs page: one table of every job — each folder in `1. Work\1. Jobs Applying to now` (watched by itself) and each application found only in
 * your email — in the order you applied, the most recent first, so job 1 is the newest and the numbers move as applications come and go. Beside
 * each title is where it stands — what its emails say, or what its folder says, or "No email yet". The **Connect Gmail** button in the header says
 * whether the mail is connected and opens the Connect view ([onOpenConnect]). Lives on this PC only and needs no phone.
 */
@Composable
fun JobsPage(store: JobsStore, onOpenConnect: () -> Unit = {}) {
    var filter by remember { mutableStateOf<JobStatus?>(null) }
    // The ticked jobs (by key). A tick on a job a filter is hiding stays, but nothing the person can't see is ever acted on: see [chosen].
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    val rows = store.rows.sortedBy { it.order } // one list, the job applied for most recently first (the store numbers them in that order)
    val chosen = visibleSelection(selected, rows.filter { filter == null || it.status == filter }.map { it.key })
    Box(modifier = Modifier.fillMaxSize()) {
        ScrollColumn(modifier = Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            PageHeader(title = "Jobs", subtitle = jobSummary(rows), modifier = Modifier.devRef("7.1", "Page header")) {
                ConnectButton(store, onOpenConnect)
                OutlinedButton(onClick = { store.scanAll() }, enabled = store.online && !store.busy && store.accounts.any { it.connected }) { Text("Scan all mail") }
                TextButton(onClick = { store.openFolder(store.jobsFolder) }, enabled = store.jobsFolder != null) { Text("Open jobs folder") }
            }
            StatusLine(store)
            store.folderProblem?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
            FilterRow(rows, filter) { filter = it }

            // One table for every job — each folder in 1. Jobs Applying to now and each application found only in your email — newest application first.
            GlassCard(modifier = Modifier.fillMaxWidth().devRef("7.8", "Jobs in your folder")) {
                CardTitle("Your jobs — the one you applied for most recently first")
                Text(
                    "Every folder in 1. Jobs Applying to now is a job, and so is every application Mimi finds in your email that has no folder. They are in " +
                        "the order you applied (from the first email about it, or when its folder was made), so 1 is the newest — and the numbers move as " +
                        "new applications come in. Tick the boxes beside the numbers — or press on one and drag down (or up) over the others — to change " +
                        "the status of several jobs at once, or to delete them from this list.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp),
                )
                if (rows.isEmpty()) {
                    Text(
                        "No jobs yet — add a folder for each job to 1. Jobs Applying to now, or connect Gmail, and they appear here by themselves.",
                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 8.dp),
                    )
                } else {
                    JobsTable(rows, filter, store, labelHeader = "#", selected = selected, onSelect = { selected = it })
                }
            }

            if (store.hiddenCount > 0) {
                Row { TextButton(onClick = { store.unhideAll() }) { Text("Show ${store.hiddenCount} hidden") } }
            }
            // Room under the last row so the bar below never covers it.
            if (chosen.isNotEmpty()) Spacer(Modifier.height(72.dp))
        }
        if (chosen.isNotEmpty()) {
            SelectionBar(
                count = chosen.size,
                onSet = { status -> store.setStatusOfAll(chosen, status); selected = emptySet() },
                onDelete = { store.deleteAll(chosen); selected = emptySet() },
                onClear = { selected = emptySet() },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 20.dp, start = 24.dp, end = 36.dp),
            )
        }
    }
}

/**
 * The bar that floats at the bottom of the Jobs page (PC7.10) while jobs are ticked: how many, then one press sets all of them to a status, sends
 * them back to what their emails say ([onSet] with null), deletes them, or unticks them. It stays in view however far down the table you are.
 * **Delete** asks first, in the bar itself: it takes the jobs off Mimi's list for good and never touches a folder on disk, and says so.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SelectionBar(count: Int, onSet: (JobStatus?) -> Unit, onDelete: () -> Unit, onClear: () -> Unit, modifier: Modifier) {
    // Asking "are you sure" is forgotten if the selection changes under it, so a confirm never applies to jobs other than the ones it was asked about.
    var confirming by remember(count) { mutableStateOf(false) }
    GlassSurface(modifier = modifier.devRef("7.10", "Selection bar"), alpha = 1f, color = DarkSurfaceVariant, shape = RoundedCornerShape(16.dp)) {
        FlowRow(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            if (confirming) {
                Text(
                    "Delete ${if (count == 1) "this job" else "these $count jobs"} from Mimi's list for good? Your folders are not touched.",
                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                )
                TextButton(onClick = onDelete) { Text("Yes, delete", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = { confirming = false }) { Text("Cancel") }
            } else {
                Text("$count selected", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text("Set to", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                JobStatus.entries.forEach { status ->
                    val color = jobStatusColor(status)
                    Text(
                        status.label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = color,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.18f)).clickable { onSet(status) }.padding(horizontal = 12.dp, vertical = 5.dp),
                    )
                }
                TextButton(onClick = { onSet(null) }) { Text("Use what the emails say") }
                TextButton(onClick = { confirming = true }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onClear) { Text("Clear") }
            }
        }
    }
}

/** One chip per status, with how many jobs have it; tapping one shows only those. */
@Composable
private fun FilterRow(rows: List<JobRow>, filter: JobStatus?, onFilter: (JobStatus?) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().devRef("7.2", "Status filter"), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        FilterChip(selected = filter == null, onClick = { onFilter(null) }, label = { Text("All ${rows.size}") })
        listOf(JobStatus.ACCEPTED, JobStatus.OFFER, JobStatus.INTERVIEW, JobStatus.APPLIED, JobStatus.NOT_SEEN, JobStatus.REJECTED).forEach { status ->
            FilterChip(
                selected = filter == status,
                onClick = { onFilter(if (filter == status) null else status) },
                label = { Text("${status.label} ${rows.count { it.status == status }}", color = if (filter == status) MaterialTheme.colorScheme.onPrimaryContainer else jobStatusColor(status)) },
            )
        }
    }
}

// ---- the mail ----

/** What the header's Connect button says: which mailbox is connected, or that none is. */
internal fun connectButtonText(accounts: List<JobAccount>): String {
    val connected = accounts.filter { it.connected }
    return when {
        connected.isEmpty() -> "Connect Gmail"
        connected.size == 1 -> "Connected · ${connected.single().email}"
        else -> "Connected · ${connected.size} mailboxes"
    }
}

/** The Jobs header's Connect button (PC7.1.1): a green dot and the address while connected, a grey dot and "Connect Gmail" when not; opens the Connect view. */
@Composable
private fun ConnectButton(store: JobsStore, onOpenConnect: () -> Unit) {
    val connected = store.accounts.any { it.connected }
    OutlinedButton(onClick = onOpenConnect, modifier = Modifier.devRef("7.1.1", "Connect button")) {
        Dot(if (connected) MAIL_CONNECTED_GREEN else Color(0xFF9AA0A6), size = 10.dp, modifier = Modifier.padding(end = 8.dp))
        Text(connectButtonText(store.accounts))
    }
}

// ---- the tables ----

@Composable
private fun JobsTable(rows: List<JobRow>, filter: JobStatus?, store: JobsStore, labelHeader: String, selected: Set<String>, onSelect: (Set<String>) -> Unit) {
    val shown = rows.filter { filter == null || it.status == filter }
    val shownKeys = shown.map { it.key }
    // Where each shown job's row starts (relative to the rows' column), kept for the drag below to find the row under the pointer. Not state: it
    // changes with layout, and nothing needs redrawing because of it.
    val tops = remember { mutableMapOf<String, Float>() }
    val anchor = remember { arrayOfNulls<String>(1) }
    val latestSelected by rememberUpdatedState(selected)
    val latestOnSelect by rememberUpdatedState(onSelect)
    Row(modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        // The select-all box: ticks every job shown in this table, or unticks them if they are all ticked already.
        Box(modifier = Modifier.width(CHECK_WIDTH).clickable(enabled = shown.isNotEmpty()) { onSelect(toggleAll(shownKeys, selected)) }.padding(start = 6.dp)) {
            TickBox(ticksOf(shownKeys, selected))
        }
        HeaderCell(labelHeader, Modifier.width(LABEL_WIDTH))
        HeaderCell("Job", Modifier.weight(3f))
        HeaderCell("Status", Modifier.width(150.dp))
        HeaderCell("Latest email", Modifier.width(170.dp))
        HeaderCell("Why", Modifier.weight(3.4f))
    }
    if (shown.isEmpty()) {
        Text("Nothing here with that status.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
    }
    Column(modifier = Modifier.fillMaxWidth().dragTicks(shownKeys, tops, { latestSelected }, { latestOnSelect(it) }, anchor)) {
        shown.forEach { row ->
            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            Box(modifier = Modifier.onGloballyPositioned { tops[row.key] = it.positionInParent().y }) { JobRowView(row, store, ticked = row.key in selected) }
        }
    }
}

/** The width of the first column: the job's number or letter. */
private val LABEL_WIDTH = 56.dp

/** The width of the column of tick boxes, left of the numbers. A press anywhere in it (not just on the little box) ticks. */
private val CHECK_WIDTH = 36.dp

/**
 * Ticking by pointer, for the column of boxes at the left of the rows. A press on a box ticks it (or unticks it if it was ticked); keep the button
 * down and move over other rows and they all take the same new state as the first one, down or up — move back and they go back. Hold Shift and
 * press to tick everything from the last box you pressed to this one, which reaches rows beyond the screen without dragging. Presses anywhere
 * else in the row are left alone, so the status pill and the rest work as before; the mouse wheel still scrolls mid-drag.
 */
private fun Modifier.dragTicks(
    keys: List<String>,
    tops: Map<String, Float>,
    selected: () -> Set<String>,
    onSelect: (Set<String>) -> Unit,
    anchor: Array<String?>,
): Modifier = pointerInput(keys) {
    val strip = CHECK_WIDTH.toPx()
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        if (keys.isEmpty() || down.position.x >= strip) return@awaitEachGesture
        if (down.type == PointerType.Mouse && !currentEvent.buttons.isPrimaryPressed) return@awaitEachGesture
        val rowAt = { y: Float -> rowIndexAt(keys.map { tops[it] }, y) }
        val first = rowAt(down.position.y)
        val base = selected()
        val fromAnchor = if (currentEvent.keyboardModifiers.isShiftPressed) anchor[0]?.let { keys.indexOf(it) }?.takeIf { it >= 0 } else null
        val start = fromAnchor ?: first
        val tick = if (fromAnchor != null) true else keys[first] !in base
        if (fromAnchor == null) anchor[0] = keys[first]
        var latest = selectRange(base, keys, start, first, tick)
        onSelect(latest)
        down.consume()
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) { change.consume(); break }
            val now = selectRange(base, keys, start, rowAt(change.position.y), tick)
            if (now != latest) { latest = now; onSelect(now) }
            if (event.type != PointerEventType.Scroll) change.consume() // a wheel turn must still reach the page's scrolling
        }
    }
}

/** The little square: empty, filled with a tick, or filled with a dash (some of a table's jobs ticked). Drawn, so it needs no icon font. */
@Composable
private fun TickBox(state: Ticks, modifier: Modifier = Modifier) {
    val fill = MaterialTheme.colorScheme.primary
    val outline = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
    val mark = MaterialTheme.colorScheme.onPrimary
    Canvas(modifier = modifier.size(TICK_SIZE)) {
        val corner = CornerRadius(4.dp.toPx())
        val w = size.width
        val h = size.height
        if (state == Ticks.NONE) {
            val inset = 0.75.dp.toPx()
            drawRoundRect(color = outline, topLeft = Offset(inset, inset), size = Size(w - 2 * inset, h - 2 * inset), cornerRadius = corner, style = Stroke(width = 1.5.dp.toPx()))
        } else {
            drawRoundRect(color = fill, cornerRadius = corner)
            val line = 2.dp.toPx()
            if (state == Ticks.ALL) {
                drawLine(mark, Offset(w * 0.25f, h * 0.52f), Offset(w * 0.43f, h * 0.70f), strokeWidth = line, cap = StrokeCap.Round)
                drawLine(mark, Offset(w * 0.43f, h * 0.70f), Offset(w * 0.76f, h * 0.31f), strokeWidth = line, cap = StrokeCap.Round)
            } else {
                drawLine(mark, Offset(w * 0.28f, h * 0.5f), Offset(w * 0.72f, h * 0.5f), strokeWidth = line, cap = StrokeCap.Round)
            }
        }
    }
}

private val TICK_SIZE = 18.dp

@Composable
private fun HeaderCell(text: String, modifier: Modifier) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
}

@Composable
private fun JobRowView(row: JobRow, store: JobsStore, ticked: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(if (ticked) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f) else Color.Transparent).padding(vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(modifier = Modifier.width(CHECK_WIDTH).padding(start = 6.dp, top = 3.dp)) { TickBox(if (ticked) Ticks.ALL else Ticks.NONE) }
        Column(modifier = Modifier.width(LABEL_WIDTH)) {
            Text(row.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            if (row.isNew) Text("NEW", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.tertiary)
        }
        Column(modifier = Modifier.weight(3f).padding(end = 12.dp)) {
            Text(row.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val base = row.folder?.folderStatus?.let { "In the ${it.folderName} folder" } ?: row.account?.takeIf { row.folder == null }?.let { "From the email of $it" }
            val also = row.alsoFolders.takeIf { it.isNotEmpty() }?.let { "Same job: also the folder ${it.joinToString(" and ") { f -> "“${f.title}”" }}" }
            val sub = listOfNotNull(base, also).joinToString(" · ").ifEmpty { null }
            if (sub != null) Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box(modifier = Modifier.width(150.dp)) { StatusChip(row, store) }
        Column(modifier = Modifier.width(170.dp).padding(end = 12.dp)) {
            val last = row.lastEvent
            if (last == null) Text("—", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            else {
                Text(last.kind.label, style = MaterialTheme.typography.bodySmall)
                Text(dayText(last.dateMillis), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Column(modifier = Modifier.weight(3.4f)) {
            row.lastEvent?.let {
                Text("“${it.evidence}”", style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            if (row.overridden) {
                Text(
                    "Set by you" + (row.auto?.let { " — the emails say ${it.label.lowercase()}" } ?: ""),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            row.note?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary) }
        }
    }
}

/** The status as a coloured pill; tapping it offers to set the status by hand, go back to what the emails say, or hide the job. */
@Composable
private fun StatusChip(row: JobRow, store: JobsStore) {
    var open by remember { mutableStateOf(false) }
    val color = jobStatusColor(row.status)
    Box {
        Row(
            modifier = Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.18f)).clickable { open = true }.padding(horizontal = 12.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(row.status.label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = color)
            Text(if (row.overridden) " ✎" else " ▾", style = MaterialTheme.typography.labelMedium, color = color)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            JobStatus.entries.forEach { status ->
                DropdownMenuItem(text = { Text("Set to ${status.label}", color = jobStatusColor(status)) }, onClick = { open = false; store.setStatus(row.key, status) })
            }
            if (row.overridden) DropdownMenuItem(text = { Text("Use what the emails say") }, onClick = { open = false; store.setStatus(row.key, null) })
            DropdownMenuItem(text = { Text("Hide this job") }, onClick = { open = false; store.hide(row.key) })
        }
    }
}
