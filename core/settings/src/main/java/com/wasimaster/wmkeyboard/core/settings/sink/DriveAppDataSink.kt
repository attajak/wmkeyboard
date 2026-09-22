package com.wasimaster.wmkeyboard.core.settings.sink

import com.wasimaster.wmkeyboard.core.net.BackupTraffic
import com.wasimaster.wmkeyboard.core.net.NetLogInterceptor
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import com.wasimaster.wmkeyboard.core.util.runCancellable
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * Hands out an OAuth access token for `drive.appdata`, or nothing.
 *
 * The interface is here, in a module with no Google dependency, and the only
 * implementation that can actually produce a token lives behind the build's
 * Play services seam. That is the whole reason this is an interface: the Drive
 * REST API is ordinary HTTP that any build can speak, and the single part that
 * genuinely needs a proprietary library is getting the token.
 */
interface DriveTokenProvider {

    /**
     * A token that is valid now, or null when the user has not authorized this
     * app, has revoked it, or this build has no way to ask.
     *
     * Called from a background job, so it must not try to show anything. If
     * consent is needed, answer null and let the settings screen ask. When the
     * question could not be put at all (offline, Play services busy), throw
     * [BackupSinkException] with [SinkError.IO] rather than answer null: null
     * is reported to the user as a grant to renew.
     */
    suspend fun accessToken(): String?
}

/**
 * The process-wide token provider, set by `:app` at startup.
 *
 * A holder rather than a constructor parameter because the thing that builds
 * the sink is [com.wasimaster.wmkeyboard.core.settings.AutoBackupRunner], deep
 * in a library module, and the thing that can implement it is in `:app`. Same
 * shape as the other process singletons in this codebase.
 *
 * Null on a build without Play services, which is exactly what makes the Drive
 * destination report itself unavailable there rather than crash.
 */
object DriveAuth {

    @Volatile
    var provider: DriveTokenProvider? = null
}

/**
 * A [BackupSink] over the app's own hidden folder in the user's Google Drive.
 *
 * `appDataFolder` is a per-app, per-user space that does not appear in the
 * Drive UI alongside the user's documents. It cannot be tidied away by
 * accident, and the only scope it needs is `drive.appdata`, which grants no
 * sight of anything else in the account.
 *
 * Written against the REST API directly rather than through the Drive client
 * library. The requests are four ordinary HTTP calls, and the client library
 * would pull a large dependency tree into a module that otherwise has none, for
 * the benefit of turning those four calls into four other calls.
 *
 * There is no `.part` dance here: Drive publishes a file when the upload
 * completes, so a killed upload leaves nothing rather than a truncated
 * something. The caller still verifies before it rotates.
 */
class DriveAppDataSink(
    private val tokens: DriveTokenProvider,
) : BackupSink {

    override val id: String get() = ID

    private val json = Json { ignoreUnknownKeys = true }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addNetworkInterceptor(NetLogInterceptor(NetSource.BACKUP, NetLogInterceptor.PATH) { BackupTraffic.unattended })
            .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT_S, TimeUnit.SECONDS)
            .build()
    }

    private suspend fun authorized(url: String): Request.Builder {
        val token = tokens.accessToken()
            ?: throw BackupSinkException(SinkError.PERMISSION_LOST)
        return Request.Builder().url(url).header("Authorization", "Bearer $token")
    }

    override suspend fun readiness(): Result<Unit> = withContext(Dispatchers.IO) {
        runCancellable {
            // One page of one file. Cheap, and it proves both that the token is
            // good and that Drive is reachable, which is the whole contract.
            val url = FILES_URL.toHttpUrl().newBuilder()
                .addQueryParameter("spaces", APP_DATA_FOLDER)
                .addQueryParameter("pageSize", "1")
                .addQueryParameter("fields", "files(id)")
                .build()
                .toString()
            call(authorized(url).get().build()) { }
        }
    }

    override suspend fun write(
        name: String,
        mimeType: String,
        body: (OutputStream) -> Unit,
    ): Result<SinkEntry> = withContext(Dispatchers.IO) {
        runCancellable {
            val bytes = ByteArrayOutputStream().also(body).toByteArray()
            val metadata = json.encodeToString(
                JsonObject.serializer(),
                buildJsonObject {
                    put("name", JsonPrimitive(name))
                    put("parents", JsonArray(listOf(JsonPrimitive(APP_DATA_FOLDER))))
                },
            )
            val created = if (bytes.size <= MULTIPART_MAX) {
                uploadMultipart(metadata, mimeType, bytes)
            } else {
                uploadResumable(metadata, mimeType, bytes)
            }
            entryOf(runCatching { json.parseToJsonElement(created).jsonObject }.getOrNull())
                ?: throw BackupSinkException(SinkError.IO)
        }
    }

    /**
     * One request, metadata and bytes together.
     *
     * multipart/related, which is what uploadType=multipart means: the
     * metadata as JSON in the first part, the file in the second. Not
     * multipart/form-data, which is what a MultipartBody defaults to.
     */
    private suspend fun uploadMultipart(metadata: String, mimeType: String, bytes: ByteArray): String {
        val multipart = MultipartBody.Builder()
            .setType(RELATED)
            .addPart(metadata.toRequestBody(JSON_MEDIA_TYPE))
            .addPart(bytes.toRequestBody(mimeType.toMediaTypeOrNull()))
            .build()
        val url = UPLOAD_URL.toHttpUrl().newBuilder()
            .addQueryParameter("uploadType", "multipart")
            .addQueryParameter("fields", FILE_FIELDS)
            .build()
            .toString()
        return call(authorized(url).post(multipart).build()) { it.body?.string().orEmpty() }
    }

    /**
     * A resumable session, for anything over the 5 MB Drive accepts in a
     * multipart upload. A bundle with stickers or icons embedded passes that
     * easily.
     *
     * Sent as one chunk, as OneDrive's is: the session exists to allow
     * resuming, and a backup that fails is retried whole on the next run.
     */
    private suspend fun uploadResumable(metadata: String, mimeType: String, bytes: ByteArray): String {
        val start = UPLOAD_URL.toHttpUrl().newBuilder()
            .addQueryParameter("uploadType", "resumable")
            .addQueryParameter("fields", FILE_FIELDS)
            .build()
            .toString()
        val session = call(
            authorized(start)
                .header("X-Upload-Content-Type", mimeType)
                .header("X-Upload-Content-Length", bytes.size.toString())
                .post(metadata.toRequestBody(JSON_MEDIA_TYPE))
                .build(),
        ) { it.header("Location") }
            ?: throw BackupSinkException(SinkError.IO)
        // The session URI is the credential for the rest of the upload, but
        // Drive also accepts the bearer alongside it, unlike Graph.
        return call(
            authorized(session)
                .put(bytes.toRequestBody(mimeType.toMediaTypeOrNull()))
                .build(),
        ) { it.body?.string().orEmpty() }
    }

    override suspend fun list(): Result<List<SinkEntry>> = withContext(Dispatchers.IO) {
        runCancellable {
            val out = ArrayList<SinkEntry>()
            var pageToken: String? = null
            do {
                val url = FILES_URL.toHttpUrl().newBuilder()
                    .addQueryParameter("spaces", APP_DATA_FOLDER)
                    .addQueryParameter("pageSize", PAGE_SIZE)
                    .addQueryParameter("fields", "nextPageToken,files($FILE_FIELDS)")
                    .apply { pageToken?.let { addQueryParameter("pageToken", it) } }
                    .build()
                    .toString()

                val page = call(authorized(url).get().build()) { it.body?.string().orEmpty() }
                val root = runCatching { json.parseToJsonElement(page).jsonObject }.getOrNull()
                    ?: throw BackupSinkException(SinkError.IO)

                root["files"]?.jsonArray?.forEach { element ->
                    entryOf(element.jsonObject)
                        ?.takeIf { AutoBackupNaming.isOurs(it.name) }
                        ?.let(out::add)
                }
                pageToken = root["nextPageToken"]?.jsonPrimitive?.contentOrNull
            } while (!pageToken.isNullOrEmpty())
            out
        }
    }

    override suspend fun read(entry: SinkEntry): Result<InputStream> =
        withContext(Dispatchers.IO) {
            runCancellable {
                val url = "$FILES_URL/${entry.id}?alt=media"
                val bytes = call(authorized(url).get().build()) {
                    it.body?.bytes() ?: ByteArray(0)
                }
                bytes.inputStream()
            }
        }

    override suspend fun delete(entry: SinkEntry): Result<Unit> = withContext(Dispatchers.IO) {
        runCancellable {
            call(
                authorized("$FILES_URL/${entry.id}").delete().build(),
                allowMissing = true,
            ) { }
        }
    }

    private fun entryOf(file: JsonObject?): SinkEntry? {
        val id = file?.get("id")?.jsonPrimitive?.contentOrNull ?: return null
        val name = file["name"]?.jsonPrimitive?.contentOrNull ?: return null
        return SinkEntry(
            id = id,
            name = name,
            // Drive reports size as a string, and omits it for some file kinds.
            sizeBytes = file["size"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: -1L,
            modifiedAtMs = file["modifiedTime"]?.jsonPrimitive?.contentOrNull
                ?.let(::parseRfc3339) ?: 0L,
        )
    }

    private fun <T> call(
        request: Request,
        allowMissing: Boolean = false,
        read: (Response) -> T,
    ): T {
        val response = try {
            client.newCall(request).execute()
        } catch (failure: Throwable) {
            throw BackupSinkException(SinkError.IO, failure)
        }
        response.use {
            if (it.isSuccessful) return read(it)
            if (allowMissing && it.code == HTTP_NOT_FOUND) return read(it)
            val reason = if (it.code == HTTP_FORBIDDEN) errorReason(it) else null
            throw BackupSinkException(statusError(it.code, reason))
        }
    }

    /** The first `error.errors[].reason` of a Drive error body, if any. */
    private fun errorReason(response: Response): String? = runCatching {
        json.parseToJsonElement(response.body?.string().orEmpty()).jsonObject["error"]
            ?.jsonObject?.get("errors")?.jsonArray?.firstOrNull()
            ?.jsonObject?.get("reason")?.jsonPrimitive?.contentOrNull
    }.getOrNull()

    companion object {
        const val ID = "drive"

        /** The scope this sink needs, and the only one it should ever ask for. */
        const val SCOPE = "https://www.googleapis.com/auth/drive.appdata"

        /**
         * Drive answers 403 for three unrelated things and names which in the
         * body: a full account, a rate limit, and an actual refusal. Only the
         * last is the user's grant, so only the last says so.
         */
        fun statusError(code: Int, reason: String? = null): SinkError = when {
            code == HTTP_FORBIDDEN && reason in QUOTA_REASONS -> SinkError.OUT_OF_SPACE
            code == HTTP_FORBIDDEN && reason in RATE_REASONS -> SinkError.IO
            code == HTTP_UNAUTHORIZED || code == HTTP_FORBIDDEN -> SinkError.PERMISSION_LOST
            code == HTTP_NOT_FOUND -> SinkError.TARGET_MISSING
            code == HTTP_INSUFFICIENT_STORAGE -> SinkError.OUT_OF_SPACE
            else -> SinkError.IO
        }

        private val QUOTA_REASONS = setOf("storageQuotaExceeded")
        private val RATE_REASONS = setOf("userRateLimitExceeded", "rateLimitExceeded", "dailyLimitExceeded")

        /** Drive's documented ceiling for uploadType=multipart. */
        private const val MULTIPART_MAX = 5 * 1024 * 1024

        private const val APP_DATA_FOLDER = "appDataFolder"
        private const val FILES_URL = "https://www.googleapis.com/drive/v3/files"
        private const val UPLOAD_URL = "https://www.googleapis.com/upload/drive/v3/files"
        private const val FILE_FIELDS = "id,name,size,modifiedTime"
        private const val PAGE_SIZE = "200"

        private const val CONNECT_TIMEOUT_S = 15L
        private const val READ_TIMEOUT_S = 30L
        private const val WRITE_TIMEOUT_S = 120L

        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_FORBIDDEN = 403
        private const val HTTP_NOT_FOUND = 404
        private const val HTTP_INSUFFICIENT_STORAGE = 507

        private val RELATED = "multipart/related".toMediaType()
        private val JSON_MEDIA_TYPE = "application/json; charset=UTF-8".toMediaTypeOrNull()

        /**
         * Drive stamps `modifiedTime` as RFC 3339 in UTC, for example
         * `2026-08-07T14:02:33.123Z`.
         *
         * Not `java.time.Instant.parse`, which is API 26 and this app supports
         * 24 with no desugaring. Not the whole string either: the fractional
         * seconds are dropped first, so a server that sends a different number
         * of decimal places still parses. Only ordering matters here, so a date
         * that will not parse becomes 0 and rotation falls back to the name,
         * exactly as it does for a WebDAV server that reports no time at all.
         */
        fun parseRfc3339(value: String): Long = runCatching {
            val seconds = value.substringBefore('.').substringBefore('Z').trimEnd('Z')
            SimpleDateFormat(RFC_3339_SECONDS, Locale.US)
                .apply { timeZone = TimeZone.getTimeZone("UTC") }
                .parse(seconds)
                ?.time
                ?: 0L
        }.getOrDefault(0L)

        private const val RFC_3339_SECONDS = "yyyy-MM-dd'T'HH:mm:ss"
    }
}
