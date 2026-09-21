package com.wasimaster.wmkeyboard.core.media

import com.wasimaster.wmkeyboard.core.settings.MediaSendMode

/**
 * MIME types for outgoing rich content, and the order to offer them in.
 *
 * The platform has no sticker flag: commitContent's only documented flag is
 * INPUT_CONTENT_GRANT_READ_URI_PERMISSION, and the entire sticker-vs-image
 * signal is the MIME string matched against EditorInfo.contentMimeTypes.
 * So "send as sticker" can only mean "offer a sticker MIME first, and let
 * the field fall back to a plain image when it doesn't advertise one".
 *
 * [WHATSAPP_STICKER] is a private contract between WhatsApp and Gboard —
 * it is not a valid RFC 2045 subtype and appears in no public docs, but
 * both sides implement it (WhatsApp's rich-content receiver lists it
 * alongside image/gif and image/png; Gboard maps it to a "wasticker_webp"
 * extension). Notably WhatsApp advertises this and *not* plain image/webp,
 * so a WebP sticker offered only as image/webp is refused outright.
 * Treat it as best-effort: it can change without notice, hence the
 * fallback chain rather than a hardcoded per-app rule.
 */
object MediaMime {

    const val WHATSAPP_STICKER = "image/webp.wasticker"
    const val WEBP = "image/webp"
    const val PNG = "image/png"
    const val GIF = "image/gif"
    const val JPEG = "image/jpeg"

    /**
     * MIME types to offer for [mimeType], most preferred first. The caller
     * picks the first one the target field advertises.
     *
     * A sticker MIME may only be offered for bytes that really are WebP —
     * declaring it for a GIF would hand the app a file that doesn't match
     * its own MIME. A GIF is stored and sent as the GIF it is (only an
     * animated PNG is re-encoded, see [AnimatedWebpWriter]), so for one this
     * returns the plain type and the send behaves as [MediaSendMode.IMAGE].
     */
    fun candidates(mimeType: String, mode: MediaSendMode): List<String> =
        if (mode == MediaSendMode.STICKER && mimeType == WEBP) {
            listOf(WHATSAPP_STICKER, WEBP)
        } else {
            listOf(mimeType)
        }

    /**
     * Whether an animated WebP should go out as a GIF, given what
     * [candidates] settled on for it ([chosen], null when the field takes
     * none of them) and whether the field takes GIFs.
     *
     * Only [WHATSAPP_STICKER] is a promise that the animation will play: it
     * is WhatsApp's own type and WhatsApp animates what arrives under it. A
     * field that merely lists `image/webp` promises nothing of the kind.
     * Messenger and Telegram both list it and both draw an animated WebP as a
     * single frame, and nothing a field advertises tells those apart from one
     * that would play it. A GIF plays in all of them, so that is what an
     * animated sticker becomes anywhere the WhatsApp type is not on offer.
     */
    fun animatedGoesAsGif(chosen: String?, fieldTakesGif: Boolean): Boolean =
        fieldTakesGif && chosen != WHATSAPP_STICKER

    /** File extension to store [mimeType] under. */
    fun extension(mimeType: String): String = when (mimeType) {
        GIF -> "gif"
        WEBP, WHATSAPP_STICKER -> "webp"
        PNG -> "png"
        else -> "jpg"
    }
}
