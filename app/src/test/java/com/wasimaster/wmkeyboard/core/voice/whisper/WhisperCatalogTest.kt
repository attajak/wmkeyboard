package com.wasimaster.wmkeyboard.core.voice.whisper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WhisperCatalogTest {

    @Test
    fun `ids are unique`() {
        val ids = WhisperCatalog.models.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `every model has positive sizes and a vocab companion`() {
        for (m in WhisperCatalog.models) {
            assertTrue("${m.id} model bytes", m.modelBytes > 0)
            assertTrue("${m.id} vocab bytes", m.vocabBytes > 0)
            assertEquals(m.modelBytes + m.vocabBytes, m.sizeBytes)
            assertTrue("${m.id} model file", m.modelFile.endsWith(".tflite"))
            assertTrue("${m.id} vocab file", m.vocabFile.endsWith(".bin"))
        }
    }

    @Test
    fun `download url is the HF resolve path`() {
        val m = WhisperCatalog.byId("base-multi")!!
        assertEquals(
            "https://huggingface.co/DocWolle/whisper_tflite_models/resolve/main/${m.modelFile}",
            WhisperCatalog.downloadUrl(m.repo, m.modelFile),
        )
    }

    @Test
    fun `single-language models carry a fixed language, multilingual ones do not`() {
        assertTrue(WhisperCatalog.byId("base-multi")!!.multilingual)
        assertEquals(null, WhisperCatalog.byId("base-multi")!!.fixedLang)
        assertEquals("de", WhisperCatalog.byId("base-de")!!.fixedLang)
        assertEquals("en", WhisperCatalog.byId("tiny-en")!!.fixedLang)
    }

    @Test
    fun `every language code in the catalog is a language whisper knows`() {
        for (m in WhisperCatalog.models) {
            for (code in m.langCodes) {
                assertNotNull("${m.id} covers unknown code $code", WhisperLanguages.tokenFor(code))
            }
        }
    }

    @Test
    fun `each graph pairs with the vocab its tokenizer and spectrogram need`() {
        // The large-v3 generation wants 128 mel bands, which only the -v3 binary
        // carries; the English-only graphs want the English tokenizer; everything
        // else wants the 80-band multilingual one.
        val v3 = setOf("turbo-multi", "large-v3-multi")
        for (m in WhisperCatalog.models) {
            val expected = when {
                m.id in v3 -> "filters_vocab_multilingual-v3.bin"
                m.fixedLang == "en" -> "filters_vocab_en.bin"
                else -> "filters_vocab_multilingual.bin"
            }
            assertEquals("${m.id} vocab", expected, m.vocabFile)
        }
    }

    @Test
    fun `tiny-en downloads the English graph that forces the English prompt`() {
        // DocWolle's graph of the same name forces the multilingual prompt onto
        // the English tokenizer and transcribes almost nothing (#207).
        val m = WhisperCatalog.byId("tiny-en")!!
        assertEquals("nyadla-sys/whisper-tiny.en.tflite", m.repo)
        assertEquals(41_507_968L, m.modelBytes)
        assertEquals(setOf(41_486_616L), m.retiredModelBytes)
        assertTrue(m.modelBytes !in m.retiredModelBytes)
    }

    @Test
    fun `the 128-band vocab is fetched from the one repo that publishes it`() {
        for (id in listOf("turbo-multi", "large-v3-multi")) {
            val m = WhisperCatalog.byId(id)!!
            assertEquals("nyadla-sys/whisper-tiny.en.tflite", m.repo)
            assertEquals("cik009/whisper", m.vocabRepo)
        }
        // Every other entry takes both files from one place.
        for (m in WhisperCatalog.models.filter { it.id !in setOf("turbo-multi", "large-v3-multi") }) {
            assertEquals("${m.id} vocab repo", m.repo, m.vocabRepo)
        }
    }

    @Test
    fun `a language no graph names can only be detected`() {
        // Bangla is in no grouped graph's list and has no graph of its own, which
        // is why dictation in it can come back as Hindi.
        assertTrue(WhisperCatalog.autoDetectOnly("bn"))
        // German has both a graph of its own and a place in the grouped lists.
        assertTrue(!WhisperCatalog.autoDetectOnly("de"))
        // Thai is in TOP_WORLD but has no graph of its own: still forceable.
        assertTrue(!WhisperCatalog.autoDetectOnly("th"))
    }

    @Test
    fun `a detect-only language is offered the model that detects best`() {
        assertTrue(WhisperCatalog.recommendedFor(setOf("bn")).any { it.id == "turbo-multi" })
        // Languages a graph can be told about do not need it on the shortlist.
        assertTrue(WhisperCatalog.recommendedFor(setOf("de")).none { it.id == "turbo-multi" })
    }

    @Test
    fun `only the grouped graphs take a language token`() {
        val world = WhisperCatalog.byId("base-world")!!
        assertTrue(world.selectableLang)
        assertEquals(40, world.langCodes.size)
        assertEquals(50261, world.langTokenFor("de"))
        // Bangla is not in the TOP_WORLD subset, so nothing is forced.
        assertNull(world.langTokenFor("bn"))
        // Our Norwegian id is "nb" while Whisper's token is "no".
        assertEquals(WhisperLanguages.tokenFor("no"), world.langTokenFor("nb"))

        assertNull(WhisperCatalog.byId("base-multi")!!.langTokenFor("de"))
        assertNull(WhisperCatalog.byId("base-de")!!.langTokenFor("de"))
    }

    @Test
    fun `european group is a subset of the world group`() {
        val eu = WhisperCatalog.byId("base-eu")!!.langCodes
        val world = WhisperCatalog.byId("base-world")!!.langCodes
        assertEquals(26, eu.size)
        assertTrue(world.containsAll(eu))
    }

    @Test
    fun `coverage counts only what a model can transcribe`() {
        val codes = setOf("en", "de", "bn")
        assertEquals(3, WhisperCatalog.byId("base-multi")!!.coverageOf(codes))
        assertEquals(2, WhisperCatalog.byId("base-world")!!.coverageOf(codes))
        assertEquals(1, WhisperCatalog.byId("base-de")!!.coverageOf(codes))
    }

    @Test
    fun `recommendations fit the enabled languages`() {
        // A language with a dedicated graph gets it, and the all-languages
        // fallback is always offered.
        val german = WhisperCatalog.recommendedFor(setOf("de"))
        assertTrue(german.any { it.fixedLang == "de" })
        assertTrue(german.any { it.id == "small-multi" })

        // Two languages both inside TOP_WORLD: the grouped graph leads.
        assertEquals("base-world", WhisperCatalog.recommendedFor(setOf("de", "fr")).first().id)

        // A language no grouped graph covers still gets the multilingual one.
        assertTrue(WhisperCatalog.recommendedFor(setOf("bn")).any { it.id == "small-multi" })

        // No enabled languages at all still yields a sane default.
        assertEquals(listOf("small-multi"), WhisperCatalog.recommendedFor(emptySet()).map { it.id })
    }

    @Test
    fun `recommendations never exceed the requested limit`() {
        val many = setOf("en", "de", "fr", "es", "it", "pt", "ru", "zh")
        assertTrue(WhisperCatalog.recommendedFor(many).size <= 4)
        assertTrue(WhisperCatalog.recommendedFor(many, limit = 2).size <= 2)
    }

    @Test
    fun `small multilingual is the one recommended model`() {
        assertEquals(
            listOf("small-multi"),
            WhisperCatalog.models.filter { it.tier == WhisperTier.RECOMMENDED }.map { it.id },
        )
    }

    @Test
    fun `every size class is represented and none is recommended past small`() {
        val sizes = WhisperCatalog.models.mapTo(mutableSetOf()) { it.size }
        assertEquals(WhisperSize.entries.toSet(), sizes)
        // Medium and Large are offered but never steered towards: they are
        // 0.8-1.6 GB downloads needing comparable RAM.
        assertTrue(
            WhisperCatalog.models
                .filter { it.size > WhisperSize.SMALL }
                .none { it.tier == WhisperTier.RECOMMENDED },
        )
    }

    @Test
    fun `only multi-language models are visible until their language is enabled`() {
        val anyLanguage = WhisperCatalog.models.filter { it.fixedLang == null }.map { it.id }

        // Nothing enabled: exactly the models that work for any language.
        assertEquals(anyLanguage, WhisperCatalog.visibleFor(emptySet()).map { it.id })

        // Enabling German adds its graph and nothing else — the Urdu pair that
        // used to show up regardless stays hidden.
        val german = WhisperCatalog.visibleFor(setOf("de")).map { it.id }
        assertEquals(anyLanguage + "base-de", german)
        assertTrue(german.none { it.endsWith("-ur") })

        // Every single-language graph for an enabled language shows, not just one:
        // Urdu has both a Base and a Small.
        val urdu = WhisperCatalog.visibleFor(setOf("ur")).map { it.id }
        assertTrue(urdu.containsAll(listOf("base-ur", "small-ur")))
    }

    @Test
    fun `ranking prefers a language's own graph, then a forceable one, then bigger`() {
        val ids = WhisperCatalog.rankedFor("de").map { it.id }
        assertEquals("base-de", ids.first())
        // Grouped graphs take a language token, so they beat the auto-detecting
        // ones even when those are larger.
        assertTrue(ids.indexOf("small-world") < ids.indexOf("small-multi"))
        // Within the auto-detecting group, more parameters wins.
        assertTrue(ids.indexOf("small-multi") < ids.indexOf("base-multi"))
        assertTrue(ids.indexOf("base-multi") < ids.indexOf("tiny-multi"))
        // Nothing that cannot do German appears at all.
        assertTrue(ids.none { it == "base-fr" || it == "tiny-en" })
    }

    @Test
    fun `ranking a language no graph covers leaves only the all-language models`() {
        val ids = WhisperCatalog.rankedFor("bn").map { it.id }
        assertEquals(
            listOf(
                "large-v3-multi", "large-multi", "turbo-multi", "medium-multi",
                "small-multi", "base-multi", "tiny-multi",
            ),
            ids,
        )
    }

    @Test
    fun `single-language lookup lists every size for that language, smallest first`() {
        assertEquals(
            listOf("base-ur", "small-ur"),
            WhisperCatalog.singleLanguageFor("ur").map { it.id },
        )
        assertEquals(
            listOf("tiny-en", "base-en", "small-en", "medium-en"),
            WhisperCatalog.singleLanguageFor("en").map { it.id },
        )
        assertTrue(WhisperCatalog.singleLanguageFor("bn").isEmpty())
    }
}
