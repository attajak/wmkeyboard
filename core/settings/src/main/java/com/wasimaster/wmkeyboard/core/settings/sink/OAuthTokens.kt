package com.wasimaster.wmkeyboard.core.settings.sink

import com.wasimaster.wmkeyboard.core.net.BackupTraffic
import com.wasimaster.wmkeyboard.core.net.NetLogInterceptor
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Turns a long-lived refresh token into a short-lived access token, and
 * remembers the answer until it expires.
 *
 * Both Dropbox and Microsoft issue access tokens that last a few hours, which
 * is useless to a backup that runs once a day: by the time it wakes up the
 * token it was given is always dead. What gets stored is therefore the refresh
 * token, and this is what trades it for a usable one at the moment of use.
 *
 * The cache is per process and in memory only. It saves a round trip when a
 * run does several calls; it is not something to persist, because the whole
 * point of the short-lived token is that it does not sit on disk.
 */
class OAuthTokens(
    private val tokenUrl: String,
    private val clientId: String,
    private val extraParams: Map<String, String> = emptyMap(),
) {

    private val json = Json { ignoreUnknownKeys = true }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addNetworkInterceptor(NetLogInterceptor(NetSource.BACKUP, NetLogInterceptor.PATH) { BackupTraffic.unattended })
            .connectTimeout(TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_S, TimeUnit.SECONDS)
            .build()
    }

    @Volatile
    private var cached: String? = null

    @Volatile
    private var cachedFor: String? = null

    @Volatile
    private var expiresAtMs: Long = 0L

    /**
     * An access token for [refreshToken], or null when the refresh was refused.
     *
     * A refusal is not transient: it means the user revoked the app, or changed
     * their password, and no amount of retrying will help. The sink turns that
     * into [SinkError.PERMISSION_LOST], which is the one the settings screen
     * tells the user to act on.
     *
     * Not being able to *ask* is a different thing, and throws
     * [BackupSinkException] with [SinkError.IO] instead: no network, a timeout,
     * or the service having a bad minute. Folding those into null told a user
     * whose phone was offline at backup time to sign in again.
     */
    fun accessToken(refreshToken: String, nowMs: Long = System.currentTimeMillis()): String? {
        if (refreshToken.isEmpty() || clientId.isEmpty()) return null
        val hit = cached
        if (hit != null && cachedFor == refreshToken && nowMs < expiresAtMs) return hit

        val form = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
            .add("client_id", clientId)
            .apply { for ((name, value) in extraParams) add(name, value) }
            .build()

        val response = try {
            client.newCall(Request.Builder().url(tokenUrl).post(form).build()).execute()
        } catch (failure: java.io.IOException) {
            throw BackupSinkException(SinkError.IO, failure)
        }
        val body = response.use {
            when {
                it.isSuccessful -> it.body?.string()
                isRefusal(it.code) -> return null
                else -> throw BackupSinkException(SinkError.IO)
            }
        } ?: throw BackupSinkException(SinkError.IO)

        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: throw BackupSinkException(SinkError.IO)
        val token = root["access_token"]?.jsonPrimitive?.contentOrNull ?: return null
        val lifetime = root["expires_in"]?.jsonPrimitive?.intOrNull ?: DEFAULT_LIFETIME_S

        cached = token
        cachedFor = refreshToken
        // A minute of slack, so a token does not expire between the check and
        // the request it was fetched for.
        expiresAtMs = nowMs + (lifetime - SLACK_S).coerceAtLeast(0) * 1000L
        return token
    }

    /**
     * Trades the code a sign-in returned for a refresh token, or null.
     *
     * The half of PKCE that proves this is the same app that started the flow:
     * [codeVerifier] is the secret generated before the browser opened, and the
     * service checks it against the challenge it was given then. No client
     * secret is involved, which is what makes this safe to do in an app that
     * anyone can unpack.
     */
    fun exchangeCode(code: String, codeVerifier: String, redirectUri: String): String? {
        if (clientId.isEmpty()) return null
        val form = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("code_verifier", codeVerifier)
            .add("client_id", clientId)
            .add("redirect_uri", redirectUri)
            .apply { for ((name, value) in extraParams) add(name, value) }
            .build()

        val body = runCatching {
            client.newCall(Request.Builder().url(tokenUrl).post(form).build()).execute()
                .use { if (it.isSuccessful) it.body?.string() else null }
        }.getOrNull() ?: return null

        return runCatching {
            json.parseToJsonElement(body).jsonObject["refresh_token"]?.jsonPrimitive?.contentOrNull
        }.getOrNull()
    }

    private companion object {
        /**
         * The answers that mean the grant itself is bad. OAuth reports
         * `invalid_grant` and `invalid_client` as 400, some servers as 401;
         * anything else, a 429 or a 5xx included, is worth another go later.
         */
        fun isRefusal(code: Int): Boolean = code == HTTP_BAD_REQUEST || code == HTTP_UNAUTHORIZED

        const val HTTP_BAD_REQUEST = 400
        const val HTTP_UNAUTHORIZED = 401
        const val TIMEOUT_S = 20L
        const val DEFAULT_LIFETIME_S = 3600
        const val SLACK_S = 60
    }
}
