package com.wasimaster.wmkeyboard.core.tools

import com.sun.net.httpserver.HttpServer
import com.wasimaster.wmkeyboard.core.netlog.NetLog
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress

/**
 * Every [ToolHttp] request lands in the network activity log with real byte
 * counts, its own source and route, and nothing of the query string.
 */
class ToolHttpNetLogTest {

    private lateinit var server: HttpServer
    private val body = ByteArray(4_321) { 'x'.code.toByte() }
    private lateinit var base: String

    @Before
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/ok") { exchange ->
            exchange.requestBody.readBytes()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.createContext("/missing") { exchange ->
            val error = """{"error":{"message":"nope"}}""".toByteArray()
            exchange.sendResponseHeaders(404, error.size.toLong())
            exchange.responseBody.use { it.write(error) }
        }
        server.start()
        base = "http://127.0.0.1:${server.address.port}"
        NetLog.clear()
    }

    @After
    fun stop() {
        server.stop(0)
    }

    @Test
    fun getRecordsBytesSourceAndRouteButNoQuery() {
        ToolHttp.get("$base/ok?q=my+secret+search&key=abc", source = NetSource.GIF, route = "/ok?q=leak")
        val row = NetLog.rows().first()
        assertEquals(NetSource.GIF, row.source)
        assertEquals("GET", row.method)
        assertEquals("127.0.0.1", row.host)
        assertEquals("/ok", row.route)
        assertEquals(200, row.status)
        assertEquals(body.size.toLong(), row.bytesIn)
        assertFalse(row.toString().contains("secret"))
        assertFalse(row.toString().contains("abc"))
    }

    @Test
    fun postCountsWhatWasSent() {
        val payload = """{"text":"hello there"}"""
        ToolHttp.postJson("$base/ok", payload, source = NetSource.AI, route = "/ok")
        val row = NetLog.rows().first()
        assertEquals("POST", row.method)
        assertEquals(payload.toByteArray().size.toLong(), row.bytesOut)
        assertEquals(body.size.toLong(), row.bytesIn)
    }

    @Test
    fun failureIsRecordedWithStatus() {
        val thrown = runCatching { ToolHttp.get("$base/missing", source = NetSource.TRANSLATE) }.exceptionOrNull()
        assertTrue(thrown is ToolHttpException)
        val row = NetLog.rows().first()
        assertEquals(404, row.status)
        assertTrue(row.failed)
        assertEquals(null, row.route)
    }

    @Test
    fun downloadCountsTheFile() {
        val target = kotlin.io.path.createTempFile().toFile()
        try {
            ToolHttp.download("$base/ok", target, source = NetSource.DOWNLOAD_WORDLIST, route = "/ok")
            val row = NetLog.rows().first()
            assertEquals(target.length(), row.bytesIn)
            assertEquals(NetSource.DOWNLOAD_WORDLIST, row.source)
        } finally {
            target.delete()
        }
    }

    @Test
    fun offLogRecordsNothing() {
        NetLog.enabled = false
        try {
            ToolHttp.get("$base/ok", source = NetSource.GIF)
            assertTrue(NetLog.rows().isEmpty())
        } finally {
            NetLog.enabled = true
        }
    }
}
