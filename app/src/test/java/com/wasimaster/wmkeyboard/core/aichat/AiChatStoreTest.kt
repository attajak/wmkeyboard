package com.wasimaster.wmkeyboard.core.aichat

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AiChatStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private var clock = 1_700_000_000_000

    private fun user(text: String) = AiChatMessage(
        role = AiChatMessage.ROLE_USER,
        content = text,
        timestamp = ++clock,
    )

    private fun assistant(text: String, provider: String = "GEMINI", model: String = "gemini-3.5-flash") =
        AiChatMessage(
            role = AiChatMessage.ROLE_ASSISTANT,
            content = text,
            timestamp = ++clock,
            provider = provider,
            model = model,
        )

    private fun file() = File(tmp.newFolder(), AiChatStore.FILE_PATH)

    @Test
    fun `conversations come back latest updated first`() {
        val store = AiChatStore(null)
        val a = store.newConversation(now = 100)
        val b = store.newConversation(now = 200)
        store.appendMessage(a.id, user("hello").copy(timestamp = 300))
        assertEquals(listOf(a.id, b.id), store.items().map { it.id })
    }

    @Test
    fun `the first user message titles the conversation`() {
        val store = AiChatStore(null)
        val c = store.newConversation(now = 1)
        store.appendMessage(c.id, user("What is the tallest mountain?\nAnd the second?"))
        store.appendMessage(c.id, assistant("Everest."))
        store.appendMessage(c.id, user("Different question"))
        assertEquals("What is the tallest mountain?", store.get(c.id)?.title)
    }

    @Test
    fun `a long title is cut on a word with an ellipsis`() {
        val store = AiChatStore(null)
        val c = store.newConversation(now = 1)
        store.appendMessage(c.id, user("word ".repeat(30)))
        val title = store.get(c.id)!!.title
        assertTrue(title.length <= AiChatStore.MAX_TITLE + 1)
        assertTrue(title.endsWith("…"))
    }

    @Test
    fun `messages past the cap drop from the front`() {
        val store = AiChatStore(null)
        val c = store.newConversation(now = 1)
        for (i in 1..AiChatStore.MAX_MESSAGES + 5) {
            store.appendMessage(c.id, user("message $i"))
        }
        val messages = store.get(c.id)!!.messages
        assertEquals(AiChatStore.MAX_MESSAGES, messages.size)
        assertEquals("message 6", messages.first().content)
    }

    @Test
    fun `oldest conversations fall off at the cap`() {
        val store = AiChatStore(null)
        for (i in 1..AiChatStore.MAX_CONVERSATIONS + 3) {
            val c = store.newConversation(now = i.toLong())
            store.appendMessage(c.id, user("chat $i").copy(timestamp = i.toLong()))
        }
        assertEquals(AiChatStore.MAX_CONVERSATIONS, store.items().size)
        assertNull(store.items().firstOrNull { it.title == "chat 1" })
    }

    @Test
    fun `long message text is cut without splitting a surrogate pair`() {
        val store = AiChatStore(null)
        val c = store.newConversation(now = 1)
        val text = "x".repeat(AiChatStore.MAX_TEXT - 1) + "😀extra"
        store.appendMessage(c.id, user(text))
        val stored = store.get(c.id)!!.messages.single().content
        assertEquals(AiChatStore.MAX_TEXT - 1, stored.length)
        assertTrue(!stored.last().isHighSurrogate())
    }

    @Test
    fun `an on-device assistant message records the local model id`() {
        val store = AiChatStore(null)
        val c = store.newConversation(now = 1)
        store.appendMessage(c.id, user("hi"))
        store.appendMessage(c.id, assistant("hello", provider = "ON_DEVICE", model = "gemma-4-e2b"))
        val stored = store.get(c.id)!!
        assertEquals("ON_DEVICE", stored.provider)
        assertEquals("gemma-4-e2b", stored.localModelId)
    }

    @Test
    fun `replaceLastMessage swaps the streamed placeholder for the final text`() {
        val store = AiChatStore(null)
        val c = store.newConversation(now = 1)
        store.appendMessage(c.id, user("hi"))
        store.appendMessage(c.id, assistant("partial"))
        store.replaceLastMessage(c.id, assistant("full answer"))
        assertEquals(listOf("hi", "full answer"), store.get(c.id)!!.messages.map { it.content })
    }

    @Test
    fun `appendMessage to a deleted conversation returns null`() {
        val store = AiChatStore(null)
        val c = store.newConversation(now = 1)
        store.delete(c.id)
        assertNull(store.appendMessage(c.id, user("hi")))
    }

    @Test
    fun `conversations and the model key survive a save and reload`() {
        val f = file()
        val store = AiChatStore(f)
        val c = store.newConversation(now = 1)
        store.appendMessage(c.id, user("persist me"))
        store.setLastModelKey("ON_DEVICE:gemma-4-e2b")
        store.save()

        val reopened = AiChatStore(f)
        assertNotNull(reopened.get(c.id))
        assertEquals("persist me", reopened.get(c.id)!!.messages.single().content)
        assertEquals("ON_DEVICE:gemma-4-e2b", reopened.lastModelKey())
    }

    @Test
    fun `ids keep counting up after a reload`() {
        val f = file()
        val store = AiChatStore(f)
        val first = store.newConversation(now = 1)
        store.appendMessage(first.id, user("keep"))
        store.save()

        val reopened = AiChatStore(f)
        val second = reopened.newConversation(now = 2)
        assertTrue(second.id > first.id)
    }

    @Test
    fun `an attachment is kept apart from the message and joined for the model`() {
        val f = file()
        val store = AiChatStore(f)
        val c = store.newConversation(now = 1)
        store.appendMessage(c.id, user("Reply to this").copy(attachment = "See you at five?"))
        store.save()

        val message = AiChatStore(f).get(c.id)!!.messages.single()
        assertEquals("Reply to this", message.content)
        assertEquals("See you at five?", message.attachment)
        assertEquals("Reply to this\n\n<text>\nSee you at five?\n</text>", message.promptText())
        // The title is the question, never the quoted text.
        assertEquals("Reply to this", AiChatStore(f).get(c.id)!!.title)
    }

    @Test
    fun `a message with no attachment reads as written`() {
        assertEquals("hello", user("hello").promptText())
    }

    @Test
    fun `a file from before attachments still opens`() {
        val f = file()
        f.parentFile!!.mkdirs()
        f.writeText(
            """{"conversations":[{"id":4,"title":"old","createdAt":1,"updatedAt":2,"messages":[""" +
                """{"role":"USER","content":"hi","timestamp":2}]}],"lastModelKey":"GEMINI"}""",
        )
        val message = AiChatStore(f).get(4)!!.messages.single()
        assertEquals("hi", message.content)
        assertEquals("", message.attachment)
    }

    @Test
    fun `dropping from a message takes everything after it too`() {
        val store = AiChatStore(null)
        val c = store.newConversation(now = 1)
        store.appendMessage(c.id, user("one"))
        store.appendMessage(c.id, assistant("first answer"))
        store.appendMessage(c.id, user("two"))
        store.appendMessage(c.id, assistant("second answer"))

        val gone = store.dropFrom(c.id, 2)
        assertEquals(listOf("two", "second answer"), gone.map { it.content })
        assertEquals(listOf("one", "first answer"), store.get(c.id)!!.messages.map { it.content })
        // Out of range, or a conversation that is gone: nothing happens.
        assertTrue(store.dropFrom(c.id, 9).isEmpty())
        assertTrue(store.dropFrom(99, 0).isEmpty())
    }

    @Test
    fun `an incognito conversation is never written to the file`() {
        val f = file()
        val store = AiChatStore(f)
        val kept = store.newConversation(now = 1)
        store.appendMessage(kept.id, user("keep me"))
        val secret = store.newConversation(now = 2, ephemeral = true)
        store.appendMessage(secret.id, user("not this"))
        store.save()

        // Still there to read and carry on while the process lives…
        assertNotNull(store.get(secret.id))
        // …and gone from what was saved.
        val reopened = AiChatStore(f)
        assertNotNull(reopened.get(kept.id))
        assertNull(reopened.get(secret.id))
    }
}
