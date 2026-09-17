package com.wasimaster.wmkeyboard.core.theme

import com.wasimaster.wmkeyboard.core.layout.KeyLabelScaleRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The single-key styles a theme can carry beyond the four colours it started
 * with (issue #107): a hint colour, a texture of the key's own, the burst it
 * throws, and how its label is drawn.
 *
 * The contract is the one every other field on [ThemeSpec] keeps — round trip,
 * decode to nothing from a theme written before it existed, and cost only
 * itself when a name is unknown — plus the one that is specific to an image:
 * the local path never travels.
 */
class KeyOverrideStylesTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun styled() = ThemeSpec(
        id = "custom_1",
        name = "Styled",
        keyOverrides = mapOf(
            "a" to KeyOverride(
                background = 0xFFFF0000,
                hint = 0xFF00FF00,
                texture = "/data/theme_images/custom_1_keytex_a.img",
                labelScale = 1.4f,
                bold = true,
            ),
            "ENTER" to KeyOverride(effect = "EMOJI", effectParam = "🎉", bold = false),
        ),
    )

    @Test
    fun `the new single-key fields survive a round trip`() {
        val decoded = ThemeCodec.decode(ThemeCodec.encode(styled()))
        assertNotNull(decoded)
        assertEquals(styled(), decoded)
    }

    @Test
    fun `a style written before these fields decodes with none of them`() {
        val old = """
            {"id":"custom_9","name":"Old","keyOverrides":{"a":{"background":4294901760}}}
        """.trimIndent()
        val decoded = ThemeCodec.decode(old)
        assertNotNull(decoded)
        val a = decoded!!.keyOverrides["a"]
        assertNotNull(a)
        assertEquals(0xFFFF0000, a!!.background)
        assertNull(a.hint)
        assertNull(a.texture)
        assertNull(a.effect)
        assertNull(a.labelScale)
        assertNull(a.bold)
        assertFalse(a.isEmpty)
    }

    @Test
    fun `an empty style is empty whichever field is the last one cleared`() {
        assertTrue(KeyOverride().isEmpty)
        assertFalse(KeyOverride(hint = 1L).isEmpty)
        assertFalse(KeyOverride(texture = "/x.img").isEmpty)
        assertFalse(KeyOverride(effect = "STARS").isEmpty)
        assertFalse(KeyOverride(labelScale = 1.2f).isEmpty)
        assertFalse(KeyOverride(bold = false).isEmpty)
    }

    /**
     * The image kinds throw the theme's own picture files, which a per-key
     * style does not carry — a name that asks for one is read as no effect
     * rather than as an effect with nothing to throw.
     */
    @Test
    fun `a per-key effect is a drawn kind or nothing`() {
        assertEquals(KeyEffectKind.STARS, KeyOverride(effect = "stars").effectKind)
        assertEquals(KeyEffectKind.EMOJI, KeyOverride(effect = "EMOJI").effectKind)
        assertNull(KeyOverride(effect = "CUSTOM_IMAGE").effectKind)
        assertNull(KeyOverride(effect = "FIREWORKS_3D").effectKind)
        assertNull(KeyOverride().effectKind)
    }

    /**
     * Two ranges, one meaning: `:core:theme` cannot see the layout module, so
     * the numbers are copied and pinned here instead of shared.
     */
    @Test
    fun `a per-key label scale is held to the same bounds an authored one is`() {
        assertEquals(KeyLabelScaleRange, KEY_OVERRIDE_LABEL_SCALE_RANGE)
    }

    /**
     * The path is local to the device that made the theme, so an export drops
     * it and the bytes ride in [ThemeSpec.assets] instead. The encode itself
     * is `android.util.Base64` and wants a device; what is asserted here is
     * the half that does not — that the path never survives, and that a key
     * with no texture takes no slot.
     */
    @Test
    fun `an export drops the texture path and takes a slot only where there is one`() {
        val exported = styled().withEmbeddedImages()
        assertNull(exported.keyOverrides.getValue("a").texture)
        assertNull(exported.keyOverrides.getValue("ENTER").texture)
        assertNull(exported.assets["${ASSET_KEY_OVERRIDE_TEXTURE_PREFIX}ENTER"])
        // Everything else about the style travels untouched.
        assertEquals(0xFF00FF00, exported.keyOverrides.getValue("a").hint)
        assertEquals("EMOJI", exported.keyOverrides.getValue("ENTER").effect)
    }

    /**
     * An import that carries no bytes for a key — a backup made on this device,
     * which stores paths — must leave the path it already had alone.
     */
    @Test
    fun `an extract with nothing embedded keeps the paths it was given`() {
        val extracted = styled().withExtractedImages(temp.newFolder("empty"))
        assertEquals(
            styled().keyOverrides.getValue("a").texture,
            extracted.keyOverrides.getValue("a").texture,
        )
        assertTrue(extracted.assets.isEmpty())
    }

    @Test
    fun `a reseed keeps every single-key style`() {
        val reseeded = styled().reseeded(0xFF3366AA, dark = false)
        assertEquals(styled().keyOverrides, reseeded.keyOverrides)
    }
}
