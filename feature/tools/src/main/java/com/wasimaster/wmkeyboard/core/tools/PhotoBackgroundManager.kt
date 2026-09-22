package com.wasimaster.wmkeyboard.core.tools

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.os.StatFs
import com.wasimaster.wmkeyboard.core.debug.DebugLog
import com.wasimaster.wmkeyboard.core.directboot.DirectBoot
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import com.wasimaster.wmkeyboard.core.settings.MAX_FETCH_PER_RUN
import com.wasimaster.wmkeyboard.core.settings.PhotoBackgroundSettings
import com.wasimaster.wmkeyboard.core.settings.PhotoNetworkConditions
import com.wasimaster.wmkeyboard.core.settings.PoolEntry
import com.wasimaster.wmkeyboard.core.settings.PoolIndex
import com.wasimaster.wmkeyboard.core.settings.PoolIndexCodec
import com.wasimaster.wmkeyboard.core.settings.RotationSourceKind
import com.wasimaster.wmkeyboard.core.settings.RotationState
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.settings.mayFetch
import com.wasimaster.wmkeyboard.core.settings.needsTopUp
import com.wasimaster.wmkeyboard.core.settings.pickNextPhoto
import com.wasimaster.wmkeyboard.core.settings.poolEvictionPlan
import com.wasimaster.wmkeyboard.core.settings.SweptFile
import com.wasimaster.wmkeyboard.core.settings.themePhotoSweepPlan
import com.wasimaster.wmkeyboard.core.theme.ThemeSpec
import com.wasimaster.wmkeyboard.core.theme.PhotoAttribution
import com.wasimaster.wmkeyboard.core.theme.PhotoSampling
import com.wasimaster.wmkeyboard.core.theme.Readability
import com.wasimaster.wmkeyboard.core.theme.regionLuminance
import com.wasimaster.wmkeyboard.core.theme.scrimAlphaFor
import com.wasimaster.wmkeyboard.core.theme.seedsFrom
import java.io.File
import java.io.IOException
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** What a photo the user asked for is doing right now. */
sealed interface PhotoApplyStatus {
    data object Downloading : PhotoApplyStatus
    data object Applying : PhotoApplyStatus
    data object Done : PhotoApplyStatus
    data class Failed(val failure: PhotoFailure) : PhotoApplyStatus
}

/**
 * Downloads background photos, applies them to a theme, and keeps the pool the
 * rotating background draws from.
 *
 * A process singleton with its own scope and a status flow, following
 * `AddonDownloadManager` and the model downloaders: there is deliberately no
 * WorkManager and no foreground service anywhere in this app, so work that has
 * to outlive a screen lives in an object like this one.
 */
object PhotoBackgroundManager {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _status = MutableStateFlow<Map<String, PhotoApplyStatus>>(emptyMap())

    /** Per-photo progress, keyed by [PhotoItem.key]. Collected by the picker. */
    val status: StateFlow<Map<String, PhotoApplyStatus>> = _status.asStateFlow()

    private val _rotationStates = MutableStateFlow<Map<String, RotationState>>(emptyMap())

    /**
     * Which photo each rotating theme is showing.
     *
     * Mirrored here from the repository by the keyboard service so the theme
     * resolver can read it without the composable needing a repository handle.
     * The repository stays the source of truth; this is only the way in.
     */
    val rotationStates: StateFlow<Map<String, RotationState>> = _rotationStates.asStateFlow()

    fun publishRotationStates(states: Map<String, RotationState>) {
        _rotationStates.value = states
    }

    /** One writer at a time for the pool index and its directory. */
    private val poolLock = Mutex()

    /** Photos already reported to Unsplash, so one selection counts once. */
    private val trackedDownloads = HashSet<String>()

    // ---- directories ---------------------------------------------------

    /** Where a photo applied to a theme lands, beside the images the picker writes. */
    fun themeImagesDir(context: Context): File =
        File(context.applicationContext.filesDir, "theme_images").apply { mkdirs() }

    /** The rotation pool, its saved library, and copies of device photos. */
    fun poolDir(context: Context): File =
        File(context.applicationContext.filesDir, POOL_DIR_NAME).apply { mkdirs() }

    private fun poolIndexFile(context: Context) = File(poolDir(context), "pool.json")

    // ---- applying one photo to a theme ---------------------------------

    /**
     * Downloads [photo] at the size the board draws and hands back the file.
     *
     * Blocking; call on an IO dispatcher. The caller writes it into the theme,
     * because what "apply" means differs between the portrait slot, the
     * landscape slot and the saved library.
     */
    private fun download(
        context: Context,
        photo: PhotoItem,
        target: PhotoTarget,
        into: File,
        background: Boolean = false,
    ): File {
        requireSpace(context, target.maxBytes)
        val url = PhotoSizing.downloadUrl(photo, target)
        // Image CDN paths are opaque ids, but they are the photo's address all
        // the same; the host alone says enough.
        ToolHttp.download(url, into, maxBytes = target.maxBytes, source = NetSource.PHOTOS, background = background)
        return into
    }

    /**
     * Reports a download to the provider that asks to be told.
     *
     * Unsplash's terms require one call per photo a user really takes, which is
     * how a photographer's download count stays honest. Fired off the critical
     * path and its failure ignored on purpose: a tracking call must never be
     * the reason a background did not get set.
     */
    private fun trackDownload(photo: PhotoItem, apiKey: String, nowMs: Long) {
        if (photo.source != PhotoSource.UNSPLASH || photo.downloadLocation.isBlank()) return
        if (apiKey.isBlank()) return
        // A double tap is one download; two separate uses of the same photo,
        // minutes apart, are two. Under-reporting cheats the photographer
        // exactly as much as over-reporting inflates them.
        val stamp = "${photo.key}@${nowMs / DOWNLOAD_DEDUPE_WINDOW_MS}"
        synchronized(trackedDownloads) {
            if (!trackedDownloads.add(stamp)) return
            if (trackedDownloads.size > MAX_TRACKED_DOWNLOADS) trackedDownloads.clear()
        }
        scope.launch {
            runCatching { UnsplashClient.triggerDownload(photo.downloadLocation, apiKey) }
        }
    }

    /**
     * Applies [photo] to a theme.
     *
     * [landscape] writes the theme's separate landscape slot. Returns the
     * measurements taken from the photo, which the editor uses to offer a
     * palette and a scrim without decoding it a second time.
     */
    suspend fun applyToTheme(
        context: Context,
        repository: SettingsRepository,
        photo: PhotoItem,
        themeId: String,
        landscape: Boolean,
        apiKey: String,
        nowMs: Long = System.currentTimeMillis(),
    ): PhotoMeasurements? {
        val key = photo.key
        _status.update { it + (key to PhotoApplyStatus.Downloading) }
        return try {
            val target = if (landscape) PhotoSizing.LANDSCAPE_STRIP else PhotoSizing.PORTRAIT_STRIP
            val suffix = if (landscape) "_land" else ""
            val file = File(themeImagesDir(context), "$themeId${suffix}_$nowMs.img")
            withContext(Dispatchers.IO) { download(context, photo, target, file) }
            _status.update { it + (key to PhotoApplyStatus.Applying) }
            val measured = withContext(Dispatchers.IO) { measure(file) }
            val replaced = repository.applyThemePhoto(
                themeId = themeId,
                path = file.absolutePath,
                credit = photo.toAttribution(),
                landscape = landscape,
            )
            // The photo this one replaced is now referenced by nothing. Deleted
            // after the write rather than before it, so a failure part-way
            // leaves the old background in place instead of none at all.
            replaced?.let { old -> withContext(Dispatchers.IO) { runCatching { File(old).delete() } } }
            addToCollection(context, file, photo.toAttribution(), nowMs)
            trackDownload(photo, apiKey, nowMs)
            _status.update { it + (key to PhotoApplyStatus.Done) }
            measured
        } catch (e: IOException) {
            _status.update { it + (key to PhotoApplyStatus.Failed(photoFailureOf(photo.source, e, nowMs))) }
            null
        }
    }

    /** Keeps [photo] in the user's library, for reuse across themes. */
    suspend fun saveToLibrary(
        context: Context,
        photo: PhotoItem,
        apiKey: String,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean {
        val key = photo.key
        _status.update { it + (key to PhotoApplyStatus.Downloading) }
        return try {
            val file = File(poolDir(context), fileNameFor(photo))
            withContext(Dispatchers.IO) {
                if (!file.isFile) download(context, photo, PhotoSizing.LANDSCAPE_STRIP, file)
            }
            val measured = withContext(Dispatchers.IO) { measure(file) }
            addToPool(context, photo, file, RotationSourceKind.SAVED, measured, nowMs)
            trackDownload(photo, apiKey, nowMs)
            _status.update { it + (key to PhotoApplyStatus.Done) }
            true
        } catch (e: IOException) {
            _status.update { it + (key to PhotoApplyStatus.Failed(photoFailureOf(photo.source, e, nowMs))) }
            false
        }
    }

    /**
     * Copies a photo the user picked on the device into the pool.
     *
     * Copied rather than referenced: a picker grant is good for one shot, so a
     * `content://` URI kept in the pool would be dead by the next rotation.
     */
    suspend fun addDevicePhoto(
        context: Context,
        uri: Uri,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean = withContext(Dispatchers.IO) {
        val file = File(poolDir(context), "d_${nowMs}_${uri.hashCode().toUInt()}.img")
        val measured = try {
            requireSpace(context, DEVICE_PHOTO_MAX_BYTES)
            context.contentResolver.openInputStream(uri)?.use { input ->
                file.outputStream().use { input.copyTo(it) }
            } ?: return@withContext false
            measure(file)
        } catch (failed: IOException) {
            // A file the provider cannot open, or a full disk. The half-written
            // copy is no use to anybody.
            DebugLog.e(LOG_TAG, "cannot copy the picked photo", failed)
            file.delete()
            return@withContext false
        } catch (failed: SecurityException) {
            // The picker grant expired before the copy finished.
            DebugLog.e(LOG_TAG, "no permission to read the picked photo", failed)
            file.delete()
            return@withContext false
        }
        // Outside the try: a cancellation here has to propagate rather than
        // be reported as "the photo could not be added".
        addLocalToPool(context, file, RotationSourceKind.SAVED, measured, nowMs)
        true
    }

    /**
     * Copies a photo that has become a background into the collection.
     *
     * Anything the user deliberately made a background is worth keeping: it can
     * then be put on another theme, or taken into the rotation, without being
     * found again. Copied rather than referenced, because the file it came from
     * belongs to one theme and is deleted when that theme replaces it.
     */
    suspend fun addToCollection(
        context: Context,
        source: File,
        credit: PhotoAttribution? = null,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean = withContext(Dispatchers.IO) {
        if (!source.isFile) return@withContext false
        // Keyed off the photo where there is one, so the same service photo
        // used on two themes is stored once.
        val name = credit?.let { "p_${it.provider}_${it.photoId.filter(Char::isLetterOrDigit)}.img" }
            ?: "c_${nowMs}_${source.name.hashCode().toUInt()}.img"
        val target = File(poolDir(context), name)
        val measured = try {
            if (!target.isFile) source.copyTo(target, overwrite = true)
            measure(target)
        } catch (failed: IOException) {
            DebugLog.e(LOG_TAG, "cannot copy a background into the collection", failed)
            target.delete()
            return@withContext false
        }
        addLocalToPool(context, target, RotationSourceKind.SAVED, measured, nowMs, credit)
        true
    }

    // ---- the rotation pool ---------------------------------------------

    suspend fun readPool(context: Context): PoolIndex = poolLock.withLock {
        readPoolLocked(context)
    }

    private fun readPoolLocked(context: Context): PoolIndex {
        val file = poolIndexFile(context)
        if (!file.isFile) return PoolIndex()
        return runCatching { PoolIndexCodec.decode(file.readText()) }.getOrDefault(PoolIndex())
    }

    private fun writePoolLocked(context: Context, index: PoolIndex) {
        runCatching { poolIndexFile(context).writeText(PoolIndexCodec.encode(index)) }
    }

    /**
     * Fetches photos until the pool is full enough, within the request budget.
     *
     * Silent on failure by design: nothing is on screen waiting for this, and a
     * top-up that could not happen should simply be tried again later.
     */
    suspend fun topUpPool(
        context: Context,
        settings: PhotoBackgroundSettings,
        keys: PhotoSearchClient.Keys,
        sources: List<PhotoSource>,
        conditions: PhotoNetworkConditions,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        if (!settings.mayFetch(conditions)) return
        val index = readPool(context)
        val ready = index.entries.count { it.lastShownAt == 0L }
        if (!settings.needsTopUp(ready, index.lastTopUpAt, nowMs)) return

        val wanted = (settings.poolTarget - index.entries.size).coerceIn(1, MAX_FETCH_PER_RUN)
        val known = index.entries.mapNotNull { it.credit?.let { c -> "${c.provider}:${c.photoId}" } }
            .toSet() + index.recentlyEvicted
        val fetched = PhotoSearchClient.prefetch(
            sources = sources,
            query = poolQuery(settings, nowMs),
            keys = keys,
            count = wanted * FETCH_OVERSAMPLE,
            nowMs = nowMs,
        ).filterNot { it.key in known }.take(wanted)

        for (photo in fetched) {
            val file = File(poolDir(context), fileNameFor(photo))
            val ok = runCatching {
                if (!file.isFile) download(context, photo, PhotoSizing.LANDSCAPE_STRIP, file, background = true)
            }.isSuccess
            if (!ok) continue
            addToPool(context, photo, file, RotationSourceKind.ONLINE, measure(file), nowMs)
        }
        poolLock.withLock {
            writePoolLocked(context, readPoolLocked(context).copy(lastTopUpAt = nowMs))
        }
    }

    /**
     * Moves [themeId] on to the next photo, if the pool has one.
     *
     * Purely local: the file is already on disk, so this is a couple of reads
     * and one preference write, which is why it is safe to do as the keyboard
     * is coming up.
     */
    suspend fun rotate(
        context: Context,
        repository: SettingsRepository,
        themeId: String,
        current: RotationState?,
        wantWide: Boolean,
        sources: Set<RotationSourceKind> = RotationSourceKind.entries.toSet(),
        random: Random = Random.Default,
        nowMs: Long = System.currentTimeMillis(),
        elapsedMs: Long = SystemClock.elapsedRealtime(),
    ): Boolean {
        if (!DirectBoot.isUserUnlocked(context)) return false
        val index = readPool(context)
        val currentName = current?.imagePath?.let { File(it).name }
        // Turning a source off has to stop its photos being shown, not merely
        // stop new ones arriving. Photos already downloaded stay on disk, so
        // turning the source back on brings them back rather than re-fetching.
        val eligible = index.entries.filter { it.source in sources }
        val next = pickNextPhoto(eligible, currentName, wantWide, random) ?: return false
        val file = File(poolDir(context), next.fileName)
        if (!file.isFile) return false

        repository.setRotationState(
            themeId = themeId,
            state = RotationState(
                imagePath = file.absolutePath,
                credit = next.credit,
                seedColor = next.seedColor,
                scrimAlpha = next.scrimAlpha,
                rotatedAtEpochMs = nowMs,
                rotatedAtElapsedMs = elapsedMs,
            ),
        )
        poolLock.withLock {
            val stored = readPoolLocked(context)
            writePoolLocked(
                context,
                stored.copy(
                    entries = stored.entries.map { entry ->
                        if (entry.fileName == next.fileName) {
                            entry.copy(lastShownAt = nowMs, showCount = entry.showCount + 1)
                        } else {
                            entry
                        }
                    },
                ),
            )
        }
        return true
    }

    /** Drops photos past the budget, and records the ones that went. */
    suspend fun prunePool(context: Context, settings: PhotoBackgroundSettings) = poolLock.withLock {
        val index = readPoolLocked(context)
        val dir = poolDir(context)
        val onDisk = dir.listFiles()?.map { it.name }?.toSet().orEmpty()
        val plan = poolEvictionPlan(
            entries = index.entries,
            existingFiles = onDisk,
            maxEntries = settings.poolTarget * POOL_HEADROOM,
            maxBytes = settings.poolBudgetMb * 1024L * 1024,
        )
        plan.deleteFiles.forEach { runCatching { File(dir, it).delete() } }
        writePoolLocked(
            context,
            index.copy(
                entries = plan.keep,
                recentlyEvicted = (plan.evictedKeys + index.recentlyEvicted)
                    .distinct()
                    .take(PoolIndexCodec.EVICTION_MEMORY),
            ),
        )
    }

    /**
     * Deletes background images nothing refers to any more, and forgets
     * rotation entries for themes that are gone.
     *
     * Best-effort and quiet: this is housekeeping, and a failure means the
     * files are swept next time rather than that anything is wrong.
     */
    suspend fun sweep(
        context: Context,
        repository: SettingsRepository,
        themes: List<ThemeSpec>,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        if (!DirectBoot.isUserUnlocked(context)) return
        val states = rotationStates.value
        val plan = withContext(Dispatchers.IO) {
            val images = themeImagesDir(context).listFiles().orEmpty()
                .map { SweptFile(it.name, it.lastModified()) }
            themePhotoSweepPlan(
                themes = themes,
                rotationStates = states,
                imagesOnDisk = images,
                poolFileNames = readPoolLocked(context).entries.mapTo(HashSet()) { it.fileName },
                nowMs = nowMs,
            )
        }
        withContext(Dispatchers.IO) {
            plan.deleteImages.forEach { name ->
                runCatching { File(themeImagesDir(context), name).delete() }
            }
        }
        if (plan.dropRotationStates.isNotEmpty()) {
            repository.pruneRotationStates(themes.mapTo(HashSet()) { it.id })
        }
    }

    /** Removes one photo from the pool and deletes its file. */
    suspend fun removeFromPool(context: Context, fileName: String) = poolLock.withLock {
        val index = readPoolLocked(context)
        runCatching { File(poolDir(context), fileName).delete() }
        writePoolLocked(context, index.copy(entries = index.entries.filterNot { it.fileName == fileName }))
    }

    /** Pool entry for a file with no service behind it, so no credit either. */
    private suspend fun addLocalToPool(
        context: Context,
        file: File,
        source: RotationSourceKind,
        measured: PhotoMeasurements?,
        nowMs: Long,
        credit: PhotoAttribution? = null,
    ) = poolLock.withLock {
        val index = readPoolLocked(context)
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(file.absolutePath, bounds)
        val entry = PoolEntry(
            fileName = file.name,
            source = source,
            credit = credit,
            widthPx = bounds.outWidth.coerceAtLeast(0),
            heightPx = bounds.outHeight.coerceAtLeast(0),
            bytes = file.length(),
            seedColor = measured?.seedColor ?: 0L,
            scrimAlpha = measured?.scrimAlpha ?: -1f,
            addedAt = nowMs,
        )
        writePoolLocked(
            context,
            index.copy(entries = index.entries.filterNot { it.fileName == entry.fileName } + entry),
        )
    }

    private suspend fun addToPool(
        context: Context,
        photo: PhotoItem,
        file: File,
        source: RotationSourceKind,
        measured: PhotoMeasurements?,
        nowMs: Long,
    ) = poolLock.withLock {
        val index = readPoolLocked(context)
        val entry = PoolEntry(
            fileName = file.name,
            source = source,
            credit = photo.toAttribution(),
            widthPx = photo.width,
            heightPx = photo.height,
            bytes = file.length(),
            seedColor = measured?.seedColor ?: 0L,
            scrimAlpha = measured?.scrimAlpha ?: -1f,
            addedAt = nowMs,
            sourceTag = photo.altText,
        )
        writePoolLocked(
            context,
            index.copy(entries = index.entries.filterNot { it.fileName == entry.fileName } + entry),
        )
    }

    // ---- measuring ------------------------------------------------------

    /**
     * The palette seed and the scrim, taken once when a photo arrives rather
     * than every time it is shown.
     */
    fun measure(
        file: File,
        boardArgb: Long = DEFAULT_BOARD_ARGB,
        keyBackgroundArgb: Long = DEFAULT_KEY_ARGB,
        keyTextArgb: Long = DEFAULT_KEY_TEXT_ARGB,
    ): PhotoMeasurements? {
        val grid = PhotoSampling.gridOf(file) ?: return null
        val luminance = regionLuminance(grid)
        return PhotoMeasurements(
            seedColor = seedsFrom(grid, max = 1).firstOrNull() ?: 0L,
            luminance = luminance,
            scrimAlpha = scrimAlphaFor(
                photoLuminance = luminance,
                imageOpacity = 1f,
                boardArgb = boardArgb,
                keyBackgroundArgb = keyBackgroundArgb,
                keyTextArgb = keyTextArgb,
                target = Readability.TARGET_CONTRAST,
            ),
        )
    }

    private fun requireSpace(context: Context, bytes: Long) {
        val stat = StatFs(context.applicationContext.filesDir.absolutePath)
        if (stat.availableBytes < bytes + SPACE_MARGIN_BYTES) {
            throw IOException("not enough free space for a background photo")
        }
    }

    /** Stable per photo, so the same one is never downloaded twice. */
    private fun fileNameFor(photo: PhotoItem): String =
        "p_${photo.source.name.lowercase()}_${photo.id.filter { it.isLetterOrDigit() || it == '-' }}.img"

    /** One of the user's topics or search terms, taking a turn each time. */
    private fun poolQuery(settings: PhotoBackgroundSettings, nowMs: Long): PhotoQuery {
        val terms = settings.topics + settings.queries
        val pick = if (terms.isEmpty()) "" else terms[((nowMs / 1000) % terms.size).toInt()]
        val isTopic = pick in settings.topics
        return PhotoQuery(
            text = if (isTopic) "" else pick,
            topicId = if (isTopic) pick else "",
            perPage = MAX_FETCH_PER_RUN * FETCH_OVERSAMPLE,
            orientation = if (settings.landscapeOnly) PhotoOrientation.LANDSCAPE else PhotoOrientation.ANY,
            safe = settings.safeSearch,
            // Successive top-ups walk down the feed rather than fetching the
            // same first page and discarding it as already known.
            page = (((nowMs / PhotoBackgroundSettings.MIN_FETCH_PERIOD_MS) % MAX_FEED_PAGE) + 1).toInt(),
        )
    }

    /** Fetch more than needed, since some come back already known. */
    private const val FETCH_OVERSAMPLE = 3

    /** Two taps inside this window are one download; further apart, two. */
    private const val DOWNLOAD_DEDUPE_WINDOW_MS = 60_000L

    private const val MAX_TRACKED_DOWNLOADS = 256

    /** How far into a feed a top-up will page. */
    private const val MAX_FEED_PAGE = 20

    /** Room above the target before eviction starts, so the pool is not churned. */
    private const val POOL_HEADROOM = 3

    private const val SPACE_MARGIN_BYTES = 32L * 1024 * 1024
    private const val POOL_DIR_NAME = "theme_photos"
    private const val LOG_TAG = "PhotoBackground"

    /** A device photo is the user's own file, so the cap is generous. */
    private const val DEVICE_PHOTO_MAX_BYTES = 16L * 1024 * 1024

    // The default dark theme, which is what an unmeasured photo is judged
    // against. Close enough: the editor re-measures against the real theme.
    private const val DEFAULT_BOARD_ARGB = 0xFF17181CL
    private const val DEFAULT_KEY_ARGB = 0x00000000L
    private const val DEFAULT_KEY_TEXT_ARGB = 0xFFE9E9EEL
}

/** What one photo measured, taken at download time. */
data class PhotoMeasurements(
    val seedColor: Long,
    val luminance: Float,
    val scrimAlpha: Float,
)

/** The credit to store on a theme, from a search result. */
fun PhotoItem.toAttribution(): PhotoAttribution = PhotoAttribution(
    provider = PhotoLinks.providerKey(source),
    photoId = id,
    photographer = photographer,
    photographerUrl = photographerUrl,
    photoUrl = pageUrl,
    altText = altText,
    avgColor = avgColor,
)
