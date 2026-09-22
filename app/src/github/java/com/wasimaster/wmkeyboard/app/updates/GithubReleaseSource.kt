package com.wasimaster.wmkeyboard.app.updates

import com.wasimaster.wmkeyboard.core.netlog.NetSource
import com.wasimaster.wmkeyboard.core.netlog.NetLog
import com.wasimaster.wmkeyboard.BuildConfig
import com.wasimaster.wmkeyboard.core.debug.DebugLog
import com.wasimaster.wmkeyboard.core.tools.ToolHttp
import com.wasimaster.wmkeyboard.core.tools.ToolHttpException
import java.io.File

/**
 * The release list, over the GitHub API.
 *
 * Unauthenticated, which means sixty requests an hour for every device sharing
 * an address. Carrier NAT can put a great many installs behind one of those,
 * so two things here are load-bearing rather than tidy: the caller only asks
 * every six hours, and every request carries the last ETag, because a 304
 * costs no quota at all.
 */
internal class GithubReleaseSource(
    private val requestList: (Map<String, String>) -> Pair<String, Map<String, String>> =
        { headers ->
            ToolHttp.getWithHeaders(
                url = GithubReleases.LIST_URL,
                headers = headers,
                wantHeaders = WANTED_HEADERS,
                source = NetSource.UPDATES,
                route = NetLog.pathOf(GithubReleases.LIST_URL),
            ).let { it.body to it.headers }
        },
    private val now: () -> Long = System::currentTimeMillis,
) : ReleaseFetcher {

    override fun fetch(etag: String?): FetchResult {
        val headers = buildMap {
            put("Accept", "application/vnd.github+json")
            put("X-GitHub-Api-Version", "2022-11-28")
            // GitHub asks every client to identify itself, and ToolHttp's
            // browser-shaped default is not an identification. Caller headers
            // are applied after its own, so this one wins.
            put("User-Agent", USER_AGENT)
            etag?.let { put("If-None-Match", it) }
        }
        return runCatching { requestList(headers) }.fold(
            onSuccess = { (body, responseHeaders) ->
                FetchResult.Fresh(body, responseHeaders.entryIgnoringCase("ETag"))
            },
            onFailure = { error -> failure(error) },
        )
    }

    private fun failure(error: Throwable): FetchResult {
        val http = error as? ToolHttpException ?: return FetchResult.Failed
        if (http.status == NOT_MODIFIED) return FetchResult.NotModified
        val limited = http.status == FORBIDDEN || http.status == TOO_MANY_REQUESTS
        if (!limited) {
            DebugLog.w(TAG, "release list failed: HTTP ${http.status}")
            return FetchResult.Failed
        }
        // 403 is also what a plain refusal looks like, so the headers are what
        // tell a rate limit from one: a remaining count of zero, or an explicit
        // wait. Without either, this was some other kind of no.
        val remaining = http.headers.entryIgnoringCase("X-RateLimit-Remaining")?.toLongOrNull()
        val retryAfter = http.headers.entryIgnoringCase("Retry-After")?.toLongOrNull()
        val reset = http.headers.entryIgnoringCase("X-RateLimit-Reset")?.toLongOrNull()
        return when {
            retryAfter != null -> FetchResult.RateLimited(now() + retryAfter * MILLIS_PER_SECOND)
            remaining == 0L && reset != null -> FetchResult.RateLimited(reset * MILLIS_PER_SECOND)
            remaining == 0L -> FetchResult.RateLimited(now() + DEFAULT_RATE_LIMIT_WAIT)
            else -> {
                val said = GithubReleaseCodec.errorMessage(http.apiMessage).orEmpty()
                DebugLog.w(TAG, "release list refused with HTTP ${http.status}: $said")
                FetchResult.Failed
            }
        }
    }

    internal companion object {
        /**
         * Names this app and points at its source, which is what GitHub asks
         * of an unauthenticated client so it has someone to contact.
         */
        val USER_AGENT = "WMKeyboard/${BuildConfig.VERSION_NAME} " +
            "(+https://github.com/${GithubReleases.REPO})"

        private val WANTED_HEADERS =
            setOf("ETag", "X-RateLimit-Remaining", "X-RateLimit-Reset", "Retry-After")

        private const val NOT_MODIFIED = 304
        private const val FORBIDDEN = 403
        private const val TOO_MANY_REQUESTS = 429
        private const val MILLIS_PER_SECOND = 1000L
        private const val DEFAULT_RATE_LIMIT_WAIT = 60L * 60 * 1000
        private const val TAG = "AppUpdates"

        /**
         * HTTP header names are case-insensitive and this map is built from
         * whatever case the server used, so a lookup has to be too.
         */
        fun Map<String, String>.entryIgnoringCase(name: String): String? =
            entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
    }
}

/** The last release list that arrived with a body, as a file beside the staged APK. */
internal class ReleaseFileCache(private val file: File) : ReleaseCache {

    override fun read(): String? = runCatching {
        file.takeIf { it.isFile && it.length() <= GithubReleases.MAX_LIST_BYTES }?.readText()
    }.getOrNull()

    override fun write(body: String) {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(body)
        }.onFailure { DebugLog.w("AppUpdates", "could not cache the release list") }
    }

    override fun exists(): Boolean = file.isFile

    override fun clear() {
        file.delete()
    }
}

/**
 * The release notes for one version, or null.
 *
 * Not the release body, which wraps the notes in a download grid and a pile of
 * HTML. This is the markdown file that body is built from, fetched from the
 * tag it belongs to, so it is the text that shipped with that version.
 *
 * The short store changelog is the fallback. A release with neither is
 * ordinary rather than a fault: the files are written before the tag, and a
 * release can be cut before one of them lands.
 */
internal fun fetchReleaseNotes(candidate: UpdateCandidate): String? =
    fetchNotesFrom(candidate.releaseNotesUrl) ?: fetchNotesFrom(candidate.changelogUrl)

private fun fetchNotesFrom(url: String): String? = runCatching {
    ToolHttp.get(url, source = NetSource.UPDATES, route = NetLog.pathOf(url), background = false)
        .trim().take(GithubReleases.MAX_NOTES_CHARS)
}.getOrNull()?.takeIf { it.isNotBlank() }

/** The checksums file a release attaches, for a release whose asset has no digest. */
internal fun fetchChecksum(candidate: UpdateCandidate): String? = runCatching {
    val url = candidate.url.substringBeforeLast('/') + "/" + GithubReleases.CHECKSUMS_ASSET
    ReleaseAssets.sha256From(
        ToolHttp.get(url, source = NetSource.UPDATES, route = NetLog.pathOf(url), background = false),
        candidate.assetName,
    )
}.getOrNull()
