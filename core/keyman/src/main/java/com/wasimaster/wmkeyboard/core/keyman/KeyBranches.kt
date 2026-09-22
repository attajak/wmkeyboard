package com.wasimaster.wmkeyboard.core.keyman

/**
 * A key group's rules as KeymanWeb runs them: one branch per key, tried in
 * order, the first branch whose key test passes being the only one tried.
 *
 * KeymanWeb does not interpret a `.kmx`; the Keyman compiler turns the same
 * compiled rules into JavaScript, and the shape of that JavaScript is part of
 * the behaviour. Each distinct key — a key code and a shift mask, as
 * `JavaScript_Key` and `JavaScript_Shift` derive them — becomes an
 * `if(k.KKM(...)) { its rules }` in the order the key first appears, chained
 * with `else`. So once a key test passes, no later branch is looked at even if
 * none of that branch's rules matched: a `[SHIFT K_LBRKT]` rule that needs a
 * context shadows a later `[NCAPS SHIFT K_LBRKT]` rule that does not. Keyman
 * Core scans rules one by one and would take the second. The chain is broken
 * every [LADDER] branches with an `if(m) {}` — a limit on JavaScript's nesting
 * that became semantics — after which an unmatched keystroke carries on.
 *
 * Character rules (`+ 'a'`) become the one US key and shift state that types
 * that character, and on a mnemonic keyboard the key is compared as the
 * character it types instead. Rules the compiler leaves out of a touch
 * keyboard — `platform('native windows')` and the like — are left out here.
 */
internal class KeyBranches private constructor(val branches: List<Branch>) {

    class Branch(val key: Int, val shift: Int, val rules: List<KmxRule>) {
        /** Keys the compiler can never see pressed, so nothing ever enters them. */
        val reachable: Boolean get() = key != 0 && key < VirtualKeys.FIRST_FRAME_CODE
    }

    companion object {
        /** The compiler's `FFix183_LadderLength`. */
        const val LADDER = 100

        fun of(keyboard: KeymanKeyboard, group: KmxGroup): KeyBranches {
            val byKey = LinkedHashMap<Long, MutableList<KmxRule>>()
            val shapes = LinkedHashMap<Long, Pair<Int, Int>>()
            for (rule in group.rules) {
                if (excludedOnTouch(keyboard, rule)) continue
                val key = jsKey(rule, keyboard.mnemonic)
                val shift = jsShift(rule, keyboard)
                val id = (key.toLong() shl 32) or (shift.toLong() and 0xffffffffL)
                byKey.getOrPut(id) { mutableListOf() }.add(rule)
                shapes.getOrPut(id) { key to shift }
            }
            return KeyBranches(
                byKey.map { (id, rules) ->
                    val (key, shift) = shapes.getValue(id)
                    Branch(key, shift, rules)
                },
            )
        }

        /** `JavaScript_Key`. */
        private fun jsKey(rule: KmxRule, mnemonic: Boolean): Int {
            val virtual = rule.shiftFlags and KmxFormat.ISVIRTUALKEY != 0
            val key = when {
                mnemonic && virtual && rule.key <= KmxFormat.VK_MAX -> 0 // K_ keys are an error on a mnemonic keyboard
                mnemonic -> rule.key
                virtual -> rule.key
                else -> {
                    val c = rule.key.toChar()
                    var n = US_SHIFT.indexOf(c)
                    if (n < 0) n = US_UNSHIFT.indexOf(c)
                    if (n < 0) 0 else US_VALUES[n].code
                }
            }
            return if (key in UNREACHABLE) 0 else key
        }

        /**
         * `JavaScript_Shift`. The compiler moves any keyboard that uses one hand's
         * Alt or Ctrl, or caps lock, up to KeymanWeb 10's full flags, and flags it
         * could only strip are ones no pre-10 keyboard used, so the full flags
         * are what every keyboard gets.
         */
        @Suppress("UNUSED_PARAMETER")
        private fun jsShift(rule: KmxRule, keyboard: KeymanKeyboard): Int {
            val flags = rule.shiftFlags
            if (flags and KmxFormat.ISVIRTUALKEY != 0) return flags
            return if (rule.key.toChar() in US_SHIFT) {
                KmxFormat.ISVIRTUALKEY or KmxFormat.K_SHIFTFLAG
            } else {
                KmxFormat.ISVIRTUALKEY
            }
        }

        /** `RuleIsExcludedByPlatform`: a `platform('native …')` naming a desktop OS. */
        private fun excludedOnTouch(keyboard: KeymanKeyboard, rule: KmxRule): Boolean {
            val context = rule.context
            var i = 0
            while (i < context.length && context[i].code != 0) {
                if (KmxString.opcodeAt(context, i) == KmxFormat.CODE_IFSYSTEMSTORE &&
                    KmxString.operandAt(context, i, 0) == KmxFormat.TSS_PLATFORM
                ) {
                    val store = KmxString.operandAt(context, i, 2)
                    val value = keyboard.stores.getOrNull(store)?.value.orEmpty()
                    if ("native" in value && DESKTOP.containsMatchIn(value)) return true
                }
                i = KmxString.next(context, i)
            }
            return false
        }

        private val DESKTOP = Regex("windows|desktop|macosx|linux")

        // The compiler's US tables, position for position.
        private const val US_UNSHIFT = " `" + "1234567890" + "-" + "=" + "qwertyuiop" + "[" + "]" + "\\" +
            "asdfghjkl" + ";" + "'" + "zxcvbnm" + "," + "." + "/"
        private const val US_SHIFT = "ÿ" + "~" + "!@#$%^&*()" + "_" + "+" + "QWERTYUIOP" + "{" + "}" + "|" +
            "ASDFGHJKL" + ":" + "\"" + "ZXCVBNM" + "<" + ">" + "?"
        private const val US_VALUES = " " + "À" + "1234567890" + "½" + "»" + "QWERTYUIOP" +
            "Û" + "Ý" + "Ü" + "ASDFGHJKL" + "º" + "Þ" + "ZXCVBNM" + "¼" + "¾" + "¿"

        /** The compiler's `UnreachableKeyCodes`: mouse buttons, modifiers, locks. */
        private val UNREACHABLE = setOf(
            0x00, 0x01, 0x02, 0x03, 0x04, 0x10, 0x11, 0x12, 0x13, 0x14, 0x5B, 0x5C, 0x5D, 0x90, 0x91,
        )
    }
}
