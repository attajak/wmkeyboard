package com.wasimaster.wmkeyboard.core.gesture

import com.wasimaster.wmkeyboard.core.prediction.FuzzyBeamSearch
import com.wasimaster.wmkeyboard.core.prediction.SuggestionEngine
import com.wasimaster.wmkeyboard.core.prediction.Trie
import com.wasimaster.wmkeyboard.core.prediction.UserLexicon
import com.wasimaster.wmkeyboard.core.snippets.Snippet
import com.wasimaster.wmkeyboard.core.snippets.SnippetIndex
import com.wasimaster.wmkeyboard.core.transliteration.BengaliPhoneticIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A text-expansion trigger can be glided (issue #170).
 *
 * "omw" is in no word list, so a stroke over o-m-w had nothing to decode to and
 * the trigger could only ever be tapped out. The keyboard now hands its
 * triggers to the glide decoder as a source of their own, and expands what the
 * stroke read at the commit.
 */
class GlideTriggerDecodeTest {

    private val keyWidth = 60f
    private val keys: List<KeyCenter> = buildList {
        "qwertyuiop".forEachIndexed { i, c -> add(KeyCenter(c, 30f + i * 60f, 30f)) }
        "asdfghjkl".forEachIndexed { i, c -> add(KeyCenter(c, 60f + i * 60f, 90f)) }
        "zxcvbnm".forEachIndexed { i, c -> add(KeyCenter(c, 90f + i * 60f, 150f)) }
    }
    private val centers = keys.associateBy { it.codePoint }
    private val grid = GlideKeyMap.of(keys, keyWidth)

    /** Straight lines through the word's key centres, densely sampled. */
    private fun gestureFor(word: String): List<GesturePoint> {
        val anchors = word.toCharArray().toList()
            .fold(ArrayList<Char>()) { acc, c -> acc.also { if (acc.lastOrNull() != c) acc.add(c) } }
            .mapNotNull { centers[it.code] }
        val points = ArrayList<GesturePoint>()
        for (i in 0 until anchors.size - 1) {
            val a = anchors[i]
            val b = anchors[i + 1]
            for (step in 0..10) {
                val t = step / 10f
                points.add(GesturePoint(a.x + t * (b.x - a.x), a.y + t * (b.y - a.y)))
            }
        }
        return points
    }

    private fun engine() = SuggestionEngine(
        Trie().apply {
            insert("own", 400)
            insert("mow", 300)
        },
        BengaliPhoneticIndex(emptyList()),
        UserLexicon(null),
    ).apply { englishSources = true }

    @Test
    fun `a stroke over a trigger reads the trigger once the engine knows it`() {
        val e = engine()
        val before = e.glide(gestureFor("omw"), grid, keyWidth, limit = 6).map { it.word }
        assertFalse("no source spells omw yet: $before", "omw" in before)

        e.glideTriggers = SuggestionEngine.triggerSource(listOf("omw", "brb"))
        val after = e.glide(gestureFor("omw"), grid, keyWidth, limit = 6).map { it.word }
        assertEquals("the trigger is the stroke's reading: $after", "omw", after.first())
    }

    @Test
    fun `a learned-words-only glide still finds a trigger`() {
        val e = engine()
        e.glideTriggers = SuggestionEngine.triggerSource(listOf("omw"))
        val words = e.glide(
            gestureFor("omw"), grid, keyWidth, limit = 6,
            tiers = setOf(FuzzyBeamSearch.Tier.USER),
        ).map { it.word }
        assertEquals(listOf("omw"), words.take(1))
    }

    @Test
    fun `a trigger is not a word the typing path completes to`() {
        val e = engine()
        e.glideTriggers = SuggestionEngine.triggerSource(listOf("omw"))
        assertFalse(e.suggest("om", previousWord = null, avroMode = false).contains("omw"))
    }

    @Test
    fun `the trigger source lowercases and drops what a stroke cannot draw`() {
        val source = SuggestionEngine.triggerSource(listOf(" OMW ", "x", "", "omw"))
        assertTrue(source.contains("omw"))
        assertFalse(source.contains("x"))
    }

    @Test
    fun `only triggers that expand on their own are offered to glide`() {
        val index = SnippetIndex.of(
            listOf(
                Snippet(id = 1, label = "a", text = "on my way", trigger = "omw"),
                Snippet(id = 2, label = "b", text = "be right back", trigger = "BRB"),
                Snippet(id = 3, label = "c", text = "asks", trigger = "ask", confirm = true),
                Snippet(id = 4, label = "d", text = "¯\\_(ツ)_/¯", trigger = ":shrug"),
            ),
        )
        assertEquals(setOf("omw", "brb"), index.expandingTriggers)
    }
}
