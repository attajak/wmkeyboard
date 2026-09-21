package com.wasimaster.wmkeyboard.core.notify

import android.content.Context
import androidx.core.content.edit

/**
 * Which notifications the user wants, in a form every module can read.
 *
 * The settings themselves live in `KeyboardSettings.notifications`, in the
 * DataStore that only `:core:settings` and the modules above it can see. The
 * things that post notifications are mostly below that line — a dictionary
 * download in `:core:prediction` cannot ask the settings repository anything —
 * so the switches are mirrored here, into device-protected preferences, and
 * read from here by [WmNotifications.post].
 *
 * Same shape and the same reason as `NotificationOtpCapture`, which mirrors the
 * one-time-code switch down to the notification listener. Two things follow
 * from it:
 *
 * - The defaults below must match the ones on `NotificationSettings`. A fresh
 *   install has never written this file, and reading a default that disagrees
 *   would show the user a switch that says one thing and behaves like another.
 * - The mirror is written by whoever changes the setting, not polled. See
 *   `SettingsRepository.setNotify*`.
 *
 * Device-protected storage, so this is readable in the direct-boot window as
 * well: nothing here is private, and the keyboard runs before the first unlock.
 */
object NotificationSwitches {

    private const val PREFS = "notifications"

    /** Written when the app has offered the notification permission once. */
    private const val KEY_ASKED = "asked"

    /** Progress, completion and failure for everything the app downloads. */
    const val DEFAULT_DOWNLOADS = true

    /** A version to fetch, one waiting to install, one just installed. */
    const val DEFAULT_UPDATES = true

    /** An automatic backup that failed, which nothing else reports. */
    const val DEFAULT_BACKUP = true

    /**
     * Keyboard controls in the shade. Off by default: it is an ongoing
     * notification, and an app that puts one there uninvited is an app people
     * turn off entirely.
     */
    const val DEFAULT_KEYBOARD = false

    /**
     * A paired computer asking to pair, sending a file, pinging. On: each is
     * something the user or their own computer just did, and none can happen
     * at all until the KDE Connect tool has been switched on and paired.
     */
    const val DEFAULT_CONNECT = true

    /** Whether [kind] may be posted at all. */
    fun isOn(context: Context, kind: NotificationKind): Boolean =
        runCatching { prefs(context).getBoolean(kind.key, kind.default) }
            .getOrDefault(kind.default)

    /** Mirrors one switch down from the settings repository. */
    fun set(context: Context, kind: NotificationKind, value: Boolean) {
        runCatching { prefs(context).edit { putBoolean(kind.key, value) } }
    }

    /**
     * Whether the app has already put the notification permission in front of
     * this user.
     *
     * The ask happens at the first moment a notification would have been
     * useful — a download starting, the shade controls being switched on —
     * rather than on a screen they opened for something else. Once offered it
     * is not offered again: a second unprompted permission dialog is how an
     * app teaches people to refuse by reflex.
     */
    fun permissionOffered(context: Context): Boolean =
        runCatching { prefs(context).getBoolean(KEY_ASKED, false) }.getOrDefault(false)

    fun markPermissionOffered(context: Context) {
        runCatching { prefs(context).edit { putBoolean(KEY_ASKED, true) } }
    }

    private val NotificationKind.key: String
        get() = channelId

    private val NotificationKind.default: Boolean
        get() = when (this) {
            NotificationKind.DOWNLOADS -> DEFAULT_DOWNLOADS
            NotificationKind.UPDATES -> DEFAULT_UPDATES
            NotificationKind.BACKUP -> DEFAULT_BACKUP
            NotificationKind.KEYBOARD -> DEFAULT_KEYBOARD
            NotificationKind.CONNECT -> DEFAULT_CONNECT
        }

    private fun prefs(context: Context) =
        context.applicationContext.createDeviceProtectedStorageContext()
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
