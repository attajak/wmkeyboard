package com.wasimaster.wmkeyboard.core.fonts

import android.content.Context
import androidx.annotation.StringRes
import com.wasimaster.wmkeyboard.content.R
import com.wasimaster.wmkeyboard.core.endpoints.ServiceEndpoints
import com.wasimaster.wmkeyboard.core.endpoints.ServiceRepo
import com.wasimaster.wmkeyboard.core.netlog.NetLog
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.FilterInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * The one emoji font the app will fetch by itself.
 *
 * Two things ship the same file. It is published as an `emoji_font` add-on, so
 * it shows up in the add-on catalogue with its licence and its preview beside
 * every other face; and it is named here, so the emoji page can offer it in
 * one tap without asking anyone to add a repository first. The add-on route is
 * the general answer and this is the shortcut to the one font almost everybody
 * who lands on that page actually wants.
 *
 * Why it exists at all: [com.wasimaster.wmkeyboard.core.settings.EmojiFontChoice.NOTO]
 * asks Google's downloadable-font provider for "Noto Color Emoji", and a
 * downloadable font is requested by family name — there is no version in the
 * request and no way to ask for the current one. The provider serves whatever
 * build it has, which on a phone with a recent system emoji font is the older
 * of the two. Fetching the file directly is the only way to be sure which
 * build the user gets.
 */
object EmojiFontCatalog {

    /**
     * Where the file lives. The same path the add-on entry points at, so the
     * two routes cannot drift into shipping different fonts.
     */
    val NOTO_URL: String get() = ServiceEndpoints.repo(ServiceRepo.ADDONS).rawUrl("fonts/noto-color-emoji.ttf")

    /**
     * A few hundred bytes beside the font saying which build it is.
     *
     * The font itself is ten megabytes, so "is there a newer one?" cannot be
     * answered by fetching it. The repository manifest holds the same version,
     * but that file is the whole catalogue and parsing it belongs to the
     * add-on layer; this is the same fact in a form this module can read on
     * its own. Both are written by the same publish, so they cannot disagree.
     */
    val NOTO_MANIFEST_URL: String get() = ServiceEndpoints.repo(ServiceRepo.ADDONS).rawUrl("fonts/noto-color-emoji.json")

    /** Name the installed font is listed under. */
    const val NOTO_NAME: String = "Noto Color Emoji"

    const val NOTO_AUTHOR: String = "Google"

    /**
     * Rough transfer size, for the row that offers the download before the
     * manifest lands. Ten megabytes on whatever connection the user is on.
     */
    const val NOTO_APPROX_BYTES: Long = 10L * 1024 * 1024

    /**
     * Whether [font] is this catalogue's font. Matched on the name, allowing
     * for the " (2)" the store appends to a duplicate — the id is minted per
     * install, so it cannot be the thing that identifies the face.
     */
    fun isNoto(font: InstalledFont): Boolean =
        font.emoji && (font.name == NOTO_NAME || font.name.startsWith("$NOTO_NAME ("))
}

/**
 * Fetches [EmojiFontCatalog]'s font and installs it into the [FontStore], so
 * it becomes selectable as an installed emoji face.
 *
 * One download at a time, one state flow for the UI, and no retry policy: this
 * only ever runs because someone pressed a button, so a failure belongs on
 * screen rather than in a queue.
 */
object EmojiFontDownload {

    sealed interface Status {
        data object Idle : Status

        /** [total] is 0 until the server states a length. */
        data class Downloading(val bytes: Long, val total: Long) : Status

        /** The store id of the installed face — what the setting points at. */
        data class Installed(val fontId: String, val name: String) : Status

        data class Failed(@StringRes val messageRes: Int) : Status
    }

    /** What the repository is publishing right now. */
    data class Published(val version: String, val sizeBytes: Long, val url: String)

    private const val USER_AGENT = "WMKeyboard emoji font downloader"
    private const val PROGRESS_INTERVAL_MS = 200L

    /** A manifest bigger than this is not a manifest. */
    private const val MAX_MANIFEST_BYTES = 8 * 1024

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    private val _state = MutableStateFlow<Status>(Status.Idle)
    val state: StateFlow<Status> = _state.asStateFlow()

    private val _published = MutableStateFlow<Published?>(null)

    /** The published build, once [checkPublished] has found out; null before. */
    val published: StateFlow<Published?> = _published.asStateFlow()

    /** True while a fetch is in flight, so the row can disable its button. */
    val running: Boolean get() = job?.isActive == true

    /**
     * Reads the small manifest beside the font, so the caller can tell an
     * installed copy from a newer publish.
     *
     * Quiet on every failure: not knowing whether there is an update is the
     * normal offline state, and it must not turn into an error on a settings
     * screen nobody opened for this. Fetched once per process.
     */
    suspend fun checkPublished(): Published? = withContext(Dispatchers.IO) {
        _published.value?.let { return@withContext it }
        val text = runCatching {
            val connection = URL(EmojiFontCatalog.NOTO_MANIFEST_URL)
                .openConnection() as HttpURLConnection
            val netCall = NetLog.call(
                NetSource.DOWNLOAD_FONT,
                "GET",
                EmojiFontCatalog.NOTO_MANIFEST_URL,
                route = NetLog.pathOf(EmojiFontCatalog.NOTO_MANIFEST_URL),
            )
            try {
                connection.connectTimeout = CONNECT_TIMEOUT_MS
                connection.readTimeout = CONNECT_TIMEOUT_MS
                connection.instanceFollowRedirects = true
                connection.setRequestProperty("User-Agent", USER_AGENT)
                netCall.status = connection.responseCode
                if (connection.responseCode != HttpURLConnection.HTTP_OK) return@runCatching null
                netCall.countIn(connection.inputStream).use { it.readBoundedBytes(MAX_MANIFEST_BYTES) }
                    .toString(Charsets.UTF_8)
            } catch (t: Throwable) {
                netCall.fail(t)
                throw t
            } finally {
                connection.disconnect()
                netCall.end()
            }
        }.getOrNull() ?: return@withContext null
        val parsed = runCatching {
            val json = JSONObject(text)
            Published(
                version = json.optString("version"),
                sizeBytes = json.optLong("sizeBytes", EmojiFontCatalog.NOTO_APPROX_BYTES),
                // A manifest that names its own file wins, so the font can be
                // moved without shipping an app update.
                url = json.optString("url").ifBlank { EmojiFontCatalog.NOTO_URL },
            )
        }.getOrNull()?.takeIf { it.version.isNotBlank() } ?: return@withContext null
        _published.value = parsed
        parsed
    }

    /**
     * Starts the download, or does nothing when one is already running.
     *
     * The caller points the setting at [Status.Installed.fontId] itself rather
     * than this doing it: the settings repository lives above this module, and
     * "put the font on disk" and "start using it" are separately useful.
     */
    @Synchronized
    fun start(context: Context) {
        if (running) return
        val app = context.applicationContext
        _state.value = Status.Downloading(0, EmojiFontCatalog.NOTO_APPROX_BYTES)
        job = scope.launch {
            _state.value = runCatching { fetchAndInstall(app, checkPublished()) }
                .getOrElse { Status.Failed(R.string.core_content_font_error_read) }
        }
    }

    /** Clears a finished or failed run, so the row goes back to offering it. */
    @Synchronized
    fun reset() {
        if (running) return
        _state.value = Status.Idle
    }

    private fun fetchAndInstall(context: Context, published: Published?): Status {
        val url = published?.url ?: EmojiFontCatalog.NOTO_URL
        val connection = URL(url).openConnection() as HttpURLConnection
        val netCall = NetLog.call(NetSource.DOWNLOAD_FONT, "GET", url, route = NetLog.pathOf(url))
        try {
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", USER_AGENT)
            netCall.status = connection.responseCode
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                return Status.Failed(R.string.core_content_font_error_download)
            }
            val total = connection.contentLengthLong.takeIf { it > 0 }
                ?: published?.sizeBytes
                ?: EmojiFontCatalog.NOTO_APPROX_BYTES
            val store = FontStore.get(context)
            // Noted before the import, because the import is what makes the
            // new copy exist and the old one has to go after it succeeds — a
            // failed update must leave the working font alone.
            val previous = store.emojiFonts().filter(EmojiFontCatalog::isNoto).map { it.id }
            // Streamed straight into the importer, which is what enforces the
            // size ceiling and checks that the bytes really are a font before
            // any of them are registered.
            val result = netCall.countIn(connection.inputStream).buffered().reporting(total).use { stream ->
                FontFile.import(
                    input = stream,
                    store = store,
                    name = EmojiFontCatalog.NOTO_NAME,
                    author = EmojiFontCatalog.NOTO_AUTHOR,
                    // The version is what a later run compares against, so an
                    // install with no manifest is deliberately left blank
                    // rather than guessed: unknown reads as "check again".
                    version = published?.version.orEmpty(),
                    emoji = true,
                )
            }
            return when (result) {
                is FontImportResult.Imported -> {
                    previous.forEach(store::delete)
                    Status.Installed(result.font.id, result.font.name)
                }
                is FontImportResult.TooManyFonts ->
                    Status.Failed(R.string.core_content_font_error_too_many)
                is FontImportResult.NotAFont -> Status.Failed(result.messageRes)
                is FontImportResult.Failed -> Status.Failed(result.messageRes)
            }
        } catch (t: Throwable) {
            netCall.fail(t)
            throw t
        } finally {
            connection.disconnect()
            netCall.end()
        }
    }

    /** Wraps the body so the progress row moves while the bytes arrive. */
    private fun InputStream.reporting(total: Long): InputStream = object : FilterInputStream(this) {
        private var read = 0L
        private var lastPost = 0L

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val count = super.read(b, off, len)
            if (count > 0) {
                read += count
                val now = System.currentTimeMillis()
                if (now - lastPost >= PROGRESS_INTERVAL_MS) {
                    lastPost = now
                    _state.value = Status.Downloading(read, total)
                }
            }
            return count
        }
    }

    /**
     * Reads at most [limit] bytes. Android has no `InputStream.readNBytes`
     * before API 33 and this app runs back to 24, so the bound is applied by
     * hand — the manifest read must not trust the server for its size.
     */
    private fun InputStream.readBoundedBytes(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (out.size() < limit) {
            val n = read(buffer, 0, minOf(buffer.size, limit - out.size()))
            if (n <= 0) break
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 60_000
}
