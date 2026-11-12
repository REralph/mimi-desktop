package com.example.a2desktop.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.a2desktop.data.CalendarEmojiStore
import com.example.a2desktop.logic.DayPick
import com.example.a2desktop.logic.SmartDate
import com.example.a2desktop.logic.smartRepeat
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val pickDateFmt = DateTimeFormatter.ofPattern("EEE d MMM")

/**
 * The drop-down list of which day a repeating task should land on: the plain date, a day off, the day before work, the last work day…
 * (see [DayPick]). The one it has now is ticked ✓. [refBase] numbers the items (`refBase.0` is the plain date, then one per kind) for
 * developer mode.
 */
@Composable
internal fun DayPickMenu(expanded: Boolean, current: DayPick, refBase: String, onPick: (DayPick) -> Unit, onClose: () -> Unit) {
    DropdownMenu(expanded = expanded, onDismissRequest = onClose) {
        Text(
            "Which day?",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
        DayPick.entries.forEach { pick ->
            DropdownMenuItem(
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(pick.label, fontWeight = if (pick == current) FontWeight.Bold else FontWeight.Normal)
                        if (pick == current) Text("  ✓", fontWeight = FontWeight.Bold)
                    }
                },
                onClick = { onPick(pick) },
                modifier = Modifier.devRef("$refBase.${pick.ordinal}", "Day choice (${pick.label})"),
            )
        }
    }
}

/**
 * What the repeat symbol says when the pointer rests on it: how it repeats, which day it lands on if a smart day is set (and, if that
 * moved it off the date the phone has, from when), and — for a repeat a smart day can be set on — that it can be tapped.
 */
internal fun repeatTip(label: String?, pick: DayPick, nominal: LocalDate?, shown: SmartDate?, canPick: Boolean): String {
    val parts = mutableListOf("Repeats · " + label.orEmpty())
    if (pick != DayPick.ANY) {
        parts += pick.label.replaceFirstChar { it.lowercase() }
        if (shown != null && !shown.matched) parts += "no such day known yet, so it stays on its date"
        else if (shown != null && nominal != null && shown.date != nominal) parts += "moved from ${nominal.format(pickDateFmt)}"
    }
    if (canPick) parts += "tap to choose the day"
    return parts.joinToString(" · ")
}

/** Where the first one would land, in a form: "First one: Tue 17 Nov.", or that no such day is known from the date yet so it stays there. */
internal fun firstOneText(snap: SmartDate, nominal: LocalDate): String =
    if (snap.matched) "First one: ${snap.date.format(pickDateFmt)}."
    else "No such day is known from ${nominal.format(pickDateFmt)} yet (your work times aren't out that far), so it waits there and moves to the right day by itself once they are."

/**
 * The repeat symbol of a repeating task or event: with a repeat of a week or more it can be tapped, which opens [DayPickMenu] to choose
 * which day of each period it lands on; a shorter repeat just shows the plain [RepeatMark]. [onList] false is for a row that is only a
 * tick kept here (nothing of that name on the phone's list), which has no repeat of its own to give a day to. The choice is kept on this PC
 * ([CalendarEmojiStore.setDayPick]); the phone keeps its own plain date.
 */
@Composable
internal fun TaskRepeatMark(
    title: String,
    label: String?,
    onList: Boolean,
    nominal: LocalDate?,
    shown: SmartDate?,
    emojis: CalendarEmojiStore,
    ref: String,
    modifier: Modifier = Modifier,
) {
    val canPick = onList && smartRepeat(label) != null
    val pick = emojis.dayPickFor(title)
    val tip = repeatTip(label, if (canPick) pick else DayPick.ANY, nominal, shown, canPick)
    if (!canPick) {
        RepeatMark(tip, modifier.devRef(ref, "Repeats"))
        return
    }
    var choosing by remember { mutableStateOf(false) }
    Box(modifier.devRef(ref, "Repeats (tap to choose the day)")) {
        Box(Modifier.clip(CircleShape).clickable { choosing = true }.padding(3.dp)) { RepeatMark(tip) }
        DayPickMenu(choosing, pick, "$ref", onPick = { emojis.setDayPick(title, it); choosing = false }, onClose = { choosing = false })
    }
}

/**
 * The "Which day?" choice in the repeat part of the Add task and Edit task forms: a button that opens [DayPickMenu], what the chosen
 * kind of day means, and [preview] — where the first one would land. Only shown for a repeat of a week or more.
 */
@Composable
internal fun DayPickField(pick: DayPick, onPick: (DayPick) -> Unit, preview: String?, refBase: String) {
    var open by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Which day?", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box {
            OutlinedButton(onClick = { open = true }, modifier = Modifier.devRef("$refBase.0", "Which day (button)")) { Text(pick.label + "  ▾") }
            DayPickMenu(open, pick, "$refBase.1", onPick = { onPick(it); open = false }, onClose = { open = false })
        }
        if (pick != DayPick.ANY) Text(pick.explain, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        preview?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
