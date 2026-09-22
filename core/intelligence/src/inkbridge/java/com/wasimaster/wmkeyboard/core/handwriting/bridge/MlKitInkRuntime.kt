package com.wasimaster.wmkeyboard.core.handwriting.bridge

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.common.sdkinternal.MlKitContext
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizer
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizerOptions
import com.google.mlkit.vision.digitalink.recognition.Ink
import com.google.mlkit.vision.digitalink.recognition.RecognitionContext
import com.google.mlkit.vision.digitalink.recognition.WritingArea
import com.wasimaster.wmkeyboard.core.handwriting.HwStroke
import com.wasimaster.wmkeyboard.core.handwriting.InkRuntime
import com.wasimaster.wmkeyboard.core.util.runCancellable
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Awaits a Play-services Task without the coroutines-play-services artifact. */
private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { if (cont.isActive) cont.resume(it) }
    addOnFailureListener { if (cont.isActive) cont.resumeWithException(it) }
    addOnCanceledListener { if (cont.isActive) cont.cancel() }
}

/**
 * The half of handwriting that touches ML Kit's digital-ink library, and
 * nothing else. Reached only by reflection (see `InkModule.BRIDGE_CLASS`),
 * because on Play it ships in the on-demand `:feature:handwriting` split and
 * the base APK has none of these imports.
 *
 * Deliberately thin. Every decision lives in `HandwritingModels` on the other
 * side of [InkRuntime], where it is compiled once for every channel; what is
 * here is the calls themselves, plus the one piece of ML Kit state that has
 * to be reset before any of them: [prepare].
 *
 * Public with a public no-arg constructor, both for `newInstance()`.
 */
class MlKitInkRuntime : InkRuntime {

    /**
     * Lazy, not eager: `getInstance` throws when ML Kit's init provider was
     * skipped (see MlKitInit), and a throw out of a constructor would poison
     * the facade's cached runtime for the whole process. Deferring it keeps
     * the damage inside the calls that actually need the manager.
     */
    private val manager: RemoteModelManager by lazy { RemoteModelManager.getInstance() }

    private val mddLock = Mutex()

    @Volatile
    private var mddPrepared = false

    private fun model(tag: String): DigitalInkRecognitionModel? {
        val identifier = runCatching {
            DigitalInkRecognitionModelIdentifier.fromLanguageTag(tag)
        }.getOrNull() ?: return null
        return DigitalInkRecognitionModel.builder(identifier).build()
    }

    /**
     * Clears Mobile Data Download's file-group record, once per process and
     * before ML Kit's first ink call.
     *
     * ML Kit 19 re-registers all 725 ink file groups when a process first
     * touches ink, and every Task it hands out — the status check, the
     * download, the recogniser's model lookup — waits for that registration.
     * In a process whose record already holds those groups, re-registering
     * them finds nothing but duplicates, and the registration never finishes:
     * no error, no thread working, just Tasks that never answer. Measured on a
     * CPH2481 with a one-file app on digital-ink-recognition 19.0.0 and
     * nothing else: the first process after install downloads and recognises;
     * every later process hangs for as long as it lives (watched for eleven
     * minutes), debug or release, interpreted or AOT-compiled. That is the
     * "downloads forever" of #235 once the shrinker crash in front of it is
     * gone, and it would stop recognition too after the keyboard restarts.
     *
     * With the record cleared the registration is a first one again and takes
     * about ten seconds, after which a model that was already downloaded
     * still reads as downloaded: MDD keeps the files and their checksums in a
     * separate record and re-verifies the group against it, without fetching
     * anything. Only the group record goes; the files record is what keeps a
     * downloaded model downloaded.
     */
    override suspend fun prepare() {
        if (mddPrepared) return
        mddLock.withLock {
            if (mddPrepared) return
            // Not initialised yet means ML Kit cannot run either; try again
            // on the next call rather than marking a reset that never happened.
            val context = runCatching { MlKitContext.getInstance().applicationContext }.getOrNull() ?: return
            withContext(Dispatchers.IO) {
                runCatching {
                    context.getSharedPreferences(MDD_GROUPS_PREFS, Context.MODE_PRIVATE).edit().clear().commit()
                }
            }
            mddPrepared = true
        }
    }

    override suspend fun isDownloaded(tag: String): Boolean {
        val model = model(tag) ?: return false
        return runCancellable { manager.isModelDownloaded(model).await() }.getOrDefault(false)
    }

    override fun startDownload(tag: String): InkRuntime.PendingDownload {
        val model = model(tag) ?: throw IllegalArgumentException("No model for $tag")
        val task = manager.download(model, DownloadConditions.Builder().build())
        return object : InkRuntime.PendingDownload {
            override val isComplete: Boolean get() = task.isComplete

            override suspend fun await() {
                task.await()
            }
        }
    }

    override suspend fun delete(tag: String) {
        val model = model(tag) ?: return
        runCancellable { manager.deleteDownloadedModel(model).await() }
    }

    override fun recognizer(tag: String): InkRuntime.Recognizer? {
        val model = model(tag) ?: return null
        val client = DigitalInkRecognition.getClient(DigitalInkRecognizerOptions.builder(model).build())
        return InkRecognizer(client)
    }

    private class InkRecognizer(private val client: DigitalInkRecognizer) : InkRuntime.Recognizer {

        override suspend fun recognize(
            strokes: List<HwStroke>,
            preContext: String,
            writingAreaWidth: Float,
            writingAreaHeight: Float,
        ): List<String> {
            val inkBuilder = Ink.builder()
            for (stroke in strokes) {
                val strokeBuilder = Ink.Stroke.builder()
                for (point in stroke.points) {
                    strokeBuilder.addPoint(Ink.Point.create(point.x, point.y, point.t))
                }
                inkBuilder.addStroke(strokeBuilder.build())
            }
            val contextBuilder = RecognitionContext.builder()
                // ML Kit caps pre-context at 20 chars; longer values throw.
                .setPreContext(preContext.takeLast(PRE_CONTEXT_CHARS))
            if (writingAreaWidth > 0f && writingAreaHeight > 0f) {
                contextBuilder.setWritingArea(WritingArea(writingAreaWidth, writingAreaHeight))
            }
            val result = client.recognize(inkBuilder.build(), contextBuilder.build()).await()
            return result.candidates.map { it.text }
        }

        override fun close() = client.close()
    }

    private companion object {
        /**
         * Where Mobile Data Download keeps its record of every ink file group.
         * ML Kit registers all 725 of them each time a process first touches
         * ink; see [prepare] for why that record has to go first.
         */
        const val MDD_GROUPS_PREFS = "gms_icing_mdd_groupsmlkit_digital_ink_recognition"

        const val PRE_CONTEXT_CHARS = 20
    }
}
