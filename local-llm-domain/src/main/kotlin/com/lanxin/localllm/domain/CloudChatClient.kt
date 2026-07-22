package com.lanxin.localllm.domain

/**
 * 云端对话客户端（可替换：OpenAI 兼容 / 自建网关 / stub）。
 * 不在 domain 内实现网络细节，避免把 HTTP 栈绑死到引擎层。
 */
interface CloudChatClient {
    val isConfigured: Boolean

    /**
     * 单轮兼容入口：system + 当前 user。
     * 默认实现走 [chatMessages]。
     */
    suspend fun chat(
        systemPrompt: String,
        userMessage: String,
        maxTokens: Int = 256
    ): CloudChatResult = chatMessages(
        systemPrompt = systemPrompt,
        messages = listOf(CloudMessage(CloudRole.USER, userMessage)),
        maxTokens = maxTokens
    )

    /**
     * 多轮入口：system + 有序消息（user/assistant 交替）。
     * 旧实现若只 override [chat]，可继续工作；新客户端应 override 本方法。
     */
    suspend fun chatMessages(
        systemPrompt: String,
        messages: List<CloudMessage>,
        maxTokens: Int = 256
    ): CloudChatResult
}

/** 云端消息角色（OpenAI 兼容） */
object CloudRole {
    const val SYSTEM = "system"
    const val USER = "user"
    const val ASSISTANT = "assistant"
}

data class CloudMessage(
    val role: String,
    val content: String
)

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

    override suspend fun chatMessages(
        systemPrompt: String,
        messages: List<CloudMessage>,
        maxTokens: Int
    ): CloudChatResult = CloudChatResult(
        ok = false,
        text = null,
        detail = "cloud_not_configured"
    )
}

/**
 * 单测 / 演示用 stub 云端。
 * 回包会带上最近一条 user 内容；若历史非空则 detail 标注 multi。
 */
class StubCloudChatClient(
    private val replyPrefix: String = "（云端 stub）"
) : CloudChatClient {
    override val isConfigured: Boolean = true

    /** 最近一次收到的 messages，单测可断言 */
    @Volatile
    var lastMessages: List<CloudMessage> = emptyList()
        private set

    @Volatile
    var lastSystem: String = ""
        private set

    override suspend fun chatMessages(
        systemPrompt: String,
        messages: List<CloudMessage>,
        maxTokens: Int
    ): CloudChatResult {
        lastSystem = systemPrompt
        lastMessages = messages.toList()
        val lastUser = messages.lastOrNull { it.role == CloudRole.USER }?.content.orEmpty()
        val histN = messages.count { it.role == CloudRole.ASSISTANT }
        return CloudChatResult(
            ok = true,
            text = "$replyPrefix$lastUser".take(maxTokens.coerceAtLeast(16)),
            detail = if (histN > 0) "stub_cloud_multi:$histN" else "stub_cloud"
        )
    }
}
