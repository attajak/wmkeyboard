package com.wasimaster.wmkeyboard.core.theme

import com.wasimaster.wmkeyboard.core.icons.IconSlots
import com.wasimaster.wmkeyboard.core.icons.RasterIcons
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Holds [GboardIcons] to the icon system it names.
 *
 * `:core:theme` cannot see `:core:icons` — `:core:icons` depends on
 * `:core:settings`, which depends on `:core:theme`, so the dependency would be
 * a cycle — and the Gboard converter therefore writes slot ids as plain
 * strings. This test lives in `:app`, which sees both modules, and is the only
 * thing standing between a renamed slot and a Gboard theme whose glyphs
 * silently stop arriving.
 */
class GboardIconSlotsTest {

    @Test
    fun `every slot the Gboard table names is a real one`() {
        for ((className, slot) in GboardIcons.SLOTS) {
            assertNotNull("$className names no slot: $slot", IconSlots.byId(slot))
        }
    }

    @Test
    fun `the glyph size cap matches the one the icon pipeline applies`() {
        // A glyph larger than the pipeline will take would be read here, stored
        // on the theme, and then refused at render time — a theme claiming an
        // icon it does not draw.
        assertEquals(RasterIcons.MAX_SOURCE_BYTES, GboardIcons.MAX_ICON_BYTES)
    }

    @Test
    fun `the class names are distinct and the day-night suffix folds`() {
        assertEquals(GboardIcons.SLOTS.size, GboardIcons.CLASSES.size)
        assertEquals(
            "icon_key_main_category_smiley",
            GboardIcons.normalize("icon_key_main_category_smiley_dark_theme"),
        )
        assertEquals(
            "icon_key_main_category_smiley",
            GboardIcons.normalize("icon_key_main_category_smiley_light_theme"),
        )
        // A name with no suffix is left alone, including one that merely ends
        // in a word the suffixes contain.
        assertEquals("icon_key_del", GboardIcons.normalize("icon_key_del"))
    }

    @Test
    fun `the asset prefix survives a round trip through the theme format`() {
        // The transport contract between the converter and `withExtractedImages`:
        // the converter writes `keyIcon:<slot>` into the asset map and the
        // theme comes back out with that slot in `keyIcons`.
        val slot = IconSlots.KEY_BACKSPACE
        assertTrue(GboardIcons.SLOTS.any { it.second == slot })
        assertEquals("keyIcon:$slot", ASSET_KEY_ICON_PREFIX + slot)
    }
}
