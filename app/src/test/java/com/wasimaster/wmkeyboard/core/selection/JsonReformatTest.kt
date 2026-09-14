package com.wasimaster.wmkeyboard.core.selection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JsonReformatTest {

    @Test
    fun `text that is not an object or array is not json`() {
        for (text in listOf("42", "\"x\"", "{like this}", "hello", "", "{", "[1,", "{\"a\":1} trailing")) {
            assertEquals(text, JsonReformat.Shape.NONE, JsonReformat.shape(text))
            assertNull(text, JsonReformat.toggle(text))
        }
    }

    @Test
    fun `minified pretty-prints with two spaces and its key order`() {
        assertEquals(JsonReformat.Shape.MINIFIED, JsonReformat.shape("""{"b":1,"a":[1,2]}"""))
        assertEquals("{\n  \"b\": 1,\n  \"a\": [\n    1,\n    2\n  ]\n}", JsonReformat.toggle("""{"b":1,"a":[1,2]}"""))
    }

    @Test
    fun `pretty minifies and literals survive`() {
        val pretty = "{\n  \"x\": 1.0,\n  \"s\": \"ক\"\n}"
        assertEquals(JsonReformat.Shape.PRETTY, JsonReformat.shape(pretty))
        assertEquals("""{"x":1.0,"s":"ক"}""", JsonReformat.toggle(pretty))
        assertEquals("[\n  [\n    1\n  ]\n]", JsonReformat.toggle("[[1]]"))
    }
}
