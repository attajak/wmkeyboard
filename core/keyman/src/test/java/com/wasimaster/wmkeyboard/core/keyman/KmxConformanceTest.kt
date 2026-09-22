package com.wasimaster.wmkeyboard.core.keyman

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Types the keystroke sequences Keyman ships with its own keyboards and checks
 * what comes out.
 *
 * A `.kmp` package carries `examples[]` entries of the form
 * `{keys: "x j m E r", text: "ខ្មែរ"}` — the keyboard author's own statement of
 * what their keyboard does. That makes them the one piece of conformance data
 * that is neither written by us nor derived from our reading of the format, so
 * they are the gate on the engine in a way that no unit test of its internals
 * can be.
 *
 * The fake host below stores **real UTF-16** and takes `deleteBefore` in UTF-16
 * units, deliberately: if it spoke the engine's own units instead, it would
 * agree with an off-by-one in the code-point/code-unit/marker conversion rather
 * than catching it, and that conversion is where every astral-plane keyboard
 * breaks.
 */
class KmxConformanceTest {

    /** What an editor would hold. Applies edits the way `InputConnection` does. */
    private class Field {
        val text = StringBuilder()

        fun apply(edit: ProcessorResult.Edit) {
            require(edit.deleteBefore <= text.length) {
                "engine asked to delete ${edit.deleteBefore} units with only ${text.length} present"
            }
            text.setLength(text.length - edit.deleteBefore)
            text.append(edit.insert)
        }
    }

    private fun keyboard(name: String): KeymanKeyboard {
        val bytes = checkNotNull(javaClass.classLoader?.getResourceAsStream("kmx/$name")) {
            "missing fixture kmx/$name"
        }.use { it.readBytes() }
        return (KmxParser.parse(bytes) as KeymanResult.Success).value
    }

    /**
     * A key token from an `examples[].keys` string to a keystroke.
     *
     * Tokens are US key caps: `x` is that key unshifted, `E` is shift plus the
     * `e` key, `]` is the right-bracket key. Built by inverting
     * [VirtualKeys.toChar] rather than by hand, so the two cannot disagree.
     */
    private fun keystroke(token: String): ProcessorKey? {
        if (token.length != 1) return null
        val c = token[0]
        for (vk in 0..255) {
            if (VirtualKeys.toChar(vk, 0) == c) return ProcessorKey(vk, 0)
        }
        for (vk in 0..255) {
            if (VirtualKeys.toChar(vk, KmxFormat.K_SHIFTFLAG) == c) {
                return ProcessorKey(vk, KmxFormat.K_SHIFTFLAG)
            }
        }
        return null
    }

    private fun type(kb: KeymanKeyboard, keys: String): String {
        val engine = KmxProcessor(kb)
        val field = Field()
        engine.resetContext("")
        for (token in keys.trim().split(" ").filter { it.isNotEmpty() }) {
            val key = keystroke(token) ?: error("cannot map key token '$token'")
            when (val result = engine.process(key)) {
                is ProcessorResult.Edit -> field.apply(result)
                is ProcessorResult.Declined -> {
                    // No rule fired, so the key types what US would have typed —
                    // which is what the host does on Declined.
                    val c = VirtualKeys.toChar(key.vkey, key.modifiers)
                    if (c != ' ' || key.vkey == 32) field.text.append(c)
                }
                is ProcessorResult.Failed -> error("engine faulted: ${result.fault}")
            }
        }
        return field.text.toString()
    }

    private fun codePoints(s: String): String =
        s.codePoints().toArray().joinToString(" ") { "U+%04X".format(it) }

    @Test
    fun `khmer angkor types its own example`() {
        val expected = "ខ្មែរ" // ខ្មែរ
        val actual = type(keyboard("khmer_angkor.kmx"), "x j m E r")
        assertEquals(
            "expected ${codePoints(expected)}\n     got ${codePoints(actual)}",
            expected,
            actual,
        )
    }

    @Test
    fun `lao 2008 types its own example`() {
        val expected = "ພາສາລາວກໍໄດ້"
        val actual = type(keyboard("lao_2008_basic.kmx"), "r k l k ] k ; d = w f h")
        assertEquals(
            "expected ${codePoints(expected)}\n     got ${codePoints(actual)}",
            expected,
            actual,
        )
    }

    /**
     * A keyboard with no rules for a key must decline rather than swallow it,
     * or plain typing on a US layout would go silent.
     */
    @Test
    fun `basic us passes ordinary letters straight through`() {
        assertEquals("hello", type(keyboard("basic_kbdus.kmx"), "h e l l o"))
    }

    /**
     * Backspace over engine output must remove exactly one character. This is
     * the delete path, which is separate from the output path and gets the
     * unit conversion wrong independently of it.
     */
    @Test
    fun `backspace removes one character of engine output`() {
        val kb = keyboard("khmer_angkor.kmx")
        val engine = KmxProcessor(kb)
        val field = Field()
        engine.resetContext("")
        for (token in listOf("x", "j", "m", "E", "r")) {
            val result = engine.process(keystroke(token)!!)
            if (result is ProcessorResult.Edit) field.apply(result)
        }
        val before = field.text.toString()
        assertTrue("nothing was typed", before.isNotEmpty())

        when (val result = engine.process(ProcessorKey(8, 0))) {
            is ProcessorResult.Edit -> field.apply(result)
            is ProcessorResult.Declined -> field.text.setLength(field.text.length - 1)
            is ProcessorResult.Failed -> error("engine faulted: ${result.fault}")
        }
        assertTrue(
            "backspace did not shorten '${codePoints(before)}' -> '${codePoints(field.text.toString())}'",
            field.text.length < before.length,
        )
    }

    /**
     * `platform('touch')` rules fire on a touch device and nowhere else. Khmer
     * Angkor deletes a subscript consonant together with its coeng on touch,
     * where the pair is one key, and one character at a time on a desktop.
     */
    @Test
    fun `platform rules follow the device`() {
        fun backspaceAfter(platform: String): String {
            val engine = KmxProcessor(keyboard("khmer_angkor.kmx"), platform = platform)
            val field = Field()
            engine.resetContext("")
            // ក ្ ក: a consonant, the coeng, and the subscript it introduces.
            for (vk in listOf(75, 74, 75)) {
                (engine.process(ProcessorKey(vk, 0)) as ProcessorResult.Edit).let(field::apply)
            }
            (engine.process(ProcessorKey(8, 0)) as ProcessorResult.Edit).let(field::apply)
            return field.text.toString()
        }
        assertEquals("\u1780", backspaceAfter(KmxProcessor.PLATFORM_TOUCH_PHONE))
        assertEquals("\u1780\u17D2", backspaceAfter("windows desktop hardware native"))
    }

    /**
     * A `T_` key is known to the rules by its place in the keyboard's key
     * dictionary. Khmer Angkor's subscript flicks are `T_17D2_xxxx` keys, each
     * with rules of its own.
     */
    @Test
    fun `named touch keys resolve through the key dictionary`() {
        val engine = KmxProcessor(keyboard("khmer_angkor.kmx"))
        val field = Field()
        engine.resetContext("")
        (engine.process(ProcessorKey(75, 0)) as ProcessorResult.Edit).let(field::apply)
        val code = checkNotNull(engine.keyForName("T_17D2_1786")) { "no T_17D2_1786 in the dictionary" }
        assertTrue("dictionary codes start at 256", code >= 256)
        assertEquals(code, engine.keyForName("t_17d2_1786"))
        (engine.process(ProcessorKey(code, 0)) as ProcessorResult.Edit).let(field::apply)
        assertEquals("\u1780\u17D2\u1786", field.text.toString())
        assertEquals(null, engine.keyForName("T_NOT_THERE"))
    }

    /**
     * Khmer Angkor's PostKeystroke group returns from the shift layer after a
     * shifted letter, stays on the numeric layer after a digit, and otherwise
     * goes back to the default layer — and, being readonly, never touches the
     * text or the engine's view of it.
     */
    @Test
    fun `post keystroke picks the layer and leaves the context alone`() {
        val engine = KmxProcessor(keyboard("khmer_angkor.kmx"))
        val field = Field()
        engine.resetContext("")
        assertTrue(engine.hasPostKeystroke)

        engine.setLayer("shift")
        (engine.process(ProcessorKey(81, KmxFormat.K_SHIFTFLAG)) as ProcessorResult.Edit).let(field::apply)
        assertEquals("ឈ", field.text.toString())
        assertEquals("default", engine.onPostKeystroke())
        // A layer the keystroke itself chose is left alone.
        engine.setLayer("shift")
        assertEquals(null, engine.onPostKeystroke(newLayer = "shift", oldLayer = "default"))

        engine.setLayer("numeric")
        (engine.process(ProcessorKey(49, 0)) as ProcessorResult.Edit).let(field::apply)
        assertEquals(null, engine.onPostKeystroke())

        // Nothing above may have changed what the engine thinks the field holds.
        (engine.process(ProcessorKey(8, 0)) as ProcessorResult.Edit).let(field::apply)
        assertEquals("ឈ", field.text.toString())
    }

    /** No sequence may ever ask the host to delete more than the field holds. */
    @Test
    fun `no keyboard ever over-deletes`() {
        for (name in listOf("khmer_angkor.kmx", "lao_2008_basic.kmx", "sil_ipa.kmx", "sil_euro_latin.kmx")) {
            val kb = keyboard(name)
            val engine = KmxProcessor(kb)
            val field = Field()
            engine.resetContext("")
            for (vk in 65..90) {
                for (mods in listOf(0, KmxFormat.K_SHIFTFLAG)) {
                    when (val r = engine.process(ProcessorKey(vk, mods))) {
                        is ProcessorResult.Edit -> {
                            assertTrue(
                                "$name asked to delete ${r.deleteBefore} of ${field.text.length}",
                                r.deleteBefore <= field.text.length,
                            )
                            field.apply(r)
                        }
                        is ProcessorResult.Declined ->
                            field.text.append(VirtualKeys.toChar(vk, mods))
                        is ProcessorResult.Failed -> error("$name faulted: ${r.fault}")
                    }
                }
            }
        }
    }
}
