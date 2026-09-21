package com.wasimaster.wmkeyboard.core.stickers

import com.wasimaster.wmkeyboard.content.R
import com.wasimaster.wmkeyboard.core.content.ContentText
import java.io.File

/**
 * The last step every sticker import shares: a list of pictures from somewhere
 * becomes a pack the store owns.
 *
 * Where the pictures come from is the caller's business ([StickerPackFile] reads
 * them out of an archive, the Signal importer downloads and decrypts them).
 * What happens to them is the same either way, and lives here once: each one is
 * normalised, written under a name derived from a freshly generated sticker id,
 * and the pack is registered only when at least one of them survived.
 */
object StickerPackAdoption {

    /**
     * One sticker on its way in.
     *
     * [label] is how a repair note names it. [problem], when set, is why the
     * source could not offer an image for it at all; the sticker is dropped
     * with that note, in its place in the list, without [read] being called.
     * [read] returning null means the image it named is missing.
     */
    class Incoming(
        val label: String,
        val name: String = "",
        val emojis: List<String> = emptyList(),
        val addedAt: Long = 0L,
        val problem: ContentText? = null,
        val read: () -> ByteArray?,
    )

    /**
     * Registers [incoming] with [store] as a new pack called [name], under a
     * fresh id, so importing the same thing twice can never collide with itself.
     *
     * [normalize] turns raw bytes into storable sticker bytes; the default runs
     * them through [StickerImage], which keeps conforming files untouched and
     * re-encodes everything else. [onProgress] is told how many of the stickers
     * have been dealt with, before each one and once more at the end.
     */
    fun adopt(
        store: StickerPackStore,
        name: String,
        incoming: List<Incoming>,
        now: Long = System.currentTimeMillis(),
        source: String = "",
        normalize: (ByteArray) -> ProcessedSticker? = { bytes ->
            (StickerImage.process(bytes) as? StickerImage.Result.Ok)?.sticker
        },
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): StickerImportResult {
        val repairs = ArrayList<ContentText>()
        if (incoming.size > StickerPackStore.MAX_STICKERS_PER_PACK) {
            repairs += ContentText(
                pluralsRes = R.plurals.core_content_sticker_repair_kept_first,
                quantity = incoming.size,
                args = listOf(incoming.size, StickerPackStore.MAX_STICKERS_PER_PACK),
            )
        }
        val packId = store.freePackId(now)
        val packDir = store.packDir(packId) ?: return StickerImportResult.Failed
        // Cleared once the pack is registered; until then any exit path,
        // including a normalizer that throws, takes the half-filled directory
        // with it.
        var unadopted: File? = packDir
        try {
            val taken = incoming.take(StickerPackStore.MAX_STICKERS_PER_PACK)
            val kept = ArrayList<CustomSticker>()
            taken.forEachIndexed { index, item ->
                onProgress(index, taken.size)
                keep(item, packDir, now, normalize, repairs)?.let { kept += it }
            }
            onProgress(taken.size, taken.size)
            // An empty pack is not a successful import. It installs, it appears
            // in the list, and it holds nothing, which looks like the app threw
            // the images away rather than like a source that didn't match its
            // own list. The repairs say which it was.
            if (kept.isEmpty()) {
                return StickerImportResult.NoStickers(
                    repairs.ifEmpty {
                        listOf(ContentText(R.string.core_content_sticker_repair_none_listed))
                    },
                )
            }
            val pack = StickerPack(id = packId, name = name, stickers = kept, createdAt = now, source = source)
            val adopted = store.adoptPack(pack) ?: return StickerImportResult.TooManyPacks
            unadopted = null
            return StickerImportResult.Imported(adopted, repairs)
        } finally {
            unadopted?.deleteRecursively()
        }
    }

    /** Writes one sticker into [packDir], or notes in [repairs] why it was dropped. */
    private fun keep(
        item: Incoming,
        packDir: File,
        now: Long,
        normalize: (ByteArray) -> ProcessedSticker?,
        repairs: MutableList<ContentText>,
    ): CustomSticker? {
        item.problem?.let {
            repairs += it
            return null
        }
        val bytes = item.read()
        if (bytes == null) {
            repairs += ContentText(R.string.core_content_sticker_repair_image_missing, args = listOf(item.label))
            return null
        }
        val processed = normalize(bytes)
        if (processed == null) {
            repairs += ContentText(R.string.core_content_sticker_repair_image_unreadable, args = listOf(item.label))
            return null
        }
        val id = StickerPackStore.newStickerId()
        val fileName = StickerPackStore.fileNameFor(id, processed.mime)
        val ok = runCatching { File(packDir, fileName).writeBytes(processed.bytes); true }.getOrDefault(false)
        if (!ok) {
            repairs += ContentText(R.string.core_content_sticker_repair_not_saved, args = listOf(item.label))
            return null
        }
        return CustomSticker(
            id = id,
            fileName = fileName,
            mime = processed.mime,
            name = item.name.trim(),
            emojis = item.emojis,
            animated = processed.animated,
            aspectRatio = processed.aspectRatio,
            addedAt = if (item.addedAt > 0) item.addedAt else now,
        )
    }
}
