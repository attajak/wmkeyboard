package com.wasimaster.wmkeyboard.app.language

import android.app.Activity
import android.os.Build
import com.google.android.play.core.splitcompat.SplitCompat
import com.google.android.play.core.splitinstall.SplitInstallManagerFactory
import com.google.android.play.core.splitinstall.SplitInstallRequest
import com.google.android.play.core.splitinstall.SplitInstallStateUpdatedListener
import com.google.android.play.core.splitinstall.model.SplitInstallSessionStatus
import java.util.Locale

/**
 * Play delivers interface languages as splits, and installs only the ones the
 * phone is set to. Android 13 and up fetch a missing one themselves when the
 * app language changes. Below that the app has to ask, which is what this file
 * does, and each settings screen has to be told about a split that arrived
 * after the process started. The non-Play version is in `src/noplay/java`.
 */
fun appLanguageSplitCompat(activity: Activity) {
    SplitCompat.installActivity(activity)
}

/**
 * Makes sure the resources for [tag] are on the phone, then runs [onReady].
 *
 * It runs [onReady] on failure too: the app then shows English, which every
 * string has, and the choice stays stored, so the next start tries again.
 */
fun fetchAppLanguage(activity: Activity, tag: String?, onReady: () -> Unit) {
    if (tag == null || Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        onReady()
        return
    }
    val locale = Locale.forLanguageTag(tag)
    val manager = SplitInstallManagerFactory.create(activity)
    if (locale.language in manager.installedLanguages) {
        onReady()
        return
    }
    var finished = false
    lateinit var listener: SplitInstallStateUpdatedListener
    val finish = {
        if (!finished) {
            finished = true
            manager.unregisterListener(listener)
            onReady()
        }
    }
    listener = SplitInstallStateUpdatedListener { state ->
        if (locale.language !in state.languages()) return@SplitInstallStateUpdatedListener
        when (state.status()) {
            SplitInstallSessionStatus.INSTALLED,
            SplitInstallSessionStatus.FAILED,
            SplitInstallSessionStatus.CANCELED,
            SplitInstallSessionStatus.REQUIRES_USER_CONFIRMATION,
            -> finish()
            else -> Unit
        }
    }
    manager.registerListener(listener)
    manager.startInstall(SplitInstallRequest.newBuilder().addLanguage(locale).build())
        .addOnFailureListener { finish() }
}
