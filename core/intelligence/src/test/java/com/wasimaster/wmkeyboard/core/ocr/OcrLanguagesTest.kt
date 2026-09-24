package com.wasimaster.wmkeyboard.core.ocr

import com.wasimaster.wmkeyboard.core.script.LanguageRegistry
import com.wasimaster.wmkeyboard.core.settings.OcrEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which Tesseract pack reads each keyboard language, and which engine runs. */
class OcrLanguagesTest {

    private fun lang(id: String) = LanguageRegistry.byId(id)

    @Test
    fun everyPackHandedOutHasASize() {
        for (language in LanguageRegistry.all) {
            val pack = OcrLanguages.packFor(language) ?: continue
            assertTrue("${language.id} -> $pack has no size", OcrLanguages.sizeOf(pack) > 0L)
        }
    }

    @Test
    fun languagesWithTheirOwnPack() {
        assertEquals("ben", OcrLanguages.packFor(lang("bn")))
        assertEquals("eng", OcrLanguages.packFor(lang("en")))
        assertEquals("chi_sim", OcrLanguages.packFor(lang("zh")))
        assertEquals("srp_latn", OcrLanguages.packFor(lang("sr-Latn")))
        assertEquals("nor", OcrLanguages.packFor(lang("nn")))
    }

    @Test
    fun aLanguageWithoutAPackBorrowsItsScriptsPack() {
        // Maithili is written in Devanagari and has no pack of its own.
        assertEquals("hin", OcrLanguages.packFor(lang("mai")))
        // Romanised Hindi is Latin text.
        assertEquals("eng", OcrLanguages.packFor(lang("hi_rom")))
    }

    @Test
    fun notationLayoutsGetNoPack() {
        assertNull(OcrLanguages.packFor(lang("ipa")))
        assertNull(OcrLanguages.packFor(lang("morse")))
    }

    @Test
    fun autoLeavesLatinToMlKit() {
        assertNull(OcrLanguages.tesseractPack(OcrEngine.AUTO, lang("en"), true))
        assertEquals("ben", OcrLanguages.tesseractPack(OcrEngine.AUTO, lang("bn"), true))
    }

    @Test
    fun forcedEnginesAndAMissingLibrary() {
        assertEquals("eng", OcrLanguages.tesseractPack(OcrEngine.TESSERACT, lang("en"), true))
        assertNull(OcrLanguages.tesseractPack(OcrEngine.ML_KIT, lang("bn"), true))
        assertNull(OcrLanguages.tesseractPack(OcrEngine.TESSERACT, lang("bn"), false))
    }

    @Test
    fun chipOffersOneLanguagePerWayOfReading() {
        val enabled = listOf(lang("en"), lang("fr"), lang("bn"), lang("ipa"))
        // English and French both go to ML Kit under Auto; IPA reads with nothing.
        assertEquals(listOf("en", "bn"), OcrLanguages.chipLanguages(OcrEngine.AUTO, enabled, true).map { it.id })
        assertEquals(
            listOf("en", "fr", "bn"),
            OcrLanguages.chipLanguages(OcrEngine.TESSERACT, enabled, true).map { it.id },
        )
        assertTrue(OcrLanguages.chipLanguages(OcrEngine.ML_KIT, enabled, true).isEmpty())
    }

    @Test
    fun packsGroupTheLanguagesThatShareThem() {
        val packs = OcrLanguages.packsFor(listOf(lang("nb"), lang("nn"), lang("bn")))
        assertEquals(listOf("nor", "ben"), packs.map { it.first })
        assertEquals(listOf("nb", "nn"), packs.first().second.map { it.id })
    }
}
