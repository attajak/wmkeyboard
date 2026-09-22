package com.wasimaster.wmkeyboard.core.settings.sink

import android.util.Log

/**
 * Debug trace for the backup destinations: `adb logcat -s WMBackup`.
 *
 * Status codes, error reasons and sizes only. Never a token, an authorization
 * code or a verifier. Swallows the "method not mocked" a plain JVM unit test
 * gets from android.util.Log.
 */
object BackupLog {
    const val TAG = "WMBackup"

    fun d(message: String) {
        runCatching { Log.d(TAG, message) }
    }

    fun w(message: String, failure: Throwable? = null) {
        runCatching { Log.w(TAG, message, failure) }
    }
}
