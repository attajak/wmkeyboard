package com.wasimaster.wmkeyboard.app.oauth

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import com.wasimaster.wmkeyboard.core.settings.BackupDestination
import com.wasimaster.wmkeyboard.core.settings.sink.DropboxSink
import com.wasimaster.wmkeyboard.core.settings.sink.OneDriveSink
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The browser half of signing in to Dropbox or OneDrive.
 *
 * PKCE, in a plain browser tab, with no SDK from either vendor. Both flows are
 * the same four steps and differ only in two URLs, so they share this: make a
 * verifier, send the user to the service with its hash, get a code back on a
 * custom scheme, trade the code for a refresh token.
 *
 * **No client secret anywhere.** That is the point of PKCE, and it is what
 * makes this honest in an app whose APK anyone can unpack: the verifier is
 * generated fresh per sign-in and never leaves the device until it is proof.
 */
object BackupOAuth {

    /** Matches the intent filter on [OAuthRedirectActivity]. */
    const val REDIRECT_URI = "wmkeyboard://oauth"

    private const val VERIFIER_BYTES = 64

    /** The sign-in that is waiting for the browser to come back, if any. */
    private data class Pending(val destination: BackupDestination, val verifier: String)

    @Volatile
    private var pending: Pending? = null

    private const val PENDING_FILE = "oauth_pending"

    /**
     * The pending sign-in also goes to disk, because the process does not
     * reliably survive the trip. The browser is in front while the user types
     * a password, and on a phone short of memory the settings process behind
     * it is the one killed. Without this the redirect came back to an empty
     * [pending] and was dropped with no word, and "Sign in" looked broken.
     *
     * `noBackupFilesDir`, so Android's own backup never copies it, and deleted
     * as soon as the redirect arrives. The verifier is only proof for a code
     * that has not been issued yet, and useless once it has been exchanged.
     */
    private fun pendingFile(context: Context) = File(context.noBackupFilesDir, PENDING_FILE)

    private fun savePending(context: Context, value: Pending) {
        runCatching { pendingFile(context).writeText("${value.destination.name}\n${value.verifier}") }
    }

    private fun takePending(context: Context): Pending? {
        val file = pendingFile(context)
        val saved = runCatching {
            val (destination, verifier) = file.readText().split('\n', limit = 2)
            Pending(BackupDestination.valueOf(destination), verifier)
        }.getOrNull()
        file.delete()
        return saved
    }

    private val _result = MutableStateFlow<Result?>(null)

    /** The code a redirect delivered, until something consumes it. */
    val result: StateFlow<Result?> = _result

    data class Result(
        val destination: BackupDestination,
        /** The authorization code, or null when the user refused. */
        val code: String?,
        val verifier: String,
    )

    /** Clears a delivered result so the next sign-in starts from nothing. */
    fun consume() {
        _result.value = null
    }

    /**
     * Starts a sign-in by opening the service's consent page in a browser.
     *
     * A browser rather than a WebView, deliberately: a WebView would let this
     * app see the password being typed, which is exactly what an OAuth flow
     * exists to avoid, and both services are entitled to refuse it.
     */
    fun start(activity: Activity, destination: BackupDestination, clientId: String): Boolean {
        if (clientId.isEmpty()) return false
        val verifier = newVerifier()
        pending = Pending(destination, verifier).also { savePending(activity, it) }

        val url = when (destination) {
            BackupDestination.DROPBOX -> Uri.parse(DropboxSink.AUTHORIZE_URL)
                .buildUpon()
                .appendQueryParameter("client_id", clientId)
                .appendQueryParameter("response_type", "code")
                .appendQueryParameter("redirect_uri", REDIRECT_URI)
                .appendQueryParameter("code_challenge", challengeFor(verifier))
                .appendQueryParameter("code_challenge_method", "S256")
                // Without this Dropbox issues a four-hour access token and no
                // refresh token, and every backup after the first would fail.
                .appendQueryParameter("token_access_type", "offline")
                .build()

            BackupDestination.ONEDRIVE -> Uri.parse(OneDriveSink.AUTHORIZE_URL)
                .buildUpon()
                .appendQueryParameter("client_id", clientId)
                .appendQueryParameter("response_type", "code")
                .appendQueryParameter("redirect_uri", REDIRECT_URI)
                .appendQueryParameter("scope", OneDriveSink.SCOPE)
                .appendQueryParameter("code_challenge", challengeFor(verifier))
                .appendQueryParameter("code_challenge_method", "S256")
                .build()

            else -> return false
        }

        return runCatching {
            activity.startActivity(Intent(Intent.ACTION_VIEW, url))
        }.isSuccess
    }

    /** Called by [OAuthRedirectActivity] when the browser comes back. */
    internal fun deliver(context: Context, uri: Uri?) {
        val saved = takePending(context)
        val waiting = pending ?: saved ?: return
        pending = null
        _result.value = Result(
            destination = waiting.destination,
            code = uri?.getQueryParameter("code"),
            verifier = waiting.verifier,
        )
    }

    private fun newVerifier(): String =
        base64Url(ByteArray(VERIFIER_BYTES).also { SecureRandom().nextBytes(it) })

    private fun challengeFor(verifier: String): String =
        base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    /**
     * Base64url with no padding, which is what the spec asks for. The `=`
     * padding is not merely optional here: a service that compares strings will
     * reject a challenge that carries it.
     */
    private fun base64Url(bytes: ByteArray): String =
        android.util.Base64.encodeToString(
            bytes,
            android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or
                android.util.Base64.NO_WRAP,
        )
}

/**
 * Catches the `wmkeyboard://oauth` redirect and gets out of the way.
 *
 * Invisible and immediately finished: the settings screen is what reacts, by
 * watching [BackupOAuth.result]. `singleTask`, so a redirect arriving while the
 * app is already open reaches this instance rather than stacking another.
 */
class OAuthRedirectActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        BackupOAuth.deliver(this, intent?.data)
        finish()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        BackupOAuth.deliver(this, intent?.data)
        finish()
    }
}
