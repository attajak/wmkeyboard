package com.wasimaster.wmkeyboard.core.settings.sink

import com.wasimaster.wmkeyboard.core.net.BackupTraffic
import com.wasimaster.wmkeyboard.core.netlog.NetLog
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import com.wasimaster.wmkeyboard.core.settings.SmbConfig
import com.wasimaster.wmkeyboard.core.settings.sink.smb.Smb2Client
import com.wasimaster.wmkeyboard.core.settings.sink.smb.SmbStatusException
import com.wasimaster.wmkeyboard.core.settings.sink.smb.SmbUnsafeException
import com.wasimaster.wmkeyboard.core.util.runCancellable
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A [BackupSink] over a Windows or Samba share, through [Smb2Client].
 *
 * A backup is written to a `.part` name and renamed over the final one, which
 * SMB does atomically and with replace, so a half-written file never has a
 * name rotation would count.
 *
 * Every verb is one short connection, like the FTP sink: a backup runs once a
 * day, and a session held open between runs is a session the server dropped.
 */
class SmbSink(private val config: SmbConfig) : BackupSink {

    override val id: String get() = ID

    /** The folder inside the share, with the backslashes SMB wants. */
    private val folder: String = config.path.replace('/', '\\').trim('\\')

    private fun pathOf(name: String): String = if (folder.isEmpty()) name else "$folder\\$name"

    override suspend fun readiness(): Result<Unit> = withContext(Dispatchers.IO) {
        runCancellable {
            if (!config.configured) throw BackupSinkException(SinkError.NOT_CONFIGURED)
            // Creates the folder when it is missing, one level at a time, so a
            // new location works on its first run without a trip to the NAS.
            session { client ->
                var at = ""
                for (part in folder.split('\\').filter { it.isNotEmpty() }) {
                    at = if (at.isEmpty()) part else "$at\\$part"
                    val dir = client.create(
                        at,
                        Smb2Client.ACCESS_READ_ATTRIBUTES or Smb2Client.ACCESS_SYNCHRONIZE,
                        Smb2Client.DISPOSITION_OPEN_IF,
                        Smb2Client.OPTION_DIRECTORY,
                    )
                    client.close(dir)
                }
            }
        }
    }

    override suspend fun write(
        name: String,
        mimeType: String,
        body: (OutputStream) -> Unit,
    ): Result<SinkEntry> = withContext(Dispatchers.IO) {
        runCancellable {
            val bytes = ByteArrayOutputStream().also(body).toByteArray()
            session { client ->
                val part = client.create(
                    pathOf(name + AutoBackupNaming.PART_SUFFIX),
                    Smb2Client.ACCESS_WRITE_DATA or Smb2Client.ACCESS_READ_ATTRIBUTES or
                        Smb2Client.ACCESS_WRITE_ATTRIBUTES or Smb2Client.ACCESS_DELETE or
                        Smb2Client.ACCESS_SYNCHRONIZE,
                    Smb2Client.DISPOSITION_OVERWRITE_IF,
                    Smb2Client.OPTION_NON_DIRECTORY,
                    share = 0,
                )
                try {
                    client.write(part, bytes)
                    client.rename(part, pathOf(name), replace = true)
                } catch (failure: Throwable) {
                    runCatching { client.close(part) }
                    runCatching { deleteFile(client, pathOf(name + AutoBackupNaming.PART_SUFFIX)) }
                    throw failure
                }
                client.close(part)
            }
            SinkEntry(id = name, name = name, sizeBytes = bytes.size.toLong(), modifiedAtMs = 0L)
        }
    }

    override suspend fun list(): Result<List<SinkEntry>> = withContext(Dispatchers.IO) {
        runCancellable {
            session { client ->
                val dir = client.create(
                    folder,
                    Smb2Client.ACCESS_READ_DATA or Smb2Client.ACCESS_READ_ATTRIBUTES or Smb2Client.ACCESS_SYNCHRONIZE,
                    Smb2Client.DISPOSITION_OPEN,
                    Smb2Client.OPTION_DIRECTORY,
                )
                val entries = try {
                    client.list(dir)
                } finally {
                    runCatching { client.close(dir) }
                }
                entries.filter { !it.directory && AutoBackupNaming.isListed(it.name) }
                    .map { SinkEntry(id = it.name, name = it.name, sizeBytes = it.size, modifiedAtMs = it.modifiedMs) }
            }
        }
    }

    override suspend fun read(entry: SinkEntry): Result<InputStream> = withContext(Dispatchers.IO) {
        runCancellable {
            session { client ->
                val file = client.create(
                    pathOf(entry.name),
                    Smb2Client.ACCESS_READ_DATA or Smb2Client.ACCESS_READ_ATTRIBUTES or Smb2Client.ACCESS_SYNCHRONIZE,
                    Smb2Client.DISPOSITION_OPEN,
                    Smb2Client.OPTION_NON_DIRECTORY,
                )
                try {
                    client.readAll(file).inputStream()
                } finally {
                    runCatching { client.close(file) }
                }
            }
        }
    }

    override suspend fun delete(entry: SinkEntry): Result<Unit> = withContext(Dispatchers.IO) {
        runCancellable<Unit> {
            session { client -> deleteFile(client, pathOf(entry.name)) }
        }
    }

    /** Already gone is success: rotation reruns after a failure. */
    private fun deleteFile(client: Smb2Client, path: String) {
        val file = try {
            client.create(
                path,
                Smb2Client.ACCESS_DELETE or Smb2Client.ACCESS_READ_ATTRIBUTES,
                Smb2Client.DISPOSITION_OPEN,
                Smb2Client.OPTION_NON_DIRECTORY or Smb2Client.OPTION_DELETE_ON_CLOSE,
            )
        } catch (missing: SmbStatusException) {
            if (missing.status == Smb2Client.STATUS_OBJECT_NAME_NOT_FOUND) return
            throw missing
        }
        client.close(file)
    }

    /**
     * Connects, signs in, opens the share, runs [block], and maps every way
     * that can fail onto a [SinkError]. One network activity log row each.
     */
    private fun <T> session(block: (Smb2Client) -> T): T {
        val call = NetLog.callTo(
            source = NetSource.BACKUP,
            method = "SMB",
            scheme = "smb",
            host = config.host,
            port = config.port,
            route = config.share,
            background = BackupTraffic.unattended,
        )
        var client: Smb2Client? = null
        try {
            client = Smb2Client.connect(config.host.trim(), config.port, CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS) { sent, received ->
                if (sent > 0) call.sent(sent)
                if (received > 0) call.received(received)
            }
            client.negotiate()
            client.login(config.user, config.password, config.domain.trim(), requireEncryption = config.encrypt)
            client.treeConnect(config.share.trim().trim('\\', '/'))
            return block(client)
        } catch (failure: Throwable) {
            val error = errorOf(failure)
            BackupLog.w("smb failed: ${failure.message.orEmpty()} => $error", failure)
            call.fail(failure)
            throw BackupSinkException(error, failure)
        } finally {
            runCatching { client?.close() }
            call.end()
        }
    }

    companion object {
        const val ID = "smb"

        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000

        /** What an SMB failure means to the user. Public for the tests. */
        fun errorOf(failure: Throwable): SinkError = when (failure) {
            is BackupSinkException -> failure.reason
            is SmbUnsafeException -> SinkError.UNSAFE
            is SmbStatusException -> when (failure.status) {
                Smb2Client.STATUS_LOGON_FAILURE, Smb2Client.STATUS_ACCESS_DENIED,
                Smb2Client.STATUS_ACCOUNT_DISABLED, Smb2Client.STATUS_PASSWORD_EXPIRED,
                Smb2Client.STATUS_ACCOUNT_LOCKED_OUT,
                -> SinkError.PERMISSION_LOST
                Smb2Client.STATUS_BAD_NETWORK_NAME, Smb2Client.STATUS_OBJECT_PATH_NOT_FOUND,
                Smb2Client.STATUS_OBJECT_NAME_NOT_FOUND,
                -> SinkError.TARGET_MISSING
                Smb2Client.STATUS_DISK_FULL, Smb2Client.STATUS_QUOTA_EXCEEDED -> SinkError.OUT_OF_SPACE
                else -> SinkError.IO
            }
            else -> SinkError.IO
        }
    }
}
