package com.wasimaster.wmkeyboard.core.feedback

import com.wasimaster.wmkeyboard.core.settings.KeySoundStyle
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
 * [KeySoundPlayer.canPlay]: whether a style and id have anything behind them.
 *
 * The question a theme's sound is now weighed against (issue #238). A theme
 * beats the global sound setting, so one naming a sound this device does not
 * have used to put every key on the system click with no way to tell why; the
 * answer here is what lets it defer to the user's own pick instead.
 */
class KeySoundCanPlayTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun sounds() = SoundStore(File(temp.root, "keysounds").apply { mkdirs() })

    private fun packs() = SoundPackStore(File(temp.root, "soundpacks").apply { mkdirs() })

    /** A minimal but real 16-bit mono PCM WAV, so the header sniff passes. */
    private fun wav(): ByteArray {
        val data = ByteArray(128)
        val out = ByteArrayOutputStream()
        fun le32(v: Int) = out.write(
            byteArrayOf(
                (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(),
                ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte(),
            ),
        )
        fun le16(v: Int) =
            out.write(byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte()))
        out.write("RIFF".toByteArray()); le32(36 + data.size); out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray()); le32(16); le16(1); le16(1)
        le32(44100); le32(88200); le16(2); le16(16)
        out.write("data".toByteArray()); le32(data.size); out.write(data)
        return out.toByteArray()
    }

    private fun installSound(store: SoundStore, name: String): InstalledSound {
        val result = SoundFile.import(ByteArrayInputStream(wav()), store, name)
        return (result as SoundImportResult.Imported).sound
    }

    private fun installPack(store: SoundPackStore, name: String): InstalledSoundPack {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(SoundPackFile.MANIFEST))
            zip.write(
                """
                {"format":"wmkeyboard-sound-pack","version":1,"id":"x","name":"$name",
                 "press":["sounds/1.wav"],"release":[],"roles":{}}
                """.trimIndent().toByteArray(),
            )
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("sounds/1.wav"))
            zip.write(wav())
            zip.closeEntry()
        }
        val result = SoundPackFile.import(ByteArrayInputStream(out.toByteArray()), store)
        return (result as SoundPackImportResult.Imported).pack
    }

    // ---- styles that are their own sound --------------------------------

    @Test
    fun `a synthesized or system style always plays, id or no id`() {
        val s = sounds()
        val p = packs()
        for (style in listOf(
            KeySoundStyle.CLICK,
            KeySoundStyle.STANDARD,
            KeySoundStyle.POP,
            KeySoundStyle.THOCK,
            KeySoundStyle.CHIME,
        )) {
            assertTrue("$style", KeySoundPlayer.canPlay(s, p, style, ""))
        }
    }

    // ---- custom ----------------------------------------------------------

    @Test
    fun `an installed sound plays by store id and by display name`() {
        val s = sounds()
        val sound = installSound(s, "Typewriter")
        assertTrue(KeySoundPlayer.canPlay(s, packs(), KeySoundStyle.CUSTOM, sound.id))
        assertTrue(KeySoundPlayer.canPlay(s, packs(), KeySoundStyle.CUSTOM, "Typewriter"))
        // A distributed theme carries the catalogue name, and cases vary.
        assertTrue(KeySoundPlayer.canPlay(s, packs(), KeySoundStyle.CUSTOM, "typewriter"))
    }

    @Test
    fun `custom naming nothing, or a sound that was never installed, cannot play`() {
        val s = sounds()
        installSound(s, "Typewriter")
        assertFalse(KeySoundPlayer.canPlay(s, packs(), KeySoundStyle.CUSTOM, ""))
        assertFalse(KeySoundPlayer.canPlay(s, packs(), KeySoundStyle.CUSTOM, "Marimba"))
    }

    @Test
    fun `a deleted sound stops being playable`() {
        val s = sounds()
        val sound = installSound(s, "Typewriter")
        s.delete(sound.id)
        assertFalse(KeySoundPlayer.canPlay(s, packs(), KeySoundStyle.CUSTOM, sound.id))
        assertFalse(KeySoundPlayer.canPlay(s, packs(), KeySoundStyle.CUSTOM, "Typewriter"))
    }

    // ---- packs -----------------------------------------------------------

    @Test
    fun `an installed pack plays by store id and by display name`() {
        val p = packs()
        val pack = installPack(p, "Typewriter")
        assertTrue(KeySoundPlayer.canPlay(sounds(), p, KeySoundStyle.PACK, pack.id))
        assertTrue(KeySoundPlayer.canPlay(sounds(), p, KeySoundStyle.PACK, "Typewriter"))
    }

    @Test
    fun `pack naming nothing, or a pack that was never installed, cannot play`() {
        val p = packs()
        installPack(p, "Typewriter")
        assertFalse(KeySoundPlayer.canPlay(sounds(), p, KeySoundStyle.PACK, ""))
        assertFalse(KeySoundPlayer.canPlay(sounds(), p, KeySoundStyle.PACK, "NK Creams"))
    }

    @Test
    fun `a deleted pack stops being playable`() {
        val p = packs()
        val pack = installPack(p, "Typewriter")
        p.delete(pack.id)
        assertFalse(KeySoundPlayer.canPlay(sounds(), p, KeySoundStyle.PACK, pack.id))
    }
}
