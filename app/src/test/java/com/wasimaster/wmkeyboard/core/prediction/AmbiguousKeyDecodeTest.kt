package com.wasimaster.wmkeyboard.core.prediction

import com.wasimaster.wmkeyboard.core.layout.LayoutFile
import com.wasimaster.wmkeyboard.core.layout.LayoutLayer
import com.wasimaster.wmkeyboard.core.layout.isAmbiguous
import com.wasimaster.wmkeyboard.core.layout.letterSet
import java.io.File
import java.text.Normalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Decoding a keyboard that puts several letters on a key (discussion #103):
 * T9's `2 = abc`, a compact grid's `qw`.
 *
 * The typed buffer holds one *anchor* letter per keystroke — what the key
 * commits — and [KeySets] says what else each of those keystrokes could have
 * meant. Everything here is about the walk turning the one into the other.
 */
class AmbiguousKeyDecodeTest {

    private val search = FuzzyBeamSearch()

    /** The ITU E.161 keypad, as `BuiltInLayouts.T9` draws it. */
    private val t9 = listOf("abc", "def", "ghi", "jkl", "mno", "pqrs", "tuv", "wxyz")

    private fun source(trie: WordSource, logWeight: Double = 0.0) =
        FuzzyBeamSearch.WalkSource(
            trie.walkers().single(), logWeight, FuzzyBeamSearch.Tier.DICTIONARY,
        )

    /**
     * What typing [word] on the keypad looks like from the service's side: the
     * anchor letters that reach the buffer, paired with the key sets behind
     * them. Exactly the pair `WMKeyboardService` hands the engine.
     */
    private fun keypad(word: String, pad: List<String> = t9): Pair<String, KeySets> {
        val anchors = StringBuilder()
        val sets = ArrayList<String>()
        for (ch in word) {
            val group = pad.firstOrNull { ch in it } ?: error("no T9 key carries '$ch'")
            anchors.append(group.first())
            sets.add(group)
        }
        return anchors.toString() to requireNotNull(KeySets.of(sets))
    }

    private fun decode(
        entries: List<Pair<String, Int>>,
        word: String,
        limit: Int = 8,
        pad: List<String> = t9,
    ): List<String> {
        val (typed, keys) = keypad(word, pad)
        return search.search(
            listOf(source(PackedTrie.of(entries))), typed, KeyProximity.QWERTY, limit,
            BeamWorkspace(), keys = keys,
        ).map { it.word }
    }

    private fun realEntries(): List<Pair<String, Int>> {
        val candidates = listOf(
            File("dictionaries-src/en.txt"),
            File("app/dictionaries-src/en.txt"),
        )
        val file = candidates.firstOrNull { it.exists() }
            ?: error("en.txt not found (cwd=${File(".").absolutePath})")
        return file.inputStream().use { DictionaryLoader.loadEntries(it) }
    }

    @Test
    fun keySetsAreAbsentWhenEveryKeystrokeMeantOneLetter() {
        // The ordinary keyboard: a frame of single letters is no frame at all,
        // and the decoder has to stay on its 1:1 path for it.
        assertNull(KeySets.of(listOf("h", "e", "l", "l", "o")))
        assertNull(KeySets.of(emptyList()))
    }

    @Test
    fun oneAmbiguousKeystrokeMakesTheWholeFrameAmbiguous() {
        // A word part-spelled from long-press picks: only the guessed
        // positions carry a choice, and the rest decode as themselves.
        val keys = requireNotNull(KeySets.of(listOf("w", "abc", "n")))
        assertTrue(keys.isAmbiguous)
        assertNull(keys.at(0))
        assertEquals("abc", keys.at(1))
        assertTrue(keys.accepts(1, 'c'))
        assertFalse(keys.accepts(1, 'd'))
        // Past the end of the frame is not a choice either.
        assertNull(keys.at(9))
    }

    @Test
    fun theKeypadRunResolvesToItsWord() {
        // 4 6 6 3 on a keypad. "good" and "home" and "gone" are all spelled by
        // it; the buffer holds "gmmd", which is none of them.
        val entries = listOf("good" to 900, "gone" to 500, "home" to 400, "hood" to 60)
        val (typed, _) = keypad("good")
        assertEquals("gmmd", typed)
        assertEquals(listOf("good", "gone", "home", "hood"), decode(entries, "good"))
    }

    @Test
    fun everyReadingArrivesUnedited() {
        // The point of the free match: a reading is not a correction of the
        // anchors, so it must not be charged as one — the engine's
        // "a known word suppresses corrections" gate throws away edited
        // candidates, and every T9 reading would go with them.
        val (typed, keys) = keypad("hood")
        val got = search.search(
            listOf(source(PackedTrie.of(listOf("hood" to 100, "good" to 100)))),
            typed, KeyProximity.QWERTY, 8, BeamWorkspace(), keys = keys,
        )
        assertTrue(got.isNotEmpty())
        for (c in got) {
            assertEquals("${c.word} decoded at ${c.edits} edits", 0, c.edits)
            assertEquals(0.0, c.editCost, 1e-9)
        }
    }

    @Test
    fun readingsAreRankedByFrequencyAlone() {
        // Nothing distinguishes the letters of one key, so the language model
        // is the only thing that can order the readings — which is exactly how
        // a T9 phone ordered them.
        val rare = listOf("good" to 5, "gone" to 900)
        assertEquals("gone", decode(rare, "good").first())
        val common = listOf("good" to 900, "gone" to 5)
        assertEquals("good", decode(common, "good").first())
    }

    @Test
    fun aResolvedPositionPinsItsLetter() {
        // The long-press escape: pick `c` out of the ABC popup and no *reading*
        // may put `a` or `b` there. "cab", "abc" and "aba" are otherwise the
        // same three keys, and all three are equally common.
        //
        // The picked letter reaches the buffer as itself — the popup commits a
        // one-character key, which carries no set — so the buffer reads "caa"
        // and not the "aaa" three anchors would have made.
        //
        // The other two are still reachable, as a mis-hit of the pinned key is
        // reachable on any keyboard; what the pin guarantees is that they are
        // edits, and so rank below the one word that honours it.
        val entries = listOf("cab" to 100, "abc" to 100, "aba" to 100)
        val pinned = requireNotNull(KeySets.of(listOf("c", "abc", "abc")))
        val got = search.search(
            listOf(source(PackedTrie.of(entries))), "caa", KeyProximity.QWERTY, 8,
            BeamWorkspace(), keys = pinned,
        )
        assertEquals("cab", got.first().word)
        assertEquals(listOf("cab"), got.filter { it.edits == 0 }.map { it.word })
    }

    @Test
    fun completionRunsPastTheKeysTyped() {
        // Three keys, a longer word: ambiguity and completion compose, so the
        // strip can offer a word before it has been fully keyed.
        val entries = listOf("keyboard" to 100)
        // k=jkl, e=def, y=wxyz
        val (typed, keys) = keypad("key")
        assertEquals("jdw", typed)
        val got = search.search(
            listOf(source(PackedTrie.of(entries))), typed, KeyProximity.QWERTY, 5,
            BeamWorkspace(), keys = keys,
        )
        assertEquals("keyboard", got.first().word)
        assertEquals(5, got.first().completedChars)
    }

    @Test
    fun aSlipToTheNextKeyIsStillCorrected() {
        // One edit survives alongside the ambiguity: 5 instead of 4 for the
        // first key of "good" (they are neighbours on the pad, and `j` and `g`
        // are neighbours on the proximity grid the layout derives). Nothing
        // reads these keys honestly, so the surcharged reading is all there is.
        val entries = listOf("good" to 900)
        val keys = requireNotNull(KeySets.of(listOf("jkl", "mno", "mno", "def")))
        val got = search.search(
            listOf(source(PackedTrie.of(entries))), "jmmd", KeyProximity.QWERTY, 5,
            BeamWorkspace(), keys = keys,
        )
        assertEquals("good", got.first().word)
        assertEquals(1, got.first().edits)
    }

    @Test
    fun twoEditsAreRefusedOnAnAmbiguousBoard() {
        // The frontier guard: speculation on top of speculation is capped at
        // one edit however deep the caller asks, so a long run of ambiguous
        // keys cannot exhaust the pop budget before it emits anything.
        val entries = listOf("good" to 900)
        val keys = requireNotNull(KeySets.of(listOf("jkl", "jkl", "mno", "def")))
        val got = search.search(
            listOf(source(PackedTrie.of(entries))), "jjmd", KeyProximity.QWERTY, 5,
            BeamWorkspace(), maxEdits = 2, keys = keys,
        )
        assertTrue("two-edit reading should not be reachable", got.isEmpty())
    }

    @Test
    fun aRealKeypadWordDecodesFromTheShippedDictionary() {
        // End to end on the actual English list, at the length where the
        // keypad is most ambiguous.
        val entries = realEntries()
        for (word in listOf("keyboard", "language", "tomorrow", "thinking", "problem", "home")) {
            val got = decode(entries, word, limit = 16)
            assertTrue("'$word' not decoded; got ${got.take(5)}", word in got)
        }
    }

    @Test
    fun anHonestReadingBeatsACommonerEditedOne() {
        // Four keys of "home" (4 6 6 3) also spell "one" if the first keystroke
        // is thrown away, and "one" is the commoner word. Spending every
        // keystroke as pressed wins anyway — that is what the ambiguous edit
        // surcharge buys.
        val entries = listOf("home" to 300, "one" to 4_000)
        assertEquals("home", decode(entries, "home").first())
    }

    @Test
    fun theCommonestReadingLeadsOnTheShippedDictionary() {
        // What a T9 user experiences when the word they want is the commonest
        // thing its keys spell: the top chip is already right.
        //
        // "home" is deliberately not in this list. Its keys (4 6 6 3) spell
        // "good" as honestly as they spell "home", and "good" is the commoner
        // word — so "good" leads, and reaching "home" is one tap on the strip.
        // That is the trade the layout makes, not a fault in the decode; the
        // test above pins that "home" is offered at all.
        val entries = realEntries()
        for (word in listOf("good", "have", "there", "keyboard", "tomorrow")) {
            assertEquals(word, decode(entries, word).first())
        }
    }

    /** The key sets of a shipped per-language keypad (#332), read off its asset. */
    private fun shippedKeypad(stem: String): List<String> {
        val file = File("src/main/assets/layouts/${stem}_t9.${LayoutFile.FILE_EXTENSION}")
        return LayoutFile.decode(file.readText())!!.layout.layer(LayoutLayer.LETTERS)!!
            .rows.flatten()
            .filter { it.isAmbiguous() }
            .map { it.letterSet() }
    }

    @Test
    fun aLanguageKeypadSpellsItsOwnLettersFromTheirBaseKey() {
        // The English keypad cannot reach "für" at all: no key carries ü, and
        // the accent fold only runs on unambiguous keystrokes. The German one
        // puts ü on 8 with tuv, so 3 8 7 is the word, free and unedited.
        val de = shippedKeypad("de")
        val entries = listOf("für" to 900, "dur" to 40, "über" to 600, "straße" to 300)
        assertEquals("für", decode(entries, "für", pad = de).first())
        assertEquals("über", decode(entries, "über", pad = de).first())
        assertEquals("straße", decode(entries, "straße", pad = de).first())
    }

    @Test
    fun aKeypadInAnotherScriptDecodesTheSameWay() {
        val cases = listOf(
            "ru" to listOf("привет" to 500, "ещё" to 400),
            "el" to listOf("καλημέρα" to 500, "είναι" to 400),
            "he" to listOf("שלום" to 500, "מים" to 400),
            "ar" to listOf("مرحبا" to 500, "إلى" to 400),
            "fa" to listOf("سلام" to 500, "گفت" to 400),
            "kk" to listOf("қазақ" to 500, "үй" to 400),
            "hy" to listOf("բարև" to 500, "հայերեն" to 400),
            "ka" to listOf("გამარჯობა" to 500, "სახლი" to 400),
            "ur" to listOf("پاکستان" to 500, "ہے" to 400),
        )
        for ((stem, entries) in cases) {
            val pad = shippedKeypad(stem)
            for ((word, _) in entries) {
                assertEquals("$stem: $word", word, decode(entries, word, pad = pad).first())
            }
        }
    }

    /**
     * Scripts written with combining marks (#332): the vowel sign, the virama
     * and the tone mark are keystrokes of their own, on the keys the phones put
     * them on, and decode like any other letter of the set.
     */
    @Test
    fun aKeypadSpellsWordsWithCombiningMarks() {
        val cases = listOf(
            "hi" to listOf("नमस्ते" to 500, "भारत" to 400),
            "bn" to listOf("আমি" to 500, "বাংলা" to 400),
            "ta" to listOf("வணக்கம்" to 500),
            "th" to listOf("สวัสดี" to 500),
            "vi" to listOf("việt" to 500, "người" to 400),
            "yo" to listOf(Normalizer.normalize("ẹ\u0301kọ\u0301", Normalizer.Form.NFC) to 500),
        )
        for ((stem, entries) in cases) {
            val pad = shippedKeypad(stem)
            for ((word, _) in entries) {
                assertEquals("$stem: $word", word, decode(entries, word, pad = pad).first())
            }
        }
    }

    /**
     * A nukta letter on a key (ज़, U+095B) is spelled base + nukta in the word
     * lists, because Unicode will not compose it back; one keystroke still
     * reads the whole letter.
     */
    @Test
    fun aNuktaLetterOnAKeyMatchesItsDecomposedSpelling() {
        val typedWith = "\u095bिंदगी"
        val listed = Normalizer.normalize(typedWith, Normalizer.Form.NFC)
        assertEquals("the list spells it decomposed", typedWith.length + 1, listed.length)
        val got = decode(listOf(listed to 500), typedWith, pad = shippedKeypad("hi"))
        assertEquals(listed, got.first())
    }

    @Test
    fun keySetsKnowWhichMembersSpellAsSeveralCharacters() {
        val keys = requireNotNull(KeySets.of(listOf("चछजझञ\u095b", "abc")))
        assertEquals(listOf("ज\u093c"), keys.spellingsAt(0)?.toList())
        assertNull(keys.spellingsAt(1))
    }
}
