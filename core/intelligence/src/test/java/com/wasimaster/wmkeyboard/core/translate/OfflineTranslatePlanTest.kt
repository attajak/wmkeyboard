package com.wasimaster.wmkeyboard.core.translate

import com.wasimaster.wmkeyboard.core.translate.OfflineTranslatePlan.Candidate
import com.wasimaster.wmkeyboard.core.translate.OfflineTranslatePlan.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The decisions around an on-device translation that need no engine: which
 * model a tag means, what the source language is when the identifier is not
 * sure, and how a message keeps its line breaks.
 */
class OfflineTranslatePlanTest {

    // ---- model codes --------------------------------------------------------

    @Test
    fun `picker codes and keyboard ids map onto model codes`() {
        assertEquals("bn", OfflineTranslateLanguages.modelCode("bn"))
        assertEquals("zh", OfflineTranslateLanguages.modelCode("zh-CN"))
        assertEquals("pt", OfflineTranslateLanguages.modelCode("pt-BR"))
        assertEquals("en", OfflineTranslateLanguages.modelCode("en_US"))
        // The names other systems use for the same languages.
        assertEquals("he", OfflineTranslateLanguages.modelCode("iw"))
        assertEquals("id", OfflineTranslateLanguages.modelCode("in"))
        assertEquals("no", OfflineTranslateLanguages.modelCode("nb"))
        assertEquals("tl", OfflineTranslateLanguages.modelCode("fil"))
    }

    @Test
    fun `a language with no model is null, not a near miss`() {
        assertNull(OfflineTranslateLanguages.modelCode("ml"))
        assertNull(OfflineTranslateLanguages.modelCode("ne"))
        assertNull(OfflineTranslateLanguages.modelCode(""))
        // The Chinese model is Simplified. Traditional must not borrow it.
        assertNull(OfflineTranslateLanguages.modelCode("zh-TW"))
        assertNull(OfflineTranslateLanguages.modelCode("zh-Hant"))
    }

    @Test
    fun `romanised text is refused, and named for what it is`() {
        assertNull(OfflineTranslateLanguages.modelCode("hi-Latn"))
        assertTrue(OfflineTranslateLanguages.isRomanized("hi-Latn"))
        assertTrue(OfflineTranslateLanguages.isRomanized("ja-Latn"))
        assertFalse(OfflineTranslateLanguages.isRomanized("hi"))
        assertFalse(OfflineTranslateLanguages.isRomanized("en-Latn"))
        assertEquals("hi", OfflineTranslateLanguages.baseLanguage("hi-Latn"))
    }

    @Test
    fun `english is never a model to download`() {
        assertEquals(listOf("bn"), OfflineTranslateLanguages.modelsNeeded("bn", "en"))
        assertEquals(listOf("de"), OfflineTranslateLanguages.modelsNeeded("en", "de"))
        assertEquals(listOf("bn", "de"), OfflineTranslateLanguages.modelsNeeded("bn", "de"))
        assertEquals(emptyList<String>(), OfflineTranslateLanguages.modelsNeeded("en", "en"))
    }

    // ---- source language ----------------------------------------------------

    @Test
    fun `the user's pick outranks everything`() {
        val source = OfflineTranslatePlan.resolveSource(
            override = "fr",
            candidates = listOf(Candidate("es", 0.99f)),
            hints = listOf("de"),
            target = "en",
        )
        assertEquals(Source.Known("fr", guessed = false), source)
    }

    @Test
    fun `a confident identification is believed`() {
        val source = OfflineTranslatePlan.resolveSource(
            override = "",
            candidates = listOf(Candidate("es", 0.2f), Candidate("bn", 0.97f)),
            hints = listOf("en"),
            target = "en",
        )
        assertEquals(Source.Known("bn", guessed = false), source)
    }

    @Test
    fun `a weak candidate the keyboard agrees with is not a guess`() {
        val source = OfflineTranslatePlan.resolveSource(
            override = null,
            candidates = listOf(Candidate("it", 0.3f), Candidate("fr", 0.25f)),
            hints = listOf("fr", "en"),
            target = "en",
        )
        assertEquals(Source.Known("fr", guessed = false), source)
    }

    @Test
    fun `with nothing from the identifier the keyboard language stands in, marked`() {
        val source = OfflineTranslatePlan.resolveSource(
            override = null,
            candidates = listOf(Candidate(OfflineTranslatePlan.UNDETERMINED, 1f)),
            hints = listOf("bn", "en"),
            target = "en",
        )
        assertEquals(Source.Known("bn", guessed = true), source)
    }

    @Test
    fun `the target is never guessed as the source`() {
        val source = OfflineTranslatePlan.resolveSource(
            override = null,
            candidates = emptyList(),
            hints = listOf("en"),
            target = "en",
        )
        assertEquals(Source.Unknown, source)
    }

    @Test
    fun `confident romanised text is unreadable, not quietly guessed around`() {
        val source = OfflineTranslatePlan.resolveSource(
            override = null,
            candidates = listOf(Candidate("hi-Latn", 0.9f)),
            hints = listOf("en"),
            target = "bn",
        )
        assertEquals(Source.Unreadable("hi-Latn", romanized = true), source)
    }

    @Test
    fun `a confident language with no model is unreadable`() {
        val source = OfflineTranslatePlan.resolveSource(
            override = null,
            candidates = listOf(Candidate("ml", 0.95f)),
            hints = listOf("en"),
            target = "en",
        )
        assertEquals(Source.Unreadable("ml", romanized = false), source)
    }

    // ---- line structure -----------------------------------------------------

    @Test
    fun `line breaks, blank lines and indentation survive`() {
        val text = "Hello there\n\n  - second line  \n123\nlast"
        val segments = OfflineTranslatePlan.segments(text)
        val bodies = segments.filter { it.body.isNotEmpty() }.map { it.body }
        assertEquals(listOf("Hello there", "- second line", "last"), bodies)
        val joined = OfflineTranslatePlan.join(segments, bodies.map { it.uppercase() })
        assertEquals("HELLO THERE\n\n  - SECOND LINE  \n123\nLAST", joined)
    }

    @Test
    fun `text with nothing to translate round-trips untouched`() {
        val text = "  \n12:30\n:)"
        val segments = OfflineTranslatePlan.segments(text)
        assertTrue(segments.all { it.body.isEmpty() })
        assertEquals(text, OfflineTranslatePlan.join(segments, emptyList()))
    }

    @Test
    fun `a single line is a single segment`() {
        val segments = OfflineTranslatePlan.segments("bonjour")
        assertEquals(1, segments.size)
        assertEquals("salut", OfflineTranslatePlan.join(segments, listOf("salut")))
    }
}
