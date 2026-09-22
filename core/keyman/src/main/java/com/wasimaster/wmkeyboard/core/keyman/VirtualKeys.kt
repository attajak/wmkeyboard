package com.wasimaster.wmkeyboard.core.keyman

/**
 * Keyman's virtual keys: the `K_*` names a touch layout uses, the Windows codes
 * a `.kmx` rule matches on, and the US mapping that turns one into a character.
 *
 * Keyman is built on the US layout. A rule written `+ [K_W] > 'ឪ'` matches the
 * key in the physical position of US `W`, and a rule that does not match falls
 * back to what US `W` would have typed. So the tables here are deliberately US
 * and deliberately fixed — localising them would change which rules fire.
 */
object VirtualKeys {

    /** `K_*` name to Windows virtual key code. */
    private val BY_NAME: Map<String, Int> = buildMap {
        put("K_BKSP", 8)
        put("K_TAB", 9)
        put("K_ENTER", 13)
        put("K_SHIFT", 16)
        put("K_CONTROL", 17)
        put("K_ALT", 18)
        put("K_PAUSE", 19)
        put("K_CAPS", 20)
        put("K_ESC", 27)
        put("K_SPACE", 32)
        put("K_PGUP", 33)
        put("K_PGDN", 34)
        put("K_END", 35)
        put("K_HOME", 36)
        put("K_LEFT", 37)
        put("K_UP", 38)
        put("K_RIGHT", 39)
        put("K_DOWN", 40)
        put("K_SEL", 41)
        put("K_PRINT", 42)
        put("K_EXEC", 43)
        put("K_INS", 45)
        put("K_DEL", 46)
        put("K_HELP", 47)
        for (d in 0..9) put("K_$d", 48 + d)
        for (c in 'A'..'Z') put("K_$c", 65 + (c - 'A'))
        for (d in 0..9) put("K_NP$d", 96 + d)
        put("K_NPSTAR", 106)
        put("K_NPPLUS", 107)
        put("K_SEPARATOR", 108)
        put("K_NPMINUS", 109)
        put("K_NPDOT", 110)
        put("K_NPSLASH", 111)
        for (f in 1..24) put("K_F$f", 111 + f)
        put("K_NUMLOCK", 144)
        put("K_SCROLL", 145)
        put("K_COLON", 186)
        put("K_EQUAL", 187)
        put("K_COMMA", 188)
        put("K_HYPHEN", 189)
        put("K_PERIOD", 190)
        put("K_SLASH", 191)
        put("K_BKQUOTE", 192)
        put("K_LBRKT", 219)
        put("K_BKSLASH", 220)
        put("K_RBRKT", 221)
        put("K_QUOTE", 222)
        put("K_LSHIFT", 160)
        put("K_RSHIFT", 161)
        put("K_LCONTROL", 162)
        put("K_RCONTROL", 163)
        put("K_LALT", 164)
        put("K_RALT", 165)
        put("K_oC1", 193)
        put("K_oDF", 223)
        put("K_oE2", 226)
        // Layer-switch conveniences. They carry no intrinsic behaviour — a key
        // using one still has to set `nextlayer` — but they are legal ids and a
        // converter that does not know them would treat them as unknown. The
        // values are KeymanWeb's own (`USVirtualKeyCodes`).
        put("K_LOPT", 50001)
        put("K_ROPT", 50002)
        put("K_NUMERALS", 50003)
        put("K_SYMBOLS", 50004)
        put("K_CURRENCIES", 50005)
        put("K_UPPER", 50006)
        put("K_LOWER", 50007)
        put("K_ALPHA", 50008)
        put("K_SHIFTED", 50009)
        put("K_ALTGR", 50010)
        put("K_TABBACK", 50011)
        put("K_TABFWD", 50012)
    }

    /** The same table keyed upper-case, because KeymanWeb reads key ids that way. */
    private val BY_UPPER_NAME: Map<String, Int> = BY_NAME.mapKeys { it.key.uppercase() }

    /**
     * The first of the codes KeymanWeb gives its layer-switch and option keys
     * (`K_LOPT`, `K_NUMERALS` ...). A key at or above it is frame, not text.
     */
    const val FIRST_FRAME_CODE: Int = 50001

    /** The virtual key a touch-layout `K_*` id names, or null. Case-insensitive. */
    fun byName(name: String): Int? = BY_NAME[name] ?: BY_UPPER_NAME[name.uppercase()]

    /** True for the ids that exist to switch layers rather than to type. */
    fun isLayerSwitch(name: String): Boolean = when (name) {
        "K_NUMERALS", "K_SYMBOLS", "K_CURRENCIES", "K_SHIFTED", "K_ALTGR" -> true
        else -> false
    }

    /**
     * What the US layout types for [vkey] under [modifiers], or 0 when the key
     * produces no character.
     *
     * This is Keyman's `VKeyToChar`, and its narrowness is deliberate: only the
     * unmodified, shifted and caps states map to a character. A key held with
     * ctrl or alt has no character, so a rule written against a bare character
     * cannot match it — which is what stops Ctrl+A from typing an `a` through a
     * rule that was only ever meant for the letter.
     */
    fun toChar(vkey: Int, modifiers: Int): Char {
        val ctrlOrAlt = modifiers and
            (KmxFormat.K_CTRLFLAG or KmxFormat.K_ALTFLAG or
                KmxFormat.LCTRLFLAG or KmxFormat.RCTRLFLAG or
                KmxFormat.LALTFLAG or KmxFormat.RALTFLAG)
        if (ctrlOrAlt != 0) return '\u0000'
        val shift = (modifiers and KmxFormat.K_SHIFTFLAG) != 0
        val caps = (modifiers and KmxFormat.CAPITALFLAG) != 0

        // Space is hardcoded upstream, ahead of every other rule.
        if (vkey == 32) return ' '
        if (vkey in 65..90) {
            // Caps lock and shift cancel each other for letters, which they do
            // not for anything else — hence the xor rather than an or.
            val upper = shift xor caps
            return if (upper) ('A' + (vkey - 65)) else ('a' + (vkey - 65))
        }
        if (vkey in 48..57) {
            val index = vkey - 48
            return if (shift) SHIFTED_DIGITS[index] else ('0' + index)
        }
        if (vkey in 96..105) return '0' + (vkey - 96)
        return when (vkey) {
            106 -> '*'
            107 -> '+'
            109 -> '-'
            110 -> '.'
            111 -> '/'
            186 -> if (shift) ':' else ';'
            187 -> if (shift) '+' else '='
            188 -> if (shift) '<' else ','
            189 -> if (shift) '_' else '-'
            190 -> if (shift) '>' else '.'
            191 -> if (shift) '?' else '/'
            192 -> if (shift) '~' else '`'
            219 -> if (shift) '{' else '['
            220 -> if (shift) '|' else '\\'
            221 -> if (shift) '}' else ']'
            222 -> if (shift) '"' else '\''
            226 -> if (shift) '|' else '\\'
            else -> '\u0000'
        }
    }

    private val SHIFTED_DIGITS = charArrayOf(')', '!', '@', '#', '$', '%', '^', '&', '*', '(')

    /**
     * The 65 slots a compiled keyboard's `KV.KLS` layer arrays are indexed by,
     * in order.
     *
     * `K_*` marks the 16 unassigned slots. Do **not** build a reverse map by
     * `associateBy` — every placeholder shares that name and they would collapse
     * to one entry, silently mapping sixteen distinct positions onto index 13.
     * [defaultKeyIndex] skips them.
     */
    val DEFAULT_CODES: List<String> = listOf(
        "K_BKQUOTE", "K_1", "K_2", "K_3", "K_4", "K_5", "K_6", "K_7", "K_8", "K_9",
        "K_0", "K_HYPHEN", "K_EQUAL", "K_*", "K_*", "K_*",
        "K_Q", "K_W", "K_E", "K_R", "K_T", "K_Y", "K_U", "K_I", "K_O", "K_P",
        "K_LBRKT", "K_RBRKT", "K_BKSLASH", "K_*", "K_*", "K_*",
        "K_A", "K_S", "K_D", "K_F", "K_G", "K_H", "K_J", "K_K", "K_L",
        "K_COLON", "K_QUOTE", "K_*", "K_*", "K_*", "K_*", "K_*",
        "K_oE2", "K_Z", "K_X", "K_C", "K_V", "K_B", "K_N", "K_M",
        "K_COMMA", "K_PERIOD", "K_SLASH", "K_*", "K_*", "K_*", "K_*", "K_*",
        "K_SPACE",
    )

    private val DEFAULT_INDEX: Map<String, Int> =
        DEFAULT_CODES.withIndex()
            .filter { it.value != "K_*" }
            .associate { (i, name) -> name to i }

    /** Index into a `KLS` layer array for a key id, or -1 if it has no slot. */
    fun defaultKeyIndex(name: String): Int = DEFAULT_INDEX[name] ?: -1

    init {
        require(DEFAULT_CODES.size == 65) { "KLS layer arrays are 65 slots" }
    }
}
