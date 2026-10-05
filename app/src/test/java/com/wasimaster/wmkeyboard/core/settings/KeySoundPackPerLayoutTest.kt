package com.wasimaster.wmkeyboard.core.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [KeySoundSettings.packFor] — which pack a layout plays (issue #520).
 *
 * The resolution itself is three lines, and it is worth a test for one reason:
 * the override map is a list of exceptions, not a second copy of the selection,
 * and a bug that made it the latter would be invisible to anyone who had set
 * every layout.
 */
class KeySoundPackPerLayoutTest {

    @Test
    fun `a layout with no entry follows the global pick`() {
        val sound = KeySoundSettings(packId = "pack_global")
        assertEquals("pack_global", sound.packFor("qwerty"))
        assertEquals("pack_global", sound.packFor(""))
    }

    @Test
    fun `a layout with an entry plays its own pack`() {
        val sound = KeySoundSettings(
            packId = "pack_global",
            packByLayout = mapOf("bn_avro" to "pack_voice"),
        )
        assertEquals("pack_voice", sound.packFor("bn_avro"))
        // And the layouts around it are untouched: this is an override list.
        assertEquals("pack_global", sound.packFor("qwerty"))
    }

    @Test
    fun `a blank entry means follow the global pick, not silence`() {
        // The only way to store one is a cleared override, and clearing one
        // means "go back to the pack above" rather than "play nothing".
        val sound = KeySoundSettings(
            packId = "pack_global",
            packByLayout = mapOf("qwerty" to "   "),
        )
        assertEquals("pack_global", sound.packFor("qwerty"))
    }

    @Test
    fun `an override stands even when nothing is picked globally`() {
        val sound = KeySoundSettings(packByLayout = mapOf("qwerty" to "pack_voice"))
        assertEquals("pack_voice", sound.packFor("qwerty"))
        // Nothing picked and no override is still nothing, which the player
        // reads as "no pack" and answers with the system click.
        assertEquals("", sound.packFor("bn_avro"))
    }
}
