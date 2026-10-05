package com.wasimaster.wmkeyboard.core.dictionaries

import com.wasimaster.wmkeyboard.core.prediction.MappedTrie
import com.wasimaster.wmkeyboard.core.prediction.PackedTrie
import com.wasimaster.wmkeyboard.core.prediction.PackedTrieCodec
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A Romanian word list loses its cedilla on the way in, whether it is being
 * downloaded now or was downloaded before this existed. Spelt in escapes
 * throughout: U+015F and U+0219 are a pixel apart on screen.
 */
class RomanianWordlistTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val filesDir: File get() = tmp.root

    private val cedillaAnd = "\u015Fi"
    private val cedillaYouLive = "tr\u0103ie\u015Fti"
    private val cedillaCorner = "col\u0163"
    private val commaAnd = "\u0219i"
    private val commaYouLive = "tr\u0103ie\u0219ti"
    private val commaCorner = "col\u021B"

    private fun plain(name: String, text: String): File =
        File(tmp.newFolder(), name).apply { writeText(text) }

    private fun trieOf(langId: String): MappedTrie =
        MappedTrie.open(DictionaryStore.downloadedFile(filesDir, langId))!!

    @Test
    fun `a Romanian list installs with the comma letters`() {
        val entry = DictionaryCatalog.byId("ro")!!
        val words = WordlistDownloadManager.install(
            filesDir, entry, DictionaryCatalog.DictionarySize.SMALL,
            plain(entry.fileName.removeSuffix(".gz"), "$cedillaAnd 100\n$cedillaYouLive 50\n$cedillaCorner 9\n"),
        )
        assertEquals(3, words)
        val trie = trieOf("ro")
        assertTrue(trie.contains(commaAnd))
        assertTrue(trie.contains(commaYouLive))
        assertTrue(trie.contains(commaCorner))
        assertFalse(trie.contains(cedillaAnd))
        assertFalse(trie.contains(cedillaYouLive))
    }

    @Test
    fun `the two spellings of one word become one, ranked by the better count`() {
        val entry = DictionaryCatalog.byId("ro")!!
        val words = WordlistDownloadManager.install(
            filesDir, entry, DictionaryCatalog.DictionarySize.SMALL,
            // The order a counted list comes in: the cedilla spelling is the
            // frequent one, which is the whole reason it kept winning.
            plain(entry.fileName.removeSuffix(".gz"), "$cedillaAnd 2000\n$commaAnd 200\ncasa 10\n"),
        )
        assertEquals(2, words)
        assertEquals(2000, trieOf("ro").frequencyOf(commaAnd))
    }

    @Test
    fun `a Turkish list keeps its cedilla`() {
        val entry = DictionaryCatalog.byId("tr")!!
        WordlistDownloadManager.install(
            filesDir, entry, DictionaryCatalog.DictionarySize.SMALL,
            plain(entry.fileName.removeSuffix(".gz"), "$cedillaAnd 100\n"),
        )
        val trie = trieOf("tr")
        assertTrue(trie.contains(cedillaAnd))
        assertFalse(trie.contains(commaAnd))
    }

    /** A list as a build before this one left it: packed, folded, cedilla and all. */
    private fun oldList(vararg entries: Pair<String, Int>) {
        val file = DictionaryStore.downloadedFile(filesDir, "ro")
        file.parentFile?.mkdirs()
        file.outputStream().use { PackedTrieCodec.write(PackedTrie.of(entries.toList()), it) }
        // Empty: the mark that the list beside it is keyed in lower case.
        DictionaryStore.capitalsFile(filesDir, "ro").createNewFile()
    }

    @Test
    fun `a list already on disk is respelt in place`() {
        oldList(cedillaAnd to 2000, cedillaYouLive to 50, "casa" to 10)
        WordlistDownloadManager.respellRomanian(filesDir)
        val trie = trieOf("ro")
        assertEquals(2000, trie.frequencyOf(commaAnd))
        assertTrue(trie.contains(commaYouLive))
        assertFalse(trie.contains(cedillaAnd))
        assertFalse(trie.contains(cedillaYouLive))
        assertTrue(trie.contains("casa"))
    }

    @Test
    fun `the capitals move with the list`() {
        oldList(cedillaAnd to 2000, "casa" to 10)
        val capitals = DictionaryStore.capitalsFile(filesDir, "ro")
        // Shape 1: the first character is a capital, so the key spells back as
        // the list wrote it. Keyed by the cedilla word, as the list was.
        capitals.outputStream().use { PackedTrieCodec.write(PackedTrie.of(listOf(cedillaAnd to 1)), it) }

        WordlistDownloadManager.respellRomanian(filesDir)

        val shapes = MappedTrie.open(capitals)!!
        assertEquals(1, shapes.frequencyOf(commaAnd))
        assertEquals(0, shapes.frequencyOf(cedillaAnd))
        assertEquals("\u0218i", DictionaryCapitals.spelling(shapes, commaAnd))
    }

    @Test
    fun `a list that is already right is left alone`() {
        oldList(commaAnd to 2000, commaYouLive to 50)
        val file = DictionaryStore.downloadedFile(filesDir, "ro")
        val before = file.readBytes()
        WordlistDownloadManager.respellRomanian(filesDir)
        assertTrue(before.contentEquals(file.readBytes()))
    }

    @Test
    fun `a language with no list downloaded is untouched`() {
        WordlistDownloadManager.respellRomanian(filesDir)
        assertFalse(DictionaryStore.downloadedFile(filesDir, "ro").exists())
        assertFalse(DictionaryStore.partFile(filesDir, "ro").exists())
    }
}
