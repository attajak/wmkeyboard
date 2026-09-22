package com.wasimaster.wmkeyboard.app.updates

import android.content.Context
import androidx.core.content.edit

/**
 * Everything the updater has to remember between two visits to the settings
 * app: what the user has already waved away, whether they want to be prompted
 * at all, and, on a channel that downloads for itself, where a half-finished
 * update had got to.
 *
 * Its own `SharedPreferences` file rather than fields on `KeyboardSettings`,
 * for two reasons. None of it is a keyboard setting: nothing in the IME reads
 * it, and it has no business in a settings export, a theme share or the config
 * backup. And `KeyboardSettings` is a data class within a handful of fields of
 * the JVM's 255-slot ceiling on `copy$default`, so its remaining room belongs
 * to things that actually shape typing.
 *
 * ## Every timestamp here is untrustworthy
 *
 * This file *is* covered by Android's own backup, so a restored device
 * inherits another device's clock readings, and a user can move the clock
 * whenever they like. So each window below treats a timestamp in the future as
 * expired rather than as an open window: the failure that matters is a stale
 * value holding a snooze, a rate-limit pause or an install open forever.
 */
internal class UpdatePrefs(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    /**
     * Whether the app may put an update in front of the user by itself.
     *
     * On by default: an update the user never hears about is the failure mode
     * this whole feature exists to fix. Turning it off leaves the update card
     * and the About row, so the information is still there. It just stops
     * arriving uninvited.
     */
    var autoPrompt: Boolean
        get() = prefs.getBoolean(KEY_AUTO_PROMPT, DEFAULT_AUTO_PROMPT)
        set(value) = prefs.edit { putBoolean(KEY_AUTO_PROMPT, value) }

    /**
     * Whether to offer pre-releases. Off by default, and only the GitHub
     * channel has any to offer.
     */
    var includePrereleases: Boolean
        get() = prefs.getBoolean(KEY_PRERELEASES, DEFAULT_PRERELEASES)
        set(value) = prefs.edit { putBoolean(KEY_PRERELEASES, value) }

    /** True while [versionCode] is inside the window opened by [snooze]. */
    fun isSnoozed(versionCode: Int, now: Long): Boolean {
        if (prefs.getInt(KEY_SNOOZED_VERSION, 0) != versionCode) return false
        val since = prefs.getLong(KEY_SNOOZED_AT, 0L)
        if (now < since) return false
        return now - since < UpdatePolicy.SNOOZE_MILLIS
    }

    /** Records "Not now" for one version. */
    fun snooze(versionCode: Int, now: Long) {
        prefs.edit {
            putInt(KEY_SNOOZED_VERSION, versionCode)
            putLong(KEY_SNOOZED_AT, now)
        }
    }

    // ---- the automatic check ----

    /** When the last check reached the network, for [UpdateCheckGate]. */
    var lastCheckAt: Long
        get() = prefs.getLong(KEY_LAST_CHECK_AT, 0L)
        set(value) = prefs.edit { putLong(KEY_LAST_CHECK_AT, value) }

    /** When GitHub said its rate limit resets. 0 when it has not complained. */
    var rateLimitedUntil: Long
        get() = prefs.getLong(KEY_RATE_LIMITED_UNTIL, 0L)
        set(value) = prefs.edit { putLong(KEY_RATE_LIMITED_UNTIL, value) }

    /**
     * The ETag of the last release list that arrived with a body.
     *
     * Sent back as `If-None-Match` so an unchanged list answers 304, which
     * costs no bytes and, more to the point, does not count against the sixty
     * unauthenticated requests an hour GitHub allows per address.
     */
    var etag: String?
        get() = prefs.getString(KEY_ETAG, null)
        set(value) = prefs.edit { putString(KEY_ETAG, value) }

    // ---- the update on offer ----

    /**
     * The update currently on offer, as JSON, or null when there is none.
     *
     * It is what makes the card survive a rotation and a process death without
     * spending a request, and on the GitHub channel it is also the only record
     * of what a half-downloaded file in the staging directory was meant to be:
     * the file itself carries no checksum and no release page, and the user
     * can delete it from the storage screen at any time.
     */
    var knownCandidate: String?
        get() = prefs.getString(KEY_CANDIDATE, null)
        set(value) = prefs.edit {
            if (value == null) remove(KEY_CANDIDATE) else putString(KEY_CANDIDATE, value)
        }

    /**
     * Whether the offer on hand is the move from the English-only build to the
     * one with every language, rather than a newer version (#322). Kept beside
     * [knownCandidate] because that candidate can have the version code already
     * installed, which on its own reads as stale and gets thrown away.
     */
    var languageSwitch: Boolean
        get() = prefs.getBoolean(KEY_LANGUAGE_SWITCH, false)
        set(value) = prefs.edit { putBoolean(KEY_LANGUAGE_SWITCH, value) }

    /** The live `PackageInstaller` session, or 0. Stale ones are abandoned on start. */
    var sessionId: Int
        get() = prefs.getInt(KEY_SESSION_ID, 0)
        set(value) = prefs.edit { putInt(KEY_SESSION_ID, value) }

    // ---- the trip out to the unknown-sources switch ----

    /**
     * When Install was pressed, as [android.os.SystemClock.elapsedRealtime].
     *
     * Monotonic on purpose, and reset by a reboot: this window exists so that
     * granting the permission and coming straight back finishes what the user
     * started, and a reboot in the middle is a good enough reason to make them
     * press it again.
     */
    var installPressedAt: Long
        get() = prefs.getLong(KEY_INSTALL_PRESSED_AT, 0L)
        set(value) = prefs.edit { putLong(KEY_INSTALL_PRESSED_AT, value) }

    // ---- after the fact ----

    /**
     * Records the version this app is about to replace itself with, and
     * whether the install is the move to every language rather than an update.
     */
    fun rememberInstall(versionCode: Int, now: Long, languageSwitch: Boolean = false) {
        prefs.edit {
            putInt(KEY_INSTALLED_VERSION, versionCode)
            putLong(KEY_INSTALLED_AT, now)
            putBoolean(KEY_INSTALLED_SWITCH, languageSwitch)
        }
    }

    /**
     * Whether the install [takeJustUpdated] is about to report was the move to
     * every language. Read it first: that call clears the record.
     */
    val justSwitchedLanguages: Boolean
        get() = prefs.getBoolean(KEY_INSTALLED_SWITCH, false)

    /**
     * True once, in the first session of a version this app installed itself.
     *
     * The whole reason it exists: on Android 12 and up an update can install
     * with no screen of its own, so without this there would be no sign at all
     * that anything happened. Clears itself, so the card shows once.
     */
    fun takeJustUpdated(currentVersionCode: Int, now: Long): Boolean {
        if (prefs.getInt(KEY_INSTALLED_VERSION, 0) != currentVersionCode) return false
        val at = prefs.getLong(KEY_INSTALLED_AT, 0L)
        prefs.edit { remove(KEY_INSTALLED_VERSION).remove(KEY_INSTALLED_AT).remove(KEY_INSTALLED_SWITCH) }
        // A restored backup can carry another device's timestamp, and a
        // version code that matches by coincidence. A week is long enough for
        // the user to open the app after an update and short enough that a
        // stale value does not announce an update that never happened here.
        return now >= at && now - at < JUST_UPDATED_MILLIS
    }

    /** Forgets an offer, and anything staged for it. */
    fun clearCandidate() {
        prefs.edit {
            remove(KEY_CANDIDATE)
            remove(KEY_LANGUAGE_SWITCH)
            remove(KEY_SESSION_ID)
            remove(KEY_INSTALL_PRESSED_AT)
        }
    }

    internal companion object {
        /**
         * What [autoPrompt] is worth before anyone touches it. Named rather
         * than inlined into the getter because the settings row reads it too,
         * for the reset control that puts the switch back.
         */
        const val DEFAULT_AUTO_PROMPT = true

        /** What [includePrereleases] is worth before anyone touches it. */
        const val DEFAULT_PRERELEASES = false

        /** How long after a self-install the "Updated to" card may still appear. */
        const val JUST_UPDATED_MILLIS = 7L * 24 * 60 * 60 * 1000

        private const val FILE_NAME = "app_updates"
        private const val KEY_AUTO_PROMPT = "auto_prompt"
        private const val KEY_PRERELEASES = "include_prereleases"
        private const val KEY_SNOOZED_VERSION = "snoozed_version"
        private const val KEY_SNOOZED_AT = "snoozed_at"
        private const val KEY_LAST_CHECK_AT = "last_check_at"
        private const val KEY_RATE_LIMITED_UNTIL = "rate_limited_until"
        private const val KEY_ETAG = "etag"
        private const val KEY_CANDIDATE = "known_candidate"
        private const val KEY_SESSION_ID = "session_id"
        private const val KEY_INSTALL_PRESSED_AT = "install_pressed_at"
        private const val KEY_INSTALLED_VERSION = "installed_version_code"
        private const val KEY_INSTALLED_AT = "installed_at"
        private const val KEY_INSTALLED_SWITCH = "installed_language_switch"
        private const val KEY_LANGUAGE_SWITCH = "language_switch"
    }
}
