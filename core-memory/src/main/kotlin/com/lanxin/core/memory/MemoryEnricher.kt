package com.lanxin.core.memory

/**
 * 将相关记忆注入 prompt（预算控制，避免上下文爆炸）。
 */
class MemoryEnricher(
    private val store: MemoryStore,
    private val maxItems: Int = 6,
    private val maxChars: Int = 800
) {
    suspend fun enrich(userMessage: String, baseSystem: String): String {
        val hits = store.search(userMessage, maxItems)
        if (hits.isEmpty()) return baseSystem
        val block = buildString {
            appendLine("【相关记忆】")
            var used = 0
            for (m in hits) {
                val line = "- ${m.content.trim()}"
                if (used + line.length > maxChars) break
                appendLine(line)
                used += line.length
            }
        }
        return "$baseSystem\n\n$block".trim()
    }
}
