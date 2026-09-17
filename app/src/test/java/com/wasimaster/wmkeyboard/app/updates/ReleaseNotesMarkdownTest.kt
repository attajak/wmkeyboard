package com.wasimaster.wmkeyboard.app.updates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the release-note dialog has to make of the files in `release-notes/`. */
class ReleaseNotesMarkdownTest {

    private fun List<NotesSpan>.text() = joinToString("") { it.text }

    @Test
    fun `a heading keeps its level and its text`() {
        val blocks = ReleaseNotesMarkdown.parse("### Swipe typing")
        val heading = blocks.single() as NotesBlock.Heading
        assertEquals(3, heading.level)
        assertEquals("Swipe typing", heading.spans.text())
    }

    @Test
    fun `a wrapped paragraph becomes one line`() {
        val blocks = ReleaseNotesMarkdown.parse(
            """
            The biggest release so far. Swipe typing gets a second chance
            at every word.
            """.trimIndent(),
        )
        val paragraph = blocks.single() as NotesBlock.Paragraph
        assertEquals(
            "The biggest release so far. Swipe typing gets a second chance at every word.",
            paragraph.spans.text(),
        )
    }

    @Test
    fun `a bullet keeps its continuation lines and drops the marker`() {
        val blocks = ReleaseNotesMarkdown.parse(
            """
            - Hyphenated words can be glided. Swipe straight through
              the hyphen.
            - A second item.
            """.trimIndent(),
        )
        assertEquals(2, blocks.size)
        val first = blocks[0] as NotesBlock.Bullet
        assertEquals("Hyphenated words can be glided. Swipe straight through the hyphen.", first.spans.text())
        assertEquals("A second item.", (blocks[1] as NotesBlock.Bullet).spans.text())
    }

    @Test
    fun `bold italic and code are styled, and the markers go`() {
        val spans = (ReleaseNotesMarkdown.parse("**bold** *lean* `code`").single() as NotesBlock.Paragraph).spans
        assertEquals("bold lean code", spans.text())
        assertTrue(spans.single { it.text == "bold" }.bold)
        assertTrue(spans.single { it.text == "lean" }.italic)
        assertTrue(spans.single { it.text == "code" }.code)
    }

    @Test
    fun `a link keeps its label and its url`() {
        val spans = (
            ReleaseNotesMarkdown
                .parse("stroke ([#135](https://github.com/wasi-master/wmkeyboard/issues/135)).")
                .single() as NotesBlock.Paragraph
            ).spans
        assertEquals("stroke (#135).", spans.text())
        val link = spans.single { it.link != null }
        assertEquals("#135", link.text)
        assertEquals("https://github.com/wasi-master/wmkeyboard/issues/135", link.link)
    }

    @Test
    fun `a link inside bold keeps both`() {
        val spans = (
            ReleaseNotesMarkdown.parse("**see [#1](https://e.com/1)**").single() as NotesBlock.Paragraph
            ).spans
        val link = spans.single { it.link != null }
        assertEquals("#1", link.text)
        assertTrue(link.bold)
    }

    @Test
    fun `a marker that is not a marker stays text`() {
        // The rule AiMarkdown follows, and for the same reason: a changelog is
        // prose with file names and arithmetic in it.
        val spans = (ReleaseNotesMarkdown.parse("2 * 3 * 4 and a_b_c").single() as NotesBlock.Paragraph).spans
        assertEquals("2 * 3 * 4 and a_b_c", spans.text())
        assertTrue(spans.none { it.italic })
    }

    @Test
    fun `a fence keeps its lines and loses its fences`() {
        val blocks = ReleaseNotesMarkdown.parse(
            """
            Run it:

            ```sh
            ./gradlew assembleFullDebug
            ```
            """.trimIndent(),
        )
        assertEquals("./gradlew assembleFullDebug", (blocks.last() as NotesBlock.Code).text)
    }

    @Test
    fun `a rule is a rule and not a bullet`() {
        assertEquals(NotesBlock.Rule, ReleaseNotesMarkdown.parse("---").single())
    }

    @Test
    fun `the short store changelog still reads as one paragraph`() {
        // The fallback when a release has no markdown file: plain prose, no
        // markers, and it must not come out empty or in pieces.
        val text = "A swiped word can be searched again. Hyphenated words glide."
        val blocks = ReleaseNotesMarkdown.parse(text)
        assertEquals(text, (blocks.single() as NotesBlock.Paragraph).spans.text())
    }

    @Test
    fun `empty text is no blocks rather than one empty one`() {
        assertEquals(emptyList<NotesBlock>(), ReleaseNotesMarkdown.parse(""))
        assertEquals(emptyList<NotesBlock>(), ReleaseNotesMarkdown.parse("\n\n   \n"))
    }
}
