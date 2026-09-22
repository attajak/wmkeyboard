package com.wasimaster.wmkeyboard.core.stickers

import android.content.Context
import android.graphics.Bitmap
import com.wasimaster.wmkeyboard.core.netlog.NetLog
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import com.wasimaster.wmkeyboard.core.util.runCancellable
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter

/**
 * [SubjectCutout] with nothing of Google's on the device: the [CutoutModel]
 * network, fetched over plain HTTPS from the data repository and run on the
 * LiteRT interpreter the full build already carries for Whisper, so it adds a
 * five megabyte download and not one byte of APK.
 *
 * The interpreter is built for one cutout and closed after it. Loading a graph
 * this small is quick next to running it, a cutout happens a few times in an
 * editing session and not at all in most, and the alternative is the settings
 * process holding the graph and its arena for as long as it lives.
 */
internal object LocalSubjectCutout {

    private const val USER_AGENT = "WMKeyboard model downloader"
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000
    private const val BUFFER_BYTES = 64 * 1024

    /** Two editors cannot be open at once, but two taps on one row can land. */
    private val downloading = Mutex()

    fun modelReady(context: Context): Boolean = CutoutModel.isDownloaded(context.filesDir)

    /**
     * Downloads the model, reporting progress from 0 to 1, and resumes a
     * download that was cut short. False when it could not be had: no network,
     * no room, or a file at the address that is not the model.
     */
    suspend fun ensureModel(context: Context, onProgress: (Float) -> Unit = {}): Boolean {
        val filesDir = context.filesDir
        return downloading.withLock {
            if (CutoutModel.isDownloaded(filesDir)) return@withLock true
            withContext(Dispatchers.IO) {
                runCancellable { download(filesDir, onProgress) }.getOrDefault(false)
            }
        }
    }

    private suspend fun download(filesDir: File, onProgress: (Float) -> Unit): Boolean {
        CutoutModel.dir(filesDir).mkdirs()
        val part = CutoutModel.partFile(filesDir)
        // A part that is already the full length failed its digest last time
        // or was never checked; either way there is nothing to resume.
        if (part.length() >= CutoutModel.SIZE_BYTES) part.delete()
        var resumeFrom = part.length()
        val connection = URL(CutoutModel.url).openConnection() as HttpURLConnection
        val netCall = NetLog.call(
            NetSource.DOWNLOAD_CUTOUT,
            "GET",
            CutoutModel.url,
            route = NetLog.pathOf(CutoutModel.url),
        )
        try {
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", USER_AGENT)
            if (resumeFrom > 0) connection.setRequestProperty("Range", "bytes=$resumeFrom-")
            netCall.status = connection.responseCode
            when (connection.responseCode) {
                HttpURLConnection.HTTP_PARTIAL -> Unit
                HttpURLConnection.HTTP_OK -> resumeFrom = 0
                else -> return false
            }
            var written = resumeFrom
            netCall.countIn(connection.inputStream).use { input ->
                RandomAccessFile(part, "rw").use { out ->
                    out.setLength(resumeFrom)
                    out.seek(resumeFrom)
                    val buffer = ByteArray(BUFFER_BYTES)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        // A mirror that answers with something far larger is
                        // not going to pass the digest; stop paying for it.
                        if (written + read > CutoutModel.SIZE_BYTES) return false
                        out.write(buffer, 0, read)
                        written += read
                        onProgress((written.toFloat() / CutoutModel.SIZE_BYTES).coerceIn(0f, 1f))
                    }
                }
            }
        } catch (t: Throwable) {
            netCall.fail(t)
            throw t
        } finally {
            connection.disconnect()
            netCall.end()
        }
        // Short means interrupted: keep the part, the next try resumes it.
        if (part.length() < CutoutModel.SIZE_BYTES) return false
        if (sha256Of(part) != CutoutModel.SHA256) {
            part.delete()
            return false
        }
        return part.renameTo(CutoutModel.file(filesDir))
    }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * The subject of [image] as an alpha mask the size of [image], unjudged:
     * whether it is worth applying is [SubjectCutout]'s call.
     *
     * The network sees a 320 pixel square whatever the picture's shape, which
     * is how it was trained, and the map it answers with is stretched back
     * over the picture with filtering, so the step from 320 up to the sticker
     * canvas softens the edge and does not staircase it.
     */
    suspend fun cutOut(context: Context, image: Bitmap): SubjectCutout.Result {
        val model = CutoutModel.file(context.filesDir)
        if (!CutoutModel.isDownloaded(context.filesDir)) return SubjectCutout.Result.ModelUnavailable
        return withContext(Dispatchers.Default) {
            runCancellable { SubjectCutout.Result.Ok(segment(model, image)) }
                .getOrDefault(SubjectCutout.Result.Failed)
        }
    }

    private fun segment(model: File, image: Bitmap): Bitmap {
        val side = CutoutModel.INPUT_SIDE
        // getPixels cannot read a hardware bitmap, and the editor's canvas is
        // not one, but a caller's picture is the caller's business.
        val readable = if (image.config == Bitmap.Config.ARGB_8888) image
        else image.copy(Bitmap.Config.ARGB_8888, false)
        val small = Bitmap.createScaledBitmap(readable, side, side, true)
        val pixels = IntArray(side * side)
        small.getPixels(pixels, 0, side, 0, 0, side, side)
        if (small !== readable) small.recycle()
        if (readable !== image) readable.recycle()

        val input = ByteBuffer.allocateDirect(pixels.size * 3 * Float.SIZE_BYTES).order(ByteOrder.nativeOrder())
        input.asFloatBuffer().put(CutoutMask.normalise(pixels))
        val output = ByteBuffer.allocateDirect(pixels.size * Float.SIZE_BYTES).order(ByteOrder.nativeOrder())

        val options = Interpreter.Options().apply {
            numThreads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
        }
        Interpreter(model, options).use { interpreter ->
            interpreter.runSignature(
                mapOf(CutoutModel.INPUT_NAME to input),
                mapOf(CutoutModel.OUTPUT_NAME to output),
                CutoutModel.SIGNATURE,
            )
        }

        val map = FloatArray(pixels.size)
        output.rewind()
        output.asFloatBuffer().get(map)
        val alpha = CutoutMask.alphaOf(map)
        // White under the alpha, so the scaled copy's alpha channel is the
        // filtered mask and extractAlpha lifts it straight out.
        for (index in alpha.indices) pixels[index] = (alpha[index] shl 24) or 0x00FFFFFF
        val coarse = Bitmap.createBitmap(pixels, side, side, Bitmap.Config.ARGB_8888)
        val full = Bitmap.createScaledBitmap(coarse, image.width, image.height, true)
        val mask = full.extractAlpha()
        if (full !== coarse) full.recycle()
        coarse.recycle()
        return mask
    }
}
