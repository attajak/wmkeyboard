package com.wasimaster.wmkeyboard.core.layout

import com.wasimaster.wmkeyboard.language.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutRepairTest {

    private fun letters(vararg rows: List<Key>) = LayoutSpec(
        id = "custom_1",
        name = "Test",
        layers = mapOf(LayoutLayer.LETTERS.key to LayerSpec(rows.toList())),
    )

    private fun LayoutSpec.lettersKeys() = layer(LayoutLayer.LETTERS)!!.rows.flatten()

    private val usableBottomRow = listOf(
        Key("⇧", action = KeyAction.Shift),
        Key(" ", action = KeyAction.Space),
        Key("⌫", action = KeyAction.Delete),
        Key("⏎", action = KeyAction.Enter),
    )

    /**
     * The test that proves the rules are right rather than merely strict. If a
     * shipped layout trips a blocking rule, the rule is wrong — a user cannot be
     * told that QWERTY is not good enough to turn on.
     */
    @Test
    fun `every built-in validates clean`() {
        for (spec in BuiltInLayouts.all) {
            val blocking = validateLayout(spec).filter { it.severity == LayoutSeverity.BLOCKING }
            assertTrue("${spec.id} should have no blocking findings but had $blocking", blocking.isEmpty())
            assertTrue("${spec.id} should be enableable", spec.canBeEnabled())
        }
    }

    @Test
    fun `every built-in survives repair unchanged`() {
        for (spec in BuiltInLayouts.all) {
            val repaired = spec.repair()
            assertEquals(
                "${spec.id} should need no repairs",
                emptyList<LayoutMessage>(),
                repaired.repairNotes,
            )
        }
    }

    /**
     * Issue #62: a secondary layout is a grid of the user's own design, not a
     * keyboard for prose, so neither pass demands a space bar of a macro pad.
     */
    @Test
    fun `a secondary layout is not forced to carry space, enter or delete`() {
        val pad = letters(listOf(Key("x"), Key("ABC", action = KeyAction.Letters))).copy(secondary = true)
        assertTrue(validateLayout(pad).none { it.severity == LayoutSeverity.BLOCKING })
        val repaired = pad.repair()
        assertEquals(pad, repaired.spec)
        assertTrue(repaired.repairNotes.isEmpty())
    }

    @Test
    fun `a secondary layout with no key that leaves it warns and nothing more`() {
        val pad = letters(listOf(Key("x"))).copy(secondary = true)
        val findings = validateLayout(pad)
        assertTrue(
            findings.any {
                it.severity == LayoutSeverity.WARNING &&
                    it.text == LayoutMessage(R.string.core_lang_layout_secondary_no_way_out_warning)
            },
        )
        assertTrue(findings.none { it.severity == LayoutSeverity.BLOCKING })
        assertTrue(pad.repair().repairNotes.isEmpty())
    }

    /**
     * Issue #63, per layout: a layout's own panel grid goes through the panel
     * rules, so its component cell survives the repair that would drop it as
     * a key typing nothing, an empty one is dropped to inherit the shared
     * panel, and its problems reach the layout's list.
     */
    @Test
    fun `a layout's own panel grid is repaired and validated as a panel`() {
        // With a language, so the round trip below is not also a migration.
        val base = letters(usableBottomRow).copy(langId = "en")
        val emojiGrid = LayerSpec(
            listOf(
                listOf(Key("", action = KeyAction.Field(PanelFieldKind.EMOJI_GRID), width = 10f)),
                listOf(Key("ABC", action = KeyAction.Letters), Key(" ", action = KeyAction.Space)),
            ),
        )
        val spec = base.copy(layers = base.layers + (PanelKind.EMOJI.layerKey to emojiGrid))
        assertEquals(emojiGrid, spec.panelLayer(PanelKind.EMOJI))
        assertTrue(validateLayout(spec).none { it.severity == LayoutSeverity.BLOCKING })
        val repaired = spec.repair()
        assertEquals(emojiGrid, repaired.spec.panelLayer(PanelKind.EMOJI))
        assertTrue(repaired.repairNotes.isEmpty())
        assertEquals(spec, LayoutCodec.decode(LayoutCodec.encode(spec)))

        // No emoji grid at all: blocked by the panel rules, and repair adds it.
        val noGrid = base.copy(
            layers = base.layers + (PanelKind.EMOJI.layerKey to LayerSpec(listOf(listOf(Key("ABC", action = KeyAction.Letters))))),
        )
        assertTrue(validateLayout(noGrid).any { it.severity == LayoutSeverity.BLOCKING && it.layer == null })
        val fixedGrid = noGrid.repair().spec.panelLayer(PanelKind.EMOJI)!!
        assertTrue(
            fixedGrid.rows.flatten().any { (it.action as? KeyAction.Field)?.kind == PanelFieldKind.EMOJI_GRID },
        )

        // Empty: dropped, so the layout inherits the shared panel instead.
        val empty = base.copy(layers = base.layers + (PanelKind.EMOJI.layerKey to LayerSpec(emptyList())))
        assertNull(empty.repair().spec.panelLayer(PanelKind.EMOJI))
        assertEquals(
            "shared fallback",
            BuiltInPanelLayouts.default(PanelKind.EMOJI),
            resolvePanelLayout(PanelKind.EMOJI, empty.repair().spec, emptyList()),
        )
    }

    /** Issue #60: the warning that ships with the "keep this layer open" switch. */
    @Test
    fun `a persistent layer warns that it needs a way out`() {
        val base = letters(usableBottomRow)
        val symbols = LayerSpec(
            listOf(listOf(Key("1"), Key("ABC", action = KeyAction.Letters))),
            persistent = true,
        )
        val spec = base.copy(layers = base.layers + (LayoutLayer.SYMBOLS.key to symbols))
        val findings = validateLayout(spec)
        assertTrue(
            findings.any {
                it.severity == LayoutSeverity.WARNING &&
                    it.text == LayoutMessage(
                        R.string.core_lang_layout_persistent_warning,
                        args = listOf(LayoutLayer.SYMBOLS.key),
                    )
            },
        )
        assertTrue(spec.canBeEnabled())
        // A persistent letters layer on an ordinary layout is where the keyboard
        // lands anyway, so it says nothing.
        val plain = base.copy(
            layers = mapOf(LayoutLayer.LETTERS.key to base.layer(LayoutLayer.LETTERS)!!.copy(persistent = true)),
        )
        assertTrue(
            validateLayout(plain).none {
                it.text.stringRes == R.string.core_lang_layout_persistent_warning
            },
        )
    }

    @Test
    fun `a missing delete key blocks and is repaired`() {
        val spec = letters(listOf(Key("a"), Key(" ", action = KeyAction.Space), Key("⏎", action = KeyAction.Enter)))

        assertFalse(spec.canBeEnabled())
        // Findings and repair notes are matched by the resource they name, not
        // by the English they happen to carry today.
        assertTrue(
            validateLayout(spec)
                .any { it.text == LayoutMessage(R.string.core_lang_layout_no_delete_error) },
        )

        val repaired = spec.repair()
        assertTrue(repaired.spec.lettersKeys().any { it.action == KeyAction.Delete })
        assertTrue(
            LayoutMessage(R.string.core_lang_repair_delete_key_added) in repaired.repairNotes,
        )
        assertTrue(repaired.spec.canBeEnabled())
    }

    @Test
    fun `a braille dot outside the range blocks and is clamped by repair`() {
        val spec = letters(
            listOf(Key("a"), Key("d", action = KeyAction.BrailleDot(9))) + usableBottomRow,
        )

        assertFalse(spec.canBeEnabled())

        val repaired = spec.repair()
        assertEquals(
            KeyAction.BrailleDot(6),
            repaired.spec.lettersKeys().first { it.action is KeyAction.BrailleDot }.action,
        )
        assertTrue(
            "repair notes were ${repaired.repairNotes}",
            repaired.repairNotes.any {
                it.stringRes == R.string.core_lang_repair_braille_dot_clamped
            },
        )
        assertTrue(repaired.spec.canBeEnabled())
    }

    /**
     * Issue #64. Quietly, unlike the braille dot: an odd column count draws a
     * strange popup rather than a layout that cannot be enabled, so it is
     * normalised without a note and without blocking.
     */
    @Test
    fun `an alternates column count outside the range is clamped quietly`() {
        val spec = letters(
            listOf(
                Key("a", longPress = listOf("@"), alternateColumns = 2),
                Key("b", longPress = listOf("#"), alternateColumns = 40),
                Key("c", longPress = listOf("$"), alternateColumns = -3),
                Key("d", longPress = listOf("%"), alternateColumns = 6),
            ) + usableBottomRow,
        )

        assertTrue(spec.canBeEnabled())

        val repaired = spec.repair()
        val keys = repaired.spec.lettersKeys().associateBy { it.label }
        assertEquals(3, keys.getValue("a").alternateColumns)
        assertEquals(11, keys.getValue("b").alternateColumns)
        assertEquals(0, keys.getValue("c").alternateColumns)
        assertEquals(6, keys.getValue("d").alternateColumns)
        assertTrue("repair notes were ${repaired.repairNotes}", repaired.repairNotes.isEmpty())
    }

    /**
     * Issue #231, and quietly for the same reason the column count is: a repeat
     * on a key that cannot use one is a flag nothing reads, not a grid that will
     * not draw. It has to go, though, or the key's hold is spent on nothing.
     */
    @Test
    fun `a repeat on a key that cannot use one is dropped quietly`() {
        val spec = letters(
            listOf(
                Key("\u2190", action = KeyAction.SendKey(21), repeatOnHold = true),
                Key("?123", action = KeyAction.Symbols, longPress = listOf("@"), repeatOnHold = true),
                Key("\u304b", flick = mapOf(FlickDirection.LEFT to "\u304d"), repeatOnHold = true),
            ) + usableBottomRow,
        )

        assertTrue(spec.canBeEnabled())

        val repaired = spec.repair()
        val keys = repaired.spec.lettersKeys().associateBy { it.label }
        assertTrue("a raw arrow is exactly what the switch is for", keys.getValue("\u2190").repeatOnHold)
        assertFalse("a layer switch has nothing to repeat", keys.getValue("?123").repeatOnHold)
        assertTrue("and it keeps the hold it had", keys.getValue("?123").opensAlternatesPopup())
        assertFalse("the flick pad owns the gesture", keys.getValue("\u304b").repeatOnHold)
        assertTrue("repair notes were ${repaired.repairNotes}", repaired.repairNotes.isEmpty())
    }

    /**
     * Repair's whole contract in one test: whatever went in, what comes out can
     * be turned on. A braille dot outside 1..6 used to slip through — validate
     * blocked it, repair had no branch for it, and the import sheet reported
     * nothing — so a file could be imported "clean" and still refuse to enable.
     */
    @Test
    fun `every blocking finding this file can produce is one repair fixes`() {
        val awkward = listOf(
            letters(listOf(Key("a"))),
            letters(emptyList()),
            letters(listOf(Key("a"), Key("z", action = KeyAction.Unknown("teleport")))),
            letters(listOf(Key("a"), Key("d", action = KeyAction.BrailleDot(0)))),
            letters(listOf(Key("a"), Key("d", action = KeyAction.BrailleDot(99)))),
            letters(listOf(Key("a", width = 0f))),
            letters(listOf(Key("a", width = Float.NaN))),
            letters(List(30) { Key("k$it") }),
            letters(*Array(11) { listOf(Key("r$it")) }),
            LayoutSpec(
                id = "custom_1",
                name = "Test",
                layers = mapOf(
                    LayoutLayer.SYMBOLS.key to LayerSpec(listOf(listOf(Key("!"), Key("@")))),
                ),
            ),
        )
        for (spec in awkward) {
            val repaired = spec.repair().spec
            val blocking =
                validateLayout(repaired).filter { it.severity == LayoutSeverity.BLOCKING }
            assertTrue("$spec repaired to $blocking", blocking.isEmpty())
        }
    }

    @Test
    fun `a missing shift key warns but does not block`() {
        val spec = letters(listOf(Key("a")) + usableBottomRow.filter { it.action != KeyAction.Shift })
        val findings = validateLayout(spec)
        assertTrue(
            findings.any { it.text == LayoutMessage(R.string.core_lang_layout_no_shift_warning) },
        )
        assertTrue("a shift-less layout is still usable", spec.canBeEnabled())
    }

    @Test
    fun `an unknown action blocks and the key is dropped by repair`() {
        val spec = letters(listOf(Key("a"), Key("z", action = KeyAction.Unknown("teleport"))) + usableBottomRow)

        assertFalse(spec.canBeEnabled())

        val repaired = spec.repair()
        assertTrue(repaired.spec.lettersKeys().none { it.action is KeyAction.Unknown })
        // The dropped tag rides in the note's arguments, which are data rather
        // than words, so this still names the offending key exactly.
        assertTrue(
            "repair notes were ${repaired.repairNotes}",
            LayoutMessage(
                R.string.core_lang_repair_unknown_key_deleted,
                args = listOf(LayoutLayer.LETTERS.key, "teleport"),
            ) in repaired.repairNotes,
        )
        assertEquals("only the offending key goes", 1, repaired.spec.lettersKeys().count { it.label == "a" })
    }

    @Test
    fun `a non-finite or zero width is clamped to one`() {
        val spec = letters(listOf(Key("a", width = 0f), Key("b", width = Float.NaN)) + usableBottomRow)
        val repaired = spec.repair()
        assertEquals(1f, repaired.spec.lettersKeys().first { it.label == "a" }.width, 0.001f)
        assertEquals(1f, repaired.spec.lettersKeys().first { it.label == "b" }.width, 0.001f)
    }

    @Test
    fun `an absurdly wide row is scaled back into the grid`() {
        val fat = List(10) { Key("k$it", width = 20f) }
        val repaired = letters(fat, usableBottomRow).repair()
        val total = repaired.spec.layer(LayoutLayer.LETTERS)!!.rows[0].sumOf { it.width.toDouble() }
        assertTrue("row totalled $total, should be scaled to fit", total <= 40.001)
    }

    @Test
    fun `an empty letters layer is dropped so the built-in grid is inherited`() {
        val spec = letters(emptyList())
        val repaired = spec.repair()
        assertNull("dropping the layer restores the shipped grid", repaired.spec.layer(LayoutLayer.LETTERS))
        assertTrue(
            "repair notes were ${repaired.repairNotes}",
            LayoutMessage(
                R.string.core_lang_repair_layer_replaced,
                args = listOf(LayoutLayer.LETTERS.key),
            ) in repaired.repairNotes,
        )
    }

    @Test
    fun `a symbols layer with no way back gets one`() {
        val spec = LayoutSpec(
            id = "custom_1",
            name = "Test",
            layers = mapOf(
                LayoutLayer.LETTERS.key to LayerSpec(listOf(listOf(Key("a")) + usableBottomRow)),
                LayoutLayer.SYMBOLS.key to LayerSpec(listOf(listOf(Key("!"), Key("?")))),
            ),
        )

        assertFalse("being stranded on the symbols layer blocks", spec.canBeEnabled())

        val repaired = spec.repair()
        val symbols = repaired.spec.layer(LayoutLayer.SYMBOLS)!!.rows.flatten()
        assertTrue(symbols.any { it.action == KeyAction.Letters })
        assertTrue(repaired.spec.canBeEnabled())
    }

    @Test
    fun `a blank text key is dropped`() {
        val spec = letters(listOf(Key("a"), Key("")) + usableBottomRow)
        val repaired = spec.repair()
        assertTrue(repaired.spec.lettersKeys().none { it.label.isEmpty() && it.action == KeyAction.Text })
    }

    @Test
    fun `an empty long-press entry is dropped without losing the key`() {
        val spec = letters(listOf(Key("a", longPress = listOf("@", "", "à"))) + usableBottomRow)
        val key = spec.repair().spec.lettersKeys().first { it.label == "a" }
        assertEquals(listOf("@", "à"), key.longPress)
    }

    /**
     * Discussion #103: the letter set and the anchor a key commits have to agree,
     * and until this rule existed a hand-written file that got it wrong was
     * accepted in silence and then typed words nobody had pressed the keys for.
     */
    @Test
    fun `a key that stands for several letters is made to type the first of them`() {
        val spec = letters(listOf(Key("QW", output = "z", letters = "qw")) + usableBottomRow)
        val repaired = spec.repair()
        val key = repaired.spec.lettersKeys().first { it.label == "QW" }
        assertEquals("qw", key.letters)
        assertEquals("q", key.output)
        assertTrue(
            "the fix has to be reported, or the file's author never learns of it",
            repaired.repairNotes.any { it.stringRes == R.string.core_lang_repair_key_letters_anchored },
        )
    }

    @Test
    fun `a letter set is cleaned of repeats, case and anything that is not a letter`() {
        val spec = letters(listOf(Key("QW", letters = "Q w2q")) + usableBottomRow)
        val key = spec.repair().spec.lettersKeys().first { it.label == "QW" }
        assertEquals("qw", key.letters)
        assertEquals("q", key.output)
    }

    @Test
    fun `a letter set holding no letters at all leaves an ordinary key`() {
        val spec = letters(listOf(Key("1", output = "1", letters = "123")) + usableBottomRow)
        val repaired = spec.repair()
        val key = repaired.spec.lettersKeys().first { it.label == "1" }
        assertNull(key.letters)
        assertEquals("the output is the key's own, untouched", "1", key.output)
        assertTrue(
            repaired.repairNotes.any { it.stringRes == R.string.core_lang_repair_key_letters_dropped },
        )
    }

    @Test
    fun `an ordinary key is left alone by the letter rule`() {
        val spec = letters(listOf(Key("a"), Key("b", output = "B")) + usableBottomRow)
        val repaired = spec.repair()
        assertTrue(
            "no letter note belongs on a layout with no letter sets",
            repaired.repairNotes.none {
                it.stringRes == R.string.core_lang_repair_key_letters_anchored ||
                    it.stringRes == R.string.core_lang_repair_key_letters_dropped
            },
        )
        assertEquals("B", repaired.spec.lettersKeys().first { it.label == "b" }.output)
    }

    @Test
    fun `validate never mutates the layout`() {
        val spec = letters(listOf(Key("a", width = 0f), Key("z", action = KeyAction.Unknown("x"))))
        val before = spec.copy()
        validateLayout(spec)
        assertEquals("validate is pure; the editor calls it on every keystroke", before, spec)
    }
}
