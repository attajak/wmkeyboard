package com.wasimaster.wmkeyboard.core.settings.sink

import com.wasimaster.wmkeyboard.core.net.BackupTraffic
import com.wasimaster.wmkeyboard.core.net.InternetGate
import com.wasimaster.wmkeyboard.core.net.NetLogInterceptor
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import com.wasimaster.wmkeyboard.core.settings.GitConfig
import com.wasimaster.wmkeyboard.core.settings.GitProvider
import com.wasimaster.wmkeyboard.core.util.runCancellable
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * A [BackupSink] over a folder in a Git repository, through the host's REST
 * API: GitHub (and GitHub Enterprise), GitLab, and Gitea or Forgejo, which
 * Codeberg runs.
 *
 * No Git client and no clone. Each verb is one or two HTTPS calls, and each
 * write or delete is a commit the host makes on the branch. The history keeps
 * every backup ever written, deleted or not, which is why
 * [GitConfig.allowPublic] is off by default and a public repository is
 * refused with [SinkError.UNSAFE].
 *
 * There is no temporary name: the host publishes a file in the same commit
 * that writes it, so a failed upload leaves nothing. The caller still reads a
 * backup back before rotating.
 */
class GitSink(
    private val config: GitConfig,
    /** This device's label, for `{device}` in the commit message. */
    private val device: String = "",
    private val nowMs: () -> Long = System::currentTimeMillis,
) : BackupSink {

    override val id: String get() = ID

    private val json = Json { ignoreUnknownKeys = true }

    private val api: String = apiBase(config.provider, config.server)
    private val repo: String = config.repository.trim().trim('/').removeSuffix(".git")
    private val folder: String = config.path.trim().trim('/')

    /** The branch, once known. The configured one, or the repository's default. */
    private var branch: String = config.branch.trim()

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor(InternetGate)
            .addNetworkInterceptor(NetLogInterceptor(NetSource.BACKUP, NetLogInterceptor.PATH) { BackupTraffic.unattended })
            .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT_S, TimeUnit.SECONDS)
            .build()
    }

    private fun pathOf(name: String): String = if (folder.isEmpty()) name else "$folder/$name"

    // ---- The five verbs ----

    override suspend fun readiness(): Result<Unit> = withContext(Dispatchers.IO) {
        runCancellable { checkRepository() }
    }

    override suspend fun write(
        name: String,
        mimeType: String,
        body: (OutputStream) -> Unit,
    ): Result<SinkEntry> = withContext(Dispatchers.IO) {
        runCancellable {
            val bytes = ByteArrayOutputStream().also(body).toByteArray()
            checkRepository()
            val existing = listFolder().firstOrNull { it.name == name }
            val sha = try {
                put(name, bytes, existing)
            } catch (expected: Conflict) {
                // Someone committed in between: another device, or a sync on
                // this one. Look again and go once more.
                put(name, bytes, listFolder().firstOrNull { it.name == name })
            }
            SinkEntry(id = entryId(pathOf(name), sha), name = name, sizeBytes = bytes.size.toLong(), modifiedAtMs = 0L)
        }
    }

    override suspend fun list(): Result<List<SinkEntry>> = withContext(Dispatchers.IO) {
        runCancellable {
            checkRepository()
            listFolder().filter { AutoBackupNaming.isListed(it.name) }
        }
    }

    override suspend fun read(entry: SinkEntry): Result<InputStream> = withContext(Dispatchers.IO) {
        runCancellable {
            checkRepository()
            val path = entry.id.substringBefore('\n')
            val url = when (config.provider) {
                GitProvider.GITHUB -> "$api/repos/$repo/contents/${encodePath(path)}?ref=${query(branch)}"
                GitProvider.GITLAB -> "$api/projects/${query(repo)}/repository/files/${query(path)}/raw?ref=${query(branch)}"
                GitProvider.GITEA -> "$api/repos/$repo/raw/${encodePath(path)}?ref=${query(branch)}"
            }
            val request = authorized(url).header("Accept", RAW_ACCEPT).get().build()
            call(request) { it.body?.bytes() ?: ByteArray(0) }.inputStream()
        }
    }

    override suspend fun delete(entry: SinkEntry): Result<Unit> = withContext(Dispatchers.IO) {
        runCancellable {
            checkRepository()
            try {
                remove(entry)
            } catch (expected: Conflict) {
                val fresh = listFolder().firstOrNull { it.name == entry.name } ?: return@runCancellable
                remove(fresh)
            }
        }
    }

    // ---- Repository ----

    private var checked = false

    /**
     * That the repository exists, the token can see it, it is private (or the
     * user allowed otherwise), and which branch to use. Once per sink.
     */
    private fun checkRepository() {
        if (checked) return
        if (!config.configured) throw BackupSinkException(SinkError.NOT_CONFIGURED)
        val url = when (config.provider) {
            GitProvider.GITHUB, GitProvider.GITEA -> "$api/repos/$repo"
            GitProvider.GITLAB -> "$api/projects/${query(repo)}"
        }
        val info = call(authorized(url).get().build()) { parse(it) }.jsonObject
        val public = when (config.provider) {
            GitProvider.GITLAB -> info.str("visibility") != "private"
            GitProvider.GITHUB, GitProvider.GITEA -> info["private"]?.jsonPrimitive?.booleanOrNull == false
        }
        if (public && !config.allowPublic) {
            BackupLog.w("git: $repo is not private; refusing")
            throw BackupSinkException(SinkError.UNSAFE)
        }
        val canPush = info["permissions"]?.jsonObject?.get("push")?.jsonPrimitive?.booleanOrNull
        if (canPush == false) throw BackupSinkException(SinkError.PERMISSION_LOST)
        if (branch.isEmpty()) branch = info.str("default_branch").ifEmpty { "main" }
        checked = true
    }

    /** The files in the folder, all of them; the callers filter. Empty when the folder is not there yet. */
    private fun listFolder(): List<SinkEntry> {
        val out = ArrayList<SinkEntry>()
        when (config.provider) {
            GitProvider.GITHUB, GitProvider.GITEA -> {
                val url = if (folder.isEmpty()) {
                    "$api/repos/$repo/contents?ref=${query(branch)}"
                } else {
                    "$api/repos/$repo/contents/${encodePath(folder)}?ref=${query(branch)}"
                }
                val listing = call(authorized(url).get().build(), missingOk = true) { if (it.code == HTTP_NOT_FOUND) null else parse(it) }
                    ?: return emptyList()
                for (item in listing.jsonArray) {
                    val o = item.jsonObject
                    if (o.str("type") != "file") continue
                    out += SinkEntry(
                        id = entryId(o.str("path"), o.str("sha")),
                        name = o.str("name"),
                        sizeBytes = o["size"]?.jsonPrimitive?.longOrNull ?: -1L,
                        modifiedAtMs = 0L,
                    )
                }
            }
            GitProvider.GITLAB -> {
                var page = "1"
                while (page.isNotEmpty()) {
                    val url = "$api/projects/${query(repo)}/repository/tree?ref=${query(branch)}&per_page=100&page=$page" +
                        (if (folder.isEmpty()) "" else "&path=${query(folder)}")
                    var next = ""
                    val listing = call(authorized(url).get().build(), missingOk = true) {
                        next = it.header("X-Next-Page").orEmpty()
                        if (it.code == HTTP_NOT_FOUND) null else parse(it)
                    } ?: return emptyList()
                    for (item in listing.jsonArray) {
                        val o = item.jsonObject
                        if (o.str("type") != "blob") continue
                        out += SinkEntry(
                            id = entryId(o.str("path"), o.str("id")),
                            name = o.str("name"),
                            sizeBytes = -1L,
                            modifiedAtMs = 0L,
                        )
                    }
                    page = next
                }
            }
        }
        return out
    }

    /** Commits [bytes] as [name], replacing [existing] if there is one. Returns the new blob id. */
    private fun put(name: String, bytes: ByteArray, existing: SinkEntry?): String {
        val path = pathOf(name)
        val content = JavaBase64Compat.encode(bytes)
        val message = message(if (existing == null) "Add" else "Update", name)
        return when (config.provider) {
            GitProvider.GITHUB, GitProvider.GITEA -> {
                val body = buildJsonObject {
                    put("message", JsonPrimitive(message))
                    put("content", JsonPrimitive(content))
                    put("branch", JsonPrimitive(branch))
                    existing?.let { put("sha", JsonPrimitive(shaOf(it))) }
                    author()?.let {
                        put("author", it)
                        put("committer", it)
                    }
                }
                val url = "$api/repos/$repo/contents/${encodePath(path)}"
                val builder = authorized(url)
                val request = when {
                    // Gitea creates with POST and updates with PUT; GitHub does both with PUT.
                    config.provider == GitProvider.GITEA && existing == null -> builder.post(jsonBody(body))
                    else -> builder.put(jsonBody(body))
                }.build()
                val reply = call(request) { parse(it) }.jsonObject
                reply["content"]?.jsonObject?.str("sha").orEmpty()
            }
            GitProvider.GITLAB -> {
                val body = buildJsonObject {
                    put("branch", JsonPrimitive(branch))
                    put("content", JsonPrimitive(content))
                    put("encoding", JsonPrimitive("base64"))
                    put("commit_message", JsonPrimitive(message))
                    if (config.authorName.isNotBlank() && config.authorEmail.isNotBlank()) {
                        put("author_name", JsonPrimitive(config.authorName.trim()))
                        put("author_email", JsonPrimitive(config.authorEmail.trim()))
                    }
                }
                val url = "$api/projects/${query(repo)}/repository/files/${query(path)}"
                val builder = authorized(url)
                val request = (if (existing == null) builder.post(jsonBody(body)) else builder.put(jsonBody(body))).build()
                call(request) { }
                // GitLab answers with the path and branch only; the blob id
                // comes from the next listing, and nothing needs it before then.
                ""
            }
        }
    }

    private fun remove(entry: SinkEntry) {
        val path = entry.id.substringBefore('\n')
        val message = message("Delete", entry.name)
        val request = when (config.provider) {
            GitProvider.GITHUB, GitProvider.GITEA -> {
                val body = buildJsonObject {
                    put("message", JsonPrimitive(message))
                    put("sha", JsonPrimitive(shaOf(entry)))
                    put("branch", JsonPrimitive(branch))
                    author()?.let {
                        put("author", it)
                        put("committer", it)
                    }
                }
                authorized("$api/repos/$repo/contents/${encodePath(path)}").delete(jsonBody(body)).build()
            }
            GitProvider.GITLAB -> authorized(
                "$api/projects/${query(repo)}/repository/files/${query(path)}" +
                    "?branch=${query(branch)}&commit_message=${query(message)}",
            ).delete().build()
        }
        call(request, missingOk = true) { }
    }

    private fun author(): JsonObject? {
        val name = config.authorName.trim()
        val email = config.authorEmail.trim()
        if (name.isEmpty() || email.isEmpty()) return null
        return buildJsonObject {
            put("name", JsonPrimitive(name))
            put("email", JsonPrimitive(email))
        }
    }

    private fun message(action: String, file: String): String =
        commitMessage(config, action, file, device, nowMs())

    private fun shaOf(entry: SinkEntry): String = entry.id.substringAfter('\n', "")

    // ---- HTTP ----

    /** A write that lost a race with another commit. Retried once by the caller. */
    private class Conflict : RuntimeException()

    private fun authorized(url: String): Request.Builder = Request.Builder().url(url).apply {
        header("User-Agent", USER_AGENT)
        when (config.provider) {
            GitProvider.GITHUB -> {
                header("Authorization", "Bearer ${config.token.trim()}")
                header("Accept", "application/vnd.github+json")
                header("X-GitHub-Api-Version", GITHUB_API_VERSION)
            }
            GitProvider.GITLAB -> header("PRIVATE-TOKEN", config.token.trim())
            GitProvider.GITEA -> {
                header("Authorization", "token ${config.token.trim()}")
                header("Accept", "application/json")
            }
        }
    }

    private fun <T> call(request: Request, missingOk: Boolean = false, read: (Response) -> T): T {
        val what = "${request.method} ${request.url.encodedPath}"
        val response = try {
            client.newCall(request).execute()
        } catch (failure: Throwable) {
            BackupLog.w("git $what failed to connect", failure)
            throw BackupSinkException(SinkError.IO, failure)
        }
        response.use {
            BackupLog.d("git $what -> ${it.code}")
            if (it.isSuccessful) return read(it)
            if (missingOk && it.code == HTTP_NOT_FOUND) return read(it)
            val writing = request.method != "GET"
            if (writing && (it.code == HTTP_CONFLICT || (it.code == HTTP_UNPROCESSABLE && isShaMismatch(it)))) {
                throw Conflict()
            }
            val error = statusError(it.code, it.header("X-RateLimit-Remaining"))
            BackupLog.w("git $what -> ${it.code} => $error: ${it.peekBody(ERROR_PEEK).string()}")
            throw BackupSinkException(error)
        }
    }

    private fun isShaMismatch(response: Response): Boolean =
        response.peekBody(ERROR_PEEK).string().contains("sha", ignoreCase = true)

    private fun parse(response: Response): JsonElement =
        runCatching { json.parseToJsonElement(response.body?.string().orEmpty()) }.getOrNull()
            ?: throw BackupSinkException(SinkError.IO)

    private fun jsonBody(o: JsonObject) = o.toString().toRequestBody(JSON_MEDIA_TYPE)

    private fun JsonObject.str(key: String): String = this[key]?.jsonPrimitive?.contentOrNull.orEmpty()

    companion object {
        const val ID = "git"

        private const val USER_AGENT = "WMKeyboard"
        private const val GITHUB_API_VERSION = "2022-11-28"
        private const val RAW_ACCEPT = "application/vnd.github.raw"
        private const val CONNECT_TIMEOUT_S = 15L
        private const val READ_TIMEOUT_S = 60L
        private const val WRITE_TIMEOUT_S = 120L
        private const val ERROR_PEEK = 512L

        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_FORBIDDEN = 403
        private const val HTTP_NOT_FOUND = 404
        private const val HTTP_CONFLICT = 409
        private const val HTTP_UNPROCESSABLE = 422
        private const val HTTP_TOO_MANY = 429
        private const val HTTP_INSUFFICIENT_STORAGE = 507

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        private fun entryId(path: String, sha: String) = "$path\n$sha"

        /** What a status means. A 403 with no requests left is GitHub's rate limit, not a refusal. */
        fun statusError(code: Int, rateRemaining: String? = null): SinkError = when {
            code == HTTP_FORBIDDEN && rateRemaining == "0" -> SinkError.IO
            code == HTTP_TOO_MANY -> SinkError.IO
            code == HTTP_UNAUTHORIZED || code == HTTP_FORBIDDEN -> SinkError.PERMISSION_LOST
            code == HTTP_NOT_FOUND -> SinkError.TARGET_MISSING
            code == HTTP_INSUFFICIENT_STORAGE -> SinkError.OUT_OF_SPACE
            else -> SinkError.IO
        }

        /** `https://host` with no trailing slash, from whatever the user typed. */
        fun normalizeServer(server: String): String {
            val s = server.trim().trimEnd('/')
            if (s.isEmpty()) return ""
            return if (s.startsWith("https://", true) || s.startsWith("http://", true)) s else "https://$s"
        }

        /** The REST API root for [provider] on [server]. */
        fun apiBase(provider: GitProvider, server: String): String {
            val host = normalizeServer(server).ifEmpty { provider.defaultServer }
            return when (provider) {
                GitProvider.GITHUB ->
                    if (host.equals("https://github.com", ignoreCase = true)) "https://api.github.com" else "$host/api/v3"
                GitProvider.GITLAB -> "$host/api/v4"
                GitProvider.GITEA -> "$host/api/v1"
            }
        }

        /**
         * A repository address pasted whole, `https://github.com/me/backups.git`
         * or `git@codeberg.org:me/backups.git`, as server and `owner/name`.
         * Null for anything that is not one.
         */
        fun parseRemote(address: String): Pair<String, String>? {
            val s = address.trim().removeSuffix("/").removeSuffix(".git")
            Regex("^git@([^:]+):(.+/.+)$").matchEntire(s)?.let { m ->
                return "https://${m.groupValues[1]}" to m.groupValues[2]
            }
            val m = Regex("^(https?://[^/]+)/(.+/[^/]+)$", RegexOption.IGNORE_CASE).matchEntire(s) ?: return null
            // A web address inside the repository ("/tree/main/backups") is not the repository.
            val path = m.groupValues[2].substringBefore("/-/").substringBefore("/tree/").substringBefore("/src/")
            if (path.count { it == '/' } < 1) return null
            return m.groupValues[1] to path
        }

        /** The commit message for [action] on [file], with the user's template and `[skip ci]`. */
        fun commitMessage(config: GitConfig, action: String, file: String, device: String, nowMs: Long): String {
            val date = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(nowMs))
            val text = config.message.ifBlank { GitConfig.DEFAULT_MESSAGE }
                .replace("{action}", action)
                .replace("{file}", file)
                .replace("{device}", device)
                .replace("{date}", date)
                .trim()
            return if (config.skipCi && !text.contains("[skip ci]")) "$text [skip ci]" else text
        }

        /** Each segment percent-encoded, the slashes between them kept. */
        private fun encodePath(path: String): String = path.split('/').joinToString("/") { query(it) }

        private fun query(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    }
}
