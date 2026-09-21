package com.wasimaster.wmkeyboard.core.translate

import com.google.mlkit.nl.translate.TranslateLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the hand-written language table to the library it describes.
 *
 * [OfflineTranslateLanguages.SUPPORTED] lives in the shared source set so the
 * lite build and the settings screens can read it without ML Kit, which means
 * nothing but this test notices when a library bump adds or drops a language.
 */
class OnDeviceTranslatorCatalogueTest {

    @Test
    fun `the supported set is exactly ML Kit's`() {
        assertEquals(TranslateLanguage.getAllLanguages().toSet(), OfflineTranslateLanguages.SUPPORTED)
    }

    @Test
    fun `every model code is one ML Kit accepts back`() {
        for (code in OfflineTranslateLanguages.SUPPORTED) {
            assertEquals(code, TranslateLanguage.fromLanguageTag(code))
        }
    }

    @Test
    fun `a model download is recognised by its address`() {
        val uri = "https://redirector.gvt1.com/edgedl/translate/offline/v5/high/r24/en_bn.zip?x=1"
        assertTrue(OnDeviceTranslator.isModelAddress(uri, "bn"))
        assertFalse(OnDeviceTranslator.isModelAddress(uri, "de"))
        // Some other download of this app's, which happens to mention a code.
        assertFalse(OnDeviceTranslator.isModelAddress("https://example.org/packs/bn.zip", "bn"))
    }
}
