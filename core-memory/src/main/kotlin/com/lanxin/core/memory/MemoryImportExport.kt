package com.lanxin.core.memory

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * 记忆 JSON 导入导出。格式与 [FileMemoryStore] 落盘一致：MemoryItem 数组。
 * 兼容旧 App 导出时尽量 ignoreUnknownKeys。
 */
object MemoryImportExport {
    val json: Json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    fun exportJson(items: List<MemoryItem>): String =
        json.encodeToString(ListSerializer(MemoryItem.serializer()), items)

    fun importJson(text: String): List<MemoryItem> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return emptyList()
        return json.decodeFromString(ListSerializer(MemoryItem.serializer()), trimmed)
    }

    /**
     * 合并导入：按 id upsert，返回写入条数。
     */
    suspend fun mergeInto(store: MemoryStore, text: String): Int {
        val items = importJson(text)
        items.forEach { store.upsert(it) }
        return items.size
    }
}
