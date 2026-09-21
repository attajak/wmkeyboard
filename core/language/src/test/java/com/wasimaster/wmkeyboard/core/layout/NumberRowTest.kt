package com.wasimaster.wmkeyboard.core.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rows the keyboard draws around a layer's grid are layout data (issue
 * #273): the built-in defaults live in core, and a layer can carry its own.
 */
class NumberRowTest {
    @Test
    fun `symbols 2 defaults to the arrow row and the rest to the digits`() {
        assertEquals(BuiltInLayouts.SYMBOLS_2_NUMBER_ROW, BuiltInLayouts.defaultNumberRow(LayoutLayer.SYMBOLS_SHIFTED))
        val digits = BuiltInLayouts.defaultNumberRow(LayoutLayer.LETTERS).map { it.label }
        assertEquals(listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"), digits)
    }

    @Test
    fun `the built-in symbols layer leads with the digits it gives up`() {
        assertTrue(leadsWithDigitRow(BuiltInLayouts.default.compile(LayoutLayer.SYMBOLS).rows))
        assertFalse(leadsWithDigitRow(listOf(BuiltInLayouts.SYMBOLS_FILL_ROW)))
        assertFalse(leadsWithDigitRow(emptyList()))
    }

    @Test
    fun `a stored fill row survives the codec and an absent one stays null`() {
        val row = listOf(Key("§"), Key("¶"))
        val spec = LayerSpec(rows = listOf(listOf(Key("1"))), fillRow = row)
        val back = layoutJson.decodeFromString(LayerSpec.serializer(), layoutJson.encodeToString(LayerSpec.serializer(), spec))
        assertEquals(row, back.fillRow)
        val plain = layoutJson.decodeFromString(LayerSpec.serializer(), """{"rows":[]}""")
        assertNull(plain.fillRow)
    }
}
