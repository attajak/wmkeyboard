package com.wasimaster.wmkeyboard.app.updates

import android.os.Bundle

/**
 * A settings link this build could not follow, held until the dialog that
 * explains it is closed.
 *
 * [since] is the version the link says it needs (its `since=` parameter), or
 * empty when it does not say. [row] separates a link to one setting from a
 * link to a whole screen, which is only a difference in the words.
 */
internal data class MissingLink(val since: String, val row: Boolean) {

    /** Kept across a rotation, which rebuilds the activity and its dialog with it. */
    fun save(out: Bundle) {
        out.putString(KEY_SINCE, since)
        out.putBoolean(KEY_ROW, row)
    }

    companion object {
        private const val KEY_SINCE = "missing_link_since"
        private const val KEY_ROW = "missing_link_row"

        fun restore(saved: Bundle?): MissingLink? {
            val since = saved?.getString(KEY_SINCE) ?: return null
            return MissingLink(since, saved.getBoolean(KEY_ROW))
        }
    }
}

/**
 * Version names, compared the way a person reads them.
 *
 * Numeric, part by part, so `0.5.10` is newer than `0.5.9`, which a string
 * comparison gets backwards. A missing part counts as 0, so `0.6` and `0.6.0`
 * are the same version. Anything after the numbers, such as a `-debug` suffix,
 * is not part of the version and is ignored.
 */
internal object AppVersion {

    private val leading = Regex("^[0-9]+(\\.[0-9]+)*")

    /** Negative when [a] is older than [b], positive when newer, 0 when the same. */
    fun compare(a: String, b: String): Int {
        val pa = parts(a)
        val pb = parts(b)
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val d = pa.getOrElse(i) { 0 }.compareTo(pb.getOrElse(i) { 0 })
            if (d != 0) return d
        }
        return 0
    }

    private fun parts(version: String): List<Int> =
        leading.find(version.trim())?.value?.split('.')?.map { it.toIntOrNull() ?: 0 }.orEmpty()
}

/**
 * What the dialog for a [MissingLink] says, worked out from the link, the
 * installed version and whatever the updater knows.
 *
 * Pure, so every channel's answer can be tested without a network or a store.
 */
internal sealed interface LinkVerdict {

    /**
     * The link says a version this old already has what it opens, so an update
     * is not the answer. The link has a mistake in it, or the screen or row
     * was renamed or removed since.
     */
    data object NotInThisVersion : LinkVerdict

    /** Nothing checked yet, and this channel checks only when asked. F-Droid. */
    data object Ask : LinkVerdict

    data object Checking : LinkVerdict

    /**
     * A newer version is out. [versionName] is null where the store does not
     * say which version (Play), in which case it may or may not be the one.
     */
    data class Update(val versionName: String?) : LinkVerdict

    /**
     * No release has it. Either the app is up to date, or the newest release
     * is still older than the link's `since=`: a link to a setting that is in
     * the source code and not released yet.
     */
    data object NotReleased : LinkVerdict

    /** An update is downloading or installing already. The dialog shows that. */
    data object Updating : LinkVerdict

    /**
     * No answer to give. [failed] is a check that went wrong and can be tried
     * again; otherwise this copy has no update source at all (a build installed
     * from outside the Play Store, a debug build), and all the dialog can do is
     * name the version to look for.
     */
    data class CannotCheck(val failed: Boolean) : LinkVerdict

    companion object {

        /**
         * [autoCheck] is false on F-Droid, whose client is what reports new
         * builds, and where this app checks only when a button says so.
         */
        fun of(since: String, installed: String, state: UpdateState, autoCheck: Boolean): LinkVerdict {
            if (since.isNotEmpty() && AppVersion.compare(since, installed) <= 0) return NotInThisVersion
            return when (state) {
                UpdateState.Unsupported -> CannotCheck(failed = false)
                UpdateState.Idle -> if (autoCheck) Checking else Ask
                UpdateState.Checking -> Checking
                is UpdateState.UpToDate -> NotReleased
                is UpdateState.Available -> {
                    val newest = state.versionName?.takeIf { it.isNotBlank() }
                    if (since.isNotEmpty() && newest != null && AppVersion.compare(newest, since) < 0) {
                        NotReleased
                    } else {
                        Update(newest)
                    }
                }
                is UpdateState.Downloading,
                UpdateState.Downloaded,
                UpdateState.Installing,
                -> Updating
                // Backing out of the store's own dialog is an answer, not a
                // fault. The question stands, so it is asked again.
                is UpdateState.Failed -> if (state.cancelled) Ask else CannotCheck(failed = true)
            }
        }

        /** Whether opening the dialog should start a check by itself. */
        fun shouldCheck(since: String, installed: String, state: UpdateState, autoCheck: Boolean): Boolean {
            if (!autoCheck) return false
            if (since.isNotEmpty() && AppVersion.compare(since, installed) <= 0) return false
            // Only from a state that is an old answer or no answer. A download
            // or an install the user started must not be checked over.
            return when (state) {
                UpdateState.Idle,
                is UpdateState.UpToDate,
                is UpdateState.Available,
                is UpdateState.Failed,
                -> true
                UpdateState.Unsupported,
                UpdateState.Checking,
                is UpdateState.Downloading,
                UpdateState.Downloaded,
                UpdateState.Installing,
                -> false
            }
        }
    }
}
