package com.lanxin.core.memory

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class FileMemoryStoreTest {
    @Test
    fun persistsAcrossInstances() = runBlocking {
        val dir = createTempDirectory("mem-test").toFile()
        try {
            val file = File(dir, "memories.json")
            val a = FileMemoryStore(file)
            a.upsert(MemoryItem(id = "1", content = "喜欢美式咖啡", type = MemoryType.PREFERENCE))
            val b = FileMemoryStore(file)
            val hits = b.search("咖啡", 5)
            assertEquals(1, hits.size)
            assertTrue(hits[0].content.contains("美式"))
        } finally {
            dir.deleteRecursively()
        }
    }
}
