package com.wasimaster.wmkeyboard.core.emoji

import com.wasimaster.wmkeyboard.core.script.LanguageRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The generated catalogue of downloadable emoji dictionaries. Guards the
 * invariants `tools/emoji/generate_dict_catalog.py` enforces, so a hand-edit
 * or a bad regeneration fails here rather than on a device.
 */
class EmojiDictCatalogTest {

    @Test fun coversTheLanguagesTheRepoHas() {
        assertTrue("expected the full catalogue", EmojiDictCatalog.entries.size > 100)
    }

    @Test fun everyEntryNamesALanguageTheAppKnows() {
        val known = LanguageRegistry.all.mapTo(HashSet()) { it.id }
        val strangers = EmojiDictCatalog.entries.map { it.languageId }.filterNot { it in known }
        assertEquals("catalogue names languages the registry doesn't", emptyList<String>(), strangers)
    }

    @Test fun oneEntryPerLanguage() {
        val ids = EmojiDictCatalog.entries.map { it.languageId }
        assertEquals(ids.distinct().size, ids.size)
    }

    /** A pack this small is an upstream stub; offering it would change nothing. */
    @Test fun everyEntryIsWorthDownloading() {
        val stubs = EmojiDictCatalog.entries.filter { it.emojiCount < 100 }
        assertEquals(emptyList<EmojiDictEntry>(), stubs)
        assertTrue(EmojiDictCatalog.entries.all { it.approxGzBytes > 0 })
    }

    @Test fun urlsPointAtTheDataRepo() {
        for (entry in EmojiDictCatalog.entries) {
            assertTrue(
                "bad url ${entry.url}",
                entry.url.startsWith("https://raw.githubusercontent.com/wasi-master/wmkeyboard-data/"),
            )
            assertTrue("bad url ${entry.url}", entry.url.endsWith("_emoji.json.gz"))
            // The file lives in its folder, named after its code.
            assertTrue("bad url ${entry.url}", "/${entry.repoDir}/${entry.repoCode}_" in entry.url)
        }
    }

    /** The repo spells Norwegian `no`; the registry calls it `nb`. */
    @Test fun repoCodesAreRemappedWhereTheySpellItDifferently() {
        val norwegian = EmojiDictCatalog.forLanguage("nb")
        assertEquals("no", norwegian?.repoCode)
        assertTrue(norwegian!!.url.endsWith("/data/no/no_emoji.json.gz"))
    }

    /** Banglish's pack is filed with the rest of the Banglish data, under `bn/`. */
    @Test fun banglishPackLivesBesideBengali() {
        val banglish = EmojiDictCatalog.forLanguage("bn_rom")
        assertEquals("bn_rom", banglish?.repoCode)
        assertTrue(banglish!!.url.endsWith("/data/bn/bn_rom_emoji.json.gz"))
        assertTrue(EmojiDictCatalog.forLanguage("bn")!!.url.endsWith("/data/bn/bn_emoji.json.gz"))
    }

    @Test fun lookupsAnswerForKnownAndUnknownLanguages() {
        assertTrue(EmojiDictCatalog.has("bn"))
        assertEquals("bn", EmojiDictCatalog.forLanguage("bn")?.repoCode)
        assertNull(EmojiDictCatalog.forLanguage("zzz"))
        assertTrue(!EmojiDictCatalog.has("zzz"))
    }
}
