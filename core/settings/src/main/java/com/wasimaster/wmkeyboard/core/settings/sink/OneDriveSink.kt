package com.wasimaster.wmkeyboard.core.settings.sink

import com.wasimaster.wmkeyboard.core.net.BackupTraffic
import com.wasimaster.wmkeyboard.core.net.NetLogInterceptor
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import com.wasimaster.wmkeyboard.core.util.runCancellable
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * A [BackupSink] over the app's own folder in the user's OneDrive.
 *
 * Uses the `Files.ReadWrite.AppFolder` scope and Microsoft Graph's `approot`,
 * which is the same bargain as Dropbox's App Folder: a folder made for this app,
 * visible to the user under Apps, and no sight of anything else in the drive.
 *
 * Graph is the most ordinary REST of the six destinations — a `PUT` to a path
 * uploads, a `GET` on children lists — so there is little to say beyond the
 * bits where it differs: item ids rather than names address a file once it
 * exists, and the upload path needs the odd `:/name:/content` punctuation that
 * separates a path from an action.
 */
class OneDriveSink(
    refreshToken: String,
    private val tokens: OAuthTokens,
    /** Stores the refresh token Microsoft sent in place of the old one; see [OAuthTokens.accessToken]. */
    private val onRefreshTokenRotated: (String) -> Unit = {},
) : BackupSink {

    @Volatile
    private var refreshToken: String = refreshToken

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

    private fun bearer(): String =
        tokens.accessToken(refreshToken, onRotated = { rotated ->
            refreshToken = rotated
            onRefreshTokenRotated(rotated)
        }) ?: throw BackupSinkException(SinkError.PERMISSION_LOST)

    private fun authorized(url: String): Request.Builder =
        Request.Builder().url(url).header("Authorization", "Bearer ${bearer()}")

    override suspend fun readiness(): Result<Unit> = withContext(Dispatchers.IO) {
        runCancellable {
            call(authorized("$APP_ROOT?\$select=id").get().build()) { }
        }
    }

    override suspend fun write(
        name: String,
        mimeType: String,
        body: (OutputStream) -> Unit,
    ): Result<SinkEntry> = withContext(Dispatchers.IO) {
        runCancellable {
            val bytes = ByteArrayOutputStream().also(body).toByteArray()
            // Simple upload, which Graph caps at 4 MB. Above that it wants a
            // resumable session, so a large bundle needs the other path.
            val created = if (bytes.size <= SIMPLE_UPLOAD_MAX) {
                call(
                    authorized("$APP_ROOT:/${escape(name)}:/content")
                        .put(bytes.toRequestBody(mimeType.toMediaTypeOrNull()))
                        .build(),
                ) { it.body?.string().orEmpty() }
            } else {
                uploadLarge(name, bytes)
            }
            entryOf(runCatching { json.parseToJsonElement(created).jsonObject }.getOrNull())
                ?: SinkEntry(name, name, bytes.size.toLong(), 0L)
        }
    }

    /**
     * An upload session, for anything over Graph's 4 MB simple-upload cap.
     *
     * Sent in [CHUNK] pieces, because Graph refuses a single request over
     * 60 MiB and a bundle with stickers in it can pass that. Each piece but
     * the last must be a multiple of 320 KiB. Nothing resumes a failed
     * session: a backup that fails is retried whole on the next run, and half
     * a bundle on the server is worse than none.
     */
    private fun uploadLarge(name: String, bytes: ByteArray): String {
        val session = call(
            authorized("$APP_ROOT:/${escape(name)}:/createUploadSession")
                // Replace, as the simple upload does: a sync file keeps its name.
                .post(SESSION_BODY.toRequestBody(JSON_MEDIA_TYPE))
                .build(),
        ) { it.body?.string().orEmpty() }
        val uploadUrl = runCatching {
            json.parseToJsonElement(session).jsonObject["uploadUrl"]?.jsonPrimitive?.contentOrNull
        }.getOrNull() ?: throw BackupSinkException(SinkError.IO)

        var start = 0
        var last = ""
        while (start < bytes.size) {
            val end = minOf(start + CHUNK, bytes.size)
            last = call(
                Request.Builder()
                    .url(uploadUrl)
                    // No Authorization: the session URL carries its own credential,
                    // and Graph rejects the request if both are present.
                    .header("Content-Range", "bytes $start-${end - 1}/${bytes.size}")
                    .put(bytes.toRequestBody(OCTET_STREAM, start, end - start))
                    .build(),
            ) { it.body?.string().orEmpty() }
            start = end
        }
        // The last piece's answer is the finished item.
        return last
    }

    override suspend fun list(): Result<List<SinkEntry>> = withContext(Dispatchers.IO) {
        runCancellable {
            val out = ArrayList<SinkEntry>()
            var url: String? = "$APP_ROOT/children?\$select=$SELECT&\$top=$PAGE_SIZE"
            while (!url.isNullOrEmpty()) {
                val page = call(authorized(url).get().build()) { it.body?.string().orEmpty() }
                val root = runCatching { json.parseToJsonElement(page).jsonObject }.getOrNull()
                    ?: throw BackupSinkException(SinkError.IO)
                root["value"]?.jsonArray?.forEach { element ->
                    entryOf(element.jsonObject)
                        ?.takeIf { AutoBackupNaming.isListed(it.name) }
                        ?.let(out::add)
                }
                url = root["@odata.nextLink"]?.jsonPrimitive?.contentOrNull
            }
            out
        }
    }

    override suspend fun read(entry: SinkEntry): Result<InputStream> =
        withContext(Dispatchers.IO) {
            runCancellable {
                call(authorized("$ITEMS/${entry.id}/content").get().build()) {
                    it.body?.bytes() ?: ByteArray(0)
                }.inputStream()
            }
        }

    override suspend fun delete(entry: SinkEntry): Result<Unit> = withContext(Dispatchers.IO) {
        runCancellable<Unit> {
            val response = runCatching {
                call(authorized("$ITEMS/${entry.id}").delete().build()) { }
            }
            // Already gone is success: rotation reruns after a failure. Any
            // other failure is real and is rethrown.
            response.exceptionOrNull()
                ?.let { it as? BackupSinkException }
                ?.takeIf { it.reason != SinkError.TARGET_MISSING }
                ?.let { throw it }
        }
    }

    private fun entryOf(item: JsonObject?): SinkEntry? {
        val id = item?.get("id")?.jsonPrimitive?.contentOrNull ?: return null
        val name = item["name"]?.jsonPrimitive?.contentOrNull ?: return null
        if (item["folder"] != null) return null
        return SinkEntry(
            id = id,
            name = name,
            sizeBytes = item["size"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: -1L,
            modifiedAtMs = item["lastModifiedDateTime"]?.jsonPrimitive?.contentOrNull
                ?.let(S3Listing::parseIso8601) ?: 0L,
        )
    }

    /** A path segment, with the space encoding a path wants rather than `+`. */
    private fun escape(name: String): String =
        URLEncoder.encode(name, "UTF-8").replace("+", "%20")

    private fun <T> call(request: Request, read: (Response) -> T): T {
        val response = try {
            client.newCall(request).execute()
        } catch (failure: Throwable) {
            throw BackupSinkException(SinkError.IO, failure)
        }
        response.use {
            if (it.isSuccessful) return read(it)
            throw BackupSinkException(statusError(it.code))
        }
    }

    private fun statusError(code: Int): SinkError = when (code) {
        HTTP_UNAUTHORIZED, HTTP_FORBIDDEN -> SinkError.PERMISSION_LOST
        HTTP_NOT_FOUND -> SinkError.TARGET_MISSING
        HTTP_QUOTA -> SinkError.OUT_OF_SPACE
        else -> SinkError.IO
    }

    companion object {
        const val ID = "onedrive"

        const val TOKEN_URL = "https://login.microsoftonline.com/common/oauth2/v2.0/token"
        const val AUTHORIZE_URL = "https://login.microsoftonline.com/common/oauth2/v2.0/authorize"

        /**
         * The app folder, and `offline_access` so the sign-in returns a refresh
         * token. Without that last one every backup after the first four hours
         * would fail with nothing to say why.
         */
        const val SCOPE = "Files.ReadWrite.AppFolder offline_access"

        private const val GRAPH = "https://graph.microsoft.com/v1.0/me/drive"
        private const val APP_ROOT = "$GRAPH/special/approot"
        private const val ITEMS = "$GRAPH/items"
        private const val SELECT = "id,name,size,lastModifiedDateTime,folder"
        private const val PAGE_SIZE = 200

        /** Graph's cap on a plain PUT. Above it an upload session is required. */
        private const val SIMPLE_UPLOAD_MAX = 4 * 1024 * 1024

        /** One upload-session piece: 32 × 320 KiB, 10 MiB, well under Graph's 60 MiB per request. */
        internal const val CHUNK = 32 * 320 * 1024

        private const val SESSION_BODY = """{"item":{"@microsoft.graph.conflictBehavior":"replace"}}"""

        private const val CONNECT_TIMEOUT_S = 15L
        private const val READ_TIMEOUT_S = 30L
        private const val WRITE_TIMEOUT_S = 120L

        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_FORBIDDEN = 403
        private const val HTTP_NOT_FOUND = 404
        private const val HTTP_QUOTA = 507

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaTypeOrNull()
        private val OCTET_STREAM = "application/octet-stream".toMediaTypeOrNull()
    }
}
