package com.lanxin.core.memory

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryImportExportTest {
    @Test
    fun roundTripAndMerge() = runBlocking {
        val original = listOf(
            MemoryItem(id = "a", content = "喜欢美式", type = MemoryType.PREFERENCE, importance = 0.9f),
            MemoryItem(id = "b", content = "叫我哥哥", type = MemoryType.INSTRUCTION)
        )
        val json = MemoryImportExport.exportJson(original)
        assertTrue(json.contains("美式"))
        val parsed = MemoryImportExport.importJson(json)
        assertEquals(2, parsed.size)

        val store = InMemoryMemoryStore()
        val n = MemoryImportExport.mergeInto(store, json)
        assertEquals(2, n)
        assertEquals(2, store.list(10).size)

        // 覆盖同 id
        val patched = """[{"id":"a","content":"改成拿铁","type":"preference","importance":0.8}]"""
        MemoryImportExport.mergeInto(store, patched)
        val hits = store.search("拿铁", 5)
        assertEquals(1, hits.size)
        assertTrue(hits[0].content.contains("拿铁"))
        Unit
    }

    @Test
    fun emptyAndUnknownKeys() {
        assertTrue(MemoryImportExport.importJson("").isEmpty())
        val withExtra = """[{"id":"x","content":"ok","extra_old_field":123}]"""
        val items = MemoryImportExport.importJson(withExtra)
        assertEquals(1, items.size)
        assertEquals("ok", items[0].content)
    }
}
