package com.wasimaster.wmkeyboard.core.icons

import com.wasimaster.wmkeyboard.icons.R
import com.wasimaster.wmkeyboard.core.settings.ToolbarTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class IconPackFileTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var store: IconPackStore

    private val svg = """<svg viewBox="0 0 24 24"><path d="M0 0h24v24H0Z"/></svg>"""

    @Before
    fun setUp() {
        store = IconPackStore(temp.newFolder("iconpacks"))
    }

    /** Builds a `.wmicons` archive by hand, entry names and all. */
    private fun archive(
        manifest: String?,
        entries: Map<String, String>,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            if (manifest != null) {
                zip.putNextEntry(ZipEntry(IconPackFile.MANIFEST))
                zip.write(manifest.toByteArray())
                zip.closeEntry()
            }
            for ((name, body) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(body.toByteArray())
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun manifest(
        format: String = IconPackFile.FORMAT,
        name: String = "Rounded",
        slots: List<String> = emptyList(),
    ): String = """
        {"format":"$format","version":1,"appVersion":1,"appVersionName":"1.0",
         "pack":{"id":"rounded","name":"$name","author":"Someone","version":"2.0",
                 "slots":[${slots.joinToString(",") { "\"$it\"" }}]}}
    """.trimIndent()

    /**
     * [IconPackFile.import] has no context, so the screen resolves
     * `core_icons_pack_imported_label` and hands it in. Every archive here
     * names itself, so the default only has to be a name, not the real one.
     */
    private fun importPack(bytes: ByteArray): IconImportResult =
        IconPackFile.import(ByteArrayInputStream(bytes), store, defaultName = "Imported icons", now = 1_000L)

    // ---- round trip ----

    @Test
    fun `write then import preserves the icons`() {
        val pack = store.createPack("Source")!!
        assertTrue(store.setIcon(pack.id, IconSlots.KEY_ENTER_SEND, svg))
        assertTrue(store.setIcon(pack.id, IconSlots.forTool(
            com.wasimaster.wmkeyboard.core.settings.ToolbarTool.CLIPBOARD), svg))

        val out = ByteArrayOutputStream()
        val saved = store.pack(pack.id)!!
        IconPackFile.write(out, saved, appVersion = 1, appVersionName = "1.0") {
            store.fileFor(saved.id, it)
        }

        val result = importPack(out.toByteArray()) as IconImportResult.Imported
        assertEquals(2, result.pack.slots.size)
        assertTrue(IconSlots.KEY_ENTER_SEND in result.pack.slots)
        // A fresh id, so importing the same file twice cannot collide.
        assertTrue(result.pack.id != saved.id)
        assertEquals(svg, store.fileFor(result.pack.id, IconSlots.KEY_ENTER_SEND)!!.readText())
    }

    @Test
    fun `metadata survives the round trip`() {
        val bytes = archive(
            manifest(slots = listOf(IconSlots.KEY_SHIFT)),
            mapOf("icons/${IconSlots.KEY_SHIFT}.svg" to svg),
        )
        val result = importPack(bytes) as IconImportResult.Imported
        assertEquals("Rounded", result.pack.name)
        assertEquals("Someone", result.pack.author)
        assertEquals("2.0", result.pack.version)
    }

    /** A hand-assembled pack whose manifest forgot to list its files still works. */
    @Test
    fun `entries not listed in the manifest are still imported`() {
        val bytes = archive(
            manifest(slots = emptyList()),
            mapOf("icons/${IconSlots.KEY_BACKSPACE}.svg" to svg),
        )
        val result = importPack(bytes) as IconImportResult.Imported
        assertEquals(listOf(IconSlots.KEY_BACKSPACE), result.pack.slots)
    }

    @Test
    fun `an icon at the archive root is accepted`() {
        val bytes = archive(manifest(), mapOf("${IconSlots.KEY_GLOBE}.svg" to svg))
        val result = importPack(bytes) as IconImportResult.Imported
        assertEquals(listOf(IconSlots.KEY_GLOBE), result.pack.slots)
    }

    @Test
    fun `manifest order is preserved`() {
        val slots = listOf(IconSlots.KEY_GLOBE, IconSlots.KEY_SHIFT, IconSlots.KEY_BACKSPACE)
        val bytes = archive(
            manifest(slots = slots),
            // Deliberately the other way round in the archive.
            slots.reversed().associate { "icons/$it.svg" to svg },
        )
        val result = importPack(bytes) as IconImportResult.Imported
        assertEquals(slots, result.pack.slots)
    }

    // ---- rejection and repair ----

    @Test
    fun `a wrong format tag is refused`() {
        val bytes = archive(
            manifest(format = "wmkeyboard-stickers", slots = listOf(IconSlots.KEY_SHIFT)),
            mapOf("icons/${IconSlots.KEY_SHIFT}.svg" to svg),
        )
        assertEquals(IconImportResult.NotAnIconPack, importPack(bytes))
    }

    @Test
    fun `no manifest is refused`() {
        val bytes = archive(null, mapOf("icons/${IconSlots.KEY_SHIFT}.svg" to svg))
        assertEquals(IconImportResult.NotAnIconPack, importPack(bytes))
    }

    @Test
    fun `a pack with nothing usable fails rather than installing empty`() {
        val bytes = archive(manifest(), emptyMap())
        assertEquals(IconImportResult.Failed, importPack(bytes))
    }

    @Test
    fun `an unknown slot is dropped and reported`() {
        val bytes = archive(
            manifest(),
            mapOf(
                "icons/tool.timetravel.svg" to svg,
                "icons/${IconSlots.KEY_SHIFT}.svg" to svg,
            ),
        )
        val result = importPack(bytes) as IconImportResult.Imported
        assertEquals(listOf(IconSlots.KEY_SHIFT), result.pack.slots)
        // The note is matched by the resource it names and the file name it
        // carries. The file name is data, not wording, so it survives a
        // rewrite of the sentence around it.
        assertTrue(
            "repairs were ${result.repairs}",
            IconText(
                pluralsRes = R.plurals.core_icons_repair_files_ignored,
                quantity = 1,
                args = listOf(1, "tool.timetravel.svg"),
            ) in result.repairs,
        )
    }

    @Test
    fun `an unparseable svg is dropped and reported`() {
        val bytes = archive(
            manifest(),
            mapOf(
                "icons/${IconSlots.KEY_SHIFT}.svg" to "not xml",
                "icons/${IconSlots.KEY_GLOBE}.svg" to svg,
            ),
        )
        val result = importPack(bytes) as IconImportResult.Imported
        assertEquals(listOf(IconSlots.KEY_GLOBE), result.pack.slots)
        assertTrue(
            "repairs were ${result.repairs}",
            IconText(
                R.string.core_icons_repair_svg_unreadable,
                args = listOf(IconSlots.KEY_SHIFT),
            ) in result.repairs,
        )
    }

    // ---- security ----

    /**
     * Entry names are matched against known slots and then discarded, so a
     * traversal attempt can only ever be dropped — never written.
     */
    @Test
    fun `path traversal entry names cannot escape the pack directory`() {
        val bytes = archive(
            manifest(),
            mapOf(
                "../../../../etc/${IconSlots.KEY_SHIFT}.svg" to svg,
                "icons/../../evil.svg" to svg,
                "icons/${IconSlots.KEY_GLOBE}.svg" to svg,
            ),
        )
        val result = importPack(bytes) as IconImportResult.Imported
        // The traversal name whose basename IS a known slot is still accepted —
        // but written under the slot's own derived name, inside the pack.
        val dir = File(temp.root, "iconpacks/${result.pack.id}")
        for (file in dir.listFiles().orEmpty()) {
            assertEquals(dir, file.parentFile)
            assertTrue(file.name.endsWith(".svg"))
            assertTrue(IconSlots.byId(file.name.removeSuffix(".svg")) != null)
        }
        assertFalse(File(temp.root, "evil.svg").exists())
        assertFalse(File(temp.root, "iconpacks/evil.svg").exists())
    }

    @Test
    fun `slotForEntry only accepts known slots`() {
        assertEquals(IconSlots.KEY_SHIFT, IconPackFile.slotForEntry("icons/${IconSlots.KEY_SHIFT}.svg"))
        assertEquals(IconSlots.KEY_SHIFT, IconPackFile.slotForEntry("../../${IconSlots.KEY_SHIFT}.SVG"))
        // Every raster extension too, since issue #504. The extension only
        // says where the slot id ends — what the file *is* comes from its
        // bytes, so a `.png` holding an SVG is still stored as an SVG.
        for (extension in listOf("png", "webp", "jpg", "gif")) {
            assertEquals(
                IconSlots.KEY_SHIFT,
                IconPackFile.slotForEntry("icons/${IconSlots.KEY_SHIFT}.$extension"),
            )
        }
        assertNull(IconPackFile.slotForEntry("icons/../../evil.svg"))
        assertNull(IconPackFile.slotForEntry("icons/unknown.svg"))
        assertNull(IconPackFile.slotForEntry("icons/unknown.png"))
        assertNull(IconPackFile.slotForEntry("icons/${IconSlots.KEY_SHIFT}.exe"))
        assertNull(IconPackFile.slotForEntry("pack.json"))
    }

    @Test
    fun `slotForEntry keeps a variant and refuses a malformed one`() {
        val gif = IconSlots.forTool(ToolbarTool.GIF)
        assertEquals("$gif@text", IconPackFile.slotForEntry("icons/$gif@text.svg"))
        assertEquals("$gif@text_small", IconPackFile.slotForEntry("icons/$gif@Text_Small.png"))
        // A variant of a slot this version does not know is as unknown as the slot.
        assertNull(IconPackFile.slotForEntry("icons/tool.nonexistent@text.svg"))
        // No dots, no second separator, nothing empty: the key becomes a file name.
        assertNull(IconPackFile.slotForEntry("icons/$gif@.svg"))
        assertNull(IconPackFile.slotForEntry("icons/$gif@a.b.svg"))
        assertNull(IconPackFile.slotForEntry("icons/$gif@a@b.svg"))
        assertNull(IconPackFile.slotForEntry("icons/$gif@1text.svg"))
    }

    @Test
    fun `an oversized icon is dropped`() {
        val huge = """<svg viewBox="0 0 24 24"><path d="${"M0 0h1v1H0Z".repeat(40_000)}"/></svg>"""
        assertTrue(huge.length > SvgParser.MAX_SOURCE_BYTES)
        val bytes = archive(
            manifest(),
            mapOf(
                "icons/${IconSlots.KEY_SHIFT}.svg" to huge,
                "icons/${IconSlots.KEY_GLOBE}.svg" to svg,
            ),
        )
        val result = importPack(bytes) as IconImportResult.Imported
        assertEquals(listOf(IconSlots.KEY_GLOBE), result.pack.slots)
    }

    @Test
    fun `a failed import leaves no half-written pack behind`() {
        val before = store.packs().size
        assertEquals(IconImportResult.Failed, importPack(archive(manifest(), emptyMap())))
        assertEquals(before, store.packs().size)
        // No orphan directories either: reload reconciles what is on disk.
        store.reload()
        assertEquals(before, store.packs().size)
    }

    @Test
    fun `too many packs is reported, not silently dropped`() {
        repeat(IconPackStore.MAX_PACKS) { i -> store.createPack("Pack $i", now = 1_000L + i) }
        val bytes = archive(manifest(), mapOf("icons/${IconSlots.KEY_SHIFT}.svg" to svg))
        assertEquals(IconImportResult.TooManyPacks, importPack(bytes))
    }

    // ---- file name ----

    @Test
    fun `exported file names are sanitised`() {
        // Separators and wildcards are stripped, not replaced — the same rule
        // StickerPackFile uses, so the two exports name files alike.
        assertEquals(
            "My icons.wmicons",
            IconPackFile.fileName(IconPack(id = "x", name = "My icons:*?")),
        )
        assertEquals(
            "Myicons.wmicons",
            IconPackFile.fileName(IconPack(id = "x", name = "My/icons")),
        )
        assertEquals("icons.wmicons", IconPackFile.fileName(IconPack(id = "x", name = "")))
    }
}
