package com.wasimaster.wmkeyboard.app.netlog

import com.wasimaster.wmkeyboard.core.netlog.NetDay
import com.wasimaster.wmkeyboard.core.netlog.NetEntry
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import com.wasimaster.wmkeyboard.core.netlog.NetTally
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetLogViewTest {

    private fun t(n: Long, bytes: Long = 0) = NetTally(requests = n, bytesIn = bytes)

    private val today = 20_000
    private val days = listOf(
        NetDay(
            epochDay = today,
            total = t(5, 500),
            sources = mapOf(NetSource.GIF.id to t(4, 100), NetSource.AI.id to t(1, 400)),
            hosts = mapOf("api.klipy.com" to t(4, 100), "api.anthropic.com" to t(1, 400)),
            hostSources = mapOf("api.klipy.com" to NetSource.GIF.id, "api.anthropic.com" to NetSource.AI.id),
            hours = mapOf(9 to mapOf(NetSource.GIF.id to t(4, 100)), 14 to mapOf(NetSource.AI.id to t(1, 400))),
        ),
        NetDay(epochDay = today - 3, total = t(2, 50), sources = mapOf(NetSource.WEATHER.id to t(2, 50))),
        NetDay(epochDay = today - 20, total = t(7, 70), sources = mapOf(NetSource.CURRENCY.id to t(7, 70))),
    )

    @Test
    fun todayIsTwentyFourHourlyBars() {
        val s = summarise(NetPeriod.TODAY, days, today, emptyMap())
        assertEquals(24, s.buckets.size)
        assertEquals(4L, s.buckets[9].total.requests)
        assertEquals(1L, s.buckets[14].total.requests)
        assertEquals(0L, s.buckets[0].total.requests)
        assertEquals(5L, s.total.requests)
    }

    @Test
    fun weekCoversSevenDaysAndSortsFeaturesByBytes() {
        val s = summarise(NetPeriod.WEEK, days, today, emptyMap())
        assertEquals(7, s.buckets.size)
        assertEquals(today, s.buckets.last().key)
        assertEquals(7L, s.total.requests)
        assertEquals(listOf(NetSource.AI, NetSource.GIF, NetSource.WEATHER), s.sources.map { it.first })
    }

    @Test
    fun monthIncludesOlderDaysAndServersSortByRequests() {
        val s = summarise(NetPeriod.MONTH, days, today, mapOf("api.klipy.com" to today))
        assertEquals(30, s.buckets.size)
        assertEquals(14L, s.total.requests)
        assertEquals("api.klipy.com", s.hosts.first().host)
        assertEquals(NetSource.GIF, s.hosts.first().source)
        assertEquals(today, s.hosts.first().firstSeenDay)
    }

    @Test
    fun filtersCombine() {
        val bg = NetEntry(1, 0, sourceId = NetSource.LINK_PREVIEW.id, method = "GET", host = "a.com", status = 200, background = true)
        val failed = NetEntry(2, 0, sourceId = NetSource.GIF.id, method = "GET", host = "b.com", status = 500)
        val incog = NetEntry(3, 0, sourceId = NetSource.AI.id, method = "POST", host = "c.com", status = 200, incognito = true)
        val all = listOf(bg, failed, incog)
        assertEquals(3, all.count(NetFilter()::keeps))
        assertEquals(listOf(bg), all.filter(NetFilter(background = true)::keeps))
        assertEquals(listOf(failed), all.filter(NetFilter(failed = true)::keeps))
        assertEquals(listOf(incog), all.filter(NetFilter(incognito = true)::keeps))
        assertEquals(listOf(failed), all.filter(NetFilter(source = NetSource.GIF)::keeps))
        assertEquals(listOf(incog), all.filter(NetFilter(host = "c.com")::keeps))
        assertTrue(all.none(NetFilter(background = true, failed = true)::keeps))
    }

    @Test
    fun localHostsAreRecognisedWithoutResolving() {
        for (h in listOf("192.168.1.5", "10.0.0.2", "172.20.1.1", "ollama.lan", "nas.local", "localhost", "fd12::1", "100.100.1.1")) {
            assertTrue(h, isLocalHost(h))
        }
        for (h in listOf("api.openai.com", "8.8.8.8", "172.32.0.1", "example.lanes.com")) {
            assertFalse(h, isLocalHost(h))
        }
    }

    @Test
    fun addressLineShowsPortOnlyWhenSet() {
        val e = NetEntry(1, 0, sourceId = NetSource.AI.id, method = "POST", host = "ollama.lan", port = 11434, route = "/api/chat")
        assertEquals("POST ollama.lan:11434/api/chat", addressLine(e))
        assertEquals("GET api.klipy.com", addressLine(e.copy(method = "GET", host = "api.klipy.com", port = -1, route = null)))
    }
}
