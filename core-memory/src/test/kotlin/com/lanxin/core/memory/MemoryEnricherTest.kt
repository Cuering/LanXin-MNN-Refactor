package com.lanxin.core.memory

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryEnricherTest {
    @Test
    fun injectsMatchingMemory() = runBlocking {
        val store = InMemoryMemoryStore()
        store.upsert(MemoryItem(id = "1", content = "哥哥喜欢喝美式咖啡", importance = 0.9f))
        val enricher = MemoryEnricher(store)
        val out = enricher.enrich("今天喝点什么", "你是兰儿。")
        assertTrue(out.contains("美式咖啡"))
    }
}
