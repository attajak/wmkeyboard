package com.wasimaster.wmkeyboard.core.selection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatSyntaxTest {

    private val whatsapp = ChatSyntax.forPackage("com.whatsapp")!!
    private val discord = ChatSyntax.forPackage("com.discord")!!

    @Test
    fun `an app not in the table gets nothing`() {
        assertNull(ChatSyntax.forPackage("com.android.chrome"))
        assertNull(ChatSyntax.forPackage(null))
    }

    @Test
    fun `wrap, then the same tap unwraps`() {
        assertEquals("*hello*", ChatSyntax.toggle("hello", whatsapp, ChatStyle.BOLD))
        assertEquals("hello", ChatSyntax.toggle("*hello*", whatsapp, ChatStyle.BOLD))
        assertEquals("**hello**", ChatSyntax.toggle("hello", discord, ChatStyle.BOLD))
        assertEquals("~~hello~~", ChatSyntax.toggle("hello", discord, ChatStyle.STRIKE))
    }

    @Test
    fun `whitespace stays outside the markers`() {
        assertEquals(" *hello* ", ChatSyntax.toggle(" hello ", whatsapp, ChatStyle.BOLD))
        assertNull(ChatSyntax.toggle("   ", whatsapp, ChatStyle.BOLD))
    }

    @Test
    fun `a lone marker wraps rather than unwrapping, and bold is not half-stripped by italic`() {
        assertEquals("***", ChatSyntax.toggle("*", whatsapp, ChatStyle.BOLD))
        assertEquals("***hello***", ChatSyntax.toggle("**hello**", discord, ChatStyle.ITALIC))
    }

    @Test
    fun `mono uses the block form across lines, and is absent where the app has none`() {
        assertEquals("```\na\nb\n```", ChatSyntax.toggle("a\nb", discord, ChatStyle.MONO))
        assertEquals("`a`", ChatSyntax.toggle("a", discord, ChatStyle.MONO))
        assertNull(ChatSyntax.toggle("a", ChatSyntax.forPackage("ch.threema.app")!!, ChatStyle.MONO))
    }

    @Test
    fun `the table is well formed`() {
        assertEquals(ChatSyntax.byPackage.size, ChatSyntax.byPackage.keys.distinct().size)
        for ((pkg, markup) in ChatSyntax.byPackage) {
            assertTrue(pkg, markup.bold.isNotEmpty() && markup.italic.isNotEmpty() && markup.strike.isNotEmpty())
            assertTrue(pkg, markup.mono?.isNotEmpty() ?: true)
        }
    }
}
