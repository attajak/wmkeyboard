package com.wasimaster.wmkeyboard.app

import android.app.job.JobParameters
import android.app.job.JobService
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.settings.sync.SyncRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Where a scheduled sync pass runs, for the same reasons the automatic backup
 * has [AutoBackupJobService]: a job gets a wakelock and a process the system
 * means to keep. All the work is in [SyncRunner].
 */
class SyncJobService : JobService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var running: Job? = null

    override fun onStartJob(params: JobParameters?): Boolean {
        running = scope.launch {
            SyncRunner.run(applicationContext, SettingsRepository(applicationContext))
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        running?.cancel()
        // A cut-off pass wrote nothing it had not finished: the remembered
        // state is saved last. Let the job come round again.
        return true
    }
}
