package com.wasimaster.wmkeyboard.core.netlog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.TimeZone

class NetLogStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private var clock = BASE
    private val utc = TimeZone.getTimeZone("UTC")

    private fun store(rows: Boolean = true) = NetLogStore(
        rowsFile = if (rows) tmp.root.resolve("netlog/rows.jsonl") else null,
        daysFile = if (rows) tmp.root.resolve("netlog/days.json") else null,
        zone = { utc },
        now = { clock },
    )

    private fun entry(
        at: Long = clock,
        source: NetSource = NetSource.GIF,
        host: String = "api.klipy.com",
        route: String? = "/v2/search",
        status: Int = 200,
        error: String? = null,
        down: Long = 100,
        up: Long = 10,
    ) = NetEntry(
        id = 0,
        firstMillis = at,
        sourceId = source.id,
        method = "GET",
        host = host,
        route = route,
        status = status,
        error = error,
        bytesIn = down,
        bytesOut = up,
        durationMs = 300,
    )

    @Test
    fun burstMergesIntoOneRow() {
        val s = store()
        repeat(12) { i -> s.add(entry(at = BASE + i * 1_000L)) }
        val rows = s.rows()
        assertEquals(1, rows.size)
        assertEquals(12, rows[0].count)
        assertEquals(1_200L, rows[0].bytesIn)
        assertEquals(120L, rows[0].bytesOut)
        assertEquals(300L, rows[0].meanDurationMs)
        assertEquals(BASE, rows[0].firstMillis)
        assertEquals(BASE + 11_000L, rows[0].lastMillis)
    }

    @Test
    fun failureDoesNotMergeWithSuccesses() {
        val s = store()
        s.add(entry())
        s.add(entry(at = BASE + 1_000, status = 0, error = "SocketTimeoutException"))
        s.add(entry(at = BASE + 2_000))
        val rows = s.rows()
        assertEquals(2, rows.size)
        assertEquals(2, rows.last().count)
        assertTrue(rows.first().failed)
    }

    @Test
    fun gapPastWindowStartsNewRow() {
        val s = store()
        s.add(entry())
        s.add(entry(at = BASE + NetLogStore.MERGE_WINDOW_MS + 1))
        assertEquals(2, s.rows().size)
    }

    @Test
    fun lateArrivalFromLongAgoDoesNotMerge() {
        val s = store()
        s.add(entry(at = BASE + 3 * HOUR))
        // Started three hours earlier, recorded only now that it finished.
        s.add(entry(at = BASE))
        assertEquals(2, s.rows().size)
    }

    @Test
    fun interleavedSourcesStillMerge() {
        val s = store()
        repeat(5) { i ->
            s.add(entry(at = BASE + i * 100L))
            s.add(entry(at = BASE + i * 100L + 50, source = NetSource.MEDIA_IMAGES, host = "media.klipy.com", route = null))
        }
        assertEquals(2, s.rows().size)
        assertTrue(s.rows().all { it.count == 5 })
    }

    @Test
    fun dailyTotalsSurviveRowCap() {
        val s = store()
        val total = NetLogStore.MAX_ROWS + 500
        // Each request a different route, far enough apart never to merge.
        repeat(total) { i ->
            clock = BASE + i * 10L
            s.add(entry(at = clock, route = "/r$i"))
        }
        assertEquals(NetLogStore.MAX_ROWS, s.rows().size)
        val day = s.days().single()
        assertEquals(total.toLong(), day.total.requests)
        assertEquals(total * 100L, day.total.bytesIn)
        assertEquals(total.toLong(), day.sources.getValue(NetSource.GIF.id).requests)
    }

    @Test
    fun hoursAndFirstSeenAreRecorded() {
        val s = store()
        s.add(entry(at = BASE + 3 * HOUR))
        s.add(entry(at = BASE + 3 * HOUR + 10, source = NetSource.TRANSLATE, host = "translate.googleapis.com", route = null))
        val day = s.days().single()
        assertEquals(setOf(3), day.hours.keys)
        assertEquals(2, day.hours.getValue(3).size)
        assertEquals(setOf("api.klipy.com", "translate.googleapis.com"), s.firstSeen().keys)
        assertEquals(NetSource.TRANSLATE.id, day.hostSources["translate.googleapis.com"])
    }

    @Test
    fun persistsAndReloads() {
        val s = store()
        repeat(3) { i -> s.add(entry(at = BASE + i)) }
        s.add(entry(at = BASE + 5, host = "open.er-api.com", source = NetSource.CURRENCY, route = null))
        s.flush()
        val again = store()
        val rows = again.rows()
        assertEquals(2, rows.size)
        assertEquals(3, rows.first { it.host == "api.klipy.com" }.count)
        assertEquals(4L, again.days().single().total.requests)
        assertEquals(s.since, again.since)
    }

    @Test
    fun mergedRowAppendedTwiceLoadsOnce() {
        val s = store()
        s.add(entry())
        s.flush()
        s.add(entry(at = BASE + 10))
        s.flush()
        val lines = tmp.root.resolve("netlog/rows.jsonl").readLines().filter { it.isNotBlank() }
        assertEquals(2, lines.size)
        val again = store()
        assertEquals(1, again.rows().size)
        assertEquals(2, again.rows().single().count)
    }

    @Test
    fun lockedBacklogIsReplayedOnAttach() {
        // What an earlier session already wrote.
        val before = store()
        before.add(entry(host = "api.giphy.com", source = NetSource.GIF))
        before.flush()

        val locked = store(rows = false)
        locked.add(entry(at = BASE + 5 * 60_000L, host = "api.klipy.com"))
        locked.attachFiles(tmp.root.resolve("netlog/rows.jsonl"), tmp.root.resolve("netlog/days.json"))
        assertEquals(2, locked.rows().size)
        assertEquals(2L, locked.days().single().total.requests)
    }

    @Test
    fun clearAndRestore() {
        val s = store()
        s.add(entry())
        clock += 1_000
        val cleared = s.clear()
        assertEquals(0, s.rows().size)
        assertTrue(s.days().isEmpty())
        s.add(entry(at = clock + 90_000, host = "api.anthropic.com", source = NetSource.AI, route = "/v1/messages"))
        s.restore(cleared)
        assertEquals(2, s.rows().size)
        assertEquals(2L, s.days().single().total.requests)
        assertEquals(BASE, s.since)
    }

    @Test
    fun oldRowsAndDaysAge_out() {
        val s = store()
        s.add(entry())
        clock = BASE + NetLogStore.MAX_ROW_AGE_MS + DAY
        s.add(entry(at = clock, route = "/later"))
        assertEquals(listOf("/later"), s.rows().map { it.route })
    }

    @Test
    fun routeCleaningDropsQueryAndFragment() {
        assertEquals("/v1/gifs/search", NetLog.cleanRoute("/v1/gifs/search?q=cats&api_key=secret"))
        assertEquals("/a", NetLog.cleanRoute("a#frag"))
        assertNull(NetLog.cleanRoute("?q=only"))
        assertNull(NetLog.cleanRoute(null))
    }

    @Test
    fun countingStreamsCountRealBytes() {
        val call = NetLog.callTo(NetSource.OTHER, "GET", "https", "example.org")
        val read = call.countIn(ByteArrayInputStream(ByteArray(5_000))).use { it.readBytes() }
        val out = ByteArrayOutputStream()
        call.countOut(out).use { it.write(ByteArray(321)) }
        assertEquals(5_000, read.size)
        assertEquals(5_000L, call.bytesIn)
        assertEquals(321L, call.bytesOut)
        assertEquals(321, out.size())
    }

    @Test
    fun trackRecordsFailureAndRethrows() {
        val call = NetLog.callTo(NetSource.OTHER, "GET", "https", "example.org")
        val thrown = runCatching {
            call.track { throw java.net.SocketTimeoutException("https://example.org/?q=secret") }
        }.exceptionOrNull()
        assertTrue(thrown is java.net.SocketTimeoutException)
        assertEquals("SocketTimeoutException", call.error)
        // The message, which quotes the URL, never reaches the row.
        assertTrue(call.toEntry(1).toString().contains("secret").not())
    }

    private companion object {
        /** 2026-09-22 00:00 UTC. */
        const val BASE = 1_790_035_200_000L
        const val HOUR = 60L * 60 * 1000
        const val DAY = 24 * HOUR
    }
}
