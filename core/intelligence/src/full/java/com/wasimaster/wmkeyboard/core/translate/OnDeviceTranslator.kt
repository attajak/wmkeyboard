package com.wasimaster.wmkeyboard.core.translate

import android.app.DownloadManager
import android.content.Context
import android.net.ConnectivityManager
import android.os.SystemClock
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import com.google.mlkit.nl.languageid.LanguageIdentifier
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.wasimaster.wmkeyboard.core.mlkit.MlKitInit
import com.wasimaster.wmkeyboard.core.util.runCancellable
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Awaits a Play-services Task without the coroutines-play-services artifact. */
private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { if (cont.isActive) cont.resume(it) }
    addOnFailureListener { if (cont.isActive) cont.resumeWithException(it) }
    addOnCanceledListener { if (cont.isActive) cont.cancel() }
}

/**
 * ML Kit's on-device translator behind the translate tool: the model store,
 * the language identifier that stands in for "detect language", and the
 * translators themselves.
 *
 * One object for the process, because the keyboard and the settings app share
 * it and must agree. A model the settings list is downloading has to show as
 * downloading in the panel, and a model the panel is translating with must not
 * be deleted out from under it by the list. Both read [models] and both go
 * through the same lock.
 */
object OnDeviceTranslator {

    /** False in the lite build, where none of this exists. */
    const val AVAILABLE: Boolean = true

    /** Longest text translated in one go, the same cut the online clients make. */
    private const val MAX_CHARS = 2500

    /**
     * The identifier is asked for everything it considers possible, however
     * unlikely, because a weak candidate that agrees with the keyboard's own
     * language is worth more than a refusal. The confident cut is made in
     * [OfflineTranslatePlan.resolveSource], not here.
     */
    private const val IDENTIFY_FLOOR = 0.01f

    private const val STATUS_TIMEOUT_MS = 20_000L
    private const val TRANSLATE_TIMEOUT_MS = 30_000L
    private const val PROGRESS_POLL_MS = 700L

    /** How long a download may bring in nothing before it is called off. */
    private const val DOWNLOAD_STALL_MS = 90_000L

    /**
     * The limit for a download whose bytes cannot be seen at all (see
     * [systemDownloadProgress]). With nothing to tell slow from stuck, only
     * the clock is left, and it has to allow thirty megabytes on a bad line.
     */
    private const val DOWNLOAD_BLIND_LIMIT_MS = 10 * 60_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Lazy for the reason the ink catalogue's is: `getInstance` throws when ML
     * Kit's init provider was skipped (a process started before first unlock),
     * and a throw out of an object initializer poisons the class for good.
     */
    private val manager: RemoteModelManager by lazy { RemoteModelManager.getInstance() }

    private val _models = MutableStateFlow<Map<String, OfflineModelState>>(emptyMap())

    /**
     * Every model whose state is known, by model code. A language missing from
     * the map has not been looked at yet; call [refresh]. English is always
     * [OfflineModelState.Downloaded].
     */
    val models: StateFlow<Map<String, OfflineModelState>> = _models.asStateFlow()

    private val downloads = mutableMapOf<String, Job>()

    /**
     * Guards the translators against the two things that would break them
     * mid-sentence: another caller swapping the cached pair, and a delete
     * taking a model file away. Translation, release and delete all hold it.
     */
    private val engineLock = Mutex()

    private var translator: Translator? = null
    private var translatorPair: Pair<String, String>? = null

    private var identifier: LanguageIdentifier? = null

    private fun remoteModel(code: String): TranslateRemoteModel =
        TranslateRemoteModel.Builder(code).build()

    /**
     * Re-reads which models are on the device. Cheap, and safe to call on every
     * panel open: it never touches a language that is mid-download.
     */
    suspend fun refresh(context: Context): Set<String> {
        MlKitInit.ensure(context)
        val onDevice = withTimeoutOrNull(STATUS_TIMEOUT_MS) {
            runCancellable {
                manager.getDownloadedModels(TranslateRemoteModel::class.java).await()
                    .map { it.language }
                    .toSet()
            }.getOrNull()
        }
        if (onDevice == null) {
            // ML Kit did not answer: not initialised, or wedged. "Checking…"
            // for ever is the wrong thing to draw, so what is not known to be
            // here is shown as not here, with its button.
            _models.update { current ->
                OfflineTranslateLanguages.SUPPORTED.associateWith { code ->
                    current[code] ?: if (code == OfflineTranslateLanguages.PIVOT) {
                        OfflineModelState.Downloaded
                    } else {
                        OfflineModelState.Missing()
                    }
                }
            }
            return downloadedNow()
        }
        val downloaded = onDevice + OfflineTranslateLanguages.PIVOT
        _models.update { current ->
            OfflineTranslateLanguages.SUPPORTED.associateWith { code ->
                val known = current[code]
                when {
                    known is OfflineModelState.Downloading -> known
                    code in downloaded -> OfflineModelState.Downloaded
                    // Keep a failure on show until the next attempt replaces it.
                    known is OfflineModelState.Missing -> known
                    else -> OfflineModelState.Missing()
                }
            }
        }
        return downloaded
    }

    private fun downloadedNow(): Set<String> =
        _models.value.filterValues { it is OfflineModelState.Downloaded }.keys +
            OfflineTranslateLanguages.PIVOT

    /**
     * Starts fetching [code]'s model. Returns at once; follow it in [models].
     * A second call for a language already on its way does nothing, so the
     * panel and the settings list can both offer the button.
     *
     * The download belongs to this object, not to whoever pressed the button:
     * the panel closes and the settings screen is left long before thirty
     * megabytes arrive.
     */
    fun download(context: Context, code: String) {
        if (code == OfflineTranslateLanguages.PIVOT || code !in OfflineTranslateLanguages.SUPPORTED) return
        val app = context.applicationContext
        synchronized(downloads) {
            if (downloads[code]?.isActive == true) return
            _models.update { it + (code to OfflineModelState.Downloading()) }
            downloads[code] = scope.launch {
                val ok = runCancellable { fetch(app, code) }.isSuccess
                synchronized(downloads) { downloads.remove(code) }
                _models.update {
                    it + (code to if (ok) OfflineModelState.Downloaded else OfflineModelState.Missing(failed = true))
                }
            }
        }
    }

    private suspend fun fetch(context: Context, code: String) {
        MlKitInit.ensure(context)
        // ML Kit hands the fetch to the system DownloadManager, which treats
        // "no network" as a reason to wait, indefinitely and in silence. A
        // button that spins forever on a plane is worse than one that says no.
        if (!hasNetwork(context)) throw IOException("No network for the $code translation model")
        val model = remoteModel(code)
        val task = manager.download(model, DownloadConditions.Builder().build())
        val startedAt = SystemClock.elapsedRealtime()
        var lastBytes = 0L
        var lastMovedAt = startedAt
        var visible = false
        while (!task.isComplete) {
            delay(PROGRESS_POLL_MS)
            val now = SystemClock.elapsedRealtime()
            val progress = systemDownloadProgress(context, code)
            if (progress != null) visible = true
            if (progress != null && progress.first > lastBytes) {
                lastBytes = progress.first
                lastMovedAt = now
            }
            _models.update { current ->
                if (current[code] !is OfflineModelState.Downloading) return@update current
                current + (
                    code to OfflineModelState.Downloading(
                        bytes = lastBytes,
                        totalBytes = progress?.second?.takeIf { it > 0 } ?: 0L,
                    )
                )
            }
            val stuck = if (visible) now - lastMovedAt > DOWNLOAD_STALL_MS else now - startedAt > DOWNLOAD_BLIND_LIMIT_MS
            if (stuck) {
                // Left alone it would finish some day into a model nobody is
                // waiting for, or block the next attempt behind itself.
                withdraw(context, code)
                throw IOException("Translation model $code stopped downloading")
            }
        }
        task.await()
    }

    /**
     * Calls off a download in progress. ML Kit has no cancel of its own, so
     * this does both halves by hand: deletes the model, which is ML Kit's own
     * way of forgetting a fetch, and removes the system download behind it, so
     * thirty megabytes nobody is waiting for do not keep arriving.
     */
    fun cancelDownload(context: Context, code: String) {
        val app = context.applicationContext
        val job = synchronized(downloads) { downloads.remove(code) } ?: return
        job.cancel()
        _models.update { it + (code to OfflineModelState.Missing()) }
        scope.launch { withdraw(app, code) }
    }

    private suspend fun withdraw(context: Context, code: String) {
        runCancellable { manager.deleteDownloadedModel(remoteModel(code)).await() }
        withContext(Dispatchers.IO) {
            runCancellable {
                val dm = context.getSystemService(DownloadManager::class.java) ?: return@runCancellable
                val ids = mutableListOf<Long>()
                dm.query(activeDownloads())?.use { cursor ->
                    val idColumn = cursor.getColumnIndex(DownloadManager.COLUMN_ID)
                    val uriColumn = cursor.getColumnIndex(DownloadManager.COLUMN_URI)
                    if (idColumn < 0 || uriColumn < 0) return@use
                    while (cursor.moveToNext()) {
                        if (isModelAddress(cursor.getString(uriColumn).orEmpty(), code)) ids += cursor.getLong(idColumn)
                    }
                }
                if (ids.isNotEmpty()) dm.remove(*ids.toLongArray())
            }
        }
    }

    private fun activeDownloads(): DownloadManager.Query = DownloadManager.Query().setFilterByStatus(
        DownloadManager.STATUS_PENDING or DownloadManager.STATUS_RUNNING or DownloadManager.STATUS_PAUSED,
    )

    /** Removes [code]'s model from the device. English cannot be removed. */
    suspend fun delete(context: Context, code: String) {
        if (code == OfflineTranslateLanguages.PIVOT) return
        MlKitInit.ensure(context)
        engineLock.withLock {
            // A translator holds its model files open. Close first, or the
            // delete succeeds on paper and the next translate reads a ghost.
            if (translatorPair?.let { code == it.first || code == it.second } == true) closeTranslator()
            runCancellable { manager.deleteDownloadedModel(remoteModel(code)).await() }
        }
        refresh(context)
        _models.update { current ->
            if (current[code] is OfflineModelState.Downloaded) current else current + (code to OfflineModelState.Missing())
        }
    }

    /**
     * Translates [text] into [target] (a picker code such as `bn` or `zh-CN`).
     *
     * [sourceOverride] is the user's own choice of source, blank for detect.
     * [hints] are the languages the user types in, the active one first; see
     * [OfflineTranslatePlan.resolveSource] for what they are used for.
     */
    suspend fun translate(
        context: Context,
        text: String,
        target: String,
        sourceOverride: String = "",
        hints: List<String> = emptyList(),
    ): OfflineTranslateResult {
        val input = text.take(MAX_CHARS)
        val targetCode = OfflineTranslateLanguages.modelCode(target)
            ?: return OfflineTranslateResult.Unsupported(target, romanized = false)
        return try {
            MlKitInit.ensure(context)
            val candidates = if (sourceOverride.isBlank()) identify(input) else emptyList()
            when (
                val source = OfflineTranslatePlan.resolveSource(sourceOverride, candidates, hints, target)
            ) {
                is OfflineTranslatePlan.Source.Unknown -> OfflineTranslateResult.Undetermined
                is OfflineTranslatePlan.Source.Unreadable ->
                    OfflineTranslateResult.Unsupported(source.tag, source.romanized)
                is OfflineTranslatePlan.Source.Known ->
                    translateKnown(context, input, source.code, targetCode, source.guessed)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            // ML Kit throws unchecked from getClient when its context never
            // came up; a keyboard must not die over a translator.
            OfflineTranslateResult.Failed(e)
        }
    }

    private suspend fun translateKnown(
        context: Context,
        text: String,
        source: String,
        target: String,
        guessed: Boolean,
    ): OfflineTranslateResult {
        // Already in the target language: hand it back, as the online
        // services do, rather than fail to build an en-to-en translator.
        if (source == target) return OfflineTranslateResult.Success(text, source, guessed)
        // The live query follows typing, so the shared map answers "is it
        // here?" once it has been filled; asking ML Kit on every keystroke
        // would be asking the same question sixty times a minute.
        val known = _models.value
        val downloaded = if (known.isEmpty()) refresh(context) else downloadedNow()
        val needed = OfflineTranslateLanguages.modelsNeeded(source, target)
        val missing = needed.filter { it !in downloaded }
        if (missing.isNotEmpty()) return OfflineTranslateResult.NeedsModels(source, target, missing)

        val segments = OfflineTranslatePlan.segments(text)
        val translated = try {
            translateSegments(segments, source, target)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            // The map can be stale: a model removed behind its back (storage
            // cleared, another process) fails here rather than above. Ask ML
            // Kit itself before calling it an engine failure.
            engineLock.withLock { closeTranslator() }
            val fresh = refresh(context)
            val stillMissing = needed.filter { it !in fresh }
            if (stillMissing.isEmpty()) throw e
            return OfflineTranslateResult.NeedsModels(source, target, stillMissing)
        }
        return OfflineTranslateResult.Success(OfflineTranslatePlan.join(segments, translated), source, guessed)
    }

    private suspend fun translateSegments(
        segments: List<OfflineTranslatePlan.Segment>,
        source: String,
        target: String,
    ): List<String> = engineLock.withLock {
        val engine = translatorFor(source, target)
        segments.filter { it.body.isNotEmpty() }.map { segment ->
            withTimeoutOrNull(TRANSLATE_TIMEOUT_MS) { engine.translate(segment.body).await() }
                ?: throw IOException("On-device translation timed out")
        }
    }

    /** Callers hold [engineLock]. One pair at a time: each holds tens of megabytes. */
    private fun translatorFor(source: String, target: String): Translator {
        translator?.takeIf { translatorPair == source to target }?.let { return it }
        closeTranslator()
        val created = Translation.getClient(
            TranslatorOptions.Builder().setSourceLanguage(source).setTargetLanguage(target).build(),
        )
        translator = created
        translatorPair = source to target
        return created
    }

    private fun closeTranslator() {
        runCatching { translator?.close() }
        translator = null
        translatorPair = null
    }

    private suspend fun identify(text: String): List<OfflineTranslatePlan.Candidate> {
        val client = identifier ?: LanguageIdentification.getClient(
            LanguageIdentificationOptions.Builder().setConfidenceThreshold(IDENTIFY_FLOOR).build(),
        ).also { identifier = it }
        // Identification failing is not translation failing: the hints can
        // still settle the source, and the user can always pick it.
        return withTimeoutOrNull(STATUS_TIMEOUT_MS) {
            runCancellable {
                client.identifyPossibleLanguages(text).await()
                    .map { OfflineTranslatePlan.Candidate(it.languageTag, it.confidence) }
            }.getOrNull()
        }.orEmpty()
    }

    /**
     * Lets go of the loaded models. The panel calls this when it closes: a
     * keyboard process that keeps sixty megabytes of translator for a tool
     * nobody has open is first in line when Android wants memory back.
     */
    fun release() {
        scope.launch {
            engineLock.withLock {
                closeTranslator()
                runCatching { identifier?.close() }
                identifier = null
            }
        }
    }

    private fun hasNetwork(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return true
        return runCatching { cm.activeNetwork != null }.getOrDefault(true)
    }

    /**
     * Bytes so far and the total for [code]'s model, read off the system
     * download ML Kit started, or null when no such download can be found.
     *
     * ML Kit's API reports no progress at all. But translate 17.0.3 fetches
     * its models through `DownloadManager` (read off its bytecode: it enqueues
     * `…/translate/offline/v5/high/<version>/<pair>.zip` and waits for
     * `DOWNLOAD_COMPLETE`), and an app may query its own downloads, so the
     * numbers exist; they are just somebody else's. The row is matched by
     * address: model files are named after their language pair, `bn_en.zip`.
     * A later release that moves off `DownloadManager` costs the bar, not the
     * download: see [DOWNLOAD_BLIND_LIMIT_MS].
     */
    private suspend fun systemDownloadProgress(context: Context, code: String): Pair<Long, Long>? =
        withContext(Dispatchers.IO) {
            runCancellable {
                val dm = context.getSystemService(DownloadManager::class.java) ?: return@runCancellable null
                dm.query(activeDownloads())?.use { cursor ->
                    val uriColumn = cursor.getColumnIndex(DownloadManager.COLUMN_URI)
                    val bytesColumn = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                    val totalColumn = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                    if (uriColumn < 0 || bytesColumn < 0 || totalColumn < 0) return@use null
                    while (cursor.moveToNext()) {
                        val uri = cursor.getString(uriColumn).orEmpty()
                        if (isModelAddress(uri, code)) {
                            return@use cursor.getLong(bytesColumn).coerceAtLeast(0L) to cursor.getLong(totalColumn)
                        }
                    }
                    null
                }
            }.getOrNull()
        }

    /** Whether [uri] is the download of [code]'s model: `…/en_bn.zip` style names. */
    internal fun isModelAddress(uri: String, code: String): Boolean {
        val file = uri.substringBefore('?').substringAfterLast('/').lowercase()
        if (!uri.contains("translate", ignoreCase = true)) return false
        return file.split('_', '-', '.').any { it == code }
    }
}
