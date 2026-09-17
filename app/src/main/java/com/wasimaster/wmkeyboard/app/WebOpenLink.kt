package com.wasimaster.wmkeyboard.app

import android.net.Uri
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * The documentation site's `/open/` page, as a link this app answers itself.
 *
 * `wmkeyboard://` is the real address of a screen, but plenty of places refuse
 * to make a custom scheme tappable at all: most chat apps, most Markdown
 * renderers, a QR reader that hands its result to a browser. So the site
 * carries a page that holds one of those addresses and offers to open it, and
 * this app claims that page's URL as an App Link. Where the claim is verified
 * the page never draws — Android hands the link straight here — and where it
 * is not, the page loads and offers the button. Same address, both ways.
 *
 * | Web address | Same as |
 * |---|---|
 * | `…/open/#wmkeyboard://settings/themes` | `wmkeyboard://settings/themes` |
 * | `…/open/?link=wmkeyboard%3A%2F%2Fsettings%2Fthemes` | the same |
 * | `…/open/?route=themes` | `wmkeyboard://settings/themes` |
 * | `…/open/?route=typing&setting=typing_autocorrect_title` | that screen, that row |
 * | `…/open/?setting=typing_autocorrect_title` | `wmkeyboard://setting/typing_autocorrect_title` |
 * | `…/open/?repo=<url>` | `wmkeyboard://repo?url=<url>` |
 * | `…/open/?repo=<url>&id=<addon>` | `wmkeyboard://addon?repo=<url>&id=<addon>` |
 * | `…/open/?addons` | `wmkeyboard://addons` |
 *
 * This only rewrites; it grants nothing. What comes out is a `wmkeyboard://`
 * string that goes through [SettingsDeepLink] and [AddonDeepLink] exactly as a
 * typed one does, so the route allowlist and the "a link only navigates" rule
 * cover a web address too. An address this cannot read returns null and the
 * link opens nothing, rather than guessing at a screen.
 *
 * Kept in step with `requestedLink()` in `docs/src/pages/open/index.astro`,
 * which reads the same address in the browser. Parsing works on [URI] rather
 * than [Uri] so it is testable off device.
 */
object WebOpenLink {

    /** The one path claimed on the docs host. Mirrored in AndroidManifest.xml. */
    const val PATH = "/open"

    /** Host of [DOCS_URL]; the manifest filter names it literally. */
    private val HOST: String = runCatching { URI(DOCS_URL).host }.getOrNull().orEmpty()

    /** Convenience for the intent's data. */
    fun appLink(uri: Uri?): String? = appLink(uri?.toString())

    /**
     * The `wmkeyboard://` address [url] stands for, or null when it is not one
     * of our `/open/` addresses or names nothing.
     */
    fun appLink(url: String?): String? {
        val text = url?.trim().orEmpty()
        if (text.isEmpty() || HOST.isEmpty()) return null
        val uri = runCatching { URI(text) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true)) return null
        if (!uri.host.equals(HOST, ignoreCase = true)) return null
        // "/open" and "/open/" and nothing else. The manifest claims exactly
        // these two, so a deeper path is not ours to answer.
        val path = uri.rawPath.orEmpty().trimEnd('/')
        if (!path.equals(PATH, ignoreCase = true)) return null

        // The address rides in the fragment, which never leaves the browser on
        // the web side and rides along in the intent on this one. [URI] hands
        // it back percent-decoded, which is what the page's own reader does.
        uri.fragment?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }

        val query = text.substringAfter('?', "").substringBefore('#')
        query.param("link")?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }

        // A route may be named and empty ("the home list"), so its presence is
        // what counts, not its content. A setting on its own names no screen.
        val route = query.param("route")
        val setting = query.param("setting")?.takeIf { it.isNotEmpty() }
        if (route != null || setting != null) {
            if (route.isNullOrEmpty() && setting != null) {
                return "${SettingsDeepLink.SCHEME}://${SettingsDeepLink.SETTING_HOST}/$setting"
            }
            return buildString {
                append(SettingsDeepLink.SCHEME).append("://").append(SettingsDeepLink.SCREEN_HOST)
                if (!route.isNullOrEmpty()) append('/').append(route.trim('/'))
                // The name is [a-z0-9_] or it is refused downstream, so it
                // needs no escaping, and escaping it would only risk a '+'
                // where the reader expects a space.
                if (setting != null) append('?').append(SettingsDeepLink.SETTING_PARAM).append('=').append(setting)
            }
        }

        val repo = query.param("repo")?.takeIf { it.isNotEmpty() }
        val id = query.param("id")?.takeIf { it.isNotEmpty() }
        if (repo != null) {
            val url = URLEncoder.encode(repo, "UTF-8")
            return when (id) {
                null -> "${AddonDeepLink.SCHEME}://repo?url=$url"
                else -> "${AddonDeepLink.SCHEME}://addon?repo=$url&id=${URLEncoder.encode(id, "UTF-8")}"
            }
        }
        if (query.has("addons")) return "${AddonDeepLink.SCHEME}://addons"
        return null
    }

    /**
     * One query parameter out of a raw `a=1&b=2` string, percent-decoded, or
     * null when the name is absent. A name present with no value reads as
     * empty rather than absent, which is the difference between "the home
     * list" and "no screen named".
     */
    private fun String.param(name: String): String? {
        for (pair in split('&')) {
            if (pair.isEmpty()) continue
            val key = pair.substringBefore('=')
            if (key != name) continue
            val value = pair.substringAfter('=', "")
            return runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault("")
        }
        return null
    }

    /** Whether a bare `?addons` style flag is present. */
    private fun String.has(name: String): Boolean =
        split('&').any { it.substringBefore('=') == name }
}
