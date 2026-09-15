package com.wasimaster.wmkeyboard.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A glided trigger previews what it expands to, not itself (issue #205).
 */
class GlideExpansionTest {

    private val futo = GlideExpansion(
        plain = "FUTO keyboard",
        capitalized = "FUTO keyboard",
        shouted = "FUTO KEYBOARD",
    )

    @Test
    fun `the preview is cased the way the trigger is shown`() {
        val omw = GlideExpansion("on my way", "On my way", "ON MY WAY")
        assertEquals("on my way", omw.previewFor("omw"))
        assertEquals("On my way", omw.previewFor("Omw"))
        assertEquals("ON MY WAY", omw.previewFor("OMW"))
        assertEquals("FUTO keyboard", futo.previewFor("fk"))
    }

    @Test
    fun `a one-letter capital is a capital, not a shout`() {
        val a = GlideExpansion("and", "And", "AND")
        assertEquals("And", a.previewFor("A"))
    }

    @Test
    fun `a long or multi-line expansion is one short line`() {
        val preview = glideExpansionPreview("Dear team,\n\n  " + "word ".repeat(30))
        assertTrue(preview, preview.startsWith("Dear team, word word"))
        assertTrue(preview, preview.endsWith("…"))
        assertTrue(preview, preview.codePointCount(0, preview.length) <= GLIDE_EXPANSION_PREVIEW_MAX + 1)
    }

    @Test
    fun `the cut never splits a surrogate pair`() {
        val emoji = "😀".repeat(GLIDE_EXPANSION_PREVIEW_MAX + 5)
        val preview = glideExpansionPreview(emoji)
        assertEquals("😀".repeat(GLIDE_EXPANSION_PREVIEW_MAX) + "…", preview)
    }
}
