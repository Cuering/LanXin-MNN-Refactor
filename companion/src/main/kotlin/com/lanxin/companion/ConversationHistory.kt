package com.lanxin.companion

/**
 * 多轮对话历史（纯内存，不持久化）。
 *
 * - 按 [maxTurns] 滑动窗口丢弃最早对话
 * - [formatForPrompt] 生成插入 prompt 的文本块
 * - 无 Android / coroutine 依赖，纯 Kotlin，可单测
 */
data class ConversationTurn(
    val role: String,       // "用户" / "兰儿"
    val content: String,
    val timestampMs: Long = System.currentTimeMillis()
)

class ConversationHistory(
    /** 最大保留轮次数（user+assistant 各算 1 轮） */
    private val maxTurns: Int = 10
) {
    private val _turns = mutableListOf<ConversationTurn>()

    val turns: List<ConversationTurn> get() = _turns.toList()
    val size: Int get() = _turns.size

    fun add(role: String, content: String) {
        _turns.add(ConversationTurn(role, content.trim()))
        while (_turns.size > maxTurns) {
            _turns.removeFirst()
        }
    }

    /**
     * 格式化为 prompt 中插入的对话历史。
     * 最旧在上、最新在下；不包含当前轮次（由 caller 附加）。
     */
    fun formatForPrompt(): String {
        if (_turns.isEmpty()) return ""
        return _turns.joinToString("\n") { "${it.role}：${it.content}" }
    }

    /** 最新 N 条（用于调试展示） */
    fun recent(n: Int = 5): List<ConversationTurn> = _turns.takeLast(n)

    fun clear() {
        _turns.clear()
    }
}
