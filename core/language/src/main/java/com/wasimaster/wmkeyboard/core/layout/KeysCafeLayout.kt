package com.wasimaster.wmkeyboard.core.layout

import com.wasimaster.wmkeyboard.core.script.LanguageRegistry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64
import kotlin.math.roundToInt

/**
 * Reading a Samsung Keyboard layout shared from Keys Cafe.
 *
 * Keys Cafe is Samsung's Good Lock module for its keyboard, and its *Make your
 * own keyboard* screen lets people rearrange the whole grid key by key. A grid
 * is saved, exported and shared as a `.kcf` file, and those files circulate on
 * the Samsung community forums the way HeliBoard themes circulate as pasted
 * JSON. It is a layout, never a theme: Keys Cafe's colours and effects travel
 * through Theme Park and are not in this file.
 *
 * **Written from the published description of the format, not from Samsung's
 * code.** The shape below is documented, with a decoder and a real export, in
 * `brawaru/keys-cafe-tools` (MIT); what is transcribed here is the shape of a
 * document, the same footing as the FlorisBoard, HeliBoard and FUTO readers
 * beside it. One real export has been read; everything the format can say that
 * the sample did not is read past rather than guessed at.
 *
 * ### What the file is
 *
 * Base64 text (wrapped at 76 columns, the way Android's default encoder writes
 * it) around AES-128/CBC ciphertext under a fixed key and an all-zero IV, around
 * a UTF-8 JSON object:
 *
 * ```json
 * {"keyboardName":"Dvorak","languageCode":"en","countryCode":"US",
 *  "inputType":"QWERTY_DEFAULT","model":"{…another JSON document…}","sha":"…"}
 * ```
 *
 * The `model` string is the grid: one keyboard, whose `elements` are rows, whose
 * `elements` are keys. A row says what kind it is (`rowType` 2 is the number
 * row, 1 a letter row, 3 the bottom row); a key says what it types as code
 * points (`normalKey.keyCodeLabel.keyCodes`), what it draws (`keyLabel`), its
 * shifted form (`upperKeyCodeLabel`), its press-and-hold letters
 * (`normalBubbles`, and `upperBubbles` for the shifted state), its corner
 * symbol (`secondaryKey.secondarySymbol`, which a long press also types) and its
 * width as a fraction of the keyboard. Function keys carry negative codes.
 *
 * The fixed key is Samsung's, not ours: it was recovered from the module and is
 * published in the repository above. It is here for the same reason the Gboard
 * stylesheet reader is: so a person can open the file they already have. The
 * `sha` field (a digest of the model) is read past, so a file edited by hand
 * still imports.
 *
 * ### The one format that carries the whole frame
 *
 * Every other foreign format leaves the shift, delete, space and enter keys and
 * the bottom row to the keyboard that reads it, which is why those imports get
 * this app's frame put round them (see [withHouseStructure]). A `.kcf` is the
 * output of a what-you-see editor and carries all of it, and the bottom row is
 * often the whole point of the file — a Dvorak grid that puts `q` and `z` beside
 * the space bar, say. So the grid is kept exactly as laid out, and the layer is
 * marked [LayerSpec.bottomRowAsLaidOut] so the Bottom-row settings do not move
 * its keys either. The frame is added only when the author took the space bar
 * or the delete key out, and [LayoutSpec.repair] still guarantees it types.
 *
 * The number row lands in the layout's own number-row slot
 * ([LayerSpec.numberRow]) rather than being dropped, so the file's digits, with
 * their fraction and superscript popups, show whenever the number row is on.
 *
 * ### What does not come across
 *
 * A function key whose code this build does not know is removed and the code
 * named, exactly as the HeliBoard reader does; the table in [actionForCode] is
 * one line to extend when a second export shows what a code means. Shifted
 * press-and-hold letters that are not simply the capitals of the unshifted ones
 * are counted and reported: this keyboard has one list per key. Key margins,
 * per-row heights, the split position and the colour and preview flags describe
 * the other keyboard's drawing and are not read.
 */
object KeysCafeLayouts {

    /**
     * Keys Cafe's own extension. Claimed by the manifest so a shared file opens
     * from a file manager; the real check is [convert] succeeding.
     */
    const val FILE_EXTENSION = "kcf"

    /** What the import picker accepts: a provider has never heard of `.kcf`. */
    val IMPORT_MIME_TYPES = arrayOf(
        "application/octet-stream",
        "text/plain",
    )

    /** Well above a real export: the sample, a full grid with popups, is 60 KB. */
    const val MAX_LENGTH = 512 * 1024

    /**
     * True when [text] is shaped like one of these: nothing but base64 text.
     *
     * Cheap, and decided before anything is decrypted, so the other readers are
     * never handed ciphertext. A HeliBoard text layout of plain letters can pass
     * it by accident, which is why callers fall through to the other readers
     * when [convert] then says no.
     */
    fun looksLikeKcf(text: String): Boolean {
        if (text.isEmpty() || text.length > MAX_LENGTH) return false
        var count = 0
        var padding = 0
        for (c in text) {
            when {
                c.isWhitespace() -> continue
                c == PAD -> padding++
                c in Base64Alphabet -> if (padding == 0) count++ else return false
                else -> return false
            }
        }
        val total = count + padding
        return count >= MIN_BASE64_CHARS && padding <= MAX_PADDING && total % BASE64_GROUP == 0
    }

    /**
     * Converts [text]. Null when it is not one of these, or holds no keys.
     *
     * Never throws: a wrong key, a truncated file and a base64 blob that is
     * something else entirely all come back null, and every shape inside that is
     * not a key is skipped rather than rejected.
     */
    fun convert(text: String, name: String): ConvertedLayout? {
        if (text.length > MAX_LENGTH) return null
        val plain = decrypt(text) ?: return null
        val share = parse(plain) ?: return null
        val model = share.string(FIELD_MODEL)?.let(::parse) ?: return null
        val keyboard = (model[FIELD_KEYBOARDS] as? JsonArray)
            ?.firstNotNullOfOrNull { (it as? JsonObject)?.get(FIELD_DEFAULT_KEYBOARD) as? JsonObject }
            ?: return null
        val rowElements = keyboard[FIELD_ELEMENTS] as? JsonArray ?: return null

        val raw = rowElements.mapNotNull { element ->
            val row = (element as? JsonObject)?.get(NODE_ROW) as? JsonObject ?: return@mapNotNull null
            val keys = (row[FIELD_ELEMENTS] as? JsonArray).orEmpty()
                .mapNotNull { (it as? JsonObject)?.get(NODE_KEY) as? JsonObject }
            RawRow(kind = row.int(FIELD_ROW_TYPE) ?: ROW_LETTERS, keys = keys)
        }
        if (raw.isEmpty()) return null

        val report = Report()
        val unit = widthUnit(raw.flatMap { it.keys })
        val numberRow = raw.firstOrNull { it.kind == ROW_NUMBERS }
            ?.keys?.mapNotNull { keyOf(it, unit, report, bottomRow = false) }
            ?.takeIf { it.isNotEmpty() }
        if (numberRow != null) report.numberRowKept = true
        val rows = raw.filter { it.kind != ROW_NUMBERS }
            .map { row -> row.keys.mapNotNull { keyOf(it, unit, report, bottomRow = row.kind == ROW_BOTTOM) } }
        val keys = rows.flatten()
        // The file carries its own frame unless the author took it apart; only
        // then is this app's frame put round what is left.
        val complete = keys.any { it.action == KeyAction.Space } && keys.any { it.action == KeyAction.Delete }

        return ForeignLayouts.assemble(
            rows = rows,
            name = share.string(FIELD_NAME)?.trim()?.ifEmpty { null } ?: name,
            source = ForeignSource.KEYS_CAFE,
            report = report,
            langId = declaredLanguage(share),
            numberRow = numberRow,
            houseFrame = !complete,
        )
    }

    /**
     * The inverse of the read: [shareData] (the outer JSON object, with `model`
     * already a string) as the base64 text Keys Cafe writes. For tests and
     * tooling, which is why it is public; the app never exports this format.
     */
    fun encode(shareData: String): String {
        val cipher = Cipher.getInstance(CIPHER)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey(), IvParameterSpec(ByteArray(BLOCK)))
        val encoded = Base64.encode(cipher.doFinal(shareData.encodeToByteArray()))
        return encoded.chunked(WRAP_COLUMNS).joinToString("\n", postfix = "\n")
    }

    // ---- the envelope ----

    private fun decrypt(text: String): String? = runCatching {
        val clean = text.filterNot { it.isWhitespace() }
        val bytes = Base64.decode(clean)
        if (bytes.isEmpty() || bytes.size % BLOCK != 0) return null
        val cipher = Cipher.getInstance(CIPHER)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), IvParameterSpec(ByteArray(BLOCK)))
        cipher.doFinal(bytes).decodeToString()
    }.getOrNull()

    private fun secretKey() = SecretKeySpec(KEY.encodeToByteArray(), KEY_ALGORITHM)

    private fun parse(text: String): JsonObject? =
        runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject

    /**
     * The language the file states, when it is one this app has. `en` + `US`
     * is tried as `en_us` first and then as `en`, the way the FUTO reader
     * resolves its tags.
     */
    private fun declaredLanguage(share: JsonObject): String? {
        val language = share.string(FIELD_LANGUAGE)?.trim()?.lowercase()?.ifEmpty { null } ?: return null
        val country = share.string(FIELD_COUNTRY)?.trim()?.lowercase().orEmpty()
        val candidates = listOfNotNull(country.takeIf { it.isNotEmpty() }?.let { "${language}_$it" }, language)
        return candidates.firstNotNullOfOrNull { id ->
            LanguageRegistry.all.firstOrNull { it.id.equals(id, ignoreCase = true) }?.id
        }
    }

    // ---- keys ----

    private class RawRow(val kind: Int, val keys: List<JsonObject>)

    private fun keyOf(obj: JsonObject, unit: Float?, report: Report, bottomRow: Boolean): Key? {
        val normal = obj[FIELD_NORMAL_KEY] as? JsonObject
        val main = normal?.get(FIELD_CODE_LABEL) as? JsonObject
        val codes = main?.intList(FIELD_CODES).orEmpty()
        val label = main?.string(FIELD_LABEL).orEmpty()
        val width = widthOf(obj, unit)
        val first = codes.firstOrNull()

        if (first != null && (first <= 0 || first in PositiveActions)) {
            val action = PositiveActions[first] ?: actionForCode(first)
            if (action == null) {
                report.unmapped += first
                return null
            }
            return Key(label = action.fallbackLabel(), action = action, width = width)
        }

        val output = textOf(codes) ?: label
        val drawn = label.ifEmpty { output }
        if (drawn.isEmpty()) return null
        val cleanLabel = normalizeForScript(drawn).also { if (it != drawn) report.normalized++ }
        val cleanOutput = normalizeForScript(output).takeIf { it != cleanLabel }

        // The shifted form, when it is more than the plain capital: a null
        // shiftLabel already uppercases at commit time for a cased script, and
        // writing the capital in would freeze it for every script that has none.
        val upper = (normal?.get(FIELD_UPPER_CODE_LABEL) as? JsonObject)?.let { up ->
            up.string(FIELD_LABEL)?.ifEmpty { null } ?: textOf(up.intList(FIELD_CODES))
        }
        val shiftLabel = upper?.let(::normalizeForScript)
            ?.takeIf { it.isNotEmpty() && it != cleanLabel && it != cleanLabel.uppercase() }

        // The corner symbol first, so it becomes the hint the way it is on the
        // other keyboard, then the press-and-hold letters.
        val hint = (obj[FIELD_SECONDARY] as? JsonObject)?.string(FIELD_SECONDARY_SYMBOL)
            ?.trim()?.takeIf { it.isNotEmpty() }
        val bubbles = bubbleLabels(obj[FIELD_NORMAL_BUBBLES])
        val longPress = (listOfNotNull(hint) + bubbles)
            .map(::normalizeForScript)
            .distinct()
            .filter { it != cleanLabel && it != cleanOutput }
        val upperBubbles = bubbleLabels(obj[FIELD_UPPER_BUBBLES])
        if (upperBubbles.isNotEmpty() && !upperBubbles.sameLettersAs(bubbles)) report.shiftedPopups++

        return Key(
            label = cleanLabel,
            output = cleanOutput,
            shiftLabel = shiftLabel,
            width = width,
            longPress = longPress,
            // Tagged only on the bottom row, where the slots mean something: a
            // comma in a Dvorak home row is a letter key that happens to be a comma.
            role = when {
                !bottomRow -> null
                (cleanOutput ?: cleanLabel) == "," -> KeyRole.Comma
                (cleanOutput ?: cleanLabel) == "." -> KeyRole.Period
                else -> null
            },
        )
    }

    /** The press-and-hold letters of a bubble list, by label and then by code. */
    private fun bubbleLabels(element: JsonElement?): List<String> =
        (element as? JsonArray).orEmpty().mapNotNull { bubble ->
            val obj = bubble as? JsonObject ?: return@mapNotNull null
            obj.string(FIELD_LABEL)?.ifEmpty { null } ?: textOf(obj.intList(FIELD_CODES))
        }

    /** Whether the shifted list is the unshifted one in capitals, and so no loss. */
    private fun List<String>.sameLettersAs(other: List<String>): Boolean =
        map { it.lowercase() }.toSet() == other.map { it.lowercase() }.toSet()

    /** Positive codes as text; null when there are none or one is not a code point. */
    private fun textOf(codes: List<Int>): String? {
        if (codes.isEmpty() || codes.any { it <= 0 || !Character.isValidCodePoint(it) }) return null
        return buildString { for (code in codes) appendCodePoint(code) }
    }

    // ---- widths ----

    /**
     * The width of an ordinary key in the file's unit, which is a fraction of
     * the keyboard. The most common width among the letter keys, so the grid
     * comes out in this app's unit — one for a plain key — and the function
     * keys keep their proportion to it. Null when the file has no letter keys
     * to measure by; the widths then stay fractions and [renormalizeWidths]
     * scales them.
     */
    private fun widthUnit(keys: List<JsonObject>): Float? {
        val letters = keys.filter { (it[FIELD_ATTRIBUTE] as? JsonObject)?.int(FIELD_KEY_TYPE) == KEY_TYPE_LETTER }
        val widths = (letters.ifEmpty { keys }).mapNotNull { rawWidth(it) }.filter { it > 0f }
        if (widths.isEmpty()) return null
        return widths.groupBy { (it * WIDTH_BUCKETS).roundToInt() }
            .maxByOrNull { (_, group) -> group.size }
            ?.value?.average()?.toFloat()
    }

    private fun rawWidth(obj: JsonObject): Float? =
        (obj[FIELD_SIZE] as? JsonObject)?.float(FIELD_WIDTH)?.takeIf { it.isFinite() && it > 0f }

    private fun widthOf(obj: JsonObject, unit: Float?): Float {
        val raw = rawWidth(obj) ?: return 1f
        if (unit == null) return raw
        val scaled = (raw / unit).coerceIn(MIN_WIDTH, MaxKeyWidth)
        return (scaled * ROUNDING).roundToInt() / ROUNDING
    }

    // ---- codes ----

    /**
     * The action a Samsung function code means here, or null when nothing does.
     *
     * Only what one real export showed. A code this build does not know is
     * reported with its number rather than guessed at, so the person who sees
     * the note can say what the key was.
     */
    internal fun actionForCode(code: Int): KeyAction? = when (code) {
        CODE_DELETE -> KeyAction.Delete
        CODE_SHIFT -> KeyAction.Shift
        CODE_SYMBOLS -> KeyAction.Symbols
        else -> null
    }

    /** Ordinary code points over there that are actions here: space and enter. */
    private val PositiveActions: Map<Int, KeyAction> = mapOf(
        0x20 to KeyAction.Space,
        0x0A to KeyAction.Enter,
        0x0D to KeyAction.Enter,
    )

    private const val CODE_DELETE = -5
    private const val CODE_SHIFT = -400
    private const val CODE_SYMBOLS = -102

    // ---- JSON ----

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.int(key: String): Int? =
        (this[key] as? JsonPrimitive)?.content?.toIntOrNull()

    private fun JsonObject.float(key: String): Float? =
        (this[key] as? JsonPrimitive)?.content?.toFloatOrNull()

    private fun JsonObject.intList(key: String): List<Int> =
        (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content?.toIntOrNull() }.orEmpty()

    private val json = Json { isLenient = true; ignoreUnknownKeys = true }

    private const val FIELD_MODEL = "model"
    private const val FIELD_NAME = "keyboardName"
    private const val FIELD_LANGUAGE = "languageCode"
    private const val FIELD_COUNTRY = "countryCode"
    private const val FIELD_KEYBOARDS = "keyboards"
    private const val FIELD_DEFAULT_KEYBOARD = "defaultKeyboard"
    private const val FIELD_ELEMENTS = "elements"
    private const val FIELD_ROW_TYPE = "rowType"
    private const val FIELD_NORMAL_KEY = "normalKey"
    private const val FIELD_CODE_LABEL = "keyCodeLabel"
    private const val FIELD_UPPER_CODE_LABEL = "upperKeyCodeLabel"
    private const val FIELD_CODES = "keyCodes"
    private const val FIELD_LABEL = "keyLabel"
    private const val FIELD_NORMAL_BUBBLES = "normalBubbles"
    private const val FIELD_UPPER_BUBBLES = "upperBubbles"
    private const val FIELD_SECONDARY = "secondaryKey"
    private const val FIELD_SECONDARY_SYMBOL = "secondarySymbol"
    private const val FIELD_ATTRIBUTE = "keyAttribute"
    private const val FIELD_KEY_TYPE = "keyType"
    private const val FIELD_SIZE = "size"
    private const val FIELD_WIDTH = "width"
    private const val NODE_ROW = "ROW"
    private const val NODE_KEY = "KEY"

    private const val ROW_LETTERS = 1
    private const val ROW_NUMBERS = 2
    private const val ROW_BOTTOM = 3
    private const val KEY_TYPE_LETTER = 1

    // ---- the cipher ----

    private const val CIPHER = "AES/CBC/PKCS5Padding"
    private const val KEY_ALGORITHM = "AES"

    /** Samsung's fixed key, as published; see the class comment. */
    private const val KEY = "k!e@y#s\$c%a^f&e*"
    private const val BLOCK = 16
    private const val WRAP_COLUMNS = 76
    private const val PAD = '='
    private const val BASE64_GROUP = 4
    private const val MAX_PADDING = 2

    /** One cipher block, which is the least a real file can hold. */
    private const val MIN_BASE64_CHARS = 22

    private val Base64Alphabet: Set<Char> =
        ("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/").toSet()

    private const val WIDTH_BUCKETS = 1000f
    private const val ROUNDING = 100f
    private const val MIN_WIDTH = 0.1f
}
