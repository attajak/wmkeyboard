package com.wasimaster.wmkeyboard.core.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.wasimaster.wmkeyboard.common.R

/**
 * Everything this app puts in the notification shade, in one place.
 *
 * A keyboard is not an app people open, which is exactly why it needs the
 * shade: the settings screen showing a download's progress is a screen nobody
 * is looking at a minute after they started it, and the keyboard itself has no
 * room to say "your dictionary finished". The shade is the only surface this
 * app has that outlives the window the user is in.
 *
 * ## The rules every poster obeys
 *
 * - **Nothing is posted the user did not ask for.** Every kind is a switch in
 *   the settings app, read here through [NotificationSwitches] rather than by
 *   the caller, so a new posting site cannot forget to check.
 * - **Nothing is posted the app cannot see the point of.** From API 33 the
 *   permission may simply not be there; [canPost] is checked in [post] and a
 *   refused permission makes every call a no-op rather than a crash.
 * - **Progress notifications expire.** This app does no background work: a
 *   download lives in whichever process started it, and that process can be
 *   killed mid-transfer. A notification left behind by a dead process would sit
 *   at 43% forever, so every progress post carries a timeout (see
 *   [PROGRESS_TIMEOUT_MS]) and the shade clears it by itself.
 *
 * Channels are created on demand rather than at startup, because startup here
 * includes the keyboard's own, where every millisecond is one the user waits
 * with a blank keyboard.
 */
object WmNotifications {

    /**
     * How long a progress notification survives without an update.
     *
     * Long enough that a slow chunk on a bad connection does not blink the
     * notification away, short enough that a killed process does not leave a
     * lie in the shade for the rest of the day.
     */
    const val PROGRESS_TIMEOUT_MS = 90_000L

    private val channelsMade = mutableSetOf<String>()

    /**
     * Whether a posted notification would actually reach the user.
     *
     * Two separate questions with the same answer as far as any caller is
     * concerned: whether Android granted the permission (API 33 and up), and
     * whether the user has since switched the app's notifications off.
     */
    fun canPost(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    /**
     * Posts one notification, or does nothing at all.
     *
     * [build] runs only once the gates are passed, so a caller pays nothing for
     * the text of a notification that was never going to be shown.
     */
    fun post(
        context: Context,
        kind: NotificationKind,
        id: Int,
        build: NotificationCompat.Builder.() -> Unit,
    ) {
        val app = context.applicationContext
        if (!NotificationSwitches.isOn(app, kind)) return
        if (!canPost(app)) return
        ensureChannel(app, kind)
        val builder = NotificationCompat.Builder(app, kind.channelId)
            .setSmallIcon(R.drawable.common_ic_notification)
            .setCategory(kind.category)
            .setShowWhen(false)
            .apply(build)
        // A denied permission is already ruled out above; this still throws on
        // the devices where the platform disagrees, and a keyboard must not die
        // over a notification.
        runCatching { NotificationManagerCompat.from(app).notify(id, builder.build()) }
    }

    /** Takes one back down. Safe to call for a notification that was never posted. */
    fun cancel(context: Context, id: Int) {
        runCatching {
            NotificationManagerCompat.from(context.applicationContext).cancel(id)
        }
    }

    /** The launcher intent, for the notifications whose only action is "open the app". */
    fun launchIntent(context: Context): Intent? =
        context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun ensureChannel(context: Context, kind: NotificationKind) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (!channelsMade.add(kind.channelId)) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            kind.channelId,
            context.getString(kind.channelNameRes),
            kind.importance,
        )
        channel.description = context.getString(kind.channelBodyRes)
        // Progress and pinned-keyboard channels are furniture, not events: they
        // must never buzz, and they have nothing to badge.
        if (kind.importance <= NotificationManager.IMPORTANCE_LOW) {
            channel.setShowBadge(false)
            channel.enableVibration(false)
        }
        runCatching { manager.createNotificationChannel(channel) }
    }
}

/**
 * The kinds of notification this app posts, one per channel.
 *
 * A kind is three things that have to agree: the channel the user sees in
 * Android's own settings, the switch they see in this app's, and the
 * importance. They live together so that adding a fourth reason to notify
 * means adding a channel and a switch, rather than quietly borrowing one that
 * says something else.
 */
enum class NotificationKind(
    internal val channelId: String,
    internal val channelNameRes: Int,
    internal val channelBodyRes: Int,
    internal val importance: Int,
    internal val category: String,
) {
    /** Dictionaries, models, voice packs, add-ons, the app's own update file. */
    DOWNLOADS(
        channelId = "downloads",
        channelNameRes = R.string.common_notify_channel_downloads_title,
        channelBodyRes = R.string.common_notify_channel_downloads_body,
        importance = NotificationManager.IMPORTANCE_LOW,
        category = NotificationCompat.CATEGORY_PROGRESS,
    ),

    /** A new version to fetch, one waiting to be installed, one just installed. */
    UPDATES(
        channelId = "updates",
        channelNameRes = R.string.common_notify_channel_updates_title,
        channelBodyRes = R.string.common_notify_channel_updates_body,
        importance = NotificationManager.IMPORTANCE_DEFAULT,
        category = NotificationCompat.CATEGORY_RECOMMENDATION,
    ),

    /** An automatic backup that did not happen, which is otherwise invisible. */
    BACKUP(
        channelId = "backup",
        channelNameRes = R.string.common_notify_channel_backup_title,
        channelBodyRes = R.string.common_notify_channel_backup_body,
        importance = NotificationManager.IMPORTANCE_DEFAULT,
        category = NotificationCompat.CATEGORY_ERROR,
    ),

    /** The keyboard's own controls, kept in the shade while the user wants them. */
    KEYBOARD(
        channelId = "keyboard",
        channelNameRes = R.string.common_notify_channel_keyboard_title,
        channelBodyRes = R.string.common_notify_channel_keyboard_body,
        importance = NotificationManager.IMPORTANCE_LOW,
        category = NotificationCompat.CATEGORY_SERVICE,
    ),

    /**
     * What a paired computer did while the keyboard was not on screen to show
     * it: asked to pair, sent a file, pinged (issue #285).
     */
    CONNECT(
        channelId = "connect",
        channelNameRes = R.string.common_notify_channel_connect_title,
        channelBodyRes = R.string.common_notify_channel_connect_body,
        importance = NotificationManager.IMPORTANCE_DEFAULT,
        category = NotificationCompat.CATEGORY_EVENT,
    ),
}

/** Stable notification ids, so two features cannot overwrite each other. */
object NotificationIds {

    /** The update in flight: checking, downloading, waiting to install. */
    const val UPDATE = 1

    /** "Updated to 0.5.7", after the fact. */
    const val UPDATED = 2

    /** The ongoing keyboard controls. */
    const val KEYBOARD = 3

    /** The automatic backup that failed. */
    const val BACKUP = 4

    /** A computer asking to pair over KDE Connect. One at a time is all the protocol allows anyway. */
    const val CONNECT_PAIR = 5

    /** The last file, ping or text a paired computer sent. */
    const val CONNECT_EVENT = 6

    /**
     * One id per download, derived from its key.
     *
     * Derived rather than allocated because the notification outlives the
     * screen that started it and may be updated by a different collector after
     * a rotation; the key is the only thing both ends agree on. The range is
     * far from the fixed ids above, and a collision inside it is two
     * downloads sharing a row rather than anything worse.
     */
    fun forDownload(key: String): Int = DOWNLOAD_BASE + (key.hashCode() and DOWNLOAD_MASK)

    private const val DOWNLOAD_BASE = 1000
    private const val DOWNLOAD_MASK = 0xFFFF
}
