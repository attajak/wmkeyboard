package com.wasimaster.wmkeyboard.core.handwriting

/**
 * The seam between the app and ML Kit's digital-ink recogniser.
 * `HandwritingModels` and `HandwritingRecognizerCache` (the facades every
 * caller uses) reach their implementation, `MlKitInkRuntime`, only by
 * reflection — see [InkModule.BRIDGE_CLASS] — because the class lives in a
 * different place per channel, exactly as ML Kit's translator does (see
 * `TranslateRuntime`):
 *
 * - Play builds: in the on-demand `:feature:handwriting` module, downloaded
 *   the first time the handwriting tool asks for a model. The recogniser is
 *   about 6.5 MB of native code per ABI, and most installs never open it.
 * - Every other full build: compiled straight into `:core:intelligence` (the
 *   `src/inkbridge` source directory), where Play's delivery does not exist
 *   to fetch it later.
 *
 * No type here may come from ML Kit: this interface is what the base APK
 * compiles against in Play builds, where that library is absent. Everything
 * that needs no ML Kit type stays on the facade's side: the language-to-tag
 * mapping (from `InkModelTags`, so it works before the module is here), the
 * measured download progress, the stall watchdog.
 */
interface InkRuntime {

    /**
     * Clears Mobile Data Download's stale file-group record, once per process
     * and before the first ink call; see the bridge for why. Every other call
     * here expects it to have run.
     */
    suspend fun prepare()

    /** Whether [tag]'s model is on the device. Unbounded; the facade applies the timeout. */
    suspend fun isDownloaded(tag: String): Boolean

    /**
     * Starts fetching [tag]'s model and hands back the fetch. ML Kit's own
     * Task underneath cannot be cancelled and reports no progress, so the
     * facade polls [PendingDownload.isComplete] while it watches the files
     * land some other way.
     */
    fun startDownload(tag: String): PendingDownload

    /** Removes [tag]'s model, and with it any fetch ML Kit had going for it. */
    suspend fun delete(tag: String)

    /** A recogniser for [tag]'s model; null when ML Kit does not know the tag. */
    fun recognizer(tag: String): Recognizer?

    /** One model fetch in flight. */
    interface PendingDownload {
        val isComplete: Boolean

        /** Returns when the fetch succeeded; throws what it failed with. */
        suspend fun await()
    }

    /** One loaded recogniser. */
    interface Recognizer {
        /**
         * Candidate texts for [strokes], best first; empty when nothing is
         * recognised. Throws when the model is missing or recognition fails.
         */
        suspend fun recognize(
            strokes: List<HwStroke>,
            preContext: String,
            writingAreaWidth: Float,
            writingAreaHeight: Float,
        ): List<String>

        fun close()
    }
}

object InkModule {

    /** FQN the facades instantiate reflectively; keep rule in :app. */
    const val BRIDGE_CLASS = "com.wasimaster.wmkeyboard.core.handwriting.bridge.MlKitInkRuntime"
}
