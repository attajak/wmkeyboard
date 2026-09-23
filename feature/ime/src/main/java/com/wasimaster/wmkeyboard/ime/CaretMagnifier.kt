package com.wasimaster.wmkeyboard.ime

import android.view.inputmethod.CursorAnchorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What is dragging the caret while the magnifier is up. Two surfaces can, and
 * each owns its own half of the pairing, so one letting go never takes the
 * bubble away from the other.
 */
enum class CaretDragSource { TRACKPAD, SPACEBAR }

/**
 * The line the magnifier shows: the text around the caret, cut at the line
 * breaks on either side, with the caret and any selection as offsets into it.
 * [selStart] equals [selEnd] when nothing is selected.
 */
@Immutable
data class MagnifierLine(val text: String, val caret: Int, val selStart: Int, val selEnd: Int)

/** Where the field's caret is on screen, in pixels: its x and its line's top and bottom. */
@Immutable
data class CaretAnchor(val x: Float, val top: Float, val bottom: Float)

/**
 * Everything the magnifier bubble draws. [anchor] is null for an editor that
 * does not say where its caret is, and the bubble then sits over the keyboard.
 */
@Immutable
data class CaretMagnifierState(val line: MagnifierLine, val anchor: CaretAnchor?)

/** How much text is read on each side of the caret. The bubble shows far less. */
internal const val MagnifierContextChars = 64

/**
 * The line to magnify, from what the field returned around its selection.
 *
 * [before] ends where the selection starts and [after] begins where it ends.
 * The caret the magnifier centres on is the end of the selection that moves:
 * [focusAtEnd] says which. A selection longer than the context keeps only the
 * part next to that end, since the rest could never be on screen anyway.
 */
internal fun magnifierLine(
    before: CharSequence,
    selected: CharSequence,
    after: CharSequence,
    focusAtEnd: Boolean,
    context: Int = MagnifierContextChars,
): MagnifierLine {
    // Text left and right of the caret, plus where the selection sits in
    // left + right. Only the side the caret is on keeps the selection's far end.
    val left: String
    val right: String
    val selStart: Int
    val selEnd: Int
    if (focusAtEnd) {
        val sel = selected.takeLastSafe(context)
        val head = if (sel.length < selected.length) "" else before.takeLastSafe(context)
        left = head + sel
        right = after.takeSafe(context)
        selStart = head.length
        selEnd = left.length
    } else {
        val sel = selected.takeSafe(context)
        val tail = if (sel.length < selected.length) "" else after.takeSafe(context)
        left = before.takeLastSafe(context)
        right = sel + tail
        selStart = left.length
        selEnd = left.length + sel.length
    }
    val lineStart = left.lastIndexOf('\n') + 1
    val newline = right.indexOf('\n')
    val lineEnd = left.length + if (newline < 0) right.length else newline
    val text = (left + right).substring(lineStart, lineEnd)
    fun clip(offset: Int) = (offset - lineStart).coerceIn(0, text.length)
    return MagnifierLine(
        text = text,
        caret = left.length - lineStart,
        selStart = clip(selStart),
        selEnd = clip(selEnd),
    )
}

/** The last [n] chars, one fewer rather than splitting a surrogate pair. */
private fun CharSequence.takeLastSafe(n: Int): String {
    if (length <= n) return toString()
    var start = length - n
    if (Character.isLowSurrogate(this[start])) start++
    return subSequence(start, length).toString()
}

/** The first [n] chars, one fewer rather than splitting a surrogate pair. */
private fun CharSequence.takeSafe(n: Int): String {
    if (length <= n) return toString()
    var end = n
    if (Character.isHighSurrogate(this[end - 1])) end--
    return subSequence(0, end).toString()
}

/**
 * The caret's place on screen from an editor's cursor report, or null when it
 * left the insertion marker out. Editors report the marker at the selection's
 * start, so while a selection grows forward the bubble stays over its start.
 */
internal fun caretAnchorOf(info: CursorAnchorInfo): CaretAnchor? {
    val x = info.insertionMarkerHorizontal
    val top = info.insertionMarkerTop
    val bottom = info.insertionMarkerBottom
    if (x.isNaN() || top.isNaN() || bottom.isNaN()) return null
    val points = floatArrayOf(x, top, x, bottom)
    info.matrix.mapPoints(points)
    return CaretAnchor(points[0], points[1], points[3])
}

/**
 * The magnifier over the caret while a trackpad or spacebar drag moves it
 * (discussion #303).
 *
 * The bubble is the keyboard's own drawing, not a lens on the app. An input
 * method cannot read another app's pixels, so it shows the text the field
 * hands back around the caret, and asks the field where the caret is on screen
 * so the bubble can sit over it. Both are read only while a drag is in
 * progress: the cursor reports are switched on at the start and off at the end,
 * and the text is read again after each move the field reports.
 *
 * Main thread only, apart from the reads themselves.
 */
internal class CaretMagnifierController(private val scope: CoroutineScope) {
    private val _state = MutableStateFlow<CaretMagnifierState?>(null)
    val state: StateFlow<CaretMagnifierState?> = _state.asStateFlow()

    private val sources = mutableSetOf<CaretDragSource>()
    private var connection: InputConnection? = null
    private var monitoring = false
    private var anchor: CaretAnchor? = null
    private var selStart = -1
    private var selEnd = -1
    private var focusAtEnd = true
    private var readJob: Job? = null

    /** [source] started moving the caret in the field behind [ic]. */
    fun begin(source: CaretDragSource, ic: InputConnection, selStart: Int, selEnd: Int) {
        val first = sources.isEmpty()
        if (!sources.add(source) || !first) return
        connection = ic
        anchor = null
        this.selStart = selStart
        this.selEnd = selEnd
        focusAtEnd = true
        monitoring = runCatching {
            ic.requestCursorUpdates(
                InputConnection.CURSOR_UPDATE_IMMEDIATE or InputConnection.CURSOR_UPDATE_MONITOR,
            )
        }.getOrDefault(false)
        read()
    }

    /** [source] let go. The bubble stays while another source still holds it. */
    fun end(source: CaretDragSource) {
        if (sources.remove(source) && sources.isEmpty()) stop()
    }

    /** Every source at once: the field or the keyboard went away. */
    fun stop() {
        sources.clear()
        readJob?.cancel()
        readJob = null
        if (monitoring) runCatching { connection?.requestCursorUpdates(0) }
        monitoring = false
        connection = null
        anchor = null
        _state.value = null
    }

    /** The field reported a new selection; the moving end is the one that changed. */
    fun onSelection(newSelStart: Int, newSelEnd: Int) {
        if (sources.isEmpty()) return
        if (newSelEnd != selEnd) {
            focusAtEnd = true
        } else if (newSelStart != selStart) {
            focusAtEnd = false
        }
        selStart = newSelStart
        selEnd = newSelEnd
        read()
    }

    /** The field said where its caret is now. */
    fun onCursorAnchor(info: CursorAnchorInfo) {
        if (sources.isEmpty()) return
        val next = caretAnchorOf(info) ?: return
        anchor = next
        _state.value?.let { _state.value = it.copy(anchor = next) }
    }

    /**
     * Reads the text around the selection off the main thread. A newer read
     * cancels the one in flight, so a fast drag only ever draws the latest.
     */
    private fun read() {
        val ic = connection ?: return
        val hasSelection = selEnd > selStart
        val atEnd = focusAtEnd
        readJob?.cancel()
        readJob = scope.launch {
            val line = withContext(Dispatchers.Default) {
                runCatching {
                    val before = ic.getTextBeforeCursor(MagnifierContextChars, 0)
                        ?: return@runCatching null
                    val selected = if (hasSelection) ic.getSelectedText(0) ?: "" else ""
                    val after = ic.getTextAfterCursor(MagnifierContextChars, 0) ?: ""
                    magnifierLine(before, selected, after, atEnd)
                }.getOrNull()
            } ?: return@launch
            // Back on the main thread, where [stop] runs: a stop that got here
            // first cancelled this job, and the switch above threw instead.
            _state.value = CaretMagnifierState(line, anchor)
        }
    }
}
