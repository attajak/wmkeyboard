package com.wasimaster.wmkeyboard.ime.ui

import com.wasimaster.wmkeyboard.core.stickers.StickerPack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Which pack the sticker panel's add chip opens (#281). */
class StickerAddTargetTest {

    private val cats = StickerPack(id = "p1", name = "Cats")
    private val dogs = StickerPack(id = "p2", name = "Dogs")

    @Test
    fun `the pack the grid is filtered to wins`() {
        assertEquals("p2", stickerAddTarget(listOf(cats, dogs), selected = "p2"))
    }

    @Test
    fun `a lone pack needs no choosing`() {
        assertEquals("p1", stickerAddTarget(listOf(cats), selected = null))
    }

    @Test
    fun `all with several packs asks`() {
        assertNull(stickerAddTarget(listOf(cats, dogs), selected = null))
    }

    @Test
    fun `a filter naming a deleted pack falls back like no filter`() {
        assertEquals("p1", stickerAddTarget(listOf(cats), selected = "gone"))
        assertNull(stickerAddTarget(listOf(cats, dogs), selected = "gone"))
    }
}
