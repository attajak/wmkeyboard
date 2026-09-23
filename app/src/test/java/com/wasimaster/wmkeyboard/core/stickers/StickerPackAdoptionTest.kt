package com.wasimaster.wmkeyboard.core.stickers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The step a `.wmstickers` file and a Signal pack share. [StickerPackFileTest]
 * covers it through the archive reader; this covers what only a caller without
 * an archive sees: where the pack says it came from, progress, and a source
 * whose pictures never arrived.
 */
class StickerPackAdoptionTest {

    @get:Rule
    val temp = TemporaryFolder()

    // StickerImage needs android.graphics; a pass-through keeps this on the JVM.
    private val passthrough: (ByteArray) -> ProcessedSticker? = { bytes ->
        if (bytes.isEmpty()) null else ProcessedSticker(bytes, "image/webp", animated = false, aspectRatio = 1f)
    }

    private fun incoming(label: String, bytes: ByteArray?) =
        StickerPackAdoption.Incoming(label = label, emojis = listOf("😀"), read = { bytes })

    @Test
    fun `the pack remembers where it came from and what each sticker was tagged`() {
        val store = StickerPackStore(temp.root)
        val result = StickerPackAdoption.adopt(
            store = store,
            name = "Zozo",
            incoming = listOf(incoming("a", byteArrayOf(1)), incoming("b", byteArrayOf(2))),
            source = StickerPack.signalSource("fb535407d2f6497ec074df8b9c51dd1d"),
            normalize = passthrough,
        )
        val pack = (result as StickerImportResult.Imported).pack
        assertEquals("signal:fb535407d2f6497ec074df8b9c51dd1d", pack.source)
        assertEquals(listOf("😀"), pack.stickers.first().keywords)
        // Survives the store being read back from disk.
        assertEquals(pack.source, StickerPackStore(temp.root).pack(pack.id)?.source)
    }

    @Test
    fun `progress counts every sticker and ends on the total`() {
        val seen = ArrayList<Pair<Int, Int>>()
        StickerPackAdoption.adopt(
            store = StickerPackStore(temp.root),
            name = "P",
            incoming = List(3) { incoming("s$it", byteArrayOf(it.toByte(), 1)) },
            normalize = passthrough,
            onProgress = { done, total -> seen += done to total },
        )
        assertEquals(listOf(0 to 3, 1 to 3, 2 to 3, 3 to 3), seen)
    }

    @Test
    fun `pictures that never arrived are dropped by name and the rest are kept`() {
        val result = StickerPackAdoption.adopt(
            store = StickerPackStore(temp.root),
            name = "P",
            incoming = listOf(incoming("first", null), incoming("second", byteArrayOf(9))),
            normalize = passthrough,
        )
        result as StickerImportResult.Imported
        assertEquals(1, result.pack.stickers.size)
        assertEquals(listOf<Any>("first"), result.repairs.single().args)
    }

    @Test
    fun `a source with no usable picture at all leaves no pack and no directory behind`() {
        val store = StickerPackStore(temp.root)
        val result = StickerPackAdoption.adopt(
            store = store,
            name = "P",
            incoming = listOf(incoming("only", null)),
            normalize = passthrough,
        )
        assertTrue(result is StickerImportResult.NoStickers)
        assertTrue(store.packs().isEmpty())
        assertTrue(temp.root.listFiles().orEmpty().none { it.isDirectory && it.name.startsWith("pack_") })
    }
}
