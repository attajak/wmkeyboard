package com.wasimaster.wmkeyboard.core.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Reading Rboard's `list.json`, in the shape the live file has. */
class RboardCatalogTest {

    private val index = """
        [
          {
            "url": "packs/3D_ThemePack.zip",
            "hash": "F513A3738D69C00E80F1249C008C5D514799C6B591F870BBB95E6732F905BEDA",
            "author": "Fakefams",
            "tags": ["Fixed", " "],
            "themes": ["3D_Black", "3D_White"],
            "size": 1474964,
            "date": 1633521138000,
            "name": "3D Theme Pack"
          },
          {
            "url": "packs/Animal Black.zip",
            "hash": "not-a-hash",
            "author": "Someone",
            "themes": [],
            "name": "Animal Black",
            "description": "Animals"
          },
          { "url": "../../etc/passwd", "name": "Climber" },
          { "url": "http://example.com/x.zip", "name": "Plain http" },
          { "name": "No url" },
          "not an object"
        ]
    """.trimIndent()

    @Test
    fun `packs are read and bad entries are skipped`() {
        val packs = RboardCatalog.parse(index)!!
        assertEquals(listOf("3D Theme Pack", "Animal Black"), packs.map { it.name })
        val first = packs[0]
        assertEquals(RboardCatalog.REPOSITORY + "packs/3D_ThemePack.zip", first.url)
        assertEquals("f513a3738d69c00e80f1249c008c5d514799c6b591f870bbb95e6732f905beda", first.sha256)
        assertEquals(1474964L, first.sizeBytes)
        assertEquals(listOf("3D_Black", "3D_White"), first.themes)
        assertEquals(listOf("Fixed"), first.tags)
    }

    @Test
    fun `a space in a pack path is encoded and a malformed hash is dropped`() {
        val animal = RboardCatalog.parse(index)!![1]
        assertEquals(RboardCatalog.REPOSITORY + "packs/Animal%20Black.zip", animal.url)
        assertNull(animal.sha256)
        assertNull(animal.sizeBytes)
        assertEquals("Animals", animal.description)
    }

    @Test
    fun `absolute https links pass through and others are refused`() {
        assertEquals(
            "https://raw.githubusercontent.com/a/b/main/p.zip",
            RboardCatalog.absoluteUrl("https://raw.githubusercontent.com/a/b/main/p.zip"),
        )
        assertNull(RboardCatalog.absoluteUrl("http://example.com/p.zip"))
        assertNull(RboardCatalog.absoluteUrl("/etc/passwd"))
        assertNull(RboardCatalog.absoluteUrl("packs/../x.zip"))
    }

    @Test
    fun `something that is not the index reads as null`() {
        assertNull(RboardCatalog.parse("{\"packs\": []}"))
        assertNull(RboardCatalog.parse("<html>rate limited</html>"))
        assertNull(RboardCatalog.parse("[1, 2, 3]"))
        assertEquals(emptyList<RboardPack>(), RboardCatalog.parse("[]"))
    }
}
