package com.wasimaster.wmkeyboard.core.tools

import com.wasimaster.wmkeyboard.core.vocab.VocabSense
import com.wasimaster.wmkeyboard.core.vocab.VocabWord
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DictionarySourcesTest {

    @Test
    fun `source list round-trips with order and switches`() {
        val choices = listOf(
            DictionarySourceChoice(DictionarySource.DICTIONARY_API),
            DictionarySourceChoice(DictionarySource.WIKTIONARY, enabled = false),
            DictionarySourceChoice(DictionarySource.VOCAB_PACKS),
            DictionarySourceChoice(DictionarySource.WIKTIONARY_REST, enabled = false),
        )
        assertEquals(choices, DictionarySources.decode(DictionarySources.encode(choices)))
    }

    @Test
    fun `unknown ids drop and missing sources join at the end, on`() {
        assertEquals(
            listOf(
                DictionarySourceChoice(DictionarySource.DICTIONARY_API),
                DictionarySourceChoice(DictionarySource.WIKTIONARY, enabled = false),
                DictionarySourceChoice(DictionarySource.VOCAB_PACKS),
                DictionarySourceChoice(DictionarySource.WIKTIONARY_REST),
            ),
            DictionarySources.decode("dictionary_api,-bogus,-wiktionary,dictionary_api"),
        )
    }

    @Test
    fun `default asks offline first and the free dictionary API last`() {
        val order = DictionarySources.DEFAULT.map { it.source }
        assertEquals(DictionarySource.VOCAB_PACKS, order.first())
        assertEquals(DictionarySource.DICTIONARY_API, order.last())
        assertTrue(DictionarySources.DEFAULT.all { it.enabled })
    }

    @Test
    fun `vocab record becomes one entry grouped by part of speech`() {
        val record = VocabWord(
            word = "run",
            ipa = mapOf("uk" to "/rʌn/"),
            audio = mapOf("us" to "https://example.org/run.mp3"),
            senses = listOf(
                VocabSense(pos = "verb", definition = "To move fast on foot.", example = " She runs. ", synonyms = listOf("sprint")),
                VocabSense(pos = "noun", definition = "An act of running."),
                VocabSense(pos = "verb", definition = "To operate."),
                VocabSense(pos = "verb", definition = "  "),
            ),
            synonyms = listOf("dash"),
        )
        val entry = DictionaryLookup.fromVocabWord(record).single()
        assertEquals("run", entry.word)
        assertEquals("/rʌn/", entry.phonetic)
        assertEquals("https://example.org/run.mp3", entry.audioUrl)
        assertEquals(listOf("verb", "noun"), entry.meanings.map { it.partOfSpeech })
        assertEquals(
            listOf(
                DictDefinition("To move fast on foot.", "She runs.", listOf("sprint")),
                DictDefinition("To operate.", null, emptyList()),
            ),
            entry.meanings[0].definitions,
        )
        // Two parts of speech: the record-wide synonyms belong to neither.
        assertTrue(entry.meanings.all { it.synonyms.isEmpty() })
    }

    @Test
    fun `vocab record with no definitions is no entry`() {
        assertTrue(DictionaryLookup.fromVocabWord(VocabWord(word = "x", audio = mapOf("us" to "x.mp3"))).isEmpty())
    }

    private val found = listOf(DictEntry("word", "", null, listOf(DictMeaning("noun", listOf(DictDefinition("A unit.", null, emptyList())), emptyList(), emptyList()))))

    @Test
    fun `lookup falls through a failing source and one without the word`() = runBlocking {
        val asked = ArrayList<DictionarySource>()
        val result = DictionaryLookup.resolve(
            " word ",
            listOf(DictionarySource.DICTIONARY_API, DictionarySource.VOCAB_PACKS, DictionarySource.WIKTIONARY),
        ) { source, word ->
            asked += source
            assertEquals("word", word)
            when (source) {
                DictionarySource.DICTIONARY_API -> throw IOException("522")
                DictionarySource.VOCAB_PACKS -> emptyList()
                else -> found
            }
        }
        assertEquals(DictionaryLookup.Result.Found(found, DictionarySource.WIKTIONARY), result)
        assertEquals(listOf(DictionarySource.DICTIONARY_API, DictionarySource.VOCAB_PACKS, DictionarySource.WIKTIONARY), asked)
    }

    @Test
    fun `lookup tells no entry from nothing reached`() = runBlocking {
        val sources = listOf(DictionarySource.WIKTIONARY, DictionarySource.DICTIONARY_API)
        assertEquals(
            DictionaryLookup.Result.NotFound,
            DictionaryLookup.resolve("word", sources) { source, _ ->
                if (source == DictionarySource.WIKTIONARY) throw IOException("down") else emptyList()
            },
        )
        assertEquals(
            DictionaryLookup.Result.Failed,
            DictionaryLookup.resolve("word", sources) { _, _ -> throw IOException("offline") },
        )
    }
}
