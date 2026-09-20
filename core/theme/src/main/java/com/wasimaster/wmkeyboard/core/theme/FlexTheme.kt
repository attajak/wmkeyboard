package com.wasimaster.wmkeyboard.core.theme

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Reading a FlorisBoard `.flex` theme extension.
 *
 * A `.flex` is a ZIP holding an `extension.json` and one stylesheet per theme,
 * written in FlorisBoard's own style language, snygg. **Written from the
 * published format, not from FlorisBoard's source** — FlorisBoard is Apache-2.0
 * and this app is MIT — and the test fixtures are hand-written for the same
 * reason.
 *
 * ### This conversion is lossy, and says so
 *
 * Snygg styles a much larger set of elements than [ThemeSpec] has fields for,
 * and styles them per element, per corner and per state. What lands is the
 * colour: board, keys, the enter key, the pressed state, popups, borders. What
 * does not is geometry beyond a single corner radius, per-element spacing,
 * elevation, and every element this keyboard does not have. [Converted] carries
 * the count of rules used against the count read, so the import can put a real
 * number in front of the user instead of implying the theme came across whole.
 *
 * ### Fields are left null rather than guessed
 *
 * Most of [ThemeSpec]'s colours are nullable and derive something legible when
 * null. A colour scraped from a selector that meant a different surface is worse
 * than that default, and worse still because the user cannot tell a converted
 * value from a defaulted one and will go and "fix" the wrong control. So a field
 * is written only when the stylesheet says something unambiguous about the same
 * surface.
 *
 * ### No image is decoded here
 *
 * Bytes come back as bytes. Turning them into a stored theme needs
 * `android.util.Base64` and a files directory, neither of which belongs in a
 * parser and both of which would put this file out of reach of a unit test.
 * `withExtractedImages` already owns that step; the caller feeds it.
 */
object FlexTheme {

    const val MANIFEST = "extension.json"

    /** The `$` an extension.json carries when it is a theme extension. */
    const val FORMAT = "ime.extension.theme"

    const val FILE_EXTENSION = "flex"

    /**
     * What the import picker accepts. Permissive for the reason the other
     * archive formats are: providers report an unusual extension as
     * `application/octet-stream` as often as not, and the real check is the
     * manifest inside.
     */
    val IMPORT_MIME_TYPES = arrayOf(
        "application/zip",
        "application/x-zip-compressed",
        "application/octet-stream",
    )

    /**
     * Zip-bomb guards. The total matches what the addon repository will already
     * download for a theme, so nothing that could be installed from a repo is
     * refused here for being too big.
     */
    private const val MAX_ENTRIES = 256
    private const val MAX_TOTAL_BYTES = 16L * 1024 * 1024
    private const val MAX_MANIFEST_BYTES = 256 * 1024
    private const val MAX_IMAGE_BYTES = 4 * 1024 * 1024
    private const val MAX_FONT_BYTES = 8 * 1024 * 1024
    private const val TTF = ".ttf"
    private const val OTF = ".otf"

    /**
     * Reads [input] and converts every theme in it.
     *
     * [palette] resolves the Material roles a stylesheet can name — see
     * [SnyggPalette]. The default stands in off-device and below Android 12;
     * `:app` passes the device's own palette so that a theme written against
     * Material You converts to the colours FlorisBoard would have drawn.
     *
     * Never throws: a truncated archive and a manifest for some other kind of
     * extension are both ordinary outcomes with their own answer.
     */
    fun read(input: InputStream, palette: SnyggPalette = SnyggPalette.Baseline): FlexResult {
        val files = runCatching { unpack(input) }.getOrNull() ?: return FlexResult.Unreadable
        val manifestText = files[MANIFEST]?.decodeToString() ?: return FlexResult.NotAFlex
        val manifest = runCatching { json.parseToJsonElement(manifestText) }.getOrNull() as? JsonObject
            ?: return FlexResult.NotAFlex
        if (manifest.string("\$") != FORMAT) return FlexResult.NotAFlex

        val meta = manifest["meta"] as? JsonObject
        val entries = (manifest["themes"] as? JsonArray)?.filterIsInstance<JsonObject>().orEmpty()
        if (entries.isEmpty()) return FlexResult.NotAFlex

        val dropped = linkedSetOf<FlexUnsupported>()
        val unknownElements = linkedSetOf<String>()
        var rules = 0
        var mapped = 0
        val themes = entries.mapNotNull { entry ->
            val sheet = stylesheetOf(entry, files) ?: return@mapNotNull null
            val night = entry.boolean("isNight") ?: true
            val style = Stylesheet.parse(sheet, palette, night) ?: return@mapNotNull null
            rules += style.ruleCount
            mapped += style.mappedCount
            dropped += style.dropped
            unknownElements += style.unknownElements
            SnyggMapper(style).convert(
                name = themeName(meta, entry, entries.size),
                id = entry.string("id").orEmpty(),
                night = night,
                files = files,
                dropped = dropped,
            )
        }
        return when {
            themes.isEmpty() -> FlexResult.NotAFlex
            else -> FlexResult.Converted(
                themes = themes,
                dropped = dropped.toList(),
                unknownElements = unknownElements.toList(),
                wallpaperColours = palette.fromDevice,
                ruleCount = rules,
                mappedRuleCount = mapped,
                license = meta?.string("license").orEmpty(),
                authors = (meta?.get("maintainers") as? JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.content }
                    .orEmpty(),
                title = meta?.string("title").orEmpty(),
            )
        }
    }

    /**
     * The stylesheet bytes for one theme entry, by the path it declares or by
     * the layout the format uses when it declares none.
     */
    private fun stylesheetOf(entry: JsonObject, files: Map<String, ByteArray>): String? {
        val id = entry.string("id").orEmpty()
        val declared = entry.string("stylesheetPath") ?: entry.string("stylesheet")
        val candidates = listOfNotNull(declared, "stylesheets/$id.json", "$id.json")
        return candidates.firstNotNullOfOrNull { lookUp(files, it) }?.decodeToString()
    }

    /**
     * A theme's name. Two themes in one extension are a day and a night pair, so
     * they are told apart rather than one of them being dropped.
     */
    private fun themeName(meta: JsonObject?, entry: JsonObject, count: Int): String {
        val base = entry.string("label")
            ?: meta?.string("title")
            ?: entry.string("id")
            ?: "Imported theme"
        if (count < 2) return base
        val suffix = if (entry.boolean("isNight") == false) "light" else "dark"
        return if (base.contains(suffix, ignoreCase = true)) base else "$base ($suffix)"
    }

    /**
     * The whole archive as bytes, keyed by entry name.
     *
     * **Entry names are never used as paths**, exactly as in `PluginFile` and
     * `IconPackFile`: a name is only ever a key in this map, and the caller
     * looks entries up by the paths the manifest declares. Neither a `../` entry
     * name nor a `../` inside `extension.json` can reach the filesystem, because
     * nothing here touches it.
     *
     * Sizes come from bytes actually read, never from what an entry declares, so
     * an entry claiming to be small and inflating to gigabytes is stopped by the
     * same cap as an honest one.
     */
    private fun unpack(input: InputStream): Map<String, ByteArray> {
        val files = HashMap<String, ByteArray>()
        ZipInputStream(input.buffered()).use { zip ->
            var count = 0
            var total = 0L
            var reading = true
            while (reading) {
                val entry = zip.nextEntry
                val remaining = MAX_TOTAL_BYTES - total
                when {
                    entry == null -> reading = false
                    entry.isDirectory -> Unit
                    ++count > MAX_ENTRIES -> reading = false
                    remaining <= 0L -> reading = false
                    else -> {
                        // The manifest gets its own, much smaller cap: it is the
                        // one entry read before anything is known about the
                        // archive, so it must not be a way to spend the whole
                        // budget before the format has even been checked.
                        val perEntry = when {
                            entry.name == MANIFEST -> MAX_MANIFEST_BYTES
                            // A font is bigger than an image by an order of
                            // magnitude: the store's own Nothing theme ships an
                            // 862 KB Inter and the whole archive is 464 KB
                            // compressed, so the image cap would truncate it.
                            entry.name.endsWith(TTF) || entry.name.endsWith(OTF) -> MAX_FONT_BYTES
                            else -> MAX_IMAGE_BYTES
                        }
                        val bytes = readCapped(zip, minOf(remaining, perEntry.toLong()).toInt())
                        total += bytes.size
                        files[entry.name] = bytes
                    }
                }
            }
        }
        return files
    }

    private fun readCapped(zip: ZipInputStream, max: Int): ByteArray {
        val out = ByteArray(max)
        var filled = 0
        val buffer = ByteArray(8 * 1024)
        while (filled < max) {
            val n = zip.read(buffer, 0, minOf(buffer.size, max - filled))
            if (n <= 0) break
            System.arraycopy(buffer, 0, out, filled, n)
            filled += n
        }
        return out.copyOf(filled)
    }

    /**
     * An entry by declared path, then by its file name alone. The second try is
     * what reads an archive whose paths carry a leading folder; the value can
     * never become a path, so a name that walks upwards simply fails to match.
     */
    internal fun lookUp(files: Map<String, ByteArray>, path: String): ByteArray? {
        files[path]?.let { return it }
        val tail = path.substringAfterLast('/').substringAfterLast('\\')
        if (tail.isEmpty()) return null
        return files[tail] ?: files.entries.firstOrNull { it.key.substringAfterLast('/') == tail }?.value
    }

    /**
     * The [ConvertedTheme.images] key for the board background. The other keys
     * are [ThemeSpec] asset slots, which already have names; the background
     * travels in its own field rather than the asset map, so it needs one here.
     */
    const val IMAGE_BACKGROUND = "background"

    internal val json = Json { isLenient = true; ignoreUnknownKeys = true }

    internal fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    internal fun JsonObject.boolean(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.content?.toBooleanStrictOrNull()

    /** Image bytes worth carrying, so a stylesheet cannot name a 4 MB icon. */
    internal fun imageBytes(files: Map<String, ByteArray>, path: String): ByteArray? =
        lookUp(files, path)?.takeIf { it.isNotEmpty() && it.size <= MAX_IMAGE_BYTES }
}

/** What reading a `.flex` produced. */
sealed interface FlexResult {

    data class Converted(
        val themes: List<ConvertedTheme>,
        /** What the stylesheets asked for that this keyboard cannot draw. */
        val dropped: List<FlexUnsupported>,
        /** The element names behind [FlexUnsupported.UNKNOWN_ELEMENT], to name them. */
        val unknownElements: List<String> = emptyList(),
        /**
         * Whether the Material You roles resolved against the device's own
         * palette. False below Android 12, where they fall back to stock
         * Material and the import must not claim the wallpaper was read.
         */
        val wallpaperColours: Boolean = false,
        /** Style rules read, and how many had somewhere to go. */
        val ruleCount: Int,
        val mappedRuleCount: Int,
        /** Carried through from the extension so it can be shown, not dropped. */
        val license: String,
        val authors: List<String>,
        /**
         * The extension's own title, for naming the family a multi-theme
         * extension becomes. Blank when the manifest has none.
         */
        val title: String = "",
    ) : FlexResult

    /** A ZIP, but not a theme extension. */
    data object NotAFlex : FlexResult

    /** Truncated, not a ZIP at all, or past the size caps. */
    data object Unreadable : FlexResult
}

/**
 * One theme, plus the image bytes its fields point at.
 *
 * The images travel beside the spec rather than inside it because encoding them
 * needs `android.util.Base64`. The caller does that, then hands the result to
 * `withExtractedImages`, which is what writes them into app-private storage and
 * rewrites the paths — the same route a native theme import takes.
 */
data class ConvertedTheme(
    val theme: ThemeSpec,
    /** Keyed by [ThemeSpec] asset slot, or `background` for the board image. */
    val images: Map<String, ByteArray>,
    /**
     * The typeface this theme asks for, when the archive carries it.
     *
     * A font here is an installed add-on that [ThemeSpec.fontId] names, so the
     * bytes cannot be stored in the theme the way an image can. They travel to
     * the caller, which installs them and writes the id back — the same shape
     * as [images], and for the same reason: installing needs a store and a
     * files directory, neither of which belongs in a parser.
     */
    val font: ConvertedFont? = null,
)

/** A typeface lifted out of a `.flex`, ready to install. */
data class ConvertedFont(
    /** The family name the stylesheet gave it, for naming the installed font. */
    val name: String,
    /** The file name inside the archive, which carries the real extension. */
    val fileName: String,
    val bytes: ByteArray,
) {
    // Arrays compare by identity, and this class rides inside a data class the
    // import screen holds in Compose state, where that difference is visible.
    override fun equals(other: Any?): Boolean =
        this === other || (other is ConvertedFont && name == other.name && fileName == other.fileName)

    override fun hashCode(): Int = 31 * name.hashCode() + fileName.hashCode()
}

/** Something a stylesheet asked for that has nowhere to go here. */
enum class FlexUnsupported {

    /**
     * The sheet gave a shadow a *colour*. The lift itself lands (see
     * `PROP_ELEVATION`); only the colour has nowhere to go, and Android ignores
     * one below version 9 anyway.
     *
     * Named for what is lost rather than for the property it was read from: as
     * `ELEVATION` it read as "this app draws no shadows", which stopped being
     * true when elevation started mapping and left the import telling users
     * their shadows had been dropped when they had not.
     */
    SHADOW_COLOR,
    PER_CORNER_RADIUS,
    PER_ELEMENT_SPACING,
    FONT,

    /**
     * The sheet named a Material You role. It is resolved against the device's
     * palette (see [SnyggPalette]) and stored as a literal colour, so the theme
     * is a snapshot: it will not follow the next wallpaper the way FlorisBoard
     * does.
     */
    DYNAMIC_COLOR,
    UNKNOWN_ELEMENT,

    /** A scraped text colour was unreadable on its background and was dropped. */
    LOW_CONTRAST_FALLBACK,
}
