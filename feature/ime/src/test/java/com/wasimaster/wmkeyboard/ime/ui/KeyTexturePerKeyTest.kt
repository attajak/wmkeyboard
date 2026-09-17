package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.ui.graphics.ImageBitmap
import com.wasimaster.wmkeyboard.core.layout.KeyAction
import com.wasimaster.wmkeyboard.core.theme.KeyTextureScale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * A texture given to one key (issue #107): [KeyTextures.forKey] hands that key
 * its own bitmap and every other key its class's, so a board can dress the
 * enter key alone without the rest of the grid paying a lookup.
 *
 * Robolectric, in native graphics mode, because an [ImageBitmap] is a real
 * `Bitmap` underneath and the legacy shadow hands back null; the routing
 * itself is plain Kotlin.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class KeyTexturePerKeyTest {

    private fun textures(perKey: Map<String, ImageBitmap>, normal: ImageBitmap? = null) =
        KeyTextures(
            normal = normal,
            modifier = null,
            enter = null,
            space = null,
            pressed = null,
            popup = null,
            scale = KeyTextureScale.CROP,
            opacity = 1f,
            perKey = perKey,
        )

    @Test
    fun `a key's own texture beats its class's`() {
        val own = ImageBitmap(2, 2)
        val classWide = ImageBitmap(4, 4)
        val set = textures(mapOf("a" to own), normal = classWide)
        assertEquals(own, set.forKey(KeyAction.Text, "a"))
        assertEquals(classWide, set.forKey(KeyAction.Text, "b"))
        // A key that never asks gets the class answer, which is what every
        // caller before single-key textures existed passed.
        assertEquals(classWide, set.forKey(KeyAction.Text))
    }

    @Test
    fun `a single-key texture stands on a board with no class textures at all`() {
        val own = ImageBitmap(2, 2)
        val set = textures(mapOf("ENTER" to own))
        assertEquals(own, set.forKey(KeyAction.Enter, "ENTER"))
        assertNull(set.forKey(KeyAction.Text, "a"))
        // …and such a set is not empty, or the decode would never be asked for.
        assertEquals(false, set.isEmpty)
    }
}
