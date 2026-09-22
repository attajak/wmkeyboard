package com.wasimaster.wmkeyboard.core.keyman

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading the data repository's copy of a keyboard's rules: its `meta.json`,
 * and the compressed `.kmx` checked against it.
 */
class KeymanMirrorTest {

    /** The mirror's `keyman/khmer_angkor/meta.json`, verbatim. */
    private val khmerMeta = """
        {
          "id": "khmer_angkor",
          "version": "2.4.1",
          "license": "mit",
          "sha256": "3849ffd0b8a26bb35029ef5c5176c317c395583db3a520036ccd29c221eb9fdb",
          "bytes": 27226,
          "source": "https://downloads.keyman.com/keyboards/khmer_angkor/2.4.1/khmer_angkor.kmp"
        }
    """.trimIndent()

    /** The same 2.4.1 `.kmx` the mirror holds; its SHA-256 is the one above. */
    private val khmerKmx: ByteArray by lazy {
        checkNotNull(javaClass.classLoader?.getResourceAsStream("keymanweb/khmer_angkor/khmer_angkor.kmx")) {
            "missing fixture"
        }.use { it.readBytes() }
    }

    private fun gzip(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }

    private fun unpack(packed: ByteArray, meta: KeymanMirror.Meta): ByteArray? =
        KeymanMirror.unpack(ByteArrayInputStream(packed), meta)

    @Test
    fun `paths follow the data repository's layout`() {
        assertEquals("keyman/khmer_angkor/meta.json", KeymanMirror.metaPath("khmer_angkor"))
        assertEquals("keyman/khmer_angkor/khmer_angkor.kmx.gz", KeymanMirror.rulesPath("khmer_angkor"))
    }

    @Test
    fun `a mirrored keyboard unpacks to the exact file and parses`() {
        val meta = KeymanMirror.parseMeta(khmerMeta, "khmer_angkor")
        assertNotNull(meta)
        assertEquals("2.4.1", meta!!.version)
        val rules = unpack(gzip(khmerKmx), meta)
        assertArrayEquals(khmerKmx, rules)
        assertTrue(KmxParser.parse(rules!!) is KeymanResult.Success)
    }

    @Test
    fun `a meta file for another keyboard is refused`() {
        assertNull(KeymanMirror.parseMeta(khmerMeta, "lao_2008_basic"))
    }

    @Test
    fun `a meta file not under MIT is refused`() {
        assertNull(KeymanMirror.parseMeta(khmerMeta.replace("\"mit\"", "\"freeware\""), "khmer_angkor"))
    }

    @Test
    fun `a meta file without a usable checksum, size or version is refused`() {
        assertNull(KeymanMirror.parseMeta(khmerMeta.replace("3849ffd0", "3849"), "khmer_angkor"))
        assertNull(KeymanMirror.parseMeta(khmerMeta.replace("3849ffd0", "3849ffzz"), "khmer_angkor"))
        assertNull(KeymanMirror.parseMeta(khmerMeta.replace("27226", "0"), "khmer_angkor"))
        assertNull(KeymanMirror.parseMeta(khmerMeta.replace("\"2.4.1\"", "\"\""), "khmer_angkor"))
        assertNull(KeymanMirror.parseMeta("not json", "khmer_angkor"))
    }

    @Test
    fun `a file whose checksum does not match is refused`() {
        val meta = KeymanMirror.parseMeta(khmerMeta, "khmer_angkor")!!
        val tampered = khmerKmx.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        assertNull(unpack(gzip(tampered), meta))
    }

    @Test
    fun `a file longer or shorter than promised is refused`() {
        val meta = KeymanMirror.parseMeta(khmerMeta, "khmer_angkor")!!
        assertNull(unpack(gzip(khmerKmx + byteArrayOf(0)), meta))
        assertNull(unpack(gzip(khmerKmx.copyOf(khmerKmx.size - 1)), meta))
    }

    @Test
    fun `a file that inflates past its promised size stops reading there`() {
        val meta = KeymanMirror.Meta("1", "0".repeat(64), bytes = 1024)
        // 64 MB of zeros compresses to about 64 KB; reading it whole would be the bug.
        val bomb = gzip(ByteArray(64 shl 20))
        assertNull(unpack(bomb, meta))
    }

    @Test
    fun `a file that is not gzip is refused`() {
        val meta = KeymanMirror.parseMeta(khmerMeta, "khmer_angkor")!!
        assertNull(unpack(khmerKmx, meta))
    }
}
