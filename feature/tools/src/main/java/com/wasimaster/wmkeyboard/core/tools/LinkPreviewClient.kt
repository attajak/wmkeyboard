package com.wasimaster.wmkeyboard.core.tools

import com.wasimaster.wmkeyboard.core.clipboard.LinkPreview
import com.wasimaster.wmkeyboard.core.netlog.NetLog
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fetches Open Graph metadata for a copied link so the clipboard panel can
 * show a title instead of a bare URL.
 *
 * Only the first [HEAD_BYTES] of the document are read — everything we want
 * lives in `<head>` — and non-HTML responses are rejected outright, so a
 * copied link to a 200 MB video never pulls more than a few KB.
 *
 * Blocking; always call from an IO dispatcher.
 */
object LinkPreviewClient {

    private const val HEAD_BYTES = 64 * 1024
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0 Mobile Safari/537.36"

    /** Never throws: a failed fetch becomes a `failed = true` preview. */
    fun fetch(url: String, timeoutMs: Int = 8_000): LinkPreview =
        runCatching { parse(head(url, timeoutMs)) }
            .getOrElse { LinkPreview(failed = true) }
            .let { if (it.isEmpty) it.copy(failed = true) else it }

    private fun head(url: String, timeoutMs: Int): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        val netCall = NetLog.call(NetSource.LINK_PREVIEW, "GET", url)
        try {
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Accept", "text/html,application/xhtml+xml")
            // Ask for only the head of the document; servers that ignore Range
            // are handled by the read cap below.
            connection.setRequestProperty("Range", "bytes=0-${HEAD_BYTES - 1}")
            netCall.status = connection.responseCode
            val status = connection.responseCode
            if (status !in 200..299) throw IOException("HTTP $status")
            val type = connection.contentType.orEmpty()
            if (!type.contains("html", ignoreCase = true)) throw IOException("not HTML: $type")
            val charset = Regex("charset=([\\w-]+)", RegexOption.IGNORE_CASE)
                .find(type)?.groupValues?.get(1) ?: "UTF-8"
            val buffer = ByteArray(HEAD_BYTES)
            var read = 0
            netCall.countIn(connection.inputStream).use { input ->
                while (read < HEAD_BYTES) {
                    val n = input.read(buffer, read, HEAD_BYTES - read)
                    if (n < 0) break
                    read += n
                }
            }
            return String(buffer, 0, read, runCatching { charset(charset) }.getOrElse { Charsets.UTF_8 })
        } catch (t: Throwable) {
            netCall.fail(t)
            throw t
        } finally {
            connection.disconnect()
            netCall.end()
        }
    }

    internal fun parse(html: String): LinkPreview {
        val title = meta(html, "og:title")
            ?: meta(html, "twitter:title")
            ?: Regex("<title[^>]*>(.*?)</title>", RE_OPTIONS).find(html)?.groupValues?.get(1)
        val description = meta(html, "og:description")
            ?: meta(html, "twitter:description")
            ?: metaName(html, "description")
        return LinkPreview(
            title = clean(title).take(140),
            description = clean(description).take(240),
            siteName = clean(meta(html, "og:site_name")).take(60),
            imageUrl = clean(meta(html, "og:image") ?: meta(html, "twitter:image")).take(2048),
        )
    }

    private val RE_OPTIONS = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)

    /** `<meta property="og:x" content="…">` in either attribute order. */
    private fun meta(html: String, property: String): String? =
        attrContent(html, "property", property) ?: attrContent(html, "name", property)

    private fun metaName(html: String, name: String): String? = attrContent(html, "name", name)

    private fun attrContent(html: String, attr: String, value: String): String? {
        val quoted = Regex.escape(value)
        val contentFirst = Regex(
            """<meta[^>]+content=["']([^"']*)["'][^>]*$attr=["']$quoted["']""", RE_OPTIONS,
        )
        val attrFirst = Regex(
            """<meta[^>]+$attr=["']$quoted["'][^>]*content=["']([^"']*)["']""", RE_OPTIONS,
        )
        return (attrFirst.find(html) ?: contentFirst.find(html))?.groupValues?.get(1)
    }

    private fun clean(raw: String?): String = raw.orEmpty()
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
        .replace("&lt;", "<").replace("&gt;", ">")
        .replace("&nbsp;", " ").replace("&amp;", "&")
        .replace(Regex("\\s+"), " ")
        .trim()
}
