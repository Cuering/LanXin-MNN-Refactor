package com.lanxin.core.memory

/**
 * 是否值得做记忆检索注入（短寒暄可跳过，省算力）。
 */
object MemoryDecide {
    private val skipExact = setOf(
        "你好", "嗨", "在吗", "早上好", "晚安", "哈哈", "嗯", "哦", "好的", "谢谢"
    )

    fun shouldInject(userMessage: String): Boolean {
        val t = userMessage.trim()
        if (t.isEmpty()) return false
        if (t.length <= 2) return false
        if (skipExact.contains(t)) return false
        // 纯表情/标点
        if (t.all { !it.isLetterOrDigit() && it.code < 0x4e00 }) return false
        return true
    }
}
