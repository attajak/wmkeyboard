package com.wasimaster.wmkeyboard.core.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The photo search client's reading of each site's answer (#349). The shapes
 * are the ones the live sites sent on 2026-09-24, cut down to what is read.
 */
class ReverseImageClientTest {

    private fun response(status: Int, location: String? = null, body: String = "") =
        ToolHttp.RawResponse(status, location, body)

    @Test
    fun `a Lens redirect is the results page`() {
        val page = ReverseImageClient.redirectTarget(
            "https://lens.google.com/v3/upload?hl=en",
            response(303, "https://www.google.com/search?vsrid=abc&udm=26&lns_mode=un"),
        )
        assertEquals("https://www.google.com/search?vsrid=abc&udm=26&lns_mode=un", page)
    }

    @Test
    fun `a relative redirect lands on the upload host`() {
        val page = ReverseImageClient.redirectTarget(
            "https://www.bing.com/images/kblob",
            response(302, "/search?q=blue&bcid=r3ak&FORM=SBIESR"),
        )
        assertEquals("https://www.bing.com/search?q=blue&bcid=r3ak&FORM=SBIESR", page)
    }

    @Test
    fun `no redirect, no page`() {
        assertNull(ReverseImageClient.redirectTarget("https://www.bing.com/images/kblob", response(200, body = "<html>")))
        assertNull(ReverseImageClient.redirectTarget("https://www.bing.com/images/kblob", response(302)))
    }

    @Test
    fun `Yandex names the stored photo`() {
        val body = """{"cnt":"pageview_candidate","blocks":[{"name":{"block":"b-page_type_search-by-image__link"},
            "params":{"originalImageUrl":"https://avatars.mds.yandex.net/get-images-cbir/1/AbC/orig","cbirId":"1/AbC"}}]}"""
        assertEquals(
            "https://yandex.com/images/search?rpt=imageview&cbir_id=1%2FAbC" +
                "&url=https%3A%2F%2Favatars.mds.yandex.net%2Fget-images-cbir%2F1%2FAbC%2Forig",
            ReverseImageClient.yandexPage(body),
        )
        assertNull(ReverseImageClient.yandexPage("""{"blocks":[{"params":{}}]}"""))
        assertNull(ReverseImageClient.yandexPage("<html>captcha</html>"))
    }

    @Test
    fun `TinEye names its results by hash`() {
        assertEquals(
            "https://tineye.com/search/e15e8a6e96e93e82",
            ReverseImageClient.tinEyePage("""{"page":1,"query_hash":"e15e8a6e96e93e82","matches":[]}"""),
        )
        assertNull(ReverseImageClient.tinEyePage("""{"query_hash":"../../evil"}"""))
        assertNull(ReverseImageClient.tinEyePage("""{"query_hash":null}"""))
    }

    @Test
    fun `a custom server may answer in any of three ways`() {
        val upload = "https://search.example.org/upload"
        assertEquals(
            "https://search.example.org/results/42",
            ReverseImageClient.customPage(upload, response(302, "/results/42")),
        )
        assertEquals(
            "https://search.example.org/results/42",
            ReverseImageClient.customPage(upload, response(200, body = "https://search.example.org/results/42\n")),
        )
        assertEquals(
            "https://search.example.org/r/7",
            ReverseImageClient.customPage(upload, response(201, body = """{"url":"r/7"}""")),
        )
        assertNull(ReverseImageClient.customPage(upload, response(200, body = "<html>hello there</html>")))
        assertNull(ReverseImageClient.customPage(upload, response(500, body = "https://x.example.org")))
    }

    @Test
    fun `only a web address is ever opened`() {
        val upload = "https://search.example.org/upload"
        assertNull(ReverseImageClient.customPage(upload, response(200, body = "javascript:alert(1)")))
        assertNull(ReverseImageClient.customPage(upload, response(302, "intent://scan#Intent;end")))
        assertNull(ReverseImageClient.resolve(upload, "file:///sdcard/x"))
        assertEquals("http://lan.example/r", ReverseImageClient.resolve(upload, "http://lan.example/r"))
    }
}
