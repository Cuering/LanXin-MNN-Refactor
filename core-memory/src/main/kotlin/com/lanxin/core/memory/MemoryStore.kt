package com.lanxin.core.memory

/**
 * 记忆存储抽象 —— 可替换为 Room / ObjectBox / 文件实现。
 */
interface MemoryStore {
    suspend fun upsert(item: MemoryItem)
    suspend fun delete(id: String)
    suspend fun list(limit: Int = 50): List<MemoryItem>
    suspend fun search(query: String, limit: Int = 8): List<MemoryItem>
}
