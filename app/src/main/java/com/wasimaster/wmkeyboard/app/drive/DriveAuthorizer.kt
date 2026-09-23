package com.wasimaster.wmkeyboard.app.drive

import android.app.Activity
import android.content.Context
import android.content.IntentSender
import com.wasimaster.wmkeyboard.core.settings.sink.DriveAuth
import com.wasimaster.wmkeyboard.core.settings.sink.DriveTokenProvider

/**
 * Asking the user to let this app use its own folder in their Google Drive.
 *
 * The shared half of the Play services seam, in `src/main` so both sides
 * compile against the same shape. `src/gms/java` has the real one;
 * `src/nogms/java` has a version that always answers "no", which is what an
 * F-Droid or otherwise Google-free build gets.
 *
 * The token side of this is separate — [DriveTokenProvider], in
 * `:core:settings` — because the background job needs a token and must never
 * need a screen. This interface is only for the part with a user in front of
 * it.
 */
interface DriveAuthorizer {

    /** Whether this build can talk to Google at all. False on F-Droid. */
    val available: Boolean

    /**
     * Whether the user has already granted [scope], without asking them
     * anything. One of `DriveSink.SCOPE` and `DriveSink.SCOPE_FILE`.
     */
    suspend fun authorized(context: Context, scope: String): Boolean

    /**
     * Asks for the scope, showing Google's consent screen if it is needed.
     *
     * Needs an [Activity] because that is what a consent screen is launched
     * from. [onConsent] is handed the intent sender when Google wants to show
     * something; the caller launches it and calls [authorize] again afterwards.
     *
     * Returns true when the scope is granted by the end of the call.
     */
    suspend fun authorize(
        activity: Activity,
        scope: String,
        onConsent: (IntentSender) -> Unit,
    ): Boolean
}

/**
 * There is deliberately no `revoke` here.
 *
 * `AuthorizationClient` has no way to hand a scope back, and pretending
 * otherwise would leave the user believing they had withdrawn something they
 * had not. Turning the destination off stops this app using the grant; taking
 * the grant away is done in the Google account's own permissions page, and the
 * settings screen says so.
 */

/** The authorizer that refuses everything, for builds with no Play services. */
object NoDriveAuthorizer : DriveAuthorizer {
    override val available: Boolean get() = false
    override suspend fun authorized(context: Context, scope: String): Boolean = false
    override suspend fun authorize(activity: Activity, scope: String, onConsent: (IntentSender) -> Unit) = false
}

/**
 * Publishes the token provider to [DriveAuth] so the background job can find
 * it, and does nothing on a build that has no provider to publish.
 *
 * Called once from the app and once from the keyboard, because either process
 * may be the one that runs a backup.
 */
fun installDriveAuth(context: Context) {
    DriveAuth.provider = driveTokenProvider(context)
}
