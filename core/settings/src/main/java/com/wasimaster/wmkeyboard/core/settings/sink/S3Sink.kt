package com.wasimaster.wmkeyboard.core.settings.sink

import com.wasimaster.wmkeyboard.core.net.BackupTraffic
import com.wasimaster.wmkeyboard.core.net.NetLogInterceptor
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import com.wasimaster.wmkeyboard.core.settings.S3Config
import com.wasimaster.wmkeyboard.core.util.runCancellable
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * A [BackupSink] over a bucket on anything that speaks the S3 API.
 *
 * One sink for AWS, MinIO on a machine at home, Cloudflare R2, Backblaze B2,
 * Wasabi and the rest, because they all implement the same four calls and the
 * same signature. No OAuth and no account of ours: the credentials are a key
 * pair the user already has, and the only thing this app has to get right is
 * [AwsSigV4].
 *
 * There is no atomic install here and none is needed: S3 makes an object
 * visible when the `PUT` completes, so a killed upload leaves nothing rather
 * than a truncated something. The caller verifies before it rotates anyway.
 */
class S3Sink(private val config: S3Config) : BackupSink {

    override val id: String get() = ID

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addNetworkInterceptor(NetLogInterceptor(NetSource.BACKUP, NetLogInterceptor.PATH) { BackupTraffic.unattended })
            .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT_S, TimeUnit.SECONDS)
            .build()
    }

    /** The host part, without a scheme, derived from the region on AWS. */
    private val host: String
        get() {
            val endpoint = config.endpoint.trim().ifEmpty {
                "https://s3.${config.region}.amazonaws.com"
            }
            val bare = endpoint.substringAfter("://").trimEnd('/')
            return if (config.pathStyle) bare else "${config.bucket}.$bare"
        }

    private val scheme: String
        get() = if (config.endpoint.startsWith("http://", ignoreCase = true)) "http" else "https"

    /** Keys are prefixed so backups can share a bucket with something else. */
    private fun keyFor(name: String): String {
        val prefix = config.prefix.trim('/')
        return if (prefix.isEmpty()) name else "$prefix/$name"
    }

    /** The signed path. Under path style the bucket is the first segment. */
    private fun pathFor(key: String): String {
        val encoded = AwsSigV4.encode(key, encodeSlash = false)
        return if (config.pathStyle) "/${config.bucket}/$encoded" else "/$encoded"
    }

    private fun signed(
        method: String,
        path: String,
        query: Map<String, String> = emptyMap(),
        bodySha: String = AwsSigV4.EMPTY_BODY_SHA256,
        body: okhttp3.RequestBody? = null,
        extraHeaders: Map<String, String> = emptyMap(),
    ): Request {
        val headers = AwsSigV4.sign(
            AwsSigV4.Request(
                method = method,
                path = path,
                query = query,
                headers = mapOf("host" to host) + extraHeaders,
                bodySha256 = bodySha,
            ),
            accessKeyId = config.accessKeyId,
            secretAccessKey = config.secretAccessKey,
            region = config.region,
            timestampMs = System.currentTimeMillis(),
        )
        val url = buildString {
            append(scheme).append("://").append(host).append(path)
            if (query.isNotEmpty()) {
                append('?')
                append(
                    query.toSortedMap().entries.joinToString("&") { (k, v) ->
                        "${AwsSigV4.encode(k)}=${AwsSigV4.encode(v)}"
                    },
                )
            }
        }
        return Request.Builder()
            .url(url)
            .method(method, body)
            .apply {
                for ((name, value) in headers) header(name, value)
                for ((name, value) in extraHeaders) header(name, value)
            }
            .build()
    }

    override suspend fun readiness(): Result<Unit> = withContext(Dispatchers.IO) {
        runCancellable {
            if (config.bucket.isEmpty() ||
                config.accessKeyId.isEmpty() ||
                config.secretAccessKey.isEmpty()
            ) {
                throw BackupSinkException(SinkError.NOT_CONFIGURED)
            }
            // One key, which proves the bucket exists and the signature is
            // accepted without pulling a listing of it.
            call(
                signed(
                    "GET",
                    if (config.pathStyle) "/${config.bucket}" else "/",
                    query = mapOf("list-type" to "2", "max-keys" to "1"),
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
            val key = keyFor(name)
            // S3 signs the body, so it has to be hashed before it is sent. That
            // rules out streaming the upload without the chunked-signature
            // scheme, which is a great deal of protocol for a file this size.
            val sha = AwsSigV4.hex(AwsSigV4.sha256(bytes))
            call(
                signed(
                    method = "PUT",
                    path = pathFor(key),
                    bodySha = sha,
                    body = bytes.toRequestBody(mimeType.toMediaTypeOrNull()),
                ),
            ) { }
            SinkEntry(id = key, name = name, sizeBytes = bytes.size.toLong(), modifiedAtMs = 0L)
        }
    }

    override suspend fun list(): Result<List<SinkEntry>> = withContext(Dispatchers.IO) {
        runCancellable {
            val out = ArrayList<SinkEntry>()
            var token: String? = null
            do {
                val query = buildMap {
                    put("list-type", "2")
                    put("max-keys", PAGE_SIZE)
                    config.prefix.trim('/').takeIf { it.isNotEmpty() }?.let { put("prefix", "$it/") }
                    token?.let { put("continuation-token", it) }
                }
                val xml = call(
                    signed(
                        "GET",
                        if (config.pathStyle) "/${config.bucket}" else "/",
                        query = query,
                    ),
                ) { it.body?.string().orEmpty() }

                val page = S3Listing.parse(xml)
                for (item in page.keys) {
                    val name = item.key.substringAfterLast('/')
                    if (!AutoBackupNaming.isOurs(name)) continue
                    out += SinkEntry(
                        id = item.key,
                        name = name,
                        sizeBytes = item.sizeBytes,
                        modifiedAtMs = item.modifiedAtMs,
                    )
                }
                token = page.continuationToken
            } while (!token.isNullOrEmpty())
            out
        }
    }

    override suspend fun read(entry: SinkEntry): Result<InputStream> =
        withContext(Dispatchers.IO) {
            runCancellable {
                val bytes = call(signed("GET", pathFor(entry.id))) {
                    it.body?.bytes() ?: ByteArray(0)
                }
                bytes.inputStream()
            }
        }

    override suspend fun delete(entry: SinkEntry): Result<Unit> = withContext(Dispatchers.IO) {
        runCancellable {
            // S3 answers 204 for a key that was never there, so nothing extra
            // is needed to make this idempotent.
            call(signed("DELETE", pathFor(entry.id))) { }
        }
    }

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
        const val ID = "s3"

        private const val PAGE_SIZE = "200"
        private const val CONNECT_TIMEOUT_S = 15L
        private const val READ_TIMEOUT_S = 30L
        private const val WRITE_TIMEOUT_S = 120L

        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_FORBIDDEN = 403
        private const val HTTP_NOT_FOUND = 404
        private const val HTTP_QUOTA = 507

        /** Whether an endpoint is one this sink will send credentials to. */
        fun isCleartext(endpoint: String): Boolean =
            endpoint.trim().startsWith("http://", ignoreCase = true)

        /** Lower-cases and trims a region, which servers are picky about. */
        fun normalizeRegion(region: String): String =
            region.trim().lowercase(Locale.US).ifEmpty { "us-east-1" }
    }
}
