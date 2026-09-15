package com.wasimaster.wmkeyboard.core.voice.whisper

import android.os.StatFs
import androidx.annotation.StringRes
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.voice.R
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Downloads Whisper models from Hugging Face into [WhisperStore]'s layout.
 *
 * Process-level singleton with its own IO scope so progress survives
 * recomposition, navigation, and process death; the settings UI just collects
 * [states]. No WorkManager/foreground service (the app uses neither): leaving
 * the app can stop a download, but the `.part` file plus an HTTP Range request
 * resume exactly where it stopped.
 *
 * A model is **two files** (the `.tflite` and its `filters_vocab_*.bin`),
 * downloaded sequentially into one directory; both must land before the model
 * counts as downloaded. The combined progress bar spans both. The DocWolle repo
 * is public, so there is no token or license/gating path — only network and
 * space failures.
 */
object WhisperDownloadManager {

    sealed interface DownloadStatus {
        data object NotDownloaded : DownloadStatus

        /** [total] is -1 while unknown (no Content-Length yet). */
        data class Downloading(val bytes: Long, val total: Long) : DownloadStatus

        /** Cancelled or interrupted with a `.part` on disk — resumable. */
        data class Paused(val bytes: Long, val total: Long) : DownloadStatus
        data object Downloaded : DownloadStatus

        /**
         * [messageRes] is the text to show the user. [messageArg] is the one
         * format argument that text takes, or "" when it takes none. The UI
         * resolves the pair together:
         * `if (messageArg.isEmpty()) stringResource(messageRes)`
         * `else stringResource(messageRes, messageArg)`.
         */
        data class Failed(
            val reason: FailReason,
            @StringRes val messageRes: Int,
            val messageArg: String = "",
        ) : DownloadStatus
    }

    enum class FailReason { NETWORK, NO_SPACE, OTHER }

    /** Free space to leave untouched after a download completes. */
    private const val SPACE_MARGIN_BYTES = 64L * 1024 * 1024
    private const val PROGRESS_INTERVAL_MS = 250L
    private const val USER_AGENT = "WMKeyboard model downloader"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var activeJob: Job? = null
    private var activeId: String? = null

    private val _states = MutableStateFlow<Map<String, DownloadStatus>>(emptyMap())

    /** Model id → status for every catalog entry touched so far. */
    val states: StateFlow<Map<String, DownloadStatus>> = _states.asStateFlow()

    val isBusy: Boolean get() = activeJob?.isActive == true

    /** Seeds [states] from disk; call when the model manager UI appears. */
    fun refresh(filesDir: File) {
        // A retired graph still on disk would otherwise read as a finished
        // download of the current one (#207).
        WhisperCatalog.models.forEach { if (it.id != activeId) WhisperStore.purgeStale(filesDir, it) }
        _states.update { current ->
            WhisperCatalog.models.associate { model ->
                val active = current[model.id]
                model.id to when {
                    model.id == activeId && isBusy && active != null -> active
                    WhisperStore.isDownloaded(filesDir, model) -> DownloadStatus.Downloaded
                    WhisperStore.hasPartial(filesDir, model) -> DownloadStatus.Paused(
                        WhisperStore.bytesOnDisk(filesDir, model), model.sizeBytes,
                    )
                    else -> DownloadStatus.NotDownloaded
                }
            }
        }
    }

    fun start(filesDir: File, model: WhisperModel) {
        if (isBusy) return
        activeId = model.id
        set(model.id, DownloadStatus.Downloading(WhisperStore.bytesOnDisk(filesDir, model), model.sizeBytes))
        activeJob = scope.launch {
            try {
                downloadModel(filesDir, model)
                set(model.id, DownloadStatus.Downloaded)
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Advertise a resumable Paused state only when something is still
                // on disk; delete() cancels then wipes and sets NotDownloaded, and
                // the unwinding coroutine must not resurrect it.
                if (WhisperStore.hasPartial(filesDir, model) || WhisperStore.isDownloaded(filesDir, model)) {
                    set(model.id, DownloadStatus.Paused(WhisperStore.bytesOnDisk(filesDir, model), model.sizeBytes))
                }
                throw e
            } catch (e: FailedException) {
                set(model.id, DownloadStatus.Failed(e.reason, e.messageRes, e.messageArg))
            } catch (_: Exception) {
                set(
                    model.id,
                    DownloadStatus.Failed(FailReason.NETWORK, CommonR.string.common_error_network),
                )
            } finally {
                activeId = null
            }
        }
    }

    fun cancel() {
        activeJob?.cancel()
    }

    fun delete(filesDir: File, model: WhisperModel) {
        if (model.id == activeId) cancel()
        WhisperStore.delete(filesDir, model)
        set(model.id, DownloadStatus.NotDownloaded)
    }

    private fun set(id: String, status: DownloadStatus) {
        _states.update { it + (id to status) }
    }

    /**
     * A download failure that already knows which message the UI should show.
     * The exception's own `message` stays null: the text lives in [messageRes]
     * so it follows the app language.
     */
    private class FailedException(
        val reason: FailReason,
        @StringRes val messageRes: Int,
        val messageArg: String = "",
    ) : IOException()

    /**
     * One downloadable file within a model — the exact final file, its `.part`,
     * remote name, and approximate size (progress fallback + space preflight).
     */
    private data class Part(val url: String, val finalFile: File, val approxBytes: Long) {
        val partFile: File get() = File(finalFile.parentFile, "${finalFile.name}.part")
    }

    private suspend fun downloadModel(filesDir: File, model: WhisperModel) {
        val dir = WhisperStore.modelDir(filesDir, model).apply { mkdirs() }
        // The loop below skips a file already in place, so a retired graph has
        // to go first or it would never be replaced.
        WhisperStore.purgeStale(filesDir, model)
        val parts = listOf(
            // Vocab first — tiny, so a bad connection fails fast before the big file.
            Part(
                WhisperCatalog.downloadUrl(model.vocabRepo, model.vocabFile),
                File(dir, model.vocabFile), model.vocabBytes,
            ),
            Part(
                WhisperCatalog.downloadUrl(model.repo, model.modelFile),
                File(dir, model.modelFile), model.modelBytes,
            ),
        )

        // Space preflight for everything still missing, up front.
        val stillNeeded = parts.filter { !it.finalFile.isFile }
            .sumOf { it.approxBytes - it.partFile.length() }
        val free = StatFs(dir.path).availableBytes
        if (free < stillNeeded + SPACE_MARGIN_BYTES) {
            val neededMb = ((stillNeeded + SPACE_MARGIN_BYTES) / 1e6).roundToInt()
            throw FailedException(
                FailReason.NO_SPACE,
                R.string.core_voice_download_space_error,
                neededMb.toString(),
            )
        }

        val grandTotal = parts.sumOf { it.approxBytes }
        var doneBytes = 0L
        for (p in parts) {
            if (p.finalFile.isFile) {
                doneBytes += p.finalFile.length()
                continue
            }
            downloadPart(model.id, p, doneBytes, grandTotal)
            doneBytes += p.finalFile.length()
        }
    }

    private suspend fun downloadPart(modelId: String, p: Part, baseBytes: Long, grandTotal: Long) {
        var resumeFrom = p.partFile.length()
        val connection = URL(p.url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", USER_AGENT)
            if (resumeFrom > 0) connection.setRequestProperty("Range", "bytes=$resumeFrom-")

            when (val status = connection.responseCode) {
                HttpURLConnection.HTTP_PARTIAL -> Unit
                HttpURLConnection.HTTP_OK -> resumeFrom = 0
                else -> throw FailedException(
                    FailReason.OTHER,
                    R.string.core_voice_download_http_error,
                    status.toString(),
                )
            }

            val remaining = connection.contentLengthLong
            val fileTotal = if (remaining >= 0) resumeFrom + remaining else -1L
            var written = resumeFrom
            var lastUpdate = 0L

            connection.inputStream.use { input ->
                RandomAccessFile(p.partFile, "rw").use { out ->
                    out.setLength(resumeFrom)
                    out.seek(resumeFrom)
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        written += read
                        val now = System.currentTimeMillis()
                        if (now - lastUpdate >= PROGRESS_INTERVAL_MS) {
                            lastUpdate = now
                            set(modelId, DownloadStatus.Downloading(baseBytes + written, grandTotal))
                        }
                    }
                }
            }

            val incomplete =
                if (fileTotal >= 0) written != fileTotal else written < p.approxBytes * 9 / 10
            if (incomplete) {
                throw FailedException(
                    FailReason.NETWORK,
                    R.string.core_voice_download_interrupted_error,
                )
            }
            check(p.partFile.renameTo(p.finalFile)) {
                "could not move the finished download into place"
            }
        } finally {
            connection.disconnect()
        }
    }
}
