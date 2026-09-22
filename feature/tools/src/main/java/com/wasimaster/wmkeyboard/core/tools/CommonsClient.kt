package com.wasimaster.wmkeyboard.core.tools

import com.wasimaster.wmkeyboard.core.netlog.NetLog
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Wikimedia Commons as a media source, for the F-Droid build.
 *
 * Commons runs MediaWiki, which is GPL and self-hostable, and its contents are
 * freely licensed — so it can stand where Unsplash, Pexels, GIPHY and KLIPY
 * stand on the other channels. Those four all want an API key that an F-Droid
 * build has no way to carry anyway, so on that channel this replaces them
 * rather than joining them.
 *
 * Two honest limitations, both visible to the user rather than hidden:
 *  - the GIF corpus is small and mostly educational, so a search for a reaction
 *    GIF finds diagrams. The panel names Commons for that reason.
 *  - there is no colour search and no editorial feed, so a blank query browses
 *    recent quality images instead of a curated one.
 */
object CommonsClient {

    /** Where the media comes from when the user has named nothing else. */
    const val DEFAULT_API = "https://commons.wikimedia.org/w/api.php"

    /** Grid thumbnails; wide enough for a two-column picker on a tall phone. */
    private const val GRID_THUMB_WIDTH = 480

    /** Previews in the GIF picker, which is a denser grid than the photo one. */
    private const val GIF_THUMB_WIDTH = 320

    /**
     * Widths offered to [PhotoSizing.downloadUrl]. It wants the smallest that
     * covers the target, and the targets are 1080 and 2160 wide, so the ladder
     * has to bracket both. Widths at or above the original are dropped: asking
     * MediaWiki to scale a file up answers 404, not a bigger picture.
     */
    private val VARIANT_WIDTHS = intArrayOf(640, 1280, 1920, 2560)

    /** No average colour comes back, and the tile needs something under it. */
    private const val PLACEHOLDER_COLOR = "#3F3F3F"

    /**
     * Blocking; call on an IO dispatcher. Throws on failure.
     *
     * Paging is by offset rather than page number because that is what the
     * search generator takes; [PhotoQuery.page] is 1-based, as the other
     * providers report it.
     */
    fun searchPhotos(query: PhotoQuery, endpoint: String = ""): HttpResponse {
        val offset = (query.page - 1).coerceAtLeast(0) * query.perPage
        return ToolHttp.getWithHeaders(
            searchUrl(
                apiUrl = apiUrl(endpoint),
                terms = photoTerms(query.text),
                limit = query.perPage,
                offset = offset,
                thumbWidth = GRID_THUMB_WIDTH,
            ),
            source = NetSource.PHOTOS,
            route = NetLog.pathOf(apiUrl(endpoint)),
        )
    }

    /**
     * Split from the request so the photo client can run it through the same
     * `timed` wrapper as the other two providers — that is what keeps the rate
     * limiter's bookkeeping honest for this source too.
     */
    fun parsePage(body: String, query: PhotoQuery): PhotoPage {
        val items = parsePhotos(body).let { photos ->
            // Commons cannot filter on shape, so the shape filter is applied
            // here. Doing it after the fetch means a page can come back short;
            // that is better than showing portraits in a landscape picker.
            if (query.orientation == PhotoOrientation.ANY) photos
            else photos.filter { matchesOrientation(it, query.orientation) }
        }
        return PhotoPage(
            items = items,
            page = query.page,
            // The generator reports no total. Callers only read hasMore.
            totalResults = 0,
            hasMore = hasContinue(body),
            source = PhotoSource.COMMONS,
        )
    }

    /** Blocking; call on an IO dispatcher. Throws on failure. */
    fun searchGifs(query: String, limit: Int = 24, endpoint: String = ""): List<GifItem> {
        val url = searchUrl(
            apiUrl = apiUrl(endpoint),
            terms = gifTerms(query),
            limit = limit,
            offset = 0,
            thumbWidth = GIF_THUMB_WIDTH,
        )
        return parseGifs(ToolHttp.get(url, source = NetSource.GIF, route = NetLog.pathOf(apiUrl(endpoint))))
    }

    /**
     * The `api.php` to talk to, from whatever the user configured.
     *
     * Blank means Commons itself. Anything else is a MediaWiki they run or
     * trust: a full `…/api.php` is taken as given, and a bare host gets the
     * default `/w/api.php` layout appended — which is where MediaWiki puts it
     * unless the operator moved it, and an operator who moved it knows to paste
     * the whole path.
     */
    internal fun apiUrl(endpoint: String): String {
        val raw = endpoint.trim()
        if (raw.isEmpty()) return DEFAULT_API
        val withScheme = when {
            raw.startsWith("http://", ignoreCase = true) -> raw
            raw.startsWith("https://", ignoreCase = true) -> raw
            !raw.contains("://") -> "https://$raw"
            else -> return DEFAULT_API
        }
        val trimmed = withScheme.trimEnd('/')
        return if (trimmed.endsWith("api.php", ignoreCase = true)) trimmed else "$trimmed/w/api.php"
    }

    /**
     * `filemime:` restricts the generator to one type, which is what keeps a
     * GIF search from answering with JPEGs. A blank query has to search for
     * *something*, so it browses featured pictures, which is the closest thing
     * Commons has to a curated feed.
     */
    internal fun photoTerms(text: String): String {
        val trimmed = text.trim()
        return if (trimmed.isEmpty()) {
            "filemime:image/jpeg incategory:Featured_pictures_on_Wikimedia_Commons"
        } else {
            "$trimmed filemime:image/jpeg"
        }
    }

    internal fun gifTerms(text: String): String {
        val trimmed = text.trim()
        return if (trimmed.isEmpty()) "filemime:image/gif" else "$trimmed filemime:image/gif"
    }

    internal fun searchUrl(
        apiUrl: String,
        terms: String,
        limit: Int,
        offset: Int,
        thumbWidth: Int,
    ): String =
        buildString {
            append(apiUrl)
            append("?action=query&format=json&formatversion=2")
            append("&generator=search")
            append("&gsrsearch=${ToolHttp.encode(terms)}")
            // Namespace 6 is File:. Without it the generator returns articles,
            // which carry no imageinfo and parse to nothing at all.
            append("&gsrnamespace=6")
            append("&gsrlimit=${limit.coerceIn(1, 50)}")
            if (offset > 0) append("&gsroffset=$offset")
            append("&prop=imageinfo")
            append("&iiprop=url%7Cmime%7Csize%7Cextmetadata")
            append("&iiurlwidth=$thumbWidth")
        }

    /** Another page exists exactly when the API offers somewhere to continue. */
    internal fun hasContinue(body: String): Boolean =
        Json.parseToJsonElement(body).jsonObject["continue"] != null

    internal fun parsePhotos(body: String): List<PhotoItem> = pages(body).mapNotNull { page ->
        val info = page["imageinfo"]?.jsonArray?.firstOrNull()?.jsonObject ?: return@mapNotNull null
        val original = info["url"]?.jsonPrimitive?.content ?: return@mapNotNull null
        val thumb = info["thumburl"]?.jsonPrimitive?.content ?: original
        val width = info["width"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
        val height = info["height"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
        val meta = info["extmetadata"]?.jsonObject
        PhotoItem(
            id = page["pageid"]?.jsonPrimitive?.content ?: return@mapNotNull null,
            source = PhotoSource.COMMONS,
            thumbUrl = thumb,
            fullUrl = original,
            pageUrl = info["descriptionurl"]?.jsonPrimitive?.content.orEmpty(),
            photographer = meta.text("Artist").ifBlank { meta.text("Credit") },
            // The artist field is a link as often as a name; when it is, the
            // link is the best "more by this person" we have.
            photographerUrl = firstHref(meta.raw("Artist")),
            width = width,
            height = height,
            avgColor = PLACEHOLDER_COLOR,
            altText = meta.text("ImageDescription").ifBlank { titleOf(page) },
            // MediaWiki resizes, but only to a width baked into the path, so
            // the fixed-variants route is the honest description of it.
            resizable = false,
            variants = variantsOf(thumb, original, width, height),
        )
    }

    internal fun parseGifs(body: String): List<GifItem> = pages(body).mapNotNull { page ->
        val info = page["imageinfo"]?.jsonArray?.firstOrNull()?.jsonObject ?: return@mapNotNull null
        val original = info["url"]?.jsonPrimitive?.content ?: return@mapNotNull null
        val width = info["width"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
        val height = info["height"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
        GifItem(
            id = page["pageid"]?.jsonPrimitive?.content ?: return@mapNotNull null,
            // The thumbnail, which MediaWiki renders as a still frame once a
            // GIF is large enough. The grid can live with a still preview; the
            // insert cannot, which is why fullUrl below is the original file.
            previewUrl = info["thumburl"]?.jsonPrimitive?.content ?: original,
            fullUrl = original,
            mime = info["mime"]?.jsonPrimitive?.content ?: "image/gif",
            aspectRatio = if (width > 0 && height > 0) width.toFloat() / height else 1f,
            source = GifSource.COMMONS,
            title = titleOf(page),
        )
    }

    /**
     * The pages the generator matched, in the order it ranked them. The JSON
     * carries `index` for exactly this reason: the array order is not the
     * relevance order, and a picker that ignores it looks arbitrary.
     */
    private fun pages(body: String) =
        (Json.parseToJsonElement(body).jsonObject["query"]?.jsonObject?.get("pages")?.jsonArray)
            .orEmpty()
            .map { it.jsonObject }
            .sortedBy { it["index"]?.jsonPrimitive?.content?.toIntOrNull() ?: Int.MAX_VALUE }

    /** "File:Sunset in the Himalayas.jpg" -> "Sunset in the Himalayas". */
    internal fun titleOf(page: kotlinx.serialization.json.JsonObject): String =
        page["title"]?.jsonPrimitive?.content.orEmpty()
            .removePrefix("File:")
            .substringBeforeLast('.')

    /**
     * MediaWiki thumbnail URLs name their width in the last path segment
     * (`…/File.jpg/480px-File.jpg`), so a different width is a substitution
     * rather than another round trip. Only widths below the original are
     * offered — MediaWiki will not upscale, and asking answers 404.
     */
    internal fun variantsOf(
        thumbUrl: String,
        originalUrl: String,
        width: Int,
        height: Int,
    ): List<PhotoVariant> {
        if (width <= 0 || height <= 0) return emptyList()
        val ratio = height.toFloat() / width
        val scaled = VARIANT_WIDTHS
            .filter { it < width }
            .mapNotNull { target ->
                thumbAt(thumbUrl, target)?.let {
                    PhotoVariant(it, target, (target * ratio).toInt().coerceAtLeast(1))
                }
            }
        // The original always belongs on the list: for a small file it may be
        // the only thing that covers the target at all. It is taken as given
        // rather than derived from the thumbnail, because the two are served
        // from different hosts (upload. and thumb.wikimedia.org) and rebuilding
        // one from the other produces a URL that 404s.
        return scaled + PhotoVariant(originalUrl, width, height)
    }

    /** The same thumbnail at another width, or null when the URL is not one. */
    internal fun thumbAt(thumbUrl: String, width: Int): String? {
        val slash = thumbUrl.lastIndexOf('/')
        if (slash < 0) return null
        val file = thumbUrl.substring(slash + 1)
        val marker = file.indexOf("px-")
        if (marker <= 0) return null
        if (file.take(marker).any { !it.isDigit() }) return null
        return thumbUrl.take(slash + 1) + width + file.substring(marker)
    }

    internal fun matchesOrientation(item: PhotoItem, want: PhotoOrientation): Boolean {
        if (item.width <= 0 || item.height <= 0) return true
        val ratio = item.width.toFloat() / item.height
        return when (want) {
            PhotoOrientation.ANY -> true
            PhotoOrientation.LANDSCAPE -> ratio > 1.15f
            PhotoOrientation.PORTRAIT -> ratio < 0.87f
            PhotoOrientation.SQUARE -> ratio in 0.87f..1.15f
        }
    }

    /** `extmetadata` values are HTML; the picker wants words. */
    internal fun stripHtml(value: String): String =
        value.replace(TAG, " ")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#039;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace(WHITESPACE, " ")
            .trim()

    /** The first `href` in a fragment of `extmetadata` HTML, or "". */
    internal fun firstHref(value: String): String =
        HREF.find(value)?.groupValues?.getOrNull(1).orEmpty()

    private fun kotlinx.serialization.json.JsonObject?.raw(field: String): String =
        this?.get(field)?.jsonObject?.get("value")?.jsonPrimitive?.content.orEmpty()

    private fun kotlinx.serialization.json.JsonObject?.text(field: String): String =
        stripHtml(raw(field))

    private val TAG = Regex("<[^>]*>")
    private val WHITESPACE = Regex("\\s+")
    private val HREF = Regex("""href\s*=\s*["']([^"']+)["']""")
}
