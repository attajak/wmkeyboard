package com.wasimaster.wmkeyboard.core.selection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CodeCasesTest {

    @Test
    fun `every convention reads the same two words`() {
        for (input in listOf("Hello World", "hello_world", "helloWorld", "HELLO_WORLD", "hello-world")) {
            assertEquals(input, listOf("hello", "world"), CodeCases.words(input))
        }
    }

    @Test
    fun `each case is written from the words`() {
        assertEquals("helloWorld", CodeCases.camel("Hello World"))
        assertEquals("hello_world", CodeCases.snake("Hello World"))
        assertEquals("hello-world", CodeCases.kebab("Hello World"))
        assertEquals("HELLO_WORLD", CodeCases.constant("Hello World"))
        assertEquals("helloWorld", CodeCases.camel("HELLO_WORLD"))
        assertEquals("hello_world", CodeCases.snake("helloWorld"))
    }

    @Test
    fun `capital runs, digits and apostrophes`() {
        assertEquals("xml_http_request", CodeCases.snake("XMLHttpRequest"))
        assertEquals(listOf("version2", "update"), CodeCases.words("version2Update"))
        assertEquals("dontStop", CodeCases.camel("don't stop"))
    }

    @Test
    fun `unchanged input, lines and other scripts`() {
        assertNull(CodeCases.camel("helloWorld"))
        assertEquals("one\ntwoWords", CodeCases.camel("one\ntwo words"))
        assertEquals("বাংলা_text", CodeCases.snake("বাংলা text"))
        assertEquals("padded", CodeCases.camel("  padded  "))
    }
}
