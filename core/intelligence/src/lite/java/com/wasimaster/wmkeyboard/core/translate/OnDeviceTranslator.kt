package com.wasimaster.wmkeyboard.core.translate

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Lite-flavor stand-in: no ML Kit, so there is no on-device translator. Every
 * place that offers one checks [AVAILABLE] first; these members only keep the
 * shared callers (IME, panel, settings) compiling, and answer "not here" if
 * one is reached anyway.
 */
object OnDeviceTranslator {

    const val AVAILABLE: Boolean = false

    val models: StateFlow<Map<String, OfflineModelState>> = MutableStateFlow(emptyMap())

    val moduleState get() = TranslateModule.gate.state

    fun requestModule() = Unit

    suspend fun refresh(context: Context): Set<String> = emptySet()

    fun download(context: Context, code: String) = Unit

    fun cancelDownload(context: Context, code: String) = Unit

    suspend fun delete(context: Context, code: String) = Unit

    suspend fun translate(
        context: Context,
        text: String,
        target: String,
        sourceOverride: String = "",
        hints: List<String> = emptyList(),
    ): OfflineTranslateResult =
        OfflineTranslateResult.Failed(UnsupportedOperationException("On-device translation is not in the lite build"))

    fun release() = Unit
}
