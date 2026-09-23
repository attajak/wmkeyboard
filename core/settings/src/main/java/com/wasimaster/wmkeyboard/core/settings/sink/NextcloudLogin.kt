package com.wasimaster.wmkeyboard.core.settings.sink

import com.wasimaster.wmkeyboard.core.net.NetLogInterceptor
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Nextcloud's Login Flow v2: the user signs in on their own server's page in
 * the browser, and the app is handed an app password for itself, never the
 * account password. The password then shows up, and can be revoked, under
 * Security in the user's Nextcloud settings, named after [USER_AGENT].
 *
 * Two calls. [start] asks the server for a login page and a poll token;
 * [await] polls until the user has approved in the browser, or gives up.
 */
object NextcloudLogin {

    /** What [start] got: the page to open, and where to wait for the answer. */
    class Started(val loginUrl: String, val pollUrl: String, val token: String)

    class Credentials(val server: String, val loginName: String, val appPassword: String)

    /** Nextcloud names the app password after this. */
    const val USER_AGENT = "WM Keyboard (Android)"

    private const val POLL_EVERY_MS = 2_000L
    private const val GIVE_UP_MS = 20 * 60 * 1000L

    private val json = Json { ignoreUnknownKeys = true }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addNetworkInterceptor(NetLogInterceptor(NetSource.BACKUP, NetLogInterceptor.PATH) { false })
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /** Null when the server does not answer like a Nextcloud, or is not https. */
    suspend fun start(server: String): Started? = withContext(Dispatchers.IO) {
        val base = "https://" + server.trim().removePrefix("https://").removePrefix("http://").trimEnd('/')
        val request = Request.Builder()
            .url("$base/index.php/login/v2")
            .header("User-Agent", USER_AGENT)
            .post(ByteArray(0).toRequestBody())
            .build()
        runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val root = json.parseToJsonElement(response.body?.string().orEmpty()).jsonObject
                val poll = root["poll"]?.jsonObject ?: return@use null
                val login = root["login"]?.jsonPrimitive?.contentOrNull ?: return@use null
                val endpoint = poll["endpoint"]?.jsonPrimitive?.contentOrNull ?: return@use null
                val token = poll["token"]?.jsonPrimitive?.contentOrNull ?: return@use null
                if (!login.startsWith("https://") || !endpoint.startsWith("https://")) return@use null
                Started(login, endpoint, token)
            }
        }.onFailure { BackupLog.w("nextcloud login start failed", it) }.getOrNull()
    }

    /**
     * Polls until the user approves, which answers 200 with the credentials.
     * Until then the server answers 404. Null after twenty minutes, or when
     * the server stops answering like it should.
     */
    suspend fun await(started: Started): Credentials? = withContext(Dispatchers.IO) {
        val deadline = System.currentTimeMillis() + GIVE_UP_MS
        while (System.currentTimeMillis() < deadline) {
            val request = Request.Builder()
                .url(started.pollUrl)
                .header("User-Agent", USER_AGENT)
                .post(FormBody.Builder().add("token", started.token).build())
                .build()
            val answer = runCatching {
                client.newCall(request).execute().use { response ->
                    when {
                        response.code == 404 -> null
                        response.isSuccessful -> {
                            val root = json.parseToJsonElement(response.body?.string().orEmpty()).jsonObject
                            Credentials(
                                server = root["server"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                                loginName = root["loginName"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                                appPassword = root["appPassword"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                            )
                        }
                        else -> null
                    }
                }
            }.getOrNull()
            if (answer != null && answer.loginName.isNotEmpty() && answer.appPassword.isNotEmpty()) return@withContext answer
            delay(POLL_EVERY_MS)
        }
        null
    }
}
