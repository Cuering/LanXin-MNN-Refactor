package com.lanxin.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationHistoryTest {

    @Test
    fun empty_formatIsBlank() {
        val h = ConversationHistory()
        assertEquals("", h.formatForPrompt())
        assertEquals(0, h.size)
    }

    @Test
    fun add_and_format_order() {
        val h = ConversationHistory(maxTurns = 10)
        h.add("用户", "你好")
        h.add("兰儿", "在呢")
        h.add("用户", "吃了吗")
        val text = h.formatForPrompt()
        assertEquals(
            "用户：你好\n兰儿：在呢\n用户：吃了吗",
            text
        )
        assertEquals(3, h.size)
    }

    @Test
    fun slidingWindow_dropsOldest() {
        val h = ConversationHistory(maxTurns = 4)
        repeat(6) { i -> h.add("用户", "m$i") }
        assertEquals(4, h.size)
        assertEquals("m2", h.turns.first().content)
        assertEquals("m5", h.turns.last().content)
    }

    @Test
    fun clear_resets() {
        val h = ConversationHistory()
        h.add("用户", "x")
        h.clear()
        assertEquals(0, h.size)
        assertEquals("", h.formatForPrompt())
    }

    @Test
    fun recent_returnsLastN() {
        val h = ConversationHistory()
        repeat(5) { h.add("用户", "t$it") }
        val r = h.recent(2)
        assertEquals(2, r.size)
        assertEquals("t3", r[0].content)
        assertEquals("t4", r[1].content)
    }

    @Test
    fun trimContent_onAdd() {
        val h = ConversationHistory()
        h.add("用户", "  hello  ")
        assertEquals("hello", h.turns.single().content)
    }

    @Test
    fun replaceAll_trimsToCapacity() {
        val h = ConversationHistory(maxTurns = 3)
        h.replaceAll(
            listOf(
                ConversationTurn("用户", "a"),
                ConversationTurn("兰儿", "b"),
                ConversationTurn("用户", "c"),
                ConversationTurn("兰儿", "d")
            )
        )
        assertEquals(3, h.size)
        assertEquals("b", h.turns.first().content)
        assertEquals("d", h.turns.last().content)
    }
}
