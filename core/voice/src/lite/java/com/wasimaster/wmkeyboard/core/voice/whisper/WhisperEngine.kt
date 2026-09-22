package com.wasimaster.wmkeyboard.core.voice.whisper

import com.wasimaster.wmkeyboard.core.modules.AlwaysInstalled
import com.wasimaster.wmkeyboard.core.modules.ModuleState
import java.io.File
import kotlinx.coroutines.flow.StateFlow

/**
 * Lite-flavor stub — the LiteRT Whisper runtime is a full-build feature, so
 * this object only keeps the shared callers (IME service) compiling. The
 * Whisper voice engine is hidden from lite settings, making this unreachable
 * in practice.
 */
object WhisperEngine {

    const val AVAILABLE = false

    /** Nothing to deliver in lite; the state is a formality for shared callers. */
    val moduleState: StateFlow<ModuleState> get() = AlwaysInstalled.state

    val ready: Boolean get() = false

    fun requestModule() = Unit

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
