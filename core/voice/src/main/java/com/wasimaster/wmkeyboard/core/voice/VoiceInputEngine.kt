package com.wasimaster.wmkeyboard.core.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.ModelDownloadListener
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat

/**
 * Thin wrapper around the platform [SpeechRecognizer] for the voice input
 * tool. One instance lives in the service; each dictation session creates
 * a fresh recognizer (reusing one across sessions strands BUSY state on
 * several OEM implementations) and destroys it when the session ends.
 *
 * Every method must be called on the main thread — SpeechRecognizer's own
 * requirement.
 */
class VoiceInputEngine(private val context: Context) {

    /** Why a session ended without a final result, pre-digested for the UI. */
    enum class ErrorKind { NO_SPEECH, NETWORK, BUSY, PERMISSION, LANGUAGE, OTHER }

    interface Listener {
        /** The mic is open and capturing. */
        fun onListening()

        /** Sound level changed; [level] is folded into 0..1. */
        fun onLevel(level: Float)

        fun onPartial(text: String)

        /**
         * The recognizer stopped listening by itself — a pause, or its own
         * limit — and is working out the result. Anything said from here on
         * is not heard (#315).
         */
        fun onEndOfSpeech() {}

        /** Terminal: the recognizer is already released when this fires. */
        fun onFinal(text: String)

        /** Terminal: the recognizer is already released when this fires. */
        fun onError(kind: ErrorKind)
    }

    private var recognizer: SpeechRecognizer? = null

    /**
     * Languages the on-device recognizer rejected; those go straight to the
     * network recognizer from then on (typically bn-BD without a local model).
     */
    private val onDeviceFailedTags = mutableSetOf<String>()

    /** False on devices without any recognition service (no GMS). */
    fun isAvailable(): Boolean =
        SpeechRecognizer.isRecognitionAvailable(context) ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                SpeechRecognizer.isOnDeviceRecognitionAvailable(context))

    /**
     * Starts one utterance session; results stream to [listener]. Prefers
     * the on-device recognizer (API 31+): faster, offline, and it skips the
     * per-utterance beep that makes continuous dictation grating. A
     * language the on-device model can't handle falls back to the network
     * recognizer transparently, once, and is remembered.
     *
     * [formatting] asks the recognizer for punctuation and capital letters
     * (API 33+). Plain voice typing turns it off, because there the words are
     * wanted exactly as they were said.
     */
    fun start(
        languageTag: String,
        listener: Listener,
        allowOnDevice: Boolean = true,
        formatting: Boolean = true,
    ) {
        cancel()
        val onDevice = allowOnDevice &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            languageTag !in onDeviceFailedTags &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        val session = if (onDevice) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            SpeechRecognizer.createSpeechRecognizer(context)
        }
        recognizer = session
        session.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = listener.onListening()

            override fun onBeginningOfSpeech() {}

            override fun onRmsChanged(rmsdB: Float) {
                // Typical rmsdB swings about -2..10 dB; fold into 0..1.
                listener.onLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
            }

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() = listener.onEndOfSpeech()

            override fun onError(error: Int) {
                release(session)
                val kind = errorKind(error)
                if (onDevice && kind == ErrorKind.LANGUAGE) {
                    onDeviceFailedTags += languageTag
                    start(languageTag, listener, allowOnDevice = false, formatting = formatting)
                    return
                }
                listener.onError(kind)
            }

            override fun onResults(results: Bundle?) {
                release(session)
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                if (text.isBlank()) listener.onError(ErrorKind.NO_SPEECH) else listener.onFinal(text)
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                if (!text.isNullOrBlank()) listener.onPartial(text)
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        session.startListening(recognizerIntent(languageTag, formatting))
    }

    private fun recognizerIntent(languageTag: String, formatting: Boolean = true): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && formatting) {
                // Recognizer-side punctuation/casing where supported.
                putExtra(
                    RecognizerIntent.EXTRA_ENABLE_FORMATTING,
                    RecognizerIntent.FORMATTING_OPTIMIZE_QUALITY,
                )
            }
        }

    // ---- on-device model management (API 33+) ----

    enum class ModelCheckResult { UNSUPPORTED, INSTALLED, DOWNLOADABLE, PENDING }

    interface ModelDownloadCallback {
        fun onProgress(percent: Int)

        /** The model is installed; the next session will use it. */
        fun onSuccess()

        /**
         * Queued by the system (waiting for Wi-Fi / idle), or requested on
         * API 33 where no completion callback exists. No further callbacks;
         * a later [checkOnDeviceModel] reports the outcome.
         */
        fun onScheduled()

        fun onError()
    }

    /**
     * Asks the on-device recognizer whether [languageTag]'s model is
     * installed, downloadable, or mid-download. UNSUPPORTED on Android 12L
     * and below (the query API is 33+), without an on-device recognizer,
     * or for languages it can't serve at all.
     */
    fun checkOnDeviceModel(languageTag: String, onResult: (ModelCheckResult) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            !SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        ) {
            onResult(ModelCheckResult.UNSUPPORTED)
            return
        }
        val checker = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        checker.checkRecognitionSupport(
            recognizerIntent(languageTag),
            ContextCompat.getMainExecutor(context),
            object : RecognitionSupportCallback {
                override fun onSupportResult(recognitionSupport: RecognitionSupport) {
                    checker.destroy()
                    onResult(
                        when {
                            recognitionSupport.installedOnDeviceLanguages
                                .containsTag(languageTag) -> ModelCheckResult.INSTALLED
                            recognitionSupport.pendingOnDeviceLanguages
                                .containsTag(languageTag) -> ModelCheckResult.PENDING
                            recognitionSupport.supportedOnDeviceLanguages
                                .containsTag(languageTag) -> ModelCheckResult.DOWNLOADABLE
                            else -> ModelCheckResult.UNSUPPORTED
                        },
                    )
                }

                override fun onError(error: Int) {
                    checker.destroy()
                    onResult(ModelCheckResult.UNSUPPORTED)
                }
            },
        )
    }

    /** Downloads [languageTag]'s on-device model via the system recognizer. */
    fun downloadModel(languageTag: String, callback: ModelDownloadCallback) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            !SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        ) {
            callback.onError()
            return
        }
        val downloader = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        val intent = recognizerIntent(languageTag)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            downloader.triggerModelDownload(
                intent,
                ContextCompat.getMainExecutor(context),
                object : ModelDownloadListener {
                    override fun onProgress(completedPercent: Int) =
                        callback.onProgress(completedPercent)

                    override fun onSuccess() {
                        downloader.destroy()
                        // The earlier network fallback for this language is
                        // stale now — the next session goes on-device.
                        onDeviceFailedTags.remove(languageTag)
                        callback.onSuccess()
                    }

                    override fun onScheduled() {
                        downloader.destroy()
                        callback.onScheduled()
                    }

                    override fun onError(error: Int) {
                        downloader.destroy()
                        callback.onError()
                    }
                },
            )
        } else {
            // API 33: fire-and-forget — the system downloads in the
            // background and support re-checks pick up the result.
            downloader.triggerModelDownload(intent)
            downloader.destroy()
            onDeviceFailedTags.remove(languageTag)
            callback.onScheduled()
        }
    }

    /** Locale-tag membership, tolerating region differences (bn ~ bn-BD). */
    private fun List<String>.containsTag(tag: String): Boolean {
        val language = tag.substringBefore('-')
        return any {
            it.equals(tag, ignoreCase = true) ||
                it.substringBefore('-').equals(language, ignoreCase = true)
        }
    }

    /** Stop capturing and let the recognizer deliver its final result. */
    fun finish() {
        recognizer?.stopListening()
    }

    /** Abandon the session: no more callbacks, mic released. */
    fun cancel() {
        recognizer?.let {
            it.cancel()
            release(it)
        }
    }

    private fun release(session: SpeechRecognizer) {
        session.destroy()
        if (recognizer === session) recognizer = null
    }

    private fun errorKind(code: Int): ErrorKind = when (code) {
        SpeechRecognizer.ERROR_NO_MATCH,
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
        -> ErrorKind.NO_SPEECH
        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
        SpeechRecognizer.ERROR_SERVER,
        -> ErrorKind.NETWORK
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
        SpeechRecognizer.ERROR_AUDIO,
        -> ErrorKind.BUSY
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> ErrorKind.PERMISSION
        // ERROR_LANGUAGE_NOT_SUPPORTED / ERROR_LANGUAGE_UNAVAILABLE, API 33
        // constants inlined so the mapping compiles against older SDKs too.
        12, 13 -> ErrorKind.LANGUAGE
        else -> ErrorKind.OTHER
    }
}
