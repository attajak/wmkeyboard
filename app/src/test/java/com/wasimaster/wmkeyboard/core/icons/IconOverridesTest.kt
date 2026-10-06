package com.wasimaster.wmkeyboard.core.icons

import com.wasimaster.wmkeyboard.core.settings.ToolbarTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IconOverridesTest {

    private val clipboard = IconSlots.forTool(ToolbarTool.CLIPBOARD)

    @Test
    fun `round trips`() {
        val map = mapOf(
            clipboard to IconOverrides.builtinSource("ContentPaste"),
            IconSlots.KEY_ENTER_SEND to IconOverrides.packSource("pack_123"),
        )
        assertEquals(map, IconOverrides.decode(IconOverrides.encode(map)))
    }

    @Test
    fun `empty and null decode to nothing`() {
        assertEquals(emptyMap<String, String>(), IconOverrides.decode(null))
        assertEquals(emptyMap<String, String>(), IconOverrides.decode(""))
        assertEquals("", IconOverrides.encode(emptyMap()))
    }

    @Test
    fun `entries for unknown slots are dropped`() {
        val decoded = IconOverrides.decode("tool.timetravel=b:Star,$clipboard=b:Check")
        assertEquals(mapOf(clipboard to "b:Check"), decoded)
    }

    @Test
    fun `malformed entries are dropped without taking the rest with them`() {
        val decoded = IconOverrides.decode("garbage,=b:Star,$clipboard=b:Check,$clipboard=")
        assertEquals(mapOf(clipboard to "b:Check"), decoded)
    }

    /**
     * A pack that is only temporarily gone must not cost the user their choice,
     * so an unresolvable source survives the round trip — the resolver is what
     * falls back, not the codec.
     */
    @Test
    fun `a source naming a missing pack is kept`() {
        val decoded = IconOverrides.decode("$clipboard=p:uninstalled")
        assertEquals(mapOf(clipboard to "p:uninstalled"), decoded)
    }

    @Test
    fun `every slot survives an encode-decode cycle`() {
        val map = IconSlots.all.associate { it.id to IconOverrides.builtinSource("Star") }
        val encoded = IconOverrides.encode(map)
        assertEquals(map, IconOverrides.decode(encoded))
        // The separators only appear between fields, never inside one.
        assertEquals(map.size - 1, encoded.count { it == ',' })
        assertEquals(map.size, encoded.count { it == '=' })
    }

    @Test
    fun `source prefixes are distinct`() {
        assertTrue(!IconOverrides.builtinSource("x").startsWith(IconOverrides.PACK_PREFIX))
        assertTrue(!IconOverrides.packSource("x").startsWith(IconOverrides.BUILTIN_PREFIX))
        assertTrue(!IconOverrides.variantSource("x").startsWith(IconOverrides.BUILTIN_PREFIX))
        assertTrue(!IconOverrides.variantSource("x").startsWith(IconOverrides.PACK_PREFIX))
    }

    @Test
    fun `a variant source round trips and names its variant`() {
        val slot = IconSlots.forTool(ToolbarTool.GIF)
        val map = mapOf(slot to IconOverrides.variantSource("text"))
        assertEquals(map, IconOverrides.decode(IconOverrides.encode(map)))
        assertEquals("text", IconOverrides.variantOf(map[slot]))
        assertEquals(null, IconOverrides.variantOf(IconOverrides.builtinSource("text")))
        assertEquals(null, IconOverrides.variantOf(null))
    }
}
