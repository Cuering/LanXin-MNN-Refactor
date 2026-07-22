package com.lanxin.localllm.domain

/**
 * 云端对话客户端（可替换：OpenAI 兼容 / 自建网关 / stub）。
 * 不在 domain 内实现网络细节，避免把 HTTP 栈绑死到引擎层。
 */
interface CloudChatClient {
    val isConfigured: Boolean
    suspend fun chat(systemPrompt: String, userMessage: String, maxTokens: Int = 256): CloudChatResult
}

data class CloudChatResult(
    val ok: Boolean,
    val text: String?,
    val detail: String? = null
)

/**
 * 显式未配置云端：状态可观测，禁止伪装成功。
 */
class UnconfiguredCloudChatClient : CloudChatClient {
    override val isConfigured: Boolean = false

    override suspend fun chat(
        systemPrompt: String,
        userMessage: String,
        maxTokens: Int
    ): CloudChatResult = CloudChatResult(
        ok = false,
        text = null,
        detail = "cloud_not_configured"
    )
}

/**
 * 单测 / 演示用 stub 云端。
 */
class StubCloudChatClient(
    private val replyPrefix: String = "（云端 stub）"
) : CloudChatClient {
    override val isConfigured: Boolean = true

    override suspend fun chat(
        systemPrompt: String,
        userMessage: String,
        maxTokens: Int
    ): CloudChatResult = CloudChatResult(
        ok = true,
        text = "$replyPrefix$userMessage".take(maxTokens.coerceAtLeast(16)),
        detail = "stub_cloud"
    )
}
