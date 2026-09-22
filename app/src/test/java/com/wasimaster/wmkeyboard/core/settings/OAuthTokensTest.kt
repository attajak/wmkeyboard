package com.wasimaster.wmkeyboard.core.settings

import com.sun.net.httpserver.HttpServer
import com.wasimaster.wmkeyboard.core.settings.sink.BackupSinkException
import com.wasimaster.wmkeyboard.core.settings.sink.OAuthTokens
import com.wasimaster.wmkeyboard.core.settings.sink.SinkError
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

/**
 * The refresh-token exchange, against a real HTTP server on localhost.
 *
 * What is under test is the line between "the service said no" (null, which
 * becomes a request to sign in again) and "the service could not be asked"
 * (an IO failure, retried next run). Plain JVM; the client's
 * network-log interceptor is exercised along the way.
 */
class OAuthTokensTest {

    private var server: HttpServer? = null
    private val hits = AtomicInteger()

    private fun serve(status: Int, body: String): String {
        val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        http.createContext("/token") { exchange ->
            hits.incrementAndGet()
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        http.start()
        server = http
        return "http://127.0.0.1:${http.address.port}/token"
    }

    @After
    fun stop() {
        server?.stop(0)
    }

    private fun expectIo(block: () -> Unit) {
        try {
            block()
            fail("expected an IO failure")
        } catch (e: BackupSinkException) {
            assertEquals(SinkError.IO, e.reason)
        }
    }

    @Test
    fun `a token is returned and cached until it expires`() {
        val url = serve(200, """{"access_token":"abc","expires_in":3600}""")
        val tokens = OAuthTokens(url, clientId = "id")
        assertEquals("abc", tokens.accessToken("refresh", nowMs = 0))
        assertEquals("abc", tokens.accessToken("refresh", nowMs = 1_000))
        assertEquals(1, hits.get())
        // Past the lifetime less its minute of slack: asks again.
        tokens.accessToken("refresh", nowMs = 3_541_000)
        assertEquals(2, hits.get())
    }

    @Test
    fun `invalid_grant is a refusal`() {
        val url = serve(400, """{"error":"invalid_grant"}""")
        assertNull(OAuthTokens(url, clientId = "id").accessToken("refresh"))
    }

    @Test
    fun `401 is a refusal`() {
        val url = serve(401, """{"error":"invalid_client"}""")
        assertNull(OAuthTokens(url, clientId = "id").accessToken("refresh"))
    }

    @Test
    fun `a server error is transient, not a lost sign-in`() {
        val url = serve(503, "unavailable")
        expectIo { OAuthTokens(url, clientId = "id").accessToken("refresh") }
    }

    @Test
    fun `a rate limit is transient`() {
        val url = serve(429, "slow down")
        expectIo { OAuthTokens(url, clientId = "id").accessToken("refresh") }
    }

    @Test
    fun `an unreachable server is transient, not a lost sign-in`() {
        // A port nothing listens on: the offline phone at backup time.
        val port = ServerSocket(0).use { it.localPort }
        expectIo { OAuthTokens("http://127.0.0.1:$port/token", clientId = "id").accessToken("refresh") }
    }

    @Test
    fun `a garbled success is transient`() {
        val url = serve(200, "<html>captive portal</html>")
        expectIo { OAuthTokens(url, clientId = "id").accessToken("refresh") }
    }

    @Test
    fun `no refresh token or no client id asks nothing`() {
        val url = serve(200, """{"access_token":"abc"}""")
        assertNull(OAuthTokens(url, clientId = "id").accessToken(""))
        assertNull(OAuthTokens(url, clientId = "").accessToken("refresh"))
        assertEquals(0, hits.get())
    }
}
