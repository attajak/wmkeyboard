package com.wasimaster.wmkeyboard.core.tools

import android.util.Base64
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import com.wasimaster.wmkeyboard.tools.R
import java.net.URI
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * Reverse image search for the camera tool's Search button (#349): uploads a
 * photo to a search site and hands back the address of its results page, for
 * the browser to open.
 *
 * None of these sites has a documented upload API. Each function makes the
 * request the site's own "search by image" button makes, checked against the
 * live sites on 2026-09-24, so any one of them can change without notice. A
 * change shows up as [ToolHttpException] with [R.string.core_tools_error_photo_search_no_page],
 * never as a wrong page, because every answer is read for the one thing it
 * must hold and refused without it.
 *
 * The results page is never fetched here. It is for the user's browser, where
 * the site can run its scripts and the user can scroll, open and search on.
 *
 * Blocking; call on an IO dispatcher.
 */
object ReverseImageClient {

    private val json = Json { ignoreUnknownKeys = true }

    /** Google Lens on the web: a 303 to the results page. */
    fun googleLens(jpeg: ByteArray, language: String): String {
        val url = "https://lens.google.com/v3/upload?hl=${ToolHttp.encode(language)}" +
            "&stcs=${System.currentTimeMillis()}"
        val response = ToolHttp.postMultipartUnfollowed(
            url,
            fields = emptyList(),
            file = photo("encoded_image", jpeg),
            source = NetSource.PHOTO_SEARCH,
            route = "/v3/upload",
        )
        return redirectPage(url, response)
    }

    /**
     * Bing Visual Search. `kblob` takes the photo as base64 in a text field,
     * which is what Bing's own page sends, and answers with a redirect.
     */
    fun bing(jpeg: ByteArray): String {
        val url = "https://www.bing.com/images/kblob"
        val response = ToolHttp.postMultipartUnfollowed(
            url,
            fields = listOf("imageBin" to Base64.encodeToString(jpeg, Base64.NO_WRAP)),
            file = null,
            source = NetSource.PHOTO_SEARCH,
            route = "/images/kblob",
        )
        return redirectPage(url, response)
    }

    /** Yandex Images: JSON naming the stored photo, which the results address carries. */
    fun yandex(jpeg: ByteArray): String {
        val request = """{"blocks":[{"block":"b-page_type_search-by-image__link"}]}"""
        val url = "https://yandex.com/images/search?rpt=imageview&format=json&request=${ToolHttp.encode(request)}"
        val response = ToolHttp.postMultipartUnfollowed(
            url,
            fields = emptyList(),
            file = photo("upfile", jpeg),
            source = NetSource.PHOTO_SEARCH,
            route = "/images/search",
        )
        failIfError(response)
        return yandexPage(response.body) ?: throw noPage()
    }

    /** TinEye: JSON whose `query_hash` names the results page. */
    fun tinEye(jpeg: ByteArray): String {
        val url = "https://tineye.com/api/v1/result_json/?sort=score&order=desc"
        val response = ToolHttp.postMultipartUnfollowed(
            url,
            fields = emptyList(),
            file = photo("image", jpeg),
            source = NetSource.PHOTO_SEARCH,
            route = "/api/v1/result_json/",
        )
        // Read before the status: a search with no matches may still name its page.
        return tinEyePage(response.body) ?: run {
            failIfError(response)
            throw noPage()
        }
    }

    /**
     * The user's own server. The photo goes in [field] as multipart form
     * data; the server answers with a redirect to the results, or with the
     * results address as the whole body, or as JSON `{"url": "…"}`.
     */
    fun custom(jpeg: ByteArray, uploadUrl: String, field: String): String {
        val url = uploadUrl.trim()
        if (!isWebAddress(url)) {
            throw ToolHttpException(R.string.core_tools_error_photo_search_no_server)
        }
        val response = ToolHttp.postMultipartUnfollowed(
            url,
            fields = emptyList(),
            file = photo(field.trim().ifEmpty { "image" }, jpeg),
            source = NetSource.PHOTO_SEARCH,
        )
        return customPage(url, response) ?: run {
            failIfError(response)
            throw noPage()
        }
    }

    private fun photo(field: String, jpeg: ByteArray) =
        ToolHttp.FilePart(field, "photo.jpg", "image/jpeg", jpeg)

    private fun redirectPage(uploadUrl: String, response: ToolHttp.RawResponse): String {
        redirectTarget(uploadUrl, response)?.let { return it }
        failIfError(response)
        throw noPage()
    }

    private fun failIfError(response: ToolHttp.RawResponse) {
        if (response.status >= 400) {
            throw ToolHttpException(R.string.core_tools_error_photo_search_refused, response.status)
        }
    }

    private fun noPage() = ToolHttpException(R.string.core_tools_error_photo_search_no_page)

    /** Where a 3xx pointed, made absolute; null for anything else. */
    internal fun redirectTarget(uploadUrl: String, response: ToolHttp.RawResponse): String? {
        if (response.status !in 300..399) return null
        val location = response.location?.trim().orEmpty()
        if (location.isEmpty()) return null
        return resolve(uploadUrl, location)
    }

    internal fun yandexPage(body: String): String? {
        val params = parse(body)?.get("blocks")?.let { runCatching { it.jsonArray }.getOrNull() }
            ?.firstOrNull()?.let { runCatching { it.jsonObject }.getOrNull() }
            ?.get("params")?.let { runCatching { it.jsonObject }.getOrNull() }
            ?: return null
        val id = params.string("cbirId") ?: return null
        val original = params.string("originalImageUrl") ?: return null
        return "https://yandex.com/images/search?rpt=imageview" +
            "&cbir_id=${ToolHttp.encode(id)}&url=${ToolHttp.encode(original)}"
    }

    internal fun tinEyePage(body: String): String? {
        val hash = parse(body)?.string("query_hash") ?: return null
        // A hex digest; anything else is not something to paste into a path.
        if (!hash.all { it in '0'..'9' || it in 'a'..'z' || it in 'A'..'Z' }) return null
        return "https://tineye.com/search/$hash"
    }

    internal fun customPage(uploadUrl: String, response: ToolHttp.RawResponse): String? {
        redirectTarget(uploadUrl, response)?.let { return it }
        if (response.status !in 200..299) return null
        val body = response.body.trim()
        val stated = if (body.startsWith("{")) parse(body)?.string("url") else body.takeIf { it.none(Char::isWhitespace) }
        return stated?.takeIf { it.isNotEmpty() }?.let { resolve(uploadUrl, it) }
    }

    /**
     * [location] against [base], or null unless the result is an http(s)
     * address. A server is choosing what the phone opens here, and a
     * `javascript:` or `intent:` answer must not get as far as the browser.
     */
    internal fun resolve(base: String, location: String): String? =
        runCatching { URI(base).resolve(location).toString() }.getOrNull()?.takeIf(::isWebAddress)

    private fun isWebAddress(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase()
        return (scheme == "https" || scheme == "http") && !uri.host.isNullOrEmpty()
    }

    private fun parse(body: String): JsonObject? =
        runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()

    /** A string member; a JSON null or a number is not an address. */
    private fun JsonObject.string(key: String): String? =
        (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
}
