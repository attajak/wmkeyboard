package com.wasimaster.wmkeyboard.app.oauth

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import com.wasimaster.wmkeyboard.core.settings.BackupDestination
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.settings.sink.BackupClients
import com.wasimaster.wmkeyboard.core.settings.sink.BackupLog
import com.wasimaster.wmkeyboard.core.settings.sink.DropboxSink
import com.wasimaster.wmkeyboard.core.settings.sink.OneDriveSink
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
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

    /** How the last sign-in ended, until the settings screen has said so. */
    val result: StateFlow<Result?> = _result

    data class Result(val destination: BackupDestination, val outcome: Outcome)

    enum class Outcome { SIGNED_IN, CANCELLED, FAILED }

    /**
     * The exchange runs here, in the process, rather than in the settings
     * row that started it. That row only exists while its screen is composed
     * and in front, and the browser hands back to whatever it likes: an
     * exchange that waited for the row could sit unused until the code
     * expired, with the user looking at "Not signed in yet".
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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

        BackupLog.d("oauth start $destination")
        return runCatching {
            activity.startActivity(Intent(Intent.ACTION_VIEW, url))
        }.onFailure { BackupLog.w("oauth start: no browser", it) }.isSuccess
    }

    /** Called by [OAuthRedirectActivity] when the browser comes back. */
    internal fun deliver(context: Context, uri: Uri?) {
        val saved = takePending(context)
        val code = uri?.getQueryParameter("code")
        BackupLog.d(
            "oauth redirect: code=${code != null} error=${uri?.getQueryParameter("error")} " +
                "pending memory=${pending != null} disk=${saved != null}",
        )
        val waiting = pending ?: saved ?: return
        pending = null
        val destination = waiting.destination
        if (code == null) {
            _result.value = Result(destination, Outcome.CANCELLED)
            return
        }
        val appContext = context.applicationContext
        scope.launch {
            val tokens = when (destination) {
                BackupDestination.DROPBOX -> BackupClients.dropbox()
                else -> BackupClients.oneDrive()
            }
            val refresh = tokens?.exchangeCode(code, waiting.verifier, REDIRECT_URI)
            if (refresh == null) {
                _result.value = Result(destination, Outcome.FAILED)
                return@launch
            }
            val repository = SettingsRepository(appContext)
            when (destination) {
                BackupDestination.DROPBOX -> repository.setAutoBackupDropboxToken(refresh)
                else -> repository.setAutoBackupOneDriveToken(refresh)
            }
            BackupLog.d("oauth $destination: refresh token stored")
            _result.value = Result(destination, Outcome.SIGNED_IN)
        }
    }

    /**
     * Puts the settings screen back in front of the browser.
     *
     * The redirect activity runs in a task of its own, so finishing it
     * returns to whatever was under it, which is the browser tab that just
     * said "you can close this". Moving the app's own task forward is what
     * the user expects, and only the activity in front may do it.
     */
    internal fun returnToApp(activity: Activity) {
        val manager = activity.getSystemService(ActivityManager::class.java)
        val task = runCatching {
            manager?.appTasks?.firstOrNull { it.taskInfo.baseActivity?.packageName == activity.packageName }
        }.getOrNull()
        if (task != null && runCatching { task.moveToFront() }.isSuccess) return
        activity.packageManager.getLaunchIntentForPackage(activity.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?.let { runCatching { activity.startActivity(it) } }
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
        BackupOAuth.returnToApp(this)
        finish()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        BackupOAuth.deliver(this, intent?.data)
        BackupOAuth.returnToApp(this)
        finish()
    }
}
