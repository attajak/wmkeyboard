package com.wasimaster.wmkeyboard.core.stickers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StickerTriggerIndexTest {

    private fun sticker(id: String, title: String = "", vararg keywords: String) = CustomSticker(
        id = id,
        fileName = "$id.webp",
        mime = "image/webp",
        name = title,
        keywords = keywords.toList(),
    )

    private fun index(vararg stickers: CustomSticker, packId: String = "p") =
        StickerTriggerIndex.build(listOf(StickerPack(id = packId, name = "Pack", stickers = stickers.toList())))

    private fun ids(match: StickerTriggerIndex.Match?) = match?.hits?.map { it.sticker.id }.orEmpty()

    @Test
    fun `a title offers its sticker only when typed in full`() {
        val index = index(sticker("a", "grumpy cat"))
        assertEquals(listOf("a"), ids(index.match("look, grumpy cat")))
        assertNull(index.match("look, grumpy"))
        assertNull(index.match("grumpy ca"))
    }

    @Test
    fun `a keyword can be any shorter handle`() {
        val index = index(sticker("a", "grumpy cat", "grr", "cat"))
        assertEquals(listOf("a"), ids(index.match("grr")))
        assertEquals(listOf("a"), ids(index.match("my cat")))
    }

    @Test
    fun `a word has to start where a word starts`() {
        val index = index(sticker("a", "", "cat"))
        assertNull(index.match("concat"))
        assertNull(index.match("cats"))
        assertEquals(listOf("a"), ids(index.match("(cat")))
    }

    @Test
    fun `case is ignored and the span covers the trigger and the space after it`() {
        val index = index(sticker("a", "Cat"))
        val match = index.match("hey CAT  ")!!
        assertEquals("CAT", match.trigger)
        assertEquals(5, match.hits.single().span)
    }

    @Test
    fun `an emoji needs no word boundary`() {
        val index = index(sticker("a", "", "😂"))
        assertEquals(listOf("a"), ids(index.match("lol😂")))
        assertEquals(2, index.match("lol😂")!!.hits.single().span)
    }

    @Test
    fun `skin tones and presentation selectors are ignored both ways`() {
        val thumbs = index(sticker("a", "", "👍"))
        assertEquals(listOf("a"), ids(thumbs.match("ok 👍🏽")))
        // The span still takes the tone back out with the thumb.
        assertEquals(4, thumbs.match("ok 👍🏽")!!.hits.single().span)
        val heart = index(sticker("b", "", "❤"))
        assertEquals(listOf("b"), ids(heart.match("love ❤️")))
        val heartSelector = index(sticker("c", "", "❤️"))
        assertEquals(listOf("c"), ids(heartSelector.match("love ❤")))
    }

    @Test
    fun `the tail of a joined emoji is not that emoji`() {
        val index = index(sticker("a", "", "🔥"))
        assertNull(index.match("❤️‍🔥"))
        assertEquals(listOf("a"), ids(index.match("🔥")))
    }

    @Test
    fun `the longest trigger comes first and each hit keeps its own span`() {
        val index = index(
            sticker("short", "", "birthday"),
            sticker("long", "happy birthday"),
        )
        val match = index.match("well, happy birthday")!!
        assertEquals("happy birthday", match.trigger)
        assertEquals(listOf("long", "short"), ids(match))
        assertEquals(listOf(14, 8), match.hits.map { it.span })
    }

    @Test
    fun `within one trigger a title beats a keyword`() {
        val index = index(
            sticker("tagged", "", "cat"),
            sticker("titled", "cat"),
        )
        assertEquals(listOf("titled", "tagged"), ids(index.match("cat")))
    }

    @Test
    fun `a sticker is offered once even when several triggers match`() {
        val index = index(sticker("a", "cat", "cat", "black cat"))
        assertEquals(listOf("a"), ids(index.match("black cat")))
    }

    @Test
    fun `the emoji panel only asks about emoji`() {
        val index = index(sticker("word", "cat"), sticker("emoji", "", "🐱"))
        assertNull(index.match("cat", emojiOnly = true))
        assertEquals(listOf("emoji"), ids(index.match("cat 🐱", emojiOnly = true)))
    }

    @Test
    fun `a mark inside a word is part of the word`() {
        // The "কা" that ends "বাকা" follows a vowel sign, which belongs to the
        // word, so it is the end of that word and not a word of its own.
        val index = index(sticker("a", "", "কা"))
        assertNull(index.match("বাকা"))
        assertEquals(listOf("a"), ids(index.match("এই কা")))
    }

    @Test
    fun `no packs, no words, or nothing typed offers nothing`() {
        assertTrue(StickerTriggerIndex.build(emptyList()).isEmpty)
        assertTrue(index(sticker("a")).isEmpty)
        assertNull(index(sticker("a", "cat")).match(""))
        assertNull(index(sticker("a", "cat")).match("   "))
    }

    @Test
    fun `a trigger longer than the lookbehind is not indexed`() {
        val long = "x".repeat(StickerTriggerIndex.MAX_TRIGGER_LENGTH + 1)
        assertTrue(index(sticker("a", long)).isEmpty)
    }

    @Test
    fun `hits are capped`() {
        val many = (1..40).map { sticker("s$it", "", "cat") }.toTypedArray()
        assertEquals(StickerTriggerIndex.MAX_HITS, index(*many).match("cat")!!.hits.size)
    }
}
