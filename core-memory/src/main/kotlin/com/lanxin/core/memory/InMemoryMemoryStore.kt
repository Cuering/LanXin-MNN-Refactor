package com.lanxin.core.memory

import java.util.concurrent.ConcurrentHashMap

/** 进程内实现，单测与演示用。 */
class InMemoryMemoryStore : MemoryStore {
    private val map = ConcurrentHashMap<String, MemoryItem>()

    override suspend fun upsert(item: MemoryItem) {
        map[item.id] = item
    }

    override suspend fun delete(id: String) {
        map.remove(id)
    }

    override suspend fun list(limit: Int): List<MemoryItem> =
        map.values
            .filter { it.status == MemoryStatus.ACTIVE }
            .sortedByDescending { it.updatedAt }
            .take(limit)

    override suspend fun search(query: String, limit: Int): List<MemoryItem> {
        val tokens = tokenize(query)
        if (tokens.isEmpty()) return list(limit)
        return map.values
            .filter { it.status == MemoryStatus.ACTIVE }
            .map { item -> item to score(item, tokens) }
            .filter { it.second > 0 }
            .sortedByDescending { it.second * 10 + it.first.importance }
            .take(limit)
            .map { it.first }
    }

    private fun tokenize(text: String): List<String> {
        val lower = text.trim().lowercase()
        if (lower.isEmpty()) return emptyList()
        val parts = Regex("""[\p{L}\p{N}]+""").findAll(lower).map { it.value }.toList()
        val grams = mutableListOf<String>()
        for (p in parts) {
            grams += p
            if (p.length >= 2 && p.any { it.code > 127 }) {
                for (i in 0 until p.length - 1) grams += p.substring(i, i + 2)
            }
        }
        return grams.distinct().filter { it.length >= 2 || it.any { c -> c.isDigit() } }
    }

    private fun score(item: MemoryItem, tokens: List<String>): Int {
        val hay = (item.content + " " + item.tags.joinToString(" ")).lowercase()
        return tokens.count { hay.contains(it) }
    }
}
