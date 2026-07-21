package com.lanxin.core.memory

import java.util.concurrent.ConcurrentHashMap

/** 进程内实现，便于先打通陪伴链路；后续可换持久化。 */
class InMemoryMemoryStore : MemoryStore {
    private val map = ConcurrentHashMap<String, MemoryItem>()

    override suspend fun upsert(item: MemoryItem) {
        map[item.id] = item
    }

    override suspend fun delete(id: String) {
        map.remove(id)
    }

    override suspend fun list(limit: Int): List<MemoryItem> =
        map.values.sortedByDescending { it.updatedAt }.take(limit)

    override suspend fun search(query: String, limit: Int): List<MemoryItem> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return list(limit)
        return map.values
            .filter { it.content.lowercase().contains(q) || it.tags.any { t -> t.lowercase().contains(q) } }
            .sortedByDescending { it.importance }
            .take(limit)
    }
}
