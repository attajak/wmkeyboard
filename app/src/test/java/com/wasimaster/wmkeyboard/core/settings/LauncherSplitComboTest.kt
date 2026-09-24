package com.wasimaster.wmkeyboard.core.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The app launcher's split-screen pairs as they sit in one flat preference:
 * a line per pair, `first<TAB>second<TAB>name`.
 */
class LauncherSplitComboTest {

    private val mail = LauncherSplitCombo("com.example.mail", "com.example.maps", "Trip")
    private val chat = LauncherSplitCombo("org.chat.app", "com.bank.two")

    @Test
    fun `a list survives the round trip`() {
        val combos = listOf(mail, chat)
        assertEquals(combos, LauncherSplitCombo.decode(LauncherSplitCombo.encode(combos)))
    }

    @Test
    fun `the stored form is one tab-separated line per pair`() {
        assertEquals(
            "com.example.mail\tcom.example.maps\tTrip\norg.chat.app\tcom.bank.two\t",
            LauncherSplitCombo.encode(listOf(mail, chat)),
        )
    }

    @Test
    fun `nothing stored decodes to no pairs`() {
        assertEquals(emptyList<LauncherSplitCombo>(), LauncherSplitCombo.decode(null))
        assertEquals(emptyList<LauncherSplitCombo>(), LauncherSplitCombo.decode(""))
    }

    @Test
    fun `a name cannot break its record`() {
        val messy = LauncherSplitCombo("a.b", "c.d", "Work\tand\nplay")
        val decoded = LauncherSplitCombo.decode(LauncherSplitCombo.encode(listOf(messy, chat)))
        assertEquals(listOf(messy.copy(name = "Work and play"), chat), decoded)
    }

    @Test
    fun `malformed lines are skipped and the rest kept`() {
        val raw = "only.one\n\ta.b\n\norg.chat.app\tcom.bank.two\nx.y\tz.w\tNamed\textra"
        assertEquals(
            listOf(chat, LauncherSplitCombo("x.y", "z.w", "Named")),
            LauncherSplitCombo.decode(raw),
        )
    }

    @Test
    fun `a pair written without a name field still decodes`() {
        assertEquals(listOf(chat), LauncherSplitCombo.decode("org.chat.app\tcom.bank.two"))
    }

    @Test
    fun `adding the same pair twice keeps one`() {
        val once = LauncherSplitCombo.add(emptyList(), chat)
        assertSame(once, LauncherSplitCombo.add(once, chat.copy(name = "Renamed")))
    }

    @Test
    fun `the same apps the other way round are a different pair`() {
        val swapped = LauncherSplitCombo(chat.second, chat.first)
        assertEquals(listOf(chat, swapped), LauncherSplitCombo.add(listOf(chat), swapped))
    }

    @Test
    fun `moving reorders and out of range is a no-op`() {
        val third = LauncherSplitCombo("p.q", "r.s")
        val combos = listOf(mail, chat, third)
        assertEquals(listOf(third, mail, chat), LauncherSplitCombo.move(combos, 2, 0))
        assertEquals(listOf(chat, mail, third), LauncherSplitCombo.move(combos, 0, 1))
        assertSame(combos, LauncherSplitCombo.move(combos, 0, 3))
        assertSame(combos, LauncherSplitCombo.move(combos, -1, 0))
    }
}
