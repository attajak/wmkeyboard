package com.wasimaster.wmkeyboard.core.netlog

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import java.net.UnknownHostException

/**
 * Whether this build holds `android.permission.INTERNET` (#292).
 *
 * The released APKs do. A build made with `-Pwmkb.noInternet=true` does not,
 * and neither does a fork that strips the permission some other way. Such a
 * build must still type, predict, learn and do everything else that runs on
 * the device; only the features that fetch something stop, the same way they
 * stop when the phone is offline.
 *
 * Without the permission the platform does not fail politely. A socket fails
 * with `EACCES`, which is an ordinary `IOException`, but a host name lookup
 * throws `SecurityException`, which is not, and would crash every caller that
 * is ready for "offline" and nothing else. So [NetLog] refuses a request before
 * it starts, with [NoInternetPermissionException], and the OkHttp clients do
 * the same through `InternetGate`.
 *
 * The permission is granted at install time and cannot change while the
 * process lives, so it is read once, from [NetLog.attach].
 */
object InternetPermission {

    /** True until [attach] reads otherwise, so JVM tests that never attach are unaffected. */
    @Volatile
    var granted: Boolean = true
        private set

    fun attach(context: Context) {
        granted = context.checkSelfPermission(Manifest.permission.INTERNET) == PackageManager.PERMISSION_GRANTED
    }

    /** Throws [NoInternetPermissionException] when this build has no internet permission. */
    fun check() {
        if (!granted) throw NoInternetPermissionException()
    }
}

/**
 * A request refused because this build has no internet permission.
 *
 * An [UnknownHostException] on purpose: every caller already treats that one
 * as "offline" and shows its offline state, which is the right answer here too.
 * The error mappers that can say something more exact check for this type first.
 */
class NoInternetPermissionException : UnknownHostException("This build has no internet permission")
