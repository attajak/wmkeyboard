package com.wasimaster.wmkeyboard.core.prediction

import com.wasimaster.wmkeyboard.core.transliteration.AvroPhonetic
import com.wasimaster.wmkeyboard.core.transliteration.BengaliPhoneticIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Desktop Avro's candidate list: the known spelling, then dictionary and
 * suffix-joined words by edit distance to the rules' reading, then the reading.
 */
class PhoneticCandidatesTest {

    private val ami = "আমি"
    private val achhi = "আছি"
    private val ashi = "আসি"
    private val ma = "মা"
    private val hello = "হ্যালো"

    private val index = BengaliPhoneticIndex(
        listOf(ami to 9000, achhi to 6900, ashi to 2300, ma to 5000),
    )

    private val spellings = SpellingMap.load(
        "hello\t$hello\n".byteInputStream(Charsets.UTF_8),
        loanwordStreams = 1,
    )

    private val backend = PhoneticBackend(PhoneticSchemes.BENGALI, index, spellings)

    private fun candidates(buffer: String) = PhoneticCandidates.build(backend, buffer, 20)

    @Test
    fun theClosestWordToTheReadingLeadsWhateverItsFrequency() {
        // আছি is three times commoner, but আসি is what the letters spell.
        val list = candidates("asi")
        assertEquals(ashi, list.first())
        assertTrue(achhi in list)
    }

    @Test
    fun aKnownSpellingComesFirst() {
        assertEquals(hello, candidates("hello").first())
    }

    @Test
    fun theReadingIsLast() {
        val list = candidates("maer")
        assertEquals(AvroPhonetic.transliterate("maer"), list.last())
    }

    @Test
    fun aSuffixAfterAVowelTakesAYa() {
        // মা + ের is মায়ের, the way Avro joins a vowel sign onto a vowel.
        assertTrue(candidates("maer").contains("$maয়ের"))
    }

    @Test
    fun suppressedWordsAreDroppedButTheReadingStays() {
        val list = PhoneticCandidates.build(backend, "asi", 20) { it == achhi || it == ashi }
        assertTrue(achhi !in list)
        assertTrue(ashi in list)
    }

    @Test
    fun inTheStripTheCommitKeepsTheFirstChip() {
        val engine = SuggestionEngine(Trie(), index, UserLexicon(null), spellings).apply {
            primaryLanguageId = "bn"
            englishSources = false
        }
        val ordinary = engine.suggest("asi", previousWord = null, phoneticLanguage = "bn")
        engine.phoneticCandidateLists = mapOf("bn" to PhoneticCandidateList.STRIP)
        val folded = engine.suggest("asi", previousWord = null, phoneticLanguage = "bn")
        assertEquals(ordinary.first(), folded.first())
        assertEquals(listOf(achhi, ashi), folded.take(2))
    }

    @Test
    fun editDistance() {
        assertEquals(0, PhoneticCandidates.levenshtein("abc", "abc"))
        assertEquals(1, PhoneticCandidates.levenshtein("abc", "abd"))
        assertEquals(3, PhoneticCandidates.levenshtein("", "abc"))
    }
}
