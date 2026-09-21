package com.wasimaster.wmkeyboard.core.stickers

import android.content.Context
import android.graphics.Bitmap
import com.google.android.gms.common.moduleinstall.InstallStatusListener
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.android.gms.common.moduleinstall.ModuleInstallStatusUpdate
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import com.wasimaster.wmkeyboard.core.util.runCancellable
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * [SubjectCutout] over ML Kit's subject segmentation, for a device that has
 * Google Play services.
 *
 * The model is not in the APK. It is a Play services module, downloaded on
 * demand, which is why this exposes [modelReady] and [ensureModel] separately
 * from [cutOut]: the editor asks first and offers the download as a visible
 * choice, rather than declaring the model in the manifest and making every
 * user of the full build pay for it at install time whether or not they ever
 * open a sticker pack.
 *
 * Callers must have run `MlKitInit.ensure(context)` first — after a reboot
 * into the lock screen, ML Kit's own ContentProvider never ran, and every
 * entry point throws for the life of the process without it.
 */
internal object GmsSubjectCutout {

    private fun options() = SubjectSegmenterOptions.Builder()
        .enableForegroundBitmap()
        .build()

    /** Whether the module is already on the device. Never asks to install. */
    suspend fun modelReady(context: Context): Boolean = runCancellable {
        val client = ModuleInstall.getClient(context)
        val segmenter = SubjectSegmentation.getClient(options())
        try {
            client.areModulesAvailable(segmenter).await().areModulesAvailable()
        } finally {
            segmenter.close()
        }
    }.getOrDefault(false)

    /**
     * Requests the module, reporting progress from 0 to 1. False when it
     * cannot be had at all — a Play services stand-in that has no such module,
     * no network, device policy.
     */
    suspend fun ensureModel(context: Context, onProgress: (Float) -> Unit = {}): Boolean =
        runCancellable {
            val client = ModuleInstall.getClient(context)
            val segmenter = SubjectSegmentation.getClient(options())
            try {
                val listener = InstallStatusListener { update: ModuleInstallStatusUpdate ->
                    val progress = update.progressInfo ?: return@InstallStatusListener
                    val total = progress.totalBytesToDownload
                    if (total > 0) {
                        onProgress((progress.bytesDownloaded.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
                val request = ModuleInstallRequest.newBuilder()
                    .addApi(segmenter)
                    .setListener(listener)
                    .build()
                client.installModules(request).await()
                client.areModulesAvailable(segmenter).await().areModulesAvailable()
            } finally {
                segmenter.close()
            }
        }.getOrDefault(false)

    /**
     * The subject of [image] as an alpha mask, unjudged: whether it is worth
     * applying is [SubjectCutout]'s call, the same for every engine.
     *
     * `enableForegroundBitmap` gives back the input's own pixels with the
     * background cleared, so the mask is one `extractAlpha` away and there is
     * no confidence threshold to guess at.
     */
    suspend fun cutOut(image: Bitmap): SubjectCutout.Result {
        val segmenter = runCatching { SubjectSegmentation.getClient(options()) }.getOrNull()
            ?: return SubjectCutout.Result.ModelUnavailable
        return try {
            val result = runCancellable {
                segmenter.process(InputImage.fromBitmap(image, 0)).await()
            }.getOrNull() ?: return SubjectCutout.Result.Failed
            val foreground = result.foregroundBitmap ?: return SubjectCutout.Result.Failed
            val alpha = foreground.extractAlpha()
            if (foreground != image) foreground.recycle()
            SubjectCutout.Result.Ok(alpha)
        } finally {
            segmenter.close()
        }
    }

    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { cont.resume(it) }
        addOnFailureListener { if (cont.isActive) cont.cancel(it) }
    }
}
