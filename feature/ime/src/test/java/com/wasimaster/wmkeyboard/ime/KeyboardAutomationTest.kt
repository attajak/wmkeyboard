package com.wasimaster.wmkeyboard.ime

import com.wasimaster.wmkeyboard.core.layout.BuiltInLayouts
import com.wasimaster.wmkeyboard.ime.KeyboardAutomation.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where an automation intent sends the keyboard. Every answer has to be one of
 * the enabled layouts or a refusal; nothing else is reachable.
 */
class KeyboardAutomationTest {

    private val qwerty = BuiltInLayouts.QWERTY_ID
    private val avro = BuiltInLayouts.AVRO_ID
    private val probhat = BuiltInLayouts.PROBHAT_ID
    private val french = BuiltInLayouts.FRENCH_ID
    private val dvorak = BuiltInLayouts.DVORAK_ID

    private val enabled = listOf(qwerty, avro, probhat, french)

    private fun resolve(
        action: String,
        layout: String? = null,
        language: String? = null,
        current: String = qwerty,
        recent: List<String> = emptyList(),
        on: List<String> = enabled,
    ) = KeyboardAutomation.resolve(action, layout, language, on, current, recent, customs = emptyList())

    @Test
    fun `set layout by id switches`() {
        assertEquals(Outcome.Switch(french), resolve(KeyboardAutomation.ACTION_SET_LAYOUT, layout = french))
    }

    @Test
    fun `set layout by id ignores case`() {
        assertEquals(
            Outcome.Switch(avro),
            resolve(KeyboardAutomation.ACTION_SET_LAYOUT, layout = avro.uppercase()),
        )
    }

    @Test
    fun `set layout by name`() {
        val name = BuiltInLayouts.byId(probhat)!!.name
        assertEquals(
            Outcome.Switch(probhat),
            resolve(KeyboardAutomation.ACTION_SET_LAYOUT, layout = name.lowercase()),
        )
    }

    @Test
    fun `set layout refuses one that is not turned on`() {
        assertTrue(resolve(KeyboardAutomation.ACTION_SET_LAYOUT, layout = dvorak) is Outcome.Failed)
    }

    @Test
    fun `set layout without the extra is refused`() {
        assertTrue(resolve(KeyboardAutomation.ACTION_SET_LAYOUT) is Outcome.Failed)
        assertTrue(resolve(KeyboardAutomation.ACTION_SET_LAYOUT, layout = "  ") is Outcome.Failed)
    }

    @Test
    fun `set layout to the current one changes nothing`() {
        assertEquals(Outcome.Unchanged(qwerty), resolve(KeyboardAutomation.ACTION_SET_LAYOUT, layout = qwerty))
    }

    @Test
    fun `set language picks the first layout of it in switch order`() {
        assertEquals(Outcome.Switch(avro), resolve(KeyboardAutomation.ACTION_SET_LANGUAGE, language = "bn"))
    }

    @Test
    fun `set language prefers the layout of it used last`() {
        assertEquals(
            Outcome.Switch(probhat),
            resolve(KeyboardAutomation.ACTION_SET_LANGUAGE, language = "bn", recent = listOf(french, probhat, avro)),
        )
    }

    @Test
    fun `set language already on screen stays on the layout in use`() {
        assertEquals(
            Outcome.Unchanged(probhat),
            resolve(KeyboardAutomation.ACTION_SET_LANGUAGE, language = "bn", current = probhat, recent = listOf(avro)),
        )
    }

    @Test
    fun `set language accepts a locale tag, a region variant and an English name`() {
        assertEquals(Outcome.Switch(french), resolve(KeyboardAutomation.ACTION_SET_LANGUAGE, language = "fr"))
        assertEquals(Outcome.Switch(french), resolve(KeyboardAutomation.ACTION_SET_LANGUAGE, language = "fr-CA"))
        assertEquals(Outcome.Switch(french), resolve(KeyboardAutomation.ACTION_SET_LANGUAGE, language = "French"))
        assertEquals(Outcome.Switch(avro), resolve(KeyboardAutomation.ACTION_SET_LANGUAGE, language = "bn_BD"))
    }

    @Test
    fun `set language with nothing of it turned on is refused`() {
        assertTrue(resolve(KeyboardAutomation.ACTION_SET_LANGUAGE, language = "de") is Outcome.Failed)
    }

    @Test
    fun `next and previous wrap around the switch order`() {
        assertEquals(Outcome.Switch(avro), resolve(KeyboardAutomation.ACTION_NEXT_LAYOUT))
        assertEquals(Outcome.Switch(french), resolve(KeyboardAutomation.ACTION_PREVIOUS_LAYOUT))
        assertEquals(Outcome.Switch(qwerty), resolve(KeyboardAutomation.ACTION_NEXT_LAYOUT, current = french))
    }

    @Test
    fun `next from a layout outside the ring enters it at the matching end`() {
        assertEquals(Outcome.Switch(qwerty), resolve(KeyboardAutomation.ACTION_NEXT_LAYOUT, current = dvorak))
        assertEquals(Outcome.Switch(french), resolve(KeyboardAutomation.ACTION_PREVIOUS_LAYOUT, current = dvorak))
    }

    @Test
    fun `next with one layout turned on changes nothing`() {
        assertEquals(
            Outcome.Unchanged(qwerty),
            resolve(KeyboardAutomation.ACTION_NEXT_LAYOUT, on = listOf(qwerty)),
        )
    }

    @Test
    fun `list marks the layout on screen`() {
        val lines = KeyboardAutomation.describe(enabled, avro, emptyList()).lines()
        assertEquals(enabled.size, lines.size)
        assertTrue(lines[1].startsWith("* $avro\tbn\t"))
        assertTrue(lines[0].startsWith("  $qwerty\ten\t"))
    }
}
