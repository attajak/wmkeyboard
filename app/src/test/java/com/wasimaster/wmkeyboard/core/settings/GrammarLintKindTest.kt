package com.wasimaster.wmkeyboard.core.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The filter's half of the grammar tool: mapping the engine's names for its
 * issue kinds onto the ones the app can hide.
 */
class GrammarLintKindTest {

    @Test
    fun `every Harper kind maps to one of ours`() {
        // The names Harper's LintKind puts on the wire, Display spelling and all.
        val wireNames = listOf(
            "Agreement", "BoundaryError", "Capitalization", "Eggcorn", "Enhancement",
            "Formatting", "Grammar", "Malapropism", "Miscellaneous", "Nonstandard",
            "Punctuation", "Readability", "Redundancy", "Regionalism", "Repetition",
            "Spelling", "Style", "Typo", "Usage", "Word Choice",
        )
        val mapped = wireNames.map { name ->
            requireNotNull(GrammarLintKind.forKind(name)) { "no kind for $name" }
        }
        assertEquals(GrammarLintKind.entries.size, wireNames.size)
        assertEquals(GrammarLintKind.entries.toSet(), mapped.toSet())
    }

    /**
     * Harper's Display spells this one with a space while every other kind is
     * its variant name. Matching letters-only is what keeps it out of the
     * "unknown, show it anyway" bucket — where it sat, mis-coloured, before.
     */
    @Test
    fun `word choice is matched however it is spelled`() {
        val expected = GrammarLintKind.WORD_CHOICE
        assertEquals(expected, GrammarLintKind.forKind("Word Choice"))
        assertEquals(expected, GrammarLintKind.forKind("WordChoice"))
        assertEquals(expected, GrammarLintKind.forKind("word choice"))
        assertEquals(GrammarCategory.CLARITY, expected.category)
    }

    @Test
    fun `a kind we do not know is shown rather than hidden`() {
        assertNull(GrammarLintKind.forKind("Vibes"))
        assertTrue(GrammarLintKind.isVisible("Vibes", GrammarLintKind.entries.toSet()))
        assertTrue(GrammarLintKind.isVisible("", GrammarLintKind.entries.toSet()))
    }

    @Test
    fun `hiding a kind hides only that kind`() {
        val hidden = setOf(GrammarLintKind.STYLE)
        assertFalse(GrammarLintKind.isVisible("Style", hidden))
        assertTrue(GrammarLintKind.isVisible("Enhancement", hidden))
        assertTrue(GrammarLintKind.isVisible("Spelling", emptySet()))
    }

    @Test
    fun `categories partition the kinds`() {
        val byCategory = GrammarCategory.entries.flatMap { GrammarLintKind.of(it) }
        assertEquals(GrammarLintKind.entries, byCategory.sortedBy { it.ordinal })
        assertEquals(GrammarLintKind.entries.size, byCategory.toSet().size)
        GrammarCategory.entries.forEach { category ->
            assertTrue(GrammarLintKind.of(category).isNotEmpty())
        }
    }

    @Test
    fun `spelling and typos are correctness, style is engagement`() {
        assertEquals(GrammarCategory.CORRECTNESS, GrammarLintKind.SPELLING.category)
        assertEquals(GrammarCategory.CORRECTNESS, GrammarLintKind.TYPO.category)
        assertEquals(GrammarCategory.ENGAGEMENT, GrammarLintKind.STYLE.category)
        assertEquals(GrammarCategory.DELIVERY, GrammarLintKind.REGIONALISM.category)
    }
}
