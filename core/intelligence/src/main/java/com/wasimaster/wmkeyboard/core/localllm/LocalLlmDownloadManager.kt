package com.wasimaster.wmkeyboard.core.localllm

import android.os.StatFs
import androidx.annotation.StringRes
import com.wasimaster.wmkeyboard.core.netlog.NetLog
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import com.wasimaster.wmkeyboard.intelligence.R
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
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
import com.wasimaster.wmkeyboard.common.R as CommonR

/**
 * Downloads catalog models from Hugging Face into [LocalLlmStore]'s layout.
 *
 * Process-level singleton with its own IO scope, so progress survives
 * recomposition, rotation, and screen navigation; the settings UI just
 * collects [states]. Deliberately no WorkManager or foreground service
 * (the app uses neither): leaving the app can stop an in-flight download,
 * but the `.part` file plus an HTTP Range request make Resume pick up
 * exactly where it stopped — including after process death.
 *
 * One download at a time: these are multi-GB files and parallel downloads
 * just split the same bandwidth while doubling failure modes.
 */
object LocalLlmDownloadManager {

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

    enum class FailReason { GATED_NO_TOKEN, LICENSE_NOT_ACCEPTED, NETWORK, NO_SPACE, OTHER }

    /**
     * A byte count for a download readout. Decimal units, because that is what
     * the model pages the sizes come from quote.
     *
     * Lives here rather than in either screen: the settings app and the AI panel
     * both draw the same progress line, and two copies would drift.
     */
    fun formatBytes(bytes: Long): String = when {
        bytes >= 1_000_000_000L -> "%.2f GB".format(bytes / 1e9)
        bytes >= 1_000_000L -> "%.0f MB".format(bytes / 1e6)
        bytes >= 1_000L -> "%.0f KB".format(bytes / 1e3)
        else -> "$bytes B"
    }

    /**
     * The download in flight right now, as `model id to status`, or null when
     * nothing is downloading. The AI panel shows this so a download started in
     * the settings app is visible from the keyboard.
     */
    fun activeDownload(states: Map<String, DownloadStatus>): Pair<String, DownloadStatus>? {
        val id = activeId ?: return null
        val status = states[id] ?: return null
        return if (status is DownloadStatus.Downloading) id to status else null
    }

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
        _states.update { current ->
            LocalLlmCatalog.models.associate { model ->
                val active = current[model.id]
                model.id to when {
                    // Never clobber a live download's progress state.
                    model.id == activeId && isBusy && active != null -> active
                    LocalLlmStore.isDownloaded(filesDir, model) -> DownloadStatus.Downloaded
                    LocalLlmStore.partFile(filesDir, model).isFile -> DownloadStatus.Paused(
                        LocalLlmStore.partFile(filesDir, model).length(), model.sizeBytes,
                    )
                    else -> DownloadStatus.NotDownloaded
                }
            }
        }
    }

    fun start(filesDir: File, model: LocalLlmModel, hfToken: String) {
        if (isBusy) return
        activeId = model.id
        val part = LocalLlmStore.partFile(filesDir, model)
        set(model.id, DownloadStatus.Downloading(part.length(), model.sizeBytes))
        activeJob = scope.launch {
            try {
                downloadInto(model, part, hfToken)
                check(part.renameTo(LocalLlmStore.modelFile(filesDir, model))) {
                    "could not move the finished download into place"
                }
                set(model.id, DownloadStatus.Downloaded)
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Only advertise a resumable Paused state when the .part still
                // exists. delete() cancels, removes the file, and sets
                // NotDownloaded; without this guard the unwinding coroutine
                // overwrites that with Paused(0, size), resurrecting a model
                // the user just deleted.
                if (part.isFile) set(model.id, DownloadStatus.Paused(part.length(), model.sizeBytes))
                throw e
            } catch (e: FailedException) {
                set(model.id, DownloadStatus.Failed(e.reason, e.messageRes, e.messageArg))
            } catch (_: Exception) {
                // IOException mid-stream leaves a resumable .part behind. The
                // system writes its own message in English only, so the shared
                // network line goes out instead of the text of the exception.
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

    fun delete(filesDir: File, model: LocalLlmModel) {
        if (model.id == activeId) cancel()
        LocalLlmStore.delete(filesDir, model)
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

    private suspend fun downloadInto(model: LocalLlmModel, part: File, hfToken: String) {
        part.parentFile?.mkdirs()
        var resumeFrom = part.length()

        val free = StatFs(part.parentFile!!.path).availableBytes
        if (free < model.sizeBytes - resumeFrom + SPACE_MARGIN_BYTES) {
            val neededGb = (model.sizeBytes - resumeFrom + SPACE_MARGIN_BYTES) / 1e9
            throw FailedException(
                FailReason.NO_SPACE,
                R.string.core_intel_llm_download_error_no_space,
                "%.1f".format(neededGb),
            )
        }

        val connection = URL(LocalLlmCatalog.downloadUrl(model)).openConnection() as HttpURLConnection
        val netCall = NetLog.call(
            NetSource.DOWNLOAD_LLM, "GET", LocalLlmCatalog.downloadUrl(model), route = NetLog.pathOf(LocalLlmCatalog.downloadUrl(model)),
        )
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", USER_AGENT)
            if (hfToken.isNotBlank()) {
                connection.setRequestProperty("Authorization", "Bearer $hfToken")
            }
            if (resumeFrom > 0) connection.setRequestProperty("Range", "bytes=$resumeFrom-")

            netCall.status = connection.responseCode
            when (val status = connection.responseCode) {
                HttpURLConnection.HTTP_PARTIAL -> Unit // appending below
                HttpURLConnection.HTTP_OK -> {
                    // Server ignored the Range (or none was sent) — start over.
                    resumeFrom = 0
                }
                HttpURLConnection.HTTP_UNAUTHORIZED -> throw FailedException(
                    FailReason.GATED_NO_TOKEN,
                    if (hfToken.isBlank()) {
                        R.string.core_intel_llm_download_error_gated_no_token
                    } else {
                        R.string.core_intel_llm_download_error_token_rejected
                    },
                )
                HttpURLConnection.HTTP_FORBIDDEN -> throw FailedException(
                    FailReason.LICENSE_NOT_ACCEPTED,
                    R.string.core_intel_llm_download_error_licence,
                )
                else -> throw FailedException(
                    FailReason.OTHER,
                    R.string.core_intel_llm_download_error_http,
                    status.toString(),
                )
            }

            val remaining = connection.contentLengthLong
            val total = if (remaining >= 0) resumeFrom + remaining else -1L
            var written = resumeFrom
            var lastUpdate = 0L

            netCall.countIn(connection.inputStream).use { input ->
                RandomAccessFile(part, "rw").use { out ->
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
                            set(model.id, DownloadStatus.Downloading(written, total))
                        }
                    }
                }
            }

            if (total >= 0) {
                if (written != total) {
                    throw FailedException(
                        FailReason.NETWORK,
                        R.string.core_intel_llm_download_error_truncated,
                    )
                }
            } else if (written < model.sizeBytes * 9 / 10) {
                // No Content-Length to validate against. sizeBytes is only
                // approximate, so use a loose floor: catch a grossly truncated
                // body (clean EOF after a partial transfer) without false-
                // rejecting a complete download whose real size differs a little
                // from the catalog estimate. Otherwise a truncated file would be
                // renamed into place and wrongly count as a valid model.
                throw FailedException(
                    FailReason.NETWORK,
                    R.string.core_intel_llm_download_error_truncated,
                )
            }
        } catch (t: Throwable) {
            netCall.fail(t)
            throw t
        } finally {
            connection.disconnect()
            netCall.end()
        }
    }
}
