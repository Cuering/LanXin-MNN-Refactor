package com.lanxin.refactor.cloud

import com.lanxin.localllm.domain.CloudChatClient
import com.lanxin.localllm.domain.CloudChatResult
import com.lanxin.localllm.domain.CloudMessage
import com.lanxin.localllm.domain.CloudRole
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
 * 支持多轮 messages；密钥与 endpoint 由 [CloudConfig] 注入。
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

    /** 最近一次请求体（单测可断言 messages 结构） */
    @Volatile
    var lastRequestJson: String? = null
        private set

    override val isConfigured: Boolean
        get() {
            val c = configProvider()
            return c.apiKey.isNotBlank() && c.baseUrl.isNotBlank()
        }

    override suspend fun chatMessages(
        systemPrompt: String,
        messages: List<CloudMessage>,
        maxTokens: Int
    ): CloudChatResult = withContext(Dispatchers.IO) {
        val cfg = configProvider()
        if (cfg.apiKey.isBlank() || cfg.baseUrl.isBlank()) {
            return@withContext CloudChatResult(false, null, "cloud_not_configured")
        }
        if (messages.isEmpty()) {
            return@withContext CloudChatResult(false, null, "empty_messages")
        }
        val endpoint = cfg.chatCompletionsUrl()
        val apiMessages = buildList {
            val sys = systemPrompt.trim()
            if (sys.isNotEmpty()) {
                add(ChatMessage(role = CloudRole.SYSTEM, content = sys))
            }
            for (m in messages) {
                val role = when (m.role) {
                    CloudRole.SYSTEM, CloudRole.USER, CloudRole.ASSISTANT -> m.role
                    else -> CloudRole.USER
                }
                val content = m.content.trim()
                if (content.isNotEmpty()) {
                    add(ChatMessage(role = role, content = content))
                }
            }
        }
        if (apiMessages.none { it.role == CloudRole.USER }) {
            return@withContext CloudChatResult(false, null, "no_user_message")
        }
        val body = ChatCompletionRequest(
            model = cfg.model.ifBlank { "gpt-4o-mini" },
            messages = apiMessages,
            maxTokens = maxTokens.coerceIn(16, 4096),
            temperature = cfg.temperature
        )
        val payload = json.encodeToString(ChatCompletionRequest.serializer(), body)
        lastRequestJson = payload
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
                CloudChatResult(
                    ok = true,
                    text = text,
                    detail = "openai_compatible:msgs=${apiMessages.size}"
                )
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
