package com.wasimaster.wmkeyboard.core.ocr

import android.graphics.Bitmap
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * The JNI surface of `libwmtess.so`, built from `native/tesseract-jni` and
 * committed under this module's `src/full/jniLibs`. Handles are native
 * pointers; see the C++ file for what each call guarantees.
 */
internal object TesseractNative {

    /** False when the library is missing from this build or failed to load. */
    val available: Boolean = runCatching { System.loadLibrary("wmtess") }.isSuccess

    @JvmStatic external fun nativeCreate(dataDir: String, languages: String): Long

    @JvmStatic external fun nativeRecognize(handle: Long, bitmap: Bitmap): String?

    @JvmStatic external fun nativeCancel(handle: Long)

    @JvmStatic external fun nativeDestroy(handle: Long)

    @JvmStatic external fun nativeVersion(): String
}

/**
 * Reads text with Tesseract. One engine is kept loaded for the last pack
 * used, since loading a pack takes longer than reading a photo; switching
 * packs closes it and loads the next.
 *
 * Every native call runs on one thread of its own: a Tesseract engine is not
 * safe to share, and this keeps create, read and destroy in order without a
 * lock. Cancelling the caller cancels the read inside Tesseract too.
 */
object TesseractOcr {

    val available: Boolean get() = TesseractNative.available

    private val thread = Executors.newSingleThreadExecutor { r -> Thread(r, "tesseract") }
    private val dispatcher = thread.asCoroutineDispatcher()

    // Touched only on [thread].
    private var handle = 0L
    private var loadedPack: String? = null

    /**
     * The text in [bitmap] as lines of space-separated words, or null when
     * [pack] is not downloaded or would not load. [bitmap] must be ARGB_8888.
     */
    suspend fun recognize(filesDir: File, pack: String, bitmap: Bitmap): String? {
        if (!available || !OcrPacks.isDownloaded(filesDir, pack)) return null
        return withContext(dispatcher) {
            val engine = engineFor(filesDir, pack)
            if (engine == 0L) return@withContext null
            suspendCancellableCoroutine { cont ->
                cont.invokeOnCancellation { TesseractNative.nativeCancel(engine) }
                cont.resumeWith(runCatching { TesseractNative.nativeRecognize(engine, bitmap) })
            }
        }
    }

    /** Frees the loaded engine, for when the panel closes. */
    fun release() {
        thread.execute { close() }
    }

    private fun engineFor(filesDir: File, pack: String): Long {
        if (handle != 0L && loadedPack == pack) return handle
        close()
        handle = TesseractNative.nativeCreate(OcrPacks.dataDir(filesDir).path, pack)
        loadedPack = if (handle != 0L) pack else null
        return handle
    }

    private fun close() {
        if (handle != 0L) TesseractNative.nativeDestroy(handle)
        handle = 0L
        loadedPack = null
    }
}
