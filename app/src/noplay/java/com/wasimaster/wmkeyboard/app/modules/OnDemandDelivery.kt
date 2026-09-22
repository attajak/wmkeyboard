package com.wasimaster.wmkeyboard.app.modules

import android.app.Application

/**
 * The [installOnDemandDelivery] of every channel that is not Play. Like
 * LlmDelivery beside it, the no-op is the correct answer, not a fallback:
 * these builds compile the LiteRT interpreter and ML Kit's ink recogniser
 * straight into the base APK, so the default always-installed gates in
 * FeatureModules are already telling the truth and there is nothing to
 * deliver.
 */
@Suppress("UNUSED_PARAMETER")
fun installOnDemandDelivery(app: Application) = Unit
