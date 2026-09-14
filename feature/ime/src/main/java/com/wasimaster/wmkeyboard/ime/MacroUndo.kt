package com.wasimaster.wmkeyboard.ime

import com.wasimaster.wmkeyboard.core.selection.FindReplace
import com.wasimaster.wmkeyboard.core.selection.TextEdit

/**
 * One rewrite the selection bar made, kept so the Undo chip can put it back.
 */
sealed interface MacroUndoEntry {
    /**
     * The selection rewritten in place: a case, a style, a chat marker, a
     * grammar fix. [original] and [replacement] are the raw selected text
     * before and after, whitespace included; [start] the field offset the
     * selection began at, or -1 when the editor never said.
     */
    data class Selection(val original: String, val replacement: String, val start: Int) : MacroUndoEntry

    /**
     * A field-wide rewrite (Replace all). [edits] are the back-to-front
     * splices that turned [before] into [after]; [selStart] and [selEnd] the
     * selection to restore afterwards, in [before]'s coordinates.
     */
    data class WholeField(
        val before: String,
        val after: String,
        val edits: List<TextEdit>,
        val selStart: Int,
        val selEnd: Int,
    ) : MacroUndoEntry
}

/**
 * The rewrites made while one selection stays live, newest last.
 *
 * Bounded, and refusing a field-wide entry over a size nobody undoes on a
 * phone, because every entry holds the text twice. Not thread-safe; every
 * caller is the input thread.
 */
class MacroUndoStack(private val limit: Int = MAX_ENTRIES) {

    private val entries = ArrayDeque<MacroUndoEntry>()

    val size: Int get() = entries.size
    val isEmpty: Boolean get() = entries.isEmpty()

    fun push(entry: MacroUndoEntry) {
        if (entry is MacroUndoEntry.WholeField && entry.before.length > MAX_FIELD_CHARS) return
        entries.addLast(entry)
        while (entries.size > limit) entries.removeFirst()
    }

    fun peek(): MacroUndoEntry? = entries.lastOrNull()

    fun pop(): MacroUndoEntry? = entries.removeLastOrNull()

    fun clear() = entries.clear()

    companion object {
        const val MAX_ENTRIES = 20
        const val MAX_FIELD_CHARS = 20_000
    }
}

object MacroUndo {

    /**
     * Whether the live selection still reads as [entry]'s output. A
     * selection moved onto other text ends the session; the echo of our own
     * rewrite passes, because what is selected is exactly what was committed.
     * Field-wide entries are checked against the field at undo time instead.
     */
    fun stillHolds(entry: MacroUndoEntry, selectedNow: String?): Boolean = when (entry) {
        is MacroUndoEntry.Selection -> selectedNow == entry.replacement
        is MacroUndoEntry.WholeField -> true
    }

    /**
     * The edits that turn the text [edits] produced back into [before],
     * expressed in that produced text's coordinates and back-to-front, so
     * they apply the way the originals did.
     */
    fun invert(before: String, edits: List<TextEdit>): List<TextEdit> {
        var shift = 0
        val inverse = ArrayList<TextEdit>(edits.size)
        for (edit in edits.sortedBy { it.start }) {
            val start = edit.start + shift
            inverse += TextEdit(start, start + edit.text.length, before.substring(edit.start, edit.end))
            shift += edit.text.length - (edit.end - edit.start)
        }
        return inverse.sortedByDescending { it.start }
    }

    fun apply(text: String, edits: List<TextEdit>): String = FindReplace.apply(text, edits)
}
