package com.wasimaster.wmkeyboard.core.fonts

import android.content.Context
import com.wasimaster.wmkeyboard.core.endpoints.ServiceEndpoints
import com.wasimaster.wmkeyboard.core.endpoints.ServiceRepo
import com.wasimaster.wmkeyboard.core.netlog.NetLog
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Fonts a shipped layout draws its keys in that no phone has, fetched from the
 * data repository the first time that layout is shown.
 *
 * A layout names its face as `installed:<name>` (`LayoutAppearance.fontId`),
 * the same id an add-on font is reached by, so once the file is in the
 * [FontStore] nothing about it is special. What this adds is getting it
 * there: when that id finds no installed font and the name is one listed
 * here, the keyboard asks for it ([request]) and draws the keys in the
 * fallback face until it lands, at which point [installs] moves and the board
 * picks it up.
 *
 * Today that is Klingon pIqaD, whose letters live in the Private Use Area
 * (the ConScript registry's U+F8D0–U+F8FF) and so in no system font at all.
 * [Entry.sha256] pins each file, so a mirror [ServiceRepo.DATA] points at
 * cannot serve a different one.
 */
object LayoutFonts {

    class Entry(
        val name: String,
        val author: String,
        val version: String,
        /** Inside the data repository. */
        val repoPath: String,
        val sizeBytes: Long,
        val sha256: String,
    ) {
        val url: String get() = ServiceEndpoints.repo(ServiceRepo.DATA).rawUrl(repoPath)
    }

    /** Klingon pIqaD HaSta, SIL OFL 1.1, for the Klingon pIqaD layout. */
    val KLINGON_PIQAD = Entry(
        name = "Klingon pIqaD HaSta",
        author = "Mike Neff, Michael Everson",
        version = "2.003",
        repoPath = "fonts/tlh/klingon-piqad-hasta.ttf",
        sizeBytes = 99_920L,
        sha256 = "b71d452bdebf7f92e338d594a0a05f143e879011f8ced7191f5ce516e5ca1e09",
    )

    private val entries = listOf(KLINGON_PIQAD)

    /** The entry installed under [name], or null for a font this does not fetch. */
    fun byName(name: String): Entry? = entries.firstOrNull { it.name.equals(name, ignoreCase = true) }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Names being fetched, and names that failed in this process and are not asked for again. */
    private val running = ConcurrentHashMap.newKeySet<String>()
    private val failed = ConcurrentHashMap.newKeySet<String>()

    private val _installs = MutableStateFlow(0)

    /**
     * Moves each time one of these fonts is installed, for the keyboard to
     * resolve its faces again: the font id it already holds is unchanged, and
     * only what it points at has appeared.
     */
    val installs: StateFlow<Int> = _installs.asStateFlow()

    /**
     * Fetches and installs [entry] in the background, unless it is on its way
     * or failed already in this process: this is asked on every resolve of a
     * face that is not there yet, and offline that is every frame the layout
     * is up. The next process tries again.
     */
    fun request(context: Context, entry: Entry) {
        if (entry.name in failed || !running.add(entry.name)) return
        val app = context.applicationContext
        scope.launch {
            val installed = runCatching { fetchAndInstall(app, entry) }.getOrDefault(false)
            running.remove(entry.name)
            if (installed) _installs.value++ else failed.add(entry.name)
        }
    }

    private fun fetchAndInstall(context: Context, entry: Entry): Boolean {
        val store = FontStore.get(context)
        if (store.textFonts().any { it.name.equals(entry.name, ignoreCase = true) }) return true
        val bytes = fetch(entry) ?: return false
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        if (digest != entry.sha256) return false
        val result = FontFile.import(
            input = ByteArrayInputStream(bytes),
            store = store,
            name = entry.name,
            author = entry.author,
            version = entry.version,
        )
        return result is FontImportResult.Imported
    }

    /** The file's bytes, or null for any answer that is not exactly the file. */
    private fun fetch(entry: Entry): ByteArray? {
        val url = entry.url
        val connection = URL(url).openConnection() as HttpURLConnection
        val netCall = NetLog.call(NetSource.DOWNLOAD_FONT, "GET", url, route = NetLog.pathOf(url))
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", USER_AGENT)
            netCall.status = connection.responseCode
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            val out = ByteArrayOutputStream(entry.sizeBytes.toInt())
            netCall.countIn(connection.inputStream).use { input ->
                val buffer = ByteArray(BUFFER_BYTES)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    // A server sending more than the file is not sending the file.
                    if (out.size() + read > entry.sizeBytes) return null
                    out.write(buffer, 0, read)
                }
            }
            return out.toByteArray().takeIf { it.size.toLong() == entry.sizeBytes }
        } catch (t: Throwable) {
            netCall.fail(t)
            throw t
        } finally {
            connection.disconnect()
            netCall.end()
        }
    }

    private const val USER_AGENT = "WMKeyboard layout font downloader"
    private const val TIMEOUT_MS = 15_000
    private const val BUFFER_BYTES = 16 * 1024
}
