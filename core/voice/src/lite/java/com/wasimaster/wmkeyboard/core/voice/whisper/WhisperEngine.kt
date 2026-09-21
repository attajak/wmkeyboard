package com.wasimaster.wmkeyboard.core.voice.whisper

import java.io.File

/**
 * Lite-flavor stub — the LiteRT Whisper runtime is a full-build feature, so
 * this object only keeps the shared callers (IME service) compiling. The
 * Whisper voice engine is hidden from lite settings, making this unreachable
 * in practice.
 */
object WhisperEngine {

    const val AVAILABLE = false

    @Suppress("UNUSED_PARAMETER")
    fun transcribe(
        modelFile: File,
        vocabFile: File,
        pcm: FloatArray,
        translate: Boolean,
        langToken: Int? = null,
    ): String = error("Offline Whisper is not available in the lite build")

    @Suppress("UNUSED_PARAMETER")
    fun warm(modelFile: File, vocabFile: File) = Unit

    fun release() = Unit
}
