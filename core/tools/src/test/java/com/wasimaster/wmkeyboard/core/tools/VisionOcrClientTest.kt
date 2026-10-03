package com.wasimaster.wmkeyboard.core.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class VisionOcrClientTest {

    @Test
    fun `the endpoint is found from whatever was pasted`() {
        assertEquals("https://api.openai.com/v1/chat/completions", VisionOcrClient.endpoint("https://api.openai.com/v1"))
        assertEquals("https://api.openai.com/v1/chat/completions", VisionOcrClient.endpoint("api.openai.com/v1/"))
        assertEquals(
            "http://192.168.0.10:11434/v1/chat/completions",
            VisionOcrClient.endpoint("http://192.168.0.10:11434/v1/chat/completions"),
        )
    }

    @Test
    fun `the request carries the prompt and the photo as a data address`() {
        val body = Json.parseToJsonElement(VisionOcrClient.requestBody(" gpt-4o-mini ", "QUJD")).jsonObject
        assertEquals("gpt-4o-mini", body["model"]!!.jsonPrimitive.content)
        val parts = body["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray
        assertEquals(VisionOcrClient.PROMPT, parts[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals(
            "data:image/jpeg;base64,QUJD",
            parts[1].jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `the answer is read as a string, as parts, and out of a fence`() {
        assertEquals("Hello\nworld", VisionOcrClient.parse("""{"choices":[{"message":{"content":"Hello\nworld\n"}}]}"""))
        assertEquals(
            "AB",
            VisionOcrClient.parse("""{"choices":[{"message":{"content":[{"type":"text","text":"A"},{"text":"B"}]}}]}"""),
        )
        assertEquals("Line 1\nLine 2", VisionOcrClient.parse("""{"choices":[{"message":{"content":"```text\nLine 1\nLine 2\n```"}}]}"""))
    }
}
