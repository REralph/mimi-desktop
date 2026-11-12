package com.example.a2desktop.logic

import java.awt.GraphicsEnvironment
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection

/** What was found on the clipboard when Paste was pressed. */
sealed interface ClipboardRead {
    data class Text(val value: String) : ClipboardRead
    /** Nothing on the clipboard at all. */
    data object Empty : ClipboardRead
    /** Something is there but it isn't text (an image, files, …). */
    data object Unsupported : ClipboardRead
}

/** The clipboard, as the UI uses it — the real one in the app, an in-memory one in tests, so a test
 * never touches the user's actual clipboard. */
interface ClipboardAccess {
    fun read(): ClipboardRead
    fun write(text: String): Boolean
}

/** The Windows clipboard, through AWT. Every call is guarded: another program can hold the
 * clipboard open for a moment (AWT then throws), and none of that should ever reach the user as a
 * crash — it just reads as "empty" / "couldn't copy". */
object SystemClipboard : ClipboardAccess {
    private const val ATTEMPTS = 4

    private fun <T> withClipboard(block: (java.awt.datatransfer.Clipboard) -> T): T? {
        if (GraphicsEnvironment.isHeadless()) return null
        repeat(ATTEMPTS) { attempt ->
            val result = runCatching { block(Toolkit.getDefaultToolkit().systemClipboard) }
            if (result.isSuccess) return result.getOrNull()
            if (attempt < ATTEMPTS - 1) Thread.sleep(40) // busy: try again shortly
        }
        return null
    }

    override fun read(): ClipboardRead = withClipboard { clipboard ->
        when {
            clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor) -> {
                val text = clipboard.getData(DataFlavor.stringFlavor) as? String
                if (text.isNullOrEmpty()) ClipboardRead.Empty else ClipboardRead.Text(text)
            }
            clipboard.availableDataFlavors.isEmpty() -> ClipboardRead.Empty
            else -> ClipboardRead.Unsupported
        }
    } ?: ClipboardRead.Empty

    override fun write(text: String): Boolean =
        withClipboard { it.setContents(StringSelection(text), null); true } ?: false
}
