package com.wasimaster.wmkeyboard.core.settings

import android.app.job.JobInfo
import com.wasimaster.wmkeyboard.core.settings.sink.DriveAppDataSink
import com.wasimaster.wmkeyboard.core.settings.sink.DropboxSink
import com.wasimaster.wmkeyboard.core.settings.sink.SinkError
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which sentence a cloud failure turns into.
 *
 * The one that matters is PERMISSION_LOST: it tells the user to sign in again,
 * and it is not retried. Anything transient reported as that sends the user to
 * fix a grant that is fine.
 */
class CloudSinkErrorsTest {

    @Test
    fun `Drive names a full account as out of space, not as a lost grant`() {
        assertEquals(SinkError.OUT_OF_SPACE, DriveAppDataSink.statusError(403, "storageQuotaExceeded"))
    }

    @Test
    fun `Drive rate limits are transient`() {
        assertEquals(SinkError.IO, DriveAppDataSink.statusError(403, "userRateLimitExceeded"))
        assertEquals(SinkError.IO, DriveAppDataSink.statusError(403, "rateLimitExceeded"))
        assertEquals(SinkError.IO, DriveAppDataSink.statusError(429))
        assertEquals(SinkError.IO, DriveAppDataSink.statusError(503))
    }

    @Test
    fun `Drive refusals are still a lost grant`() {
        assertEquals(SinkError.PERMISSION_LOST, DriveAppDataSink.statusError(401))
        assertEquals(SinkError.PERMISSION_LOST, DriveAppDataSink.statusError(403, "insufficientPermissions"))
        assertEquals(SinkError.PERMISSION_LOST, DriveAppDataSink.statusError(403, null))
    }

    @Test
    fun `Dropbox 409 is read from the error summary`() {
        assertEquals(
            SinkError.OUT_OF_SPACE,
            DropboxSink.statusError(409, "path/insufficient_space/..."),
        )
        assertEquals(
            SinkError.TARGET_MISSING,
            DropboxSink.statusError(409, "path/not_found/.."),
        )
        assertEquals(
            SinkError.IO,
            DropboxSink.statusError(409, "path/too_many_write_operations/.."),
        )
    }

    @Test
    fun `Dropbox refusals and limits`() {
        assertEquals(SinkError.PERMISSION_LOST, DropboxSink.statusError(401))
        assertEquals(SinkError.IO, DropboxSink.statusError(429))
        assertEquals(SinkError.IO, DropboxSink.statusError(500))
    }

    @Test
    fun `a network destination always waits for a network`() {
        for (destination in BackupDestination.entries - BackupDestination.FOLDER) {
            assertEquals(
                JobInfo.NETWORK_TYPE_ANY,
                AutoBackupScheduler.networkTypeFor(
                    AutoBackupSettings(destination = destination, requireUnmetered = false),
                ),
            )
            assertEquals(
                JobInfo.NETWORK_TYPE_UNMETERED,
                AutoBackupScheduler.networkTypeFor(
                    AutoBackupSettings(destination = destination, requireUnmetered = true),
                ),
            )
        }
    }

    @Test
    fun `a folder needs no network either way`() {
        for (unmetered in listOf(true, false)) {
            assertEquals(
                JobInfo.NETWORK_TYPE_NONE,
                AutoBackupScheduler.networkTypeFor(
                    AutoBackupSettings(destination = BackupDestination.FOLDER, requireUnmetered = unmetered),
                ),
            )
        }
    }
}
