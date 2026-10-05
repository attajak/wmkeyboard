package com.wasimaster.wmkeyboard.core.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Turning a page into the prose a model can read, and refusing what it should not open. */
class WebFetchClientTest {

    // ---- what counts as an address ---------------------------------------

    @Test
    fun `a bare host is read as https`() {
        assertEquals("https://example.com/a", WebFetchClient.normalize("example.com/a"))
        assertEquals("https://example.com", WebFetchClient.normalize("  <https://example.com>  "))
        assertEquals("http://example.com", WebFetchClient.normalize("http://example.com"))
    }

    @Test
    fun `nothing but http and https is opened`() {
        // The model inventing one of these must never have it read the app's
        // own storage and hand the contents to a cloud provider.
        assertNull(WebFetchClient.normalize("file:///data/data/app/secrets"))
        assertNull(WebFetchClient.normalize("content://media/external/images/1"))
        assertNull(WebFetchClient.normalize("javascript:alert(1)"))
        assertNull(WebFetchClient.normalize("ftp://example.com"))
    }

    @Test
    fun `something with no host at all is refused`() {
        assertNull(WebFetchClient.normalize(""))
        assertNull(WebFetchClient.normalize("just some words"))
        assertNull(WebFetchClient.normalize("https:///nohost"))
    }

    // ---- markup to prose --------------------------------------------------

    private val page = """
        <!doctype html><html><head>
          <title>  The  Tides </title>
          <style>body { color: red }</style>
          <script>var x = "<p>not text</p>";</script>
        </head><body>
          <!-- a comment -->
          <h1>High water</h1>
          <p>The tide came in &amp; went out.</p>
          <ul><li>One</li><li>Two</li></ul>
          <p>Caf&#233; closed &mdash; see&nbsp;you.</p>
        </body></html>
    """.trimIndent()

    @Test
    fun `the title is read and collapsed`() {
        assertEquals("The Tides", WebFetchClient.readable(page).title)
    }

    @Test
    fun `scripts, styles and comments are not text`() {
        val text = WebFetchClient.readable(page).text
        assertFalse("color: red" in text)
        assertFalse("var x" in text)
        assertFalse("not text" in text)
        assertFalse("a comment" in text)
    }

    @Test
    fun `entities are decoded and the structure survives as lines`() {
        val text = WebFetchClient.readable(page).text
        assertTrue("The tide came in & went out." in text)
        assertTrue("Café closed — see you." in text)
        // A list reads far better to a model as lines than as one sentence.
        assertTrue(text.lineSequence().any { it == "One" })
        assertTrue(text.lineSequence().any { it == "Two" })
        // And no markup is left behind.
        assertFalse('<' in text)
    }

    @Test
    fun `a long page is cut at the budget on a word boundary`() {
        val long = "<p>" + "word ".repeat(500) + "</p>"
        val read = WebFetchClient.readable(long, maxChars = 100)
        assertTrue(read.truncated)
        assertTrue(read.text.length <= 100)
        assertTrue(read.text.endsWith("word"))
    }

    @Test
    fun `a page inside the budget is not marked cut`() {
        val read = WebFetchClient.readable("<p>short</p>", maxChars = 100)
        assertFalse(read.truncated)
        assertEquals("short", read.text)
    }
}
