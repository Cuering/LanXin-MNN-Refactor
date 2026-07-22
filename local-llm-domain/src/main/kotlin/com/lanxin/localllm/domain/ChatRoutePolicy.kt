package com.lanxin.localllm.domain

/**
 * 云端 / 本地路由策略（P5）。
 * 默认 PreferLocal：本地可用则本地，否则按策略降级。
 */
enum class ChatRoutePolicy {
    /** 仅本地，不可用则失败 */
    LOCAL_ONLY,

    /** 本地优先，失败可走云端（若配置了 CloudChatClient） */
    PREFER_LOCAL,

    /** 云端优先，失败回落本地 */
    PREFER_CLOUD,

    /** 仅云端 */
    CLOUD_ONLY
}

/** 实际选用的后端，写入 TurnResult 便于 UI/日志观测 */
enum class ChatBackend {
    LOCAL,
    CLOUD,
    NONE
}
