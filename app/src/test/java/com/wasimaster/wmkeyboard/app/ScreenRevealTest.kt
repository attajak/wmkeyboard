package com.wasimaster.wmkeyboard.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The rules behind the settings entrance: which groups compose in the frame
 * the screen opens in, and which wait for the wave.
 *
 * Written against the real group shapes of three screens, because the bug this
 * pins was invisible in the abstract and obvious against them — the mode
 * editor's eighteen-row group was composing during the entrance and costing a
 * third of a second on a mid-range phone, while screens made of small groups
 * were landing on the budget exactly and running clean.
 */
class ScreenRevealTest {

    /** Runs [groups] through a fresh ledger; returns the rows composed eagerly. */
    private fun eagerRows(vararg groups: Int): Int {
        val reveal = ScreenReveal()
        var eager = 0
        for (rows in groups) {
            if (reveal.claimGroup(rows) == null) eager += rows
        }
        return eager
    }

    /** The queue place each group was given; null where it composed at once. */
    private fun places(vararg groups: Int): List<Int?> {
        val reveal = ScreenReveal()
        return groups.map { reveal.claimGroup(it) }
    }

    @Test
    fun `small groups fill the budget and stop`() {
        // Layout & size: 3, 4, 2, 1, 2, 4. Five groups fit in twelve rows; the
        // sixth waits. This screen was already behaving, and has to keep doing
        // exactly this.
        assertEquals(12, eagerRows(3, 4, 2, 1, 2, 4))
        assertEquals(listOf(null, null, null, null, null, 0), places(3, 4, 2, 1, 2, 4))
    }

    @Test
    fun `a group landing exactly on the budget still composes`() {
        // Typing: 6, 6, 1, 2. The second six lands on twelve, not past it.
        assertEquals(12, eagerRows(6, 6, 1, 2))
        assertEquals(listOf(null, null, 0, 1), places(6, 6, 1, 2))
    }

    @Test
    fun `a group larger than the budget waits instead of sailing through`() {
        // The mode editor: 3, 18, 6. The eighteen no longer rides in on the
        // three ahead of it, which is what cost the entrance its frames.
        assertEquals(3, eagerRows(3, 18, 6))
        assertEquals(listOf(null, 0, 1), places(3, 18, 6))
    }

    @Test
    fun `the first group composes however big it is`() {
        // Otherwise a screen that opens with one long group opens as nothing
        // but skeletons, which is worse than the wait being spread out.
        assertEquals(40, eagerRows(40, 2))
        assertEquals(listOf(null, 0), places(40, 2))
    }

    @Test
    fun `a small group behind a deferred one waits its turn`() {
        // Order, not size, decides once the queue has started: filling around
        // the gap a deferred group left reads as the page assembling itself
        // out of sequence.
        assertEquals(listOf(null, 0, 1, 2), places(11, 18, 1, 1))
    }

    @Test
    fun `nothing waits once the entrance has settled`() {
        val reveal = ScreenReveal().apply { isSettled = true }
        assertNull(reveal.claimGroup(40))
        assertNull(reveal.claimGroup(40))
        assertEquals(0, reveal.deferredCount)
    }

    @Test
    fun `queue places are handed out in order and count the queue`() {
        val reveal = ScreenReveal()
        assertNull(reveal.claimGroup(12))
        assertEquals(0, reveal.claimGroup(3))
        assertEquals(1, reveal.claimGroup(3))
        assertEquals(2, reveal.claimGroup(3))
        assertEquals(3, reveal.deferredCount)
    }
}
