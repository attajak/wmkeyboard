package com.wasimaster.wmkeyboard.core.media

import com.wasimaster.wmkeyboard.core.settings.MediaSendMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which type a sticker is offered under, and when an animated one gives up on
 * being a WebP.
 *
 * The second half is what device testing decided: WhatsApp played an animated
 * WebP sent under its sticker type, while Messenger and Telegram both accepted
 * `image/webp` and showed one frame. Nothing a field advertises tells those
 * two kinds of app apart, so the rule goes by the one type that is a promise.
 */
class MediaMimeTest {

    @Test
    fun `a WebP sent as a sticker offers WhatsApp's type first and the plain one second`() {
        assertEquals(
            listOf(MediaMime.WHATSAPP_STICKER, MediaMime.WEBP),
            MediaMime.candidates(MediaMime.WEBP, MediaSendMode.STICKER),
        )
        assertEquals(listOf(MediaMime.WEBP), MediaMime.candidates(MediaMime.WEBP, MediaSendMode.IMAGE))
        assertEquals(listOf(MediaMime.GIF), MediaMime.candidates(MediaMime.GIF, MediaSendMode.STICKER))
    }

    @Test
    fun `under WhatsApp's sticker type an animated sticker stays a WebP`() {
        assertFalse(MediaMime.animatedGoesAsGif(MediaMime.WHATSAPP_STICKER, fieldTakesGif = true))
    }

    @Test
    fun `a field that only lists plain WebP gets a GIF, because listing it is not playing it`() {
        assertTrue(MediaMime.animatedGoesAsGif(MediaMime.WEBP, fieldTakesGif = true))
    }

    @Test
    fun `a field that takes no WebP at all gets a GIF too`() {
        assertTrue(MediaMime.animatedGoesAsGif(null, fieldTakesGif = true))
    }

    @Test
    fun `with no GIF on offer there is nothing to change to`() {
        assertFalse(MediaMime.animatedGoesAsGif(MediaMime.WEBP, fieldTakesGif = false))
        assertFalse(MediaMime.animatedGoesAsGif(null, fieldTakesGif = false))
    }
}
