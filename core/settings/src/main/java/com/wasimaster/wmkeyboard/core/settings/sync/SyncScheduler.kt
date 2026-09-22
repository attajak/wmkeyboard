package com.wasimaster.wmkeyboard.core.settings.sync

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import com.wasimaster.wmkeyboard.core.settings.AutoBackupSettings
import com.wasimaster.wmkeyboard.core.settings.SyncMode
import com.wasimaster.wmkeyboard.core.settings.needsNetwork
import com.wasimaster.wmkeyboard.core.settings.targets

/**
 * When sync runs, per [SyncMode]:
 *
 * - **Soon after a change**: a one-off job a minute after the last change to a
 *   synced setting (each change pushes it back, so a burst of edits is one
 *   pass), and a periodic pull every hour for what the other devices changed.
 * - **On a schedule**: the periodic job alone, every [AutoBackupSettings.sync]
 *   interval.
 * - **Only when asked**: nothing here; the Sync now button runs a pass itself.
 *
 * `JobScheduler` for the reasons [com.wasimaster.wmkeyboard.core.settings.AutoBackupScheduler]
 * gives. Both jobs wait for a network when any sync target needs one, and
 * never for a charger: a sync pass is a few small files.
 */
object SyncScheduler {

    /** Arbitrary and permanent, like the backup job's. */
    private const val PERIODIC_JOB_ID = 20260924
    private const val SOON_JOB_ID = 20260925

    /** Same string-component reason as the backup job; see its SERVICE_CLASS. */
    const val SERVICE_CLASS = "com.wasimaster.wmkeyboard.app.SyncJobService"

    private const val HOUR_MS = 60L * 60L * 1000L
    private const val SOON_PULL_HOURS = 1

    /** How long after a change the push waits, so a burst of edits is one pass. */
    const val DEBOUNCE_MS = 60_000L

    /** Latest a debounced push may slip to when the phone is busy. */
    private const val SOON_DEADLINE_MS = 15L * 60L * 1000L

    private fun networkType(settings: AutoBackupSettings): Int =
        if (settings.sync.targets(settings.locations).any { it.type.needsNetwork }) {
            JobInfo.NETWORK_TYPE_ANY
        } else {
            JobInfo.NETWORK_TYPE_NONE
        }

    private fun component(context: Context) = ComponentName(context.packageName, SERVICE_CLASS)

    /** Brings the periodic job in line with [settings]. Leaves a correct job alone, as backup does. */
    fun sync(context: Context, settings: AutoBackupSettings) {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
        val sync = settings.sync
        val on = sync.enabled && sync.targets(settings.locations).isNotEmpty() && sync.mode != SyncMode.MANUAL
        if (!on) {
            runCatching { scheduler.cancel(PERIODIC_JOB_ID) }
            runCatching { scheduler.cancel(SOON_JOB_ID) }
            return
        }
        val hours = if (sync.mode == SyncMode.SOON) SOON_PULL_HOURS else sync.intervalHours.coerceAtLeast(1)
        val intervalMs = hours * HOUR_MS
        val network = networkType(settings)
        val pending = runCatching { scheduler.getPendingJob(PERIODIC_JOB_ID) }.getOrNull()
        if (pending != null && pending.intervalMillis == intervalMs && pending.networkType == network) return
        val job = JobInfo.Builder(PERIODIC_JOB_ID, component(context))
            .setPeriodic(intervalMs)
            .setRequiredNetworkType(network)
            .build()
        runCatching { scheduler.schedule(job) }
        if (sync.mode != SyncMode.SOON) runCatching { scheduler.cancel(SOON_JOB_ID) }
    }

    /**
     * A pass in [delayMs], replacing any one already waiting. For "soon"
     * mode only; the other modes ignore it, which is what makes it safe to
     * call from anywhere a change or an app start is noticed.
     */
    fun requestSoon(context: Context, settings: AutoBackupSettings, delayMs: Long = DEBOUNCE_MS) {
        val sync = settings.sync
        if (!sync.enabled || sync.mode != SyncMode.SOON) return
        if (sync.targets(settings.locations).isEmpty()) return
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
        val job = JobInfo.Builder(SOON_JOB_ID, component(context))
            .setMinimumLatency(delayMs)
            .setOverrideDeadline(delayMs + SOON_DEADLINE_MS)
            .setRequiredNetworkType(networkType(settings))
            .build()
        runCatching { scheduler.schedule(job) }
    }
}
