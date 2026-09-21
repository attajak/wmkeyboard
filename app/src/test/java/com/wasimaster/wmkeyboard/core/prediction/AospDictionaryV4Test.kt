package com.wasimaster.wmkeyboard.core.prediction

import com.wasimaster.wmkeyboard.core.prediction.AospFixtures.Entry
import com.wasimaster.wmkeyboard.core.prediction.AospFixtures.history
import com.wasimaster.wmkeyboard.core.prediction.AospFixtures.probability
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading a version 4 dictionary from its two files. Every fixture is built by
 * [AospFixtures] from the published layout.
 *
 * The test that reads a real one takes the `.header` path from `AOSP_DICT_V4`
 * (the `.body` beside it) and skips when that is unset.
 */
class AospDictionaryV4Test {

    private fun read(header: ByteArray, body: ByteArray): AospDictionary.Result.Contents {
        val result = AospDictionaryV4.read(header, body)
        assertTrue("expected contents, got $result", result is AospDictionary.Result.Contents)
        return result as AospDictionary.Result.Contents
    }

    @Test
    fun `words pairs and triples come out of a static dictionary`() {
        // ids: 0 "i", 1 "am", 2 "here". "i am" is a pair, "i am here" a
        // triple: it hangs under "am" (the nearer word) and then "i".
        val body = AospFixtures.body(
            words = listOf("i", "am", "here"),
            model = listOf(
                Entry(0, probability(200)),
                Entry(
                    1,
                    probability(180),
                    next = listOf(
                        Entry(2, probability(150)),
                        Entry(0, probability(0, flags = NOT_A_VALID_ENTRY), next = listOf(Entry(2, probability(170)))),
                    ),
                ),
                Entry(2, probability(90)),
            ),
        )
        val contents = read(AospFixtures.header(VERSION403), body)
        assertEquals(
            mapOf(
                "i" to DictionaryLoader.scaleAospFrequency(200),
                "am" to DictionaryLoader.scaleAospFrequency(180),
                "here" to DictionaryLoader.scaleAospFrequency(90),
            ),
            contents.words.toMap(),
        )
        assertEquals(
            setOf(listOf("am") to "here", listOf("i", "am") to "here"),
            contents.ngrams.map { it.context to it.word }.toSet(),
        )
        val pair = contents.ngrams.first { it.context.size == 1 }
        assertEquals(AospScores.pairCount(150), pair.count)
    }

    @Test
    fun `a user history keeps its counts`() {
        val body = AospFixtures.body(
            words = listOf("see", "you"),
            model = listOf(
                Entry(0, history(40), next = listOf(Entry(1, history(12)))),
                Entry(1, history(55)),
            ),
            total = 5000,
        )
        val contents = read(AospFixtures.header(VERSION403, attributes = listOf("HAS_HISTORICAL_INFO" to "1")), body)
        val words = contents.words.toMap()
        assertEquals(AospScores.historicalFrequency(40, 5000), words["see"])
        assertTrue(words.getValue("you") > words.getValue("see"))
        assertEquals(AospScores.historicalPairCount(12), contents.ngrams.single().count)
        assertEquals(listOf("see"), contents.ngrams.single().context)
    }

    @Test
    fun `blacklisted, sentence-start and never-typed entries are left out`() {
        val body = AospFixtures.body(
            words = listOf("ok", "bad", "gone"),
            model = listOf(
                Entry(0, history(3)),
                Entry(1, history(9, flags = BLACKLISTED)),
                Entry(2, history(0)),
            ),
        )
        val contents = read(AospFixtures.header(VERSION403, attributes = listOf("HAS_HISTORICAL_INFO" to "1")), body)
        assertEquals(setOf("ok"), contents.words.toMap().keys)
    }

    @Test
    fun `shortcuts come out of the sparse table`() {
        val body = AospFixtures.body(
            words = listOf("omw", "ok"),
            model = listOf(Entry(0, probability(100)), Entry(1, probability(100))),
            shortcuts = mapOf(0 to listOf("on my way" to 15, "oh my word" to 3)),
        )
        val contents = read(AospFixtures.header(VERSION403), body)
        assertEquals(
            listOf(
                AospDictionary.Shortcut("omw", "on my way", whitelist = true),
                AospDictionary.Shortcut("omw", "oh my word", whitelist = false),
            ),
            contents.shortcuts,
        )
    }

    @Test
    fun `a body that does not split into its buffers is not a dictionary`() {
        val body = AospFixtures.body(listOf("a"), listOf(Entry(0, probability(1))))
        assertEquals(
            AospDictionary.Result.NotADictionary,
            AospDictionaryV4.read(AospFixtures.header(VERSION403), body.copyOf(body.size - 1)),
        )
    }

    @Test
    fun `revision 402 is refused`() {
        val body = AospFixtures.body(listOf("a"), listOf(Entry(0, probability(1))))
        assertEquals(
            AospDictionary.Result.Unsupported(402),
            AospDictionaryV4.read(AospFixtures.header(402), body),
        )
    }

    @Test
    fun `a real version 4 dictionary reads, when one is pointed at`() {
        val path = System.getenv("AOSP_DICT_V4") ?: return
        val header = File(path)
        val body = File(path.removeSuffix(".header") + ".body")
        if (!header.isFile || !body.isFile) return
        val contents = read(header.readBytes(), body.readBytes())
        assertTrue(contents.words.isNotEmpty())
        println("$path: ${contents.words.size} words, ${contents.ngrams.size} pairs, ${contents.shortcuts.size} shortcuts")
    }

    private companion object {
        const val VERSION403 = 403
        const val NOT_A_VALID_ENTRY = 0x2
        const val BLACKLISTED = 0x8
    }
}
