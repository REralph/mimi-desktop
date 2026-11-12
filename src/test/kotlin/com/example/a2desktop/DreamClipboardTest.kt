package com.example.a2desktop

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.example.a2desktop.logic.DreamEditorState
import com.example.a2desktop.logic.DreamField
import com.example.a2desktop.logic.DreamPastePlan
import com.example.a2desktop.logic.formatDreamForClipboard
import com.example.a2desktop.logic.parseDreamClipboard
import com.example.a2desktop.logic.planDreamPaste
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DreamClipboardTest {
    private fun state(
        title: String = "",
        description: String = "",
        titleSel: TextRange = TextRange(title.length),
        descSel: TextRange = TextRange(description.length),
    ) = DreamEditorState(TextFieldValue(title, titleSel), TextFieldValue(description, descSel))

    // ---- Copy format and reading it back ----

    @Test fun copyIsLabelledTitleAndDescription() {
        assertEquals(
            "Title: Flying\n\nDescription:\nI was over a lake.",
            formatDreamForClipboard("  Flying ", " I was over a lake.\n"),
        )
    }

    @Test fun copyAlwaysCarriesBothLabelsEvenWhenOnePartIsEmpty() {
        assertEquals("Title:\n\nDescription:\nI was over a lake.", formatDreamForClipboard("", "I was over a lake."))
        assertEquals("Title: Flying\n\nDescription:", formatDreamForClipboard("Flying", "  "))
        assertEquals("", formatDreamForClipboard(" ", " "))
    }

    @Test fun anEmptyTitleLabelNeverBlanksTheTitleAlreadyInTheEditor() {
        val apply = planDreamPaste("Title:\n\nDescription:\nA corrected body.", state("Kept title", "A body with mistaks"), DreamField.DESCRIPTION)
        val confirm = apply as DreamPastePlan.ConfirmReplace
        assertEquals("Kept title", confirm.result.title.text)
        assertEquals("A corrected body.", confirm.result.description.text)
    }

    @Test fun copyThenPasteReturnsExactlyWhatWasCopied() {
        val cases = listOf(
            "Flying" to "I was over a lake.",
            "Flying" to "Paragraph one.\n\nParagraph two, with a colon: like this.\nAnd a third line.",
            "" to "Just a description.",
            "Only a title" to "",
            // A description that itself looks like a label must survive the round trip untouched.
            "Odd" to "Title: this is how the dream began",
            "Odd" to "Description: the first words of the dream",
            "" to "Title: the dream began like this",
        )
        for ((t, d) in cases) {
            val parsed = parseDreamClipboard(formatDreamForClipboard(t, d))
            assertEquals(t, parsed.title, "title of ($t | $d)")
            assertEquals(d, parsed.description, "description of ($t | $d)")
        }
    }

    @Test fun anAiReplyInCommonFormatsIsRecognised() {
        val expected = "Flying over a lake"
        listOf(
            "Title: Flying over a lake\n\nI glided low.",
            "**Title:** Flying over a lake\n\n**Description:** I glided low.",
            "# Title: Flying over a lake\r\n\r\nDescription:\r\nI glided low.\r\n",
            "Title: \"Flying over a lake\"\n\nI glided low.",
            "Title – Flying over a lake\n\nI glided low.",
            "TITLE: Flying over a lake\nI glided low.",
            "Here is the corrected version:\n\nTitle: Flying over a lake\n\nI glided low.",
        ).forEach { reply ->
            val parsed = parseDreamClipboard(reply)
            assertEquals(expected, parsed.title, reply)
            assertEquals("I glided low.", parsed.description, reply)
        }
    }

    @Test fun plainTextAndLookalikesAreNotMistakenForATitle() {
        listOf(
            "I glided low over the lake.",
            "Titles were scrolling past in the dream, then I woke up.",
            "Title-less dream: I just remember water.",
            "Sure thing, happy to help!\nTitle: Flying\n\nBody", // chatter that isn't a lead-in ending in a colon
        ).forEach { assertNull(parseDreamClipboard(it).title, it) }
        assertEquals("I glided low over the lake.", parseDreamClipboard("  I glided low over the lake.\n").description)
    }

    // ---- What Paste does ----

    @Test fun anEmptyOrBlankClipboardDoesNothing() {
        assertEquals(DreamPastePlan.Empty, planDreamPaste(null, state(), DreamField.DESCRIPTION))
        assertEquals(DreamPastePlan.Empty, planDreamPaste("  \n ", state("x", "y"), DreamField.DESCRIPTION))
    }

    @Test fun aTitledPasteFillsBothFieldsWhenTheEditorIsEmpty() {
        val apply = planDreamPaste("Title: Flying\n\nI glided.", state(), DreamField.DESCRIPTION) as DreamPastePlan.Apply
        assertEquals("Flying", apply.result.title.text)
        assertEquals("I glided.", apply.result.description.text)
        assertEquals(false, apply.replacedText)
    }

    @Test fun aTitledPasteOverExistingTextAsksFirstAndNeverDestroysQuietly() {
        val plan = planDreamPaste("Title: Flying\n\nI glided.", state("Old", "Old text with mistaks"), DreamField.DESCRIPTION)
        val confirm = plan as DreamPastePlan.ConfirmReplace
        assertEquals("Flying", confirm.result.title.text)
        assertEquals("I glided.", confirm.result.description.text)
    }

    @Test fun aTitleOnlyPasteKeepsTheDescription() {
        val apply = planDreamPaste("Title: Better title", state("", "My description"), DreamField.DESCRIPTION) as DreamPastePlan.Apply
        assertEquals("Better title", apply.result.title.text)
        assertEquals("My description", apply.result.description.text)
    }

    @Test fun pastingTheSameTextAgainChangesNothing() {
        assertEquals(DreamPastePlan.Unchanged, planDreamPaste("Title: A\n\nB", state("A", "B"), DreamField.DESCRIPTION))
        assertEquals(DreamPastePlan.Unchanged, planDreamPaste("B", state("A", "B"), DreamField.DESCRIPTION))
    }

    @Test fun plainTextGoesStraightIntoAnEmptyField() {
        val apply = planDreamPaste("Corrected dream.", state(), DreamField.DESCRIPTION) as DreamPastePlan.Apply
        assertEquals("Corrected dream.", apply.result.description.text)
        assertEquals("", apply.result.title.text)
        assertEquals(TextRange("Corrected dream.".length), apply.result.description.selection)
    }

    @Test fun plainTextReplacesASelectionLikeAnyEditor() {
        val s = state(description = "I walked to the see", descSel = TextRange(16, 19)) // "see" selected
        val apply = planDreamPaste("sea", s, DreamField.DESCRIPTION) as DreamPastePlan.Apply
        assertEquals("I walked to the sea", apply.result.description.text)
        assertEquals(TextRange(19), apply.result.description.selection)
        assertTrue(apply.replacedText)
    }

    @Test fun plainTextIntoAFieldWithTextAndJustACursorAsksReplaceOrInsert() {
        val s = state(description = "Hello world", descSel = TextRange(5))
        val choose = planDreamPaste("brave new", s, DreamField.DESCRIPTION) as DreamPastePlan.ChooseMode
        assertEquals("brave new", choose.replaceAll.description.text)
        assertEquals("Hellobrave new world", choose.insertAtCursor.description.text)
        assertEquals(TextRange("Hellobrave new".length), choose.insertAtCursor.description.selection)
        assertEquals("", choose.replaceAll.title.text)
    }

    @Test fun plainTextTargetsTheFocusedFieldAndATitleNeverTakesLineBreaks() {
        val s = state("", "Existing description")
        val apply = planDreamPaste("A title\nthat wrapped", s, DreamField.TITLE) as DreamPastePlan.Apply
        assertEquals("A title that wrapped", apply.result.title.text)
        assertEquals("Existing description", apply.result.description.text) // untouched
    }
}
