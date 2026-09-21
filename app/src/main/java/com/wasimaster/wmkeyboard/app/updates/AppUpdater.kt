package com.wasimaster.wmkeyboard.app.updates

import androidx.annotation.StringRes
import androidx.compose.runtime.staticCompositionLocalOf
import com.wasimaster.wmkeyboard.app.SpecialAccess
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * In-app updates, as the settings app sees them.
 *
 * The interface is here, in `main`, so every screen compiles in every build
 * channel. The implementations are not, and there is one per channel:
 *
 * - `src/play/java` drives Google's `AppUpdateManager`.
 * - `src/github/java` reads this project's GitHub releases, downloads the APK
 *   that matches the device, and hands it to Android's package installer.
 * - `src/fdroid/java` asks F-Droid what F-Droid has and opens F-Droid. It
 *   downloads nothing: F-Droid's client installs F-Droid builds, and
 *   installs up to 0.5.9 carry F-Droid's key, which our file cannot replace.
 *
 * Exactly one of those directories is on the compile path, picked by
 * `app/build.gradle.kts`. That is what keeps Play Core out of an F-Droid APK,
 * and the installer code and its permissions out of a Play one, where Play's
 * own policy forbids an app from updating itself by any other route.
 *
 * ## The one rule
 *
 * The keyboard shares a process with this activity. Finishing an update
 * restarts that process, so [install] is never called on the app's own
 * initiative: a keyboard that vanishes mid-sentence is a much worse bug than a
 * version that is a day old. Every path that ends in a restart starts with the
 * user pressing something. That matters most on the GitHub channel, where
 * Android may install without a screen of its own once the app owns its
 * updates, so the press is the only warning the user gets.
 */
internal interface AppUpdater {

    /** What the updater knows right now. Drives every piece of update UI. */
    val state: StateFlow<UpdateState>

    /**
     * The release notes for the update on offer, once someone has asked for
     * them with [loadNotes]. Null while they have not been asked for, are
     * still arriving, or do not exist.
     */
    val releaseNotes: StateFlow<String?>
        get() = NoNotes

    /** Where updates come from, for the card and rows to name. */
    @get:StringRes
    val sourceNameRes: Int

    /**
     * Whether this updater fetches the bytes itself.
     *
     * True only on the GitHub channel, and it decides three things that all
     * follow from the same fact: the download can be cancelled, it has to obey
     * the user's data-saving setting, and the install afterwards is this app
     * replacing itself rather than a store doing it.
     */
    val ownsDownload: Boolean
        get() = false

    /**
     * Whether [start] leaves the app instead of downloading. True on F-Droid,
     * where the only useful thing to do is open F-Droid.
     */
    val startsExternally: Boolean
        get() = false

    /** Whether this updater can offer pre-releases at all. GitHub only. */
    val supportsPrereleases: Boolean
        get() = false

    /**
     * Whether [loadNotes] can actually fetch anything. GitHub only.
     *
     * Separate from having a [UpdateState.Available.releaseUrl]: F-Droid has a
     * page to link to and no notes to read out of it, so a card that offered
     * "What's new" there would sit and wait for text that is never coming.
     */
    val supportsNotes: Boolean
        get() = false

    /** Whether to offer pre-releases. Meaningless unless [supportsPrereleases]. */
    var includePrereleases: Boolean

    /**
     * Whether the app may open the update prompt by itself, or only draw the
     * card and wait to be pressed. The user's setting; see
     * [UpdatePrefs.autoPrompt] for why it defaults to on.
     */
    var autoPrompt: Boolean

    /**
     * The grant Android needs before [install] can do anything, or null when
     * it already has it. The UI shows its disclosure and opens the switch;
     * [install] records that it was pressed and picks up again on the way back.
     */
    val installGrant: SpecialAccess?
        get() = null

    /**
     * Asks what is available.
     *
     * [userAsked] separates the check the About screen's row runs from the one
     * the activity runs when it resumes: only the latter is allowed to open a
     * dialog on its own, only the former reports "you are up to date" as a
     * result worth showing, and only the former ignores the interval that
     * keeps a resume from turning into a request every few seconds.
     */
    fun check(userAsked: Boolean = false)

    /**
     * Starts the update the user just agreed to: the store's own flow, a
     * download, or a trip to F-Droid, depending on the channel.
     */
    fun start()

    /** Abandons a download in progress. Nothing to do unless [ownsDownload]. */
    fun cancel() {
        // Nothing to abandon unless this updater owns the download.
    }

    /** Fetches the release notes into [releaseNotes], at most once per release. */
    fun loadNotes() {
        // Only a release has notes to fetch.
    }

    /**
     * Installs a finished download and restarts the app. Call this only from a
     * control the user pressed, see the class note. When [installGrant] is not
     * null this only remembers that it was pressed, and the UI sends the user
     * to the switch.
     */
    fun install()

    /**
     * "Not now": stops this version from prompting again for
     * [UpdatePolicy.SNOOZE_DAYS]. It never hides a high-priority update, which
     * is the whole point of that priority.
     */
    fun dismiss()
}

/** Shared empty notes flow, so the default [AppUpdater.releaseNotes] allocates nothing. */
private val NoNotes: StateFlow<String?> = MutableStateFlow(null)

/** The updater for builds that have no update source behind them. */
internal object NoAppUpdater : AppUpdater {
    override val state: StateFlow<UpdateState> = MutableStateFlow(UpdateState.Unsupported)
    override val sourceNameRes: Int = 0
    override var includePrereleases: Boolean
        get() = false
        set(@Suppress("UNUSED_PARAMETER") value) = Unit
    override var autoPrompt: Boolean
        get() = false
        set(@Suppress("UNUSED_PARAMETER") value) = Unit
    override fun check(userAsked: Boolean) = Unit
    override fun start() = Unit
    override fun install() = Unit
    override fun dismiss() = Unit
}

/**
 * The updater every settings screen reads.
 *
 * Static rather than threaded through the navigation graph: the update card on
 * the home screen and the rows on the About screen are far apart in the tree
 * and must never disagree about the same download. Defaults to [NoAppUpdater]
 * so a preview or a screen composed outside [com.wasimaster.wmkeyboard.app.MainActivity]
 * simply shows nothing.
 */
internal val LocalAppUpdater = staticCompositionLocalOf<AppUpdater> { NoAppUpdater }
