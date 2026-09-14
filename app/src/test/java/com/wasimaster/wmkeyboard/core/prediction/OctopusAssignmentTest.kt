package com.wasimaster.wmkeyboard.core.prediction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The octopus (discussion #102) makes exactly one promise: the word floating
 * over a key is the word you get by pressing that key next. Everything here
 * pins that promise, because it is the difference between a feature and a lie
 * about the affordance — a word over the wrong key teaches the user to distrust
 * every word on the board.
 */
class OctopusAssignmentTest {

    /** A 1:1 board where every letter is its own key. */
    private val everyLetter: (Int) -> Int = { it }

    private fun candidate(word: String, score: Double, edits: Int = 0) = OctopusCandidate(
        word, score,
        if (edits == 0) OctopusKind.COMPLETION else OctopusKind.CORRECTION,
        edits,
    )

    private fun assign(
        typed: String,
        candidates: List<OctopusCandidate>,
        keys: KeySets? = null,
        keyOf: (Int) -> Int = everyLetter,
        limit: Int = 8,
        spread: Double = Double.POSITIVE_INFINITY,
        perKey: Int = 1,
    ) = assignOctopus(typed, candidates, keys, keyOf, limit, spread, perKey)

    // ---- a key carrying more than one word (#136) ----

    @Test
    fun `a key honours as many claims as the stack allows, in rank order`() {
        // "the", "they" and "there" all continue "th" with an e.
        val floated = assign(
            "th",
            listOf(candidate("the", 3.0), candidate("they", 2.0), candidate("there", 1.0), candidate("this", 0.5)),
            perKey = 2,
        )
        assertEquals(listOf("the", "they", "this"), floated.map { it.word })
        assertEquals(listOf(0, 1, 0), floated.map { it.tier })
        assertEquals(listOf('e'.code, 'e'.code, 'i'.code), floated.map { it.keyCodePoint })
    }

    @Test
    fun `one word per key is still the rule when the stack is one`() {
        val floated = assign(
            "th",
            listOf(candidate("the", 3.0), candidate("they", 2.0)),
            perKey = 1,
        )
        assertEquals(listOf("the"), floated.map { it.word })
        assertEquals(0, floated.single().tier)
    }

    private fun only(typed: String, word: String, edits: Int = 0): OctopusWord? =
        assign(typed, listOf(candidate(word, 1.0, edits))).firstOrNull()

    // ---- the one rule, in every shape the strip can hand us ----

    @Test
    fun `a completion hangs off the character after the prefix`() {
        val floated = only("hel", "hello")!!
        assertEquals('l'.code, floated.keyCodePoint)
        assertEquals(3, floated.typedChars)
    }

    @Test
    fun `a substitution hangs off the character that was got wrong`() {
        val floated = only("helli", "hello", edits = 1)!!
        assertEquals('o'.code, floated.keyCodePoint)
        assertEquals(4, floated.typedChars)
    }

    @Test
    fun `an insertion hangs off the character that should have been typed`() {
        val floated = only("helo", "hello", edits = 1)!!
        assertEquals('l'.code, floated.keyCodePoint)
        assertEquals(3, floated.typedChars)
    }

    @Test
    fun `a deletion hangs off the first character that disagrees`() {
        val floated = only("helllo", "hello", edits = 1)!!
        assertEquals('o'.code, floated.keyCodePoint)
        assertEquals(4, floated.typedChars)
    }

    @Test
    fun `an empty buffer hangs every word off its first letter`() {
        val words = assign(
            "",
            listOf(
                OctopusCandidate("and", 1.0, OctopusKind.NEXT_WORD),
                OctopusCandidate("speak", 0.9, OctopusKind.NEXT_WORD),
                OctopusCandidate("playing", 0.8, OctopusKind.NEXT_WORD),
            ),
        )
        assertEquals(
            listOf('a'.code, 's'.code, 'p'.code), words.map { it.keyCodePoint },
        )
        assertTrue("nothing is typed yet", words.all { it.typedChars == 0 })
    }

    @Test
    fun `the key always types the character the word needs next`() {
        // The promise, stated directly: whatever the edit was, word[typedChars]
        // is the character the flicked key produces.
        for (word in listOf("hello", "help", "held")) {
            for (typed in listOf("hel", "hell", "hekl", "he")) {
                val floated = only(typed, word, edits = 1) ?: continue
                assertEquals(
                    "$typed -> $word",
                    floated.word[floated.typedChars].code,
                    floated.keyCodePoint,
                )
            }
        }
    }

    // ---- what cannot float ----

    @Test
    fun `the typed word itself has no next key`() {
        assertNull(only("hello", "hello"))
    }

    @Test
    fun `a candidate shorter than the buffer has no next key`() {
        // "hell" is a prefix of what was typed: pressing anything does not
        // reach it, so there is nowhere to hang it.
        assertNull(only("hello", "hell"))
    }

    @Test
    fun `a character this board cannot type in one press is dropped`() {
        // The emoji next-word prediction can return, or a long-press-only
        // glyph: keyOf says -1 and the word simply does not appear.
        val words = assign(
            "",
            listOf(
                OctopusCandidate("😀", 2.0, OctopusKind.NEXT_WORD),
                OctopusCandidate("and", 1.0, OctopusKind.NEXT_WORD),
            ),
            keyOf = { if (it > 0xFFFF) -1 else it },
        )
        assertEquals(listOf("and"), words.map { it.word })
    }

    // ---- ambiguous boards ----

    @Test
    fun `an ambiguous board resolves divergence through the key sets`() {
        // A T9 board: the buffer holds the anchor letter each key commits, so
        // three presses of abc/ghi/ghi to write "big" leave "agg" behind.
        val keys = KeySets.of(listOf("abc", "ghi", "ghi"))!!
        assertEquals(3, octopusDivergence("agg", "big", keys))
        // Without the key sets the very first character disagrees, and every
        // candidate would hang off its own first letter — the bug this guards.
        assertEquals(0, octopusDivergence("agg", "big", null))
        val floated = assign("agg", listOf(candidate("bigger", 1.0)), keys = keys).single()
        assertEquals('g'.code, floated.keyCodePoint)
        assertEquals(3, floated.typedChars)
    }

    @Test
    fun `two letters sharing a key count as one slot`() {
        // On a compact grid `q` and `w` are one key; the better word takes it
        // and the other is dropped rather than moved.
        val shared: (Int) -> Int = { if (it == 'w'.code) 'q'.code else it }
        val words = assign(
            "",
            listOf(
                OctopusCandidate("quite", 2.0, OctopusKind.NEXT_WORD),
                OctopusCandidate("were", 1.0, OctopusKind.NEXT_WORD),
                OctopusCandidate("and", 0.5, OctopusKind.NEXT_WORD),
            ),
            keyOf = shared,
        )
        assertEquals(listOf("quite", "and"), words.map { it.word })
    }

    // ---- one word per key, one key per word ----

    @Test
    fun `the better word takes a contested key and the loser is dropped not moved`() {
        val words = assign("hel", listOf(candidate("hello", 2.0), candidate("hellish", 1.0)))
        assertEquals(listOf("hello"), words.map { it.word })
        assertEquals('l'.code, words.single().keyCodePoint)
    }

    @Test
    fun `the same word never floats twice`() {
        val words = assign(
            "",
            listOf(
                OctopusCandidate("and", 2.0, OctopusKind.NEXT_WORD),
                OctopusCandidate("And", 1.0, OctopusKind.NEXT_WORD),
            ),
        )
        assertEquals(listOf("and"), words.map { it.word })
    }

    @Test
    fun `a word rejected as a duplicate does not hold the key hostage`() {
        // "And" loses to "and" on the word claim; `a` must still be free for
        // the next candidate that wants it.
        val words = assign(
            "",
            listOf(
                OctopusCandidate("and", 3.0, OctopusKind.NEXT_WORD),
                OctopusCandidate("And", 2.0, OctopusKind.NEXT_WORD),
            ),
            keyOf = { 'a'.code },
        )
        assertEquals(listOf("and"), words.map { it.word })
    }

    // ---- ranking and trimming ----

    @Test
    fun `ties break by word so the same buffer paints the same board`() {
        val first = assign("", listOf(
            OctopusCandidate("beta", 1.0, OctopusKind.NEXT_WORD),
            OctopusCandidate("alpha", 1.0, OctopusKind.NEXT_WORD),
        ))
        val second = assign("", listOf(
            OctopusCandidate("alpha", 1.0, OctopusKind.NEXT_WORD),
            OctopusCandidate("beta", 1.0, OctopusKind.NEXT_WORD),
        ))
        assertEquals(listOf("alpha", "beta"), first.map { it.word })
        assertEquals(first, second)
    }

    @Test
    fun `rank counts the words that floated, not the ones considered`() {
        val words = assign("hel", listOf(
            candidate("hello", 3.0),
            candidate("hellish", 2.5), // loses `l` to hello
            candidate("help", 2.0),
            candidate("held", 1.0),
        ))
        assertEquals(listOf("hello", "help", "held"), words.map { it.word })
        assertEquals(listOf(0, 1, 2), words.map { it.rank })
    }

    @Test
    fun `the limit keeps the best`() {
        val words = assign("hel", listOf(
            candidate("hello", 3.0), candidate("help", 2.0), candidate("held", 1.0),
        ), limit = 2)
        assertEquals(listOf("hello", "help"), words.map { it.word })
    }

    @Test
    fun `the score floor lets the board go quiet instead of padding`() {
        // Sparse mode has room for three, but only one word is anywhere near
        // as good as the best. A Z10 shows one word, not one word and two
        // guesses.
        val words = assign(
            "hel",
            listOf(candidate("hello", 10.0), candidate("help", 2.0), candidate("held", 1.0)),
            limit = 3,
            spread = 4.0,
        )
        assertEquals(listOf("hello"), words.map { it.word })
    }

    @Test
    fun `an empty candidate list floats nothing`() {
        assertEquals(emptyList<OctopusWord>(), assign("hel", emptyList()))
    }
}
