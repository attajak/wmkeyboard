package com.wasimaster.wmkeyboard.core.gesture

import com.wasimaster.wmkeyboard.core.prediction.SuggestionEngine
import com.wasimaster.wmkeyboard.core.prediction.SystemUserDictionary
import com.wasimaster.wmkeyboard.core.prediction.Trie
import com.wasimaster.wmkeyboard.core.prediction.UserLexicon
import com.wasimaster.wmkeyboard.core.transliteration.BengaliPhoneticIndex
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * One word, two sources, one chip (issue #172).
 *
 * The platform's personal dictionary is indexed under folded keys ("boston")
 * with the capital kept beside them; a word list keeps whatever spelling it was
 * written with ("Boston"). The decoder keys its results on each trie's own
 * spelling, so a stroke over b-o-s-t-o-n came back with both — and the display
 * pass then put the platform's capital onto the folded one, leaving two chips
 * that read identically. The engine now folds the list before it is sliced.
 */
class GlideCrossSourceDedupeTest {

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

    @Test
    fun `a word in both the word list and the platform dictionary is one chip`() {
        // The list spells it with its capital, as an imported list may; the
        // platform index folds its own copy to "boston" and remembers "Boston".
        val list = Trie().apply {
            insert("Boston", 500)
            insert("bost", 50)
        }
        val platform = SystemUserDictionary.index(listOf("Boston"))
        val engine = SuggestionEngine(list, BengaliPhoneticIndex(emptyList()), UserLexicon(null))
            .apply {
                englishSources = true
                systemDictionary = platform.source
                systemWordCases = platform.shapes
            }

        val words = engine.glide(gestureFor("boston"), grid, keyWidth, limit = 6).map { it.word }

        assertEquals("one Boston, however many stores hold it: $words", 1, words.count { it == "Boston" })
        assertEquals("and the fold does not lose the folded copy's spelling", "Boston", words.first())
    }
}
