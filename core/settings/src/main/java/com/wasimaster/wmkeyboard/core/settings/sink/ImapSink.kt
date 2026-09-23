package com.wasimaster.wmkeyboard.core.settings.sink

import com.wasimaster.wmkeyboard.core.net.BackupTraffic
import com.wasimaster.wmkeyboard.core.netlog.NetCall
import com.wasimaster.wmkeyboard.core.netlog.NetLog
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import com.wasimaster.wmkeyboard.core.settings.ImapConfig
import com.wasimaster.wmkeyboard.core.settings.ImapSecurity
import com.wasimaster.wmkeyboard.core.settings.sink.imap.ImapClient
import com.wasimaster.wmkeyboard.core.settings.sink.imap.ImapException
import com.wasimaster.wmkeyboard.core.util.runCancellable
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.internal.tls.OkHostnameVerifier

/**
 * A [BackupSink] over a folder in a mail account.
 *
 * Each backup is one message in [ImapConfig.mailbox], whose body is the file
 * in base64 and whose `X-WMKeyboard-Backup` header carries its name. Listing
 * reads that header and nothing else; restoring fetches one body. Rotation
 * flags a message deleted and expunges it.
 *
 * An append is atomic: a message is in the folder whole or not at all. So
 * there is no temporary name, and writing a name again adds a second message
 * rather than replacing the first, which [allowsDuplicateNames] says.
 *
 * TLS certificates are checked against the host name, which a raw
 * `SSLSocket` does not do by itself.
 */
class ImapSink(
    private val config: ImapConfig,
    private val nowMs: () -> Long = System::currentTimeMillis,
) : BackupSink {

    override val id: String get() = ID

    override val allowsDuplicateNames: Boolean get() = true

    private val mailbox: String = config.mailbox.trim().ifEmpty { ImapConfig.DEFAULT_MAILBOX }

    override suspend fun readiness(): Result<Unit> = withContext(Dispatchers.IO) {
        runCancellable<Unit> {
            if (!config.configured) throw BackupSinkException(SinkError.NOT_CONFIGURED)
            session { client, _ -> client.selectOrCreate(mailbox) }
        }
    }

    override suspend fun write(
        name: String,
        mimeType: String,
        body: (OutputStream) -> Unit,
    ): Result<SinkEntry> = withContext(Dispatchers.IO) {
        runCancellable {
            val bytes = ByteArrayOutputStream().also(body).toByteArray()
            val message = composeMessage(name, mimeType, bytes, config.user, nowMs())
            val uid = session { client, _ ->
                client.selectOrCreate(mailbox)
                client.append(mailbox, message)
            }
            SinkEntry(id = uid?.toString().orEmpty(), name = name, sizeBytes = bytes.size.toLong(), modifiedAtMs = nowMs())
        }
    }

    override suspend fun list(): Result<List<SinkEntry>> = withContext(Dispatchers.IO) {
        runCancellable {
            session { client, _ ->
                if (client.selectOrCreate(mailbox) == 0) return@session emptyList()
                client.uidFetch("1:*", "(UID INTERNALDATE BODY.PEEK[HEADER.FIELDS ($HEADER)])")
                    .mapNotNull(::entryOf)
                    .filter { AutoBackupNaming.isListed(it.name) }
            }
        }
    }

    override suspend fun read(entry: SinkEntry): Result<InputStream> = withContext(Dispatchers.IO) {
        runCancellable {
            session { client, _ ->
                client.selectOrCreate(mailbox)
                val reply = client.uidFetch(entry.id, "(BODY.PEEK[TEXT])").firstOrNull()
                    ?: throw BackupSinkException(SinkError.TARGET_MISSING)
                val text = reply.literals.firstOrNull() ?: throw BackupSinkException(SinkError.IO)
                JavaBase64Compat.decode(String(text, Charsets.US_ASCII)).inputStream()
            }
        }
    }

    override suspend fun delete(entry: SinkEntry): Result<Unit> = withContext(Dispatchers.IO) {
        runCancellable<Unit> {
            session { client, _ ->
                client.selectOrCreate(mailbox)
                // UID STORE on a UID that is gone succeeds with no effect,
                // which is exactly the idempotence rotation needs.
                entry.id.toLongOrNull()?.let(client::deleteUid)
            }
        }
    }

    private fun <T> session(block: (ImapClient, NetCall) -> T): T {
        val call = NetLog.callTo(
            source = NetSource.BACKUP,
            method = "IMAP",
            scheme = if (config.security == ImapSecurity.TLS) "imaps" else "imap",
            host = config.host,
            port = config.port,
            route = null,
            background = BackupTraffic.unattended,
        )
        var socket: Socket? = null
        var client: ImapClient? = null
        try {
            val host = config.host.trim()
            var s: Socket = Socket().apply {
                soTimeout = READ_TIMEOUT_MS
                connect(InetSocketAddress(host, config.port), CONNECT_TIMEOUT_MS)
            }
            socket = s
            if (config.security == ImapSecurity.TLS) {
                s = tls(s, host)
                socket = s
            }
            client = ImapClient(counting(s.getInputStream(), call), counting(s.getOutputStream(), call))
            client.greeting()
            if (config.security == ImapSecurity.STARTTLS) {
                client.startTls()
                s = tls(s, host)
                socket = s
                client = ImapClient(counting(s.getInputStream(), call), counting(s.getOutputStream(), call))
                client.capability()
            }
            client.login(config.user, config.password)
            return block(client, call)
        } catch (failure: Throwable) {
            val error = errorOf(failure)
            BackupLog.w("imap failed: ${failure.javaClass.simpleName} => $error", failure)
            call.fail(failure)
            throw BackupSinkException(error, failure)
        } finally {
            runCatching { client?.logout() }
            runCatching { socket?.close() }
            call.end()
        }
    }

    private fun tls(plain: Socket, host: String): Socket {
        val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
        val secure = factory.createSocket(plain, host, plain.port, true) as SSLSocket
        secure.useClientMode = true
        secure.startHandshake()
        if (!OkHostnameVerifier.verify(host, secure.session)) {
            runCatching { secure.close() }
            throw javax.net.ssl.SSLPeerUnverifiedException("certificate is not for $host")
        }
        return secure
    }

    private fun counting(input: InputStream, call: NetCall): InputStream = object : FilterInputStream(input) {
        override fun read(): Int = super.read().also { if (it >= 0) call.received(1) }
        override fun read(b: ByteArray, off: Int, len: Int): Int =
            super.read(b, off, len).also { if (it > 0) call.received(it.toLong()) }
    }

    private fun counting(output: OutputStream, call: NetCall): OutputStream = object : FilterOutputStream(output) {
        override fun write(b: Int) {
            out.write(b)
            call.sent(1)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len)
            call.sent(len.toLong())
        }
    }

    companion object {
        const val ID = "imap"

        /** The header a backup message is found by. Lower-case in FETCH replies on some servers. */
        const val HEADER = "X-WMKeyboard-Backup"

        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 60_000
        private const val LINE = 76

        fun errorOf(failure: Throwable): SinkError = when (failure) {
            is BackupSinkException -> failure.reason
            is javax.net.ssl.SSLPeerUnverifiedException -> SinkError.UNSAFE
            is ImapException -> when {
                failure.kind == ImapClient.KIND_AUTH ||
                    failure.text.contains("[AUTHENTICATIONFAILED]", true) ||
                    failure.text.contains("[AUTHORIZATIONFAILED]", true) -> SinkError.PERMISSION_LOST
                failure.text.contains("[OVERQUOTA]", true) || failure.text.contains("quota", true) -> SinkError.OUT_OF_SPACE
                failure.text.contains("[NONEXISTENT]", true) -> SinkError.TARGET_MISSING
                else -> SinkError.IO
            }
            else -> SinkError.IO
        }

        /**
         * The whole message for one backup. The subject is the file name so the
         * folder reads sensibly in a mail app, and the body is an attachment of
         * that name, so a person can also save it from there by hand.
         */
        fun composeMessage(name: String, mimeType: String, bytes: ByteArray, user: String, nowMs: Long): ByteArray {
            val from = if (user.contains('@')) user.trim() else "wmkeyboard@localhost"
            val date = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US).format(Date(nowMs))
            val id = ByteArray(12).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
            val safeName = name.filter { it.code in 0x20..0x7E && it != '"' }
            val head = buildString {
                append("From: WM Keyboard <").append(from).append(">\r\n")
                append("To: <").append(from).append(">\r\n")
                append("Subject: ").append(safeName).append("\r\n")
                append("Date: ").append(date).append("\r\n")
                append("Message-ID: <").append(id).append("@wmkeyboard>\r\n")
                append(HEADER).append(": ").append(safeName).append("\r\n")
                append("MIME-Version: 1.0\r\n")
                append("Content-Type: ").append(mimeType).append("; name=\"").append(safeName).append("\"\r\n")
                append("Content-Disposition: attachment; filename=\"").append(safeName).append("\"\r\n")
                append("Content-Transfer-Encoding: base64\r\n")
                append("\r\n")
            }
            val out = ByteArrayOutputStream(head.length + bytes.size * 4 / 3 + bytes.size / 40 + 16)
            out.write(head.toByteArray(Charsets.US_ASCII))
            val encoded = JavaBase64Compat.encode(bytes)
            var at = 0
            while (at < encoded.length) {
                val end = minOf(encoded.length, at + LINE)
                out.write(encoded.substring(at, end).toByteArray(Charsets.US_ASCII))
                out.write("\r\n".toByteArray(Charsets.US_ASCII))
                at = end
            }
            return out.toByteArray()
        }

        private val UID = Regex("\\bUID (\\d+)", RegexOption.IGNORE_CASE)
        private val INTERNALDATE = Regex("INTERNALDATE \"([^\"]+)\"", RegexOption.IGNORE_CASE)

        /** One listing entry from a FETCH reply, or null when it is not one of ours. */
        fun entryOf(reply: ImapClient.Reply): SinkEntry? {
            val uid = UID.find(reply.text)?.groupValues?.get(1) ?: return null
            val header = reply.literals.firstOrNull()?.let { String(it, Charsets.UTF_8) } ?: return null
            val name = header.lineSequence()
                .firstOrNull { it.startsWith("$HEADER:", ignoreCase = true) }
                ?.substringAfter(':')?.trim()
                ?.takeIf { it.isNotEmpty() } ?: return null
            val date = INTERNALDATE.find(reply.text)?.groupValues?.get(1)?.let { raw ->
                runCatching { SimpleDateFormat("d-MMM-yyyy HH:mm:ss Z", Locale.US).parse(raw.trim())?.time }.getOrNull()
            } ?: 0L
            return SinkEntry(id = uid, name = name, sizeBytes = -1L, modifiedAtMs = date)
        }
    }
}
