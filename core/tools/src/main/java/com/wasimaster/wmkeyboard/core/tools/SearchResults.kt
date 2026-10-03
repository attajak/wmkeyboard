package com.wasimaster.wmkeyboard.core.tools

/** One web hit from the search tool's backend. */
data class WebResult(
    val title: String,
    val snippet: String,
    val url: String,
    /** Short host shown under the title ("en.wikipedia.org"). */
    val displayUrl: String,
)

/**
 * One page of web results, and the short answer the backend wrote for the
 * query when it writes one (#470): Tavily's `answer`, a SearXNG instance's
 * `answers`. Brave gives none on the plain search API.
 */
data class WebSearchPage(
    val results: List<WebResult>,
    val answer: String? = null,
)

/** One image hit from the image search tool. */
data class ImageResult(
    val title: String,
    /** Small thumbnail rendered in the picker grid. */
    val thumbUrl: String,
    /** Full image downloaded and inserted on tap. */
    val imageUrl: String,
    val mime: String,
    /** Page the image came from, for the link-insert action. */
    val contextUrl: String,
)
