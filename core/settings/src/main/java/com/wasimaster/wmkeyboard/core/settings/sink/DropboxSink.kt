package com.wasimaster.wmkeyboard.core.settings.sink

import com.wasimaster.wmkeyboard.core.net.BackupTraffic
import com.wasimaster.wmkeyboard.core.net.InternetGate
import com.wasimaster.wmkeyboard.core.net.NetLogInterceptor
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import com.wasimaster.wmkeyboard.core.util.runCancellable
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
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
 * A [BackupSink] over the app's own folder in the user's Dropbox.
 *
 * Registered with the **App Folder** scope, not full Dropbox access, so every
 * path here is relative to a folder Dropbox creates for this app and nothing
 * outside it is visible. The user sees that folder in their Dropbox and can
 * delete it, which is a difference from Google Drive's hidden `appDataFolder`
 * and on balance a fair one: it is their storage.
 *
 * Two hosts, which is Dropbox's design rather than a mistake here: metadata
 * calls go to `api.dropboxapi.com` with a JSON body, and the two that move
 * bytes go to `content.dropboxapi.com` with the arguments in a header instead.
 */
class DropboxSink(
    private val refreshToken: String,
    private val tokens: OAuthTokens,
) : BackupSink {

    override val id: String get() = ID

    private val json = Json { ignoreUnknownKeys = true }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor(InternetGate)
            .addNetworkInterceptor(NetLogInterceptor(NetSource.BACKUP, NetLogInterceptor.PATH) { BackupTraffic.unattended })
            .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT_S, TimeUnit.SECONDS)
            .build()
    }

    private fun bearer(): String = tokens.accessToken(refreshToken)
        ?: throw BackupSinkException(SinkError.PERMISSION_LOST)
            .also { BackupLog.w("dropbox: refresh refused (token empty=${refreshToken.isEmpty()})") }

    private fun rpc(endpoint: String, body: JsonObject): Request = Request.Builder()
        .url("$API/$endpoint")
        .header("Authorization", "Bearer ${bearer()}")
        .post(
            json.encodeToString(JsonObject.serializer(), body)
                .toRequestBody(JSON_MEDIA_TYPE),
        )
        .build()

    override suspend fun readiness(): Result<Unit> = withContext(Dispatchers.IO) {
        runCancellable {
            // Listing the app folder root is the cheapest call that proves both
            // the token and the folder.
            call(
                rpc(
                    "files/list_folder",
                    buildJsonObject {
                        put("path", JsonPrimitive(""))
                        put("limit", JsonPrimitive(1))
                    },
                ),
            ) { }
        }
    }

    override suspend fun write(
        name: String,
        mimeType: String,
        body: (OutputStream) -> Unit,
    ): Result<SinkEntry> = withContext(Dispatchers.IO) {
        runCancellable {
            val bytes = ByteArrayOutputStream().also(body).toByteArray()
            BackupLog.d("dropbox write $name ${bytes.size} B")
            val arg = json.encodeToString(
                JsonObject.serializer(),
                buildJsonObject {
                    put("path", JsonPrimitive("/$name"))
                    // A file of this name is an older generation of ours, so
                    // replacing it is the intent rather than a collision.
                    put("mode", JsonPrimitive("overwrite"))
                    put("mute", JsonPrimitive(true))
                },
            )
            val request = Request.Builder()
                .url("$CONTENT/files/upload")
                .header("Authorization", "Bearer ${bearer()}")
                .header("Dropbox-API-Arg", arg)
                .post(bytes.toRequestBody(OCTET_STREAM))
                .build()
            val created = call(request) { it.body?.string().orEmpty() }
            entryOf(runCatching { json.parseToJsonElement(created).jsonObject }.getOrNull())
                ?: SinkEntry(name, name, bytes.size.toLong(), 0L)
        }
    }

    override suspend fun list(): Result<List<SinkEntry>> = withContext(Dispatchers.IO) {
        runCancellable {
            val out = ArrayList<SinkEntry>()
            var cursor: String? = null
            do {
                val request = if (cursor == null) {
                    rpc("files/list_folder", buildJsonObject { put("path", JsonPrimitive("")) })
                } else {
                    rpc(
                        "files/list_folder/continue",
                        buildJsonObject { put("cursor", JsonPrimitive(cursor)) },
                    )
                }
                val page = call(request) { it.body?.string().orEmpty() }
                val root = runCatching { json.parseToJsonElement(page).jsonObject }.getOrNull()
                    ?: throw BackupSinkException(SinkError.IO)

                root["entries"]?.jsonArray?.forEach { element ->
                    entryOf(element.jsonObject)
                        ?.takeIf { AutoBackupNaming.isListed(it.name) }
                        ?.let(out::add)
                }
                cursor = if (root["has_more"]?.jsonPrimitive?.contentOrNull == "true") {
                    root["cursor"]?.jsonPrimitive?.contentOrNull
                } else {
                    null
                }
            } while (!cursor.isNullOrEmpty())
            out
        }
    }

    override suspend fun read(entry: SinkEntry): Result<InputStream> =
        withContext(Dispatchers.IO) {
            runCancellable {
                val arg = json.encodeToString(
                    JsonObject.serializer(),
                    buildJsonObject { put("path", JsonPrimitive("/${entry.name}")) },
                )
                val request = Request.Builder()
                    .url("$CONTENT/files/download")
                    .header("Authorization", "Bearer ${bearer()}")
                    .header("Dropbox-API-Arg", arg)
                    // Dropbox insists this be a POST with no body at all.
                    .post(ByteArray(0).toRequestBody(null))
                    .build()
                call(request) { it.body?.bytes() ?: ByteArray(0) }.inputStream()
            }
        }

    override suspend fun delete(entry: SinkEntry): Result<Unit> = withContext(Dispatchers.IO) {
        // Explicitly Unit so the discarded runCatching below does not become
        // the return value: already gone is the outcome rotation wanted.
        runCancellable<Unit> {
            runCatching {
                call(
                    rpc(
                        "files/delete_v2",
                        buildJsonObject { put("path", JsonPrimitive("/${entry.name}")) },
                    ),
                ) { }
            }
            // Dropbox reports a missing path as a 409 with a not_found tag
            // rather than a 404, so the failure is swallowed rather than mapped.
        }
    }

    private fun entryOf(file: JsonObject?): SinkEntry? {
        val name = file?.get("name")?.jsonPrimitive?.contentOrNull ?: return null
        if (file["tag"]?.jsonPrimitive?.contentOrNull == "folder") return null
        if (file[".tag"]?.jsonPrimitive?.contentOrNull == "folder") return null
        return SinkEntry(
            id = file["path_lower"]?.jsonPrimitive?.contentOrNull ?: name,
            name = name,
            sizeBytes = file["size"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: -1L,
            modifiedAtMs = file["server_modified"]?.jsonPrimitive?.contentOrNull
                ?.let(S3Listing::parseIso8601) ?: 0L,
        )
    }

    private fun <T> call(request: Request, read: (Response) -> T): T {
        val what = request.url.encodedPath
        val response = try {
            client.newCall(request).execute()
        } catch (failure: Throwable) {
            BackupLog.w("dropbox $what failed to connect", failure)
            throw BackupSinkException(SinkError.IO, failure)
        }
        response.use {
            BackupLog.d("dropbox $what -> ${it.code}")
            if (it.isSuccessful) return read(it)
            val summary = if (it.code == HTTP_CONFLICT) errorSummary(it) else null
            val error = statusError(it.code, summary)
            BackupLog.w("dropbox $what -> ${it.code} summary=$summary => $error")
            throw BackupSinkException(error)
        }
    }

    private fun errorSummary(response: Response): String? = runCatching {
        json.parseToJsonElement(response.body?.string().orEmpty())
            .jsonObject["error_summary"]?.jsonPrimitive?.contentOrNull
    }.getOrNull()

    companion object {
        const val ID = "dropbox"

        /**
         * Dropbox answers 409 for every endpoint-specific error and names which
         * in `error_summary`, for example `path/insufficient_space/..`. A full
         * account and a missing path are different sentences to the user, and
         * `too_many_write_operations` is neither: it is contention, and passes.
         */
        fun statusError(code: Int, summary: String? = null): SinkError = when {
            code == HTTP_UNAUTHORIZED || code == HTTP_FORBIDDEN -> SinkError.PERMISSION_LOST
            code == HTTP_CONFLICT && summary?.contains("insufficient_space") == true -> SinkError.OUT_OF_SPACE
            code == HTTP_CONFLICT && summary?.contains("not_found") == true -> SinkError.TARGET_MISSING
            code == HTTP_CONFLICT && summary == null -> SinkError.TARGET_MISSING
            code == HTTP_NOT_FOUND -> SinkError.TARGET_MISSING
            code == HTTP_QUOTA -> SinkError.OUT_OF_SPACE
            else -> SinkError.IO
        }

        const val TOKEN_URL = "https://api.dropbox.com/oauth2/token"
        const val AUTHORIZE_URL = "https://www.dropbox.com/oauth2/authorize"

        private const val API = "https://api.dropboxapi.com/2"
        private const val CONTENT = "https://content.dropboxapi.com/2"

        private const val CONNECT_TIMEOUT_S = 15L
        private const val READ_TIMEOUT_S = 30L
        private const val WRITE_TIMEOUT_S = 120L

        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_FORBIDDEN = 403
        private const val HTTP_NOT_FOUND = 404
        private const val HTTP_CONFLICT = 409
        private const val HTTP_QUOTA = 507

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaTypeOrNull()
        private val OCTET_STREAM = "application/octet-stream".toMediaTypeOrNull()
    }
}
