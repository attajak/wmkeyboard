package com.wasimaster.wmkeyboard.core.layout

import com.wasimaster.wmkeyboard.core.settings.TextEditAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a flick off a key does: the text arms of a kana pad, their shift forms
 * (issue #550), and the arms that run an action instead (issue #549).
 */
class FlickArmTest {

    private val left = KeyAlternate(KeyAction.Edit(TextEditAction.LEFT))

    @Test
    fun `a text arm types its own shift form, not the tap's`() {
        val key = Key(
            "あ",
            shiftLabel = "ア",
            flick = mapOf(FlickDirection.LEFT to "い", FlickDirection.UP to "う"),
            flickShift = mapOf(FlickDirection.LEFT to "イ"),
        )
        val flicked = key.flickKey(FlickDirection.LEFT)!!
        assertEquals("い", flicked.output)
        assertEquals("イ", flicked.shiftLabel)
        // An arm with no shift form of its own has none, so Shift does to it
        // what it does to any key without a shift label.
        assertNull(key.flickKey(FlickDirection.UP)!!.shiftLabel)
    }

    @Test
    fun `an action arm beats the text arm the same way`() {
        val key = Key(
            "a",
            flick = mapOf(FlickDirection.LEFT to "b"),
            flickActions = mapOf(FlickDirection.LEFT to left),
        )
        assertTrue(key.flickArm(FlickDirection.LEFT) is FlickArm.Action)
        assertEquals(KeyAction.Edit(TextEditAction.LEFT), key.flickKey(FlickDirection.LEFT)!!.action)
        assertNull(key.flickArm(FlickDirection.RIGHT))
    }

    @Test
    fun `an action key flicks its action arms and never its text`() {
        val key = Key(
            "",
            action = KeyAction.Edit(TextEditAction.SELECT),
            flick = mapOf(FlickDirection.UP to "x"),
            flickActions = mapOf(FlickDirection.LEFT to left),
        )
        assertTrue(key.hasFlicks())
        assertNull(key.flickArm(FlickDirection.UP))
        assertTrue(key.flickKey(FlickDirection.LEFT)!!.flickArmRepeats())
    }

    @Test
    fun `keys whose drag or hold is already the gesture take no action arms`() {
        val arms = mapOf(FlickDirection.LEFT to left)
        assertFalse(Key(" ", action = KeyAction.Space, flickActions = arms).hasFlicks())
        assertFalse(Key("", action = KeyAction.Delete, flickActions = arms).hasFlicks())
        assertFalse(Key("x", repeatOnHold = true, flickActions = arms).hasFlicks())
        assertTrue(Key("", action = KeyAction.Shift, flickActions = arms).hasFlicks())
    }

    @Test
    fun `a corner arm is an arm like any other`() {
        // Issue #410: MessagEase's o carries all eight.
        val key = Key(
            "o",
            flick = mapOf(
                FlickDirection.UP_LEFT to "q", FlickDirection.UP to "u", FlickDirection.UP_RIGHT to "p",
                FlickDirection.LEFT to "c", FlickDirection.RIGHT to "b",
                FlickDirection.DOWN_LEFT to "g", FlickDirection.DOWN to "d", FlickDirection.DOWN_RIGHT to "j",
            ),
        )
        assertTrue(key.hasFlicks())
        assertEquals("q", (key.flickArm(FlickDirection.UP_LEFT) as FlickArm.Text).text)
        assertEquals("j", key.flickKey(FlickDirection.DOWN_RIGHT)!!.output)
        assertTrue(FlickDirection.UP_LEFT.isDiagonal)
        assertFalse(FlickDirection.UP.isDiagonal)
        // The pad order puts the centre in the middle and every arm once.
        assertEquals(9, FlickDirection.gridOrder.size)
        assertEquals(null, FlickDirection.gridOrder[4])
        assertEquals(FlickDirection.entries.toSet(), FlickDirection.gridOrder.filterNotNull().toSet())
    }

    @Test
    fun `repair drops an action arm that only types or cannot run`() {
        val key = Key(
            "a",
            flickActions = mapOf(
                FlickDirection.LEFT to left,
                FlickDirection.UP to KeyAlternate(KeyAction.Text, label = "z"),
                FlickDirection.DOWN to KeyAlternate(KeyAction.Unknown("future")),
            ),
        )
        val repaired = key.repairKey("a", mutableListOf())!!
        assertEquals(mapOf(FlickDirection.LEFT to left), repaired.flickActions)
    }
}
