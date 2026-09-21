package com.wasimaster.wmkeyboard.core.prediction

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The strip's Delete edits the imported list itself (#190); an imported
 * dictionary's pairs and shortcuts sit beside its list and follow it.
 */
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

    private val combined = """
        dictionary=main:fr,locale=fr,description=Français
         word=coeur,f=103
          shortcut=cœur,f=14
          bigram=de,f=160
         word=de,f=221
    """.trimIndent()

    private fun inspect(vararg files: Pair<String, ByteArray>): CustomDictionaries.Inspection =
        CustomDictionaries.inspect(files.map { CustomDictionaries.ImportFile(it.first, it.second) })

    private fun found(vararg files: Pair<String, ByteArray>): List<CustomDictionaries.ImportCandidate> {
        val result = inspect(*files)
        assertTrue("expected found, got $result", result is CustomDictionaries.Inspection.Found)
        return (result as CustomDictionaries.Inspection.Found).dictionaries
    }

    @Test
    fun importWritesTheWordsThePairsAndTheShortcutsSideBySide() {
        val count = CustomDictionaries.import(temp.root, "fr", "main_fr.combined", combined.byteInputStream())
        assertEquals(4, count) // two words, one pair, one shortcut
        val list = CustomDictionaries.lists(temp.root, "fr").single()
        assertEquals("main_fr.txt", list.name)
        assertEquals(listOf("coeur", "de"), CustomDictionaries.entries(temp.root, "fr").map { it.first })
        assertEquals(1, CustomDictionaries.pairCount(list))
        assertEquals(1, CustomDictionaries.shortcutCount(list))
        assertEquals(mapOf("coeur" to "cœur"), CustomDictionaries.shortcuts(temp.root, "fr"))
        val pack = NgramPack.of(CustomDictionaries.pairPacks(temp.root, "fr").map { MappedNgramPack.open(it) })
        assertTrue(pack.bigramCount("coeur", "de") > 0)
        assertEquals(listOf("de"), pack.nextWords("Coeur", 5))
    }

    @Test
    fun switchingAListOffTakesItsPairsAndShortcutsWithIt() {
        CustomDictionaries.import(temp.root, "fr", "main_fr.combined", combined.byteInputStream())
        val list = CustomDictionaries.lists(temp.root, "fr").single()
        val off = CustomDictionaries.setEnabled(list, false)
        assertTrue(CustomDictionaries.pairPacks(temp.root, "fr").isEmpty())
        assertTrue(CustomDictionaries.shortcuts(temp.root, "fr").isEmpty())
        // Still counted while off, so the row can say what switching it on brings back.
        assertEquals(1, CustomDictionaries.pairCount(off))
        CustomDictionaries.setEnabled(off, true)
        assertEquals(1, CustomDictionaries.pairPacks(temp.root, "fr").size)
    }

    @Test
    fun deletingAListDeletesWhatSitsBesideIt() {
        CustomDictionaries.import(temp.root, "fr", "main_fr.combined", combined.byteInputStream())
        val list = CustomDictionaries.lists(temp.root, "fr").single()
        assertEquals(3, CustomDictionaries.filesOf(list).size)
        CustomDictionaries.remove(list)
        assertEquals(emptyList<String>(), CustomDictionaries.languageDir(temp.root, "fr").list()!!.toList())
    }

    @Test
    fun onlyTheChosenPartsAreWritten() {
        val candidate = found("main_fr.combined" to combined.toByteArray()).single()
        val written = CustomDictionaries.write(
            temp.root,
            "fr",
            candidate,
            CustomDictionaries.ImportParts(words = false, pairs = true, shortcuts = false),
        )
        assertEquals(CustomDictionaries.Written(words = 0, pairs = 1, shortcuts = 0), written)
        val list = CustomDictionaries.lists(temp.root, "fr").single()
        // The list is there to be switched and deleted, with no words in it.
        assertTrue(CustomDictionaries.entries(temp.root, "fr").isEmpty())
        assertFalse(CustomDictionaries.shortcutsFile(list).exists())
        assertEquals(1, CustomDictionaries.pairPacks(temp.root, "fr").size)
    }

    @Test
    fun choosingNothingWritesNothing() {
        val candidate = found("main_fr.combined" to combined.toByteArray()).single()
        val written = CustomDictionaries.write(
            temp.root,
            "fr",
            candidate,
            CustomDictionaries.ImportParts(words = false, pairs = false, shortcuts = false),
        )
        assertEquals(0, written.total)
        assertTrue(CustomDictionaries.languageDir(temp.root, "fr").list()!!.isEmpty())
    }

    @Test
    fun aSecondImportUnderTheSameNameDoesNotAdoptTheFirstOnesPairs() {
        CustomDictionaries.import(temp.root, "fr", "main_fr.combined", combined.byteInputStream())
        val first = CustomDictionaries.lists(temp.root, "fr").single()
        CustomDictionaries.setEnabled(first, false)
        CustomDictionaries.import(temp.root, "fr", "main_fr.txt", "bonjour 5\n".byteInputStream())
        val second = CustomDictionaries.lists(temp.root, "fr").single()
        assertEquals("main_fr (2).txt", second.name)
        assertFalse(CustomDictionaries.pairsFile(second).exists())
    }

    @Test
    fun aPlainListIsKeptByteForByte() {
        val text = "# my words\nbonjour 5\n\nchat\n"
        CustomDictionaries.import(temp.root, "fr", "mine.txt", text.byteInputStream())
        assertEquals(text, CustomDictionaries.lists(temp.root, "fr").single().readText())
    }

    @Test
    fun aBinaryFileThatIsNoDictionaryIsRefused() {
        val result = inspect("photo.jpg" to byteArrayOf(-1, -40, -1, 0, 0, 1, 2))
        assertEquals(CustomDictionaries.Inspection.Refused(CustomDictionaries.Refusal.NothingReadable), result)
    }

    /**
     * A version 2 `.dict` of two words, `omw` offering `on my way` and `the`,
     * assembled from the published format like every fixture here.
     */
    private val compiled: ByteArray = AospFixtures.header(202, attributes = listOf("locale" to "ar")) +
        AospFixtures.bytes(
            listOf(
                0x02,
                0x38, // several characters, terminal, has shortcuts
                'o'.code, 'm'.code, 'w'.code, AospFixtures.TERMINATOR,
                0x80,
                0x00, 0x0D, // the shortcut list is thirteen bytes, these two included
                0x00, // last shortcut, strength 0
            ) + AospFixtures.string("on my way") + listOf(
                0x30, // several characters, terminal
                't'.code, 'h'.code, 'e'.code, AospFixtures.TERMINATOR,
                0xFF,
            ),
        )

    private fun leftover(langId: String, name: String, bytes: ByteArray): File {
        val dir = CustomDictionaries.languageDir(temp.root, langId).apply { mkdirs() }
        return File(dir, name).apply { writeBytes(bytes) }
    }

    @Test
    fun aCompiledDictionaryAnOlderVersionCopiedInIsUnpackedInPlace() {
        // 0.5.9 copied whatever was picked to <name>.txt, byte for byte (#288).
        val file = leftover("ar", "main_ar.txt", compiled)
        // Never read as lines, repaired or not.
        assertTrue(CustomDictionaries.wordsOf(file).isEmpty())
        assertTrue(CustomDictionaries.entries(temp.root, "ar").isEmpty())

        assertEquals(CustomDictionaries.Repaired(unpacked = 1, switchedOff = 0), CustomDictionaries.repairUnreadImports(temp.root))
        assertEquals("main_ar.txt", CustomDictionaries.lists(temp.root, "ar").single().name)
        assertEquals(listOf("omw" to 5019, "the" to 10000), CustomDictionaries.entries(temp.root, "ar"))
        // As a new import of the same file would have: what it carries besides words, beside it.
        assertEquals(mapOf("omw" to "on my way"), CustomDictionaries.shortcuts(temp.root, "ar"))
        assertFalse(File(file.parentFile, file.name + ".tmp").exists())

        // And then it is a text list like any other.
        val before = file.lastModified()
        assertEquals(CustomDictionaries.Repaired(0, 0), CustomDictionaries.repairUnreadImports(temp.root))
        assertEquals(before, file.lastModified())
    }

    @Test
    fun aSwitchedOffLeftoverIsUnpackedAndStaysOff() {
        val file = leftover("ar", "main_ar.txt${CustomDictionaries.DISABLED_SUFFIX}", compiled)
        assertEquals(CustomDictionaries.Repaired(1, 0), CustomDictionaries.repairUnreadImports(temp.root))
        assertTrue(CustomDictionaries.lists(temp.root, "ar").isEmpty())
        assertEquals(listOf("omw", "the"), CustomDictionaries.wordsOf(file).map { it.first })
        // Its shortcuts wait beside it for the list to be switched on.
        assertTrue(CustomDictionaries.shortcuts(temp.root, "ar").isEmpty())
        assertEquals(1, CustomDictionaries.shortcutCount(file))
    }

    @Test
    fun aLeftoverThatCannotBeUnpackedIsSwitchedOffRatherThanReadAsWords() {
        // Each half of a version 4 dictionary, which 0.5.9 took one at a time.
        val header = leftover("en", "main_dict.txt", AospFixtures.header(403))
        val body = leftover(
            "en",
            "main_dict (2).txt",
            AospFixtures.body(listOf("yo"), listOf(AospFixtures.Entry(0, AospFixtures.probability(99)))),
        )
        val mine = list("en", "mine.txt", "hello 5\n")
        assertEquals(CustomDictionaries.Repaired(0, 2), CustomDictionaries.repairUnreadImports(temp.root))
        assertEquals(listOf(mine), CustomDictionaries.lists(temp.root, "en"))
        assertEquals(3, CustomDictionaries.allLists(temp.root, "en").size)
        assertFalse(header.exists())
        assertFalse(body.exists())
        assertEquals(listOf("hello" to 5), CustomDictionaries.entries(temp.root, "en"))
        // Switched back on by hand it still reads as nothing, and goes off again.
        val off = CustomDictionaries.allLists(temp.root, "en").first { !CustomDictionaries.isEnabled(it) }
        val on = CustomDictionaries.setEnabled(off, true)
        assertTrue(CustomDictionaries.wordsOf(on).isEmpty())
        assertEquals(CustomDictionaries.Repaired(0, 1), CustomDictionaries.repairUnreadImports(temp.root))
    }

    @Test
    fun aVersion4DictionaryNeedsBothOfItsFiles() {
        val header = AospFixtures.header(403, attributes = listOf("locale" to "en_US"))
        val body = AospFixtures.body(
            listOf("hi", "there"),
            listOf(
                AospFixtures.Entry(
                    0,
                    AospFixtures.probability(150),
                    next = listOf(AospFixtures.Entry(1, AospFixtures.probability(140))),
                ),
                AospFixtures.Entry(1, AospFixtures.probability(120)),
            ),
        )
        assertEquals(
            CustomDictionaries.Inspection.Refused(CustomDictionaries.Refusal.MissingBody),
            inspect("main.dict.header" to header),
        )
        assertEquals(
            CustomDictionaries.Inspection.Refused(CustomDictionaries.Refusal.MissingHeader),
            inspect("main.dict.body" to body),
        )
        val candidate = found("main.dict.header" to header, "main.dict.body" to body).single()
        assertEquals("main.dict", candidate.name)
        assertEquals("en_US", candidate.locale)
        assertEquals(2, candidate.words.size)
        assertEquals(1, candidate.ngrams.size)
    }

    @Test
    fun aZipGivesUpItsDictionariesAndNothingElse() {
        val header = AospFixtures.header(403)
        val body = AospFixtures.body(listOf("yo"), listOf(AospFixtures.Entry(0, AospFixtures.probability(99))))
        val zip = java.io.ByteArrayOutputStream().also { bytes ->
            java.util.zip.ZipOutputStream(bytes).use { out ->
                fun put(name: String, data: ByteArray) {
                    out.putNextEntry(java.util.zip.ZipEntry(name))
                    out.write(data)
                    out.closeEntry()
                }
                put("UserHistoryDictionary.en_US.dict/UserHistoryDictionary.en_US.dict.header", header)
                put("UserHistoryDictionary.en_US.dict/UserHistoryDictionary.en_US.dict.body", body)
                // A backup's layouts and blacklists are text, and are not word lists.
                put("layouts/custom.txt", "q w e r t y\n".toByteArray())
                put("blacklists/en.txt", "badword\n".toByteArray())
            }
        }.toByteArray()
        val candidates = found("backup.zip" to zip)
        assertEquals(listOf("UserHistoryDictionary.en_US.dict/UserHistoryDictionary.en_US.dict"), candidates.map { it.name })
        assertEquals(listOf("yo"), candidates.single().words.map { it.first })
        CustomDictionaries.write(temp.root, "en", candidates.single(), CustomDictionaries.ImportParts.ALL)
        assertEquals("UserHistoryDictionary_en_US.txt", CustomDictionaries.lists(temp.root, "en").single().name)
    }
}
