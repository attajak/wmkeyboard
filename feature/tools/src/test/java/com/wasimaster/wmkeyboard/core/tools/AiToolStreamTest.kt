package com.wasimaster.wmkeyboard.core.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reading tool calls back out of each provider's event stream. */
class AiToolStreamTest {

    private fun buffer() = AiClient.StreamBuffer()

    @Test
    fun `openai reassembles arguments split across chunks`() {
        val buffer = buffer()
        AiClient.applyOpenAiEvent(
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"c1",""" +
                """"function":{"name":"web_search","arguments":"{\"que"}}]}}]}""",
            buffer,
        )
        AiClient.applyOpenAiEvent(
            """{"choices":[{"delta":{"tool_calls":[{"index":0,""" +
                """"function":{"arguments":"ry\":\"rain\"}"}}]}}]}""",
            buffer,
        )
        val call = buffer.finish().toolCalls.single()
        assertEquals("c1", call.id)
        assertEquals("web_search", call.name)
        assertEquals("rain", call.argument("query"))
    }

    @Test
    fun `openai keeps two calls apart by index`() {
        val buffer = buffer()
        AiClient.applyOpenAiEvent(
            """{"choices":[{"delta":{"tool_calls":[""" +
                """{"index":0,"id":"a","function":{"name":"web_search","arguments":"{}"}},""" +
                """{"index":1,"id":"b","function":{"name":"web_fetch","arguments":"{}"}}]}}]}""",
            buffer,
        )
        assertEquals(listOf("a", "b"), buffer.finish().toolCalls.map { it.id })
    }

    @Test
    fun `anthropic reassembles input_json_delta against its block index`() {
        val buffer = buffer()
        AiClient.applyAnthropicEvent(
            """{"type":"content_block_start","index":1,""" +
                """"content_block":{"type":"tool_use","id":"tu_1","name":"web_fetch"}}""",
            buffer,
        )
        AiClient.applyAnthropicEvent(
            """{"type":"content_block_delta","index":1,""" +
                """"delta":{"type":"input_json_delta","partial_json":"{\"url\":\"ht"}}""",
            buffer,
        )
        AiClient.applyAnthropicEvent(
            """{"type":"content_block_delta","index":1,""" +
                """"delta":{"type":"input_json_delta","partial_json":"tps://a.test\"}"}}""",
            buffer,
        )
        val call = buffer.finish().toolCalls.single()
        assertEquals("tu_1", call.id)
        assertEquals("https://a.test", call.argument("url"))
    }

    @Test
    fun `anthropic text still reads as text alongside a call`() {
        val buffer = buffer()
        AiClient.applyAnthropicEvent(
            """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Let me look."}}""",
            buffer,
        )
        AiClient.applyAnthropicEvent(
            """{"type":"content_block_start","index":1,""" +
                """"content_block":{"type":"tool_use","id":"t","name":"web_search"}}""",
            buffer,
        )
        val completion = buffer.finish()
        assertEquals("Let me look.", completion.text)
        assertEquals(1, completion.toolCalls.size)
    }

    @Test
    fun `gemini reads a whole call from one part`() {
        val buffer = buffer()
        AiClient.applyGeminiEvent(
            """{"candidates":[{"content":{"parts":[""" +
                """{"functionCall":{"name":"web_search","args":{"query":"tides"}}}]}}]}""",
            buffer,
        )
        assertEquals("tides", buffer.finish().toolCalls.single().argument("query"))
    }

    @Test
    fun `ollama reads object arguments`() {
        val buffer = buffer()
        AiClient.applyOllamaEvent(
            """{"message":{"content":"","tool_calls":[""" +
                """{"function":{"name":"web_fetch","arguments":{"url":"https://b.test"}}}]}}""",
            buffer,
        )
        assertEquals("https://b.test", buffer.finish().toolCalls.single().argument("url"))
    }

    @Test
    fun `a round that only called a tool is not empty`() {
        val buffer = buffer()
        AiClient.applyGeminiEvent(
            """{"candidates":[{"content":{"parts":[{"functionCall":{"name":"web_search","args":{}}}]}}]}""",
            buffer,
        )
        // Text is empty, but the round is complete: without this the stream
        // runner would re-parse the body and lose the call.
        assertTrue(buffer.isEmpty)
        assertTrue(buffer.hasToolCalls)
    }

    @Test
    fun `a missing argument reads as absent rather than blank`() {
        assertEquals(null, AiToolCall("", "web_search", "{}").argument("query"))
        assertEquals(null, AiToolCall("", "web_search", """{"query":"  "}""").argument("query"))
        assertEquals("x", AiToolCall("", "web_search", """{"query":" x "}""").argument("query"))
    }
}
