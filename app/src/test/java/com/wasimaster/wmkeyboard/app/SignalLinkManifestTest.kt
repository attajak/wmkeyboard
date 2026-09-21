package com.wasimaster.wmkeyboard.app

import com.wasimaster.wmkeyboard.core.addons.ImportLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * What the manifest claims of Signal's links, which is one thing and has to
 * stay one thing.
 *
 * `sgnl://` is Signal's whole scheme: chats, device linking, group invites,
 * payments. This app answers the sticker-pack host of it and nothing else, and
 * a filter widened by accident would put a keyboard in the chooser for
 * somebody's device-linking QR code.
 */
class SignalLinkManifestTest {

    private val manifest: String by lazy { File("src/main/AndroidManifest.xml").readText() }

    private val signalData: List<String> by lazy {
        Regex("""<data\b[^>]*android:scheme="sgnl"[^>]*/>""").findAll(manifest).map { it.value }.toList()
    }

    @Test
    fun `the sgnl scheme is claimed for sticker packs only`() {
        assertEquals("one sgnl filter, for one host", 1, signalData.size)
        assertTrue(signalData.single(), signalData.single().contains("""android:host="addstickers""""))
    }

    @Test
    fun `signal's web domains are not claimed`() {
        // Not this app's domains to verify, so Android would not route them
        // here, and claiming them would only add a row to "Open by default".
        assertFalse(manifest.contains("""android:host="signal.art""""))
        assertFalse(manifest.contains("""android:host="signal.org""""))
    }

    @Test
    fun `what the filter lets in is what the link reader understands`() {
        val link = "sgnl://addstickers?pack_id=fb535407d2f6497ec074df8b9c51dd1d" +
            "&pack_key=17e971c134035622781d2ee249e6473b774583750b68c11bb82b7509c68b6dfd"
        assertTrue(ImportLink.resolve(link) is ImportLink.Target.SignalStickers)
        // Another host of the scheme, should one ever arrive, is not a pack.
        assertFalse(ImportLink.resolve("sgnl://linkdevice?uuid=abc") is ImportLink.Target.SignalStickers)
    }
}
