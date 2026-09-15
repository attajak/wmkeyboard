package com.wasimaster.wmkeyboard.core.voice.whisper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WhisperStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val model get() = WhisperCatalog.byId("base-multi")!!

    private fun materialize(file: File, content: String = "x") {
        file.parentFile?.mkdirs()
        file.writeText(content)
    }

    @Test
    fun `paths follow whisper-id-file layout`() {
        val d = tmp.root
        assertEquals(File(d, "whisper/${model.id}/${model.modelFile}"), WhisperStore.modelFile(d, model))
        assertEquals(File(d, "whisper/${model.id}/${model.vocabFile}"), WhisperStore.vocabFile(d, model))
    }

    @Test
    fun `downloaded needs both model and vocab files`() {
        val d = tmp.root
        assertFalse(WhisperStore.isDownloaded(d, model))
        materialize(WhisperStore.modelFile(d, model))
        // Model present, vocab missing → not yet usable, but a partial exists.
        assertFalse(WhisperStore.isDownloaded(d, model))
        assertTrue(WhisperStore.hasPartial(d, model))
        materialize(WhisperStore.vocabFile(d, model))
        assertTrue(WhisperStore.isDownloaded(d, model))
    }

    @Test
    fun `soleDownloadedId returns the only complete model`() {
        val d = tmp.root
        assertNull(WhisperStore.soleDownloadedId(d))
        materialize(WhisperStore.modelFile(d, model))
        materialize(WhisperStore.vocabFile(d, model))
        assertEquals(model.id, WhisperStore.soleDownloadedId(d))
    }

    @Test
    fun `effectiveModel falls back to the sole downloaded model when unselected`() {
        val d = tmp.root
        materialize(WhisperStore.modelFile(d, model))
        materialize(WhisperStore.vocabFile(d, model))
        assertEquals(model.id, WhisperStore.effectiveModel(d, "")?.id)
        assertEquals(model.id, WhisperStore.effectiveModel(d, model.id)?.id)
    }

    private fun models(vararg ids: String) = ids.map { WhisperCatalog.byId(it)!! }

    private fun pick(
        downloaded: List<WhisperModel>,
        languageId: String,
        fallback: String = "",
        pinned: Map<String, String> = emptyMap(),
    ) = WhisperStore.pickForLanguage(downloaded, languageId, fallback, pinned)?.id

    @Test
    fun `nothing downloaded means no model for any language`() {
        assertNull(pick(emptyList(), "en"))
    }

    @Test
    fun `a pinned model wins for its language only`() {
        val disk = models("small-multi", "base-de")
        assertEquals("base-de", pick(disk, "de", pinned = mapOf("de" to "base-de")))
        // Pinning German says nothing about English.
        assertEquals("small-multi", pick(disk, "en", pinned = mapOf("de" to "base-de")))
    }

    @Test
    fun `a pin to a model that cannot do the language is ignored`() {
        val disk = models("small-multi", "base-de")
        // base-de is German-only; asking it for French falls through to the
        // multilingual graph rather than transcribing French as German.
        assertEquals("small-multi", pick(disk, "fr", pinned = mapOf("fr" to "base-de")))
        // Same when the pinned model is not downloaded at all.
        assertEquals("small-multi", pick(disk, "en", pinned = mapOf("en" to "small-en")))
    }

    @Test
    fun `downloading a single-language graph routes that language to it`() {
        // Downloading base-de is itself the statement that German uses it — no
        // explicit pin needed — while everything else stays on the fallback.
        val disk = models("small-multi", "base-de")
        assertEquals("base-de", pick(disk, "de", fallback = "small-multi"))
        assertEquals("small-multi", pick(disk, "fr", fallback = "small-multi"))
    }

    @Test
    fun `the larger single-language graph wins when both sizes are downloaded`() {
        assertEquals("small-ur", pick(models("base-ur", "small-ur"), "ur"))
    }

    @Test
    fun `norwegian resolves through whisper's own code`() {
        // Our id is "nb"; the grouped graph's token is "no".
        assertEquals("base-world", pick(models("base-world"), "nb"))
    }

    @Test
    fun `the fallback is used for languages with no dedicated graph`() {
        val disk = models("base-multi", "small-multi")
        assertEquals("base-multi", pick(disk, "bn", fallback = "base-multi"))
        // With no fallback set, ranking picks the better of what is on disk.
        assertEquals("small-multi", pick(disk, "bn"))
    }

    @Test
    fun `a language nothing on disk covers still gets a model to dictate with`() {
        // Only a German graph is downloaded and the user dictates Bangla: better
        // to hand back something (settings warns about it) than a dead mic.
        assertEquals("base-de", pick(models("base-de"), "bn"))
    }

    @Test
    fun `a language whisper does not know at all falls back too`() {
        // "tlh" (Klingon) is a keyboard language with no Whisper code.
        assertEquals("small-multi", pick(models("small-multi"), "tlh", fallback = "small-multi"))
    }

    @Test
    fun `modelForLanguage reads the same answer off disk`() {
        val d = tmp.root
        val de = WhisperCatalog.byId("base-de")!!
        for (m in listOf(model, de)) {
            materialize(WhisperStore.modelFile(d, m))
            materialize(WhisperStore.vocabFile(d, m))
        }
        assertEquals("base-de", WhisperStore.modelForLanguage(d, "de", model.id, emptyMap())?.id)
        assertEquals(model.id, WhisperStore.modelForLanguage(d, "en", model.id, emptyMap())?.id)
    }

    @Test
    fun `a retired graph left on disk is not a download and is purged`() {
        val d = tmp.root
        val tinyEn = WhisperCatalog.byId("tiny-en")!!
        val retired = tinyEn.retiredModelBytes.single()
        java.io.RandomAccessFile(WhisperStore.modelFile(d, tinyEn).apply { parentFile?.mkdirs() }, "rw")
            .use { it.setLength(retired) }
        materialize(WhisperStore.vocabFile(d, tinyEn))
        assertTrue(WhisperStore.isStale(d, tinyEn))
        assertFalse(WhisperStore.isDownloaded(d, tinyEn))
        assertTrue(WhisperStore.downloadedModels(d).none { it.id == "tiny-en" })

        WhisperStore.purgeStale(d, tinyEn)
        assertFalse(WhisperStore.modelFile(d, tinyEn).exists())
        assertTrue(WhisperStore.vocabFile(d, tinyEn).isFile)

        // The current graph, at any other size, counts as downloaded.
        materialize(WhisperStore.modelFile(d, tinyEn))
        assertFalse(WhisperStore.isStale(d, tinyEn))
        assertTrue(WhisperStore.isDownloaded(d, tinyEn))
    }

    @Test
    fun `orphan dir is detected and cleanable`() {
        val d = tmp.root
        materialize(File(WhisperStore.rootDir(d), "ghost-model/some.tflite"))
        assertEquals(1, WhisperStore.orphanDirs(d).size)
        WhisperStore.deleteOrphans(d)
        assertTrue(WhisperStore.orphanDirs(d).isEmpty())
    }
}
