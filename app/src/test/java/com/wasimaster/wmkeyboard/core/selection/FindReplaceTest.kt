package com.wasimaster.wmkeyboard.core.selection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FindReplaceTest {

    private val text = "The cat sat on the concatenated mat. THE CAT."

    private fun ranges(result: FindResult): List<IntRange> = (result as FindResult.Matches).ranges

    @Test
    fun `plain find ignores case unless asked`() {
        assertEquals(3, ranges(FindReplace.find(text, "the", FindOptions())).size)
        assertEquals(1, ranges(FindReplace.find(text, "The", FindOptions(matchCase = true))).size)
        assertEquals(0, ranges(FindReplace.find(text, "", FindOptions())).size)
    }

    @Test
    fun `whole word is bounded by letters in any script`() {
        assertEquals(2, ranges(FindReplace.find(text, "cat", FindOptions(wholeWord = true))).size)
        assertEquals(3, ranges(FindReplace.find(text, "cat", FindOptions())).size)
        assertEquals(1, ranges(FindReplace.find("আমি আমিও", "আমি", FindOptions(wholeWord = true))).size)
    }

    @Test
    fun `regex mode, bad patterns and the time budget`() {
        assertEquals(listOf(0..2), ranges(FindReplace.find("123 abc", """\d+""", FindOptions(regex = true))))
        assertTrue(FindReplace.find(text, "(", FindOptions(regex = true)) is FindResult.BadPattern)
        // The JVM's engine memoises the classic exponential patterns, so the
        // budget is exercised with a clock that runs a millisecond per read
        // check rather than with a pattern that only hangs on Android.
        var ticks = 0L
        val slow = FindReplace.find("a".repeat(4000), "a+", FindOptions(regex = true), budgetMs = 2) { ticks += 1_000_000; ticks }
        assertEquals(FindResult.TimedOut, slow)
    }

    @Test
    fun `the match count stops at the cap`() {
        val many = "a ".repeat(FindReplace.MAX_MATCHES + 10)
        val result = FindReplace.find(many, "a", FindOptions()) as FindResult.Matches
        assertEquals(FindReplace.MAX_MATCHES, result.ranges.size)
        assertTrue(result.truncated)
    }

    @Test
    fun `next wraps and previous walks back`() {
        assertEquals(4..6, FindReplace.next(text, "cat", FindOptions(wholeWord = true), from = 0))
        assertEquals(41..43, FindReplace.next(text, "cat", FindOptions(wholeWord = true), from = 5))
        assertEquals(4..6, FindReplace.next(text, "cat", FindOptions(wholeWord = true), from = 42))
        assertNull(FindReplace.next(text, "cat", FindOptions(wholeWord = true), from = 42, wrap = false))
        assertEquals(41..43, FindReplace.previous(text, "cat", FindOptions(wholeWord = true), from = 4))
        assertEquals(4..6, FindReplace.previous(text, "cat", FindOptions(wholeWord = true), from = 41))
        assertNull(FindReplace.next(text, "dog", FindOptions(), from = 0))
    }

    @Test
    fun `replace one and replace all, literal and with groups`() {
        val edit = FindReplace.replaceOne("a cat", 2..4, "\$dog", "cat", FindOptions())
        assertEquals(TextEdit(2, 5, "\$dog"), edit)
        val edits = FindReplace.replaceAll("x1 y22", listOf(1..1, 4..5), "<$1>", """(\d+)""", FindOptions(regex = true))
        assertEquals(listOf(TextEdit(4, 6, "<22>"), TextEdit(1, 2, "<1>")), edits)
        assertEquals("x<1> y<22>", FindReplace.apply("x1 y22", edits))
    }
}
