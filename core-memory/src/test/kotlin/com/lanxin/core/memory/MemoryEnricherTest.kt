package com.lanxin.core.memory

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryEnricherTest {
    @Test
    fun injectsMatchingMemory() = runBlocking {
        val store = InMemoryMemoryStore()
        store.upsert(MemoryItem(id = "1", content = "哥哥喜欢喝美式咖啡", importance = 0.9f))
        store.upsert(MemoryItem(id = "2", content = "无关记忆：天气晴", importance = 0.2f))
        val enricher = MemoryEnricher(store)
        val out = enricher.enrich("今天想喝咖啡", "你是兰儿。")
        assertTrue(out.contains("美式咖啡"))
    }
}
