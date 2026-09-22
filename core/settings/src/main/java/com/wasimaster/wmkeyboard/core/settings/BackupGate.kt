package com.wasimaster.wmkeyboard.core.settings

import kotlinx.coroutines.sync.Mutex

/**
 * One backup-or-sync run at a time, process-wide.
 *
 * Both runners set [com.wasimaster.wmkeyboard.core.net.BackupTraffic.unattended]
 * for the network log and clear it when they finish. With a lock each, a
 * backup finishing mid-sync cleared it under the sync, and the rest of that
 * sync's traffic was filed as the user's own. One lock makes the flag true of
 * whichever run holds it, and a sync that waits for a backup to finish costs
 * nothing anyone notices.
 */
internal object BackupGate {
    val mutex = Mutex()
}
