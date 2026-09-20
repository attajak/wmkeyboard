package com.wasimaster.wmkeyboard.core.layout

/**
 * The sugar a HeliBoard key label carries, and what it means here.
 *
 * HeliBoard's label is not only what a key draws. The same field spells the
 * functional keys (`delete`, `alpha`, `action`), the local currency (`$$$`),
 * a key whose label and output differ (`aa|bb`), an explicit code
 * (`abc|!code/-10043`) and an icon (`!icon/previous_key`). Its simple text
 * format has no other field to put any of that in, so a reader that treats the
 * label as plain text turns every one of those into a key that types the word
 * "delete" or the string `aa|bb`.
 *
 * **Written from the published format** (HeliBoard's `layouts.md` documents
 * every form here), on the same footing as the key codes in [ForeignLayouts] —
 * see that file's header for why no upstream code is read.
 *
 * Kept beside the converter rather than inside it because both formats need it:
 * the JSON reader for a key whose `label` carries sugar, and the text reader for
 * every line of the file, including the press-and-hold letters after the first
 * token.
 */
internal data class ForeignLabel(
    /** What the key draws. Empty means "let the action decide". */
    val label: String,
    /** What it commits, when that differs from [label]. */
    val output: String? = null,
    val action: KeyAction = KeyAction.Text,
    /** Which punctuation slot this key fills, for field adaptation. */
    val role: KeyRole? = null,
    /** Press-and-hold letters the label itself implies, such as the currencies. */
    val longPress: List<String> = emptyList(),
    /** The label named something this keyboard does not have; drop the key. */
    val dropped: Boolean = false,
    /** Mapped onto something close rather than something equal. */
    val approximate: Boolean = false,
    /** Asked for a drawing this keyboard has no form of, such as an icon. */
    val restyled: Boolean = false,
) {

    /** True when nothing is left to draw or commit. */
    val isEmpty: Boolean
        get() = !dropped && label.isEmpty() && output.isNullOrEmpty() && action == KeyAction.Text
}

/**
 * Reads one HeliBoard label.
 *
 * Order matters and follows the format: a leading `\` escapes everything after
 * it, then the label and the output are split on the first unescaped `|`, and
 * only then is each half looked at. A file that wants a literal key called
 * "space" writes `\space`, and this is the only thing that stops it becoming a
 * spacebar.
 */
internal fun parseForeignLabel(raw: String): ForeignLabel {
    if (raw.isEmpty()) return ForeignLabel(label = "")
    if (raw.startsWith(ESCAPE)) return ForeignLabel(label = raw.drop(1))
    val split = splitOnPipe(raw)
    val left = resolveLabel(split.first)
    val right = split.second ?: return left
    return applyOutput(left, right)
}

/**
 * The label half joined to the output half.
 *
 * `!code/` on the right names an action or a code point and wins outright —
 * that is what `abc|!code/-10043` means. Anything else on the right is literal
 * text to commit, which is the `aa|bb` form.
 */
private fun applyOutput(label: ForeignLabel, right: String): ForeignLabel {
    if (!right.startsWith(CODE_PREFIX)) {
        return label.copy(output = right.takeIf { it != label.label })
    }
    val spec = right.removePrefix(CODE_PREFIX)
    val code = spec.toIntOrNull()
        // A named code (`!code/key_action_previous`) names a constant in the
        // other keyboard's source, not a number in its documented format. There
        // is nothing here to resolve it against, so the key is dropped and
        // reported rather than guessed at.
        ?: return label.copy(dropped = true)
    return when {
        code > 0 -> label.copy(
            output = String(Character.toChars(code)).takeIf { it != label.label },
            action = KeyAction.Text,
        )
        else -> when (val action = keyActionForCode(code)) {
            null -> label.copy(dropped = true)
            else -> label.copy(action = action, output = null)
        }
    }
}

/** The label half, with every special spelling the format gives it. */
private fun resolveLabel(text: String): ForeignLabel {
    if (text.isEmpty()) return ForeignLabel(label = "")
    if (text.startsWith(ICON_PREFIX)) {
        // The icon names are the other keyboard's own drawable slots. Nothing
        // here maps onto them one for one, so the key keeps whatever its action
        // draws and the loss is reported instead of a wrong icon being picked.
        return ForeignLabel(label = "", restyled = true)
    }
    CurrencyLabels[text]?.let { return it }
    return SpecialLabels[text] ?: ForeignLabel(label = text)
}

/**
 * Splits at the first `|` that is not escaped.
 *
 * Written by hand rather than with a regex because the escape is a single
 * backslash and `Regex` would need it doubled twice over, once for Kotlin and
 * once for the pattern — which is exactly the kind of thing that reads as
 * correct and is not.
 */
private fun splitOnPipe(text: String): Pair<String, String?> {
    var i = 0
    val label = StringBuilder()
    while (i < text.length) {
        val ch = text[i]
        when {
            ch == ESCAPE_CHAR && i + 1 < text.length -> {
                label.append(text[i + 1])
                i += 2
            }
            ch == '|' -> return label.toString() to text.substring(i + 1)
            else -> {
                label.append(ch)
                i++
            }
        }
    }
    return label.toString() to null
}

/**
 * What a popup entry that is a marker rather than a letter asks for.
 *
 * HeliBoard spells popup *options* as entries in the popup list itself, wrapped
 * in exclamation marks. Read as letters they become keys that type
 * `!hasLabels!`, which is what they used to do here.
 */
internal data class ForeignPopupMarker(
    /** Columns the popup should lay itself out on, or 0 for no opinion. */
    val columns: Int = 0,
)

/** Null when [text] is an ordinary letter rather than one of the markers. */
internal fun parseForeignPopupMarker(text: String): ForeignPopupMarker? {
    if (!text.startsWith("!")) return null
    val name = text.drop(1).substringBefore('!').lowercase()
    if (name.isEmpty() || !text.drop(1).contains('!')) return null
    val digits = text.substringAfterLast('!').trim().toIntOrNull() ?: 0
    return when (name) {
        "autocolumnorder", "fixedcolumnorder" -> ForeignPopupMarker(
            columns = digits.takeIf { it in AlternateColumnsRange } ?: 0,
        )
        // The rest are drawing options with no field here: dividers, label-sized
        // popup text, and "open no popup at all". They are still markers, so
        // they are swallowed rather than typed.
        "needsdividers", "haslabels", "nopanelautopopupkey" -> ForeignPopupMarker()
        else -> null
    }
}

private const val ESCAPE = "\\"
private const val ESCAPE_CHAR = '\\'
private const val CODE_PREFIX = "!code/"
private const val ICON_PREFIX = "!icon/"

/** Zero-width non-joiner, which the other keyboard names rather than spells. */
private const val ZWNJ = "\u200C"

/**
 * The words HeliBoard puts in a label to mean a functional key.
 *
 * Both spellings are here: the documentation writes them in italics
 * (`_delete_`), and the files write them bare. A key this keyboard reaches
 * another way — the D-pad layer, the toolbar keys — is marked dropped rather
 * than mapped onto something near it, for the reason the code table gives: a
 * key that silently does nothing is worse than a key that is honestly missing.
 */
private val SpecialLabels: Map<String, ForeignLabel> = buildMap {
    // Named `add`, not `put`: a local `put` inside `buildMap` shadows the map's
    // own and calls itself, which compiles and overflows the stack at class
    // initialisation time.
    fun add(name: String, label: ForeignLabel) {
        put(name, label)
        put("_${name}_", label)
    }
    add("alpha", ForeignLabel(label = "ABC", action = KeyAction.Letters))
    add("symbol", ForeignLabel(label = "?123", action = KeyAction.Symbols))
    // One key that steps between the two views. The symbols key here already
    // steps back, so the pair collapse onto it.
    add("symbol_alpha", ForeignLabel(label = "?123", action = KeyAction.Symbols, approximate = true))
    add("numpad", ForeignLabel(label = "123", action = KeyAction.Numpad))
    add("emoji", ForeignLabel(label = "", action = KeyAction.Emoji))
    add("language_switch", ForeignLabel(label = "", action = KeyAction.LanguageSwitch))
    add("action", ForeignLabel(label = "", action = KeyAction.Enter))
    add("delete", ForeignLabel(label = "", action = KeyAction.Delete))
    add("shift", ForeignLabel(label = "", action = KeyAction.Shift))
    add("space", ForeignLabel(label = "", action = KeyAction.Space))
    add("zwnj", ForeignLabel(label = ZWNJ))
    // The two punctuation slots. Their popups and their email/URI behaviour are
    // this keyboard's own job once the slot is tagged, which is why the role is
    // the whole mapping and no letters are invented here.
    add("period", ForeignLabel(label = ".", role = KeyRole.Period))
    add("comma", ForeignLabel(label = ",", role = KeyRole.Comma))
    add("ctrl", ForeignLabel(label = "", action = KeyAction.Mod(ModifierKey.CTRL)))
    add("alt", ForeignLabel(label = "", action = KeyAction.Mod(ModifierKey.ALT)))
    add("meta", ForeignLabel(label = "", action = KeyAction.Mod(ModifierKey.META)))
    add("fn", ForeignLabel(label = "", action = KeyAction.Fn))
    // Domain suffixes, which the other keyboard localises per language. A
    // literal ".com" is the closest thing here and says so.
    add("com", ForeignLabel(label = ".com", approximate = true))
    add("dpad", ForeignLabel(label = "", dropped = true))
}

/**
 * `$$$` and its numbered siblings.
 *
 * Over there the bare one is the *local* currency and the numbered ones are the
 * others on long press, both decided from the layout's language at draw time.
 * A converted layout has no language yet — the import asks for one afterwards —
 * so each lands on a fixed sign and the key is counted as approximated. The set
 * is the one every keyboard offers; picking it is a smaller lie than leaving a
 * key that types `$$$`.
 */
private val CurrencyLabels: Map<String, ForeignLabel> = mapOf(
    "$$$" to ForeignLabel(label = "$", longPress = listOf("€", "£", "¥", "₹"), approximate = true),
    "$$$1" to ForeignLabel(label = "€", approximate = true),
    "$$$2" to ForeignLabel(label = "£", approximate = true),
    "$$$3" to ForeignLabel(label = "¥", approximate = true),
    "$$$4" to ForeignLabel(label = "₹", approximate = true),
    "$$$5" to ForeignLabel(label = "₽", approximate = true),
)
