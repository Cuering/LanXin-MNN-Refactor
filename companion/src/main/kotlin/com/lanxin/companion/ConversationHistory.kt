package com.lanxin.companion

import kotlinx.serialization.Serializable

/**
 * 多轮对话历史（纯内存 + 可选持久化）。
 *
 * - 按 [maxTurns] 滑动窗口丢弃最早对话
 * - [formatForPrompt] 生成插入 prompt 的文本块
 * - 无 Android 依赖；序列化模型可供 [ConversationHistoryStore] 落盘
 */
@Serializable
data class ConversationTurn(
    val role: String,       // "用户" / "兰儿"
    val content: String,
    val timestampMs: Long = System.currentTimeMillis()
)

class ConversationHistory(
    /** 最大保留条数（用户/兰儿各算 1 条） */
    private val maxTurns: Int = 10
) {
    private val _turns = mutableListOf<ConversationTurn>()

    val turns: List<ConversationTurn> get() = _turns.toList()
    val size: Int get() = _turns.size
    val capacity: Int get() = maxTurns

    fun add(role: String, content: String) {
        _turns.add(ConversationTurn(role, content.trim()))
        trimToCapacity()
    }

    /** 用磁盘/备份数据整表替换（加载时调用） */
    fun replaceAll(turns: List<ConversationTurn>) {
        _turns.clear()
        _turns.addAll(turns.map { it.copy(content = it.content.trim()) })
        trimToCapacity()
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

    private fun trimToCapacity() {
        // 用 removeAt(0)，避免 JVM/Kotlin 对 removeFirst() 的 NoSuchMethodError
        while (_turns.size > maxTurns) {
            _turns.removeAt(0)
        }
    }
}
