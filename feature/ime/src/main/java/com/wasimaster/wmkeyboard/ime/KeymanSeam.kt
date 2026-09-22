package com.wasimaster.wmkeyboard.ime

import com.wasimaster.wmkeyboard.core.keyman.KeyProcessor
import com.wasimaster.wmkeyboard.core.keyman.KmxModifiers
import com.wasimaster.wmkeyboard.core.keyman.ProcessorKey
import com.wasimaster.wmkeyboard.core.keyman.ProcessorResult
import com.wasimaster.wmkeyboard.core.keyman.SyncDecision

/**
 * The decisions the Keyman seam makes, pulled out of [WMKeyboardService] so they
 * can be checked without an `InputConnection` — the same reason `ComposingResume`
 * exists, and for the same kind of logic: small, easy to get subtly wrong, and
 * impossible to exercise from an instrumented test at the rate a unit test can.
 */
object KeymanSeam {

    /**
     * The caret position the context describes after an edit is applied.
     *
     * Arithmetic, never a read: `onUpdateSelection` fires on every keystroke and
     * a `getTextBeforeCursor` in it would reintroduce exactly the binder
     * round-trip that `expectedSelStart` exists to avoid.
     */
    fun anchorAfter(anchor: Int, edit: ProcessorResult.Edit): Int =
        if (anchor < 0) anchor else anchor + edit.insert.length - edit.deleteBefore

    /**
     * Whether a selection report is the echo of our own edit rather than the
     * user or the app moving the caret.
     *
     * Only a collapsed caret exactly where the last edit left it counts. Anything
     * else marks the context stale, and stale resolves to a re-read at the next
     * keystroke rather than immediately — one read per caret move, none per key.
     */
    fun isOwnEcho(newSelStart: Int, newSelEnd: Int, anchor: Int): Boolean =
        anchor >= 0 && newSelStart == newSelEnd && newSelStart == anchor

    /**
     * The keyboard's shift and caps state as a Keyman modifier mask.
     *
     * Deliberately narrow: our soft keyboard has no ctrl or alt of its own, and
     * inventing them would let a rule written for `[CTRL K_A]` fire on a plain
     * `a`. Hardware modifiers reach the engine through the hardware path, which
     * has real meta state to convert.
     */
    fun modifiersFor(shifted: Boolean, capsLocked: Boolean): Int {
        var mask = 0
        if (shifted || capsLocked) mask = mask or KmxModifiers.SHIFT
        if (capsLocked) mask = mask or KmxModifiers.CAPS
        return mask
    }

    /**
     * The grid on screen as the Keyman layer a rule would name in
     * `if(&layer = ...)`. The converter folds Keyman's `shift` layer into our
     * shift state and maps `default`, `numeric` and `symbol` onto our own
     * three, so this is that mapping run backwards.
     */
    fun layerName(mode: LayoutMode, shifted: Boolean): String = when (mode) {
        LayoutMode.SYMBOLS -> "numeric"
        LayoutMode.SYMBOLS_SHIFTED -> "symbol"
        else -> if (shifted) "shift" else "default"
    }
}

/**
 * How much text behind the caret the engine is given when its context has to be
 * rebuilt. Keyman's own window, in UTF-16 code units.
 */
const val KEYMAN_CONTEXT_UNITS = 64

/**
 * One input session's worth of engine state: the processor, and how far the
 * context can be trusted.
 *
 * Holds no `Context` and touches no `InputConnection`, so the service can drive
 * it and a test can too. The service supplies the text behind the caret via a
 * lambda, which is what keeps the expensive read at the call site where it can
 * be skipped.
 */
class KeymanSession(val processor: KeyProcessor) {

    /** Caret position the cached context describes, or -1 when unknown. */
    var anchor: Int = -1
        private set

    /** True when the context may no longer match the field. */
    var stale: Boolean = true
        private set

    /** Set after the engine faults; the session is done and types nothing. */
    var disabled: Boolean = false
        private set

    fun markStale() {
        stale = true
    }

    fun reset(before: CharSequence, at: Int) {
        processor.resetContext(before)
        anchor = at
        stale = false
    }

    /**
     * Brings the context into line with the field if it has gone stale.
     *
     * [readBefore] is only called when it is actually needed, which is once per
     * caret move rather than once per keystroke.
     */
    fun syncIfNeeded(at: Int, readBefore: () -> CharSequence): SyncDecision? {
        if (!stale) return null
        val decision = processor.syncContext(readBefore())
        anchor = at
        stale = false
        return decision
    }

    /**
     * Runs one key. Returns null when the session is disabled, so the caller
     * falls back to the ordinary path.
     */
    fun process(key: ProcessorKey): ProcessorResult? {
        if (disabled) return null
        return when (val result = processor.process(key)) {
            is ProcessorResult.Failed -> {
                // A blown budget is deterministic, so retrying it would stall
                // every subsequent key. The session gives up and the layout goes
                // back to typing its own caps for the rest of the field.
                disabled = true
                null
            }
            else -> result
        }
    }

    /** Records an applied edit. Call only after the edit reached the field. */
    fun onEdited(edit: ProcessorResult.Edit) {
        anchor = KeymanSeam.anchorAfter(anchor, edit)
    }

    /** Handles a selection report, marking the context stale unless it is ours. */
    fun onSelectionReported(newSelStart: Int, newSelEnd: Int) {
        if (!KeymanSeam.isOwnEcho(newSelStart, newSelEnd, anchor)) markStale()
    }
}
