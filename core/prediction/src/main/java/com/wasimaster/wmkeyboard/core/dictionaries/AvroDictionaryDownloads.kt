package com.wasimaster.wmkeyboard.core.dictionaries

import com.wasimaster.wmkeyboard.core.endpoints.ServiceEndpoints
import com.wasimaster.wmkeyboard.core.endpoints.ServiceRepo
import com.wasimaster.wmkeyboard.core.netlog.NetLog
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Desktop Avro's dictionary (ibus-avro's `avrodict.js`, MPL-1.1), fetched from
 * the data repo for the Avro candidate list and kept as the repo serves it, at
 * `dict/bn/avro_dictionary.txt.gz`. The rest of Avro's data is small and ships
 * in `assets/avro/`; the dictionary is most of its weight and only the people
 * who turn the candidate list on need it.
 *
 * The file is checked (it has to inflate and hold at least one table) before
 * the `.part` is renamed into place, so presence means valid, as for the
 * wordlists. [completions] tells the keyboard to read it.
 */
object AvroDictionaryDownloads {

    sealed interface Status {
        data object NotDownloaded : Status
        data object Downloading : Status
        data class Downloaded(val sizeBytes: Long) : Status
        data object Failed : Status
    }

    /** Roughly what the download costs, for the row that offers it. */
    const val APPROX_BYTES = 580_000L

    private const val FILE_NAME = "avro_dictionary.txt.gz"
    private const val REPO_PATH = "data/bn/bn_avro_dictionary.txt.gz"
    private const val USER_AGENT = "WMKeyboard Avro dictionary downloader"

    /** Far above the real file; a response past it is not this file. */
    private const val MAX_BYTES = 8L * 1024 * 1024

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    private val _status = MutableStateFlow<Status>(Status.NotDownloaded)
    val status: StateFlow<Status> = _status.asStateFlow()

    private val _completions = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Emits once a dictionary has landed (or been removed) and should be re-read. */
    val completions: SharedFlow<Unit> = _completions.asSharedFlow()

    fun file(filesDir: File): File = File(File(File(filesDir, "dict"), "bn"), FILE_NAME)

    fun isDownloaded(filesDir: File): Boolean = file(filesDir).isFile

    /** Seeds [status] from disk; call when the row appears. */
    fun refresh(filesDir: File) {
        if (_status.value == Status.Downloading) return
        val file = file(filesDir)
        _status.value = if (file.isFile) Status.Downloaded(file.length()) else {
            if (_status.value == Status.Failed) Status.Failed else Status.NotDownloaded
        }
    }

    /** Fetches the dictionary unless it is here or on its way. */
    fun start(filesDir: File) {
        synchronized(this) {
            if (job?.isActive == true) return
            if (isDownloaded(filesDir)) {
                _status.value = Status.Downloaded(file(filesDir).length())
                return
            }
            _status.value = Status.Downloading
            job = scope.launch {
                val ok = runCatching { download(filesDir) }.isSuccess
                _status.value = if (ok) Status.Downloaded(file(filesDir).length()) else Status.Failed
                if (ok) _completions.tryEmit(Unit)
            }
        }
    }

    fun delete(filesDir: File) {
        synchronized(this) { job?.cancel() }
        file(filesDir).delete()
        File(file(filesDir).path + ".part").delete()
        _status.value = Status.NotDownloaded
        _completions.tryEmit(Unit)
    }

    private fun download(filesDir: File) {
        val target = file(filesDir)
        target.parentFile?.mkdirs()
        val part = File(target.path + ".part")
        val url = ServiceEndpoints.repo(ServiceRepo.DATA).rawUrl(REPO_PATH)
        val connection = URL(url).openConnection() as HttpURLConnection
        val netCall = NetLog.call(NetSource.DOWNLOAD_WORDLIST, "GET", url, route = NetLog.pathOf(url))
        try {
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Accept-Encoding", "identity")
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            netCall.status = connection.responseCode
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("HTTP ${connection.responseCode} for $url")
            }
            netCall.countIn(connection.inputStream).use { input ->
                part.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_BYTES) throw IOException("$url is larger than the Avro dictionary")
                        out.write(buffer, 0, read)
                    }
                }
            }
            check(holdsTables(part)) { "$url is not an Avro dictionary" }
            if (!part.renameTo(target)) throw IOException("could not move ${part.name} into place")
        } catch (t: Throwable) {
            part.delete()
            netCall.fail(t)
            throw t
        } finally {
            netCall.end()
            connection.disconnect()
        }
    }

    /** Whether [file] inflates and opens at least one `@table` of words. */
    private fun holdsTables(file: File): Boolean = runCatching {
        GZIPInputStream(file.inputStream()).bufferedReader().useLines { lines ->
            lines.take(4).any { it.startsWith("@") }
        }
    }.getOrDefault(false)
}
