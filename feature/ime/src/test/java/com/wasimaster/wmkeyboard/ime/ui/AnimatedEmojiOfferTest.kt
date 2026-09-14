package com.wasimaster.wmkeyboard.ime.ui

import com.wasimaster.wmkeyboard.core.emoji.AnimatedEmoji
import com.wasimaster.wmkeyboard.core.settings.DataSaverSettings
import com.wasimaster.wmkeyboard.core.settings.DataSaverStatus
import com.wasimaster.wmkeyboard.core.settings.MeteredPolicy
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the long-press popup draws its preview slot and its Send row. The Send
 * row used to wait for the preview unconditionally, so on a metered connection,
 * where data saving holds the preview back, the popup showed an empty box and
 * no way to send at all.
 */
class AnimatedEmojiOfferTest {

    private val grinning = "😆"

    private fun state(
        dataSaver: DataSaverStatus = DataSaverStatus(),
        file: File? = null,
        loading: Boolean = false,
        noPreview: Boolean = false,
    ) = KeyboardUiState(
        animatedEmoji = AnimatedEmoji.load("1f606\n".byteInputStream()),
        fieldContentMimeTypes = listOf("image/gif", "image/png"),
        dataSaver = dataSaver,
        animatedEmojiFile = file,
        animatedEmojiLoading = loading,
        animatedEmojiNoPreview = noPreview,
    )

    @Test
    fun `while the preview loads there is a slot and no Send`() {
        val offer = state(loading = true).animatedEmojiOffer(grinning)!!
        assertTrue(offer.showsPreview)
        assertFalse(offer.canSend)
    }

    @Test
    fun `a landed preview shows with Send under it`() {
        val offer = state(file = File("preview.webp")).animatedEmojiOffer(grinning)!!
        assertTrue(offer.showsPreview)
        assertTrue(offer.canSend)
    }

    @Test
    fun `a preview held by data saving still offers Send, without an empty slot`() {
        val asking = DataSaverStatus(active = true)
        val offer = state(dataSaver = asking, noPreview = true).animatedEmojiOffer(grinning)
        assertNotNull(offer)
        assertFalse(offer!!.showsPreview)
        assertTrue(offer.canSend)
    }

    @Test
    fun `a failed preview fetch still offers Send`() {
        val offer = state(noPreview = true).animatedEmojiOffer(grinning)!!
        assertFalse(offer.showsPreview)
        assertTrue(offer.canSend)
    }

    @Test
    fun `data saving set to block offers nothing`() {
        val blocking = DataSaverStatus(
            active = true,
            settings = DataSaverSettings(animatedEmoji = MeteredPolicy.BLOCK),
        )
        assertNull(state(dataSaver = blocking, noPreview = true).animatedEmojiOffer(grinning))
    }

    @Test
    fun `a field that takes no images offers nothing`() {
        val plain = state().copy(fieldContentMimeTypes = emptyList())
        assertNull(plain.animatedEmojiOffer(grinning))
    }
}
