package com.wasimaster.wmkeyboard.core.layout.json

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The tolerant parser: what it reads, what it reports, and where it says the caret is. */
class JsonTreeTest {

    private fun codes(text: String) = JsonTree.parse(text).syntax.map { it.code }

    /** Splits `|` out of [marked] and returns the document and the caret. */
    private fun at(marked: String): Pair<JsonDocument, Int> {
        val caret = marked.indexOf('|')
        return JsonTree.parse(marked.removeRange(caret, caret + 1)) to caret
    }

    @Test
    fun `valid json parses with no problems`() {
        val document = JsonTree.parse("{\"a\": [1, true, null, \"x\"], \"b\": {\"c\": -2.5e3}}")
        assertTrue(document.syntax.isEmpty())
        assertTrue(document.parses)
        val root = document.root as JsonObjectNode
        assertEquals(listOf("a", "b"), root.members.map { it.name })
        assertEquals(4, (root.member("a")!!.value as JsonArrayNode).items.size)
    }

    @Test
    fun `a missing comma is one problem and the rest still reads`() {
        val document = JsonTree.parse("{\"a\": 1 \"b\": 2}")
        assertEquals(listOf(JsonIssueCode.EXPECTED_COMMA), document.syntax.map { it.code })
        assertEquals(listOf("a", "b"), (document.root as JsonObjectNode).members.map { it.name })
    }

    @Test
    fun `a trailing comma is reported at the comma`() {
        val document = JsonTree.parse("[1, 2,]")
        val issue = document.syntax.single()
        assertEquals(JsonIssueCode.TRAILING_COMMA, issue.code)
        assertEquals(5, issue.start)
    }

    @Test
    fun `a closer of an outer container ends the inner one`() {
        val document = JsonTree.parse("[{\"label\": \"a\"]")
        assertEquals(listOf(JsonIssueCode.UNCLOSED_OBJECT), document.syntax.map { it.code })
        val array = document.root as JsonArrayNode
        assertTrue(array.closed)
        assertEquals(1, array.items.size)
    }

    @Test
    fun `an unclosed string stops at the end of its line`() {
        val document = JsonTree.parse("{\"a\": \"oops\n, \"b\": 1}")
        assertTrue(JsonIssueCode.UNCLOSED_STRING in document.syntax.map { it.code })
        assertNotNull((document.root as JsonObjectNode).member("b"))
    }

    @Test
    fun `unquoted words, stray closers and unknown characters are named`() {
        assertTrue(JsonIssueCode.UNQUOTED_WORD in codes("{label: \"a\"}"))
        assertTrue(JsonIssueCode.STRAY_CLOSER in codes("{\"a\": 1}}").plus(codes("[1]]")) || JsonIssueCode.TRAILING_CONTENT in codes("{\"a\": 1}}"))
        assertTrue(JsonIssueCode.UNKNOWN_CHARACTER in codes("[1, @]"))
        assertTrue(JsonIssueCode.UNCLOSED_ARRAY in codes("[1, 2"))
    }

    @Test
    fun `escapes decode and a bad one is reported`() {
        val document = JsonTree.parse("[\"a\\\"b\\u0041\\n\", \"\\q\"]")
        val items = (document.root as JsonArrayNode).items.map { (it as JsonScalarNode).text }
        assertEquals("a\"bA\n", items[0])
        assertEquals(listOf(JsonIssueCode.BAD_ESCAPE), document.syntax.map { it.code })
    }

    @Test
    fun `the caret after an opening brace is at a key`() {
        val (document, caret) = at("{\"rows\": [[{|}]]}")
        val location = document.locationAt(caret)
        assertEquals(JsonSlot.KEY, location.slot)
        assertEquals(listOf(JsonPathStep.Key("rows"), JsonPathStep.Index(0), JsonPathStep.Index(0)), location.path)
    }

    @Test
    fun `the caret inside a key string is at that key, with the string as its token`() {
        val (document, caret) = at("{\"label\": \"a\", \"wi|\"}")
        val location = document.locationAt(caret)
        assertEquals(JsonSlot.KEY, location.slot)
        assertEquals(JsonTokenKind.STRING, location.token?.kind)
    }

    @Test
    fun `the caret after a colon is at that key's value`() {
        val (document, caret) = at("{\"a\": {\"type\": |}}")
        val location = document.locationAt(caret)
        assertEquals(JsonSlot.VALUE, location.slot)
        assertEquals(listOf(JsonPathStep.Key("a"), JsonPathStep.Key("type")), location.path)
        assertEquals("type", location.key)
    }

    @Test
    fun `the caret after a finished value is past it`() {
        val (document, caret) = at("{\"width\": 1 |}")
        assertEquals(JsonSlot.AFTER, document.locationAt(caret).slot)
    }

    @Test
    fun `list items are counted by their commas`() {
        val (document, caret) = at("[1, 2, |]")
        val location = document.locationAt(caret)
        assertEquals(JsonSlot.ITEM, location.slot)
        assertEquals(listOf(JsonPathStep.Index(2)), location.path)
    }

    @Test
    fun `an empty document is at the root`() {
        val (document, caret) = at("|")
        assertEquals(JsonSlot.ROOT, document.locationAt(caret).slot)
        assertNull(document.root)
    }
}
