package com.wasimaster.wmkeyboard.core.netlog

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One row of the network activity log: one request, or a burst of identical
 * ones merged into a row (see [NetLogStore.add]).
 *
 * What is here is the whole privacy story of the log, so nothing may be added
 * that would let a reader reconstruct what the user typed:
 *
 *  * [host] and [route] are an *address*, never a query string. The route is
 *    what the call site says it is, not a cut of the URL, because some paths
 *    carry what the user typed (a looked-up word is a path segment on
 *    Wikipedia). No route given means the row shows the host alone.
 *  * No headers (API keys travel there), no bodies, no parameters.
 *  * [error] is an exception class name, never its message, which can quote
 *    the URL back.
 *
 * Sizes are the data carried, not the wire: headers are not counted, and a
 * compressed response counts what it decompressed to where the platform
 * decompresses before we see it.
 */
@Serializable
data class NetEntry(
    /** Unique within one log; a merged row keeps the id of its first request. */
    val id: Long,
    @SerialName("t0") val firstMillis: Long,
    @SerialName("t1") val lastMillis: Long = firstMillis,
    @SerialName("n") val count: Int = 1,
    @SerialName("src") val sourceId: String,
    @SerialName("m") val method: String,
    @SerialName("h") val host: String,
    /** -1 when the address named no port (the scheme's default). */
    @SerialName("p") val port: Int = -1,
    @SerialName("s") val scheme: String = "https",
    @SerialName("r") val route: String? = null,
    /** HTTP status, or 0 when the request failed before one arrived. */
    @SerialName("st") val status: Int = 0,
    @SerialName("e") val error: String? = null,
    @SerialName("up") val bytesOut: Long = 0,
    @SerialName("dn") val bytesIn: Long = 0,
    /** Summed over [count]; divide for the mean. */
    @SerialName("ms") val durationMs: Long = 0,
    @SerialName("inc") val incognito: Boolean = false,
    @SerialName("bg") val background: Boolean = false,
) {
    val source: NetSource get() = NetSource.of(sourceId)

    /** Failed outright or answered with a status outside 200..399. */
    val failed: Boolean get() = error != null || status !in 200..399

    val meanDurationMs: Long get() = if (count > 0) durationMs / count else durationMs

    /**
     * What makes two requests "the same" for burst merging. The status is kept
     * as a class (2xx, 4xx…) so a run of thumbnails that are all fine merges,
     * while the one that failed stays its own row.
     */
    internal val mergeKey: String
        get() = buildString {
            append(sourceId).append('|').append(method).append('|')
            append(host).append(':').append(port).append('|').append(route.orEmpty()).append('|')
            append(if (error != null) "e:$error" else (status / 100).toString())
            append('|').append(incognito).append('|').append(background)
        }
}
