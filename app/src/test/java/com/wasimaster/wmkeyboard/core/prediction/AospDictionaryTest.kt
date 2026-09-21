package com.wasimaster.wmkeyboard.core.prediction

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading a compiled AOSP `.dict`.
 *
 * Every fixture here is assembled byte by byte in this file from the published
 * format. Nothing is copied from a dictionary repository: those lists are their
 * authors' work, and all this needs is the shape of a node.
 *
 * The one test that reads a real dictionary takes it from `AOSP_DICT` and skips
 * silently when that is unset, the same arrangement `:core:keyman` uses for its
 * corpus sweep, so an ordinary build needs no download.
 */
class AospDictionaryTest {

    /** Assembles a file: header, then whatever bytes the body is. */
    private fun dictionary(
        version: Int = VERSION202,
        optionFlags: Int = 0,
        attributes: List<Pair<String, String>> = emptyList(),
        body: List<Int>,
    ): ByteArray = AospFixtures.header(version, optionFlags, attributes) + AospFixtures.bytes(body)

    private fun contents(bytes: ByteArray): AospDictionary.Result.Contents {
        val result = AospDictionary.read(bytes)
        assertTrue("expected contents, got $result", result is AospDictionary.Result.Contents)
        return result as AospDictionary.Result.Contents
    }

    private fun words(bytes: ByteArray): Map<String, Int> = contents(bytes).words.toMap()

    @Test
    fun `a two-node trie reads both words`() {
        // "a" with a child "t", which is the smallest file that exercises a
        // children address at all.
        val body = listOf(
            0x01, // one node in the root array
            0x50, // terminal, one-byte children address
            'a'.code,
            0xFF, // frequency
            0x01, // children are the very next byte
            0x01, // one node in the child array
            0x10, // terminal, no children
            't'.code,
            0x80,
        )
        assertEquals(mapOf("a" to 10000, "at" to 5019), words(dictionary(body = body)))
    }

    @Test
    fun `a node with several characters spells one word`() {
        val body = listOf(
            0x01,
            0x30, // multiple characters, terminal
            't'.code, 'h'.code, 'e'.code, TERMINATOR,
            0xFF,
        )
        assertEquals(setOf("the"), words(dictionary(body = body)).keys)
    }

    @Test
    fun `a character outside latin-1 takes three bytes`() {
        // U+0985 BENGALI LETTER A. A first byte below 0x20 is the top third of
        // a code point, which is how the format reaches beyond the BMP too.
        val body = listOf(
            0x01,
            0x10,
            0x00, 0x09, 0x85,
            0xFF,
        )
        assertEquals(setOf("অ"), words(dictionary(body = body)).keys)
    }

    @Test
    fun `an entry marked not a word is skipped`() {
        val body = listOf(
            0x02,
            0x12, // terminal, is not a word
            'x'.code,
            0xFF,
            0x10, // terminal, an ordinary word
            'y'.code,
            0xFF,
        )
        assertEquals(setOf("y"), words(dictionary(body = body)).keys)
    }

    @Test
    fun `a shortcut list is read, and the node after it still is`() {
        // Getting the list's length wrong would put the reader out of step
        // for the rest of the file. The node after it proves the position
        // survived.
        val body = listOf(
            0x02,
            0x58, // terminal, one-byte children address, has shortcuts
            'a'.code,
            0xFF,
            0x00, // no children
            0x00, 0x06, // shortcut list is six bytes including these two
            0x00, // last shortcut, strength 0
            'b'.code, 'c'.code, TERMINATOR,
            0x10,
            'z'.code,
            0xFF,
        )
        val read = contents(dictionary(body = body))
        assertEquals(setOf("a", "z"), read.words.toMap().keys)
        assertEquals(listOf(AospDictionary.Shortcut("a", "bc", whitelist = false)), read.shortcuts)
    }

    @Test
    fun `a whitelist shortcut is marked as one`() {
        val body = listOf(
            0x01,
            0x18, // terminal, has shortcuts
            'a'.code,
            0xFF,
            0x00, 0x05,
            0x0F, // last shortcut, strength 15: the source applied it on its own
            'b'.code, TERMINATOR,
        )
        assertEquals(
            listOf(AospDictionary.Shortcut("a", "b", whitelist = true)),
            contents(dictionary(body = body)).shortcuts,
        )
    }

    @Test
    fun `a not-a-word entry carries its shortcut without becoming a word`() {
        val body = listOf(
            0x02,
            0x1A, // terminal, has shortcuts, not a word
            'x'.code,
            0xFF,
            0x00, 0x05, 0x00, 'y'.code, TERMINATOR,
            0x10, 'z'.code, 0xFF,
        )
        val read = contents(dictionary(body = body))
        assertEquals(setOf("z"), read.words.toMap().keys)
        assertEquals(listOf("x" to "y"), read.shortcuts.map { it.trigger to it.expansion })
    }

    @Test
    fun `a pair resolves its address to the word it points at`() {
        // The address counts from where the address field starts: the flags
        // byte is behind it, the node it names ahead of it.
        val body = listOf(
            0x02,
            0x14, // terminal, has bigrams
            'a'.code,
            0xFF,
            0x10, // one-byte address, the last one, strength 0
            0x01, // the next node starts one byte on
            0x10,
            'z'.code,
            0xFF,
        )
        val read = contents(dictionary(body = body))
        assertEquals(setOf("a", "z"), read.words.toMap().keys)
        assertEquals(listOf(listOf("a") to "z"), read.ngrams.map { it.context to it.word })
        // The second word is at the top of the scale and so is the pair.
        assertEquals(AospScores.pairCount(255), read.ngrams.single().count)
    }

    @Test
    fun `a pair can point backwards`() {
        val body = listOf(
            0x02,
            0x10, 'z'.code, 0xFF, // "z" starts at body byte 1
            0x14, 'a'.code, 0x80,
            0x50, // negative, one-byte address, the last one
            0x07, // from body byte 8 back to body byte 1
        )
        assertEquals(
            listOf(listOf("a") to "z"),
            contents(dictionary(body = body)).ngrams.map { it.context to it.word },
        )
    }

    @Test
    fun `a chain of pairs is read to its end`() {
        val body = listOf(
            0x04,
            0x14, 'a'.code, 0xFF,
            0x90, 0x06, // one-byte address, another follows: body 5 + 6 is "y"
            0x10, 0x07, // the last: body 7 + 7 is "z"
            0x10, 'x'.code, 0xFF,
            0x10, 'y'.code, 0xFF,
            0x10, 'z'.code, 0xFF,
        )
        assertEquals(
            listOf("y", "z"),
            contents(dictionary(body = body)).ngrams.map { it.word },
        )
    }

    @Test
    fun `a stronger pair counts for more`() {
        val weak = AospScores.pairCount(AospScores.bigramProbability(100, 0))
        val strong = AospScores.pairCount(AospScores.bigramProbability(100, 15))
        assertTrue("$weak vs $strong", strong > weak)
        assertTrue(weak >= 1)
    }

    @Test
    fun `a two-byte node count is read as one number`() {
        // 0x80 0x02 is 2 nodes, not 128. Getting this wrong reads the first
        // node's flags as a count and the file falls apart from there.
        val body = listOf(
            0x80, 0x02,
            0x10, 'a'.code, 0xFF,
            0x10, 'b'.code, 0xFF,
        )
        assertEquals(setOf("a", "b"), words(dictionary(body = body)).keys)
    }

    @Test
    fun `the header's own attributes come back`() {
        val bytes = dictionary(
            attributes = listOf("locale" to "fr", "description" to "Français"),
            body = listOf(0x01, 0x10, 'a'.code, 0xFF),
        )
        assertEquals(setOf("a"), words(bytes).keys)
        assertEquals("fr", contents(bytes).attributes["locale"])
        assertEquals("Français", contents(bytes).attributes["description"])
    }

    @Test
    fun `a code point table re-points the one-byte characters`() {
        // Byte 0x20 + n is the n-th character of the table. Reading the file
        // without applying it would not fail, it would produce different
        // words, which is the one failure a user could never spot.
        val body = listOf(
            0x02,
            0x10, 0x20, 0xFF, // the table's first character
            0x30, 0x21, 'z'.code, TERMINATOR, 0xFF, // the second, then a byte past the table's end
        )
        val bytes = dictionary(
            version = VERSION203,
            attributes = listOf("codePointTable" to "жa"),
            body = body,
        )
        assertEquals(setOf("ж", "az"), words(bytes).keys)
    }

    @Test
    fun `the table does not apply to shortcut targets`() {
        val body = listOf(
            0x01,
            0x18, 0x20, 0xFF,
            0x00, 0x05, 0x00, 0x20, TERMINATOR, // a space, not the table's first character
        )
        val bytes = dictionary(attributes = listOf("codePointTable" to "ж"), body = body)
        assertEquals(listOf("ж" to " "), contents(bytes).shortcuts.map { it.trigger to it.expansion })
    }

    @Test
    fun `a version 4 header alone asks for its body`() {
        val header = dictionary(version = VERSION403, body = emptyList())
        assertEquals(AospDictionary.Result.HeaderOnly(VERSION403), AospDictionary.read(header))
    }

    @Test
    fun `version 402 is refused`() {
        val header = dictionary(version = VERSION402, body = emptyList())
        assertEquals(AospDictionary.Result.Unsupported(VERSION402), AospDictionary.read(header))
    }

    @Test
    fun `anything else is not a dictionary`() {
        assertEquals(AospDictionary.Result.NotADictionary, AospDictionary.read(ByteArray(0)))
        assertEquals(
            AospDictionary.Result.NotADictionary,
            AospDictionary.read("the 10000\n".toByteArray()),
        )
        assertFalse(AospDictionary.looksLikeDictionary("the ".toByteArray()))
    }

    @Test
    fun `a truncated file gives back what it could read`() {
        // Half a dictionary is still worth importing, and the alternative is a
        // parser that throws out of an import the user asked for.
        val whole = dictionary(
            body = listOf(0x02, 0x10, 'a'.code, 0xFF, 0x10, 'b'.code, 0xFF),
        )
        val cut = whole.copyOf(whole.size - 1)
        assertEquals(setOf("a"), words(cut).keys)
    }

    @Test
    fun `a real dictionary reads, when one is pointed at`() {
        val path = System.getenv("AOSP_DICT") ?: return
        val file = File(path)
        if (!file.isFile) return
        val result = AospDictionary.read(file.readBytes())
        assertTrue("$path did not read: $result", result is AospDictionary.Result.Contents)
        val read = result as AospDictionary.Result.Contents
        val entries = read.words
        assertTrue("only ${entries.size} words", entries.size > REAL_DICTIONARY_MIN_WORDS)
        assertTrue(entries.all { it.first.isNotEmpty() })
        assertTrue(entries.all { it.second in 1..10000 })
        // Every dictionary of a Latin language has these, and a reader that is
        // one byte out of step produces neither.
        val words = entries.toMap()
        assertTrue("no common words at all", words.keys.containsAll(setOf("the", "and")))
        // Every pair names two words the file spells.
        assertTrue(read.ngrams.all { it.word.isNotEmpty() && it.context.all(String::isNotEmpty) })
        println("$path: ${entries.size} words, ${read.ngrams.size} pairs, ${read.shortcuts.size} shortcuts")
        for (head in listOf("of", "I", "thank", "going")) {
            println("pairs after '$head': " + read.ngrams.filter { it.context == listOf(head) }.sortedByDescending { it.count }.take(8).map { it.word to it.count })
        }
        println("shortcuts: " + read.shortcuts.take(8))
    }

    private companion object {
        const val TERMINATOR = AospFixtures.TERMINATOR
        const val VERSION202 = 202
        const val VERSION203 = 203
        const val VERSION402 = 402
        const val VERSION403 = 403
        const val REAL_DICTIONARY_MIN_WORDS = 1000
    }
}
