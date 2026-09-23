package com.wasimaster.wmkeyboard.core.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The DeepL client's pure parts: hosts, language codes and the two response shapes (#331). */
class DeepLClientTest {

    @Test
    fun `the key picks the host when no server is named`() {
        assertEquals("https://api-free.deepl.com", DeepLClient.baseUrl("abc-123:fx", ""))
        assertEquals("https://api-free.deepl.com", DeepLClient.baseUrl(" abc-123:fx ", "  "))
        assertEquals("https://api.deepl.com", DeepLClient.baseUrl("abc-123", ""))
    }

    @Test
    fun `a pasted server reduces to its root however it was copied`() {
        val expected = "https://deepl.example.org"
        for (pasted in listOf(
            "deepl.example.org",
            "https://deepl.example.org/",
            "https://deepl.example.org/v2",
            "https://deepl.example.org/v2/translate",
            "https://deepl.example.org/v2/write/rephrase",
        )) {
            assertEquals(pasted, expected, DeepLClient.baseUrl("key:fx", pasted))
        }
        assertEquals("http://192.168.0.10:1188", DeepLClient.baseUrl("", "http://192.168.0.10:1188/"))
        // A path of the proxy's own is kept.
        assertEquals("https://example.org/deepl", DeepLClient.baseUrl("", "https://example.org/deepl/v2"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a scheme other than http is refused`() {
        DeepLClient.baseUrl("", "ftp://deepl.example.org")
    }

    @Test
    fun `picker codes become DeepL targets`() {
        assertEquals("EN-US", DeepLClient.targetCode("en"))
        assertEquals("PT-BR", DeepLClient.targetCode("pt"))
        assertEquals("ZH-HANS", DeepLClient.targetCode("zh-CN"))
        assertEquals("ZH-HANT", DeepLClient.targetCode("zh-TW"))
        assertEquals("NB", DeepLClient.targetCode("no"))
        assertEquals("DE", DeepLClient.targetCode("de"))
    }

    @Test
    fun `picker codes become DeepL sources, auto meaning none`() {
        assertNull(DeepLClient.sourceCode("auto"))
        assertNull(DeepLClient.sourceCode(""))
        assertEquals("ZH", DeepLClient.sourceCode("zh-TW"))
        assertEquals("NB", DeepLClient.sourceCode("no"))
        assertEquals("EN", DeepLClient.sourceCode("en"))
    }

    @Test
    fun `a translation is read with its detected language in the pickers' case`() {
        val t = DeepLClient.parseTranslation(
            """{"translations":[{"detected_source_language":"EN","text":"Hallo, Welt!"}]}""",
        )
        assertEquals("Hallo, Welt!", t.text)
        assertEquals("en", t.detectedSource)
        assertTrue(t.viaDeepL)
    }

    @Test
    fun `a rephrase is read`() {
        val r = DeepLClient.parseRephrase(
            """{"improvements":[{"text":"I could really use some help.","detected_source_language":"en","target_language":"en-US"}]}""",
        )
        assertEquals("I could really use some help.", r.text)
        assertEquals("en", r.detectedLanguage)
    }

    @Test(expected = ToolHttpException::class)
    fun `an empty answer is a failure, not a blank result`() {
        DeepLClient.parseRephrase("""{"improvements":[]}""")
    }
}
