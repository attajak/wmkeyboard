package com.wasimaster.wmkeyboard.core.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Invariants of the regrouped built-in list. The one that matters most: every
 * id that ever shipped still resolves, because selections, panel pins and
 * auto-theme slots on existing installs store those ids as plain strings.
 */
class BuiltInThemeFamilyTest {

    /** Every built-in id shipped before the family grouping, verbatim. */
    private val shippedIds = listOf(
        "builtin_ocean", "builtin_forest", "builtin_sunset", "builtin_berry",
        "builtin_crimson", "builtin_slate", "builtin_pitch",
        "builtin_snow", "builtin_mint", "builtin_rose", "builtin_sand",
        "builtin_nebula", "builtin_sunset_drift", "builtin_aurora",
        "builtin_deep_sea", "builtin_glacier", "builtin_bubble", "builtin_facet",
        "builtin_dracula", "builtin_nord",
        "builtin_solarized_dark", "builtin_solarized_light",
        "builtin_catppuccin_mocha", "builtin_catppuccin_latte",
        "builtin_tokyo_night", "builtin_cyberpunk",
    )

    @Test
    fun `every id that ever shipped still resolves`() {
        val flattened = BuiltInThemes.flattenedThemes().map { it.id }
        for (id in shippedIds) {
            assertTrue("$id no longer resolves", id in flattened)
        }
    }

    @Test
    fun `flattened ids are unique`() {
        val flattened = BuiltInThemes.flattenedThemes().map { it.id }
        assertEquals(flattened.size, flattened.toSet().size)
    }

    @Test
    fun `every flattened theme has a translated name`() {
        for (theme in BuiltInThemes.flattenedThemes()) {
            assertNotNull("${theme.id} has no name resource", builtInThemeNameRes(theme.id))
        }
    }

    @Test
    fun `every family has a label`() {
        for (parent in BuiltInThemes.filter { it.variants.isNotEmpty() }) {
            assertNotNull("${parent.id} heads a family with no label", builtInThemeFamilyNameRes(parent.id))
        }
    }

    @Test
    fun `the new Catppuccin flavours are dark`() {
        val mocha = BuiltInThemes.first { it.id == "builtin_catppuccin_mocha" }
        val byId = mocha.selfAndVariants().associateBy { it.id }
        assertEquals(true, byId["builtin_catppuccin_frappe"]?.dark)
        assertEquals(true, byId["builtin_catppuccin_macchiato"]?.dark)
    }

    @Test
    fun `Alucard is Dracula's light look`() {
        val dracula = BuiltInThemes.first { it.id == "builtin_dracula" }
        val alucard = dracula.variants.single()
        assertEquals("builtin_alucard", alucard.id)
        assertEquals(false, alucard.dark)
        assertEquals(true, dracula.dark)
    }

    @Test
    fun `built-ins never carry a familyName`() {
        // Built-in family labels come from resources so they translate; the
        // stored field is the custom themes' mechanism.
        for (theme in BuiltInThemes.flattenedThemes()) {
            assertEquals(null, theme.familyName)
        }
    }

    @Test
    fun `variants never nest`() {
        for (parent in BuiltInThemes) {
            for (variant in parent.variants) {
                assertTrue("${variant.id} nests variants", variant.variants.isEmpty())
            }
        }
    }
}
