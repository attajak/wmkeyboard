package com.wasimaster.wmkeyboard.docshots

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.ComposeTestRule
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository

/**
 * One docs screenshot of the settings app: where to open, what state to be in
 * first, what to do once there, and what to mark.
 *
 * [id] is the manifest id (docs/screenshots/manifest.json) and so also the
 * asset path under docs/src/assets/screens/.
 */
data class Shot(
    val id: String,
    /** A NavHost route, arguments included: `typing`, `language/bn`, `tool/CALENDAR`. */
    val route: String,
    /**
     * The resource name of a row's or a group's title. It is highlighted the
     * way a search result is, so it is scrolled into view, and then ringed.
     */
    val setting: String? = null,
    /** Stored state to be in before the screen opens, on top of the defaults. */
    val seed: suspend Seed.() -> Unit = {},
    /** What to do once the screen is up: taps, typing, scrolling, ringing. */
    val steps: Steps.() -> Unit = {},
    /**
     * Opens something other than a settings screen: an addon link, or a file
     * handed to ImportFileActivity. [route] and [setting] are ignored.
     */
    val launch: (suspend Seed.() -> android.content.Intent)? = null,
    /** Leaves setup unfinished, so the app opens on the setup wizard. */
    val onboarding: Boolean = false,
)

/** The receiver of [Shot.seed]. */
class Seed(val repo: SettingsRepository, val context: android.content.Context) {
    /**
     * Personal dictionary words: a count of 200 or more reads "Added by you",
     * anything below "Seen N×".
     */
    fun lexicon(vararg words: Pair<String, Int>) {
        val file = java.io.File(context.filesDir, "learning/user_lexicon.json")
        file.delete()
        file.parentFile?.mkdirs()
        val lex = com.wasimaster.wmkeyboard.core.prediction.UserLexicon(file)
        for ((word, count) in words) {
            if (count >= 200) lex.addWord(word, boost = count) else lex.learnWord(word, count)
        }
        lex.save()
    }

    suspend fun blacklist(vararg words: String) = words.forEach { repo.addSuggestionBlacklistWord(it) }

    /** Saves one of the docs' sample themes (docs/src/assets/themes) as the user's own. */
    suspend fun theme(name: String): com.wasimaster.wmkeyboard.core.theme.ThemeSpec {
        val json = java.io.File("../docs/src/assets/themes/$name.wmtheme.json").readText()
        val spec = requireNotNull(com.wasimaster.wmkeyboard.core.theme.ThemeCodec.decode(json)) { name }
        repo.upsertCustomTheme(spec)
        return spec
    }

    /** A file on the device, named [name], holding [text]. */
    fun file(name: String, text: String): java.io.File =
        java.io.File(context.cacheDir, "docshots/$name").apply {
            parentFile?.mkdirs()
            writeText(text)
        }

    /** One of the test resources under app/src/test/resources, copied onto the device as [name]. */
    fun resourceFile(path: String, name: String = path.substringAfterLast('/')): java.io.File =
        java.io.File(context.cacheDir, "docshots/$name").apply {
            parentFile?.mkdirs()
            writeBytes(requireNotNull(Seed::class.java.classLoader!!.getResourceAsStream(path)) { path }.readBytes())
        }

    /** What a file manager sends when a file is opened with WM Keyboard. */
    fun openFile(file: java.io.File): android.content.Intent =
        android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.fromFile(file))
            .setClassName(context, "com.wasimaster.wmkeyboard.app.ImportFileActivity")

    /** A `wmkeyboard://` link, followed the way the browser follows it. */
    fun link(uri: String): android.content.Intent =
        android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(uri))
            .setClassName(context, "com.wasimaster.wmkeyboard.app.MainActivity")
}

/**
 * The receiver of [Shot.steps]. Every finder matches on drawn text, since that
 * is what a reader of the page sees too.
 */
interface Steps {
    val rule: ComposeTestRule

    /** Clicks the first node showing [text], scrolling to it first. */
    fun tap(text: String, substring: Boolean = false)

    /** Clicks the first node described as [description] (icon buttons). */
    fun tapIcon(description: String, substring: Boolean = false)

    /** Types into the [index]th editable field on screen. */
    fun type(text: String, index: Int = 0)

    /** Scrolls the screen so the node showing [text] sits in its upper third. */
    fun scrollTo(text: String, substring: Boolean = false)

    /** Rings the node showing [text] (its whole clickable row, where it has one). */
    fun ring(text: String, substring: Boolean = false)

    /** Rings whatever [find] selects. */
    fun ringNode(find: ComposeTestRule.() -> SemanticsNodeInteraction)

    /** Presses [button] until [target] is on screen, at most [limit] times: a wizard's Next. */
    fun tapUntil(button: String, target: String, limit: Int = 12)

    /** Lets the screen settle again after a change that loads or animates. */
    fun settle()
}

/** Where the ring goes, in pixels of the captured frame. */
data class Ring(val bounds: Rect)
