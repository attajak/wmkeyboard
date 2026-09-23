package com.wasimaster.wmkeyboard.core.layout

import com.wasimaster.wmkeyboard.core.script.LanguageRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutRegistryTest {

    @Test
    fun `every built-in defines a letters layer and a known language`() {
        for (spec in BuiltInLayouts.all) {
            assertNotNull("${spec.id} must define a letters layer", spec.layer(LayoutLayer.LETTERS))
            assertNotSame(
                "${spec.id} resolves to an unknown language",
                LanguageRegistry.GENERIC,
                spec.language(),
            )
        }
    }

    @Test
    fun `built-in ids are unique`() {
        val ids = BuiltInLayouts.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `colemak workman halmak built-in layouts exist`() {
        assertNotNull(BuiltInLayouts.byId(BuiltInLayouts.COLEMAK_ID))
        assertNotNull(BuiltInLayouts.byId(BuiltInLayouts.WORKMAN_ID))
        assertNotNull(BuiltInLayouts.byId(BuiltInLayouts.HALMAK_ID))
        assertEquals("Colemak", BuiltInLayouts.COLEMAK.name)
        assertEquals("Workman", BuiltInLayouts.WORKMAN.name)
        assertEquals("Halmak", BuiltInLayouts.HALMAK.name)
    }

    @Test
    fun `with no customs every built-in is found and nothing else is`() {
        for (spec in BuiltInLayouts.all) {
            assertEquals(spec, findLayout(emptyList(), spec.id))
            assertTrue(isShippedLayoutId(spec.id))
        }
        assertNull(findLayout(emptyList(), "custom_1"))
    }

    @Test
    fun `a custom layout reusing a built-in id shadows it in place`() {
        val edited = BuiltInLayouts.QWERTY.copy(name = "My QWERTY")
        assertEquals("My QWERTY", findLayout(listOf(edited), BuiltInLayouts.QWERTY_ID)?.name)
        // Still shipped: deleting the edit gives the built-in back, so its
        // references are kept rather than dropped.
        assertTrue(isShippedLayoutId(BuiltInLayouts.QWERTY_ID))
        assertEquals(
            "shipped order is unmoved by the edit",
            BuiltInLayouts.all.indexOfFirst { it.id == BuiltInLayouts.QWERTY_ID },
            shippedLayoutRank(BuiltInLayouts.QWERTY_ID),
        )
    }

    @Test
    fun `deleting the shadow restores the shipped grid`() {
        val edited = BuiltInLayouts.QWERTY.copy(name = "My QWERTY")
        assertEquals("My QWERTY", resolveLayout(listOf(edited), BuiltInLayouts.QWERTY_ID).name)
        assertEquals("QWERTY", resolveLayout(emptyList(), BuiltInLayouts.QWERTY_ID).name)
    }

    @Test
    fun `a genuinely new custom layout is found as itself and is not shipped`() {
        val mine = LayoutSpec(id = "custom_1", name = "Mine")
        assertEquals(mine, findLayout(listOf(mine), "custom_1"))
        assertFalse(isShippedLayoutId("custom_1"))
        assertEquals(Int.MAX_VALUE, shippedLayoutRank("custom_1"))
    }

    @Test
    fun `an id that no longer exists falls back to the default rather than crashing`() {
        assertEquals(BuiltInLayouts.default, resolveLayout(emptyList(), "custom_deleted"))
    }

    @Test
    fun `compiling an undefined layer inherits the default's grid`() {
        val lettersOnly = LayoutSpec(
            id = "custom_1",
            name = "Mine",
            layers = mapOf(LayoutLayer.LETTERS.key to LayerSpec(listOf(listOf(Key("a"))))),
        )
        assertEquals(
            "an undefined symbols layer inherits the shipped one",
            BuiltInLayouts.default.compile(LayoutLayer.SYMBOLS).rows,
            lettersOnly.compile(LayoutLayer.SYMBOLS).rows,
        )
    }

    @Test
    fun `compiling twice returns the cached instance`() {
        assertSame(
            BuiltInLayouts.PROBHAT.compile(LayoutLayer.LETTERS),
            BuiltInLayouts.PROBHAT.compile(LayoutLayer.LETTERS),
        )
    }

    @Test
    fun `the compile cache notices an edit under the same id`() {
        val id = "custom_cache"
        val first = LayoutSpec(id, "A", layers = mapOf(LayoutLayer.LETTERS.key to LayerSpec(listOf(listOf(Key("a"))))))
        val second = LayoutSpec(id, "B", layers = mapOf(LayoutLayer.LETTERS.key to LayerSpec(listOf(listOf(Key("b"))))))

        assertEquals("a", first.compile(LayoutLayer.LETTERS).rows[0][0].label)
        assertEquals(
            "an edit under the same id must not serve the stale grid",
            "b",
            second.compile(LayoutLayer.LETTERS).rows[0][0].label,
        )
    }

    @Test
    fun `the default enabled ids all exist`() {
        assertTrue(BuiltInLayouts.defaultEnabledIds.all { BuiltInLayouts.byId(it) != null })
    }
}
