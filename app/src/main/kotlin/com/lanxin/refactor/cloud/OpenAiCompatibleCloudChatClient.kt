package com.lanxin.refactor.cloud

import com.lanxin.localllm.domain.CloudChatClient
import com.lanxin.localllm.domain.CloudChatResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * OpenAI Chat Completions 兼容客户端（/v1/chat/completions）。
 * 密钥与 endpoint 由 [CloudConfig] 注入；网络走 [HttpTransport] 便于单测。
 */
class OpenAiCompatibleCloudChatClient(
    private val configProvider: () -> CloudConfig,
    private val transport: HttpTransport = UrlHttpTransport(),
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }
) : CloudChatClient {

    override val isConfigured: Boolean
        get() {
            val c = configProvider()
            return c.apiKey.isNotBlank() && c.baseUrl.isNotBlank()
        }

    override suspend fun chat(
        systemPrompt: String,
        userMessage: String,
        maxTokens: Int
    ): CloudChatResult = withContext(Dispatchers.IO) {
        val cfg = configProvider()
        if (cfg.apiKey.isBlank() || cfg.baseUrl.isBlank()) {
            return@withContext CloudChatResult(false, null, "cloud_not_configured")
        }
        val endpoint = cfg.chatCompletionsUrl()
        val body = ChatCompletionRequest(
            model = cfg.model.ifBlank { "gpt-4o-mini" },
            messages = listOf(
                ChatMessage(role = "system", content = systemPrompt),
                ChatMessage(role = "user", content = userMessage)
            ),
            maxTokens = maxTokens.coerceIn(16, 4096),
            temperature = cfg.temperature
        )
        val payload = json.encodeToString(ChatCompletionRequest.serializer(), body)
        try {
            val resp = transport.postJson(
                url = endpoint,
                headers = mapOf(
                    "Authorization" to "Bearer ${cfg.apiKey}",
                    "Content-Type" to "application/json"
                ),
                body = payload,
                connectTimeoutMs = cfg.connectTimeoutMs,
                readTimeoutMs = cfg.readTimeoutMs
            )
            if (resp.code !in 200..299) {
                return@withContext CloudChatResult(
                    ok = false,
                    text = null,
                    detail = "http_${resp.code}:${resp.body.take(200)}"
                )
            }
            val parsed = json.decodeFromString(ChatCompletionResponse.serializer(), resp.body)
            val text = parsed.choices.firstOrNull()?.message?.content?.trim()
            if (text.isNullOrBlank()) {
                CloudChatResult(false, null, "empty_completion")
            } else {
                CloudChatResult(true, text, detail = "openai_compatible")
            }
        } catch (t: Throwable) {
            CloudChatResult(
                ok = false,
                text = null,
                detail = "network:${t.javaClass.simpleName}:${t.message}"
            )
        }
    }
}

data class CloudConfig(
    val baseUrl: String = "https://api.openai.com/v1",
    val apiKey: String = "",
    val model: String = "gpt-4o-mini",
    val temperature: Double = 0.7,
    val connectTimeoutMs: Int = 15_000,
    val readTimeoutMs: Int = 60_000
) {
    fun chatCompletionsUrl(): String {
        val root = baseUrl.trim().trimEnd('/')
        return if (root.endsWith("/chat/completions")) root
        else if (root.endsWith("/v1")) "$root/chat/completions"
        else "$root/chat/completions"
    }
}

interface HttpTransport {
    fun postJson(
        url: String,
        headers: Map<String, String>,
        body: String,
        connectTimeoutMs: Int,
        readTimeoutMs: Int
    ): HttpResponse
}

data class HttpResponse(val code: Int, val body: String)

class UrlHttpTransport : HttpTransport {
    override fun postJson(
        url: String,
        headers: Map<String, String>,
        body: String,
        connectTimeoutMs: Int,
        readTimeoutMs: Int
    ): HttpResponse {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        try {
            OutputStreamWriter(conn.outputStream, StandardCharsets.UTF_8).use { it.write(body) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.use { s ->
                BufferedReader(InputStreamReader(s, StandardCharsets.UTF_8)).readText()
            }.orEmpty()
            return HttpResponse(code, text)
        } finally {
            conn.disconnect()
        }
    }
}

@Serializable
data class ChatCompletionRequest(
    val model: String,
    val messages: List<ChatMessage>,
    @SerialName("max_tokens") val maxTokens: Int,
    val temperature: Double = 0.7
)

@Serializable
data class ChatMessage(
    val role: String,
    val content: String
)

@Serializable
data class ChatCompletionResponse(
    val choices: List<Choice> = emptyList()
)

@Serializable
data class Choice(
    val message: ChatMessage? = null
)
