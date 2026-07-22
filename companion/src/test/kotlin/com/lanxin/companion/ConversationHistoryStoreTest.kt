package com.lanxin.companion

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.Rule
import java.io.File

class ConversationHistoryStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun inMemory_roundTrip() = runBlocking {
        val store = InMemoryConversationHistoryStore()
        store.save(
            listOf(
                ConversationTurn("用户", "hi"),
                ConversationTurn("兰儿", "在")
            )
        )
        val loaded = store.load()
        assertEquals(2, loaded.size)
        assertEquals("hi", loaded[0].content)
        store.clear()
        assertTrue(store.load().isEmpty())
    }

    @Test
    fun fileStore_roundTrip_and_corruptSafe() = runBlocking {
        val f = File(tmp.root, "conversation_history.json")
        val store = FileConversationHistoryStore(f)
        assertTrue(store.load().isEmpty())

        val turns = listOf(
            ConversationTurn("用户", "你好"),
            ConversationTurn("兰儿", "在呢哥哥"),
            ConversationTurn("用户", "吃了吗")
        )
        store.save(turns)
        assertTrue(f.exists())
        val loaded = store.load()
        assertEquals(3, loaded.size)
        assertEquals("在呢哥哥", loaded[1].content)

        // corrupt file → empty, no throw
        f.writeText("{not-json")
        assertTrue(FileConversationHistoryStore(f).load().isEmpty())
    }

    @Test
    fun history_replaceAll_then_format() {
        val h = ConversationHistory(maxTurns = 10)
        h.replaceAll(
            listOf(
                ConversationTurn("用户", "a"),
                ConversationTurn("兰儿", "b")
            )
        )
        assertEquals(2, h.size)
        assertEquals("用户：a\n兰儿：b", h.formatForPrompt())
    }
}
