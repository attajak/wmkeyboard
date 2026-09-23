package com.wasimaster.wmkeyboard.core.settings.sink.imap

import com.wasimaster.wmkeyboard.core.settings.sink.ImapSink
import com.wasimaster.wmkeyboard.core.settings.sink.JavaBase64Compat
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The IMAP client against a scripted server: the replies are fixed in
 * advance, and what the client sent is checked afterwards. IMAP has no
 * out-of-order replies, so a script is a faithful server for one session.
 */
class ImapClientTest {

    private fun script(vararg lines: String): ByteArrayInputStream =
        ByteArrayInputStream(lines.joinToString("") { it }.toByteArray(Charsets.UTF_8))

    @Test
    fun `a literal in a reply is read whole and kept aside`() {
        val header = "X-WMKeyboard-Backup: wmkeyboard-auto-20260923-101500_abcd1234_Pixel.wmconfig.json\r\n\r\n"
        val server = script(
            "* OK [CAPABILITY IMAP4rev1 UIDPLUS] ready\r\n",
            "* 1 FETCH (UID 42 INTERNALDATE \"23-Sep-2026 10:15:00 +0000\" BODY[HEADER.FIELDS (X-WMKEYBOARD-BACKUP)] {${header.length}}\r\n",
            header,
            ")\r\n",
            "w1 OK done\r\n",
        )
        val sent = ByteArrayOutputStream()
        val client = ImapClient(server, sent)
        client.greeting()
        assertTrue("UIDPLUS" in client.capabilities)
        val replies = client.uidFetch("1:*", "(UID)")
        assertEquals(1, replies.size)
        val entry = ImapSink.entryOf(replies.single())!!
        assertEquals("42", entry.id)
        assertEquals("wmkeyboard-auto-20260923-101500_abcd1234_Pixel.wmconfig.json", entry.name)
        assertEquals(1_790_158_500_000L, entry.modifiedAtMs)
        assertEquals("w1 UID FETCH 1:* (UID)\r\n", sent.toString("UTF-8"))
    }

    @Test
    fun `append waits for the go-ahead and returns the APPENDUID`() {
        val server = script(
            "* OK hi\r\n",
            "+ go ahead\r\n",
            "w1 OK [APPENDUID 7 99] appended\r\n",
        )
        val sent = ByteArrayOutputStream()
        val client = ImapClient(server, sent)
        client.greeting()
        assertEquals(99L, client.append("Backups", "hello".toByteArray()))
        assertEquals("w1 APPEND \"Backups\" (\\Seen) {5}\r\nhello\r\n", sent.toString("UTF-8"))
    }

    @Test
    fun `a password with a quote is escaped, and one past ASCII goes as a literal`() {
        assertEquals("\"a\\\"b\\\\c\"", ImapClient.quote("a\"b\\c"))
        assertNull(ImapClient.quote("pässword"))
    }

    @Test
    fun `a refused login is a credentials problem whatever the words`() {
        val server = script("* OK hi\r\n", "w1 NO nope\r\n")
        val client = ImapClient(server, ByteArrayOutputStream())
        client.greeting()
        val failure = runCatching { client.login("me", "bad") }.exceptionOrNull() as ImapException
        assertEquals(ImapClient.KIND_AUTH, failure.kind)
    }

    @Test
    fun `folder names are modified UTF-7`() {
        assertEquals("WM Keyboard backups", ModifiedUtf7.encode("WM Keyboard backups"))
        assertEquals("Entw&APw-rfe", ModifiedUtf7.encode("Entwürfe"))
        assertEquals("A&-B", ModifiedUtf7.encode("A&B"))
        assertEquals("&ZeVnLIqe-", ModifiedUtf7.encode("日本語"))
    }

    @Test
    fun `the composed message carries the name and the body decodes back`() {
        val body = ByteArray(5000) { (it * 31).toByte() }
        val message = String(ImapSink.composeMessage("x.wmconfig.json", "application/json", body, "me@example.com", 0L), Charsets.US_ASCII)
        assertTrue(message.contains("\r\nX-WMKeyboard-Backup: x.wmconfig.json\r\n"))
        assertTrue(message.lines().all { it.length <= 998 })
        val text = message.substringAfter("\r\n\r\n")
        assertArrayEquals(body, JavaBase64Compat.decode(text))
    }
}
