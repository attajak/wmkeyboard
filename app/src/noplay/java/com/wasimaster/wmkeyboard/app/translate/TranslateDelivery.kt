package com.wasimaster.wmkeyboard.app.translate

import android.app.Application

/**
 * The [installTranslateDelivery] of every channel that is not Play. Like
 * LlmDelivery beside it, the no-op is the correct answer, not a fallback:
 * these builds compile the ML Kit bridge straight into the base APK
 * (`:core:intelligence`'s src/translatebridge), so the default
 * always-installed gate in TranslateModule is already telling the truth and
 * there is nothing to deliver.
 */
@Suppress("UNUSED_PARAMETER")
fun installTranslateDelivery(app: Application) = Unit
