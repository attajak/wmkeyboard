package com.wasimaster.wmkeyboard.core.tools

import com.wasimaster.wmkeyboard.tools.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslateClientTest {

    @Test
    fun `free endpoint response parses segments and detected language`() {
        val body = """[[["Hello ","হ্যালো ",null,null,10],["world","বিশ্ব",null,null,10]],null,"bn"]"""
        val translation = TranslateClient.parseFree(body)
        assertEquals("Hello world", translation.text)
        assertEquals("bn", translation.detectedSource)
    }

    @Test
    fun `official v2 response parses translation and detected language`() {
        val body = """
            {"data":{"translations":[
                {"translatedText":"Hello world","detectedSourceLanguage":"bn"}
            ]}}
        """.trimIndent()
        val translation = TranslateClient.parseOfficial(body)
        assertEquals("Hello world", translation.text)
        assertEquals("bn", translation.detectedSource)
    }

    @Test
    fun `official v2 without detection still parses`() {
        val body = """{"data":{"translations":[{"translatedText":"Hola"}]}}"""
        assertEquals("", TranslateClient.parseOfficial(body).detectedSource)
    }
}

class KlipyClientTest {

    private val gifBody = """
        {"result":true,"data":{"data":[
            {"id":111,"title":"cat","file":{
                "sm":{"gif":{"url":"https://k/sm1.gif","width":200,"height":100}},
                "hd":{"gif":{"url":"https://k/hd1.gif","width":400,"height":200}}
            }},
            {"id":222,"title":"no file"}
        ],"current_page":1,"has_next":true}}
    """.trimIndent()

    @Test
    fun `gif results parse preview, full url, aspect ratio and source`() {
        val items = KlipyClient.parse(gifBody)
        // The second result has no file object and is skipped.
        assertEquals(1, items.size)
        val item = items[0]
        assertEquals("klipy_111", item.id)
        assertEquals("https://k/sm1.gif", item.previewUrl)
        assertEquals("https://k/hd1.gif", item.fullUrl)
        assertEquals("image/gif", item.mime)
        assertEquals(2f, item.aspectRatio)
        assertEquals(GifSource.KLIPY, item.source)
        assertEquals("cat", item.title)
    }

    @Test
    fun `falls back through sizes when sm and hd missing`() {
        val body = """
            {"result":true,"data":{"data":[
                {"id":"x","file":{
                    "xs":{"gif":{"url":"https://k/xs.gif"}},
                    "md":{"gif":{"url":"https://k/md.gif"}}
                }}
            ]}}
        """.trimIndent()
        val item = KlipyClient.parse(body)[0]
        assertEquals("https://k/xs.gif", item.previewUrl)
        assertEquals("https://k/md.gif", item.fullUrl)
    }

    @Test
    fun `data as a bare array also parses`() {
        val body = """
            {"result":true,"data":[
                {"id":"y","file":{"sm":{"gif":{"url":"https://k/s.gif"}},"hd":{"gif":{"url":"https://k/h.gif"}}}}
            ]}
        """.trimIndent()
        assertEquals(1, KlipyClient.parse(body).size)
    }

    @Test
    fun `missing data parses to empty list`() {
        assertTrue(KlipyClient.parse("{}").isEmpty())
        assertTrue(KlipyClient.parse("""{"result":true,"data":{}}""").isEmpty())
    }

    @Test
    fun `categories parse from a wrapped data array`() {
        val body = """
            {"result":true,"data":{"data":[
                {"name":"Reactions","slug":"reactions"},
                {"title":"Animals"}
            ]}}
        """.trimIndent()
        val categories = KlipyClient.parseCategories(body)
        assertEquals(listOf("reactions", "Animals"), categories.map { it.term })
        assertEquals(listOf("Reactions", "Animals"), categories.map { it.label })
    }

    @Test
    fun `categories parse from bare strings`() {
        val body = """{"data":["love","dance",""]}"""
        val categories = KlipyClient.parseCategories(body)
        assertEquals(listOf("love", "dance"), categories.map { it.term })
    }

    @Test
    fun `an unknown category shape parses to empty list`() {
        // A wrong guess at the endpoint has to look like "no categories",
        // never like an error.
        assertTrue(KlipyClient.parseCategories("{}").isEmpty())
        assertTrue(KlipyClient.parseCategories("""{"data":{"nope":1}}""").isEmpty())
        assertTrue(KlipyClient.parseCategories("<html>404</html>").isEmpty())
    }
}

class GiphyClientTest {

    @Test
    fun `results parse preview, original and aspect ratio`() {
        val body = """
            {"data":[
                {"id":"abc","title":"Funny Cat GIF","images":{
                    "fixed_width_small":{"url":"https://g/small.gif","width":"200","height":"100"},
                    "original":{"url":"https://g/full.gif","width":"400","height":"200"}
                }},
                {"id":"noimages"}
            ]}
        """.trimIndent()
        val items = GiphyClient.parse(body)
        assertEquals(1, items.size)
        val item = items[0]
        assertEquals("giphy_abc", item.id)
        assertEquals("https://g/small.gif", item.previewUrl)
        assertEquals("https://g/full.gif", item.fullUrl)
        assertEquals("image/gif", item.mime)
        assertEquals(2f, item.aspectRatio)
        assertEquals(GifSource.GIPHY, item.source)
        assertEquals("Funny Cat GIF", item.title)
    }

    @Test
    fun `falls back to original when small renditions missing`() {
        val body = """
            {"data":[{"id":"x","images":{"original":{"url":"https://g/only.gif"}}}]}
        """.trimIndent()
        val items = GiphyClient.parse(body)
        assertEquals("https://g/only.gif", items[0].previewUrl)
        assertEquals("https://g/only.gif", items[0].fullUrl)
    }

    @Test
    fun `missing data parses to empty list`() {
        assertTrue(GiphyClient.parse("{}").isEmpty())
    }

    @Test
    fun `categories prefer the encoded name as the search term`() {
        val body = """
            {"data":[
                {"name":"Reaction GIFs","name_encoded":"reaction-gifs","subcategories":[{"name":"x"}]},
                {"name":"Animals"}
            ]}
        """.trimIndent()
        val categories = GiphyClient.parseCategories(body)
        assertEquals(listOf("reaction-gifs", "Animals"), categories.map { it.term })
        assertEquals(listOf("Reaction GIFs", "Animals"), categories.map { it.label })
    }

    @Test
    fun `trending searches parse as categories`() {
        val body = """{"data":["birthday","monday"]}"""
        val categories = GiphyClient.parseCategories(body)
        assertEquals(listOf("birthday", "monday"), categories.map { it.term })
        assertEquals(listOf("birthday", "monday"), categories.map { it.label })
    }

    @Test
    fun `missing category data parses to empty list`() {
        assertTrue(GiphyClient.parseCategories("{}").isEmpty())
        assertTrue(GiphyClient.parseCategories("nonsense").isEmpty())
    }
}

class MediaCategoriesTest {

    private fun provider(vararg terms: String) = terms.map { MediaCategory(term = it, label = it) }

    @Test
    fun `normalise dedupes on the term, ignoring case`() {
        val categories = MediaCategories.normalise(provider("Love", "love", "LOVE ", "cats"), false)
        assertEquals(listOf("Love", "cats"), categories.map { it.term })
    }

    @Test
    fun `normalise drops blank and overlong terms`() {
        val long = "a".repeat(33)
        assertEquals(
            listOf("cats"),
            MediaCategories.normalise(provider("", "   ", long, "cats"), false).map { it.term },
        )
    }

    @Test
    fun `normalise caps the row`() {
        val many = provider(*Array(50) { "term$it" })
        assertEquals(MediaCategories.MAX_CATEGORIES, MediaCategories.normalise(many, false).size)
    }

    @Test
    fun `normalise falls back to the bundle when nothing is usable`() {
        assertEquals(MediaCategories.bundledGif, MediaCategories.normalise(emptyList(), false))
        assertEquals(MediaCategories.bundledSticker, MediaCategories.normalise(provider(""), true))
    }

    @Test
    fun `bundled categories carry a label resource and an untranslated term`() {
        for (category in MediaCategories.bundledGif + MediaCategories.bundledSticker) {
            assertTrue(category.labelRes != 0)
            assertEquals("", category.label)
            assertTrue(category.term.isNotBlank())
            assertTrue(category.term.all { it in 'a'..'z' })
        }
    }

    @Test
    fun `bundled terms are unique within a list`() {
        for (list in listOf(MediaCategories.bundledGif, MediaCategories.bundledSticker)) {
            assertEquals(list.size, list.map { it.term }.toSet().size)
        }
    }
}

class MediaCategoryCacheTest {

    private val categories = listOf(MediaCategory(term = "love", label = "Love"))

    @Test
    fun `entries expire`() {
        MediaCategoryCache.clear()
        MediaCategoryCache.put(GifSource.KLIPY, sticker = false, categories, nowMs = 0L)
        assertEquals(categories, MediaCategoryCache.get(GifSource.KLIPY, false, nowMs = 60_000L))
        assertNull(MediaCategoryCache.get(GifSource.KLIPY, false, nowMs = 13 * 60 * 60_000L))
    }

    @Test
    fun `an empty answer is cached too`() {
        MediaCategoryCache.clear()
        MediaCategoryCache.put(GifSource.GIPHY, sticker = true, emptyList(), nowMs = 0L)
        assertEquals(emptyList<MediaCategory>(), MediaCategoryCache.get(GifSource.GIPHY, true, 1L))
    }

    @Test
    fun `keys separate the provider and the panel`() {
        val gifKey = MediaCategoryCache.keyOf(GifSource.KLIPY, sticker = false)
        assertTrue(gifKey != MediaCategoryCache.keyOf(GifSource.KLIPY, sticker = true))
        assertTrue(gifKey != MediaCategoryCache.keyOf(GifSource.GIPHY, sticker = false))
    }
}

class GifSourcesTest {

    private fun item(id: String, source: GifSource) =
        GifItem(id, "p", "f", "image/gif", 1f, source)

    @Test
    fun `interleave alternates sources evenly`() {
        val klipy = listOf(item("k1", GifSource.KLIPY), item("k2", GifSource.KLIPY), item("k3", GifSource.KLIPY))
        val giphy = listOf(item("g1", GifSource.GIPHY))
        val merged = GifSources.interleave(listOf(klipy, giphy))
        assertEquals(listOf("k1", "g1", "k2", "k3"), merged.map { it.id })
    }

    @Test
    fun `interleave of empty lists is empty`() {
        assertTrue(GifSources.interleave(listOf(emptyList(), emptyList())).isEmpty())
    }

    private fun ratioItem(id: String, ratio: Float) =
        GifItem(id, "p", "f", "image/gif", ratio, GifSource.KLIPY)

    @Test
    fun `squarish items pack three to a row`() {
        val items = (1..7).map { ratioItem("i$it", 1f) }
        val rows = GifSources.rows(items)
        assertEquals(listOf(3, 3, 1), rows.map { it.size })
    }

    @Test
    fun `squarish items pack as many to a row as asked`() {
        val items = (1..7).map { ratioItem("i$it", 1f) }
        assertEquals(listOf(5, 2), GifSources.rows(items, perRow = 5).map { it.size })
        assertEquals(List(7) { 1 }, GifSources.rows(items, perRow = 1).map { it.size })
    }

    @Test
    fun `wide items still close a roomier row early`() {
        // Five to a row targets 5.2; three wide ones already carry 5.4.
        val items = (1..6).map { ratioItem("w$it", 1.8f) }
        assertEquals(listOf(3, 3), GifSources.rows(items, perRow = 5).map { it.size })
    }

    @Test
    fun `wide items pack two to a row`() {
        val items = (1..4).map { ratioItem("w$it", 1.8f) }
        assertEquals(listOf(2, 2), GifSources.rows(items).map { it.size })
    }

    @Test
    fun `a wide item does not join an already loaded row`() {
        // 1.3 + 1.3 = 2.6, under target; adding 2.4 would blow past the max,
        // so the wide one opens the next row instead of flattening this one.
        val rows = GifSources.rows(
            listOf(ratioItem("a", 1.3f), ratioItem("b", 1.3f), ratioItem("c", 2.4f)),
        )
        assertEquals(listOf(2, 1), rows.map { it.size })
    }

    @Test
    fun `degenerate ratios are clamped for the cell`() {
        assertEquals(2.6f, GifSources.cellRatio(ratioItem("banner", 10f)))
        assertEquals(0.5f, GifSources.cellRatio(ratioItem("strip", 0.05f)))
    }

    @Test
    fun `rows of an empty list are empty`() {
        assertTrue(GifSources.rows(emptyList()).isEmpty())
    }
}

class BraveSearchClientTest {

    @Test
    fun `web results parse title, cleaned snippet and display url`() {
        val body = """
            {"web":{"results":[
                {"title":"Kotlin","url":"https://kotlinlang.org/",
                 "description":"A <strong>modern</strong> language.",
                 "meta_url":{"netloc":"kotlinlang.org"}},
                {"description":"missing title and url"}
            ]}}
        """.trimIndent()
        val results = BraveSearchClient.parseWeb(body)
        assertEquals(1, results.size)
        assertEquals("Kotlin", results[0].title)
        assertEquals("A modern language.", results[0].snippet)
        assertEquals("kotlinlang.org", results[0].displayUrl)
    }

    @Test
    fun `display url falls back to the url host`() {
        val body = """{"web":{"results":[{"title":"T","url":"https://example.com/page"}]}}"""
        assertEquals("example.com", BraveSearchClient.parseWeb(body)[0].displayUrl)
    }

    @Test
    fun `image results parse thumb, full url and inferred mime`() {
        val body = """
            {"results":[
                {"title":"A cat","url":"https://site/page",
                 "thumbnail":{"src":"https://imgs/thumb.jpg"},
                 "properties":{"url":"https://site/cat.PNG"}}
            ]}
        """.trimIndent()
        val results = BraveSearchClient.parseImages(body)
        assertEquals(1, results.size)
        assertEquals("https://imgs/thumb.jpg", results[0].thumbUrl)
        assertEquals("https://site/cat.PNG", results[0].imageUrl)
        assertEquals("image/png", results[0].mime)
        assertEquals("https://site/page", results[0].contextUrl)
    }

    @Test
    fun `mime inference handles query strings and defaults to jpeg`() {
        assertEquals("image/gif", BraveSearchClient.mimeFromUrl("https://x/a.gif?width=200"))
        assertEquals("image/jpeg", BraveSearchClient.mimeFromUrl("https://x/no-extension"))
    }

    @Test
    fun `empty responses parse to empty lists`() {
        assertTrue(BraveSearchClient.parseWeb("{}").isEmpty())
        assertTrue(BraveSearchClient.parseImages("{}").isEmpty())
    }
}

class ToolHttpTest {

    @Test
    fun `google style error body surfaces the api message`() {
        val body = """{"error":{"code":403,"message":"API key not valid."}}"""
        assertEquals("API key not valid.", ToolHttp.apiErrorText(body))
        // The provider already wrote in the user's language, so its words ride
        // on the failure verbatim alongside our own wording for the status.
        val failure = ToolHttp.httpFailure(403, body)
        assertEquals("API key not valid.", failure.apiMessage)
        assertEquals(R.string.core_tools_error_http_key_rejected, failure.messageRes)
    }

    @Test
    fun `unparseable error body falls back to status text`() {
        // Nothing to surface from the body, so the failure carries only our
        // wording. That wording is a resource id here rather than the English
        // sentence it renders as — which is also what keeps this test from
        // rotting the next time the sentence is reworded.
        assertNull(ToolHttp.apiErrorText("<html>nope</html>"))
        val failure = ToolHttp.httpFailure(429, "<html>nope</html>")
        assertNull(failure.apiMessage)
        assertEquals(R.string.core_tools_error_http_rate_limit, failure.messageRes)
        // The status is the argument that fills the "(HTTP %1$d)" placeholder.
        assertEquals(429, failure.status)
    }
}
