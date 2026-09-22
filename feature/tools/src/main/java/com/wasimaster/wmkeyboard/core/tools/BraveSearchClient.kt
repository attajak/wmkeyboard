package com.wasimaster.wmkeyboard.core.tools

import com.wasimaster.wmkeyboard.core.netlog.NetSource
import com.wasimaster.wmkeyboard.core.endpoints.ServiceEndpoint
import com.wasimaster.wmkeyboard.core.endpoints.ServiceEndpoints
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Brave Search API client — the whole-web alternative for the web and
 * image search tools now that Google Programmable Search no longer offers
 * "search the entire web" to new engines (since 2026-01-20; existing
 * whole-web engines run until 2027-01-01). Key goes in the
 * X-Subscription-Token header; Brave's plan gives a monthly free credit
 * but requires attribution, which the panels show.
 */
object BraveSearchClient {

    /** Blocking; call on an IO dispatcher. Throws on failure. */
    fun webSearch(query: String, apiKey: String, count: Int, safe: Boolean): List<WebResult> {
        val url = ServiceEndpoints.base(ServiceEndpoint.BRAVE_SEARCH) + "/res/v1/web/search" +
            "?q=${ToolHttp.encode(query.trim())}" +
            "&count=${count.coerceIn(1, 20)}" +
            "&safesearch=${if (safe) "moderate" else "off"}"
        return parseWeb(ToolHttp.get(url, headers = headers(apiKey), source = NetSource.WEB_SEARCH, route = "/res/v1/web/search"))
    }

    /** Blocking; call on an IO dispatcher. Throws on failure. */
    fun imageSearch(query: String, apiKey: String, count: Int, safe: Boolean): List<ImageResult> {
        // Image search only knows strict/off — no "moderate".
        val url = ServiceEndpoints.base(ServiceEndpoint.BRAVE_SEARCH) + "/res/v1/images/search" +
            "?q=${ToolHttp.encode(query.trim())}" +
            "&count=${count.coerceIn(1, 20)}" +
            "&safesearch=${if (safe) "strict" else "off"}"
        return parseImages(
            ToolHttp.get(url, headers = headers(apiKey), source = NetSource.IMAGE_SEARCH, route = "/res/v1/images/search"),
        )
    }

    private fun headers(apiKey: String) = mapOf(
        "X-Subscription-Token" to apiKey,
        "Accept" to "application/json",
    )

    internal fun parseWeb(body: String): List<WebResult> {
        val results = Json.parseToJsonElement(body).jsonObject["web"]?.jsonObject
            ?.get("results")?.jsonArray ?: return emptyList()
        return results.mapNotNull { element ->
            val item = element.jsonObject
            val url = item["url"]?.jsonPrimitive?.content ?: return@mapNotNull null
            WebResult(
                title = item["title"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                snippet = item["description"]?.jsonPrimitive?.content.orEmpty()
                    // Brave descriptions carry highlight markup.
                    .replace("<strong>", "").replace("</strong>", ""),
                url = url,
                displayUrl = item["meta_url"]?.jsonObject?.get("netloc")?.jsonPrimitive?.content
                    ?: url.substringAfter("://").substringBefore('/'),
            )
        }
    }

    internal fun parseImages(body: String): List<ImageResult> {
        val results = Json.parseToJsonElement(body).jsonObject["results"]?.jsonArray
            ?: return emptyList()
        return results.mapNotNull { element ->
            val item = element.jsonObject
            val full = item["properties"]?.jsonObject?.get("url")?.jsonPrimitive?.content
                ?: return@mapNotNull null
            ImageResult(
                title = item["title"]?.jsonPrimitive?.content.orEmpty(),
                thumbUrl = item["thumbnail"]?.jsonObject?.get("src")?.jsonPrimitive?.content ?: full,
                imageUrl = full,
                mime = mimeFromUrl(full),
                contextUrl = item["url"]?.jsonPrimitive?.content.orEmpty(),
            )
        }
    }

    /** Brave doesn't report MIME; go by the file extension. */
    internal fun mimeFromUrl(url: String): String {
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        return when {
            path.endsWith(".png") -> "image/png"
            path.endsWith(".gif") -> "image/gif"
            path.endsWith(".webp") -> "image/webp"
            else -> "image/jpeg"
        }
    }
}
