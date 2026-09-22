package com.wasimaster.wmkeyboard.core.tools

import com.wasimaster.wmkeyboard.core.netlog.NetLog
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import com.wasimaster.wmkeyboard.core.endpoints.ServiceEndpoint
import com.wasimaster.wmkeyboard.core.endpoints.ServiceEndpoints
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Pexels client for the background-photo picker, next to [UnsplashClient].
 *
 * Three ways it differs from every other client here, each of which has bitten
 * somebody before:
 *
 * 1. The key goes in `Authorization` with **no scheme** — not `Bearer`, not
 *    `Client-ID`. It looks like a mistake next to the other two clients.
 * 2. `X-Ratelimit-Limit` reports the **monthly** quota, so the 200-an-hour cap
 *    is invisible in the headers and [PhotoRateLimit] counts it locally.
 * 3. There is no content filter of any kind, so a safe-search request simply
 *    sends nothing. A test pins that absence, because the obvious "fix" is to
 *    invent a parameter the API then rejects.
 */
object PexelsClient {

    /** api.pexels.com unless `ServiceEndpoint.PEXELS` points elsewhere. */
    private val base: String get() = ServiceEndpoints.base(ServiceEndpoint.PEXELS) + "/v1"

    /** Present on 2xx only; Pexels answers an error with no budget at all. */
    val RATE_HEADERS = setOf("X-Ratelimit-Limit", "X-Ratelimit-Remaining", "X-Ratelimit-Reset")

    /** Blocking; call on an IO dispatcher. Throws on failure. */
    fun search(query: PhotoQuery, apiKey: String): HttpResponse =
        ToolHttp.getWithHeaders(
            searchUrl(query), headers = headers(apiKey), wantHeaders = RATE_HEADERS,
            source = NetSource.PHOTOS, route = "/v1/search",
        )

    /** Blocking; call on an IO dispatcher. Pexels' own editorial feed. */
    fun curated(query: PhotoQuery, apiKey: String): HttpResponse =
        ToolHttp.getWithHeaders(
            curatedUrl(query), headers = headers(apiKey), wantHeaders = RATE_HEADERS,
            source = NetSource.PHOTOS, route = NetLog.pathOf(curatedUrl(query)),
        )

    internal fun headers(apiKey: String) = mapOf(
        "Authorization" to apiKey,
        "Accept" to "application/json",
    )

    // ---- URL building -------------------------------------------------

    internal fun searchUrl(query: PhotoQuery): String = buildString {
        append("$base/search")
        append("?query=${ToolHttp.encode(query.text.trim())}")
        append("&page=${query.page.coerceAtLeast(1)}")
        append("&per_page=${query.perPage.coerceIn(1, MAX_PER_PAGE)}")
        orientationParam(query.orientation)?.let { append("&orientation=$it") }
        colorParam(query.color)?.let { append("&color=$it") }
        // No content_filter equivalent exists. See the class comment.
    }

    internal fun curatedUrl(query: PhotoQuery): String = buildString {
        append("$base/curated")
        append("?page=${query.page.coerceAtLeast(1)}")
        append("&per_page=${query.perPage.coerceIn(1, MAX_PER_PAGE)}")
    }

    internal fun orientationParam(orientation: PhotoOrientation): String? = when (orientation) {
        PhotoOrientation.ANY -> null
        PhotoOrientation.LANDSCAPE -> "landscape"
        PhotoOrientation.PORTRAIT -> "portrait"
        // Unsplash spells the same idea "squarish".
        PhotoOrientation.SQUARE -> "square"
    }

    /**
     * Null drops the filter for this provider. Three values are near matches
     * rather than exact ones: showing the user a swatch that returns nothing
     * from one provider is worse than a slightly different hue.
     */
    internal fun colorParam(color: PhotoColor): String? = when (color) {
        PhotoColor.ANY -> null
        PhotoColor.BLACK -> "black"
        PhotoColor.WHITE -> "white"
        PhotoColor.GRAY -> "gray"
        PhotoColor.BROWN -> "brown"
        PhotoColor.RED -> "red"
        PhotoColor.ORANGE -> "orange"
        PhotoColor.YELLOW -> "yellow"
        PhotoColor.GREEN -> "green"
        PhotoColor.TEAL -> "turquoise"
        PhotoColor.BLUE -> "blue"
        PhotoColor.PURPLE -> "violet"
        PhotoColor.MAGENTA -> "pink"
        // Pexels has no black-and-white filter.
        PhotoColor.MONOCHROME -> null
    }

    // ---- parsing ------------------------------------------------------

    /**
     * Both endpoints share one envelope. Paging comes from `next_page`, which
     * is a full URL and is absent on the last page; it is read as a flag and
     * never followed, so a page always maps back to the query that asked for
     * it and the cache cannot hold the same page under two keys.
     */
    internal fun parsePage(body: String, page: Int, perPage: Int): PhotoPage {
        val root = Json.parseToJsonElement(body).jsonObject
        val items = root["photos"]?.jsonArray.orEmpty().mapNotNull { parsePhoto(it.jsonObject) }
        val total = root["total_results"]?.jsonPrimitive?.intOrNull ?: items.size
        val next = root["next_page"]?.jsonPrimitive?.contentOrNull
        return PhotoPage(
            items = items,
            page = page,
            totalResults = total,
            hasMore = if (next != null) next.isNotBlank() else page.toLong() * perPage < total,
            source = PhotoSource.PEXELS,
        )
    }

    internal fun parsePhoto(obj: JsonObject): PhotoItem? {
        val src = obj["src"]?.jsonObject ?: return null
        val original = src.text("original") ?: return null
        val width = obj["width"]?.jsonPrimitive?.intOrNull ?: 0
        val height = obj["height"]?.jsonPrimitive?.intOrNull ?: 0
        return PhotoItem(
            // Pexels ids are JSON numbers where Unsplash's are strings.
            // `content` reads both; anything narrower breaks one of them.
            id = obj["id"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: return null,
            source = PhotoSource.PEXELS,
            thumbUrl = src.text("medium") ?: src.text("small") ?: original,
            fullUrl = original,
            pageUrl = obj.text("url").orEmpty(),
            photographer = obj.text("photographer").orEmpty(),
            photographerUrl = obj.text("photographer_url").orEmpty(),
            width = width,
            height = height,
            avgColor = obj.text("avg_color").orEmpty(),
            altText = obj.text("alt").orEmpty(),
            // Pexels serves these sizes and nothing between them.
            resizable = false,
            variants = variantsOf(src, width, height),
        )
    }

    /**
     * The named sizes, as pixels. `large2x` is documented as "940x650 at DPR 2",
     * which is 1880x1300 of actual image and matters when choosing what covers
     * the keyboard. `medium` and `small` are a fixed height with a width that
     * follows the photo, so their width is derived from the photo's own shape.
     */
    internal fun variantsOf(src: JsonObject, width: Int, height: Int): List<PhotoVariant> {
        fun flexible(name: String, atHeight: Int): PhotoVariant? {
            val url = src.text(name) ?: return null
            val derived = if (height > 0) (atHeight.toLong() * width / height).toInt() else atHeight
            return PhotoVariant(url, derived.coerceAtLeast(1), atHeight)
        }
        return listOfNotNull(
            src.text("tiny")?.let { PhotoVariant(it, TINY_W, TINY_H) },
            flexible("small", SMALL_H),
            flexible("medium", MEDIUM_H),
            src.text("large")?.let { PhotoVariant(it, LARGE_W, LARGE_H) },
            src.text("landscape")?.let { PhotoVariant(it, LANDSCAPE_W, LANDSCAPE_H) },
            src.text("portrait")?.let { PhotoVariant(it, PORTRAIT_W, PORTRAIT_H) },
            src.text("large2x")?.let { PhotoVariant(it, LARGE_2X_W, LARGE_2X_H) },
            src.text("original")?.let { PhotoVariant(it, width, height) },
        )
    }

    private fun JsonObject.text(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

    private const val MAX_PER_PAGE = 80
    private const val TINY_W = 280
    private const val TINY_H = 200
    private const val SMALL_H = 130
    private const val MEDIUM_H = 350
    private const val LARGE_W = 940
    private const val LARGE_H = 650
    private const val LANDSCAPE_W = 1200
    private const val LANDSCAPE_H = 627
    private const val PORTRAIT_W = 800
    private const val PORTRAIT_H = 1200
    private const val LARGE_2X_W = 1880
    private const val LARGE_2X_H = 1300
}
