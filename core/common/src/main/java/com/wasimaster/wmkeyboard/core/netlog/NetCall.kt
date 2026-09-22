package com.wasimaster.wmkeyboard.core.netlog

import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * One request on its way, from [NetLog.call] until [end]. The call site feeds it
 * the status and the byte counts; [end] turns it into a log row exactly once,
 * however many times it is reached (a `finally` and an explicit end both call
 * it).
 *
 * Byte counts come from the streams themselves ([countIn] / [countOut]), not
 * from `Content-Length`, which a server may leave out or get wrong.
 */
class NetCall internal constructor(
    val source: NetSource,
    val method: String,
    val scheme: String,
    val host: String,
    val port: Int,
    val route: String?,
    val background: Boolean,
    val startMillis: Long,
    private val startNanos: Long,
    private val sink: (NetCall) -> Unit,
) {
    private val sent = AtomicLong()
    private val received = AtomicLong()
    private val ended = AtomicBoolean(false)

    /** Set when the status line arrives. 0 until then. */
    @Volatile
    var status: Int = 0

    @Volatile
    var error: String? = null
        private set

    /** Captured when the call starts, so a mid-request toggle does not split it. */
    val incognito: Boolean = NetLog.incognito

    val bytesOut: Long get() = sent.get()
    val bytesIn: Long get() = received.get()

    @Volatile
    var durationMs: Long = 0
        private set

    fun sent(bytes: Long) {
        if (bytes > 0) sent.addAndGet(bytes)
    }

    fun received(bytes: Long) {
        if (bytes > 0) received.addAndGet(bytes)
    }

    /** Records [t] as the reason this call failed. Does not end it. */
    fun fail(t: Throwable) {
        if (error == null) error = errorName(t)
    }

    /** Ends the call and records its row. Safe to reach more than once. */
    fun end() {
        if (!ended.compareAndSet(false, true)) return
        durationMs = (System.nanoTime() - startNanos) / NANOS_PER_MILLI
        sink(this)
    }

    /** An input stream that adds everything read through it to [bytesIn]. */
    fun countIn(stream: InputStream): InputStream = object : FilterInputStream(stream) {
        override fun read(): Int = super.read().also { if (it >= 0) received(1) }

        override fun read(b: ByteArray, off: Int, len: Int): Int =
            super.read(b, off, len).also { if (it > 0) received(it.toLong()) }

        override fun skip(n: Long): Long = super.skip(n).also { received(it) }
    }

    /** An output stream that adds everything written through it to [bytesOut]. */
    fun countOut(stream: OutputStream): OutputStream = object : FilterOutputStream(stream) {
        override fun write(b: Int) {
            out.write(b)
            sent(1)
        }

        // FilterOutputStream's own version writes one byte at a time.
        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len)
            sent(len.toLong())
        }
    }

    internal fun toEntry(id: Long): NetEntry = NetEntry(
        id = id,
        firstMillis = startMillis,
        sourceId = source.id,
        method = method,
        host = host,
        port = port,
        scheme = scheme,
        route = route,
        status = status,
        error = error,
        bytesOut = bytesOut,
        bytesIn = bytesIn,
        durationMs = durationMs,
        incognito = incognito,
        background = background,
    )

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L

        /**
         * The class name alone. An exception's message is left out on purpose:
         * a failed connection often quotes the whole URL back, query and all.
         */
        fun errorName(t: Throwable): String = t::class.java.simpleName.ifEmpty { "Exception" }
    }
}

/**
 * Runs [block] as this call: a throw is recorded as the failure and rethrown,
 * and the call ends either way.
 */
inline fun <T> NetCall.track(block: (NetCall) -> T): T {
    try {
        return block(this)
    } catch (t: Throwable) {
        fail(t)
        throw t
    } finally {
        end()
    }
}
