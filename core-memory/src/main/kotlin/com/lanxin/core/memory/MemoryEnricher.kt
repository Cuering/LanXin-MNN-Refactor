package com.lanxin.core.memory

/**
 * 将相关记忆注入 system prompt（预算控制）。
 */
class MemoryEnricher(
    private val store: MemoryStore,
    private val maxItems: Int = 6,
    private val maxChars: Int = 800,
    private val useDecide: Boolean = true
) {
    suspend fun enrich(userMessage: String, baseSystem: String): String {
        if (useDecide && !MemoryDecide.shouldInject(userMessage)) return baseSystem
        val hits = store.search(userMessage, maxItems)
        if (hits.isEmpty()) return baseSystem
        val block = buildString {
            appendLine("【相关记忆】")
            var used = 0
            for (m in hits) {
                val line = "- (${m.type}) ${m.content.trim()}"
                if (used + line.length > maxChars) break
                appendLine(line)
                used += line.length
            }
        }
        return "$baseSystem\n\n$block".trim()
    }
}
