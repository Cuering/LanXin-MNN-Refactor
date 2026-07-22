package com.lanxin.localllm.domain

/**
 * 根据 [ChatRoutePolicy] 与本地/云端就绪状态选择后端。
 * 纯决策，不持有会话状态。
 */
class ChatRouter(
    private val policy: ChatRoutePolicy = ChatRoutePolicy.PREFER_LOCAL
) {
    data class Decision(
        val backend: ChatBackend,
        val reason: String
    )

    fun decide(
        localUsable: Boolean,
        cloudConfigured: Boolean
    ): Decision {
        return when (policy) {
            ChatRoutePolicy.LOCAL_ONLY -> {
                if (localUsable) Decision(ChatBackend.LOCAL, "policy=LOCAL_ONLY")
                else Decision(ChatBackend.NONE, "LOCAL_ONLY but local unusable")
            }
            ChatRoutePolicy.CLOUD_ONLY -> {
                if (cloudConfigured) Decision(ChatBackend.CLOUD, "policy=CLOUD_ONLY")
                else Decision(ChatBackend.NONE, "CLOUD_ONLY but cloud not configured")
            }
            ChatRoutePolicy.PREFER_LOCAL -> {
                when {
                    localUsable -> Decision(ChatBackend.LOCAL, "prefer_local:local_ready")
                    cloudConfigured -> Decision(ChatBackend.CLOUD, "prefer_local:fallback_cloud")
                    else -> Decision(ChatBackend.NONE, "prefer_local:no_backend")
                }
            }
            ChatRoutePolicy.PREFER_CLOUD -> {
                when {
                    cloudConfigured -> Decision(ChatBackend.CLOUD, "prefer_cloud:cloud_ready")
                    localUsable -> Decision(ChatBackend.LOCAL, "prefer_cloud:fallback_local")
                    else -> Decision(ChatBackend.NONE, "prefer_cloud:no_backend")
                }
            }
        }
    }

    /** 主后端失败后的回落（仅 PREFER_* 策略）。 */
    fun fallback(primary: ChatBackend, localUsable: Boolean, cloudConfigured: Boolean): Decision? {
        return when (policy) {
            ChatRoutePolicy.PREFER_LOCAL -> {
                if (primary == ChatBackend.LOCAL && cloudConfigured) {
                    Decision(ChatBackend.CLOUD, "fallback_after_local_fail")
                } else null
            }
            ChatRoutePolicy.PREFER_CLOUD -> {
                if (primary == ChatBackend.CLOUD && localUsable) {
                    Decision(ChatBackend.LOCAL, "fallback_after_cloud_fail")
                } else null
            }
            else -> null
        }
    }
}
