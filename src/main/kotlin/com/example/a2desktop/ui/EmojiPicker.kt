package com.example.a2desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.a2desktop.logic.EMOJI_CATALOG
import com.example.a2desktop.logic.EMOJI_CATEGORIES
import com.example.a2desktop.logic.emojisIn
import com.example.a2desktop.logic.searchEmojis

/** The emoji (if any) a person typed or pasted into an emoji box: the newest one, so typing another replaces what was there. */
internal fun typedEmoji(text: String): String? = emojisIn(text).lastOrNull()

/** One emoji to pick, outlined once it is the chosen one; its name shows when the pointer rests on it. */
@Composable
private fun EmojiChoice(emoji: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(9.dp)
    val name = remember(emoji) { EMOJI_CATALOG.firstOrNull { it.emoji == emoji }?.name }
    val box: @Composable () -> Unit = {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(shape)
                .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.06f))
                .border(if (selected) 1.5.dp else 0.dp, if (selected) MaterialTheme.colorScheme.primary else Color.Transparent, shape)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Text(emoji, fontSize = 20.sp, lineHeight = 22.sp)
        }
    }
    if (name != null) Tip(name, box) else box()
}

/**
 * The emoji of an event or task in a form: a small box showing it (type or paste one to change it — Windows key + . opens
 * Windows' own emoji panel), a **＋** button beside it that opens the picker, and the emoji most used so far in a row for one-tap
 * choosing. Tapping the chosen one in that row takes it back. [onChoose] gets the new emoji, or null for none.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EmojiField(chosen: String?, frequent: List<String>, onChoose: (String?) -> Unit, title: String? = null, modifier: Modifier = Modifier) {
    var picking by remember { mutableStateOf(false) }
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = chosen.orEmpty(),
            onValueChange = { text -> if (text.isEmpty()) onChoose(null) else typedEmoji(text)?.let(onChoose) },
            label = { Text("Emoji") },
            singleLine = true,
            modifier = Modifier.width(96.dp),
        )
        OutlinedButton(onClick = { picking = true }, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(width = 40.dp, height = 36.dp)) {
            Text("＋", fontSize = 18.sp)
        }
        frequent.forEach { emoji -> EmojiChoice(emoji, selected = emoji == chosen) { onChoose(if (emoji == chosen) null else emoji) } }
    }
    if (picking) EmojiPickerDialog(title = title, chosen = chosen, frequent = frequent, onChoose = onChoose, onClose = { picking = false })
}

/** A group of emoji under a small heading, in a wrapping grid. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EmojiSection(heading: String, emojis: List<String>, chosen: String?, onPick: (String) -> Unit) {
    Text(heading, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        emojis.forEach { emoji -> EmojiChoice(emoji, selected = emoji == chosen) { onPick(emoji) } }
    }
}

/**
 * The emoji picker: a search box (by name — "hair", "dentist", "car"), the emoji used most first, then every group to browse, and
 * a box to type or paste any other. Picking one calls [onChoose] and closes; "No emoji" takes the current one back. [title] says
 * what it is for, such as the name of the event.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EmojiPickerDialog(title: String?, chosen: String?, frequent: List<String>, onChoose: (String?) -> Unit, onClose: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var typed by remember { mutableStateOf("") }
    val pick: (String) -> Unit = { emoji -> onChoose(emoji); onClose() }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(if (title.isNullOrBlank()) "Choose an emoji" else "Emoji for “$title”") },
        text = {
            Column(modifier = Modifier.width(430.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query, onValueChange = { query = it },
                    label = { Text("Search, e.g. hair, car, dentist") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                // A fixed height, so searching or browsing never makes the window change size.
                Column(modifier = Modifier.fillMaxWidth().height(300.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (query.isBlank()) {
                        if (frequent.isNotEmpty()) EmojiSection("Frequently used", frequent, chosen, pick)
                        EMOJI_CATEGORIES.forEach { (name, entries) -> EmojiSection(name, entries.map { it.emoji }, chosen, pick) }
                    } else {
                        val found = searchEmojis(query)
                        if (found.isEmpty()) {
                            Text("No emoji found — try another word, or type one below.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            EmojiSection("Found", found.map { it.emoji }, chosen, pick)
                        }
                    }
                }
                OutlinedTextField(
                    value = typed,
                    onValueChange = { text ->
                        typed = text
                        // The first emoji typed or pasted is the choice.
                        typedEmoji(text)?.let { pick(it) }
                    },
                    label = { Text("Or type / paste any emoji") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } },
        dismissButton = if (chosen != null) ({ TextButton(onClick = { onChoose(null); onClose() }) { Text("No emoji") } }) else null,
    )
}
