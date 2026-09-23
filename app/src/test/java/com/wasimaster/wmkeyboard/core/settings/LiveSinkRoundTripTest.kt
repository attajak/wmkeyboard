package com.wasimaster.wmkeyboard.core.settings

import com.wasimaster.wmkeyboard.core.settings.sink.AutoBackupNaming
import com.wasimaster.wmkeyboard.core.settings.sink.BackupSink
import com.wasimaster.wmkeyboard.core.settings.sink.BackupSinkException
import com.wasimaster.wmkeyboard.core.settings.sink.FtpSink
import com.wasimaster.wmkeyboard.core.settings.sink.GitSink
import com.wasimaster.wmkeyboard.core.settings.sink.ImapSink
import com.wasimaster.wmkeyboard.core.settings.sink.S3Sink
import com.wasimaster.wmkeyboard.core.settings.sink.SftpSink
import com.wasimaster.wmkeyboard.core.settings.sink.SinkError
import com.wasimaster.wmkeyboard.core.settings.sink.SmbSink
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Every [BackupSink] verb against a real server, not a parser fixture.
 *
 * Opt-in: runs only with `WMKB_LIVE_SINKS=1` and `rclone` on the PATH, which
 * it uses to stand up an S3 and an FTP server on localhost over a temporary
 * folder. Skipped everywhere else, CI included.
 *
 * WebDAV is not here because the sink refuses `http://` and a self-signed
 * certificate is refused by the client; the three cloud services are not here
 * because they need an account.
 *
 * The servers rclone cannot play are read from the environment, each skipped
 * when its variable is unset:
 * - `WMKB_LIVE_SSHD=port:user:keyfile`, an OpenSSH `sshd` taking that key
 * - `WMKB_LIVE_SMB=port:share:user:password[:noenc]`, a Samba on localhost
 * - `WMKB_LIVE_IMAP=port:user:password`, plain IMAP on localhost
 * - `WMKB_LIVE_GITEA=url|owner/repo|token[|owner/public-repo]`, a Gitea or Forgejo
 */
class LiveSinkRoundTripTest {

    private val servers = mutableListOf<Process>()
    private val root: File = Files.createTempDirectory("wmkb-sinks").toFile()

    @After
    fun tearDown() {
        servers.forEach { it.destroy() }
        root.deleteRecursively()
    }

    private fun rclone(): String? {
        if (System.getenv("WMKB_LIVE_SINKS") != "1") return null
        return listOf("/opt/homebrew/bin/rclone", "/usr/local/bin/rclone", "/usr/bin/rclone")
            .firstOrNull { File(it).canExecute() }
    }

    private fun freePort() = ServerSocket(0).use { it.localPort }

    private fun start(vararg command: String, port: Int) {
        val process = ProcessBuilder(*command).redirectErrorStream(true)
            .redirectOutput(File(root, "rclone-$port.log")).start()
        servers += process
        repeat(50) {
            if (runCatching { Socket("127.0.0.1", port).close() }.isSuccess) return
            Thread.sleep(100)
        }
        error("rclone did not start on $port: " + File(root, "rclone-$port.log").readText())
    }

    /** The whole rotation cycle: write three, list, read one back, delete two. */
    private fun roundTrip(sink: BackupSink) = runBlocking {
        sink.readiness().getOrThrow()
        val payload = ByteArray(6 * 1024 * 1024) { (it * 31).toByte() }
        val names = (1..3).map { AutoBackupNaming.name(1_754_575_353_000L + it * 86_400_000L, encrypted = true) }
        for (name in names) {
            val written = sink.write(name, "application/octet-stream") { it.write(payload) }.getOrThrow()
            assertEquals(name, written.name)
        }
        val listed = sink.list().getOrThrow()
        assertEquals(names.toSet(), listed.map { it.name }.toSet())
        listed.forEach { assertTrue(it.sizeBytes == -1L || it.sizeBytes == payload.size.toLong()) }

        val back = sink.read(listed.first()).getOrThrow().use { it.readBytes() }
        assertArrayEquals(payload, back)

        for (entry in AutoBackupNaming.rotation(listed, keep = 1)) sink.delete(entry).getOrThrow()
        assertEquals(listOf(names.last()), sink.list().getOrThrow().map { it.name })
        // Idempotent: deleting what is already gone still succeeds.
        sink.delete(listed.first { it.name == names.first() }).getOrThrow()
    }

    @Test
    fun s3() {
        val rclone = rclone()
        assumeTrue("set WMKB_LIVE_SINKS=1 with rclone installed", rclone != null)
        val port = freePort()
        val data = File(root, "s3").apply { File(this, "backups").mkdirs() }
        start(
            rclone!!, "serve", "s3", "--addr", "127.0.0.1:$port",
            "--auth-key", "AKIDEXAMPLE,wJalrXUtnFEMI/K7MDENG", data.path,
            port = port,
        )
        roundTrip(
            S3Sink(
                S3Config(
                    endpoint = "http://127.0.0.1:$port",
                    region = "us-east-1",
                    bucket = "backups",
                    prefix = "wm",
                    accessKeyId = "AKIDEXAMPLE",
                    secretAccessKey = "wJalrXUtnFEMI/K7MDENG",
                    pathStyle = true,
                ),
            ),
        )
    }

    @Test
    fun ftp() {
        val rclone = rclone()
        assumeTrue("set WMKB_LIVE_SINKS=1 with rclone installed", rclone != null)
        val port = freePort()
        val data = File(root, "ftp").apply { File(this, "wm").mkdirs() }
        start(
            rclone!!, "serve", "ftp", "--addr", "127.0.0.1:$port",
            "--user", "wm", "--pass", "secret", data.path,
            port = port,
        )
        roundTrip(
            FtpSink(
                FtpConfig(
                    host = "127.0.0.1",
                    port = port,
                    user = "wm",
                    password = "secret",
                    path = "wm",
                    secure = false,
                ),
            ),
        )
    }

    @Test
    fun sftp() {
        val rclone = rclone()
        assumeTrue("set WMKB_LIVE_SINKS=1 with rclone installed", rclone != null)
        val port = freePort()
        val data = File(root, "sftp").apply { mkdirs() }
        start(
            rclone!!, "serve", "sftp", "--addr", "127.0.0.1:$port",
            "--user", "wm", "--pass", "secret", data.path,
            port = port,
        )
        val config = SftpConfig(host = "127.0.0.1", port = port, user = "wm", password = "secret", path = "backups/wm")
        hostKeyPinning(config)
    }

    @Test
    fun sftpOpenSsh() {
        val spec = System.getenv("WMKB_LIVE_SSHD")?.split(':') ?: return assumeTrue("WMKB_LIVE_SSHD unset", false)
        val (port, user, keyFile) = spec
        val config = SftpConfig(
            host = "127.0.0.1",
            port = port.toInt(),
            user = user,
            privateKey = File(keyFile).readText(),
            path = File(root, "sshd/backups").absolutePath,
        )
        hostKeyPinning(config)
    }

    /** The round trip, then: the key it saw lets a pinned sink in, and a different key is refused. */
    private fun hostKeyPinning(config: SftpConfig) {
        var seen = ""
        roundTrip(SftpSink(config) { seen = it })
        assertTrue("no host key recorded", seen.contains(' '))
        runBlocking {
            SftpSink(config.copy(hostKey = seen)).readiness().getOrThrow()
            val wrong = seen.substringBefore(' ') + " AAAAC3NzaC1lZDI1NTE5AAAAIOMqqnkVzrm0SdG6UOoqKLsabgH5C9okWi0dh2l9GKJl"
            val refused = SftpSink(config.copy(hostKey = wrong)).readiness().exceptionOrNull()
            assertEquals(SinkError.UNSAFE, (refused as BackupSinkException).reason)
        }
    }

    @Test
    fun smb() {
        val spec = System.getenv("WMKB_LIVE_SMB")?.split(':') ?: return assumeTrue("WMKB_LIVE_SMB unset", false)
        val (port, share, user, password) = spec
        // A fifth part, "noenc", says the server stops at SMB 2 and cannot
        // encrypt: then asking for encryption must be refused, not ignored.
        val canEncrypt = spec.getOrNull(4) != "noenc"
        if (!canEncrypt) {
            runBlocking {
                val refused = SmbSink(
                    SmbConfig(host = "127.0.0.1", port = port.toInt(), share = share, user = user, password = password, encrypt = true),
                ).readiness().exceptionOrNull()
                assertEquals(SinkError.UNSAFE, (refused as BackupSinkException).reason)
            }
        }
        for (encrypt in if (canEncrypt) listOf(true, false) else listOf(false)) {
            roundTrip(
                SmbSink(
                    SmbConfig(
                        host = "127.0.0.1", port = port.toInt(), share = share,
                        path = "wm/enc-$encrypt", user = user, password = password, encrypt = encrypt,
                    ),
                ),
            )
        }
        runBlocking {
            val refused = SmbSink(SmbConfig(host = "127.0.0.1", port = port.toInt(), share = share, user = user, password = "wrong"))
                .readiness().exceptionOrNull()
            assertEquals(SinkError.PERMISSION_LOST, (refused as BackupSinkException).reason)
        }
    }

    @Test
    fun imap() {
        val spec = System.getenv("WMKB_LIVE_IMAP")?.split(':') ?: return assumeTrue("WMKB_LIVE_IMAP unset", false)
        val (port, user, password) = spec
        roundTrip(
            ImapSink(
                ImapConfig(
                    host = "127.0.0.1", port = port.toInt(), security = ImapSecurity.NONE,
                    user = user, password = password, mailbox = "WM Keyboard backups ${System.nanoTime()}",
                ),
            ),
        )
    }

    @Test
    fun gitea() {
        val spec = System.getenv("WMKB_LIVE_GITEA")?.split('|') ?: return assumeTrue("WMKB_LIVE_GITEA unset", false)
        val (url, repo, token) = spec
        roundTrip(
            GitSink(
                GitConfig(
                    provider = GitProvider.GITEA, server = url, repository = repo, token = token,
                    path = "backups/${System.nanoTime()}", authorName = "WM Test", authorEmail = "wm@example.com",
                ),
                device = "Test device",
            ),
        )
        // A fourth part names a public repository, which must be refused.
        spec.getOrNull(3)?.let { public ->
            runBlocking {
                val refused = GitSink(GitConfig(provider = GitProvider.GITEA, server = url, repository = public, token = token))
                    .readiness().exceptionOrNull()
                assertEquals(SinkError.UNSAFE, (refused as BackupSinkException).reason)
                GitSink(GitConfig(provider = GitProvider.GITEA, server = url, repository = public, token = token, allowPublic = true))
                    .readiness().getOrThrow()
            }
        }
    }
}
