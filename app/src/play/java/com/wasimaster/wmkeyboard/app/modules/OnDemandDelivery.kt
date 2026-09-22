package com.wasimaster.wmkeyboard.app.modules

import android.app.Application
import com.google.android.play.core.splitcompat.SplitCompat
import com.google.android.play.core.splitinstall.SplitInstallManager
import com.google.android.play.core.splitinstall.SplitInstallManagerFactory
import com.google.android.play.core.splitinstall.SplitInstallRequest
import com.google.android.play.core.splitinstall.SplitInstallSessionState
import com.google.android.play.core.splitinstall.model.SplitInstallSessionStatus
import com.wasimaster.wmkeyboard.core.modules.FeatureModules
import com.wasimaster.wmkeyboard.core.modules.ModuleGate
import com.wasimaster.wmkeyboard.core.modules.ModuleState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Play-channel delivery of the on-demand modules that share one gate type:
 * `:feature:litert` and `:feature:handwriting`. This directory replaces
 * `src/noplay/java` when `wmkb.enablePlayStore` is on, the same seam as
 * LlmDelivery and TranslateDelivery beside it, and each module here is
 * driven exactly the way TranslateDelivery drives its own.
 */

/** The splits' names: the last segment of each `:feature:*` project path. */
private const val LITERT_MODULE = "litert"
private const val HANDWRITING_MODULE = "handwriting"

/**
 * Publishes a SplitInstall-backed gate for each module into [FeatureModules].
 * Called once from [WMApplication.onCreate]; nothing here touches the
 * network by itself. One manager and one listener serve both modules: the
 * listener is registered for the life of the process because it is the only
 * thing that hears an install finish, and whatever asked for it may be gone
 * by then.
 */
fun installOnDemandDelivery(app: Application) {
    val manager = SplitInstallManagerFactory.create(app)
    val gates = listOf(LITERT_MODULE, HANDWRITING_MODULE).associateWith { PlayModuleGate(manager, it) }
    manager.registerListener { session: SplitInstallSessionState ->
        for (name in session.moduleNames()) gates[name]?.onSession(session)
        if (session.status() == SplitInstallSessionStatus.INSTALLED) {
            // Once more, now that there is something new to see: the install
            // at attachBaseContext predates this split.
            SplitCompat.install(app)
        }
    }
    FeatureModules.litert = gates.getValue(LITERT_MODULE)
    FeatureModules.handwriting = gates.getValue(HANDWRITING_MODULE)
}

private class PlayModuleGate(
    private val manager: SplitInstallManager,
    private val module: String,
) : ModuleGate {

    private val installedNow: Boolean get() = module in manager.installedModules

    private val mutableState = MutableStateFlow<ModuleState>(
        if (installedNow) ModuleState.Installed else ModuleState.Missing(),
    )

    override val state: StateFlow<ModuleState> = mutableState

    fun onSession(session: SplitInstallSessionState) {
        mutableState.value = when (session.status()) {
            SplitInstallSessionStatus.PENDING,
            SplitInstallSessionStatus.DOWNLOADING,
            SplitInstallSessionStatus.DOWNLOADED,
            SplitInstallSessionStatus.INSTALLING,
            -> ModuleState.Installing(session.bytesDownloaded(), session.totalBytesToDownload())

            SplitInstallSessionStatus.INSTALLED -> ModuleState.Installed

            // Play wants the user to confirm (a very large download, or no
            // Wi-Fi under the user's Play settings). A keyboard has no
            // activity to show that dialog from, so it reads as a failure
            // here and the settings screen's button is the place to retry.
            SplitInstallSessionStatus.REQUIRES_USER_CONFIRMATION,
            SplitInstallSessionStatus.FAILED,
            -> ModuleState.Missing(failed = true)

            SplitInstallSessionStatus.CANCELING,
            SplitInstallSessionStatus.CANCELED,
            -> ModuleState.Missing()

            else -> mutableState.value
        }
    }

    override fun requestInstall() {
        if (installedNow) {
            mutableState.value = ModuleState.Installed
            return
        }
        if (mutableState.value is ModuleState.Installing) return
        mutableState.value = ModuleState.Installing()
        // Re-requesting the module of an active session just returns that
        // session. A failure that never becomes a session (no Play Store,
        // no network) only reaches the failure listener, so it is mapped
        // there as well as in onSession.
        manager.startInstall(SplitInstallRequest.newBuilder().addModule(module).build())
            .addOnFailureListener { mutableState.value = ModuleState.Missing(failed = true) }
    }
}
