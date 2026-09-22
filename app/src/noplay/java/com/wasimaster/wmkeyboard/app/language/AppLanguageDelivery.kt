package com.wasimaster.wmkeyboard.app.language

import android.app.Activity

/**
 * Builds outside Play carry every interface language they list inside the APK,
 * so there is nothing to fetch and nothing for SplitCompat to wire up. The Play
 * version of this file is in `src/play/java`.
 */
fun appLanguageSplitCompat(activity: Activity) = Unit

/** Nothing to download: [onReady] runs straight away. */
@Suppress("UNUSED_PARAMETER")
fun fetchAppLanguage(activity: Activity, tag: String?, onReady: () -> Unit) = onReady()
