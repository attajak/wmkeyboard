package com.wasimaster.wmkeyboard.core.net

import com.wasimaster.wmkeyboard.core.netlog.NetCall
import com.wasimaster.wmkeyboard.core.netlog.NetLog
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer

/**
 * Puts every request an OkHttp client makes into the network activity log.
 *
 * A *network* interceptor (`addNetworkInterceptor`), not an application one, so
 * each redirect hop and each retry is its own row: that is what actually went
 * over the network. The response body is counted as the caller reads it, and
 * the row is written when the body is exhausted or closed, so a download that
 * is abandoned halfway says how far it got.
 *
 * [route] turns the URL into the address to show after the host; the default
 * shows none, which is right for clients that fetch arbitrary third-party
 * URLs. [background] is asked per request, for clients whose traffic is
 * sometimes scheduled and sometimes asked for.
 */
class NetLogInterceptor(
    private val source: NetSource,
    private val route: (HttpUrl) -> String? = { null },
    private val background: () -> Boolean = { source.background },
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url
        val call = NetLog.callTo(
            source = source,
            method = request.method,
            scheme = url.scheme,
            host = url.host,
            port = if (url.port == HttpUrl.defaultPort(url.scheme)) -1 else url.port,
            route = route(url),
            background = background(),
        )
        request.body?.contentLength()?.takeIf { it > 0 }?.let(call::sent)
        val response = try {
            chain.proceed(request)
        } catch (t: Throwable) {
            call.fail(t)
            call.end()
            throw t
        }
        call.status = response.code
        val body = response.body ?: return response.also { call.end() }
        val counted = CountingSource(body.source(), call).buffer()
        return response.newBuilder()
            .body(counted.asResponseBody(body.contentType(), body.contentLength()))
            .build()
    }

    private class CountingSource(delegate: okio.Source, private val call: NetCall) : ForwardingSource(delegate) {
        override fun read(sink: Buffer, byteCount: Long): Long {
            val read = try {
                super.read(sink, byteCount)
            } catch (t: Throwable) {
                call.fail(t)
                call.end()
                throw t
            }
            if (read > 0) call.received(read) else if (read < 0) call.end()
            return read
        }

        override fun close() {
            try {
                super.close()
            } finally {
                call.end()
            }
        }
    }

    companion object {
        /** Shows the whole path: for clients talking to the user's own servers. */
        val PATH: (HttpUrl) -> String? = { it.encodedPath }
    }
}

/**
 * Whether the backup traffic under way right now is the scheduled backup, which
 * nobody is watching, rather than something the user pressed. Set by
 * [com.wasimaster.wmkeyboard.core.settings.AutoBackupRunner] for the length of an
 * unattended run; its lock means only one run is ever in flight.
 */
object BackupTraffic {
    @Volatile
    var unattended: Boolean = false
}
