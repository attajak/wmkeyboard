package com.wasimaster.wmkeyboard.ime.aichat

import com.wasimaster.wmkeyboard.core.grammar.GrammarLint
import com.wasimaster.wmkeyboard.core.tools.DictDefinition
import com.wasimaster.wmkeyboard.core.tools.DictEntry
import com.wasimaster.wmkeyboard.core.tools.DictMeaning
import com.wasimaster.wmkeyboard.core.tools.WikipediaClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the tools hand the AI chat with Ask AI (#352). */
class AskAiContextTest {

    private val summary = WikipediaClient.Summary(
        title = "Cat",
        description = "Small domesticated carnivorous mammal",
        extract = "The cat is a small carnivorous mammal.",
        url = "https://en.wikipedia.org/wiki/Cat",
    )

    @Test
    fun `a selection comes with its source and its paragraph`() {
        val text = AskAiContext.selection("carnivorous", "the Wikipedia article \"Cat\"", summary.extract)
        assertTrue(text.startsWith("A passage selected from the Wikipedia article \"Cat\""))
        assertTrue("\"carnivorous\"" in text)
        assertTrue(summary.extract in text)
    }

    @Test
    fun `a selection of the whole block does not repeat it`() {
        val text = AskAiContext.selection(summary.extract, "x", summary.extract)
        assertFalse("paragraph it is in" in text)
    }

    @Test
    fun `a huge block is left out rather than cut`() {
        val block = "word ".repeat(AskAiContext.MAX_SURROUNDING)
        assertFalse("paragraph it is in" in AskAiContext.selection("word", "x", block))
    }

    @Test
    fun `an article prefers its full text over the summary`() {
        val full = AskAiContext.wikipedia(summary, "Full text of the article.")
        assertTrue("Wikipedia article: Cat" in full)
        assertTrue("URL: https://en.wikipedia.org/wiki/Cat" in full)
        assertTrue("Full text of the article." in full)
        assertFalse(summary.extract in full)
        assertTrue(summary.extract in AskAiContext.wikipedia(summary, null))
    }

    @Test
    fun `a dictionary entry numbers its definitions under their part of speech`() {
        val entry = DictEntry(
            word = "run",
            phonetic = "/rʌn/",
            audioUrl = null,
            meanings = listOf(
                DictMeaning(
                    partOfSpeech = "verb",
                    definitions = listOf(
                        DictDefinition("Move fast on foot.", "She ran home.", listOf("sprint")),
                        DictDefinition("Operate.", null, emptyList()),
                    ),
                    synonyms = emptyList(),
                    antonyms = listOf("walk"),
                ),
            ),
        )
        val text = AskAiContext.dictionary(listOf(entry))
        assertTrue("Dictionary entry: run /rʌn/" in text)
        assertTrue("verb:\n1. Move fast on foot.\n   Example: \"She ran home.\"\n2. Operate." in text)
        assertTrue("Synonyms: sprint" in text)
        assertTrue("Antonyms: walk" in text)
    }

    @Test
    fun `a grammar issue carries its sentence and nothing past it`() {
        val source = "I like it. Their going home now. See you."
        val start = source.indexOf("Their")
        val lint = GrammarLint(start = start, end = start + 5, original = "Their", kind = "WordChoice", message = "Did you mean they're?")
        val text = AskAiContext.grammar(lint, listOf("They're"), source, "Word choice")
        assertTrue("The sentence: Their going home now." in text)
        assertTrue("Suggested fixes: \"They're\"" in text)
        assertFalse("See you" in text)
    }

    @Test
    fun `the sentence stops at a line break without keeping it`() {
        assertEquals("second line", AskAiContext.sentenceAround("first\nsecond line\nthird", 8, 10))
    }
}
