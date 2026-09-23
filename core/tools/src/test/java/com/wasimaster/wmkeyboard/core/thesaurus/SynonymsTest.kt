package com.wasimaster.wmkeyboard.core.thesaurus

import com.wasimaster.wmkeyboard.core.tools.DictDefinition
import com.wasimaster.wmkeyboard.core.tools.DictEntry
import com.wasimaster.wmkeyboard.core.tools.DictMeaning
import com.wasimaster.wmkeyboard.core.vocab.VocabSense
import com.wasimaster.wmkeyboard.core.vocab.VocabWord
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SynonymsTest {

    @Test
    fun `source list round-trips with order and switches`() {
        val choices = listOf(
            SynonymSourceChoice(SynonymSource.WIKTIONARY),
            SynonymSourceChoice(SynonymSource.DATAMUSE, enabled = false),
            SynonymSourceChoice(SynonymSource.VOCAB_PACKS),
            SynonymSourceChoice(SynonymSource.DICTIONARY_API),
            SynonymSourceChoice(SynonymSource.SIMILAR_WORDS, enabled = false),
        )
        assertEquals(choices, SynonymSources.decode(SynonymSources.encode(choices)))
    }

    @Test
    fun `unknown ids drop and missing sources join at the end, on`() {
        val decoded = SynonymSources.decode("wiktionary,-bogus,-datamuse,wiktionary")
        assertEquals(
            listOf(
                SynonymSourceChoice(SynonymSource.WIKTIONARY),
                SynonymSourceChoice(SynonymSource.DATAMUSE, enabled = false),
                SynonymSourceChoice(SynonymSource.VOCAB_PACKS),
                SynonymSourceChoice(SynonymSource.DICTIONARY_API),
                SynonymSourceChoice(SynonymSource.SIMILAR_WORDS),
            ),
            decoded,
        )
    }

    @Test
    fun `datamuse keeps tagged synonyms in rank order, grouped by first part of speech`() {
        val results = DatamuseClient.parse(
            """[{"word":"pleased","score":9,"tags":["syn","adj","v"]},
                {"word":"joy","score":8,"tags":["n"]},
                {"word":"glad","score":7,"tags":["syn","adj"]},
                {"word":"Happy","score":6,"tags":["syn","adj"]},
                {"word":"cheer up","score":5,"tags":["syn","v"]}]""",
        )
        val groups = SynonymFold.fromDatamuse(results, "happy", synonymsOnly = true)
        assertEquals(
            listOf(
                SynonymGroup("adjective", null, listOf("pleased", "glad")),
                SynonymGroup("verb", null, listOf("cheer up")),
            ),
            groups,
        )
        val similar = SynonymFold.fromDatamuse(results, "happy", synonymsOnly = false)
        assertEquals(listOf("pleased", "glad", "joy", "cheer up"), similar.flatMap { it.words })
    }

    @Test
    fun `datamuse parse tolerates junk`() {
        assertTrue(DatamuseClient.parse("not json").isEmpty())
        assertTrue(DatamuseClient.parse("{}").isEmpty())
        assertTrue(DatamuseClient.parse("[]").isEmpty())
    }

    @Test
    fun `vocab record groups by meaning and drops thesaurus pointers and repeats`() {
        val record = VocabWord(
            word = "abhor",
            pos = listOf("verb"),
            senses = listOf(
                VocabSense(pos = "verb", definition = "To regard with horror.", synonyms = listOf("detest", "Thesaurus:hate")),
                VocabSense(pos = "verb", definition = "To shrink back.", synonyms = emptyList()),
            ),
            synonyms = listOf("detest", "loathe", "abhor", "hate 2"),
        )
        assertEquals(
            listOf(
                SynonymGroup("verb", "To regard with horror.", listOf("detest")),
                SynonymGroup("verb", null, listOf("loathe")),
            ),
            SynonymFold.fromVocabWord(record, "abhor"),
        )
    }

    @Test
    fun `dictionary api groups by definition, then by part of speech`() {
        val entries = listOf(
            DictEntry(
                word = "quick",
                phonetic = "",
                audioUrl = null,
                meanings = listOf(
                    DictMeaning(
                        partOfSpeech = "Adjective",
                        definitions = listOf(DictDefinition("Moving fast.", null, listOf("fast", "rapid"))),
                        synonyms = listOf("speedy", "fast"),
                        antonyms = emptyList(),
                    ),
                ),
            ),
        )
        assertEquals(
            listOf(
                SynonymGroup("adjective", "Moving fast.", listOf("fast", "rapid")),
                SynonymGroup("adjective", null, listOf("speedy")),
            ),
            SynonymFold.fromDictionary(entries, "quick"),
        )
    }

    @Test
    fun `usable synonyms are short phrases in letters`() {
        assertTrue(SynonymFold.isUsable("glad"))
        assertTrue(SynonymFold.isUsable("larger-than-life"))
        assertTrue(SynonymFold.isUsable("on cloud nine"))
        assertFalse(SynonymFold.isUsable("be over the moon"))
        assertFalse(SynonymFold.isUsable("Thesaurus:happy"))
        assertFalse(SynonymFold.isUsable("24/7"))
        assertFalse(SynonymFold.isUsable(""))
    }

    @Test
    fun `lookup falls through an empty source and a failing one`() = runBlocking {
        val asked = mutableListOf<SynonymSource>()
        val result = SynonymLookup.resolve(
            "happy",
            listOf(SynonymSource.VOCAB_PACKS, SynonymSource.DATAMUSE, SynonymSource.WIKTIONARY),
        ) { source, _ ->
            asked += source
            when (source) {
                SynonymSource.VOCAB_PACKS -> emptyList()
                SynonymSource.DATAMUSE -> throw IOException("down")
                else -> listOf(SynonymGroup("adjective", null, listOf("glad")))
            }
        }
        assertEquals(listOf(SynonymSource.VOCAB_PACKS, SynonymSource.DATAMUSE, SynonymSource.WIKTIONARY), asked)
        val found = result as SynonymLookup.Result.Found
        assertEquals(SynonymSource.WIKTIONARY, found.set.source)
    }

    @Test
    fun `lookup tells nothing found from nothing reached`() = runBlocking {
        val empty = SynonymLookup.resolve("qwzx", listOf(SynonymSource.DATAMUSE)) { _, _ -> emptyList() }
        assertEquals(SynonymLookup.Result.NotFound, empty)
        val down = SynonymLookup.resolve("happy", listOf(SynonymSource.DATAMUSE)) { _, _ -> throw IOException() }
        assertEquals(SynonymLookup.Result.Failed, down)
        val offline = SynonymLookup.resolve("happy", listOf(SynonymSource.DATAMUSE), allowOnline = false) { _, _ ->
            error("not asked")
        }
        assertEquals(SynonymLookup.Result.Failed, offline)
    }
}
