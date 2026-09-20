package com.wasimaster.wmkeyboard.core.layout

import com.wasimaster.wmkeyboard.language.R
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading a FUTO Keyboard layout.
 *
 * The fixtures are hand-written from the published format, not layouts taken
 * from FUTO's repository: that repository is its authors' work and this only
 * needs the shape of a document.
 */
class FutoLayoutTest {

    private fun converted(yaml: String): ConvertedLayout =
        checkNotNull(FutoLayouts.convert(yaml, "x.yaml")) { "did not convert" }

    private fun ConvertedLayout.letters(): List<List<Key>> =
        checkNotNull(layout.layer(LayoutLayer.LETTERS)).rows

    private fun ConvertedLayout.keys(): List<Key> = letters().flatten()

    // ---- the document ----

    @Test
    fun `a row written as a string is one key per word`() {
        val converted = converted(
            """
            name: QWERTY
            rows:
              - letters: q w e
              - letters: a s d
            """.trimIndent(),
        )
        assertEquals(listOf("q", "w", "e"), converted.letters()[0].map { it.label })
        assertEquals(2, converted.letters().size)
    }

    @Test
    fun `the layout takes the name the file gives it`() {
        assertEquals(
            "Lietuvių",
            converted("name: Lietuvių\nrows:\n  - letters: a b c").layout.name,
        )
    }

    @Test
    fun `a declared language is used instead of a guess`() {
        // The one thing this format has that the others do not: it says what
        // language the layout is for, so the picker starts from a fact.
        val converted = converted("name: x\nlanguages: [fr]\nrows:\n  - letters: a b c")
        assertEquals("fr", converted.guessedLangId)
    }

    @Test
    fun `a language this app does not have falls back to the guess`() {
        val converted = converted("name: x\nlanguages: [zzz]\nrows:\n  - letters: a b c")
        assertEquals("en", converted.guessedLangId)
    }

    @Test
    fun `it is told apart from a JSON layout before anything is parsed`() {
        assertTrue(FutoLayouts.looksLikeFutoLayout("name: x\nrows:\n  - letters: a"))
        // A YAML parser takes JSON happily, so the sniff is on the document's
        // own shape. Without this every FlorisBoard layout would come through
        // here instead.
        assertFalse(FutoLayouts.looksLikeFutoLayout("""[[{"label":"a"}]]"""))
        assertFalse(FutoLayouts.looksLikeFutoLayout("a b c\n\nd e f"))
    }

    @Test
    fun `a file with no rows converts to nothing`() {
        assertNull(FutoLayouts.convert("name: x", "x.yaml"))
        assertNull(FutoLayouts.convert("not: a layout", "x.yaml"))
        assertNull(FutoLayouts.convert("", "x.yaml"))
    }

    // ---- keys ----

    @Test
    fun `a list is a key and its press-and-hold letters`() {
        val converted = converted("name: x\nrows:\n  - letters: [[a, ą, ä], b]")
        val a = converted.keys().first { it.label == "a" }
        assertEquals(listOf("ą", "ä"), a.longPress)
    }

    @Test
    fun `an object key states its spec and popups`() {
        val converted = converted(
            """
            name: x
            rows:
              - letters:
                  - {type: base, spec: "c", moreKeys: "č,ç"}
            """.trimIndent(),
        )
        val c = converted.keys().first { it.label == "c" }
        assertEquals(listOf("č", "ç"), c.longPress)
    }

    @Test
    fun `a case key keeps both cases`() {
        val converted = converted(
            """
            name: x
            rows:
              - letters:
                  - {type: case, normal: "ß", shifted: "ẞ"}
            """.trimIndent(),
        )
        val key = converted.keys().first { it.label == "ß" }
        assertEquals("ẞ", key.shiftLabel)
    }

    @Test
    fun `the template keys become the keys they name`() {
        val converted = converted(
            """
            name: x
            rows:
              - letters: ${'$'}shift z x ${'$'}delete
              - bottom: ${'$'}symbols ${'$'}space ${'$'}period ${'$'}enter
            """.trimIndent(),
        )
        val actions = converted.keys().map { it.action }
        assertTrue(KeyAction.Shift in actions)
        assertTrue(KeyAction.Delete in actions)
        assertTrue(KeyAction.Symbols in actions)
        assertEquals(1, actions.count { it == KeyAction.Space })
        assertEquals(1, actions.count { it == KeyAction.Enter })
        assertEquals(KeyRole.Period, converted.keys().first { it.label == "." }.role)
    }

    @Test
    fun `the spacebar takes what is left of its row`() {
        val converted = converted(
            """
            name: x
            rows:
              - letters: q w e r t y u i o p
              - bottom: ${'$'}symbols ${'$'}space ${'$'}enter
            """.trimIndent(),
        )
        val bottom = converted.letters().last()
        val space = bottom.first { it.action == KeyAction.Space }
        // Wider than the keys beside it, and the row adds up to the letters
        // row above rather than to some constant.
        assertTrue("space is ${space.width}", space.width > 2f)
    }

    @Test
    fun `an alternate page key is dropped and named`() {
        // A whole second grid this app has no slot for. A key that switched to
        // nothing would strand the user on it.
        val converted = converted("name: x\nrows:\n  - letters: a ${'$'}alt0 b")
        assertEquals(listOf("a", "b"), converted.letters()[0].take(2).map { it.label })
        assertTrue(
            converted.notes.any { it.pluralsRes == R.plurals.core_lang_foreign_labels_dropped },
        )
    }

    @Test
    fun `a contextual key becomes the punctuation slot when it has a fallback`() {
        val converted = converted(
            """
            name: x
            rows:
              - letters:
                  - {type: contextual, fallbackKey: ","}
            """.trimIndent(),
        )
        assertEquals(KeyRole.Comma, converted.keys().first { it.label == "," }.role)
    }

    @Test
    fun `a gap is a gap, not a key`() {
        val converted = converted("name: x\nrows:\n  - letters: [a, ${'$'}gap, b]")
        assertTrue(converted.letters()[0].any { it.action == KeyAction.None })
    }

    // ---- what the format shares with the others ----

    @Test
    fun `an AOSP key spec is read the same way it is elsewhere`() {
        val converted = converted("""name: x${'\n'}rows:${'\n'}  - letters: ["aa|bb", c]""")
        val key = converted.keys().first { it.label == "aa" }
        assertEquals("bb", key.output)
    }

    @Test
    fun `a popup marker is not a letter here either`() {
        val converted = converted(
            """name: x${'\n'}rows:${'\n'}  - letters: [[q, "!fixedColumnOrder!4", a, b]]""",
        )
        val q = converted.keys().first { it.label == "q" }
        assertEquals(listOf("a", "b"), q.longPress)
        assertEquals(4, q.alternateColumns)
    }

    @Test
    fun `the automatic-popup placeholder is not a percent key`() {
        // `%` says where this keyboard's own extra letters go. There is no such
        // list here, so it goes; left in, holding the key would type a percent.
        val converted = converted("""name: x${'\n'}rows:${'\n'}  - letters: [[q, "%", a]]""")
        assertEquals(listOf("a"), converted.keys().first { it.label == "q" }.longPress)
    }

    // ---- rows this app draws itself ----

    @Test
    fun `an optional number row is dropped and reported`() {
        val converted = converted(
            """
            name: x
            rows:
              - numbers: "1 2 3"
              - letters: q w e
            """.trimIndent(),
        )
        assertEquals(1, converted.letters().size)
        assertTrue(
            converted.notes.any {
                it.stringRes == R.string.core_lang_foreign_number_row_dropped
            },
        )
    }

    @Test
    fun `a number row the layout insists on is kept`() {
        val converted = converted(
            """
            name: x
            numberRowMode: AlwaysEnabled
            rows:
              - numbers: "` 1 2"
              - letters: q w e
            """.trimIndent(),
        )
        assertEquals(2, converted.letters().size)
        assertEquals("`", converted.letters()[0].first().label)
    }

    // ---- the real repository ----

    @Test
    fun `real layouts convert, when a folder of them is pointed at`() {
        // Takes FUTO's layout repository from `FUTO_LAYOUTS` and skips silently
        // when it is unset, the way the Keyman corpus sweep does.
        val folder = System.getenv("FUTO_LAYOUTS")?.let(::File) ?: return
        // The sniff is the filter, exactly as it is in the app: that repository
        // also holds its own name tables and its issue templates, which are
        // YAML and are not layouts, and neither the app nor this test should
        // treat a file the user never picked as a failure.
        val files = folder.walkTopDown()
            .filter { it.isFile && (it.extension == "yaml" || it.extension == "yml") }
            .filter { FutoLayouts.looksLikeFutoLayout(it.readText()) }
            .toList()
        if (files.isEmpty()) return
        var converted = 0
        val failures = mutableListOf<String>()
        for (file in files) {
            val text = file.readText()
            val result = FutoLayouts.convert(text, file.name)
            if (result == null) {
                // A file whose every key names one of that keyboard's own
                // internal actions (its error screen does) has nothing left
                // once those are dropped, which is the right outcome rather
                // than a conversion failure.
                if (!text.contains(INTERNAL_ACTION_CODE)) failures += file.name
                continue
            }
            converted++
            val keys = checkNotNull(result.layout.layer(LayoutLayer.LETTERS)).rows.flatten()
            assertTrue("${file.name} has no keys", keys.isNotEmpty())
            assertTrue("${file.name} has a key with no width", keys.all { it.width > 0f })
            // The one rule the whole converter rests on: a repaired layout may
            // never carry an action this app cannot write back out.
            assertTrue(
                "${file.name} carries an unknown action",
                keys.none { it.action is KeyAction.Unknown },
            )
        }
        assertTrue("did not convert: $failures", failures.isEmpty())
        assertTrue("converted only $converted", converted > MIN_REAL_LAYOUTS)
    }

    private companion object {
        /** A key code naming one of the other keyboard's own internal actions. */
        const val INTERNAL_ACTION_CODE = "!code/action_"

        /** That repository holds well over this many; a floor, not a count. */
        const val MIN_REAL_LAYOUTS = 100
    }
}
