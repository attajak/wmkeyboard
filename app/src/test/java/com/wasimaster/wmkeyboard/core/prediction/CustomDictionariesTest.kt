package com.wasimaster.wmkeyboard.core.prediction

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The strip's Delete edits the imported list itself (#190). */
class CustomDictionariesTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun list(langId: String, name: String, text: String): File {
        val dir = CustomDictionaries.languageDir(temp.root, langId).apply { mkdirs() }
        return File(dir, name).apply { writeText(text) }
    }

    @Test
    fun removeWordDropsEveryLineForTheWordAndKeepsTheRest() {
        val file = list(
            "fr",
            "mine.txt",
            "# a comment\nbonjour 100\nchat 50\n\nBonjour 3\nchien\n",
        )
        assertTrue(CustomDictionaries.removeWord(temp.root, "fr", "bonjour"))
        assertEquals("# a comment\nchat 50\n\nchien\n", file.readText())
        assertFalse(CustomDictionaries.trie(temp.root, "fr").contains("bonjour"))
        assertTrue(CustomDictionaries.trie(temp.root, "fr").contains("chat"))
    }

    @Test
    fun removeWordMatchesTheLoaderNotTheWholeLine() {
        // A multi-word entry keeps "bon jour" as its word; the frequency is the
        // last token only, so "bon" alone is not it and "bon jour" is.
        val file = list("fr", "phrases.txt", "bon jour 7\nbon 9\n")
        assertTrue(CustomDictionaries.removeWord(temp.root, "fr", "bon"))
        assertEquals("bon jour 7\n", file.readText())
    }

    @Test
    fun removeWordReachesSwitchedOffListsToo() {
        val on = list("de", "a.txt", "haus 5\nbaum 4\n")
        val off = list("de", "b.txt${CustomDictionaries.DISABLED_SUFFIX}", "haus 1\n")
        assertTrue(CustomDictionaries.removeWord(temp.root, "de", "Haus"))
        assertEquals("baum 4\n", on.readText())
        assertEquals("", off.readText())
    }

    @Test
    fun removeWordLeavesAListWithoutTheWordUntouched() {
        val file = list("es", "words.txt", "hola 10\nadios 9\n")
        val before = file.lastModified()
        assertFalse(CustomDictionaries.removeWord(temp.root, "es", "gracias"))
        assertEquals("hola 10\nadios 9\n", file.readText())
        assertEquals(before, file.lastModified())
        assertFalse(File(file.parentFile, file.name + ".tmp").exists())
    }

    @Test
    fun removeWordIsFalseForALanguageWithNoLists() {
        assertFalse(CustomDictionaries.removeWord(temp.root, "xx", "word"))
        assertFalse(CustomDictionaries.removeWord(temp.root, "xx", "  "))
    }
}
