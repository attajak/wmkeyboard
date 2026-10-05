package com.wasimaster.wmkeyboard.core.layout

import com.wasimaster.wmkeyboard.language.R
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Reading a Samsung Keyboard layout shared from Keys Cafe.
 *
 * The fixtures are hand-written models, encrypted here by the reader's own
 * [KeysCafeLayouts.encode], so the test owns both directions of the format and
 * no third party's export is checked in. The shapes are the ones the published
 * description gives; a real export is swept only when `KEYS_CAFE_LAYOUTS`
 * points at a folder of them.
 */
class KeysCafeLayoutTest {

    // ---- fixtures ----

    private fun key(
        label: String,
        vararg codes: Int,
        width: Float = UNIT,
        type: Int = TYPE_LETTER,
        upper: String? = null,
        bubbles: List<String> = emptyList(),
        upperBubbles: List<String> = emptyList(),
        hint: String? = null,
    ): JsonObject = buildJsonObject {
        putJsonObject("KEY") {
            putJsonObject("keyAttribute") { put("keyType", type) }
            putJsonObject("normalKey") {
                putJsonObject("keyCodeLabel") {
                    putJsonArray("keyCodes") { codes.forEach { add(it) } }
                    put("keyLabel", label)
                }
                if (upper != null) {
                    putJsonObject("upperKeyCodeLabel") {
                        putJsonArray("keyCodes") { add(upper.codePointAt(0)) }
                        put("keyLabel", upper)
                    }
                }
            }
            putJsonArray("normalBubbles") {
                for (b in bubbles) addJsonObject { putJsonArray("keyCodes") { add(b.codePointAt(0)) }; put("keyLabel", b) }
            }
            if (upperBubbles.isNotEmpty()) {
                putJsonArray("upperBubbles") {
                    for (b in upperBubbles) addJsonObject { putJsonArray("keyCodes") { add(b.codePointAt(0)) }; put("keyLabel", b) }
                }
            }
            if (hint != null) putJsonObject("secondaryKey") { put("secondarySymbol", hint) }
            putJsonObject("size") { put("width", width); put("height", 0.155f) }
            put("type", "KEY")
        }
    }

    private fun row(kind: Int, vararg keys: JsonObject): JsonObject = buildJsonObject {
        putJsonObject("ROW") {
            put("rowType", kind)
            putJsonArray("elements") { keys.forEach { add(it) } }
            put("type", "ROW")
        }
    }

    private fun share(
        vararg rows: JsonObject,
        name: String = "My grid",
        language: String = "en",
        country: String = "US",
    ): String {
        val model = buildJsonObject {
            putJsonArray("keyboards") {
                addJsonObject {
                    putJsonObject("defaultKeyboard") {
                        putJsonArray("elements") { rows.forEach { add(it) } }
                        put("type", "KEYBOARD")
                    }
                }
            }
            put("type", "KEYBOARD_SET")
        }
        val outer = buildJsonObject {
            put("keyboardName", name)
            put("languageCode", language)
            put("countryCode", country)
            put("inputType", "QWERTY_DEFAULT")
            put("model", model.toString())
            put("version", 1)
        }
        return KeysCafeLayouts.encode(outer.toString())
    }

    private fun shift() = key("", CODE_SHIFT, width = 0.12f, type = TYPE_FUNCTION)
    private fun delete() = key("", CODE_DELETE, width = 0.12f, type = TYPE_FUNCTION)
    private fun symbols() = key("", CODE_SYMBOLS, width = 0.12f, type = TYPE_BOTTOM)
    private fun space() = key("", 32, width = 0.4f, type = TYPE_BOTTOM)
    private fun enter() = key("", 10, width = 0.16f, type = TYPE_BOTTOM)
    private fun digit(d: String, vararg bubbles: String) =
        key(d, d.codePointAt(0), type = TYPE_DIGIT, bubbles = bubbles.toList())

    /** A whole Keys Cafe grid: digits, two letter rows with the frame, a bottom row. */
    private fun fullGrid(): String = share(
        row(ROW_NUMBERS, digit("1", "¹", "½"), digit("2", "²"), digit("3")),
        row(ROW_LETTERS, key("q", 'q'.code, upper = "Q"), key("e", 'e'.code, upper = "E", bubbles = listOf("è", "é"), hint = "3")),
        row(ROW_LETTERS, shift(), key("i", 'i'.code, upper = "İ"), delete()),
        row(ROW_BOTTOM, symbols(), key(",", ','.code, type = TYPE_SYMBOL), key("z", 'z'.code, upper = "Z"), space(), key(".", '.'.code, type = TYPE_SYMBOL), enter()),
    )

    private fun converted(kcf: String): ConvertedLayout =
        checkNotNull(KeysCafeLayouts.convert(kcf, "grid.kcf")) { "did not convert" }

    private fun ConvertedLayout.letters(): LayerSpec = checkNotNull(layout.layer(LayoutLayer.LETTERS))

    private fun ConvertedLayout.keys(): List<Key> = letters().rows.flatten()

    // ---- the envelope ----

    @Test
    fun `a share file decrypts and names its layout`() {
        val converted = converted(fullGrid())
        assertEquals("My grid", converted.layout.name)
        assertEquals(ForeignSource.KEYS_CAFE, converted.source)
    }

    @Test
    fun `the file name stands in when the file names nothing`() {
        assertEquals("grid", converted(share(row(ROW_LETTERS, key("a", 'a'.code)), name = " ")).layout.name)
    }

    @Test
    fun `it is told apart by its characters alone`() {
        assertTrue(KeysCafeLayouts.looksLikeKcf(fullGrid()))
        // The three text formats the other readers take.
        assertFalse(KeysCafeLayouts.looksLikeKcf("""[[{"label":"a"}]]"""))
        assertFalse(KeysCafeLayouts.looksLikeKcf("name: x\nrows:\n  - letters: a"))
        assertFalse(KeysCafeLayouts.looksLikeKcf("q w e\n\na s d"))
        // Padding is only ever at the end.
        assertFalse(KeysCafeLayouts.looksLikeKcf("QUJD=QUJDREVGR0hJSktMTU5PUFFSU1RV"))
        assertFalse(KeysCafeLayouts.looksLikeKcf(""))
    }

    @Test
    fun `base64 that is not a share file converts to nothing`() {
        // Random bytes: the padding check fails, or the plaintext is not JSON.
        assertNull(KeysCafeLayouts.convert("QUJDREVGR0hJSktMTU5PUFFSU1RVVldYWVowMTIzNDU2Nzg5YWJjZGVmZ2hpamts", "x.kcf"))
        // A truncated export.
        val whole = fullGrid().filterNot { it.isWhitespace() }
        assertNull(KeysCafeLayouts.convert(whole.take(whole.length / 2 / 4 * 4), "x.kcf"))
        // Something else encrypted under the same scheme would still have to be
        // a grid; a bare object is not one.
        assertNull(KeysCafeLayouts.convert(KeysCafeLayouts.encode("""{"hello":"world"}"""), "x.kcf"))
    }

    @Test
    fun `line wrapping is immaterial`() {
        val wrapped = fullGrid()
        assertTrue(wrapped.contains('\n'))
        assertEquals(converted(wrapped).keys().map { it.label }, converted(wrapped.filterNot { it.isWhitespace() }).keys().map { it.label })
    }

    // ---- the grid ----

    @Test
    fun `the grid is kept exactly as laid out`() {
        val converted = converted(fullGrid())
        val layer = converted.letters()
        // Two letter rows and the file's own bottom row; no house row added.
        assertEquals(3, layer.rows.size)
        assertTrue("the bottom row must be left to the file", layer.bottomRowAsLaidOut)
        assertEquals(
            listOf(KeyAction.Symbols, KeyAction.Text, KeyAction.Text, KeyAction.Space, KeyAction.Text, KeyAction.Enter),
            layer.rows.last().map { it.action },
        )
        // The letter beside the space bar stays there.
        assertEquals("z", layer.rows.last()[2].label)
        // No 🌐 was slipped in.
        assertTrue(converted.keys().none { it.action == KeyAction.LanguageSwitch })
    }

    @Test
    fun `the bottom row's comma and full stop take their slots`() {
        val bottom = converted(fullGrid()).letters().rows.last()
        assertEquals(KeyRole.Comma, bottom[1].role)
        assertEquals(KeyRole.Period, bottom[4].role)
        // A comma in a letter row is a letter key that happens to be a comma.
        val home = converted(share(row(ROW_LETTERS, key(",", ','.code, type = TYPE_SYMBOL), key("a", 'a'.code), space(), delete())))
        assertNull(home.keys().first { it.label == "," }.role)
    }

    @Test
    fun `the number row becomes the layout's own`() {
        val converted = converted(fullGrid())
        val numberRow = checkNotNull(converted.letters().numberRow)
        assertEquals(listOf("1", "2", "3"), numberRow.map { it.label })
        assertEquals(listOf("¹", "½"), numberRow[0].longPress)
        // Not a letter row as well.
        assertTrue(converted.letters().rows.none { row -> row.any { it.label == "1" } })
        assertTrue(converted.notes.any { it.stringRes == R.string.core_lang_foreign_number_row_kept })
    }

    @Test
    fun `the frame is added only when the file took it away`() {
        // No space bar and no delete key: this app's frame goes round the letters.
        val converted = converted(share(row(ROW_LETTERS, key("a", 'a'.code), key("b", 'b'.code))))
        val layer = converted.letters()
        assertFalse(layer.bottomRowAsLaidOut)
        assertTrue(layer.rows.last().any { it.action == KeyAction.Space })
        assertTrue(converted.keys().any { it.action == KeyAction.Delete })
        assertTrue(converted.keys().any { it.action == KeyAction.Shift })
    }

    @Test
    fun `repair has nothing left to do`() {
        val converted = converted(fullGrid())
        assertTrue(converted.layout.repair().repairNotes.isEmpty())
        assertTrue(converted.keys().none { it.action is KeyAction.Unknown })
    }

    // ---- keys ----

    @Test
    fun `the function keys are read by their codes`() {
        val keys = converted(fullGrid()).keys()
        assertEquals(1, keys.count { it.action == KeyAction.Shift })
        assertEquals(1, keys.count { it.action == KeyAction.Delete })
        assertEquals(1, keys.count { it.action == KeyAction.Symbols })
        assertEquals(1, keys.count { it.action == KeyAction.Space })
        assertEquals(1, keys.count { it.action == KeyAction.Enter })
    }

    @Test
    fun `a code this build does not know is removed and named`() {
        val converted = converted(
            share(row(ROW_LETTERS, key("a", 'a'.code), key("", -108, type = TYPE_BOTTOM), space(), delete())),
        )
        assertEquals(listOf(-108), converted.unmapped)
        assertTrue(converted.keys().none { it.action !is KeyAction.Text && it.action !in FRAME })
        val note = converted.notes.first { it.pluralsRes == R.plurals.core_lang_foreign_keys_dropped }
        assertTrue(note.args[1].toString().contains("-108"))
    }

    @Test
    fun `a plain capital is left to the shift key, anything else is kept`() {
        val keys = converted(fullGrid()).keys()
        assertNull(keys.first { it.label == "q" }.shiftLabel)
        assertEquals("İ", keys.first { it.label == "i" }.shiftLabel)
    }

    @Test
    fun `the corner symbol is the first press-and-hold entry`() {
        val e = converted(fullGrid()).keys().first { it.label == "e" }
        assertEquals(listOf("3", "è", "é"), e.longPress)
    }

    @Test
    fun `a key that types several code points keeps them`() {
        val keys = converted(
            share(row(ROW_LETTERS, key("ch", 'c'.code, 'h'.code), key("", 'a'.code, 'b'.code), space(), delete())),
        ).keys()
        val ch = keys.first { it.label == "ch" }
        assertNull("label and output are the same text", ch.output)
        assertEquals("ab", keys.first { it.label == "ab" }.label)
    }

    @Test
    fun `shifted popups that are only capitals are no loss`() {
        val same = converted(
            share(row(ROW_LETTERS, key("y", 'y'.code, bubbles = listOf("ý"), upperBubbles = listOf("Ý")), space(), delete())),
        )
        assertTrue(same.notes.none { it.pluralsRes == R.plurals.core_lang_foreign_shifted_popups_dropped })
        val different = converted(
            share(row(ROW_LETTERS, key("s", 's'.code, bubbles = listOf("ß"), upperBubbles = listOf("§")), space(), delete())),
        )
        val note = different.notes.first { it.pluralsRes == R.plurals.core_lang_foreign_shifted_popups_dropped }
        assertEquals(1, note.quantity)
    }

    // ---- widths ----

    @Test
    fun `widths are measured against the file's own plain key`() {
        val keys = converted(fullGrid()).keys()
        assertEquals(1f, keys.first { it.label == "q" }.width, 0.001f)
        assertEquals(1.5f, keys.first { it.action == KeyAction.Shift }.width, 0.001f)
        assertEquals(5f, keys.first { it.action == KeyAction.Space }.width, 0.001f)
        assertEquals(2f, keys.first { it.action == KeyAction.Enter }.width, 0.001f)
        // In this app's unit already, so the fraction-to-grid scaling must not
        // have run over them again.
        assertTrue(converted(fullGrid()).notes.none { it.stringRes == R.string.core_lang_foreign_widths_scaled })
    }

    // ---- the language ----

    @Test
    fun `the declared language seeds the picker`() {
        assertTrue(converted(fullGrid()).guessedLangId in setOf("en", "en_us"))
        // Cyrillic letters under an English tag: the tag is a fact and wins.
        val tagged = converted(share(row(ROW_LETTERS, key("й", 'й'.code), space(), delete()), language = "ru", country = "RU"))
        assertTrue(tagged.guessedLangId.startsWith("ru"))
    }

    @Test
    fun `a language this app does not have falls back to the guess`() {
        val converted = converted(share(row(ROW_LETTERS, key("a", 'a'.code), space(), delete()), language = "zz", country = ""))
        assertEquals("en", converted.guessedLangId)
    }

    // ---- real exports ----

    @Test
    fun `real exports convert, when a folder of them is pointed at`() {
        // Takes a folder of `.kcf` files from `KEYS_CAFE_LAYOUTS` and skips
        // silently when it is unset, the way the FUTO and Keyman sweeps do.
        val folder = System.getenv("KEYS_CAFE_LAYOUTS")?.let(::File) ?: return
        val files = folder.walkTopDown().filter { it.isFile && it.extension.equals("kcf", ignoreCase = true) }.toList()
        if (files.isEmpty()) return
        val failures = mutableListOf<String>()
        for (file in files) {
            val text = file.readText()
            assertTrue("${file.name} does not look like a share file", KeysCafeLayouts.looksLikeKcf(text))
            val result = KeysCafeLayouts.convert(text, file.name)
            if (result == null) {
                failures += file.name
                continue
            }
            val keys = result.keys()
            assertTrue("${file.name} has no keys", keys.isNotEmpty())
            assertTrue("${file.name} has a key with no width", keys.all { it.width > 0f })
            assertTrue("${file.name} carries an unknown action", keys.none { it.action is KeyAction.Unknown })
            assertTrue("${file.name} lost its frame", result.letters().bottomRowAsLaidOut)
        }
        assertTrue("did not convert: $failures", failures.isEmpty())
    }

    private companion object {
        const val UNIT = 0.08f
        const val TYPE_LETTER = 1
        const val TYPE_SYMBOL = 2
        const val TYPE_DIGIT = 3
        const val TYPE_FUNCTION = 4
        const val TYPE_BOTTOM = 65540
        const val ROW_LETTERS = 1
        const val ROW_NUMBERS = 2
        const val ROW_BOTTOM = 3
        const val CODE_DELETE = -5
        const val CODE_SHIFT = -400
        const val CODE_SYMBOLS = -102
        val FRAME = setOf(KeyAction.Space, KeyAction.Delete, KeyAction.Shift, KeyAction.Enter, KeyAction.Symbols)
    }
}
