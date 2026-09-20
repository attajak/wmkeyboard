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
    ): ByteArray {
        val attributeBytes = attributes.flatMap { (key, value) ->
            key.map { it.code } + TERMINATOR + value.map { it.code } + TERMINATOR
        }
        val headerSize = HEADER_FIXED_BYTES + attributeBytes.size
        val head = listOf(0x9B, 0xC1, 0x3A, 0xFE) +
            listOf((version shr 8) and 0xFF, version and 0xFF) +
            listOf((optionFlags shr 8) and 0xFF, optionFlags and 0xFF) +
            listOf(0, 0, (headerSize shr 8) and 0xFF, headerSize and 0xFF)
        return (head + attributeBytes + body).map { it.toByte() }.toByteArray()
    }

    private fun words(bytes: ByteArray): Map<String, Int> {
        val result = AospDictionary.read(bytes)
        assertTrue("expected words, got $result", result is AospDictionary.Result.Words)
        return (result as AospDictionary.Result.Words).entries.toMap()
    }

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
    fun `a shortcut list is stepped over, not read`() {
        // A shortcut is an expansion, not a word somebody typed, and getting
        // its length wrong would put the reader out of step for the rest of
        // the file. The node after it proves the position survived.
        val body = listOf(
            0x02,
            0x58, // terminal, one-byte children address, has shortcuts
            'a'.code,
            0xFF,
            0x00, // no children
            0x00, 0x06, // shortcut list is six bytes including these two
            0x00, // last shortcut, frequency 0
            'b'.code, 'c'.code, TERMINATOR,
            0x10,
            'z'.code,
            0xFF,
        )
        assertEquals(setOf("a", "z"), words(dictionary(body = body)).keys)
    }

    @Test
    fun `a bigram chain is stepped over, not read`() {
        val body = listOf(
            0x02,
            0x14, // terminal, has bigrams
            'a'.code,
            0xFF,
            0x90, 0x04, // one-byte address, another follows
            0x10, 0x08, // one-byte address, the last one
            0x10,
            'z'.code,
            0xFF,
        )
        assertEquals(setOf("a", "z"), words(dictionary(body = body)).keys)
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
    fun `header attributes are stepped over`() {
        val body = listOf(0x01, 0x10, 'a'.code, 0xFF)
        val bytes = dictionary(
            attributes = listOf("locale" to "en_US", "description" to "English (US)"),
            body = body,
        )
        assertEquals(setOf("a"), words(bytes).keys)
    }

    @Test
    fun `a code point table is refused rather than misread`() {
        // The table re-points the one-byte encoding. Reading the file without
        // applying it does not fail, it produces different words, which is the
        // one failure a user could never spot.
        val result = AospDictionary.read(
            dictionary(
                version = VERSION203,
                attributes = listOf("codePointTable" to "abc"),
                body = listOf(0x01, 0x10, 'a'.code, 0xFF),
            ),
        )
        assertTrue(result is AospDictionary.Result.Unsupported)
    }

    @Test
    fun `version 4 is refused`() {
        val result = AospDictionary.read(
            dictionary(version = VERSION403, body = listOf(0x01, 0x10, 'a'.code, 0xFF)),
        )
        assertEquals(AospDictionary.Result.Unsupported(VERSION403), result)
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
        assertTrue("$path did not read: $result", result is AospDictionary.Result.Words)
        val entries = (result as AospDictionary.Result.Words).entries
        assertTrue("only ${entries.size} words", entries.size > REAL_DICTIONARY_MIN_WORDS)
        assertTrue(entries.all { it.first.isNotEmpty() })
        assertTrue(entries.all { it.second in 1..10000 })
        // Every dictionary of a Latin language has these, and a reader that is
        // one byte out of step produces neither.
        val words = entries.toMap()
        assertTrue("no common words at all", words.keys.containsAll(setOf("the", "and")))
    }

    private companion object {
        const val TERMINATOR = 0x1F
        const val HEADER_FIXED_BYTES = 12
        const val VERSION202 = 202
        const val VERSION203 = 203
        const val VERSION403 = 403
        const val REAL_DICTIONARY_MIN_WORDS = 1000
    }
}
