package com.wasimaster.wmkeyboard.app.updates

import android.content.Context
import android.os.StatFs
import com.wasimaster.wmkeyboard.core.netlog.NetLog
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/** Why a download stopped short, in the terms the card has to explain. */
internal class DownloadFailure(
    val reason: UpdateFailure,
    val keepPartial: Boolean,
    cause: Throwable? = null,
) : Exception(cause)

/**
 * Where an update is kept between downloading it and installing it.
 *
 * `noBackupFilesDir` rather than the cache directory, which is where every
 * other download in this app goes. Android empties the cache under storage
 * pressure, and storage pressure is exactly the condition a seventy-megabyte
 * APK creates, so a cached update could vanish in the seconds between
 * finishing and the user pressing Install. The price is that this app has to
 * clean up after itself, which is what [ApkStaging.sweep] is for, and that the
 * file is worth showing on the storage screen.
 */
internal object ApkStaging {

    private const val DIR = "updates"

    fun dir(context: Context): File = File(context.noBackupFilesDir, DIR).apply { mkdirs() }

    fun apkFile(context: Context, candidate: UpdateCandidate): File =
        File(dir(context), candidate.assetName)

    fun partFile(context: Context, candidate: UpdateCandidate): File =
        File(dir(context), candidate.assetName + ".part")

    fun listCache(context: Context): File = File(dir(context), "releases.json")

    /**
     * Deletes anything staged that this install can no longer use: a download
     * of the version already running or older, and any file whose name is not
     * one of ours. Runs once per process, before anything else looks at the
     * directory.
     */
    fun sweep(context: Context, installedVersionCode: Int) {
        val keep = listCache(context).name
        dir(context).listFiles()?.forEach { file ->
            if (file.name == keep) return@forEach
            val parsed = ReleaseAssets.parseAssetName(file.name.removeSuffix(".part"))
            if (parsed == null || parsed.versionCode <= installedVersionCode) file.delete()
        }
    }
}

/**
 * Downloads one release asset, resuming a part-finished one.
 *
 * The shape is [com.wasimaster.wmkeyboard.core.addons.AddonDownloadManager]'s
 * `transfer`, for the same reasons and with the same handling of a server that
 * ignores the range: request the canonical release URL every time, never a
 * redirect target, because GitHub redirects to a signed CDN address whose
 * signature expires and would answer a resume with a refusal.
 */
internal object ApkDownload {

    private const val BUFFER_BYTES = 256 * 1024
    private const val PROGRESS_INTERVAL_MS = 250L
    private const val TIMEOUT_MS = 20_000

    /**
     * Room needed before starting: the file, plus as much again because
     * Android stages its own copy of it during the install, plus a margin so
     * finishing does not leave the device with nothing left.
     */
    private const val SPACE_MARGIN_BYTES = 64L * 1024 * 1024

    /** Throws when there is not enough room for [sizeBytes] and the install after it. */
    fun requireSpace(dir: File, sizeBytes: Long, alreadyHave: Long) {
        val need = (sizeBytes - alreadyHave).coerceAtLeast(0L) + sizeBytes + SPACE_MARGIN_BYTES
        val free = runCatching { StatFs(dir.path).availableBytes }.getOrNull() ?: return
        if (free < need) throw DownloadFailure(UpdateFailure.NO_SPACE, keepPartial = true)
    }

    /**
     * Streams [candidate] into [part], appending to whatever is already there.
     *
     * [onProgress] is called with bytes written and the expected total, at most
     * every quarter second, which is often enough to look continuous and rare
     * enough not to recompose the card on every buffer.
     */
    @Suppress("ThrowsCount")
    suspend fun transfer(
        candidate: UpdateCandidate,
        part: File,
        onProgress: (Long, Long) -> Unit,
    ) {
        part.parentFile?.mkdirs()
        val resumeFrom = if (part.exists()) part.length() else 0L
        val connection = URL(candidate.url).openConnection() as HttpURLConnection
        val netCall = NetLog.call(
            NetSource.UPDATES,
            "GET",
            candidate.url,
            route = NetLog.pathOf(candidate.url),
            background = false,
        )
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", GithubReleaseSource.USER_AGENT)
            if (resumeFrom > 0) connection.setRequestProperty("Range", "bytes=$resumeFrom-")

            netCall.status = connection.responseCode
            val status = connection.responseCode
            // A server that ignored the range sends 200 and the whole file, so
            // what is on disk is worthless and this starts again. 416 means the
            // partial is longer than the file now is, which is the same answer.
            val appending = status == HttpURLConnection.HTTP_PARTIAL
            if (status !in 200..299 && status != HttpURLConnection.HTTP_PARTIAL) {
                throw DownloadFailure(UpdateFailure.NETWORK, keepPartial = false)
            }
            if (!appending && resumeFrom > 0) part.delete()

            val already = if (appending) resumeFrom else 0L
            val total = candidate.sizeBytes.takeIf { it > 0 }
                ?: (already + connection.contentLengthLong.coerceAtLeast(0L))

            netCall.countIn(connection.inputStream).use { input ->
                RandomAccessFile(part, "rw").use { output ->
                    output.setLength(already)
                    output.seek(already)
                    val buffer = ByteArray(BUFFER_BYTES)
                    var written = already
                    var lastReport = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        written += read
                        // The release says how big the file is, so anything
                        // past that is not the file the release described.
                        if (candidate.sizeBytes > 0 && written > candidate.sizeBytes) {
                            throw DownloadFailure(UpdateFailure.CORRUPT, keepPartial = false)
                        }
                        output.write(buffer, 0, read)
                        val nowMs = System.currentTimeMillis()
                        if (nowMs - lastReport >= PROGRESS_INTERVAL_MS) {
                            lastReport = nowMs
                            onProgress(written, total)
                        }
                    }
                    onProgress(written, maxOf(total, written))
                }
            }
        } catch (t: Throwable) {
            netCall.fail(t)
            throw t
        } finally {
            connection.disconnect()
            netCall.end()
        }
    }

    /**
     * Checks [file] against the length and checksum the release published.
     *
     * The checksum is the reason an update is allowed to install at all, so a
     * release that offers none is not installed on trust: the caller falls back
     * to the checksums file the release workflow attaches, and refuses when
     * even that is missing.
     */
    fun verify(file: File, candidate: UpdateCandidate, sha256: String): Boolean {
        if (candidate.sizeBytes > 0 && file.length() != candidate.sizeBytes) return false
        val digest = MessageDigest.getInstance("SHA-256")
        runCatching {
            file.inputStream().use { input ->
                val buffer = ByteArray(BUFFER_BYTES)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
        }.getOrElse { return false }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        return actual == sha256.lowercase()
    }
}
