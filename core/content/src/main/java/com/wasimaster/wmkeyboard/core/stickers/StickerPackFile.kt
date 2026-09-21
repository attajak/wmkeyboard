package com.wasimaster.wmkeyboard.core.stickers

import com.wasimaster.wmkeyboard.content.R
import com.wasimaster.wmkeyboard.core.content.ContentText
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** The outcome of reading a `.wmstickers` file. */
sealed interface StickerImportResult {
    /**
     * [repairs] says what the reader had to drop on the way in. Each line
     * arrives unresolved, so the dialog calls [ContentText.resolve] on it.
     */
    data class Imported(val pack: StickerPack, val repairs: List<ContentText>) : StickerImportResult

    /** No manifest, or a manifest for something else entirely. */
    data object NotAStickerPack : StickerImportResult

    /**
     * A valid manifest that yielded no usable stickers.
     *
     * Its own result rather than an `Imported` pack with an empty list: an empty
     * pack installs successfully, appears in the list, and contains nothing,
     * which reads as the app losing the images. [repairs] says what went wrong
     * with each one.
     */
    data class NoStickers(val repairs: List<ContentText>) : StickerImportResult

    /** [StickerPackStore.MAX_PACKS] reached. */
    data object TooManyPacks : StickerImportResult

    /** Unreadable archive, or nowhere to write. */
    data object Failed : StickerImportResult
}

@Serializable
private data class StickerEnvelope(
    val format: String,
    val version: Int,
    val appVersion: Int = 0,
    val appVersionName: String = "",
    val pack: StickerPack,
)

/**
 * The envelope as *read*, which is a looser thing than the envelope as written.
 *
 * A pack exported by the app round-trips through [StickerEnvelope] exactly. A
 * pack written by hand — which the addon repository format explicitly invites —
 * gets the shape wrong in three predictable ways, none of which is a reason to
 * throw the images away:
 *
 * - `stickers[]` beside `pack` instead of inside it, because that reads more
 *   naturally than nesting a list under a metadata object;
 * - `file` instead of `fileName`, matching the key the ZIP calls it by;
 * - a path (`stickers/happy.png`) instead of a bare name, because that is what
 *   the entry is actually called in the archive.
 *
 * All three are accepted. Nothing here is used as a path — see [ReadSticker.source].
 */
@Serializable
private data class ReadEnvelope(
    val format: String = "",
    val version: Int = 0,
    val pack: ReadPack = ReadPack(),
    val stickers: List<ReadSticker> = emptyList(),
) {
    /** The declared stickers, from wherever the author put them. */
    val declared: List<ReadSticker> get() = pack.stickers.ifEmpty { stickers }
}

@Serializable
private data class ReadPack(
    val id: String = "",
    val name: String = "",
    val source: String = "",
    val stickers: List<ReadSticker> = emptyList(),
)

@Serializable
private data class ReadSticker(
    val id: String = "",
    val fileName: String = "",
    /** What a hand-written manifest usually calls [fileName]. */
    val file: String = "",
    val mime: String = "",
    val name: String = "",
    val emojis: List<String> = emptyList(),
    val addedAt: Long = 0L,
) {
    /**
     * How this sticker names its image. A lookup key into the archive's entry
     * names and nothing else — the file it is written to is always derived from
     * a freshly minted sticker id — so a `/` in here is harmless.
     */
    val source: String get() = fileName.ifBlank { file }.trim()

    fun label(): String = name.ifBlank { source }.ifBlank { id }
}

/**
 * A sticker pack as a shareable file: a ZIP holding a `pack.json` manifest and
 * the sticker images beside it.
 *
 * ```
 * mypack.wmstickers
 * ├── pack.json
 * └── stickers/<fileName>
 * ```
 *
 * A ZIP rather than the base64-in-JSON the theme export uses, because a pack is
 * bulk binary: 200 stickers would be a ~27 MB JSON string that has to be held in
 * memory whole to parse, where the archive streams entry by entry.
 *
 * The manifest is the same versioned envelope as
 * [com.wasimaster.wmkeyboard.core.layout.LayoutFile] — the format tag is the one
 * strict check, and everything past it is repaired and reported rather than
 * refused, since a hand-built pack shouldn't fail on one bad entry.
 *
 * **Entry names are never used as paths.** Every file is written to a name
 * derived from a freshly generated sticker id, so neither `../` in an entry name
 * nor `../` in a manifest `fileName` can escape the pack directory.
 */
object StickerPackFile {

    const val FORMAT = "wmkeyboard-stickers"
    const val VERSION = 1
    const val FILE_EXTENSION = "wmstickers"
    const val MIME_TYPE = "application/zip"

    /**
     * What the import picker accepts. Permissive on purpose: providers report
     * a custom extension as `application/octet-stream` as often as not, and the
     * real check is the manifest inside.
     */
    val IMPORT_MIME_TYPES = arrayOf(
        "application/zip",
        "application/x-zip-compressed",
        "application/octet-stream",
    )

    /**
     * Public because it is not private to this format: the icon pack uses the
     * same manifest name, and [com.wasimaster.wmkeyboard.app.WMFileTypes] has
     * to read it to tell the two archives apart.
     */
    const val MANIFEST = "pack.json"
    private const val STICKER_DIR = "stickers/"

    /** Zip-bomb guards: nothing legitimate comes close to either. */
    private const val MAX_ENTRIES = 500
    private const val MAX_TOTAL_BYTES = 64L * 1024 * 1024

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

    fun fileName(pack: StickerPack): String {
        val stem = pack.name.ifBlank { "stickers" }
            .replace(Regex("[^\\p{L}\\p{N} _-]"), "")
            .trim()
            .ifBlank { "stickers" }
        return "$stem.$FILE_EXTENSION"
    }

    /**
     * Writes [pack] and its images to [out]. Stickers whose file has gone
     * missing are skipped — exporting is not the moment to fail over one.
     *
     * The archive holds the manifest and the file each sticker names, and so
     * carries the finished sticker only: the photo it was cut out of stays on
     * the phone (see [StickerPackStore]), because a pack is something the user
     * hands to somebody else.
     */
    fun write(
        out: OutputStream,
        pack: StickerPack,
        appVersion: Int,
        appVersionName: String,
        fileFor: (CustomSticker) -> File?,
    ) {
        ZipOutputStream(out.buffered()).use { zip ->
            val present = pack.stickers.filter { fileFor(it)?.isFile == true }
            zip.putNextEntry(ZipEntry(MANIFEST))
            zip.write(
                json.encodeToString(
                    StickerEnvelope(
                        format = FORMAT,
                        version = VERSION,
                        appVersion = appVersion,
                        appVersionName = appVersionName,
                        pack = pack.copy(stickers = present),
                    )
                ).toByteArray()
            )
            zip.closeEntry()
            for (sticker in present) {
                val file = fileFor(sticker) ?: continue
                zip.putNextEntry(ZipEntry(STICKER_DIR + sticker.fileName))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    /**
     * Reads a pack out of [input] and registers it with [store] under a fresh
     * id, so importing the same file twice can never collide with itself.
     *
     * [defaultName] names a pack whose own file gives no name. The caller
     * passes it because the import runs off the main thread with no context of
     * its own: the wording is `R.string.core_content_sticker_pack_imported_label`,
     * and the screen that starts the import resolves it.
     *
     * [normalize] turns raw bytes into storable sticker bytes; the default runs
     * them through [StickerImage], which keeps conforming files untouched and
     * re-encodes everything else.
     */
    fun import(
        input: InputStream,
        store: StickerPackStore,
        defaultName: String,
        now: Long = System.currentTimeMillis(),
        normalize: (ByteArray) -> ProcessedSticker? = { bytes ->
            (StickerImage.process(bytes) as? StickerImage.Result.Ok)?.sticker
        },
    ): StickerImportResult {
        val staging = store.stagingDir() ?: return StickerImportResult.Failed
        try {
            val unpacked = unpack(input, staging)
            if (!unpacked.read && unpacked.manifest == null) return StickerImportResult.Failed
            val staged = unpacked.files

            val envelope = unpacked.manifest
                ?.let { runCatching { json.decodeFromString<ReadEnvelope>(it) }.getOrNull() }
                ?: return StickerImportResult.NotAStickerPack
            if (envelope.format != FORMAT) return StickerImportResult.NotAStickerPack

            val incoming = envelope.declared.map { declared ->
                val source = declared.source
                StickerPackAdoption.Incoming(
                    label = declared.label(),
                    name = declared.name,
                    emojis = declared.emojis,
                    addedAt = declared.addedAt,
                    problem = if (source.isEmpty()) {
                        ContentText(R.string.core_content_sticker_repair_no_image_named)
                    } else {
                        null
                    },
                    read = { staged.lookUp(source)?.readBytes() },
                )
            }
            return StickerPackAdoption.adopt(
                store = store,
                name = envelope.pack.name.trim().ifBlank { defaultName },
                incoming = incoming,
                now = now,
                source = envelope.pack.source.trim().take(StickerPack.MAX_SOURCE_LENGTH),
                normalize = normalize,
            )
        } finally {
            staging.deleteRecursively()
        }
    }

    /** The archive spilled into a staging directory: entry name -> staged file. */
    private class Unpacked(
        val files: Map<String, File>,
        val manifest: String?,
        /** False when the archive read threw partway; a manifest may still have landed. */
        val read: Boolean,
    )

    /**
     * Spills every entry into [staging] under a name of our own choosing, and
     * keeps the manifest as text.
     *
     * Nothing is written to a path derived from an entry name — the map's keys
     * carry the names, and only for looking entries up afterwards.
     */
    private fun unpack(input: InputStream, staging: File): Unpacked {
        val staged = HashMap<String, File>()
        var manifest: String? = null
        val read = runCatching {
            ZipInputStream(input.buffered()).use { zip ->
                var count = 0
                var total = 0L
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) continue
                    if (++count > MAX_ENTRIES) break
                    val name = entry.name
                    if (name == MANIFEST) {
                        manifest = zip.readBytes().decodeToString()
                        continue
                    }
                    val target = File(staging, "e$count.bin")
                    val written = spill(zip, target, total)
                    total += written
                    if (written < 0 || written > StickerImage.MAX_SOURCE_BYTES) {
                        target.delete()
                        if (written < 0) break
                    } else {
                        staged[name] = target
                    }
                }
            }
            true
        }.getOrDefault(false)
        return Unpacked(staged, manifest, read)
    }

    /**
     * Copies one entry into [target]. Returns the bytes written, or -1 once the
     * archive as a whole has gone past [MAX_TOTAL_BYTES] — at which point there
     * is no point reading the rest of it.
     */
    private fun spill(zip: ZipInputStream, target: File, soFar: Long): Long {
        var written = 0L
        var overran = false
        target.outputStream().buffered().use { sink ->
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val n = zip.read(buffer)
                if (n <= 0) break
                written += n
                if (soFar + written > MAX_TOTAL_BYTES) {
                    overran = true
                    break
                }
                if (written > StickerImage.MAX_SOURCE_BYTES) break
                sink.write(buffer, 0, n)
            }
        }
        return if (overran) -1 else written
    }

    /**
     * Finds the archive entry a manifest entry names, trying what it said, that
     * name under `stickers/`, and — last — its bare tail, so `stickers/a.png`,
     * `a.png` and `images/a.png` all find `stickers/a.png`.
     *
     * Matching on the tail is safe because the value never becomes a path: the
     * result is a staged file this importer wrote itself under a name it chose.
     */
    private fun Map<String, File>.lookUp(source: String): File? {
        this[source]?.let { return it }
        this[STICKER_DIR + source]?.let { return it }
        val tail = source.substringAfterLast('/').substringAfterLast('\\')
        if (tail.isEmpty() || tail == source) return null
        return this[tail] ?: this[STICKER_DIR + tail] ?: entries
            .firstOrNull { it.key.substringAfterLast('/') == tail }
            ?.value
    }
}
