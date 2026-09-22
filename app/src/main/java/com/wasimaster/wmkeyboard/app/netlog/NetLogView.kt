package com.wasimaster.wmkeyboard.app.netlog

import com.wasimaster.wmkeyboard.core.netlog.NetDay
import com.wasimaster.wmkeyboard.core.netlog.NetEntry
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import com.wasimaster.wmkeyboard.core.netlog.NetTally

/*
 * The network activity screen's arithmetic, kept apart from the drawing so it
 * is tested without Compose: which days a period covers, the bars of its chart,
 * its per-feature and per-server totals, and which rows the filters keep.
 */

/** Which stretch the hero card and the two totals cards cover. */
internal enum class NetPeriod(val days: Int) { TODAY(1), WEEK(7), MONTH(30) }

/** One bar of the chart: a local hour for [NetPeriod.TODAY], otherwise a day. */
internal data class NetBucket(
    /** The local hour (0..23) or the local epoch day. */
    val key: Int,
    val bySource: Map<NetSource, NetTally>,
) {
    val total: NetTally = bySource.values.fold(NetTally.EMPTY, NetTally::plus)
}

/** One server on the "Servers contacted" card. */
internal data class NetHost(
    val host: String,
    val tally: NetTally,
    val source: NetSource,
    val firstSeenDay: Int?,
)

/** Everything the upper half of the screen draws for one period. */
internal data class NetSummary(
    val period: NetPeriod,
    val total: NetTally,
    val buckets: List<NetBucket>,
    /** Sorted by bytes, then by requests. */
    val sources: List<Pair<NetSource, NetTally>>,
    /** Sorted by requests, then by bytes. */
    val hosts: List<NetHost>,
)

internal fun summarise(period: NetPeriod, days: List<NetDay>, today: Int, firstSeen: Map<String, Int>): NetSummary {
    val first = today - period.days + 1
    val inRange = days.filter { it.epochDay in first..today }
    val buckets = if (period == NetPeriod.TODAY) {
        val day = inRange.firstOrNull { it.epochDay == today }
        (0 until HOURS).map { hour -> NetBucket(hour, bySource(day?.hours?.get(hour).orEmpty())) }
    } else {
        val byDay = inRange.associateBy { it.epochDay }
        (first..today).map { day -> NetBucket(day, bySource(byDay[day]?.sources.orEmpty())) }
    }
    val sources = HashMap<NetSource, NetTally>()
    val hosts = HashMap<String, NetTally>()
    val hostSource = HashMap<String, String>()
    var total = NetTally.EMPTY
    for (day in inRange.sortedBy { it.epochDay }) {
        total += day.total
        for ((id, tally) in day.sources) {
            val source = NetSource.of(id)
            sources[source] = (sources[source] ?: NetTally.EMPTY) + tally
        }
        for ((host, tally) in day.hosts) hosts[host] = (hosts[host] ?: NetTally.EMPTY) + tally
        hostSource.putAll(day.hostSources)
    }
    return NetSummary(
        period = period,
        total = total,
        buckets = buckets,
        sources = sources.entries
            .sortedWith(compareByDescending<Map.Entry<NetSource, NetTally>> { it.value.bytes }.thenByDescending { it.value.requests })
            .map { it.key to it.value },
        hosts = hosts.entries
            .sortedWith(compareByDescending<Map.Entry<String, NetTally>> { it.value.requests }.thenByDescending { it.value.bytes })
            .map { (host, tally) ->
                NetHost(host, tally, NetSource.of(hostSource[host] ?: NetSource.OTHER.id), firstSeen[host])
            },
    )
}

private fun bySource(map: Map<String, NetTally>): Map<NetSource, NetTally> {
    val out = HashMap<NetSource, NetTally>()
    for ((id, tally) in map) {
        val source = NetSource.of(id)
        out[source] = (out[source] ?: NetTally.EMPTY) + tally
    }
    return out
}

/** The timeline's filters. At most one of [source] and [host] is set at a time. */
internal data class NetFilter(
    val background: Boolean = false,
    val failed: Boolean = false,
    val incognito: Boolean = false,
    val source: NetSource? = null,
    val host: String? = null,
) {
    val isEmpty: Boolean get() = !background && !failed && !incognito && source == null && host == null

    fun keeps(entry: NetEntry): Boolean =
        (!background || entry.background) &&
            (!failed || entry.failed) &&
            (!incognito || entry.incognito) &&
            (source == null || entry.source == source) &&
            (host == null || entry.host == host)
}

/**
 * Whether [host] is on the local network: a private or link-local address, or a
 * name that only resolves there. Decided from the name alone, never by
 * resolving it, since resolving would itself be a request.
 */
internal fun isLocalHost(host: String): Boolean {
    val h = host.lowercase().trimEnd('.')
    if (h == "localhost" || h.endsWith(".local") || h.endsWith(".lan") || h.endsWith(".home.arpa") ||
        h.endsWith(".internal") || h.endsWith(".home")
    ) {
        return true
    }
    val parts = h.split('.')
    if (parts.size == 4 && parts.all { p -> p.toIntOrNull()?.let { it in 0..255 } == true }) {
        val a = parts[0].toInt()
        val b = parts[1].toInt()
        return a == 10 || a == 127 || (a == 192 && b == 168) || (a == 172 && b in 16..31) ||
            (a == 169 && b == 254) || (a == 100 && b in 64..127)
    }
    // IPv6: unique-local fc00::/7 and link-local fe80::/10.
    return h.contains(':') && (h.startsWith("fc") || h.startsWith("fd") || h.startsWith("fe8") || h == "::1")
}

/** The host of [url], lower-cased, or null when it is not an address. */
internal fun hostOf(url: String): String? =
    runCatching { java.net.URL(url.trim()).host.lowercase() }.getOrNull()?.takeIf { it.isNotEmpty() }

private const val HOURS = 24
