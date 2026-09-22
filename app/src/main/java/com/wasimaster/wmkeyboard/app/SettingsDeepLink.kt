package com.wasimaster.wmkeyboard.app

import android.net.Uri
import java.net.URI
import java.net.URLDecoder

/**
 * `wmkeyboard://` links into the settings app, so a launcher shortcut, a
 * documentation page, a support reply or another app on the device can open
 * one exact screen — or one exact switch — instead of the home list.
 *
 * | Link | Lands on |
 * |---|---|
 * | `wmkeyboard://settings` | the settings home list |
 * | `wmkeyboard://settings/<route>` | that screen, e.g. `themes`, `typing/corrections` |
 * | `wmkeyboard://settings/<route>/<id>` | a screen that names one of the user's own things, e.g. `language/en_US`, `tool/CLIPBOARD` |
 * | `wmkeyboard://settings/<route>?setting=<name>` | that screen, with one row, or a group by its heading, scrolled to and flashed |
 * | `wmkeyboard://setting/<name>` | the same row, on whichever screen holds it |
 *
 * Either form may carry `since=<version>`, the first release that has what the
 * link opens (`…/typing?setting=typing_auto_close_brackets_title&since=0.5.12`).
 * It changes nothing about where a link goes. It is there for the copy of the
 * app that is too old to go anywhere: that copy cannot know about a screen
 * added after it was built, but it can read a version number and say "update
 * to 0.5.12" instead of opening nothing. See [unknown]. The documentation
 * site's link builder and `/open/` page add it; a copy of the app from before
 * this parameter existed ignores it, as it ignores every parameter it does not
 * know.
 *
 * [SettingsRoutes] is the allowlist for the first half and the settings search
 * index is the allowlist for the second, which is why this parses rather than
 * trusting what it was handed. `NavController.navigate` throws on a route it
 * does not know, and the throw would land in an activity that has just been
 * resumed by a stranger's intent, so nothing reaches it unchecked. A link that
 * names no screen navigates nowhere and nothing else happens.
 *
 * **A link only ever navigates.** It cannot flip a switch, press a button, or
 * change a value: `setting=` scrolls a row into view and pulses it once, and
 * the user is the one who then touches it. The screens that show what the
 * keyboard has learned are guarded by the fingerprint lock, which gates by
 * route ([com.wasimaster.wmkeyboard.app.lock.AppLockTargets.screen]) and so
 * covers a link exactly as it covers a tap.
 *
 * Parsing works on the raw string rather than [Uri] so it is testable off
 * device; [Uri] is a framework class with no behaviour in a JVM unit test.
 * Raw path and raw query throughout, never the decoded ones: an argument
 * segment is handed to `navigate()` still encoded, because that is the form it
 * decodes from, and decoding here would turn a `%2F` inside a repository URL
 * into a path separator.
 */
object SettingsDeepLink {

    const val SCHEME = "wmkeyboard"

    /** The host that marks a link to a screen. */
    const val SCREEN_HOST = "settings"

    /** The host that marks a link to one row, wherever it lives. */
    const val SETTING_HOST = "setting"

    /** The query parameter naming one row on the screen the link opens. */
    const val SETTING_PARAM = "setting"

    /** The query parameter naming the first version that has what the link opens. */
    const val SINCE_PARAM = "since"

    /**
     * Where a link points.
     *
     * [route] is a real NavHost route, ready for `navigate()`, or empty when
     * the link named only a [setting] and the screen holding it has yet to be
     * looked up in the search index.
     *
     * [setting] is the *resource name* of a row's title — `typing_autocorrect_title`,
     * not the words "Autocorrect". A name survives translation and an app
     * update; the drawn text survives neither, and the numeric id the compiler
     * assigns survives even less.
     *
     * [since] is the link's `since=` version, or empty. Only read when [setting]
     * turns out to be a row this build does not have.
     */
    data class Target(val route: String, val setting: String = "", val since: String = "")

    /**
     * A settings link this build cannot follow: its screen is not one this
     * build has, or its row name is not a name at all. [since] is the version
     * the link says it needs, or empty when it does not say.
     */
    data class Unknown(val since: String)

    /** Convenience for the intent's data. */
    fun parse(uri: Uri?): Target? = parse(uri?.toString())

    /**
     * The screen and row [link] names, or null when it isn't one of ours or
     * names no screen at all.
     */
    fun parse(link: String?): Target? {
        val (host, path, query) = split(link) ?: return null
        val since = version(query.param(SINCE_PARAM)).orEmpty()

        return when {
            host.equals(SETTING_HOST, ignoreCase = true) -> {
                // The row is the whole address here; the screen it sits on is
                // whatever the index says, so the route is filled in later.
                settingName(path.trim('/'))?.let { Target(route = "", setting = it, since = since) }
            }

            host.equals(SCREEN_HOST, ignoreCase = true) -> {
                val setting = settingName(query.param(SETTING_PARAM)).orEmpty()
                val body = path.trim('/')
                // A bare "wmkeyboard://settings" is the app's front door, and
                // "wmkeyboard://settings?setting=…" is the same address as the
                // setting host: no screen named, so the index names one.
                if (body.isEmpty()) {
                    when {
                        setting.isNotEmpty() -> Target(route = "", setting = setting, since = since)
                        else -> Target(route = "home")
                    }
                } else {
                    SettingsRoutes.resolve(body)?.let { Target(it, setting, since) }
                }
            }

            else -> null
        }
    }

    /**
     * What to tell the user about a settings link [parse] refused, or null
     * when [link] is not a settings link at all or [parse] took it.
     *
     * A refused settings link is most often a link from a newer version: a
     * screen that was added after this build. It can also be a typo. Only the
     * link's `since=` can tell the two apart, and only when it is there, so
     * this hands that on and leaves the deciding to whoever draws the answer.
     * An addon link or a stranger's scheme gets null, and is still nobody's
     * business here.
     */
    fun unknown(link: String?): Unknown? {
        val (host, _, query) = split(link) ?: return null
        val ours = host.equals(SETTING_HOST, ignoreCase = true) || host.equals(SCREEN_HOST, ignoreCase = true)
        if (!ours || parse(link) != null) return null
        return Unknown(since = version(query.param(SINCE_PARAM)).orEmpty())
    }

    /** Host, raw path and raw query of one of our links, or null when [link] is not one. */
    private fun split(link: String?): Triple<String, String, String>? {
        val text = link?.trim().orEmpty()
        if (text.isEmpty()) return null
        val uri = runCatching { URI(text) }.getOrNull() ?: return null
        if (!uri.scheme.equals(SCHEME, ignoreCase = true)) return null

        // "wmkeyboard://settings/themes" puts the host and path apart; the
        // opaque "wmkeyboard:settings/themes" form carries both in one string.
        // Accept either rather than making the XML care which it wrote.
        val query = text.substringAfter('?', "")
        if (uri.isOpaque) {
            val body = uri.rawSchemeSpecificPart.orEmpty().substringBefore('?')
            return Triple(body.substringBefore('/'), body.substringAfter('/', ""), query)
        }
        return Triple(uri.host.orEmpty(), uri.rawPath.orEmpty(), query)
    }

    /**
     * The route for [target], resolving the screen from [index] when the link
     * named a row and no screen, and null when the row is not one the app has.
     *
     * A row can be indexed on more than one screen — the backup screen carries
     * a toggle named after nearly every feature — so the entry with the
     * strongest claim wins, which is exactly the order
     * [EntryWeight] already declares for search results.
     *
     * A row drawn on a screen that takes an argument, such as one keyboard
     * mode's editor, is indexed on the screen search can open and carries the
     * real one as [SettingsSearchEntry.screenPattern]. A link naming that
     * screen with its argument filled in (`mode_edit/mode_browser`) matches
     * the row through the pattern, so any mode, built-in or the user's own,
     * can be linked to one of its rows.
     */
    internal fun resolve(target: Target, index: () -> List<SettingsSearchEntry>): SettingsSearchEntry? {
        if (target.setting.isEmpty()) return null
        val suffix = "#${target.setting}"
        val pattern = SettingsRoutes.patternOf(target.route)
        return index()
            .filter { entry -> entry.key.endsWith(suffix) }
            .filter { entry ->
                target.route.isEmpty() || entry.route == target.route ||
                    (entry.screenPattern != null && entry.screenPattern == pattern)
            }
            .minByOrNull { entry -> entry.weight.ordinal }
    }

    /**
     * [name] if it looks like a string resource's name, else null.
     *
     * The shape `aapt` gives them, so nothing else can be smuggled through the
     * parameter — the value reaches a lookup by name, and a lookup is a place
     * where "whatever the caller sent" has never been the right input.
     */
    private fun settingName(name: String?): String? = name
        ?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull() }
        ?.takeIf { it.matches(Regex("[a-z][a-z0-9_]{0,127}")) }

    /**
     * [text] if it reads as a version name, `0.5.12` or `1.0`, else null. Same
     * reason as [settingName]: the value ends up in a sentence on screen.
     */
    private fun version(text: String?): String? = text
        ?.takeIf { it.matches(Regex("[0-9]{1,4}(\\.[0-9]{1,6}){0,3}")) }

    /** One query parameter out of a raw `a=1&b=2` string. */
    private fun String.param(name: String): String? = split('&')
        .firstOrNull { it.substringBefore('=') == name }
        ?.substringAfter('=', "")
        ?.takeIf { it.isNotBlank() }
}
