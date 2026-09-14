package com.wasimaster.wmkeyboard.core.vocab

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * The speaker button's voice: Wiktionary's recording when one is offered,
 * the platform's speech synthesiser otherwise — and also whenever the
 * recording fails to play, because a speaker button that stays silent reads
 * as a bug.
 *
 * One instance per process side (the keyboard service owns one, a settings
 * screen creates one for its lifetime). The synthesiser is created lazily
 * on the first request that needs it, since its initialisation takes a
 * noticeable moment and most taps never need it; a request that arrives
 * before it is ready waits and plays when it is. [shutdown] releases both.
 */
class VocabSpeaker(context: Context) {

    private val appContext = context.applicationContext
    private var player: MediaPlayer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var ttsFailed = false
    private var pending: (() -> Unit)? = null

    /**
     * Told when the current utterance or recording ends, or fails. Called on
     * whichever thread the engine uses, so the owner posts to its own.
     */
    @Volatile
    private var onDone: (() -> Unit)? = null

    /**
     * Plays [audioUrl] when given, else speaks [word]. [rate] and [pitch] are
     * the synthesiser's, 1.0 being its default; [locale] its voice. [onDone]
     * fires once the speech ends, for a button that reads Stop meanwhile.
     */
    fun speak(word: String, audioUrl: String?, rate: Float, pitch: Float, locale: Locale, onDone: (() -> Unit)? = null) {
        stop()
        this.onDone = onDone
        if (audioUrl.isNullOrBlank()) {
            speakSynthesised(word, rate, pitch, locale)
            return
        }
        val mediaPlayer = player ?: MediaPlayer().also { player = it }
        runCatching {
            mediaPlayer.reset()
            mediaPlayer.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            mediaPlayer.setDataSource(audioUrl)
            mediaPlayer.setOnPreparedListener { it.start() }
            mediaPlayer.setOnCompletionListener { finished() }
            mediaPlayer.setOnErrorListener { _, _, _ ->
                speakSynthesised(word, rate, pitch, locale)
                true
            }
            mediaPlayer.prepareAsync()
        }.onFailure {
            speakSynthesised(word, rate, pitch, locale)
        }
    }

    private fun finished() {
        val done = onDone ?: return
        onDone = null
        done()
    }

    private fun speakSynthesised(word: String, rate: Float, pitch: Float, locale: Locale) {
        if (ttsFailed) {
            finished()
            return
        }
        val engine = tts
        if (engine != null && ttsReady) {
            utter(engine, word, rate, pitch, locale)
            return
        }
        pending = { tts?.let { utter(it, word, rate, pitch, locale) } }
        if (engine == null) {
            tts = TextToSpeech(appContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    ttsReady = true
                    tts?.setOnUtteranceProgressListener(
                        object : UtteranceProgressListener() {
                            override fun onStart(utteranceId: String?) {}
                            override fun onDone(utteranceId: String?) = finished()
                            @Deprecated("Deprecated in Java")
                            override fun onError(utteranceId: String?) = finished()
                            override fun onError(utteranceId: String?, errorCode: Int) = finished()
                        },
                    )
                    pending?.invoke()
                } else {
                    ttsFailed = true
                    finished()
                }
                pending = null
            }
        }
    }

    private fun utter(engine: TextToSpeech, word: String, rate: Float, pitch: Float, locale: Locale) {
        runCatching {
            engine.language = locale
            engine.setSpeechRate(rate.coerceIn(MIN_RATE, MAX_RATE))
            engine.setPitch(pitch.coerceIn(MIN_RATE, MAX_RATE))
            engine.speak(word, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
        }
    }

    /** Stops whatever is playing; the panel closing calls this. */
    fun stop() {
        pending = null
        onDone = null
        runCatching { player?.takeIf { it.isPlaying }?.stop() }
        runCatching { tts?.takeIf { ttsReady }?.stop() }
    }

    /** Releases the player and the synthesiser; the owner's teardown calls this. */
    fun shutdown() {
        stop()
        runCatching { player?.release() }
        player = null
        runCatching { tts?.shutdown() }
        tts = null
        ttsReady = false
    }

    private companion object {
        const val UTTERANCE_ID = "vocab"
        const val MIN_RATE = 0.5f
        const val MAX_RATE = 2.0f
    }
}
