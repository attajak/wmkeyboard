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
 * Unsplash client for the background-photo picker, next to [PexelsClient].
 *
 * The key goes in an `Authorization: Client-ID` header. Their free tier allows
 * 50 requests an hour, so [PhotoCache] and [PhotoRateLimit] sit in front of
 * this rather than beside it, and the rotation prefetcher takes ten photos per
 * request through [random] instead of one.
 *
 * Two things their guidelines require and this client carries out: thumbnails
 * stay hotlinked from the URLs the API returns (that is how photographers are
 * credited with views), and [triggerDownload] fires once per photo the user
 * really takes.
 */
object UnsplashClient {

    /** api.unsplash.com unless `ServiceEndpoint.UNSPLASH` points elsewhere. */
    private val base: String get() = ServiceEndpoints.base(ServiceEndpoint.UNSPLASH)

    /** The headers whose values [PhotoRateLimit] wants back. */
    val RATE_HEADERS = setOf("X-Ratelimit-Limit", "X-Ratelimit-Remaining")

    /** Blocking; call on an IO dispatcher. Throws on failure. */
    fun search(query: PhotoQuery, apiKey: String): HttpResponse =
        ToolHttp.getWithHeaders(
            searchUrl(query), headers = headers(apiKey), wantHeaders = RATE_HEADERS,
            source = NetSource.PHOTOS, route = "/search/photos",
        )

    /** Blocking; call on an IO dispatcher. The curated feed, or one topic. */
    fun feed(query: PhotoQuery, apiKey: String): HttpResponse =
        ToolHttp.getWithHeaders(
            feedUrl(query), headers = headers(apiKey), wantHeaders = RATE_HEADERS,
            source = NetSource.PHOTOS, route = NetLog.pathOf(feedUrl(query)),
        )

    /**
     * Up to 30 photos for one request, which is what makes an unattended
     * prefetch affordable against a 50-per-hour budget.
     */
    fun random(query: PhotoQuery, apiKey: String, count: Int): HttpResponse =
        ToolHttp.getWithHeaders(
            randomUrl(query, count), headers = headers(apiKey), wantHeaders = RATE_HEADERS,
            // Only the rotating pool asks for random photos, and it does so unattended.
            source = NetSource.PHOTOS, route = "/photos/random", background = true,
        )

    /**
     * The download ping their guidelines require whenever a user really takes a
     * photo. Must carry the key, or Unsplash answers 401 and the photographer
     * is not credited. Callers run this off the critical path and swallow any
     * failure: a tracking call must never be the reason a background did not
     * get set.
     */
    fun triggerDownload(downloadLocation: String, apiKey: String) {
        if (downloadLocation.isBlank()) return
        ToolHttp.get(downloadLocation, headers = headers(apiKey), source = NetSource.PHOTOS, route = "/photos/{id}/download")
    }

    private fun headers(apiKey: String) = mapOf(
        "Authorization" to "Client-ID $apiKey",
        "Accept-Version" to "v1",
        "Accept" to "application/json",
    )

    // ---- URL building -------------------------------------------------

    internal fun searchUrl(query: PhotoQuery): String = buildString {
        append("$base/search/photos")
        append("?query=${ToolHttp.encode(query.text.trim())}")
        append("&page=${query.page.coerceAtLeast(1)}")
        append("&per_page=${query.perPage.coerceIn(1, MAX_PER_PAGE)}")
        if (query.safe) append("&content_filter=high")
        orientationParam(query.orientation)?.let { append("&orientation=$it") }
        colorParam(query.color)?.let { append("&color=$it") }
    }

    internal fun feedUrl(query: PhotoQuery): String = buildString {
        if (query.topicId.isNotBlank()) {
            append("$base/topics/${ToolHttp.encode(query.topicId)}/photos")
        } else {
            append("$base/photos")
        }
        append("?page=${query.page.coerceAtLeast(1)}")
        append("&per_page=${query.perPage.coerceIn(1, MAX_PER_PAGE)}")
        orientationParam(query.orientation)?.let { append("&orientation=$it") }
    }

    internal fun randomUrl(query: PhotoQuery, count: Int): String = buildString {
        append("$base/photos/random")
        append("?count=${count.coerceIn(1, MAX_RANDOM_COUNT)}")
        if (query.text.isNotBlank()) append("&query=${ToolHttp.encode(query.text.trim())}")
        if (query.topicId.isNotBlank()) append("&topics=${ToolHttp.encode(query.topicId)}")
        if (query.safe) append("&content_filter=high")
        orientationParam(query.orientation)?.let { append("&orientation=$it") }
    }

    /** Unsplash calls a roughly square photo "squarish"; Pexels calls it "square". */
    internal fun orientationParam(orientation: PhotoOrientation): String? = when (orientation) {
        PhotoOrientation.ANY -> null
        PhotoOrientation.LANDSCAPE -> "landscape"
        PhotoOrientation.PORTRAIT -> "portrait"
        PhotoOrientation.SQUARE -> "squarish"
    }

    /**
     * Null drops the filter for this provider. Grey and brown are Pexels-only,
     * and a request carrying them comes back empty rather than unfiltered, so
     * a mixed grid would lose its Unsplash half for no visible reason.
     */
    internal fun colorParam(color: PhotoColor): String? = when (color) {
        PhotoColor.ANY -> null
        PhotoColor.MONOCHROME -> "black_and_white"
        PhotoColor.BLACK -> "black"
        PhotoColor.WHITE -> "white"
        PhotoColor.RED -> "red"
        PhotoColor.ORANGE -> "orange"
        PhotoColor.YELLOW -> "yellow"
        PhotoColor.GREEN -> "green"
        PhotoColor.TEAL -> "teal"
        PhotoColor.BLUE -> "blue"
        PhotoColor.PURPLE -> "purple"
        PhotoColor.MAGENTA -> "magenta"
        PhotoColor.GRAY, PhotoColor.BROWN -> null
    }

    // ---- parsing ------------------------------------------------------

    /** `/search/photos` answers with an envelope that counts the whole result set. */
    internal fun parseSearch(body: String, page: Int): PhotoPage {
        val root = Json.parseToJsonElement(body).jsonObject
        val items = root["results"]?.jsonArray.orEmpty().mapNotNull { parsePhoto(it.jsonObject) }
        val totalPages = root["total_pages"]?.jsonPrimitive?.intOrNull ?: 0
        return PhotoPage(
            items = items,
            page = page,
            totalResults = root["total"]?.jsonPrimitive?.intOrNull ?: items.size,
            hasMore = page < totalPages,
            source = PhotoSource.UNSPLASH,
        )
    }

    /**
     * `/photos`, `/topics/{id}/photos` and `/photos/random?count=` answer with a
     * bare array and no envelope at all, so "is there another page" can only be
     * inferred from a full page having come back.
     */
    internal fun parseList(body: String, page: Int, perPage: Int): PhotoPage {
        val items = Json.parseToJsonElement(body).jsonArray.mapNotNull { parsePhoto(it.jsonObject) }
        return PhotoPage(
            items = items,
            page = page,
            totalResults = items.size,
            hasMore = items.size >= perPage,
            source = PhotoSource.UNSPLASH,
        )
    }

    internal fun parsePhoto(obj: JsonObject): PhotoItem? {
        val urls = obj["urls"]?.jsonObject ?: return null
        val links = obj["links"]?.jsonObject
        val user = obj["user"]?.jsonObject
        val raw = urls.text("raw") ?: urls.text("full") ?: return null
        return PhotoItem(
            id = obj.text("id") ?: return null,
            source = PhotoSource.UNSPLASH,
            thumbUrl = urls.text("small") ?: urls.text("thumb") ?: raw,
            fullUrl = raw,
            pageUrl = links?.text("html").orEmpty(),
            photographer = user?.text("name") ?: user?.text("username").orEmpty(),
            photographerUrl = user?.get("links")?.jsonObject?.text("html").orEmpty(),
            width = obj["width"]?.jsonPrimitive?.intOrNull ?: 0,
            height = obj["height"]?.jsonPrimitive?.intOrNull ?: 0,
            avgColor = obj.text("color").orEmpty(),
            altText = obj.text("alt_description") ?: obj.text("description").orEmpty(),
            // imgix serves any size from the raw URL, so there is nothing to list.
            resizable = true,
            downloadLocation = links?.text("download_location").orEmpty(),
            blurHash = obj.text("blur_hash").orEmpty(),
        )
    }

    /**
     * `contentOrNull`, never `content`: Unsplash sends JSON `null` for
     * `description`, `alt_description`, `color` and `blur_hash` routinely, and
     * `content` turns that into the four-letter string "null", which reaches
     * the screen as a caption reading "null".
     */
    private fun JsonObject.text(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

    private const val MAX_PER_PAGE = 30
    private const val MAX_RANDOM_COUNT = 30
}
