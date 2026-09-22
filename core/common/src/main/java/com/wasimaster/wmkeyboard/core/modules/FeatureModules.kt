package com.wasimaster.wmkeyboard.core.modules

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first

/**
 * Where one of the app's on-demand modules stands on this install.
 *
 * Play builds carry some native runtimes as Play Feature Delivery modules,
 * fetched the first time a feature needs them; every other build compiles
 * the same code into the base APK. This is the state a facade in a core
 * module reads before it reaches for its runtime, and what a settings screen
 * or panel shows while the module is on its way. The same shape as
 * `TranslateModuleState`, which predates it.
 */
sealed interface ModuleState {

    /** The runtime is here: always, outside Play; after the download, on Play. */
    data object Installed : ModuleState

    /** Not here. [failed] when the last attempt to fetch it went wrong. */
    data class Missing(val failed: Boolean = false) : ModuleState

    /** On its way from Play. Both figures are 0 before Play reports any. */
    data class Installing(val bytes: Long = 0, val totalBytes: Long = 0) : ModuleState
}

/**
 * Whether a module's home is present on this install, and how to fetch it
 * when it is not. Play builds swap a SplitInstall-backed gate into
 * [FeatureModules] at startup (see `installOnDemandDelivery` in `:app`);
 * everywhere else the default [AlwaysInstalled] is the truth.
 */
interface ModuleGate {
    val state: StateFlow<ModuleState>

    /** Idempotent; safe to call while a download is already running. */
    fun requestInstall()

    val installed: Boolean get() = state.value == ModuleState.Installed
}

/** The gate of every channel that compiles its runtimes into the base APK. */
object AlwaysInstalled : ModuleGate {
    override val state: StateFlow<ModuleState> = MutableStateFlow(ModuleState.Installed)

    override fun requestInstall() = Unit
}

/**
 * The on-demand modules, one gate each. A `var` per module rather than a map
 * keyed by name so that a facade cannot ask for a module that does not exist.
 */
object FeatureModules {

    /**
     * `:feature:litert`: the LiteRT interpreter, shared by offline Whisper
     * dictation (`:core:voice`) and the sticker editor's own background
     * remover (`:core:content`).
     */
    @Volatile
    var litert: ModuleGate = AlwaysInstalled

    /** `:feature:handwriting`: ML Kit's digital-ink recogniser. */
    @Volatile
    var handwriting: ModuleGate = AlwaysInstalled

    /**
     * Asks for the module and waits until it is here, reporting Play's byte
     * counts on the way. False when the fetch failed; the caller decides what
     * that means to the user. A cancelled caller leaves the download running:
     * Play owns it, and the next caller finds it further along.
     */
    suspend fun ModuleGate.awaitInstalled(onProgress: (bytes: Long, totalBytes: Long) -> Unit = { _, _ -> }): Boolean {
        if (installed) return true
        requestInstall()
        val settled = state.first { current ->
            if (current is ModuleState.Installing) onProgress(current.bytes, current.totalBytes)
            current == ModuleState.Installed || (current is ModuleState.Missing && current.failed)
        }
        return settled == ModuleState.Installed
    }

    /**
     * Builds a bridge class by name. Throws when it is not loadable, which on
     * Play means the split is installed but this process cannot see it yet.
     * [loader] must be the app's class loader: SplitCompat patches that one,
     * and a library's own would miss the split.
     */
    fun <T> load(className: String, loader: ClassLoader): T {
        @Suppress("UNCHECKED_CAST")
        return Class.forName(className, true, loader).getDeclaredConstructor().newInstance() as T
    }
}
