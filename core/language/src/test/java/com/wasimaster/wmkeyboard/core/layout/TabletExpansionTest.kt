package com.wasimaster.wmkeyboard.core.layout

import com.wasimaster.wmkeyboard.core.settings.DeviceForm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The golden grids here are the contract. The transform's widths are computed
 * rather than written down, so an arithmetic change would otherwise slide
 * through silently and only show up as a keyboard that looks slightly wrong on
 * a device nobody on the team is holding.
 */
class TabletExpansionTest {

    private val qwerty = BuiltInLayouts.QWERTY.compile(LayoutLayer.LETTERS)

    private fun List<Key>.spell() = joinToString(" ") { key ->
        val label = when (key.action) {
            KeyAction.Shift -> "⇧"
            KeyAction.CapsLock -> "⇪"
            KeyAction.Delete -> "⌫"
            KeyAction.Enter -> "⏎"
            KeyAction.Space -> "␣"
            KeyAction.Emoji -> "☺"
            KeyAction.Symbols -> "?123"
            KeyAction.LanguageSwitch -> "🌐"
            else -> key.label
        }
        "$label:${key.width}"
    }

    private fun List<Key>.width() = sumOf { it.width.toDouble() }.toFloat()

    // -- the golden grids ---------------------------------------------------

    @Test
    fun `large tablet with the number row on matches the reference grid`() {
        val rows = qwerty.expandForTablet(DeviceForm.LARGE_TABLET, numberRowShown = true).rows

        assertEquals(4, rows.size)
        assertEquals(
            "⇥:1.0 q:1.0 w:1.0 e:1.0 r:1.0 t:1.0 y:1.0 u:1.0 i:1.0 o:1.0 p:1.0 \\:1.0",
            rows[0].spell(),
        )
        assertEquals(
            "⇪:1.5 a:1.0 s:1.0 d:1.0 f:1.0 g:1.0 h:1.0 j:1.0 k:1.0 l:1.0 ⏎:1.5",
            rows[1].spell(),
        )
        assertEquals(
            "⇧:1.5 z:1.0 x:1.0 c:1.0 v:1.0 b:1.0 n:1.0 m:1.0 ,:1.0 .:1.0 ⇧:1.5",
            rows[2].spell(),
        )
        assertEquals(
            "?123:1.5 ☺:1.0 🌐:1.0 ␣:5.0 ←:1.0 →:1.0 ?123:1.5",
            rows[3].spell(),
        )
        // Every row exactly the grid width, which is what puts `,` under `k`
        // and the mirrored shift under enter.
        rows.forEach { assertEquals(12f, it.width(), 0.001f) }
        assertEquals(12f, tabletGridWidth(qwerty, DeviceForm.LARGE_TABLET)!!, 0.001f)
    }

    @Test
    fun `small tablet keeps the period on the bottom row and the arrows after it`() {
        val rows = qwerty.expandForTablet(DeviceForm.SMALL_TABLET, numberRowShown = true).rows

        // Rows A and B are identical to the large form.
        assertEquals(
            qwerty.expandForTablet(DeviceForm.LARGE_TABLET, true).rows[0].spell(),
            rows[0].spell(),
        )
        assertEquals(
            qwerty.expandForTablet(DeviceForm.LARGE_TABLET, true).rows[1].spell(),
            rows[1].spell(),
        )
        assertEquals(
            "⇧:1.5 z:1.0 x:1.0 c:1.0 v:1.0 b:1.0 n:1.0 m:1.0 ,:1.0 ⇧:2.5",
            rows[2].spell(),
        )
        assertEquals("?123:1.5 ☺:1.0 🌐:1.0 ␣:5.5 .:1.0 ←:1.0 →:1.0", rows[3].spell())
        rows.forEach { assertEquals(12f, it.width(), 0.001f) }
    }

    @Test
    fun `with the number row off backspace stays on the shift row`() {
        val rows = qwerty.expandForTablet(DeviceForm.LARGE_TABLET, numberRowShown = false).rows

        assertEquals(
            "⇧:1.5 z:1.0 x:1.0 c:1.0 v:1.0 b:1.0 n:1.0 m:1.0 ,:1.0 .:1.0 ⌫:1.5",
            rows[2].spell(),
        )
        assertEquals(
            "the mirrored shift is what backspace would have displaced",
            1,
            rows.sumOf { row -> row.count { it.action == KeyAction.Shift } },
        )
        rows.forEach { assertEquals(12f, it.width(), 0.001f) }
    }

    @Test
    fun `the digit row gains a backtick and a backspace`() {
        val digits = Layouts.SYMBOLS.rows.first()
        val expanded = digits.expandNumberRowForTablet()

        assertEquals(digits.size + 2, expanded.size)
        assertEquals("`", expanded.first().label)
        assertEquals(KeyAction.Delete, expanded.last().action)
        // Laid out against its own key count, so a wide backspace here would
        // push every digit off the column it belongs over.
        expanded.forEach { assertEquals(1f, it.width, 0.001f) }
    }

    /**
     * The digit row and the body have to end up on the same column pitch, and
     * they get there by different routes: the body is laid out against the grid
     * width this file computes, the digit row against its own key *count* (see
     * `rememberKeyGrid`). Twelve unit-width keys is what makes the two agree, and
     * it is the reason the relocated backspace up there must not be 1.5 wide.
     */
    @Test
    fun `the digit row lands on the same pitch as the body`() {
        val body = qwerty.expandForTablet(DeviceForm.LARGE_TABLET, numberRowShown = true)
        val digits = Layouts.SYMBOLS.rows.first().expandNumberRowForTablet()

        assertEquals(12f, tabletGridWidth(qwerty, DeviceForm.LARGE_TABLET)!!, 0.001f)
        assertEquals("the digit row's own grid weight is its key count", 12, digits.size)
        assertEquals(12f, digits.width(), 0.001f)
        body.rows.forEach { assertEquals(12f, it.width(), 0.001f) }
    }

    @Test
    fun `the digit row does not gain a second backspace or backtick`() {
        val already = listOf(Key("`")) + Layouts.SYMBOLS.rows.first() + Key("⌫", action = KeyAction.Delete)
        assertEquals(already, already.expandNumberRowForTablet())
    }

    // -- invariants over every built-in -------------------------------------

    @Test
    fun `a phone gets back the very same instance`() {
        for (spec in BuiltInLayouts.all) {
            for (layer in LayoutLayer.entries) {
                val grid = spec.compile(layer)
                assertSame(
                    "${spec.id}/${layer.key}",
                    grid,
                    grid.expandForTablet(DeviceForm.PHONE, numberRowShown = true),
                )
            }
            assertNull(tabletGridWidth(spec.compile(LayoutLayer.LETTERS), DeviceForm.PHONE))
        }
    }

    @Test
    fun `every built-in survives the transform intact`() {
        for (spec in BuiltInLayouts.all) {
            val before = spec.compile(LayoutLayer.LETTERS)
            for (form in listOf(DeviceForm.SMALL_TABLET, DeviceForm.LARGE_TABLET)) {
                for (numberRow in listOf(true, false)) {
                    val after = before.expandForTablet(form, numberRow)
                    val where = "${spec.id} $form numberRow=$numberRow"
                    if (after === before) continue

                    // The constraint the whole feature rests on.
                    assertEquals("$where: row count", before.rows.size, after.rows.size)
                    assertEquals("$where: row heights", before.rowHeights, after.rowHeights)

                    after.rows.forEach { row ->
                        assertTrue("$where: keys per row", row.size <= MaxKeysPerRow)
                        assertTrue("$where: row width", row.width() <= MaxRowWidth)
                        row.forEach {
                            assertTrue("$where: key width ${it.width}", it.width > 0f)
                            assertTrue("$where: key width ${it.width}", it.width <= MaxKeyWidth)
                        }
                    }

                    fun count(pred: (Key) -> Boolean) = after.rows.sumOf { it.count(pred) }
                    // Backspace leaves the body only when the digit row is
                    // there to receive it — expandNumberRowForTablet supplies
                    // that one, and the pair has to agree or the keyboard has
                    // no backspace at all.
                    assertEquals(
                        "$where: delete — on the digit row when it is drawn",
                        if (numberRow) 0 else 1,
                        count { it.action == KeyAction.Delete },
                    )
                    assertEquals("$where: enter", 1, count { it.action == KeyAction.Enter })
                    assertEquals("$where: space", 1, count { it.action == KeyAction.Space })
                    assertEquals(
                        "$where: shift — mirrored only when backspace moved away",
                        if (numberRow) 2 else 1,
                        count { it.action == KeyAction.Shift },
                    )
                    assertEquals("$where: emoji", 1, count { it.action == KeyAction.Emoji })

                    // No letter may be lost: the glide decoder scores against
                    // key centres, and a missing letter makes it confident and
                    // wrong rather than quiet.
                    val letters = { grid: KeyboardLayout ->
                        grid.rows.flatten()
                            .filter { it.action == KeyAction.Text }
                            .map { it.output ?: it.label }
                            .sorted()
                    }
                    assertTrue(
                        "$where: text keys lost",
                        letters(after).containsAll(letters(before)),
                    )

                    // Relocated punctuation must carry its role explicitly —
                    // roleIn only infers positionally on the last row, so a
                    // layout relying on that fallback would lose its email and
                    // URI adaptation the moment the comma moved up.
                    val shiftRow = after.rows[after.rows.lastIndex - 1]
                    assertTrue(
                        "$where: relocated comma keeps its role",
                        shiftRow.any { it.role == KeyRole.Comma },
                    )
                }
            }
        }
    }

    // -- the gate -----------------------------------------------------------

    private fun grid(vararg rows: List<Key>) = KeyboardLayout("t", rows.toList())

    private val plainRows = listOf(
        listOf(Key("q"), Key("w"), Key("e")),
        listOf(Key("a"), Key("s"), Key("d")),
        listOf(Key("⇧", action = KeyAction.Shift), Key("z"), Key("⌫", action = KeyAction.Delete)),
        listOf(
            Key("?123", action = KeyAction.Symbols),
            Key(","),
            Key(" ", action = KeyAction.Space),
            Key("."),
            Key("⏎", action = KeyAction.Enter),
        ),
    )

    private fun declines(message: String, layout: KeyboardLayout) {
        assertNull(message, tabletGridWidth(layout, DeviceForm.LARGE_TABLET))
        assertSame(message, layout, layout.expandForTablet(DeviceForm.LARGE_TABLET, true))
    }

    @Test
    fun `the reference grid for the gate tests is itself eligible`() {
        assertNotNull(tabletGridWidth(grid(*plainRows.toTypedArray()), DeviceForm.LARGE_TABLET))
    }

    @Test
    fun `a flick pad is declined`() {
        val rows = plainRows.toMutableList()
        rows[0] = listOf(Key("あ", flick = mapOf(FlickDirection.LEFT to "い")), Key("w"), Key("e"))
        declines("kana flick", grid(*rows.toTypedArray()))
    }

    @Test
    fun `a braille grid is declined`() {
        val rows = plainRows.toMutableList()
        rows[0] = listOf(Key("1", action = KeyAction.BrailleDot(1)), Key("w"), Key("e"))
        declines("braille", grid(*rows.toTypedArray()))
    }

    @Test
    fun `a morse grid is declined`() {
        val rows = plainRows.toMutableList()
        rows[0] = listOf(Key("·", action = KeyAction.MorseDot), Key("w"), Key("e"))
        declines("morse", grid(*rows.toTypedArray()))
    }

    @Test
    fun `an unknown action is declined`() {
        val rows = plainRows.toMutableList()
        rows[0] = listOf(Key("?", action = KeyAction.Unknown("teleport")), Key("w"), Key("e"))
        declines("unrepaired layout", grid(*rows.toTypedArray()))
    }

    @Test
    fun `a three row grid is declined`() {
        declines("too short", grid(*plainRows.drop(1).toTypedArray()))
    }

    @Test
    fun `two shift keys are declined`() {
        val rows = plainRows.toMutableList()
        rows[2] = rows[2] + Key("⇧", action = KeyAction.Shift)
        declines("ambiguous shift", grid(*rows.toTypedArray()))
    }

    @Test
    fun `a shift key on the bottom row is declined`() {
        val rows = plainRows.toMutableList()
        rows[2] = rows[2].filterNot { it.action == KeyAction.Shift }
        rows[3] = rows[3] + Key("⇧", action = KeyAction.Shift)
        declines("shift out of place", grid(*rows.toTypedArray()))
    }

    @Test
    fun `a grid with no comma is declined`() {
        val rows = plainRows.toMutableList()
        rows[3] = rows[3].filterNot { it.label == "," }
        declines("no comma slot", grid(*rows.toTypedArray()))
    }

    @Test
    fun `a grid with no symbols key is declined`() {
        val rows = plainRows.toMutableList()
        rows[3] = rows[3].filterNot { it.action == KeyAction.Symbols }
        declines("no layer key", grid(*rows.toTypedArray()))
    }

    @Test
    fun `the symbols layer is declined because it has no shift`() {
        declines("symbols layer", BuiltInLayouts.QWERTY.compile(LayoutLayer.SYMBOLS))
    }
}
