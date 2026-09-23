package com.wasimaster.wmkeyboard.core.net

import com.wasimaster.wmkeyboard.core.netlog.InternetPermission
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Refuses every request of an OkHttp client in a build without the internet
 * permission (#292), with the `IOException` its callers already handle as
 * "offline".
 *
 * An *application* interceptor, and it has to be: OkHttp looks the host up
 * before it runs the network interceptors, so by the time [NetLogInterceptor]
 * sees the request the lookup has already thrown the platform's
 * `SecurityException`, which nothing is ready to catch.
 */
object InternetGate : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        InternetPermission.check()
        return chain.proceed(chain.request())
    }
}
