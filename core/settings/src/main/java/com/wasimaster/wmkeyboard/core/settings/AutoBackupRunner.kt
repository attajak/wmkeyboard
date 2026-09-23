package com.wasimaster.wmkeyboard.core.settings

import android.content.Context
import android.net.Uri
import android.util.Base64
import com.wasimaster.wmkeyboard.core.directboot.DirectBoot
import com.wasimaster.wmkeyboard.core.net.BackupTraffic
import com.wasimaster.wmkeyboard.core.settings.sink.AutoBackupNaming
import com.wasimaster.wmkeyboard.core.settings.sink.BackupLog
import com.wasimaster.wmkeyboard.core.settings.sink.BackupSink
import com.wasimaster.wmkeyboard.core.settings.sink.BackupSinkException
import com.wasimaster.wmkeyboard.core.settings.sink.DriveSink
import com.wasimaster.wmkeyboard.core.settings.sink.DriveAuth
import com.wasimaster.wmkeyboard.core.settings.sink.DropboxSink
import com.wasimaster.wmkeyboard.core.settings.sink.FtpSink
import com.wasimaster.wmkeyboard.core.settings.sink.GitSink
import com.wasimaster.wmkeyboard.core.settings.sink.ImapSink
import com.wasimaster.wmkeyboard.core.settings.sink.OneDriveSink
import com.wasimaster.wmkeyboard.core.settings.sink.S3Sink
import com.wasimaster.wmkeyboard.core.settings.sink.BackupClients
import com.wasimaster.wmkeyboard.core.settings.sink.SafFolderSink
import com.wasimaster.wmkeyboard.core.settings.sink.SftpSink
import com.wasimaster.wmkeyboard.core.settings.sink.SmbSink
import com.wasimaster.wmkeyboard.core.settings.sink.SinkError
import com.wasimaster.wmkeyboard.core.settings.sink.WebDavSink
import com.wasimaster.wmkeyboard.core.util.runCancellable
import java.io.File
import java.io.OutputStreamWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Takes one automatic backup, in the order that cannot lose data.
 *
 * The ordering is the whole point, so it is worth stating plainly. A backup is
 * staged locally, renamed into place locally, **read back and parsed**, and
 * only then copied to the destination — and only after *that* does anything
 * older get deleted. Every one of those steps exists because of a way the
 * obvious version goes wrong:
 *
 * - Writing straight to the destination cannot be atomic. A folder behind a
 *   `DocumentsProvider` may not support rename, and a process killed mid-write
 *   leaves a file that is the right shape and the wrong length.
 * - A half-written bundle that nobody reads back looks exactly like a good one
 *   in a folder listing. Rotation would then count it as a generation and
 *   delete a real one to make room.
 * - A backup taken while the device is locked is not a small backup, it is an
 *   almost empty one: the file-backed sections live in credential-encrypted
 *   storage and simply are not there. Written and rotated, a few lock-screen
 *   sessions would evict every good generation with nothing.
 *
 * One run at a time, process-wide, because "Back up now" and the scheduled job
 * can land together and they would otherwise fight over the staging file.
 */
object AutoBackupRunner {

    /**
     * The point past which the image sections are dropped rather than embedded.
     *
     * Not a tuning knob so much as a ceiling on how much of the heap one export
     * may ask for. See [SettingsRepository.embeddedByteEstimate].
     */
    private const val MAX_EMBEDDED_BYTES = 16L * 1024 * 1024

    /** Sections that carry whole files, and so are the ones worth dropping. */
    private val HEAVY_SECTIONS = setOf(
        ConfigBackup.Section.STICKERS,
        ConfigBackup.Section.ICONS,
        ConfigBackup.Section.WORDLISTS,
        ConfigBackup.Section.VOCAB,
    )

    private const val STAGING_DIR = "backup"


    /** What one run did, for the settings screen and for the job's retry choice. */
    sealed interface Outcome {

        /**
         * At least one location has the backup. [name] is what the file ended
         * up called at the first of them, which is not always what we asked.
         * [failed] names the locations that did not get it, by id.
         */
        data class Done(
            val name: String,
            val skipped: Set<ConfigBackup.Section>,
            val failed: Map<String, SinkError> = emptyMap(),
        ) : Outcome

        /** Nothing to do: turned off, no folder, or the interval has not elapsed. */
        data object Skipped : Outcome

        /**
         * The device was locked, so the bundle would have been nearly empty.
         * Not recorded as an error: nothing is wrong, this was the wrong moment.
         */
        data object Locked : Outcome

        /** [reason] is worth retrying only when it is [SinkError.IO]. */
        data class Failed(val reason: SinkError) : Outcome
    }

    /**
     * Runs a backup if one is due.
     *
     * [force] is the "Back up now" button: it skips the interval check and the
     * enabled switch, but not the lock check or the destination check, because
     * those are about whether a backup can be any good rather than whether one
     * was asked for.
     */
    suspend fun run(
        context: Context,
        repository: SettingsRepository,
        force: Boolean = false,
        nowMs: Long = System.currentTimeMillis(),
    ): Outcome = BackupGate.mutex.withLock {
        // Files this run's traffic as scheduled in the network activity log,
        // unless the user pressed "Back up now". The gate holds one run at a time.
        BackupTraffic.unattended = !force
        try {
            runLocked(context, repository, force, nowMs)
        } finally {
            BackupTraffic.unattended = false
        }
    }

    private suspend fun runLocked(
        context: Context,
        repository: SettingsRepository,
        force: Boolean,
        nowMs: Long,
    ): Outcome {
        val appContext = context.applicationContext
        val settings = repository.settings.first().autoBackup
        val targets = settings.backupTargets

        if (!force && !settings.enabled) return Outcome.Skipped
        if (targets.isEmpty()) return Outcome.Skipped
        if (!force && !isDue(settings, nowMs)) return Outcome.Skipped
        // Before anything else. A locked device cannot produce a real bundle,
        // and an unreal one is worse than none.
        if (!DirectBoot.isUserUnlocked(appContext)) return Outcome.Locked

        BackupLog.d("run force=$force locations=${targets.map { "${it.type.id}:${it.id}" }}")
        val outcome = runCancellable {
            backUp(appContext, repository, targets, settings, nowMs, announce = !force)
        }
        return outcome.getOrElse { failure ->
            fail(appContext, repository, failure, nowMs, announce = !force, location = null)
        }
    }

    /**
     * The sink for [location], or null when this build cannot reach it.
     *
     * Only Drive, Dropbox and OneDrive can be missing: Drive on a build with
     * no Play services compiled in ([DriveAuth.provider] is what `:app` fills
     * in behind that seam), the other two on a build with no client id. A null
     * is that build, not a failure.
     */
    fun sinkFor(context: Context, location: BackupLocation): BackupSink? =
        when (location.type) {
            BackupDestination.FOLDER ->
                SafFolderSink(context, Uri.parse(location.folderUri))
            BackupDestination.WEBDAV -> WebDavSink(
                baseUrl = location.webDavUrl,
                user = location.webDavUser,
                password = location.webDavPassword,
            )
            BackupDestination.DRIVE -> DriveAuth.provider?.let {
                DriveSink(it, location.driveSpace, location.driveFolder)
            }
            BackupDestination.S3 -> S3Sink(location.s3)
            BackupDestination.FTP -> FtpSink(location.ftp)
            BackupDestination.DROPBOX -> BackupClients.dropbox()?.let {
                DropboxSink(location.refreshToken, it)
            }
            BackupDestination.ONEDRIVE -> BackupClients.oneDrive()?.let {
                OneDriveSink(location.refreshToken, it) { rotated ->
                    storeRefreshToken(context, location.id, rotated)
                }
            }
            BackupDestination.SFTP -> SftpSink(location.sftp) { key ->
                storeHostKey(context, location.id, key)
            }
            BackupDestination.SMB -> SmbSink(location.smb)
            BackupDestination.GIT -> GitSink(location.git, BackupInstall.deviceLabel(context))
            BackupDestination.IMAP -> ImapSink(location.imap)
        }

    /**
     * Saves the host key the first SFTP connection saw, so every later one is
     * held to it. Blocking for the same reason as [storeRefreshToken]. Only
     * fills an empty slot: a key already there is never replaced from here.
     */
    private fun storeHostKey(context: Context, id: String, key: String) {
        runCatching {
            runBlocking {
                SettingsRepository(context.applicationContext).updateBackupLocation(id) {
                    if (it.sftp.hostKey.isEmpty()) it.copy(sftp = it.sftp.copy(hostKey = key)) else it
                }
            }
        }.onFailure { BackupLog.w("could not store the SFTP host key for $id", it) }
    }

    /**
     * Saves the refresh token a service sent in place of [BackupLocation.refreshToken].
     *
     * Blocking, and before the call that needed the token goes on: a run the
     * system stops right after would otherwise leave only the old token saved.
     * The old token keeps working until its own expiry, so a lost write costs
     * nothing at once, but three months later it is a location that has to be
     * signed in to again.
     */
    private fun storeRefreshToken(context: Context, id: String, token: String) {
        runCatching {
            runBlocking {
                SettingsRepository(context.applicationContext).updateBackupLocation(id) {
                    it.copy(refreshToken = token)
                }
            }
        }.onFailure { BackupLog.w("could not store the new refresh token for $id", it) }
    }

    /**
     * Whether enough wall-clock time has passed since the last good run.
     *
     * Pure, and public so the tests in `:app` can reach it — the arithmetic has
     * two edge cases that are easy to get wrong and impossible to observe from
     * outside a run.
     */
    fun isDue(settings: AutoBackupSettings, nowMs: Long): Boolean {
        val last = settings.lastRunAtMs
        // A last-run time in the future is a clock that moved, or a bundle from
        // another device that got through. Either way, waiting for it to come
        // round again could mean waiting for weeks; take one now instead.
        if (last <= 0L || last > nowMs) return true
        return nowMs - last >= settings.intervalHours.coerceAtLeast(1) * 60L * 60L * 1000L
    }

    private suspend fun backUp(
        appContext: Context,
        repository: SettingsRepository,
        targets: List<BackupLocation>,
        settings: AutoBackupSettings,
        nowMs: Long,
        announce: Boolean,
    ): Outcome {
        val encrypt = settings.encrypt && settings.passphrase.isNotEmpty()
        val requested = settings.sectionSet
        if (requested.isEmpty()) return Outcome.Skipped

        // Drop the bulky sections before building anything, not after: the
        // failure being avoided is an allocation, so noticing it in the
        // finished string would be noticing it too late.
        val skipped = if (repository.embeddedByteEstimate(requested) > MAX_EMBEDDED_BYTES) {
            requested intersect HEAVY_SECTIONS
        } else {
            emptySet()
        }
        val sections = requested - skipped
        if (sections.isEmpty()) return Outcome.Skipped

        val version = appVersion(appContext)
        val bundle = repository.exportConfig(
            sections = sections,
            // The user's call, on the automatic backup's own switch. The screen
            // strongly suggests a passphrase before it goes on, and warns when
            // it is on without one; it does not quietly drop keys the user
            // asked to keep.
            includeSecrets = settings.backupIncludeSecrets,
            appVersion = version.first,
            appVersionName = version.second,
            // Never the locations themselves. A backup at one location holding
            // the passwords and sign-ins for all of them would hand every one
            // of them to whoever can open that one.
            excludeKeys = setOf(SettingsBackup.AUTO_BACKUP_LOCATIONS),
        )

        // Staged and verified once, then copied to every location: the
        // expensive part and the safety check do not scale with how many
        // places the user wants a copy in.
        val staged = stage(appContext, bundle, settings, encrypt)
        try {
            if (!verify(staged, settings, encrypt)) {
                return Outcome.Failed(SinkError.IO)
            }
            val installId = BackupInstall.id(appContext)
            val name = AutoBackupNaming.name(
                stampMs = nowMs,
                encrypted = encrypt,
                installId = installId,
                device = BackupInstall.deviceLabel(appContext),
            )
            val mime =
                if (encrypt) ConfigBackup.ENCRYPTED_MIME_TYPE else ConfigBackup.MIME_TYPE

            var firstName: String? = null
            val failed = LinkedHashMap<String, SinkError>()
            for (location in targets) {
                val written = copyTo(appContext, location, name, mime, staged, settings.keep, installId)
                written.onSuccess { entry ->
                    if (firstName == null) firstName = entry.name
                    repository.setLocationStatus(location.id) { it.copy(backupAtMs = nowMs, backupError = "") }
                }.onFailure { failure ->
                    val reason = (failure as? BackupSinkException)?.reason ?: SinkError.IO
                    BackupLog.w("location ${location.id} failed: $reason", failure)
                    failed[location.id] = reason
                    repository.setLocationStatus(location.id) { it.copy(backupError = reason.name) }
                    if (announce && reason != SinkError.NOT_CONFIGURED) {
                        BackupNotification.post(appContext, reason, location.type)
                    }
                }
            }

            val landed = firstName
                ?: return fail(
                    appContext,
                    repository,
                    BackupSinkException(failed.values.firstOrNull() ?: SinkError.IO),
                    nowMs,
                    announce = false,
                    location = null,
                )
            // The run counts as done when any location has it: the schedule is
            // about whether a recent copy exists somewhere, and the failures are
            // recorded per location for the screen to show.
            repository.setAutoBackupOutcome(ranAtMs = nowMs, error = "")
            BackupLog.d("done $landed (${staged.length()} B) skipped=$skipped failed=$failed")
            return Outcome.Done(landed, skipped, failed)
        } finally {
            staged.delete()
        }
    }

    /**
     * One location's share of a run: readiness, the copy, then rotation.
     * Rotation only after the copy is confirmed, as ever: it is the one step
     * that destroys a generation.
     */
    private suspend fun copyTo(
        appContext: Context,
        location: BackupLocation,
        name: String,
        mime: String,
        staged: File,
        keep: Int,
        installId: String,
    ): Result<com.wasimaster.wmkeyboard.core.settings.sink.SinkEntry> {
        val sink = sinkFor(appContext, location)
            ?: return Result.failure(BackupSinkException(SinkError.NOT_CONFIGURED))
        sink.readiness().exceptionOrNull()?.let { return Result.failure(it) }
        val written = sink.write(name, mime) { out -> staged.inputStream().use { it.copyTo(out) } }
        if (written.isSuccess) rotate(sink, keep, installId)
        return written
    }

    /**
     * Writes the bundle to local storage and renames it into place.
     *
     * The rename is the atomic step the rest of the app already relies on, and
     * it is available here because this is an ordinary file. It is what makes
     * [verify] meaningful: without it, a reader could see a file still being
     * written and call the short read corruption.
     */
    private suspend fun stage(
        appContext: Context,
        bundle: String,
        settings: AutoBackupSettings,
        encrypt: Boolean,
    ): File = withContext(Dispatchers.IO) {
        val dir = File(appContext.filesDir, STAGING_DIR).apply { mkdirs() }
        val part = File(dir, "staging.part")
        val staged = File(dir, "staging")
        part.delete()
        staged.delete()

        part.outputStream().use { out ->
            if (encrypt) {
                BackupCrypto.encrypt(
                    out = out,
                    passphrase = settings.passphrase.toCharArray(),
                    salt = Base64.decode(settings.kdfSalt, Base64.NO_WRAP),
                    plaintext = bundle,
                )
            } else {
                OutputStreamWriter(out, Charsets.UTF_8).use { it.write(bundle) }
            }
        }
        if (!part.renameTo(staged)) throw BackupSinkException(SinkError.IO)
        staged
    }

    /**
     * Reads the staged file back and parses it.
     *
     * Not paranoia about the disk: this is the check that a truncated or
     * garbled generation never reaches the destination, and so never gets
     * counted as the reason an older one can be deleted.
     */
    private suspend fun verify(
        staged: File,
        settings: AutoBackupSettings,
        encrypt: Boolean,
    ): Boolean = withContext(Dispatchers.IO) {
        val text = if (encrypt) {
            val result = staged.inputStream().use {
                BackupCrypto.decrypt(it, settings.passphrase.toCharArray())
            }
            (result as? BackupCrypto.DecryptResult.Ok)?.text ?: return@withContext false
        } else {
            staged.readText()
        }
        ConfigBackup.decode(text) != null
    }

    /**
     * The running app's version, read from the package rather than taken from
     * a `BuildConfig` this module cannot see. It is stamped into the bundle so
     * a restore can tell it came from a newer app than the one reading it.
     */
    private fun appVersion(appContext: Context): Pair<Int, String> = runCatching {
        val info = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        @Suppress("DEPRECATION")
        info.versionCode to info.versionName.orEmpty()
    }.getOrDefault(0 to "")

    private suspend fun rotate(sink: BackupSink, keep: Int, installId: String) {
        // Backups only. A sink lists sync files too, and a sync file counted
        // here would be a generation to delete.
        val entries = sink.list().getOrNull()?.filter { AutoBackupNaming.isOurs(it.name) } ?: return
        val doomed = AutoBackupNaming.rotation(entries, keep, installId)
        BackupLog.d("rotate: ${entries.size} listed, keep $keep, deleting ${doomed.map { it.name }}")
        for (entry in doomed) {
            sink.delete(entry)
        }
    }

    /**
     * Records a failed run, and says so in the shade when nobody was watching.
     *
     * [announce] is false for the "Back up now" button: the user is looking at
     * the screen that is about to show the same sentence, and a notification
     * for something they just watched fail is the kind that teaches people to
     * turn the channel off. [SinkError.NOT_CONFIGURED] is never announced
     * either — no destination is a setting, not a fault.
     */
    private suspend fun fail(
        context: Context,
        repository: SettingsRepository,
        failure: Throwable,
        nowMs: Long,
        announce: Boolean,
        location: BackupLocation?,
    ): Outcome {
        val reason = (failure as? BackupSinkException)?.reason ?: SinkError.IO
        BackupLog.w("failed: $reason", failure)
        repository.setAutoBackupOutcome(ranAtMs = nowMs, error = reason.name)
        if (announce && reason != SinkError.NOT_CONFIGURED) {
            BackupNotification.post(context, reason, location?.type ?: BackupDestination.FOLDER)
        }
        return Outcome.Failed(reason)
    }
}
