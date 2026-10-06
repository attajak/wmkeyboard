package com.wasimaster.wmkeyboard.core.icons

import com.wasimaster.wmkeyboard.icons.R
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * The `.wmicons` icon-pack file: the app's native icon export and import.
 *
 * ```
 * mypack.wmicons
 * ├── pack.json
 * └── icons/<slotId>.<svg|png|webp|jpg|gif>
 * ```
 *
 * `pack.json` is the same versioned envelope the layout and sticker formats
 * use — `format` is the one strict check, and everything past it is repaired
 * and reported rather than refused, because a hand-built pack shouldn't fail
 * over one bad file:
 *
 * ```json
 * {
 *   "format": "wmkeyboard-icons",
 *   "version": 1,
 *   "appVersion": 41,
 *   "appVersionName": "1.4.0",
 *   "pack": {
 *     "id": "rounded",
 *     "name": "Rounded",
 *     "author": "…",
 *     "description": "…",
 *     "version": "1.0.0",
 *     "slots": ["tool.clipboard", "key.enter_send", "…"]
 *   }
 * }
 * ```
 *
 * A ZIP rather than the base64-in-JSON the theme export uses, for the same
 * reason the sticker format is: a full pack is ~90 separate files and the
 * archive streams entry by entry.
 *
 * Slot ids are the file names — an icon for `tool.clipboard` is
 * `icons/tool.clipboard.svg`, or `.png` for a raster one (issue #504). An
 * alternative look for a slot adds `@<variant>` to the id —
 * `icons/tool.gif@text.svg` — and is listed in `slots` under that same key. The list
 * in `slots` is advisory; the import walks the archive's entries and keeps every
 * one whose name matches a slot [IconSlots] knows, so a pack hand-assembled
 * without a careful manifest still works.
 *
 * **Entry names are never used as paths.** An entry is matched against the
 * known slot ids and then written to a name derived from *that id* and from the
 * format its own bytes turn out to be, so neither `../` in an entry name nor
 * anything in the manifest can escape the pack directory, and a `.png` that is
 * really an SVG is stored as what it is. Icons the app has no slot for are
 * dropped, not stored.
 */
object IconPackFile {

    const val FORMAT = "wmkeyboard-icons"
    const val VERSION = 1
    const val FILE_EXTENSION = "wmicons"
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

    const val MANIFEST = "pack.json"
    private const val ICON_DIR = "icons/"

    /**
     * Zip-bomb guards: a full pack is ~90 small files, so nothing legitimate is
     * near these. The total is sized for a raster pack — ninety PNGs at the
     * per-file cap would not fit in the old 8 MB, and a pack of photographs is
     * not what either number is defending against.
     */
    private const val MAX_ENTRIES = 400
    private const val MAX_TOTAL_BYTES = 24L * 1024 * 1024

    /** The largest a single icon may be, whichever kind it is. */
    private val MAX_ICON_BYTES = maxOf(SvgParser.MAX_SOURCE_BYTES, RasterIcons.MAX_SOURCE_BYTES)

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

    @Serializable
    private data class IconEnvelope(
        val format: String = "",
        val version: Int = 0,
        val appVersion: Int = 0,
        val appVersionName: String = "",
        val pack: IconPack = IconPack(id = "", name = ""),
    )

    fun fileName(pack: IconPack): String {
        val stem = pack.name.ifBlank { "icons" }
            .replace(Regex("[^\\p{L}\\p{N} _-]"), "")
            .trim()
            .ifBlank { "icons" }
        return "$stem.$FILE_EXTENSION"
    }

    /**
     * Writes [pack] and its SVGs to [out]. A slot whose file has gone missing
     * is skipped and left out of the manifest — exporting is not the moment to
     * fail over one.
     */
    fun write(
        out: OutputStream,
        pack: IconPack,
        appVersion: Int,
        appVersionName: String,
        fileFor: (String) -> File?,
    ) {
        ZipOutputStream(out.buffered()).use { zip ->
            val present = pack.slots.filter { fileFor(it)?.isFile == true }
            zip.putNextEntry(ZipEntry(MANIFEST))
            zip.write(
                json.encodeToString(
                    IconEnvelope(
                        format = FORMAT,
                        version = VERSION,
                        appVersion = appVersion,
                        appVersionName = appVersionName,
                        pack = pack.copy(slots = present),
                    )
                ).toByteArray()
            )
            zip.closeEntry()
            for (slot in present) {
                val file = fileFor(slot) ?: continue
                // The extension the file actually has on disk, so a raster icon
                // does not travel claiming to be an SVG.
                val extension = file.name.substringAfterLast('.', "svg")
                zip.putNextEntry(ZipEntry(ICON_DIR + IconPackStore.fileNameFor(slot, extension)))
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
     * its own: the wording is `R.string.core_icons_pack_imported_label`, and
     * the screen that starts the import resolves it.
     */
    fun import(
        input: InputStream,
        store: IconPackStore,
        defaultName: String,
        now: Long = System.currentTimeMillis(),
    ): IconImportResult {
        val staging = store.stagingDir() ?: return IconImportResult.Failed
        // Cleared once the pack is registered; until then any exit path takes
        // the half-filled directory with it.
        var unadopted: File? = null
        try {
            // Slot id → the staged file holding its SVG.
            val staged = HashMap<String, File>()
            val skipped = ArrayList<String>()
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
                        // The entry name is matched against the known slots and
                        // then discarded; it never reaches the filesystem.
                        val slot = slotForEntry(name)
                        if (slot == null) {
                            if (skipped.size < 8) skipped += name.substringAfterLast('/')
                            continue
                        }
                        // `.bin`, because what the bytes turn out to be is not
                        // known until they have all been read.
                        val target = File(staging, "e$count.bin")
                        var written = 0L
                        target.outputStream().buffered().use { sink ->
                            val buffer = ByteArray(16 * 1024)
                            while (true) {
                                val n = zip.read(buffer)
                                if (n <= 0) break
                                written += n
                                total += n
                                if (written > MAX_ICON_BYTES || total > MAX_TOTAL_BYTES) break
                                sink.write(buffer, 0, n)
                            }
                        }
                        if (written > MAX_ICON_BYTES || total > MAX_TOTAL_BYTES) {
                            target.delete()
                            if (total > MAX_TOTAL_BYTES) break
                        } else {
                            staged[slot] = target
                        }
                    }
                }
                true
            }.getOrDefault(false)
            if (!read && manifest == null) return IconImportResult.Failed

            val envelope = manifest
                ?.let { runCatching { json.decodeFromString<IconEnvelope>(it) }.getOrNull() }
                ?: return IconImportResult.NotAnIconPack
            if (envelope.format != FORMAT) return IconImportResult.NotAnIconPack

            val repairs = ArrayList<IconText>()
            if (skipped.isNotEmpty()) {
                repairs += IconText(
                    pluralsRes = R.plurals.core_icons_repair_files_ignored,
                    quantity = skipped.size,
                    args = listOf(skipped.size, skipped.joinToString(", ")),
                )
            }

            val packId = store.freePackId(now)
            val packDir = store.packDir(packId) ?: return IconImportResult.Failed
            unadopted = packDir
            val kept = ArrayList<String>()
            // Manifest order first so the pack author's ordering survives, then
            // anything in the archive the manifest forgot to list.
            val order = envelope.pack.slots.filter { it in staged } +
                staged.keys.filter { it !in envelope.pack.slots }
            for (slot in order.distinct()) {
                val source = staged[slot] ?: continue
                val bytes = runCatching { source.readBytes() }.getOrNull()
                val extension = bytes?.let(::extensionOf)
                if (extension == null) {
                    repairs += IconText(R.string.core_icons_repair_svg_unreadable, args = listOf(slot))
                    continue
                }
                val ok = runCatching {
                    File(packDir, IconPackStore.fileNameFor(slot, extension)).writeBytes(bytes)
                    true
                }.getOrDefault(false)
                if (!ok) {
                    repairs += IconText(R.string.core_icons_repair_not_saved, args = listOf(slot))
                    continue
                }
                kept += slot
            }
            if (kept.isEmpty()) return IconImportResult.Failed

            val pack = IconPack(
                id = packId,
                name = envelope.pack.name.trim().ifBlank { defaultName },
                author = envelope.pack.author.trim(),
                description = envelope.pack.description.trim(),
                version = envelope.pack.version.trim(),
                slots = kept,
                createdAt = now,
            )
            val adopted = store.adoptPack(pack) ?: return IconImportResult.TooManyPacks
            unadopted = null
            return IconImportResult.Imported(adopted, repairs)
        } finally {
            staging.deleteRecursively()
            unadopted?.deleteRecursively()
        }
    }

    /**
     * `icons/tool.clipboard.svg` → `tool.clipboard`, or null when the entry
     * names no slot this version knows. Accepts the file at the archive root
     * too, since that is how a pack assembled by hand often ends up, and any of
     * the raster extensions as well as `.svg`. A variant entry keeps its
     * variant: `icons/tool.gif@text.svg` → `tool.gif@text`.
     *
     * The extension is only used to find where the slot id ends. What the file
     * actually *is* comes from [extensionOf], reading the bytes.
     */
    fun slotForEntry(entryName: String): String? {
        val base = entryName.substringAfterLast('/').substringAfterLast('\\')
        val extension = RasterIcons.FILE_EXTENSIONS
            .firstOrNull { base.endsWith(".$it", ignoreCase = true) } ?: return null
        val key = base.dropLast(extension.length + 1).lowercase()
        return key.takeIf(IconSlots::isKnownKey)
    }

    /**
     * The extension [bytes] should be stored under, or null when they are not
     * an icon this can draw.
     *
     * The same judgement [IconPackStore.setIcon] makes, and deliberately made
     * from the content: an archive is a stranger's file, and its entry names
     * are only ever a hint about which slot was meant.
     */
    private fun extensionOf(bytes: ByteArray): String? {
        RasterIcons.read(bytes)?.let { return it.format.extension }
        if (bytes.size > SvgParser.MAX_SOURCE_BYTES) return null
        val text = runCatching { bytes.decodeToString() }.getOrNull() ?: return null
        return if (SvgParser.parse(text) != null) "svg" else null
    }
}
