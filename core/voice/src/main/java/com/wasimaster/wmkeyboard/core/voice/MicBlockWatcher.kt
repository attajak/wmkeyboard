package com.wasimaster.wmkeyboard.core.voice

import android.content.Context
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * Notices when Android hands a dictation session silence instead of the
 * microphone. The Microphone access tile in Quick Settings (Android 12+) and
 * the developer Sensors off tile both let recording start and then feed it
 * zeros. Apps cannot read either toggle (SensorPrivacyManager's getters are
 * system API), and the system's own unblock dialog does not come up for a
 * keyboard, so without this the session listens to nothing.
 *
 * What an app can see is the capture itself being silenced
 * ([AudioRecordingConfiguration.isClientSilenced], API 29+). Other apps'
 * captures are listed too, anonymized but with that flag kept, which is what
 * lets this watch the system recognizer's recording in its own process.
 *
 * A silenced capture is checked again after [CONFIRM_MS] before the callback
 * fires, so a flag that flickers while the recording opens does not end the
 * session. Main thread only. Below API 29 this does nothing.
 */
class MicBlockWatcher(private val context: Context) {
    // Lazy: the service builds this before its base context is attached.
    private val audio: AudioManager? by lazy { context.getSystemService(AudioManager::class.java) }
    private val handler = Handler(Looper.getMainLooper())
    private var callback: AudioManager.AudioRecordingCallback? = null
    private var sessionId = NO_SESSION
    private var onBlocked: (() -> Unit)? = null

    private val confirm = Runnable {
        val am = audio ?: return@Runnable
        if (callback != null && blocked(am.activeRecordingConfigurations)) {
            val fire = onBlocked
            stop()
            fire?.invoke()
        }
    }

    /**
     * Watches until [stop]. [audioSessionId] narrows the watch to one
     * [android.media.AudioRecord] of this app; null watches every capture on
     * the device, for a recognizer that records in another process. Start
     * that one only once the recognizer reports it is listening, or an
     * unrelated silenced capture could be taken for it. [onBlocked] fires at
     * most once.
     */
    fun start(audioSessionId: Int?, onBlocked: () -> Unit) {
        stop()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val am = audio ?: return
        sessionId = audioSessionId ?: NO_SESSION
        this.onBlocked = onBlocked
        val cb = object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: List<AudioRecordingConfiguration>) {
                evaluate(configs)
            }
        }
        callback = cb
        am.registerAudioRecordingCallback(cb, handler)
        evaluate(am.activeRecordingConfigurations)
    }

    /** Stops watching. Idempotent. */
    fun stop() {
        handler.removeCallbacks(confirm)
        callback?.let { cb -> audio?.unregisterAudioRecordingCallback(cb) }
        callback = null
        onBlocked = null
    }

    private fun evaluate(configs: List<AudioRecordingConfiguration>) {
        handler.removeCallbacks(confirm)
        if (blocked(configs)) handler.postDelayed(confirm, CONFIRM_MS)
    }

    /**
     * Every watched capture is silenced. "Every" matters when watching all
     * captures: a background app silenced by the concurrent capture policy
     * sits in the same list as a recognizer that is hearing fine.
     */
    private fun blocked(configs: List<AudioRecordingConfiguration>): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val watched = if (sessionId == NO_SESSION) {
            configs
        } else {
            configs.filter { it.clientAudioSessionId == sessionId }
        }
        return watched.isNotEmpty() && watched.all { it.isClientSilenced }
    }

    private companion object {
        const val NO_SESSION = -1
        const val CONFIRM_MS = 400L
    }
}
