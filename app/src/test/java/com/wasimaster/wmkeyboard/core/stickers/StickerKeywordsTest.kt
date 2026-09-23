package com.wasimaster.wmkeyboard.core.stickers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StickerKeywordsTest {

    @Test
    fun `commas separate keywords so a keyword can be a phrase`() {
        assertEquals(listOf("cat", "good night"), StickerKeywords.parse("cat, good night"))
    }

    @Test
    fun `emoji need no separator`() {
        assertEquals(listOf("😂", "🐱"), StickerKeywords.parse("😂🐱"))
        assertEquals(listOf("cat", "😂", "🐱"), StickerKeywords.parse("cat, 😂🐱"))
    }

    @Test
    fun `an emoji inside a phrase is lifted out on its own`() {
        assertEquals(listOf("😂", "so funny"), StickerKeywords.parse("so 😂 funny"))
    }

    @Test
    fun `joined emoji, skin tones and flags stay whole`() {
        assertEquals(
            listOf("👨‍👩‍👧", "👍🏽", "🇧🇩", "🇺🇸", "❤️"),
            StickerKeywords.parse("👨‍👩‍👧👍🏽🇧🇩🇺🇸❤️"),
        )
    }

    @Test
    fun `blanks and repeats are dropped, the first spelling kept`() {
        assertEquals(listOf("Cat", "dog"), StickerKeywords.parse(" Cat ,, cat , dog,  \n"))
        // 👍 and 👍🏽 are the same trigger, so they are one keyword.
        assertEquals(listOf("👍"), StickerKeywords.parse("👍👍🏽"))
    }

    @Test
    fun `other scripts' commas separate too`() {
        assertEquals(listOf("قطة", "猫", "বিড়াল"), StickerKeywords.parse("قطة، 猫、বিড়াল"))
    }

    @Test
    fun `format and parse round-trip`() {
        val keywords = listOf("cat", "good night", "😂")
        assertEquals(keywords, StickerKeywords.parse(StickerKeywords.format(keywords)))
    }

    @Test
    fun `the search line holds the title and every keyword`() {
        val sticker = CustomSticker(
            id = "s1",
            fileName = "s1.webp",
            mime = "image/webp",
            name = "grumpy",
            keywords = listOf("😾", "cat"),
        )
        assertEquals("grumpy 😾 cat", StickerKeywords.haystack(sticker))
    }

    @Test
    fun `emoji-ness is about letters and digits`() {
        assertTrue(StickerKeywords.isEmoji("😂"))
        assertTrue(StickerKeywords.isEmoji("❤️"))
        assertFalse(StickerKeywords.isEmoji("cat"))
        assertFalse(StickerKeywords.isEmoji("😂 cat"))
        assertFalse(StickerKeywords.isEmoji(" "))
    }
}
