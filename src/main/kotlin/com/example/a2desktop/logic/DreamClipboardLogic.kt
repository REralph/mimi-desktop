package com.example.a2desktop.logic

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

// COPY of the phone project's ui/dreams/DreamClipboardLogic.kt (only the package line differs).
// The Copy / Paste workflow for fixing speech-to-text mistakes: copy the dream, have an AI correct
// it, paste the corrected text back, review it, Save Dream. Everything that decides WHAT to copy and
// WHAT a paste should do lives here as pure functions, so the Android app and the desktop app (which
// holds an identical copy of this file — a desktop test fails if the two drift) behave the same way.
// Nothing in here saves anything: pasting only ever changes the editor's text.

/** The dream editor's two text fields, with cursor/selection. */
data class DreamEditorState(val title: TextFieldValue, val description: TextFieldValue)

enum class DreamField { TITLE, DESCRIPTION }

/** A clipboard text split into the parts that can fill the dream's fields. [title] is null when the
 * text isn't in the recognisable "Title: …" format. */
data class ParsedDream(val title: String?, val description: String)

/** What a Paste press should do — decided purely from the clipboard and the editor's current state. */
sealed interface DreamPastePlan {
    /** Nothing usable on the clipboard. */
    data object Empty : DreamPastePlan

    /** The clipboard text is already what the editor holds. */
    data object Unchanged : DreamPastePlan

    /** Safe to do straight away. [replacedText] is true when existing text was replaced (an explicit
     * selection, say), so the UI can offer Undo. */
    data class Apply(val result: DreamEditorState, val replacedText: Boolean) : DreamPastePlan

    /** A "Title: …" paste that would overwrite existing text in the fields — ask first. */
    data class ConfirmReplace(val result: DreamEditorState) : DreamPastePlan

    /** Plain text pasted into a field that already has text and no selection: replace it all, or
     * insert at the cursor? Ask. */
    data class ChooseMode(val field: DreamField, val replaceAll: DreamEditorState, val insertAtCursor: DreamEditorState) : DreamPastePlan
}

private const val LEAD = """[\s>*_#`~\-]*"""
private const val TRAIL = """[\s*_`]*"""
private val TITLE_LINE = Regex("""^${LEAD}title$TRAIL[:：–—]$TRAIL(.*?)$TRAIL$""", RegexOption.IGNORE_CASE)
private val DESCRIPTION_LABEL = Regex(
    """^${LEAD}(?:dream description|description|dream text|dream|story|text)$TRAIL[:：]$TRAIL(.*?)$TRAIL$""",
    RegexOption.IGNORE_CASE,
)
private val QUOTES = charArrayOf('"', '\'', '“', '”', '‘', '’')

/** The text Copy puts on the clipboard, always labelled so it can be read straight back:
 *
 *     Title: <title>
 *
 *     Description:
 *     <description>
 *
 * Both labels are always there (an empty part just leaves its label bare), so an AI that is asked to
 * correct the text answers in the same shape and Paste knows which part is which. A dream with
 * nothing in it copies as an empty string. The description starts on the line after its label, so
 * nothing in it — however much it looks like a label — can be mistaken for one. */
fun formatDreamForClipboard(title: String, description: String): String {
    val t = title.trim()
    val d = description.trim()
    if (t.isEmpty() && d.isEmpty()) return ""
    return buildString {
        append("Title:")
        if (t.isNotEmpty()) append(' ').append(t)
        append("\n\nDescription:")
        if (d.isNotEmpty()) append('\n').append(d)
    }
}

/**
 * Reads a clipboard text back into title + description. Recognises "Title: …" (also with Markdown
 * emphasis or heading marks, quotes around the title, or a dash/colon separator) as the first line
 * — or after a short AI lead-in line ending in a colon, like "Here is the corrected version:" — and
 * an optional "Description:" label before the body. Anything else is just a description, with
 * [ParsedDream.title] null.
 */
fun parseDreamClipboard(raw: String): ParsedDream {
    val lines = raw.replace("\r\n", "\n").replace('\r', '\n').trim().split('\n')
    val nonBlank = lines.withIndex().filter { it.value.isNotBlank() }
    val titleAt = nonBlank.take(4).indexOfFirst { TITLE_LINE.matches(it.value.trim()) }
    if (titleAt < 0) return ParsedDream(null, raw.trim())
    // Lines before the title only count as an AI lead-in if they are one ("…:" or a rule line).
    val leadIn = nonBlank.take(titleAt)
    if (!leadIn.all { it.value.trim().endsWith(":") || it.value.trim().trim('-', '*', '_', '=').isEmpty() }) {
        return ParsedDream(null, raw.trim())
    }

    val titleLine = nonBlank[titleAt]
    val title = TITLE_LINE.matchEntire(titleLine.value.trim())!!.groupValues[1].trim().trim(*QUOTES).trim()
    var rest = lines.drop(titleLine.index + 1)
    val firstBody = rest.indexOfFirst { it.isNotBlank() }
    if (firstBody >= 0) {
        val label = DESCRIPTION_LABEL.matchEntire(rest[firstBody].trim())
        if (label != null) {
            val inline = label.groupValues[1]
            rest = (if (inline.isNotBlank()) listOf(inline) else emptyList()) + rest.drop(firstBody + 1)
        }
    }
    return ParsedDream(title, rest.joinToString("\n").trim())
}

private fun atEnd(text: String) = TextFieldValue(text, TextRange(text.length))

/**
 * Decides what pressing Paste does, without doing it:
 *  - A recognisable "Title: …" paste fills both fields (a title-only paste leaves the description
 *    alone, and an empty "Title:" leaves the title alone). If that would overwrite existing text, it
 *    asks first.
 *  - Plain text goes into [focused], the field the user was last in (a title never takes line
 *    breaks). An empty field just takes it; a selection is replaced, as in any editor; a field with
 *    text and just a cursor asks whether to replace everything or insert at the cursor — pasting a
 *    corrected dream shouldn't silently double it up, nor silently wipe the original.
 * Nothing here saves anything.
 */
fun planDreamPaste(clipboard: String?, state: DreamEditorState, focused: DreamField): DreamPastePlan {
    if (clipboard.isNullOrBlank()) return DreamPastePlan.Empty
    val parsed = parseDreamClipboard(clipboard)

    if (parsed.title != null) {
        // A label with nothing after it ("Title:" on its own) never blanks what the editor holds.
        val newTitle = parsed.title.ifBlank { state.title.text.trim() }
        val newDescription = parsed.description.ifBlank { state.description.text.trim() }
        val oldTitle = state.title.text
        val oldDescription = state.description.text
        if (newTitle == oldTitle.trim() && newDescription == oldDescription.trim()) return DreamPastePlan.Unchanged
        val result = DreamEditorState(atEnd(newTitle), atEnd(newDescription))
        val wouldLoseText = (oldTitle.isNotBlank() && oldTitle.trim() != newTitle) ||
            (oldDescription.isNotBlank() && oldDescription.trim() != newDescription)
        return if (wouldLoseText) DreamPastePlan.ConfirmReplace(result) else DreamPastePlan.Apply(result, replacedText = false)
    }

    val text = if (focused == DreamField.TITLE) parsed.description.replace(Regex("\\s*\n\\s*"), " ") else parsed.description
    val field = if (focused == DreamField.TITLE) state.title else state.description
    fun withField(value: TextFieldValue) =
        if (focused == DreamField.TITLE) state.copy(title = value) else state.copy(description = value)

    if (field.text.trim() == text.trim()) return DreamPastePlan.Unchanged
    if (field.text.isBlank()) return DreamPastePlan.Apply(withField(atEnd(text)), replacedText = false)

    val start = field.selection.min.coerceIn(0, field.text.length)
    val end = field.selection.max.coerceIn(0, field.text.length)
    val inserted = field.text.substring(0, start) + text + field.text.substring(end)
    val insertedValue = TextFieldValue(inserted, TextRange(start + text.length))
    return if (start != end) {
        DreamPastePlan.Apply(withField(insertedValue), replacedText = true) // normal selection replacement
    } else {
        DreamPastePlan.ChooseMode(focused, replaceAll = withField(atEnd(text)), insertAtCursor = withField(insertedValue))
    }
}
