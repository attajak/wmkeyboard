package com.wasimaster.wmkeyboard.core.layout.json

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The doc strip's lookup and the fold regions. */
class LayoutJsonLookupTest {

    private fun lookup(marked: String, root: LayoutJsonRoot = LayoutJsonRoot.LAYOUT): JsonDocEntry? {
        val caret = marked.indexOf('|')
        return LayoutJsonLookup.at(JsonTree.parse(marked.removeRange(caret, caret + 1)), caret, root)
    }

    private fun inKey(inside: String) = "{\"id\": \"x\", \"name\": \"X\", \"layers\": {\"letters\": {\"rows\": [[$inside]]}}}"

    @Test
    fun `a caret on a key explains that property with its type and default`() {
        val entry = lookup(inKey("{\"label\": \"a\", \"wi|dth\": 2}"))!!
        assertEquals("Key.width", entry.path)
        assertEquals("\"width\": number = 1.0", entry.signature)
        assertTrue(entry.body.isNotBlank())
    }

    @Test
    fun `a caret in a plain value explains the property it belongs to`() {
        assertEquals("Key.label", lookup(inKey("{\"label\": \"a|\"}"))?.path)
    }

    @Test
    fun `a caret in an action's type explains the action`() {
        val entry = lookup(inKey("{\"label\": \"\", \"action\": {\"type\": \"to|ol\", \"tool\": \"VOICE\"}}"))!!
        assertEquals("action:tool", entry.path)
        assertTrue(entry.signature.contains("\"tool\": ToolbarTool"))
    }

    @Test
    fun `a caret on a layer name explains the layers map`() {
        assertEquals("LayoutSpec.layers", lookup("{\"layers\": {\"lett|ers\": {\"rows\": []}}}")?.path)
    }

    @Test
    fun `a caret between members explains nothing`() {
        assertNull(lookup(inKey("{\"label\": \"a\",| \"width\": 2}")))
    }

    @Test
    fun `only blocks over more than one line fold`() {
        val text = "{\n  \"a\": [1, 2],\n  \"b\": {\n    \"c\": 1\n  }\n}"
        val regions = LayoutJsonLookup.foldRegions(JsonTree.parse(text))
        assertEquals(listOf(0, text.indexOf("{\n    ")), regions.map { it.start })
        assertEquals(text.length, regions.first().end)
    }
}
