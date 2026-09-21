package com.wasimaster.wmkeyboard.core.stickers

import android.content.Context
import android.graphics.Bitmap

/**
 * Lite build: no segmentation model is linked, so there is no one-tap
 * background removal. The sticker editor asks [supported] and leaves its
 * cutout control out entirely — the eraser and restore brushes are all in the
 * shared source set and work here exactly as they do in the full build, so
 * there is nothing to apologise for on screen.
 *
 * Same file path and same signatures as the full version, per the flavor-stub
 * contract the rest of the project follows.
 */
object SubjectCutout {

    const val supported: Boolean = false

    sealed interface Result {
        /** ALPHA_8 mask the size of the input; 255 keeps the pixel. */
        data class Ok(val alpha: Bitmap) : Result

        /** The model ran and found nothing worth keeping. */
        data object NoSubject : Result

        /** No engine could give us a model. */
        data object ModelUnavailable : Result

        data object Failed : Result

        /** This build has no segmenter at all. */
        data object Unsupported : Result
    }

    @Suppress("UnusedParameter")
    fun downloadsFromPlayServices(context: Context): Boolean = false

    @Suppress("UnusedParameter")
    suspend fun modelReady(context: Context): Boolean = false

    @Suppress("UnusedParameter")
    suspend fun ensureModel(context: Context, onProgress: (Float) -> Unit = {}): Boolean = false

    @Suppress("UnusedParameter")
    suspend fun cutOut(context: Context, image: Bitmap): Result = Result.Unsupported
}
