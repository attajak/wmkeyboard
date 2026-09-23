package com.wasimaster.wmkeyboard.ime

import com.wasimaster.wmkeyboard.core.input.composer.JapaneseComposer
import com.wasimaster.wmkeyboard.core.layout.Key
import com.wasimaster.wmkeyboard.core.layout.KeyAction
import com.wasimaster.wmkeyboard.core.layout.KeyboardLayout
import com.wasimaster.wmkeyboard.core.layout.Layouts
import com.wasimaster.wmkeyboard.core.script.LanguageRegistry
import com.wasimaster.wmkeyboard.core.settings.CjkSettings
import com.wasimaster.wmkeyboard.core.settings.HapticSettings
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The Japanese pad's two service-side additions: a space bar that can type the
 * ideographic space (issue #341), and the flag that turns a
 * `kanaVariantWhileComposing` key into the 小゛゜ key while the reading ends in
 * a kana that has a marked form (issue #340).
 *
 * Driven like the other service tests here: a subclass with a context attached
 * and `onCreate` never called.
 */
@RunWith(RobolectricTestRunner::class)
class KanaPadKeysTest {

    private val space = Key(" ", action = KeyAction.Space)

    private fun settings(fullWidth: Set<String> = emptySet()) = KeyboardSettings(
        learnFromTyping = false,
        haptics = HapticSettings(enabled = false),
        cjk = CjkSettings(fullWidthSpaceLanguages = fullWidth),
    )

    /** A pad whose globe key doubles as 小゛゜, or an ordinary one. */
    private fun pad(flagged: Boolean): LayoutSet {
        val letters = KeyboardLayout(
            name = "pad",
            rows = listOf(
                listOf(
                    Key("🌐", action = KeyAction.LanguageSwitch, kanaVariantWhileComposing = flagged),
                    Key("か"),
                    Key("ん"),
                ),
            ),
        )
        return LayoutSet(letters, Layouts.SYMBOLS, Layouts.SYMBOLS_SHIFTED)
    }

    private fun japanese(settings: KeyboardSettings, layouts: LayoutSet = pad(flagged = false)) =
        glideReadyState(glideReady = false, settings = settings).copy(
            composer = JapaneseComposer,
            language = LanguageRegistry.byId("ja"),
            layouts = layouts,
        )

    @Test
    fun `a language set to full-width spaces types the ideographic space`() {
        val (service, editor, _) = glideKeyboard(japanese(settings(fullWidth = setOf("ja"))))
        service.onKey(space)
        assertEquals("　", editor.typed)
    }

    @Test
    fun `every other language keeps the ascii space`() {
        val (service, editor, _) = glideKeyboard(japanese(settings(fullWidth = setOf("zh"))))
        service.onKey(space)
        assertEquals(" ", editor.typed)
    }

    @Test
    fun `a flagged key is the kana-mark key only while the last kana has a mark to take`() {
        val (service, _, _) = glideKeyboard(japanese(settings(), pad(flagged = true)))
        assertFalse(service.uiState.value.kanaVariantReady)

        service.onKey(Key("か"))
        assertTrue("か can become が", service.uiState.value.kanaVariantReady)

        service.onKey(Key("ん"))
        assertFalse("ん has no marked form", service.uiState.value.kanaVariantReady)
    }

    @Test
    fun `the kana-mark key changes the last kana and stays up for the next form`() {
        val (service, _, _) = glideKeyboard(japanese(settings(), pad(flagged = true)))
        service.onKey(Key("か"))
        service.onKey(Key("小゛゜", action = KeyAction.KanaVariant))
        assertEquals("が", service.uiState.value.composingPreview)
        assertTrue(service.uiState.value.kanaVariantReady)
    }

    /**
     * The flag is a key of the grid's `remember`, so a board with nothing to
     * swap must never see it move.
     */
    @Test
    fun `a board with no flagged key never publishes the flag`() {
        val (service, _, _) = glideKeyboard(japanese(settings(), pad(flagged = false)))
        service.onKey(Key("か"))
        assertFalse(service.uiState.value.kanaVariantReady)
    }
}
