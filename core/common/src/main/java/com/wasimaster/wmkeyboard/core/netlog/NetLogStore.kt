package com.wasimaster.wmkeyboard.core.netlog

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.TimeZone

/** Requests, failures and bytes, for one slice of the log (a day, a source, an hour). */
@Serializable
data class NetTally(
    @SerialName("n") val requests: Long = 0,
    @SerialName("f") val failures: Long = 0,
    @SerialName("dn") val bytesIn: Long = 0,
    @SerialName("up") val bytesOut: Long = 0,
) {
    val bytes: Long get() = bytesIn + bytesOut

    operator fun plus(other: NetTally) = NetTally(
        requests + other.requests,
        failures + other.failures,
        bytesIn + other.bytesIn,
        bytesOut + other.bytesOut,
    )

    companion object {
        val EMPTY = NetTally()

        fun of(entry: NetEntry) = NetTally(
            requests = entry.count.toLong(),
            failures = if (entry.failed) entry.count.toLong() else 0,
            bytesIn = entry.bytesIn,
            bytesOut = entry.bytesOut,
        )
    }
}

/**
 * One local calendar day of the log, as totals. Kept apart from the rows and
 * for longer than they are, so the screen's figures and charts stay right after
 * the oldest rows have been dropped to the row cap.
 *
 * [sources] and [hosts] are keyed by [NetSource.id] and host name. [hours] is
 * keyed by local hour (0..23), then source id. [hostSources] says which source
 * last reached each host, for the "Servers contacted" card's subtitle.
 */
@Serializable
data class NetDay(
    @SerialName("d") val epochDay: Int,
    @SerialName("t") val total: NetTally = NetTally.EMPTY,
    @SerialName("s") val sources: Map<String, NetTally> = emptyMap(),
    @SerialName("h") val hosts: Map<String, NetTally> = emptyMap(),
    @SerialName("hs") val hostSources: Map<String, String> = emptyMap(),
    @SerialName("hr") val hours: Map<Int, Map<String, NetTally>> = emptyMap(),
)

/**
 * The network activity log's data: the rows, the per-day totals, and the day
 * each host was first contacted. Pure apart from the two files it is handed, so
 * it is tested without a device; [NetLog] is the process-wide front end.
 *
 * **Bursts are merged when written.** A request that matches one of the last
 * few rows (same source, method, host, route, status class, incognito and
 * background flags) within [MERGE_WINDOW_MS] of that row's last request joins
 * it: the count goes up, the bytes and durations add, and the row moves to the
 * top. The GIF grid fires dozens of thumbnail requests a minute; without this a
 * single GIF session would push the whole day out of the [MAX_ROWS] cap and
 * the timeline would be a wall of identical lines.
 *
 * **Storage.** The rows are a JSON-lines file written by appending: a merged row
 * is appended again under the same id, and loading keeps the last line for each
 * id. The file is rewritten compact once it holds twice the cap. The totals are
 * one small JSON file, rewritten on every flush. Both live in
 * credential-protected storage, so before the first unlock the store has no
 * files: it keeps everything in memory and folds it into the files when
 * [attachFiles] finally hands them over.
 */
class NetLogStore(
    rowsFile: File?,
    daysFile: File?,
    private val zone: () -> TimeZone = { TimeZone.getDefault() },
    private val now: () -> Long = System::currentTimeMillis,
) {
    @Serializable
    private data class DaysSnapshot(
        val since: Long = 0,
        val nextId: Long = 1,
        val days: List<NetDay> = emptyList(),
        val firstSeen: Map<String, Int> = emptyMap(),
    )

    /** What [clear] removed, for [restore] to put back when the user undoes it. */
    class Cleared internal constructor(
        internal val rows: List<NetEntry>,
        internal val days: Map<Int, NetDay>,
        internal val firstSeen: Map<String, Int>,
        internal val since: Long,
    )

    private var rowsFile: File? = rowsFile
    private var daysFile: File? = daysFile

    /** Oldest first, by [NetEntry.lastMillis]. */
    private val rows = ArrayList<NetEntry>()
    private val days = HashMap<Int, NetDay>()
    private val firstSeen = HashMap<String, Int>()
    private var nextId = 1L
    private var sinceMillis = 0L

    /** Rows changed since the last flush, to append. */
    private val pending = LinkedHashMap<Long, NetEntry>()
    private var linesInFile = 0
    private var rewriteRows = false
    private var daysDirty = false

    /** Requests recorded while there were no files, replayed by [attachFiles]. */
    private val backlog = ArrayList<NetEntry>()

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    init {
        load()
        if (sinceMillis == 0L) {
            sinceMillis = now()
            daysDirty = true
        }
    }

    /** When the log started, or was last cleared: the "since" of the empty state. */
    val since: Long @Synchronized get() = sinceMillis

    /** Records one finished request. */
    @Synchronized
    fun add(single: NetEntry) {
        if (rowsFile == null) backlog.add(single)
        record(single)
    }

    private fun record(single: NetEntry) {
        tally(single)
        val merged = mergeTarget(single)
        val row = if (merged >= 0) {
            val old = rows.removeAt(merged)
            old.copy(
                lastMillis = maxOf(old.lastMillis, single.firstMillis),
                count = old.count + single.count,
                bytesIn = old.bytesIn + single.bytesIn,
                bytesOut = old.bytesOut + single.bytesOut,
                durationMs = old.durationMs + single.durationMs,
            )
        } else {
            single.copy(id = nextId++)
        }
        rows.add(row)
        pending[row.id] = row
        trimRows()
    }

    /** Index of the recent row [single] should merge into, or -1. */
    private fun mergeTarget(single: NetEntry): Int {
        val key = single.mergeKey
        val oldest = maxOf(0, rows.size - MERGE_LOOKBACK)
        for (i in rows.indices.reversed()) {
            if (i < oldest) break
            val row = rows[i]
            if (single.firstMillis - row.lastMillis > MERGE_WINDOW_MS) break
            if (row.mergeKey == key) return i
        }
        return -1
    }

    private fun tally(single: NetEntry) {
        val at = single.firstMillis
        val offset = zone().getOffset(at)
        val day = Math.floorDiv(at + offset, DAY_MS).toInt()
        val hour = (Math.floorMod(at + offset, DAY_MS) / HOUR_MS).toInt()
        val t = NetTally.of(single)
        val src = single.sourceId
        val old = days[day] ?: NetDay(day)
        val hosts = if (single.host in old.hosts || old.hosts.size < MAX_HOSTS_PER_DAY) {
            old.hosts + (single.host to (old.hosts[single.host] ?: NetTally.EMPTY) + t)
        } else {
            old.hosts
        }
        val hourMap = old.hours[hour].orEmpty()
        days[day] = old.copy(
            total = old.total + t,
            sources = old.sources + (src to (old.sources[src] ?: NetTally.EMPTY) + t),
            hosts = hosts,
            hostSources = if (single.host in hosts) old.hostSources + (single.host to src) else old.hostSources,
            hours = old.hours + (hour to hourMap + (src to (hourMap[src] ?: NetTally.EMPTY) + t)),
        )
        if (single.host.isNotEmpty() && single.host !in firstSeen && firstSeen.size < MAX_FIRST_SEEN) {
            firstSeen[single.host] = day
        }
        pruneDays(day)
        daysDirty = true
    }

    private fun pruneDays(today: Int) {
        if (days.size <= DAYS_KEPT) return
        days.keys.filter { it <= today - DAYS_KEPT }.forEach { days.remove(it) }
    }

    /**
     * Drops rows past the cap or the age limit. The file is left alone: the
     * dropped lines are dropped again on the next load, and the compacting
     * rewrite in [flush] removes them for good. Rewriting here would rewrite
     * the whole file on every request once the log is full.
     */
    private fun trimRows() {
        val cutoff = now() - MAX_ROW_AGE_MS
        var drop = 0
        while (drop < rows.size && (rows.size - drop > MAX_ROWS || rows[drop].lastMillis < cutoff)) drop++
        if (drop > 0) rows.subList(0, drop).clear()
    }

    /** Every row, oldest first. */
    @Synchronized
    fun rows(): List<NetEntry> = ArrayList(rows)

    /** The per-day totals that are kept, oldest first. */
    @Synchronized
    fun days(): List<NetDay> = days.values.sortedBy { it.epochDay }

    /** The local epoch day each host was first contacted. */
    @Synchronized
    fun firstSeen(): Map<String, Int> = HashMap(firstSeen)

    /** Today's local epoch day, as the store counts days. */
    fun today(): Int {
        val at = now()
        return Math.floorDiv(at + zone().getOffset(at), DAY_MS).toInt()
    }

    /** Empties the log and hands back what was in it, for [restore]. */
    @Synchronized
    fun clear(): Cleared {
        val cleared = Cleared(ArrayList(rows), HashMap(days), HashMap(firstSeen), sinceMillis)
        rows.clear()
        days.clear()
        firstSeen.clear()
        pending.clear()
        backlog.clear()
        sinceMillis = now()
        rewriteRows = true
        daysDirty = true
        return cleared
    }

    /**
     * Puts back what [clear] removed, keeping anything recorded since. A day
     * present in both is summed.
     */
    @Synchronized
    fun restore(cleared: Cleared) {
        val newer = ArrayList(rows)
        rows.clear()
        rows.addAll((cleared.rows + newer).sortedBy { it.lastMillis })
        for ((day, stat) in cleared.days) {
            val mine = days[day]
            days[day] = if (mine == null) stat else sum(stat, mine)
        }
        for ((host, day) in cleared.firstSeen) {
            firstSeen[host] = minOf(day, firstSeen[host] ?: day)
        }
        sinceMillis = minOf(sinceMillis, cleared.since)
        nextId = maxOf(nextId, (rows.maxOfOrNull { it.id } ?: 0L) + 1)
        rewriteRows = true
        daysDirty = true
        trimRows()
    }

    /**
     * Hands the store its files once they can be read (the first unlock after
     * boot). What was recorded in memory until now is replayed on top of what
     * the files already held.
     */
    @Synchronized
    fun attachFiles(rowsFile: File, daysFile: File) {
        if (this.rowsFile != null) return
        val replay = ArrayList(backlog)
        backlog.clear()
        this.rowsFile = rowsFile
        this.daysFile = daysFile
        rows.clear()
        days.clear()
        firstSeen.clear()
        pending.clear()
        load()
        if (sinceMillis == 0L) sinceMillis = replay.minOfOrNull { it.firstMillis } ?: now()
        replay.forEach(::record)
        daysDirty = true
    }

    /** Writes whatever changed. Cheap when nothing did. */
    @Synchronized
    fun flush() {
        val rowsOut = rowsFile
        if (rowsOut != null && (rewriteRows || pending.isNotEmpty())) {
            runCatching {
                rowsOut.parentFile?.mkdirs()
                if (rewriteRows || linesInFile + pending.size > MAX_ROWS * 2) {
                    rowsOut.writeText(rows.joinToString("") { json.encodeToString(NetEntry.serializer(), it) + "\n" })
                    linesInFile = rows.size
                } else {
                    rowsOut.appendText(
                        pending.values.joinToString("") { json.encodeToString(NetEntry.serializer(), it) + "\n" },
                    )
                    linesInFile += pending.size
                }
                pending.clear()
                rewriteRows = false
            }
        }
        val daysOut = daysFile
        if (daysOut != null && daysDirty) {
            runCatching {
                daysOut.parentFile?.mkdirs()
                val snapshot = DaysSnapshot(sinceMillis, nextId, days(), HashMap(firstSeen))
                val tmp = File(daysOut.path + ".tmp")
                tmp.writeText(json.encodeToString(DaysSnapshot.serializer(), snapshot))
                if (!tmp.renameTo(daysOut)) {
                    daysOut.writeText(tmp.readText())
                    tmp.delete()
                }
                daysDirty = false
            }
        }
    }

    private fun load() {
        daysFile?.takeIf { it.exists() }?.let { file ->
            runCatching { json.decodeFromString(DaysSnapshot.serializer(), file.readText()) }.getOrNull()?.let {
                sinceMillis = it.since
                nextId = it.nextId
                it.days.forEach { d -> days[d.epochDay] = d }
                firstSeen.putAll(it.firstSeen)
            }
        }
        rowsFile?.takeIf { it.exists() }?.let { file ->
            val byId = LinkedHashMap<Long, NetEntry>()
            var lines = 0
            runCatching {
                file.forEachLine { line ->
                    if (line.isBlank()) return@forEachLine
                    lines++
                    runCatching { json.decodeFromString(NetEntry.serializer(), line) }.getOrNull()?.let {
                        byId[it.id] = it
                    }
                }
            }
            rows.addAll(byId.values.sortedBy { it.lastMillis })
            linesInFile = lines
            nextId = maxOf(nextId, (byId.keys.maxOrNull() ?: 0L) + 1)
        }
        trimRows()
        pruneDays(today())
    }

    private fun sum(a: NetDay, b: NetDay): NetDay {
        fun merge(x: Map<String, NetTally>, y: Map<String, NetTally>) =
            (x.keys + y.keys).associateWith { (x[it] ?: NetTally.EMPTY) + (y[it] ?: NetTally.EMPTY) }
        return NetDay(
            epochDay = a.epochDay,
            total = a.total + b.total,
            sources = merge(a.sources, b.sources),
            hosts = merge(a.hosts, b.hosts),
            hostSources = a.hostSources + b.hostSources,
            hours = (a.hours.keys + b.hours.keys).associateWith {
                merge(a.hours[it].orEmpty(), b.hours[it].orEmpty())
            },
        )
    }

    companion object {
        const val MAX_ROWS = 2_000
        const val DAYS_KEPT = 31
        const val MAX_ROW_AGE_MS = 30L * 24 * 60 * 60 * 1000
        const val MERGE_WINDOW_MS = 60_000L

        /** How many recent rows a new request is compared against. */
        const val MERGE_LOOKBACK = 16
        const val MAX_HOSTS_PER_DAY = 200
        const val MAX_FIRST_SEEN = 1_000
        private const val DAY_MS = 24L * 60 * 60 * 1000
        private const val HOUR_MS = 60L * 60 * 1000
    }
}
