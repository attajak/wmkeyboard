package com.wasimaster.wmkeyboard.core.selection

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** The written forms a colour can take on the bar. */
enum class ColourForm { HEX, HEX_ALPHA, HEX_0X, RGB, RGBA, HSL, HSLA }

/**
 * One parsed colour. Channels are 0..255, [alpha] 0..1, [form] the shape the
 * selection was written in, which the ladder leaves out.
 */
data class Colour(val r: Int, val g: Int, val b: Int, val alpha: Float, val form: ColourForm) {
    val hasAlpha: Boolean get() = alpha < 1f

    /** Packed ARGB, for a swatch. */
    val argb: Int
        get() = ((alpha * 255f).roundToInt() shl 24) or (r shl 16) or (g shl 8) or b
}

/**
 * Reads a selection that is one colour code and rewrites it in another form.
 *
 * Hex is read the CSS way (`#rrggbbaa`, alpha last) and the `0x` literal the
 * Android way (`0xAARRGGBB`, alpha first), because those are the two places
 * each spelling comes from. Everything here is pure: no `android.graphics`,
 * so the maths has a unit test and the `Color(argb)` for the swatch is the
 * caller's one line.
 */
object ColourCodes {

    const val MAX_LENGTH = 40

    private val HEX = Regex("""#([0-9a-f]{3,8})""", RegexOption.IGNORE_CASE)
    private val HEX_0X = Regex("""0x([0-9a-f]{6}|[0-9a-f]{8})""", RegexOption.IGNORE_CASE)
    private val FUNCTION = Regex("""(rgba?|hsla?)\s*\((.*)\)""", RegexOption.IGNORE_CASE)
    private val SPLIT = Regex("""\s*[,/]\s*|\s+""")

    /** [text] as a colour, when the whole trimmed selection is one. */
    fun parse(text: String): Colour? {
        val t = text.trim()
        if (t.isEmpty() || t.length > MAX_LENGTH) return null
        val lower = t.lowercase(Locale.ROOT)
        if (!(lower.startsWith("#") || lower.startsWith("0x") || lower.startsWith("rgb") || lower.startsWith("hsl"))) return null
        HEX.matchEntire(t)?.let { return hex(it.groupValues[1]) }
        HEX_0X.matchEntire(t)?.let { return hex0x(it.groupValues[1]) }
        val call = FUNCTION.matchEntire(t) ?: return null
        val parts = call.groupValues[2].trim().split(SPLIT).filter { it.isNotEmpty() }
        if (parts.size !in 3..4) return null
        val alpha = if (parts.size == 4) alpha(parts[3]) ?: return null else 1f
        return if (call.groupValues[1].lowercase(Locale.ROOT).startsWith("rgb")) rgb(parts, alpha) else hsl(parts, alpha)
    }

    private fun rgb(parts: List<String>, alpha: Float): Colour? {
        val r = channel(parts[0]) ?: return null
        val g = channel(parts[1]) ?: return null
        val b = channel(parts[2]) ?: return null
        return Colour(r, g, b, alpha, if (parts.size == 4) ColourForm.RGBA else ColourForm.RGB)
    }

    private fun hsl(parts: List<String>, alpha: Float): Colour? {
        val h = parts[0].removeSuffix("deg").toFloatOrNull() ?: return null
        val s = percent(parts[1]) ?: return null
        val l = percent(parts[2]) ?: return null
        val rgb = hslToRgb(((h % 360f) + 360f) % 360f, s, l)
        return Colour(rgb[0], rgb[1], rgb[2], alpha, if (parts.size == 4) ColourForm.HSLA else ColourForm.HSL)
    }

    /** [colour] written as [form]. */
    fun render(colour: Colour, form: ColourForm): String = when (form) {
        ColourForm.HEX -> "#%02x%02x%02x".format(Locale.ROOT, colour.r, colour.g, colour.b)
        ColourForm.HEX_ALPHA -> "#%02x%02x%02x%02x".format(Locale.ROOT, colour.r, colour.g, colour.b, alphaByte(colour))
        ColourForm.HEX_0X -> "0x%02X%02X%02X".format(Locale.ROOT, colour.r, colour.g, colour.b)
        ColourForm.RGB -> "rgb(${colour.r}, ${colour.g}, ${colour.b})"
        ColourForm.RGBA -> "rgba(${colour.r}, ${colour.g}, ${colour.b}, ${alphaText(colour)})"
        ColourForm.HSL -> rgbToHsl(colour.r, colour.g, colour.b).let { (h, s, l) ->
            "hsl(${h.roundToInt()}, ${s.roundToInt()}%, ${l.roundToInt()}%)"
        }
        ColourForm.HSLA -> rgbToHsl(colour.r, colour.g, colour.b).let { (h, s, l) ->
            "hsla(${h.roundToInt()}, ${s.roundToInt()}%, ${l.roundToInt()}%, ${alphaText(colour)})"
        }
    }

    /** The forms worth offering for [colour]: everything but the one it is in, alpha forms only with alpha. */
    fun ladder(colour: Colour): List<ColourForm> {
        val forms = if (colour.hasAlpha) {
            ColourForm.entries
        } else {
            listOf(ColourForm.HEX, ColourForm.HEX_0X, ColourForm.RGB, ColourForm.HSL)
        }
        return forms.filter { it != colour.form }
    }

    /** Hue 0..360, saturation and lightness 0..100. */
    internal fun rgbToHsl(r: Int, g: Int, b: Int): FloatArray {
        val rf = r / 255f
        val gf = g / 255f
        val bf = b / 255f
        val max = maxOf(rf, gf, bf)
        val min = minOf(rf, gf, bf)
        val l = (max + min) / 2f
        val d = max - min
        if (d < 1e-6f) return floatArrayOf(0f, 0f, l * 100f)
        val s = if (l > 0.5f) d / (2f - max - min) else d / (max + min)
        var h = when (max) {
            rf -> (gf - bf) / d + (if (gf < bf) 6f else 0f)
            gf -> (bf - rf) / d + 2f
            else -> (rf - gf) / d + 4f
        } * 60f
        if (h >= 360f) h -= 360f
        return floatArrayOf(h, s * 100f, l * 100f)
    }

    internal fun hslToRgb(h: Float, s: Float, l: Float): IntArray {
        val sf = s / 100f
        val lf = l / 100f
        val c = (1f - abs(2f * lf - 1f)) * sf
        val hp = h / 60f
        val x = c * (1f - abs(hp % 2f - 1f))
        val (r1, g1, b1) = when {
            hp < 1f -> Triple(c, x, 0f)
            hp < 2f -> Triple(x, c, 0f)
            hp < 3f -> Triple(0f, c, x)
            hp < 4f -> Triple(0f, x, c)
            hp < 5f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        val m = lf - c / 2f
        return intArrayOf(((r1 + m) * 255f).roundToInt(), ((g1 + m) * 255f).roundToInt(), ((b1 + m) * 255f).roundToInt())
    }

    private fun hex(digits: String): Colour? {
        val full = when (digits.length) {
            3, 4 -> digits.map { "$it$it" }.joinToString("")
            6, 8 -> digits
            else -> return null
        }
        val value = full.toLongOrNull(16) ?: return null
        return if (full.length == 6) {
            Colour(
                ((value shr 16) and 0xFF).toInt(), ((value shr 8) and 0xFF).toInt(), (value and 0xFF).toInt(),
                1f, ColourForm.HEX,
            )
        } else {
            Colour(
                ((value shr 24) and 0xFF).toInt(), ((value shr 16) and 0xFF).toInt(), ((value shr 8) and 0xFF).toInt(),
                (value and 0xFF).toInt() / 255f, ColourForm.HEX_ALPHA,
            )
        }
    }

    private fun hex0x(digits: String): Colour? {
        val value = digits.toLongOrNull(16) ?: return null
        val alpha = if (digits.length == 8) ((value shr 24) and 0xFF).toInt() / 255f else 1f
        return Colour(
            ((value shr 16) and 0xFF).toInt(), ((value shr 8) and 0xFF).toInt(), (value and 0xFF).toInt(),
            alpha, ColourForm.HEX_0X,
        )
    }

    private fun channel(part: String): Int? {
        if (part.endsWith("%")) {
            val pct = part.dropLast(1).toFloatOrNull() ?: return null
            if (pct < 0f || pct > 100f) return null
            return (pct / 100f * 255f).roundToInt()
        }
        val value = part.toIntOrNull() ?: return null
        return value.takeIf { it in 0..255 }
    }

    private fun percent(part: String): Float? {
        val value = part.removeSuffix("%").toFloatOrNull() ?: return null
        return value.takeIf { it in 0f..100f }
    }

    private fun alpha(part: String): Float? {
        if (part.endsWith("%")) {
            val pct = part.dropLast(1).toFloatOrNull() ?: return null
            return (pct / 100f).takeIf { it in 0f..1f }
        }
        val value = part.toFloatOrNull() ?: return null
        return value.takeIf { it in 0f..1f }
    }

    private fun alphaByte(colour: Colour): Int = (colour.alpha * 255f).roundToInt()

    private fun alphaText(colour: Colour): String {
        val rounded = (colour.alpha * 100f).roundToInt() / 100f
        return rounded.toString().trimEnd('0').trimEnd('.').ifEmpty { "0" }
    }
}
