package com.lanxin.refactor.cloud

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiCompatibleCloudChatClientTest {

    @Test
    fun notConfigured_whenMissingKey() = runBlocking {
        val client = OpenAiCompatibleCloudChatClient(
            configProvider = { CloudConfig(apiKey = "", baseUrl = "https://example.com/v1") }
        )
        assertFalse(client.isConfigured)
        val r = client.chat("sys", "hi")
        assertFalse(r.ok)
        assertEquals("cloud_not_configured", r.detail)
    }

    @Test
    fun parsesCompletion() = runBlocking {
        val fake = HttpTransport { _, _, _, _, _ ->
            HttpResponse(
                200,
                """{"choices":[{"message":{"role":"assistant","content":"你好哥哥"}}]}"""
            )
        }
        val client = OpenAiCompatibleCloudChatClient(
            configProvider = {
                CloudConfig(apiKey = "sk-test", baseUrl = "https://example.com/v1", model = "m")
            },
            transport = fake
        )
        assertTrue(client.isConfigured)
        val r = client.chat("sys", "hi", 64)
        assertTrue(r.ok)
        assertEquals("你好哥哥", r.text)
    }

    @Test
    fun httpError_surfacesCode() = runBlocking {
        val fake = HttpTransport { _, _, _, _, _ ->
            HttpResponse(401, """{"error":"nope"}""")
        }
        val client = OpenAiCompatibleCloudChatClient(
            configProvider = { CloudConfig(apiKey = "x", baseUrl = "https://example.com/v1") },
            transport = fake
        )
        val r = client.chat("s", "u")
        assertFalse(r.ok)
        assertTrue(r.detail!!.startsWith("http_401"))
    }

    @Test
    fun chatCompletionsUrl_normalization() {
        assertEquals(
            "https://api.openai.com/v1/chat/completions",
            CloudConfig(baseUrl = "https://api.openai.com/v1").chatCompletionsUrl()
        )
        assertEquals(
            "https://x/chat/completions",
            CloudConfig(baseUrl = "https://x/chat/completions").chatCompletionsUrl()
        )
        assertEquals(
            "https://gw.example/v1/chat/completions",
            CloudConfig(baseUrl = "https://gw.example/v1/").chatCompletionsUrl()
        )
    }
}

private fun HttpTransport(
    block: (String, Map<String, String>, String, Int, Int) -> HttpResponse
): HttpTransport = object : HttpTransport {
    override fun postJson(
        url: String,
        headers: Map<String, String>,
        body: String,
        connectTimeoutMs: Int,
        readTimeoutMs: Int
    ): HttpResponse = block(url, headers, body, connectTimeoutMs, readTimeoutMs)
}
