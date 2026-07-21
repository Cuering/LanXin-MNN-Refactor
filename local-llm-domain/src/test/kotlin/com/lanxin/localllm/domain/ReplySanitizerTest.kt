package com.lanxin.localllm.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplySanitizerTest {
    @Test
    fun stripsThinkAndMeta() {
        val raw = """
            <think>内部推理</think>
            你好呀哥哥
            系统已明确角色设定与输出规范，请遵守。
            [[mood=happy]]
        """.trimIndent()
        val cleaned = ReplySanitizer.clean(raw)
        assertTrue(cleaned.displayText.contains("你好"))
        assertFalse(cleaned.displayText.contains("think"))
        assertFalse(cleaned.displayText.contains("系统已明确"))
        assertFalse(cleaned.displayText.contains("mood"))
    }
}
