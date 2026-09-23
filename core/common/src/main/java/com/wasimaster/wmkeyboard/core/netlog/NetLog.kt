package com.wasimaster.wmkeyboard.core.netlog

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.wasimaster.wmkeyboard.core.directboot.DirectBoot
import com.wasimaster.wmkeyboard.core.endpoints.ServiceEndpoint
import com.wasimaster.wmkeyboard.core.endpoints.ServiceEndpoints
import com.wasimaster.wmkeyboard.core.endpoints.ServiceGroup
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The network activity log: every request the keyboard's own code makes, which
 * feature made it, which server it went to, how much it carried, and how it
 * ended. What Settings › Privacy › Network activity shows.
 *
 * Every network path in the app goes through here — `ToolHttp`, the download
 * managers, the backup sinks' OkHttp clients, the media image loader, the FTP
 * and KDE Connect sockets — and `NetworkCallSitesTest` fails the build if a new
 * one appears that does not. What cannot be seen from inside the app (ML Kit,
 * Play Store libraries, downloadable fonts) the screen says so about, rather
 * than pretending to a completeness it does not have.
 *
 * A process-wide object for the same reason as `ServiceEndpoints`: requests are
 * made deep inside download managers and clients that never see a `Context`.
 * The settings app and the keyboard share the process, so they share this log.
 *
 * Usage:
 * ```
 * NetLog.call(NetSource.GIF, "GET", url, route = "/v2/search").track { call ->
 *     connection.inputStream.let(call::countIn)…
 *     call.status = connection.responseCode
 * }
 * ```
 */
object NetLog {

    /** Under `filesDir`. Named in `StorageCategories`, so keep the two in step. */
    const val DIR = "netlog"
    const val ROWS_FILE = "rows.jsonl"
    const val DAYS_FILE = "days.json"

    /** A burst of requests is written this long after its first one. */
    private const val FLUSH_DELAY_MS = 2_000L

    private const val MAX_ROUTE = 160

    /** Published from the settings (Keep a network log). */
    @Volatile
    var enabled: Boolean = true

    /**
     * Published by the keyboard service whenever incognito turns on or off, so
     * the rows made meanwhile carry the mark without every call site asking.
     */
    @Volatile
    var incognito: Boolean = false

    private val store = NetLogStore(null, null)

    private val underWay = ArrayList<NetCall>()
    private val inFlightFlow = MutableStateFlow<List<NetCall>>(emptyList())

    /** Requests under way right now. Drives the screen's live line and the keyboard dot. */
    val inFlight: StateFlow<List<NetCall>> = inFlightFlow.asStateFlow()

    private val versionFlow = MutableStateFlow(0L)

    /** Bumps whenever the log changes, for screens to re-read it. */
    val version: StateFlow<Long> = versionFlow.asStateFlow()

    private val writer = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "netlog-writer").apply { isDaemon = true }
    }
    private val flushQueued = AtomicBoolean(false)

    @Volatile
    private var attached = false
    private var unlockReceiver: BroadcastReceiver? = null

    /**
     * Points the log at its files. Idempotent; called from `WMApplication`, the
     * first app code in any process. The files are read on the log's own
     * thread. Before the first unlock after boot they cannot be read at all, so
     * the log keeps what it records in memory and moves it into the files when
     * the unlock comes.
     */
    @Synchronized
    fun attach(context: Context) {
        if (attached) return
        attached = true
        val app = context.applicationContext ?: context
        InternetPermission.attach(app)
        if (DirectBoot.isUserUnlocked(app)) {
            // Off the main thread: this runs on every process start, the
            // keyboard's included, and the rows file can be a few hundred KB.
            // Anything recorded before it lands is replayed on top.
            writer.execute { attachFiles(app) }
            return
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (intent.action != Intent.ACTION_USER_UNLOCKED) return
                runCatching { app.unregisterReceiver(this) }
                unlockReceiver = null
                writer.execute { attachFiles(app) }
            }
        }
        unlockReceiver = receiver
        runCatching {
            ContextCompat.registerReceiver(
                app, receiver, IntentFilter(Intent.ACTION_USER_UNLOCKED),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }
    }

    private fun attachFiles(context: Context) {
        val dir = File(context.filesDir, DIR)
        store.attachFiles(File(dir, ROWS_FILE), File(dir, DAYS_FILE))
        scheduleFlush()
        changed()
    }

    /**
     * Starts recording a request to [url]. [route] is the address to show after
     * the host; leave it null when the path could carry anything the user typed.
     * Whatever is passed, a query string or fragment in it is cut off.
     *
     * Throws [NoInternetPermissionException] in a build without the internet
     * permission, before anything is recorded: every request passes through
     * here before its socket opens, so this is where such a build stops them.
     */
    fun call(
        source: NetSource,
        method: String,
        url: String,
        route: String? = null,
        background: Boolean = source.background,
    ): NetCall {
        val parsed = runCatching { URL(url) }.getOrNull()
        return callTo(
            source = source,
            method = method,
            scheme = parsed?.protocol ?: url.substringBefore("://", "https"),
            host = parsed?.host.orEmpty(),
            port = parsed?.port ?: -1,
            route = route,
            background = background,
        )
    }

    /**
     * [call] for a connection that is not a URL: an FTP session, a LAN link.
     *
     * [live] false keeps it out of [inFlight]: for a connection that stays up
     * for hours (a KDE Connect link), which would otherwise pin the screen's
     * live line on "talking to…" for as long as the device is paired. It is
     * still recorded when it ends.
     */
    fun callTo(
        source: NetSource,
        method: String,
        scheme: String,
        host: String,
        port: Int = -1,
        route: String? = null,
        background: Boolean = source.background,
        live: Boolean = true,
    ): NetCall {
        InternetPermission.check()
        val call = NetCall(
            source = source,
            method = method.uppercase(),
            scheme = scheme.lowercase(),
            host = host.lowercase(),
            port = port,
            route = cleanRoute(route),
            background = background,
            startMillis = System.currentTimeMillis(),
            startNanos = System.nanoTime(),
            sink = ::finished,
        )
        if (enabled) call.accepted = true
        if (enabled && live) {
            synchronized(underWay) {
                underWay.add(call)
                inFlightFlow.value = ArrayList(underWay)
            }
        }
        return call
    }

    private fun finished(call: NetCall) {
        val (wasLive, nowIdle) = synchronized(underWay) {
            val removed = underWay.remove(call)
            if (removed) inFlightFlow.value = ArrayList(underWay)
            removed to underWay.isEmpty()
        }
        // Checked against the start as well as now: a request that began while
        // the log was off is not recorded even if it was switched on meanwhile.
        if (!enabled || !call.accepted) return
        if (wasLive && nowIdle) lastIdleAt = System.currentTimeMillis()
        store.add(call.toEntry(0))
        scheduleFlush()
        changed()
    }

    /** When the last request under way finished; 0 before the first. */
    @Volatile
    var lastIdleAt: Long = 0L
        private set

    /** Every row, newest first. */
    fun rows(): List<NetEntry> = store.rows().asReversed()

    /** The per-day totals, oldest first. */
    fun days(): List<NetDay> = store.days()

    /** Local epoch day each host was first contacted. */
    fun firstSeen(): Map<String, Int> = store.firstSeen()

    /** Today's local epoch day, as the log counts days. */
    fun today(): Int = store.today()

    /** When the log started or was last cleared. */
    val since: Long get() = store.since

    /** Empties the log. Hand the result to [restore] to undo. */
    fun clear(): NetLogStore.Cleared = store.clear().also {
        scheduleFlush()
        changed()
    }

    fun restore(cleared: NetLogStore.Cleared) {
        store.restore(cleared)
        scheduleFlush()
        changed()
    }

    /** Writes now rather than on the timer, for a caller about to lose the process. */
    fun flushNow() {
        writer.execute { store.flush() }
    }

    private fun scheduleFlush() {
        if (!flushQueued.compareAndSet(false, true)) return
        writer.schedule({
            flushQueued.set(false)
            store.flush()
        }, FLUSH_DELAY_MS, TimeUnit.MILLISECONDS)
    }

    private fun changed() {
        versionFlow.value = versionFlow.value + 1
    }

    /**
     * The source for a request whose call site did not name one: the service
     * the host belongs to ([ServiceEndpoint]), or [NetSource.OTHER]. A fallback
     * only; call sites name their source, since one host can serve several
     * features.
     */
    fun inferSource(url: String): NetSource {
        val host = runCatching { URL(url).host }.getOrNull()?.lowercase() ?: return NetSource.OTHER
        val endpoint = ServiceEndpoint.entries.firstOrNull { endpoint ->
            val base = ServiceEndpoints.base(endpoint).replace("{lang}", "en")
            runCatching { URL(base).host.lowercase() }.getOrNull()?.let { it == host || host.endsWith(".$it") } == true
        } ?: return NetSource.OTHER
        return when (endpoint.group) {
            ServiceGroup.TRANSLATE -> NetSource.TRANSLATE
            ServiceGroup.SEARCH -> NetSource.WEB_SEARCH
            ServiceGroup.MEDIA -> NetSource.GIF
            ServiceGroup.PHOTOS -> NetSource.PHOTOS
            ServiceGroup.WIKIPEDIA -> NetSource.WIKIPEDIA
            ServiceGroup.DICTIONARY -> NetSource.DICTIONARY
            ServiceGroup.VOCABULARY -> NetSource.VOCABULARY
            ServiceGroup.WEATHER -> NetSource.WEATHER
            ServiceGroup.CURRENCY -> NetSource.CURRENCY
            ServiceGroup.AI -> NetSource.AI
            ServiceGroup.DOWNLOADS -> NetSource.ADDONS
            ServiceGroup.UPDATES -> NetSource.UPDATES
        }
    }

    /**
     * The path of [url], for a call site whose path carries nothing the user
     * typed (a fixed API route, a public file name) and so can be shown whole.
     */
    fun pathOf(url: String): String? = runCatching { URL(url).path }.getOrNull()?.takeIf { it.isNotEmpty() }

    internal fun cleanRoute(route: String?): String? {
        val path = route?.substringBefore('?')?.substringBefore('#')?.trim().orEmpty()
        if (path.isEmpty()) return null
        return (if (path.startsWith('/')) path else "/$path").take(MAX_ROUTE)
    }
}
