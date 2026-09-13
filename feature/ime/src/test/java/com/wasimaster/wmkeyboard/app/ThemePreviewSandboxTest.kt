package com.wasimaster.wmkeyboard.app

import com.wasimaster.wmkeyboard.core.layout.Key
import com.wasimaster.wmkeyboard.core.layout.KeyAction
import com.wasimaster.wmkeyboard.core.settings.ToolbarTool
import com.wasimaster.wmkeyboard.ime.LayoutMode
import com.wasimaster.wmkeyboard.ime.PanelMode
import com.wasimaster.wmkeyboard.ime.ShiftState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The theme editor's stand-in for a text field (issue #148). Every rule here
 * exists because the preview would look wrong without it: a shift that never
 * came back down, a ?123 key that went nowhere, a backspace that took half an
 * emoji.
 */
class ThemePreviewSandboxTest {

    private val context = SandboxContext(
        cased = true,
        capitalize = true,
        activeLayoutId = "en",
        enabledLayoutIds = listOf("en", "bn", "de"),
        secondaryLayoutIds = setOf("numpad_custom"),
        hasFnLayer = false,
        capsLockMs = 350,
    )

    private fun key(label: String, action: KeyAction = KeyAction.Text, shiftLabel: String? = null) =
        Key(label = label, shiftLabel = shiftLabel, action = action)

    private fun ThemePreviewSandbox.type(text: String, ctx: SandboxContext = context): ThemePreviewSandbox =
        text.fold(this) { sandbox, char ->
            sandbox.onKey(
                if (char == ' ') key(" ", KeyAction.Space) else key(char.toString()),
                ctx,
            )
        }

    @Test
    fun `an empty buffer arms shift for the first letter`() {
        val start = ThemePreviewSandbox().settled(context)
        assertEquals(ShiftState.ON, start.shift)
        assertFalse(start.shiftPressedByUser)
    }

    @Test
    fun `a letter under an armed shift is capitalized and spends it`() {
        val typed = ThemePreviewSandbox().settled(context).onKey(key("h"), context)
        assertEquals("H", typed.text)
        assertEquals(ShiftState.OFF, typed.shift)
    }

    @Test
    fun `a sentence end re-arms shift and a plain space does not`() {
        val mid = ThemePreviewSandbox().settled(context).type("Hi ")
        assertEquals(ShiftState.OFF, mid.shift)
        val ended = mid.type("there. ")
        assertEquals("Hi there. ", ended.text)
        assertEquals(ShiftState.ON, ended.shift)
        assertFalse(ended.shiftPressedByUser)
    }

    @Test
    fun `enter starts a new sentence`() {
        val broken = ThemePreviewSandbox().settled(context).type("ok")
            .onKey(key("", KeyAction.Enter), context)
        assertEquals("Ok\n", broken.text)
        assertEquals(ShiftState.ON, broken.shift)
    }

    @Test
    fun `caps lock holds through letters`() {
        val locked = ThemePreviewSandbox().onKey(key("", KeyAction.CapsLock), context).type("ab")
        assertEquals("AB", locked.text)
        assertEquals(ShiftState.CAPS_LOCK, locked.shift)
        assertTrue(locked.shiftPressedByUser)
        assertEquals(ShiftState.OFF, locked.onKey(key("", KeyAction.CapsLock), context).shift)
    }

    @Test
    fun `two shift taps inside the window lock caps and a later tap clears`() {
        val shift = key("", KeyAction.Shift)
        val once = ThemePreviewSandbox().onKey(shift, context, nowMs = 1_000)
        assertEquals(ShiftState.ON, once.shift)
        val twice = once.onKey(shift, context, nowMs = 1_100)
        assertEquals(ShiftState.CAPS_LOCK, twice.shift)
        val cleared = twice.onKey(shift, context, nowMs = 5_000)
        assertEquals(ShiftState.OFF, cleared.shift)
    }

    @Test
    fun `shift prefers the key's own shift label to upper-casing`() {
        val bang = ThemePreviewSandbox(shift = ShiftState.ON, shiftPressedByUser = true)
            .onKey(key("1", shiftLabel = "!"), context)
        assertEquals("!", bang.text)
    }

    @Test
    fun `an uncased script neither capitalizes nor upper-cases`() {
        val bengali = context.copy(cased = false, clusterShaping = true)
        val start = ThemePreviewSandbox().settled(bengali)
        assertEquals(ShiftState.OFF, start.shift)
        val shifted = start.onKey(key("", KeyAction.Shift), bengali).onKey(key("ক"), bengali)
        assertEquals("ক", shifted.text)
    }

    @Test
    fun `backspace takes a whole character, not half a surrogate pair`() {
        val typed = ThemePreviewSandbox(text = "a\uD83D\uDC4D")
        val deleted = typed.onKey(key("", KeyAction.Delete), context)
        assertEquals("a", deleted.text)
        // An empty buffer is left alone, and re-arms shift for what comes next.
        val empty = deleted.onKey(key("", KeyAction.Delete), context)
        assertEquals("", empty.text)
        assertEquals(ShiftState.ON, empty.shift)
        assertSame(empty, empty.onKey(key("", KeyAction.Delete), context))
    }

    @Test
    fun `the symbols key cycles the symbol layers and letters returns`() {
        val symbols = key("?123", KeyAction.Symbols)
        val first = ThemePreviewSandbox().onKey(symbols, context)
        assertEquals(LayoutMode.SYMBOLS, first.layoutMode)
        val second = first.onKey(symbols, context)
        assertEquals(LayoutMode.SYMBOLS_SHIFTED, second.layoutMode)
        assertEquals(LayoutMode.SYMBOLS, second.onKey(symbols, context).layoutMode)
        assertEquals(LayoutMode.LETTERS, second.onKey(key("ABC", KeyAction.Letters), context).layoutMode)
    }

    @Test
    fun `fn needs an fn layer and toggles back to the letters`() {
        val fn = key("Fn", KeyAction.Fn)
        assertEquals(LayoutMode.LETTERS, ThemePreviewSandbox().onKey(fn, context).layoutMode)
        val withFn = context.copy(hasFnLayer = true)
        val up = ThemePreviewSandbox().onKey(fn, withFn)
        assertEquals(LayoutMode.FN, up.layoutMode)
        assertEquals(LayoutMode.LETTERS, up.onKey(fn, withFn).layoutMode)
    }

    @Test
    fun `the globe cycles the enabled layouts from the active one`() {
        val globe = key("", KeyAction.LanguageSwitch)
        val next = ThemePreviewSandbox().onKey(globe, context)
        assertEquals("bn", next.layoutId)
        val after = next.onKey(globe, context)
        assertEquals("de", after.layoutId)
        assertEquals("en", after.onKey(globe, context).layoutId)
    }

    @Test
    fun `a secondary layout key toggles its grid and ignores unknown ids`() {
        val numpad = key("", KeyAction.Layout("numpad_custom"))
        val open = ThemePreviewSandbox().onKey(numpad, context)
        assertEquals(LayoutMode.SECONDARY, open.layoutMode)
        assertEquals("numpad_custom", open.secondaryLayoutId)
        assertEquals(LayoutMode.LETTERS, open.onKey(numpad, context).layoutMode)
        val start = ThemePreviewSandbox()
        assertSame(start, start.onKey(key("", KeyAction.Layout("gone")), context))
    }

    @Test
    fun `a suggestion replaces the word under the caret and ends it`() {
        val typed = ThemePreviewSandbox().settled(context).type("Say hel")
        assertEquals(listOf("hel"), typed.suggestions)
        val taken = typed.onSuggestion("hello", context)
        assertEquals("Say hello ", taken.text)
        assertEquals(emptyList<String>(), taken.suggestions)
    }

    @Test
    fun `tools open their panel and a second tap closes it`() {
        val opened = ThemePreviewSandbox().onTool(ToolbarTool.EMOJI)
        assertEquals(PanelMode.EMOJI, opened.panel)
        assertEquals(PanelMode.NONE, opened.onTool(ToolbarTool.EMOJI).panel)
        assertEquals(PanelMode.CALCULATOR, opened.onTool(ToolbarTool.CALCULATOR).panel)
        val start = ThemePreviewSandbox()
        assertSame(start, start.onTool(ToolbarTool.UNDO))
    }

    @Test
    fun `the chrome closes a panel by naming it, as the service does`() {
        // The toolbox launcher, the bar's back chevron and a panel's own close
        // button all hand over the panel that is open; setting it outright
        // left the toolbox stuck open under its back arrow.
        val toolbox = ThemePreviewSandbox().onPanel(PanelMode.TOOLBOX)
        assertEquals(PanelMode.TOOLBOX, toolbox.panel)
        assertEquals(PanelMode.NONE, toolbox.onPanel(PanelMode.TOOLBOX).panel)
        assertEquals(PanelMode.EMOJI, toolbox.onPanel(PanelMode.EMOJI).panel)
    }

    @Test
    fun `the emoji and numpad keys open their panels`() {
        assertEquals(
            PanelMode.EMOJI,
            ThemePreviewSandbox().onKey(key("", KeyAction.Emoji), context).panel,
        )
        assertEquals(
            PanelMode.NUMPAD,
            ThemePreviewSandbox().onKey(key("", KeyAction.Numpad), context).panel,
        )
        assertEquals(
            PanelMode.CLIPBOARD,
            ThemePreviewSandbox().onKey(key("", KeyAction.Tool(ToolbarTool.CLIPBOARD)), context).panel,
        )
    }

    @Test
    fun `keys with nowhere to go leave the sandbox alone`() {
        val start = ThemePreviewSandbox()
        assertSame(start, start.onKey(key("", KeyAction.SendKey(keyCode = 61)), context))
        assertSame(start, start.onKey(key("", KeyAction.InputMethodPicker), context))
    }
}
