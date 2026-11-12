package com.example.a2desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.a2desktop.data.DesktopStore
import com.example.a2desktop.link.DreamSaveResult
import com.example.a2desktop.link.LinkController
import com.example.a2desktop.link.LinkDreamEdit
import com.example.a2desktop.logic.ClipboardAccess
import com.example.a2desktop.logic.ClipboardRead
import com.example.a2desktop.logic.DreamEditorState
import com.example.a2desktop.logic.DreamField
import com.example.a2desktop.logic.DreamPastePlan
import com.example.a2desktop.logic.SystemClipboard
import com.example.a2desktop.logic.formatDreamForClipboard
import com.example.a2desktop.logic.planDreamPaste
import com.example.a2desktop.model.Dream
import com.example.a2desktop.model.DreamStatus
import com.example.a2desktop.theme.GlassCard
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay

private val dreamDateFmt = DateTimeFormatter.ofPattern("EEE d MMM yyyy")

// The phone's Dreams list shows only SAVED dreams (a draft lives inside Record Dream), so Saved is
// the default here too; drafts are one click away.
private enum class DreamFilter(val label: String) { SAVED("Saved"), DRAFTS("Drafts") }

/**
 * Dreams — the phone's Dreams Home + Record Dream (2.1/2.2/2.3) side by side: the list with search
 * and a Saved/Draft filter on the left, and the selected dream open for reading and editing on the
 * right, so recording or revising a dream never needs a separate screen.
 */
@Composable
fun DreamsPage(store: DesktopStore, clipboard: ClipboardAccess = SystemClipboard, link: LinkController? = null) {
    var filter by remember { mutableStateOf(DreamFilter.SAVED) }
    var query by remember { mutableStateOf("") }
    var selectedId by remember { mutableStateOf(store.dreams.firstOrNull()?.id) }

    val visible = store.dreams
        .filter {
            when (filter) {
                DreamFilter.SAVED -> it.status == DreamStatus.SAVED
                DreamFilter.DRAFTS -> it.status == DreamStatus.DRAFT
            }
        }
        .filter { query.isBlank() || it.title.contains(query, ignoreCase = true) || it.text.contains(query, ignoreCase = true) }
        .sortedByDescending { it.date }

    Column(modifier = Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        PageHeader("Dreams", "${store.dreams.count { it.status == DreamStatus.SAVED }} saved", modifier = Modifier.devRef("3.1", "Page header")) {
            if (!store.readOnly) {
                Button(onClick = { store.newDream()?.let { selectedId = it.id } }) { Text("New dream") }
            }
        }
        Row(modifier = Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Column(modifier = Modifier.width(400.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Search dreams") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().devRef("3.2", "Search box"),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.devRef("3.3", "Saved / Drafts filter")) {
                    DreamFilter.entries.forEach { f ->
                        FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(f.label) })
                    }
                }
                ScrollColumn(modifier = Modifier.weight(1f).fillMaxWidth().devRef("3.4", "Dream list"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    visible.forEach { dream -> DreamRow(dream, dream.id == selectedId) { selectedId = dream.id } }
                    if (visible.isEmpty()) Text("No dreams match.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            val selected = store.dreams.firstOrNull { it.id == selectedId }
            GlassCard(modifier = Modifier.weight(1f).fillMaxHeight().devRef("3.5", "Dream editor"), contentPadding = 20.dp) {
                if (selected == null) {
                    Text("Select a dream, or start a new one.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    DreamEditor(store, selected, clipboard, link)
                }
            }
        }
    }
}

@Composable
private fun DreamRow(dream: Dream, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) colors.primaryContainer.copy(alpha = 0.35f) else colors.surface.copy(alpha = 0.85f), shape)
            .border(if (selected) 1.5.dp else 1.dp, if (selected) colors.primary else Color.White.copy(alpha = 0.10f), shape)
            .clickable(onClick = onClick)
            .padding(16.dp),
    ) {
        Row {
            Text(
                dream.title.ifBlank { "Untitled dream" },
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (dream.status == DreamStatus.DRAFT) {
                Text("DRAFT", style = MaterialTheme.typography.labelSmall, color = colors.tertiary, fontWeight = FontWeight.Bold)
            }
        }
        Text(dream.date.format(dreamDateFmt), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
        Text(
            dream.text,
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/** A short message under the editor's buttons, optionally with an Undo. */
private class Notice(val text: String, val lingerMillis: Long = 7_000, val undo: (() -> Unit)? = null)

/**
 * The selected dream, open for reading and editing. Copy / Paste support fixing speech-to-text
 * mistakes with an outside AI: Copy puts labelled "Title: …" and "Description: …" text on the clipboard, Paste brings
 * the corrected text back into the editor for review (what exactly each does is in
 * DreamClipboardLogic.kt, shared with the phone app). Neither saves anything — only Save dream (or
 * Save as draft, on demo data) does — and a Paste that replaces text can be undone.
 *
 * With the phone's data, Save dream SENDS the dream to the phone ([LinkController.saveDream]): the
 * phone checks it, does the saving and answers with its own saved copy, which is what then shows
 * here — the phone stays the source of truth. If the phone can't be reached, or says no because the
 * dream changed there, nothing is lost: the text stays in the editor and a message says why.
 */
@Composable
private fun DreamEditor(store: DesktopStore, dream: Dream, clipboard: ClipboardAccess, link: LinkController?) {
    // Re-keyed on the dream's id so switching dreams loads that dream's text into the fields.
    var titleField by remember(dream.id) { mutableStateOf(TextFieldValue(dream.title, TextRange(dream.title.length))) }
    var descriptionField by remember(dream.id) { mutableStateOf(TextFieldValue(dream.text, TextRange(dream.text.length))) }
    var focusedField by remember(dream.id) { mutableStateOf(DreamField.DESCRIPTION) }
    var pendingPaste by remember(dream.id) { mutableStateOf<DreamPastePlan?>(null) }
    var notice by remember(dream.id) { mutableStateOf<Notice?>(null) }

    LaunchedEffect(notice) {
        if (notice != null) {
            val shown = notice
            delay(shown?.lingerMillis ?: 7_000)
            notice = null
        }
    }

    fun save(status: DreamStatus) {
        val title = titleField.text.trim()
        val text = descriptionField.text.trim()
        if (!store.readOnly) {
            store.saveDream(dream.copy(title = title, text = text, status = status))
            notice = Notice("Saved.")
            return
        }
        // The phone's data: the phone does the saving. What it last told us about this dream goes
        // along, so it can tell if the dream has changed on the phone since.
        if (link == null) {
            notice = Notice("Saving to the phone isn't available here.")
            return
        }
        notice = Notice("Saving to your phone…", lingerMillis = 30_000)
        link.saveDream(LinkDreamEdit(dream.id, dream.title, dream.text, title, text)) { result ->
            notice = when (result) {
                is DreamSaveResult.Saved -> Notice("Saved on your phone.")
                is DreamSaveResult.Failed -> Notice(result.message, lingerMillis = 20_000) // long, and worth the time to read
            }
        }
    }

    fun currentEditor() = DreamEditorState(titleField, descriptionField)

    fun showEditor(state: DreamEditorState) {
        titleField = state.title
        descriptionField = state.description
    }

    /** Puts [state] in the editor; if it replaced text, offers Undo so nothing is lost by accident. */
    fun applyPaste(state: DreamEditorState, replacedText: Boolean) {
        val before = currentEditor()
        showEditor(state)
        notice = if (replacedText) {
            Notice("Pasted — your previous text was replaced.") { showEditor(before); notice = null }
        } else {
            Notice("Pasted — review it, then press Save dream.")
        }
    }

    fun copyDream() {
        val text = formatDreamForClipboard(titleField.text, descriptionField.text)
        notice = Notice(
            when {
                text.isEmpty() -> "Nothing to copy yet."
                clipboard.write(text) -> "Dream copied to the clipboard."
                else -> "Couldn't copy to the clipboard."
            },
        )
    }

    fun pasteDream() {
        val clip = when (val read = clipboard.read()) {
            is ClipboardRead.Text -> read.value
            ClipboardRead.Empty -> null
            ClipboardRead.Unsupported -> {
                notice = Notice("The clipboard doesn't contain text.")
                return
            }
        }
        when (val plan = planDreamPaste(clip, currentEditor(), focusedField)) {
            DreamPastePlan.Empty -> notice = Notice("The clipboard is empty — copy the corrected dream first.")
            DreamPastePlan.Unchanged -> notice = Notice("That text is already in the editor.")
            is DreamPastePlan.Apply -> applyPaste(plan.result, plan.replacedText)
            is DreamPastePlan.ConfirmReplace, is DreamPastePlan.ChooseMode -> pendingPaste = plan
        }
    }

    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                dream.date.format(dreamDateFmt),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(
                if (dream.status == DreamStatus.DRAFT) "Draft" else "Saved",
                style = MaterialTheme.typography.labelLarge,
                color = if (dream.status == DreamStatus.DRAFT) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
            )
        }
        OutlinedTextField(
            value = titleField,
            onValueChange = { titleField = it },
            label = { Text("Title") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) focusedField = DreamField.TITLE },
        )
        OutlinedTextField(
            value = descriptionField,
            onValueChange = { descriptionField = it },
            label = { Text("What happened in the dream?") },
            modifier = Modifier.fillMaxWidth().weight(1f).onFocusChanged { if (it.isFocused) focusedField = DreamField.DESCRIPTION },
        )
        if (store.readOnly) {
            Text(
                "Save dream sends this to your phone, which keeps it. The phone must be on your Wi-Fi with Desktop link on.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.devRef("3.6", "Copy / Paste / Save buttons")) {
            OutlinedButton(onClick = { copyDream() }) { Text("Copy") }
            OutlinedButton(onClick = { pasteDream() }) { Text("Paste") }
            val saving = link?.savingDream == true
            Button(onClick = { save(DreamStatus.SAVED) }, enabled = descriptionField.text.isNotBlank() && !saving) {
                Text(if (saving) "Saving…" else "Save dream")
            }
            if (!store.readOnly) { // demo data only — the phone keeps a single autosaved draft of its own
                OutlinedButton(onClick = { save(DreamStatus.DRAFT) }) { Text("Save as draft") }
            }
            notice?.let { shown ->
                Text(shown.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f, fill = false).padding(start = 6.dp))
                shown.undo?.let { undo -> TextButton(onClick = undo) { Text("Undo") } }
            }
        }
    }

    when (val plan = pendingPaste) {
        is DreamPastePlan.ConfirmReplace -> AlertDialog(
            onDismissRequest = { pendingPaste = null },
            title = { Text("Replace this dream's text?") },
            text = { Text("The clipboard has a title and description. Pasting will replace what's in the editor now. You can undo it straight afterwards, and nothing is saved until you press Save dream.") },
            confirmButton = {
                TextButton(onClick = {
                    pendingPaste = null
                    applyPaste(plan.result, replacedText = true)
                }) { Text("Replace") }
            },
            dismissButton = { TextButton(onClick = { pendingPaste = null }) { Text("Cancel") } },
        )
        is DreamPastePlan.ChooseMode -> AlertDialog(
            onDismissRequest = { pendingPaste = null },
            title = { Text("Paste into the ${if (plan.field == DreamField.TITLE) "title" else "description"}") },
            text = { Text("It already has text. Replace all of it with the clipboard text, or insert the clipboard text at the cursor? Nothing is saved until you press Save dream.") },
            confirmButton = {
                TextButton(onClick = {
                    pendingPaste = null
                    applyPaste(plan.replaceAll, replacedText = true)
                }) { Text("Replace all") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { pendingPaste = null }) { Text("Cancel") }
                    TextButton(onClick = {
                        pendingPaste = null
                        applyPaste(plan.insertAtCursor, replacedText = false)
                    }) { Text("Insert at cursor") }
                }
            },
        )
        else -> Unit
    }
}
