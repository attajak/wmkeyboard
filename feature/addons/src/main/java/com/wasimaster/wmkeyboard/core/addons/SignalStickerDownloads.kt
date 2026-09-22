package com.wasimaster.wmkeyboard.core.addons

import com.wasimaster.wmkeyboard.core.endpoints.ServiceEndpoint
import com.wasimaster.wmkeyboard.core.endpoints.ServiceEndpoints
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import com.wasimaster.wmkeyboard.core.stickers.StickerImportResult
import com.wasimaster.wmkeyboard.core.stickers.StickerPack
import com.wasimaster.wmkeyboard.core.stickers.StickerPackAdoption
import com.wasimaster.wmkeyboard.core.stickers.StickerPackStore
import com.wasimaster.wmkeyboard.core.stickers.signal.SignalPackManifest
import com.wasimaster.wmkeyboard.core.stickers.signal.SignalSticker
import com.wasimaster.wmkeyboard.core.stickers.signal.SignalStickerCrypto
import com.wasimaster.wmkeyboard.core.tools.ToolHttp
import com.wasimaster.wmkeyboard.core.tools.ToolHttpException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Fetches Signal sticker packs.
 *
 * A pack lives on Signal's sticker CDN as `stickers/<pack id>/manifest.proto`
 * and `stickers/<pack id>/full/<sticker id>`, every one of them encrypted under
 * the pack key (see [SignalStickerCrypto]). Nothing is asked of Signal's
 * service itself and no account is involved: it is the same anonymous fetch a
 * Signal client makes when someone taps a pack link.
 *
 * Decrypted stickers are kept under `cacheDir/signal_stickers/<pack id>/` so
 * that the pack preview and the import that follows it download each picture
 * once. It is a cache like any other, trimmed by [trimCache] and free for the
 * system to clear.
 */
object SignalStickerDownloads {

    sealed interface ManifestOutcome {
        data class Ok(val manifest: SignalPackManifest) : ManifestOutcome

        /** The CDN has no such pack: a mistyped id, or a pack that was taken down. */
        data object NotFound : ManifestOutcome

        /** The pack exists and the key does not open it. */
        data object BadKey : ManifestOutcome

        data class Failed(val error: Throwable) : ManifestOutcome
    }

    /** What [import] is doing, for the progress line. */
    enum class Phase { DOWNLOADING, ADDING }

    private const val CACHE_DIR = "signal_stickers"
    private const val MAX_MANIFEST_BYTES = 1L * 1024 * 1024
    private const val MAX_STICKER_BYTES = 3L * 1024 * 1024
    private const val MAX_CACHE_BYTES = 32L * 1024 * 1024
    private const val PARALLEL_DOWNLOADS = 4
    private const val HTTP_FORBIDDEN = 403
    private const val HTTP_NOT_FOUND = 404

    fun manifestUrl(packId: String): String =
        "${ServiceEndpoints.base(ServiceEndpoint.SIGNAL_STICKER_CDN)}/stickers/$packId/manifest.proto"

    fun stickerUrl(packId: String, stickerId: Int): String =
        "${ServiceEndpoints.base(ServiceEndpoint.SIGNAL_STICKER_CDN)}/stickers/$packId/full/$stickerId"

    /** Downloads and opens the manifest of [packId]. Blocking. */
    fun manifest(packId: String, packKey: String, cacheDir: File): ManifestOutcome {
        val id = SignalStickerCrypto.packIdOrNull(packId) ?: return ManifestOutcome.NotFound
        val temp = File(packDir(cacheDir, id), "manifest.enc")
        return try {
            // The pack id is the path, so the route names it rather than showing it.
            ToolHttp.download(
                manifestUrl(id), temp, maxBytes = MAX_MANIFEST_BYTES,
                source = NetSource.SIGNAL_STICKERS, route = "/stickers/{pack}/manifest.proto",
            )
            val plain = SignalStickerCrypto.decrypt(temp.readBytes(), packKey)
                ?: return ManifestOutcome.BadKey
            SignalPackManifest.parse(plain)?.let { ManifestOutcome.Ok(it) } ?: ManifestOutcome.BadKey
        } catch (e: ToolHttpException) {
            // The CDN is object storage behind a cache, which answers a key it
            // does not hold with 403 as often as with 404.
            val missing = e.status == HTTP_FORBIDDEN || e.status == HTTP_NOT_FOUND
            if (missing) ManifestOutcome.NotFound else ManifestOutcome.Failed(e)
        } catch (e: IOException) {
            ManifestOutcome.Failed(e)
        } finally {
            temp.delete()
        }
    }

    /**
     * One sticker of [packId], decrypted, from the cache or the network.
     * Blocking. Throws [IOException] when the network is what failed, and
     * returns null when the sticker arrived and did not open.
     */
    @Throws(IOException::class)
    fun sticker(packId: String, packKey: String, stickerId: Int, cacheDir: File): File? {
        val id = SignalStickerCrypto.packIdOrNull(packId) ?: return null
        val dir = packDir(cacheDir, id)
        val cached = File(dir, stickerId.toString())
        if (cached.isFile && cached.length() > 0) {
            cached.setLastModified(System.currentTimeMillis())
            return cached
        }
        // Named per request as well as per sticker: the preview grid and an
        // import may want the same picture at the same moment.
        val tag = "$stickerId.${System.nanoTime()}"
        val encrypted = File(dir, "$tag.enc")
        val part = File(dir, "$tag.part")
        return try {
            ToolHttp.download(
                stickerUrl(id, stickerId), encrypted, maxBytes = MAX_STICKER_BYTES,
                source = NetSource.SIGNAL_STICKERS, route = "/stickers/{pack}/full/{sticker}",
            )
            val plain = SignalStickerCrypto.decrypt(encrypted.readBytes(), packKey) ?: return null
            part.writeBytes(plain)
            if (part.renameTo(cached) || cached.isFile) cached else null
        } finally {
            encrypted.delete()
            part.delete()
        }
    }

    /**
     * Downloads every sticker [manifest] lists and registers them with [store]
     * as one pack.
     *
     * Throws [IOException] when not one sticker could be fetched and the
     * network is why, so that the screen can say so instead of reporting a
     * pack with nothing in it.
     */
    @Throws(IOException::class)
    suspend fun import(
        store: StickerPackStore,
        packId: String,
        packKey: String,
        manifest: SignalPackManifest,
        defaultName: String,
        cacheDir: File,
        onProgress: (phase: Phase, done: Int, total: Int) -> Unit,
    ): StickerImportResult = withContext(Dispatchers.IO) {
        val wanted = manifest.stickers
        val fetching = minOf(wanted.size, StickerPackStore.MAX_STICKERS_PER_PACK)
        val files = download(packId, packKey, wanted, cacheDir) { done ->
            onProgress(Phase.DOWNLOADING, done, fetching)
        }
        ensureActive()
        val incoming = wanted.map { sticker ->
            StickerPackAdoption.Incoming(
                label = sticker.label(),
                emojis = listOfNotNull(sticker.emoji.takeIf { it.isNotBlank() }),
                read = { files[sticker.id]?.takeIf { it.isFile }?.readBytes() },
            )
        }
        StickerPackAdoption.adopt(
            store = store,
            name = manifest.title.ifBlank { defaultName },
            incoming = incoming,
            source = StickerPack.signalSource(packId),
            onProgress = { done, total -> onProgress(Phase.ADDING, done, total) },
        )
    }

    private suspend fun download(
        packId: String,
        packKey: String,
        wanted: List<SignalSticker>,
        cacheDir: File,
        onDone: (Int) -> Unit,
    ): Map<Int, File> = coroutineScope {
        val gate = Semaphore(PARALLEL_DOWNLOADS)
        val done = AtomicInteger(0)
        val networkFailure = AtomicReference<IOException?>(null)
        onDone(0)
        val fetched = wanted.take(StickerPackStore.MAX_STICKERS_PER_PACK).map { sticker ->
            async {
                gate.withPermit {
                    ensureActive()
                    val file = try {
                        sticker(packId, packKey, sticker.id, cacheDir)
                    } catch (e: IOException) {
                        networkFailure.set(e)
                        null
                    }
                    onDone(done.incrementAndGet())
                    file?.let { sticker.id to it }
                }
            }
        }.awaitAll().filterNotNull().toMap()
        if (fetched.isEmpty()) networkFailure.get()?.let { throw it }
        fetched
    }

    /** How a repair note names a sticker that has no name: its emoji and its number. */
    private fun SignalSticker.label(): String = "$emoji #$id".trim()

    private fun packDir(cacheDir: File, packId: String): File =
        File(File(cacheDir, CACHE_DIR), packId).apply { mkdirs() }

    /** Deletes the least recently used cached stickers once the cache passes its cap. */
    fun trimCache(cacheDir: File) {
        val files = File(cacheDir, CACHE_DIR).walkTopDown().filter { it.isFile }.toList()
        var total = files.sumOf { it.length() }
        if (total <= MAX_CACHE_BYTES) return
        for (file in files.sortedBy { it.lastModified() }) {
            if (total <= MAX_CACHE_BYTES) break
            total -= file.length()
            file.delete()
        }
    }
}
