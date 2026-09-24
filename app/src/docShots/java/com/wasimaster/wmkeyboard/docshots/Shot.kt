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
    /**
     * Stops the clock as soon as the screen opens: for a screen that opens on a
     * focused field, whose cursor blinks forever and never lets it read idle.
     */
    val freezeClock: Boolean = false,
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

    /** What a share sheet hands WM Keyboard when a link is shared into it. */
    fun sharedLink(text: String): android.content.Intent =
        android.content.Intent(android.content.Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(android.content.Intent.EXTRA_TEXT, text)
            .setClassName(context, "com.wasimaster.wmkeyboard.app.ImportLinkActivity")

    /**
     * WM Keyboard enabled and picked as the keyboard, the way the setup
     * wizard's first page waits for. Robolectric has no input methods of its own.
     */
    fun keyboardReady() {
        val imm = context.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
        val service = "com.wasimaster.wmkeyboard.ime.WMKeyboardService"
        val info = android.view.inputmethod.InputMethodInfo(context.packageName, service, "WM Keyboard", null)
        org.robolectric.Shadows.shadowOf(imm).setEnabledInputMethodInfoList(listOf(info))
        android.provider.Settings.Secure.putString(
            context.contentResolver, android.provider.Settings.Secure.DEFAULT_INPUT_METHOD, "${context.packageName}/$service",
        )
    }

    /** Two weeks of typing statistics, so every tile and chart has something to draw. */
    fun typingStats() {
        val file = java.io.File(context.filesDir, com.wasimaster.wmkeyboard.core.tools.TypingStats.FILE_PATH)
        file.delete()
        file.parentFile?.mkdirs()
        val today = com.wasimaster.wmkeyboard.core.tools.TypingStatsMath
            .localEpochDay(System.currentTimeMillis(), java.util.TimeZone.getDefault())
        val perDay = listOf(3100, 2400, 4200, 1800, 3600, 900, 2700, 3900, 2200, 4600, 3300, 1500, 2800, 3700)
        var chars = 0L
        var words = 0L
        var backspaces = 0L
        var active = 0L
        val days = perDay.mapIndexed { back, c ->
            val w = c / 5L
            val b = c / 18L
            val ms = c * 190L
            chars += c; words += w; backspaces += b; active += ms
            "\"${today - back}\":{\"chars\":$c,\"words\":$w,\"backspaces\":$b,\"activeMs\":$ms}"
        }
        // A day's typing, heaviest in the evening.
        val hours = listOf(40, 10, 0, 0, 0, 5, 60, 320, 540, 610, 480, 430, 520, 470, 390, 410, 450, 520, 640, 780, 910, 860, 520, 190)
        file.writeText(
            "{\"days\":{${days.joinToString(",")}},\"totalChars\":${chars * 6},\"totalWords\":${words * 6}," +
                "\"totalBackspaces\":${backspaces * 6},\"totalActiveMs\":${active * 6},\"hourHistogram\":[${hours.joinToString(",") { (it * 12).toString() }}]}",
        )
    }

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
