package com.wasimaster.wmkeyboard.app

import android.content.res.Configuration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalConfiguration

/**
 * What a key press asks a code editor to do.
 *
 * The keys are Visual Studio Code's defaults for Windows and Linux, which is the
 * layout of a keyboard plugged into an Android device, so hands that know that
 * editor already know this one. [Scope.EDITOR] commands work on the text and the
 * field runs them itself; [Scope.SCREEN] commands belong to the screen around the
 * field, which may have nothing to do for one of them.
 *
 * Keys with no command here, such as Ctrl+A, Ctrl+Backspace or Shift+Right, are
 * left to the text field, which already does what every editor does with them.
 */
internal enum class CodeCommand(val scope: Scope) {
    INDENT(Scope.EDITOR),
    OUTDENT(Scope.EDITOR),
    INDENT_LINES(Scope.EDITOR),
    OUTDENT_LINES(Scope.EDITOR),
    UNDO(Scope.EDITOR),
    REDO(Scope.EDITOR),
    TOGGLE_LINE_COMMENT(Scope.EDITOR),
    TOGGLE_BLOCK_COMMENT(Scope.EDITOR),
    MOVE_LINES_UP(Scope.EDITOR),
    MOVE_LINES_DOWN(Scope.EDITOR),
    COPY_LINES_UP(Scope.EDITOR),
    COPY_LINES_DOWN(Scope.EDITOR),
    DELETE_LINES(Scope.EDITOR),
    INSERT_LINE_BELOW(Scope.EDITOR),
    INSERT_LINE_ABOVE(Scope.EDITOR),

    /** Cut, copy and paste take the whole line when nothing is selected, and leave a selection to the field. */
    CUT(Scope.EDITOR),
    COPY(Scope.EDITOR),
    PASTE(Scope.EDITOR),
    HOME(Scope.EDITOR),
    SELECT_HOME(Scope.EDITOR),
    DOCUMENT_START(Scope.EDITOR),
    SELECT_DOCUMENT_START(Scope.EDITOR),
    DOCUMENT_END(Scope.EDITOR),
    SELECT_DOCUMENT_END(Scope.EDITOR),
    PAGE_UP(Scope.EDITOR),
    SELECT_PAGE_UP(Scope.EDITOR),
    PAGE_DOWN(Scope.EDITOR),
    SELECT_PAGE_DOWN(Scope.EDITOR),
    SCROLL_LINE_UP(Scope.EDITOR),
    SCROLL_LINE_DOWN(Scope.EDITOR),
    SELECT_LINE(Scope.EDITOR),
    EXPAND_SELECTION(Scope.EDITOR),
    SHRINK_SELECTION(Scope.EDITOR),
    JUMP_TO_BRACKET(Scope.EDITOR),
    FOLD(Scope.EDITOR),
    UNFOLD(Scope.EDITOR),
    FOLD_ALL(Scope.EDITOR),
    UNFOLD_ALL(Scope.EDITOR),
    SUGGEST(Scope.EDITOR),

    FIND(Scope.SCREEN),
    REPLACE(Scope.SCREEN),
    FIND_NEXT(Scope.SCREEN),
    FIND_PREVIOUS(Scope.SCREEN),
    GO_TO_LINE(Scope.SCREEN),
    GO_TO_DEFINITION(Scope.SCREEN),
    RENAME(Scope.SCREEN),
    GO_TO_SYMBOL(Scope.SCREEN),
    FORMAT(Scope.SCREEN),
    TOGGLE_WRAP(Scope.SCREEN),
    ZOOM_IN(Scope.SCREEN),
    ZOOM_OUT(Scope.SCREEN),
    ZOOM_RESET(Scope.SCREEN),
    SAVE(Scope.SCREEN),
    RUN(Scope.SCREEN),
    STOP(Scope.SCREEN),
    NEXT_PROBLEM(Scope.SCREEN),
    PREVIOUS_PROBLEM(Scope.SCREEN),
    SHOW_PROBLEMS(Scope.SCREEN),
    SHOW_CONSOLE(Scope.SCREEN),
    SHOW_COMMANDS(Scope.SCREEN),

    /** Closes what is open over the code. It never leaves the screen: an editor does not close on Esc. */
    ESCAPE(Scope.SCREEN),

    /** The first key of a two-key chord, or a second key that names nothing. The press does nothing else. */
    CHORD(Scope.EDITOR),
    ;

    enum class Scope { EDITOR, SCREEN }
}

/** One key press and the command it runs. [keyName] is the key as printed on it, for the label a menu shows. */
@Immutable
internal data class CodeBinding(
    val key: Key,
    val keyName: String,
    val command: CodeCommand,
    val ctrl: Boolean = false,
    val shift: Boolean = false,
    val alt: Boolean = false,
) {
    /** The press written the way VS Code writes it, such as Ctrl+Shift+K or Shift+Alt+F. */
    val label: String
        get() = buildString {
            if (ctrl) append("Ctrl+")
            if (shift) append("Shift+")
            if (alt) append("Alt+")
            append(keyName)
        }
}

/**
 * Every single press the code editors answer. The first binding of a command is
 * the one its menu item shows. Meta counts as Ctrl, for keyboards made for a Mac.
 */
internal val CodeBindings: List<CodeBinding> = listOf(
    CodeBinding(Key.Tab, "Tab", CodeCommand.INDENT),
    CodeBinding(Key.Tab, "Tab", CodeCommand.OUTDENT, shift = true),
    CodeBinding(Key.RightBracket, "]", CodeCommand.INDENT_LINES, ctrl = true),
    CodeBinding(Key.LeftBracket, "[", CodeCommand.OUTDENT_LINES, ctrl = true),
    CodeBinding(Key.Z, "Z", CodeCommand.UNDO, ctrl = true),
    CodeBinding(Key.Y, "Y", CodeCommand.REDO, ctrl = true),
    CodeBinding(Key.Z, "Z", CodeCommand.REDO, ctrl = true, shift = true),
    CodeBinding(Key.Slash, "/", CodeCommand.TOGGLE_LINE_COMMENT, ctrl = true),
    CodeBinding(Key.A, "A", CodeCommand.TOGGLE_BLOCK_COMMENT, shift = true, alt = true),
    CodeBinding(Key.DirectionUp, "Up", CodeCommand.MOVE_LINES_UP, alt = true),
    CodeBinding(Key.DirectionDown, "Down", CodeCommand.MOVE_LINES_DOWN, alt = true),
    CodeBinding(Key.DirectionUp, "Up", CodeCommand.COPY_LINES_UP, shift = true, alt = true),
    CodeBinding(Key.DirectionDown, "Down", CodeCommand.COPY_LINES_DOWN, shift = true, alt = true),
    CodeBinding(Key.K, "K", CodeCommand.DELETE_LINES, ctrl = true, shift = true),
    CodeBinding(Key.Enter, "Enter", CodeCommand.INSERT_LINE_BELOW, ctrl = true),
    CodeBinding(Key.NumPadEnter, "Enter", CodeCommand.INSERT_LINE_BELOW, ctrl = true),
    CodeBinding(Key.Enter, "Enter", CodeCommand.INSERT_LINE_ABOVE, ctrl = true, shift = true),
    CodeBinding(Key.NumPadEnter, "Enter", CodeCommand.INSERT_LINE_ABOVE, ctrl = true, shift = true),
    CodeBinding(Key.X, "X", CodeCommand.CUT, ctrl = true),
    CodeBinding(Key.C, "C", CodeCommand.COPY, ctrl = true),
    CodeBinding(Key.V, "V", CodeCommand.PASTE, ctrl = true),
    CodeBinding(Key.MoveHome, "Home", CodeCommand.HOME),
    CodeBinding(Key.MoveHome, "Home", CodeCommand.SELECT_HOME, shift = true),
    CodeBinding(Key.MoveHome, "Home", CodeCommand.DOCUMENT_START, ctrl = true),
    CodeBinding(Key.MoveHome, "Home", CodeCommand.SELECT_DOCUMENT_START, ctrl = true, shift = true),
    CodeBinding(Key.MoveEnd, "End", CodeCommand.DOCUMENT_END, ctrl = true),
    CodeBinding(Key.MoveEnd, "End", CodeCommand.SELECT_DOCUMENT_END, ctrl = true, shift = true),
    CodeBinding(Key.PageUp, "PageUp", CodeCommand.PAGE_UP),
    CodeBinding(Key.PageUp, "PageUp", CodeCommand.SELECT_PAGE_UP, shift = true),
    CodeBinding(Key.PageDown, "PageDown", CodeCommand.PAGE_DOWN),
    CodeBinding(Key.PageDown, "PageDown", CodeCommand.SELECT_PAGE_DOWN, shift = true),
    CodeBinding(Key.DirectionUp, "Up", CodeCommand.SCROLL_LINE_UP, ctrl = true),
    CodeBinding(Key.DirectionDown, "Down", CodeCommand.SCROLL_LINE_DOWN, ctrl = true),
    CodeBinding(Key.L, "L", CodeCommand.SELECT_LINE, ctrl = true),
    CodeBinding(Key.DirectionRight, "Right", CodeCommand.EXPAND_SELECTION, shift = true, alt = true),
    CodeBinding(Key.DirectionLeft, "Left", CodeCommand.SHRINK_SELECTION, shift = true, alt = true),
    CodeBinding(Key.Backslash, "\\", CodeCommand.JUMP_TO_BRACKET, ctrl = true, shift = true),
    CodeBinding(Key.LeftBracket, "[", CodeCommand.FOLD, ctrl = true, shift = true),
    CodeBinding(Key.RightBracket, "]", CodeCommand.UNFOLD, ctrl = true, shift = true),
    CodeBinding(Key.Spacebar, "Space", CodeCommand.SUGGEST, ctrl = true),
    // VS Code's second key for suggestions. It matters here: with WM Keyboard as the
    // input method, Ctrl+Space switches the language before the editor sees it.
    CodeBinding(Key.I, "I", CodeCommand.SUGGEST, ctrl = true),
    CodeBinding(Key.F, "F", CodeCommand.FIND, ctrl = true),
    CodeBinding(Key.H, "H", CodeCommand.REPLACE, ctrl = true),
    CodeBinding(Key.F3, "F3", CodeCommand.FIND_NEXT),
    CodeBinding(Key.F3, "F3", CodeCommand.FIND_PREVIOUS, shift = true),
    CodeBinding(Key.G, "G", CodeCommand.GO_TO_LINE, ctrl = true),
    CodeBinding(Key.F12, "F12", CodeCommand.GO_TO_DEFINITION),
    CodeBinding(Key.F2, "F2", CodeCommand.RENAME),
    CodeBinding(Key.O, "O", CodeCommand.GO_TO_SYMBOL, ctrl = true, shift = true),
    CodeBinding(Key.F, "F", CodeCommand.FORMAT, shift = true, alt = true),
    CodeBinding(Key.Z, "Z", CodeCommand.TOGGLE_WRAP, alt = true),
    CodeBinding(Key.Equals, "=", CodeCommand.ZOOM_IN, ctrl = true),
    CodeBinding(Key.Equals, "=", CodeCommand.ZOOM_IN, ctrl = true, shift = true),
    CodeBinding(Key.Plus, "+", CodeCommand.ZOOM_IN, ctrl = true),
    CodeBinding(Key.NumPadAdd, "NumPad+", CodeCommand.ZOOM_IN, ctrl = true),
    CodeBinding(Key.Minus, "-", CodeCommand.ZOOM_OUT, ctrl = true),
    CodeBinding(Key.NumPadSubtract, "NumPad-", CodeCommand.ZOOM_OUT, ctrl = true),
    CodeBinding(Key.Zero, "0", CodeCommand.ZOOM_RESET, ctrl = true),
    CodeBinding(Key.NumPad0, "NumPad0", CodeCommand.ZOOM_RESET, ctrl = true),
    CodeBinding(Key.S, "S", CodeCommand.SAVE, ctrl = true),
    CodeBinding(Key.F5, "F5", CodeCommand.RUN),
    CodeBinding(Key.F5, "F5", CodeCommand.STOP, shift = true),
    CodeBinding(Key.F8, "F8", CodeCommand.NEXT_PROBLEM),
    CodeBinding(Key.F8, "F8", CodeCommand.PREVIOUS_PROBLEM, shift = true),
    CodeBinding(Key.M, "M", CodeCommand.SHOW_PROBLEMS, ctrl = true, shift = true),
    CodeBinding(Key.Y, "Y", CodeCommand.SHOW_CONSOLE, ctrl = true, shift = true),
    CodeBinding(Key.F1, "F1", CodeCommand.SHOW_COMMANDS),
    CodeBinding(Key.P, "P", CodeCommand.SHOW_COMMANDS, ctrl = true, shift = true),
    CodeBinding(Key.Escape, "Esc", CodeCommand.ESCAPE),
)

/** The second key of each chord that starts with Ctrl+K, as VS Code has them. */
internal val CodeChordBindings: List<CodeBinding> = listOf(
    CodeBinding(Key.Zero, "0", CodeCommand.FOLD_ALL, ctrl = true),
    CodeBinding(Key.J, "J", CodeCommand.UNFOLD_ALL, ctrl = true),
)

private val ChordStart = CodeBinding(Key.K, "K", CodeCommand.CHORD, ctrl = true)

private data class Press(val key: Key, val ctrl: Boolean, val shift: Boolean, val alt: Boolean)

private val CodeBinding.press: Press get() = Press(key, ctrl, shift, alt)

private val SinglePresses: Map<Press, CodeCommand> by lazy { CodeBindings.associate { it.press to it.command } }

private val ChordPresses: Map<Press, CodeCommand> by lazy { CodeChordBindings.associate { it.press to it.command } }

/** A modifier on its own is the hand getting ready for the next key, not a key. */
private val ModifierKeys = setOf(
    Key.CtrlLeft, Key.CtrlRight, Key.ShiftLeft, Key.ShiftRight, Key.AltLeft, Key.AltRight,
    Key.MetaLeft, Key.MetaRight, Key.Function, Key.CapsLock,
)

/** The command for one press on its own, or null to leave the key to the text field. */
internal fun codeCommandFor(key: Key, ctrl: Boolean, shift: Boolean, alt: Boolean): CodeCommand? =
    SinglePresses[Press(key, ctrl, shift, alt)]

/**
 * Turns presses into commands, remembering a Ctrl+K that waits for its second
 * key. One per field, so a chord started in one editor does not finish in another.
 */
internal class CodeKeyChords {
    private var waiting = false

    fun map(key: Key, ctrl: Boolean, shift: Boolean, alt: Boolean): CodeCommand? {
        if (key in ModifierKeys) return null
        val press = Press(key, ctrl, shift, alt)
        if (waiting) {
            waiting = false
            return ChordPresses[press] ?: CodeCommand.CHORD
        }
        if (press == ChordStart.press) {
            waiting = true
            return CodeCommand.CHORD
        }
        return SinglePresses[press]
    }
}

/** The keys a menu shows beside this command, or null for a command no key runs. */
internal fun CodeCommand.shortcutLabel(): String? {
    CodeChordBindings.firstOrNull { it.command == this }?.let { return "${ChordStart.label} ${it.label}" }
    return CodeBindings.firstOrNull { it.command == this }?.label
}

/** Whether a physical keyboard is attached and open: the code key row steps aside, and menus show keys. */
@Composable
internal fun hardwareKeyboardAttached(): Boolean {
    val configuration = LocalConfiguration.current
    return configuration.keyboard != Configuration.KEYBOARD_NOKEYS &&
        configuration.hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_NO
}

/** The trailing slot of a menu item that runs [command]: its keys while [keyboard] is attached, else nothing. */
internal fun shortcutSlot(keyboard: Boolean, command: CodeCommand): (@Composable () -> Unit)? {
    val label = command.shortcutLabel()
    if (!keyboard || label == null) return null
    return { Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
}
