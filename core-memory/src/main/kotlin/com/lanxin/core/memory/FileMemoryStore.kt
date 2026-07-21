package com.lanxin.core.memory

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * JSON 文件持久化记忆。可整文件替换为 Room/ObjectBox 实现而不改上层接口。
 */
class FileMemoryStore(
    private val file: File,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }
) : MemoryStore {

    private val mutex = Mutex()
    private val map = LinkedHashMap<String, MemoryItem>()
    private var loaded = false

    private suspend fun ensureLoaded() {
        if (loaded) return
        withContext(Dispatchers.IO) {
            if (!file.exists()) {
                file.parentFile?.mkdirs()
                loaded = true
                return@withContext
            }
            try {
                val text = file.readText()
                if (text.isNotBlank()) {
                    val list = json.decodeFromString(ListSerializer(MemoryItem.serializer()), text)
                    map.clear()
                    list.forEach { map[it.id] = it }
                }
            } catch (_: Exception) {
                // corrupt → start empty; caller can re-import later
                map.clear()
            }
            loaded = true
        }
    }

    private suspend fun persist() {
        withContext(Dispatchers.IO) {
            file.parentFile?.mkdirs()
            val list = map.values.sortedByDescending { it.updatedAt }
            file.writeText(json.encodeToString(ListSerializer(MemoryItem.serializer()), list))
        }
    }

    override suspend fun upsert(item: MemoryItem) = mutex.withLock {
        ensureLoaded()
        map[item.id] = item.copy(updatedAt = System.currentTimeMillis())
        persist()
    }

    override suspend fun delete(id: String) = mutex.withLock {
        ensureLoaded()
        map.remove(id)
        persist()
    }

    override suspend fun list(limit: Int): List<MemoryItem> = mutex.withLock {
        ensureLoaded()
        map.values
            .filter { it.status == MemoryStatus.ACTIVE }
            .sortedByDescending { it.updatedAt }
            .take(limit)
    }

    override suspend fun search(query: String, limit: Int): List<MemoryItem> = mutex.withLock {
        ensureLoaded()
        val tokens = tokenize(query)
        if (tokens.isEmpty()) {
            return@withLock map.values
                .filter { it.status == MemoryStatus.ACTIVE }
                .sortedByDescending { it.importance }
                .take(limit)
        }
        map.values
            .filter { it.status == MemoryStatus.ACTIVE }
            .map { item -> item to score(item, tokens) }
            .filter { it.second > 0 }
            .sortedByDescending { it.second * 10 + it.first.importance }
            .take(limit)
            .map { (item, _) ->
                item.copy(lastAccessedAt = System.currentTimeMillis()).also { map[it.id] = it }
            }
            .also { persist() }
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
        val hay = (item.content + " " + item.tags.joinToString(" ") + " " + item.type).lowercase()
        return tokens.count { hay.contains(it) }
    }
}
