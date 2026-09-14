package com.wasimaster.wmkeyboard.core.selection

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlin.io.encoding.Base64

/**
 * Two encodings a selection can arrive in and be wanted out of: Base64 and
 * percent-encoding.
 *
 * Detection is deliberately strict. Base64 has no signature, so an ordinary
 * word like `Password` fits its alphabet; the gate demands the shape (length,
 * padding, mixed case, a digit or padding) and then a decode that yields
 * readable text. Percent-encoding needs at least one `%XX`, and `50% off` has
 * none.
 */
object TextCodecs {

    /** Longer than this is a file, and decoding it into a text field is not a favour. */
    const val MAX_LENGTH = 4000

    private val PERCENT = Regex("""%[0-9A-Fa-f]{2}""")

    fun looksBase64(text: String): Boolean {
        val t = text.trim()
        if (t.length < 8 || t.length > MAX_LENGTH) return false
        val standard = t.all { (it in 'A'..'Z') || (it in 'a'..'z') || (it in '0'..'9') || it == '+' || it == '/' || it == '=' }
        val urlSafe = t.all { (it in 'A'..'Z') || (it in 'a'..'z') || (it in '0'..'9') || it == '-' || it == '_' || it == '=' }
        if (!standard && !urlSafe) return false
        val unpadded = t.trimEnd('=')
        if (unpadded.length % 4 == 1) return false
        if (unpadded.length != t.length && t.length % 4 != 0) return false
        if (!t.any { it in 'A'..'Z' } || !t.any { it in 'a'..'z' }) return false
        if (!t.any { it in '0'..'9' } && !t.endsWith('=')) return false
        return base64Decode(t) != null
    }

    /** The text [text] encodes, or null when it is not Base64 or not text. */
    fun base64Decode(text: String): String? {
        val t = text.trim()
        if (t.isEmpty() || t.length > MAX_LENGTH) return null
        val bytes = runCatching { Base64.Default.withPadding(Base64.PaddingOption.PRESENT_OPTIONAL).decode(t) }.getOrNull()
            ?: runCatching { Base64.UrlSafe.withPadding(Base64.PaddingOption.PRESENT_OPTIONAL).decode(t) }.getOrNull()
            ?: return null
        val decoded = runCatching {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        }.getOrNull() ?: return null
        return decoded.takeIf { readable(it) }
    }

    fun isUrlEncoded(text: String): Boolean = text.length <= MAX_LENGTH && PERCENT.containsMatchIn(text)

    /**
     * [text] with every `%XX` run decoded as UTF-8. A `+` stays a `+`: this
     * is for a link somebody selected, where `+` is a plus, not a form
     * field. Null on a broken sequence or when nothing changed.
     */
    fun urlDecode(text: String): String? {
        if (!isUrlEncoded(text)) return null
        val out = StringBuilder(text.length)
        val bytes = ArrayList<Byte>()
        var i = 0
        fun flushBytes(): Boolean {
            if (bytes.isEmpty()) return true
            val decoded = runCatching {
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes.toByteArray()))
                    .toString()
            }.getOrNull() ?: return false
            out.append(decoded)
            bytes.clear()
            return true
        }
        while (i < text.length) {
            val c = text[i]
            if (c == '%') {
                if (i + 2 >= text.length) return null
                val value = text.substring(i + 1, i + 3).toIntOrNull(16) ?: return null
                bytes += value.toByte()
                i += 3
                continue
            }
            if (!flushBytes()) return null
            out.append(c)
            i++
        }
        if (!flushBytes()) return null
        return out.toString().takeIf { it != text }
    }

    private fun readable(text: String): Boolean =
        text.any { it.isLetterOrDigit() } && text.all { printable(it) }

    private fun printable(c: Char): Boolean =
        (c.code >= 0x20 || c == '\n' || c == '\r' || c == '\t') && c.code != 0x7F && c != '\uFFFD'
}
