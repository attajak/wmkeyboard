package com.wasimaster.wmkeyboard.core.stickers

import com.wasimaster.wmkeyboard.content.R
import com.wasimaster.wmkeyboard.core.content.ContentText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Stands in for what the screen resolves out of
 * `R.string.core_content_sticker_pack_imported_label` before it starts the
 * import. The reader takes the finished words because it runs with no context
 * of its own — including here, where there is no Android to resolve anything.
 */
private const val FALLBACK_NAME = "Imported stickers"

class StickerPackFileTest {

    @get:Rule
    val temp = TemporaryFolder()

    /** Stand-in for [StickerImage], which needs a device to decode anything. */
    private val passthrough: (ByteArray) -> ProcessedSticker? = { bytes ->
        ProcessedSticker(bytes, "image/webp", animated = false, aspectRatio = 1f)
    }

    private fun store(dir: String = "stickers") =
        StickerPackStore(File(temp.root, dir).apply { mkdirs() })

    private fun seed(store: StickerPackStore, name: String, count: Int): StickerPack {
        val pack = store.createPack(name)!!
        repeat(count) { index ->
            store.addSticker(
                pack.id,
                ProcessedSticker(
                    byteArrayOf(index.toByte(), 7, 7),
                    "image/webp",
                    animated = false,
                    aspectRatio = 1f,
                ),
                name = "sticker $index",
            )
        }
        return store.pack(pack.id)!!
    }

    private fun export(store: StickerPackStore, pack: StickerPack): ByteArray {
        val out = ByteArrayOutputStream()
        StickerPackFile.write(out, pack, appVersion = 7, appVersionName = "1.2.3") {
            store.fileFor(pack.id, it)
        }
        return out.toByteArray()
    }

    @Test
    fun `round-trips a pack`() {
        val source = store("source")
        val pack = seed(source, "Cats", 3)
        val bytes = export(source, pack)

        val target = store("target")
        val result = StickerPackFile.import(
            ByteArrayInputStream(bytes),
            target,
            FALLBACK_NAME,
            normalize = passthrough,
        )

        assertTrue(result is StickerImportResult.Imported)
        val imported = (result as StickerImportResult.Imported).pack
        assertEquals("Cats", imported.name)
        assertEquals(3, imported.stickers.size)
        assertEquals(listOf("sticker 0", "sticker 1", "sticker 2"), imported.stickers.map { it.name })
        for (sticker in imported.stickers) {
            assertTrue(target.fileFor(imported.id, sticker)!!.isFile)
        }
        assertTrue(result.repairs.isEmpty())
    }

    @Test
    fun `import regenerates ids so a re-import cannot collide`() {
        val source = store("source")
        val pack = seed(source, "Cats", 2)
        val bytes = export(source, pack)

        val target = store("target")
        val first = StickerPackFile.import(
            ByteArrayInputStream(bytes),
            target,
            FALLBACK_NAME,
            normalize = passthrough,
        )
        val second = StickerPackFile.import(
            ByteArrayInputStream(bytes),
            target,
            FALLBACK_NAME,
            normalize = passthrough,
        )

        val a = (first as StickerImportResult.Imported).pack
        val b = (second as StickerImportResult.Imported).pack
        assertFalse(a.id == b.id)
        assertEquals("Cats", a.name)
        assertEquals("Cats (2)", b.name)
        assertTrue(a.stickers.map { it.id }.intersect(b.stickers.map { it.id }.toSet()).isEmpty())
        assertEquals(2, target.packs().size)
    }

    @Test
    fun `refuses a file that is not a sticker pack`() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("pack.json"))
            zip.write("""{"format":"wmkeyboard-layout","version":1}""".toByteArray())
            zip.closeEntry()
        }

        val target = store()
        assertEquals(
            StickerImportResult.NotAStickerPack,
            StickerPackFile.import(
                ByteArrayInputStream(out.toByteArray()),
                target,
                FALLBACK_NAME,
                normalize = passthrough,
            ),
        )
        assertTrue(target.isEmpty())
    }

    @Test
    fun `refuses a zip with no manifest`() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("stickers/a.webp"))
            zip.write(byteArrayOf(1, 2, 3))
            zip.closeEntry()
        }

        assertEquals(
            StickerImportResult.NotAStickerPack,
            StickerPackFile.import(
                ByteArrayInputStream(out.toByteArray()),
                store(),
                FALLBACK_NAME,
                normalize = passthrough,
            ),
        )
    }

    @Test
    fun `refuses something that is not a zip at all`() {
        assertEquals(
            StickerImportResult.NotAStickerPack,
            StickerPackFile.import(
                ByteArrayInputStream("just some text".toByteArray()),
                store(),
                FALLBACK_NAME,
                normalize = passthrough,
            ),
        )
    }

    @Test
    fun `a traversing entry name cannot escape the pack directory`() {
        val manifest = """
            {"format":"wmkeyboard-stickers","version":1,"pack":{"id":"p","name":"Evil","stickers":[
              {"id":"a","fileName":"../../evil.webp","mime":"image/webp"},
              {"id":"b","fileName":"ok.webp","mime":"image/webp"}
            ]}}
        """.trimIndent()
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("pack.json"))
            zip.write(manifest.toByteArray())
            zip.closeEntry()
            // Both a traversing entry name and a traversing manifest name.
            zip.putNextEntry(ZipEntry("stickers/../../../pwned.webp"))
            zip.write(byteArrayOf(6, 6, 6))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("stickers/ok.webp"))
            zip.write(byteArrayOf(1, 2, 3))
            zip.closeEntry()
        }

        val target = store()
        val result = StickerPackFile.import(
            ByteArrayInputStream(out.toByteArray()),
            target,
            FALLBACK_NAME,
            normalize = passthrough,
        )

        val imported = (result as StickerImportResult.Imported).pack
        assertEquals(1, imported.stickers.size)
        assertEquals(1, result.repairs.size)
        assertFalse(File(temp.root, "evil.webp").exists())
        assertFalse(File(temp.root, "pwned.webp").exists())
        assertFalse(File(temp.root.parentFile, "pwned.webp").exists())
        // Every written file sits inside the pack's own directory.
        val dir = target.packDir(imported.id)!!
        assertEquals(setOf(imported.stickers.single().fileName), dir.list()!!.toSet())
    }

    @Test
    fun `a missing image is dropped and reported`() {
        // The writer skips stickers whose file is gone, so manifest and
        // archive always agree — hand-build the mismatch instead.
        val manifest = """
            {"format":"wmkeyboard-stickers","version":1,"pack":{"id":"p","name":"Cats","stickers":[
              {"id":"a","fileName":"a.webp","mime":"image/webp","name":"here"},
              {"id":"b","fileName":"b.webp","mime":"image/webp","name":"gone"}
            ]}}
        """.trimIndent()
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("pack.json"))
            zip.write(manifest.toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("stickers/a.webp"))
            zip.write(byteArrayOf(1))
            zip.closeEntry()
        }

        val result = StickerPackFile.import(
            ByteArrayInputStream(out.toByteArray()),
            store("target"),
            FALLBACK_NAME,
            normalize = passthrough,
        ) as StickerImportResult.Imported

        assertEquals(listOf("here"), result.pack.stickers.map { it.name })
        // Checked by the resource the line names plus the sticker it names in
        // its argument — the value that fills %1$s. The sticker name is data,
        // so this still says "the message names the sticker" the way the old
        // `contains("gone")` did, and it no longer breaks on a rewording.
        assertEquals(
            ContentText(R.string.core_content_sticker_repair_image_missing, args = listOf("gone")),
            result.repairs.single(),
        )
    }

    @Test
    fun `an image the normalizer rejects is dropped`() {
        val source = store("source")
        val pack = seed(source, "Cats", 2)
        val bytes = export(source, pack)

        val result = StickerPackFile.import(
            ByteArrayInputStream(bytes),
            store("target"),
            FALLBACK_NAME,
            normalize = { raw -> if (raw[0].toInt() == 0) null else passthrough(raw) },
        ) as StickerImportResult.Imported

        assertEquals(listOf("sticker 1"), result.pack.stickers.map { it.name })
        assertEquals(
            listOf(
                ContentText(
                    R.string.core_content_sticker_repair_image_unreadable,
                    args = listOf("sticker 0"),
                ),
            ),
            result.repairs,
        )
    }

    @Test
    fun `export skips stickers whose file went missing`() {
        val source = store("source")
        val pack = seed(source, "Cats", 2)
        source.fileFor(pack.id, pack.stickers.first())!!.delete()

        val result = StickerPackFile.import(
            ByteArrayInputStream(export(source, pack)),
            store("target"),
            FALLBACK_NAME,
            normalize = passthrough,
        ) as StickerImportResult.Imported

        assertEquals(listOf("sticker 1"), result.pack.stickers.map { it.name })
        assertTrue(result.repairs.isEmpty())
    }

    @Test
    fun `import leaves no staging directory behind`() {
        val source = store("source")
        val pack = seed(source, "Cats", 2)
        val target = store("target")

        StickerPackFile.import(
            ByteArrayInputStream(export(source, pack)),
            target,
            FALLBACK_NAME,
            normalize = passthrough,
        )

        val leftovers = File(temp.root, "target").list()!!.filter { it.startsWith(".") }
        assertTrue(leftovers.toString(), leftovers.isEmpty())
    }

    @Test
    fun `a normalizer that throws leaves nothing behind`() {
        val source = store("source")
        val pack = seed(source, "Cats", 2)
        val target = store("target")

        runCatching {
            StickerPackFile.import(
                ByteArrayInputStream(export(source, pack)),
                target,
                FALLBACK_NAME,
                normalize = { error("boom") },
            )
        }

        assertTrue(target.isEmpty())
        // No pack directory, no staging directory — and no manifest either,
        // since nothing was ever registered to write one.
        assertEquals(emptyList<String>(), File(temp.root, "target").list()!!.sorted())
    }

    /**
     * The shape a hand-written pack actually arrives in — which is how the
     * addon repository's own sticker pack was written, and why it installed as
     * an empty pack until the reader learned to read it.
     */
    @Test
    fun `reads a hand-written manifest that puts stickers beside pack`() {
        val manifest = """
            {"format":"wmkeyboard-stickers","version":1,
             "pack":{"id":"undraw","name":"unDraw"},
             "stickers":[
               {"id":"airplane","name":"Airplane","file":"stickers/airplane.png"},
               {"id":"barbecue","name":"Barbecue","file":"stickers/barbecue.png"}
             ]}
        """.trimIndent()
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("pack.json"))
            zip.write(manifest.toByteArray())
            zip.closeEntry()
            for (name in listOf("airplane", "barbecue")) {
                zip.putNextEntry(ZipEntry("stickers/$name.png"))
                zip.write(byteArrayOf(1, 2, 3))
                zip.closeEntry()
            }
        }

        val result = StickerPackFile.import(
            ByteArrayInputStream(out.toByteArray()),
            store("target"),
            FALLBACK_NAME,
            normalize = passthrough,
        ) as StickerImportResult.Imported

        assertEquals("unDraw", result.pack.name)
        assertEquals(listOf("Airplane", "Barbecue"), result.pack.stickers.map { it.name })
        assertTrue(result.repairs.isEmpty())
    }

    /**
     * Keywords are written under `emojis`, the key every older build and every
     * pack already out there uses, so a pack exported now still carries its
     * keywords into an older build. A hand-written manifest may say `keywords`
     * instead, or both.
     */
    @Test
    fun `keywords travel under the old emojis key and a keywords key is read too`() {
        val source = store("source")
        val pack = source.createPack("Cats")!!
        source.addSticker(pack.id, passthrough(byteArrayOf(1))!!, name = "grumpy", keywords = listOf("cat", "😾"))
        val bytes = export(source, source.pack(pack.id)!!)
        val manifestText = java.util.zip.ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            generateSequence { zip.nextEntry }.first { it.name == "pack.json" }
            zip.readBytes().decodeToString()
        }
        assertTrue(manifestText.contains("\"emojis\""))
        assertFalse(manifestText.contains("\"keywords\""))
        val imported = StickerPackFile.import(
            ByteArrayInputStream(bytes),
            store("target"),
            FALLBACK_NAME,
            normalize = passthrough,
        ) as StickerImportResult.Imported
        assertEquals(listOf("cat", "😾"), imported.pack.stickers.single().keywords)

        val manifest = """
            {"format":"wmkeyboard-stickers","version":1,
             "pack":{"id":"p","name":"Hand"},
             "stickers":[{"id":"a","name":"Wave","file":"a.png","emojis":["👋"],"keywords":["hello","👋"]}]}
        """.trimIndent()
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("pack.json"))
            zip.write(manifest.toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("a.png"))
            zip.write(byteArrayOf(1, 2, 3))
            zip.closeEntry()
        }
        val hand = StickerPackFile.import(
            ByteArrayInputStream(out.toByteArray()),
            store("hand"),
            FALLBACK_NAME,
            normalize = passthrough,
        ) as StickerImportResult.Imported
        assertEquals(listOf("👋", "hello"), hand.pack.stickers.single().keywords)
    }

    /**
     * The reader cannot name a nameless pack itself: it holds no `Context`, so
     * the caller hands it the already-resolved words.
     */
    @Test
    fun `a pack whose manifest names it nothing takes the caller's name`() {
        val manifest = """
            {"format":"wmkeyboard-stickers","version":1,"pack":{"id":"p"},
             "stickers":[{"id":"a","file":"a.png","name":"cat"}]}
        """.trimIndent()
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("pack.json"))
            zip.write(manifest.toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("stickers/a.png"))
            zip.write(byteArrayOf(9))
            zip.closeEntry()
        }

        val result = StickerPackFile.import(
            ByteArrayInputStream(out.toByteArray()),
            store("target"),
            FALLBACK_NAME,
            normalize = passthrough,
        ) as StickerImportResult.Imported

        assertEquals(FALLBACK_NAME, result.pack.name)
    }

    @Test
    fun `a bare file name finds an image filed under stickers`() {
        val manifest = """
            {"format":"wmkeyboard-stickers","version":1,"pack":{"id":"p","name":"Cats"},
             "stickers":[{"id":"a","file":"a.png","name":"cat"}]}
        """.trimIndent()
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("pack.json"))
            zip.write(manifest.toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("stickers/a.png"))
            zip.write(byteArrayOf(9))
            zip.closeEntry()
        }

        val result = StickerPackFile.import(
            ByteArrayInputStream(out.toByteArray()),
            store("target"),
            FALLBACK_NAME,
            normalize = passthrough,
        ) as StickerImportResult.Imported

        assertEquals(listOf("cat"), result.pack.stickers.map { it.name })
    }

    @Test
    fun `a pack whose images all fail is refused rather than imported empty`() {
        val manifest = """
            {"format":"wmkeyboard-stickers","version":1,"pack":{"id":"p","name":"Cats","stickers":[
              {"id":"a","fileName":"a.webp","mime":"image/webp","name":"gone"}
            ]}}
        """.trimIndent()
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("pack.json"))
            zip.write(manifest.toByteArray())
            zip.closeEntry()
        }

        val target = store("target")
        val result = StickerPackFile.import(
            ByteArrayInputStream(out.toByteArray()),
            target,
            FALLBACK_NAME,
            normalize = passthrough,
        )

        assertTrue(result.toString(), result is StickerImportResult.NoStickers)
        // Same trade as above: the resource id plus the sticker name that fills
        // its placeholder, rather than the English the line happens to be in.
        assertEquals(
            ContentText(R.string.core_content_sticker_repair_image_missing, args = listOf("gone")),
            (result as StickerImportResult.NoStickers).repairs.single(),
        )
        // Nothing registered, and no orphan directory left behind.
        assertTrue(target.isEmpty())
        assertEquals(emptyList<String>(), File(temp.root, "target").list()!!.sorted())
    }

    @Test
    fun `a manifest listing no stickers is refused`() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("pack.json"))
            zip.write("""{"format":"wmkeyboard-stickers","version":1,"pack":{"id":"p","name":"Empty"}}""".toByteArray())
            zip.closeEntry()
        }

        val target = store("target")
        val result = StickerPackFile.import(
            ByteArrayInputStream(out.toByteArray()),
            target,
            FALLBACK_NAME,
            normalize = passthrough,
        )

        assertTrue(result is StickerImportResult.NoStickers)
        assertTrue(target.isEmpty())
    }

    @Test
    fun `file name is sanitised`() {
        assertEquals(
            "Cats and dogs.wmstickers",
            StickerPackFile.fileName(StickerPack(id = "p", name = "Cats /and: dogs")),
        )
        assertEquals(
            "stickers.wmstickers",
            StickerPackFile.fileName(StickerPack(id = "p", name = "///")),
        )
    }
}
