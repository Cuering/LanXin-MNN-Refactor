package com.lanxin.localllm.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        assertFalse(cleaned.displayText.contains("think", ignoreCase = true))
        assertFalse(cleaned.displayText.contains("系统已明确"))
        assertFalse(cleaned.displayText.contains("mood"))
        assertFalse(cleaned.displayText.contains("[["))
    }

    @Test
    fun showThinkingKeepsThinkingButDisplayClean() {
        val raw = "<think>plan A</think>\n正文[[mood=smile]]"
        val cleaned = ReplySanitizer.clean(raw, showThinking = true)
        assertEquals("plan A", cleaned.thinkingText)
        assertEquals("正文", cleaned.displayText)
    }

    @Test
    fun unclosedThinkStripped() {
        val raw = "可见前缀\n<think>\n还在想\n没有闭合"
        val cleaned = ReplySanitizer.clean(raw, showThinking = false)
        assertEquals("可见前缀", cleaned.displayText)
        assertNull(cleaned.thinkingText)
    }

    @Test
    fun stripsUntaggedMetaAnalysisKeepsSuggested() {
        val raw = """
            让我分析一下这个问题：

            1. 用户是兰心（AI），用户说"你好"，我应该回应欢迎和友好

            查看可用工具：没有专门处理问候语的 tool

            ## 回应建议

            欢迎来到美丽的世界！我是一名智能助手，很高兴能为你提供支持。

            ---

            **分析：**
            - "你好"是问候语
            - 不需要调用工具

            （系统时间：2026-07-21 13:21）
        """.trimIndent()
        val cleaned = ReplySanitizer.clean(raw, showThinking = false)
        assertTrue(
            "应保留回应建议正文，实际=${cleaned.displayText}",
            cleaned.displayText.contains("欢迎") || cleaned.displayText.contains("美丽")
        )
        assertFalse(cleaned.displayText.contains("让我分析"))
        assertFalse(cleaned.displayText.contains("greeting_tool") || cleaned.displayText.contains("查看可用工具"))
        assertFalse(cleaned.displayText.contains("系统时间"))
        assertFalse(cleaned.displayText.contains("**分析**") || cleaned.displayText.contains("分析："))
    }

    @Test
    fun forSpeechStripsEmojiButDisplayMayKeep() {
        val withEmoji = "你好呀～ 欢迎！很高兴见到你 " +
            String(Character.toChars(0x1F31F)) +
            String(Character.toChars(0x1F496))
        val display = ReplySanitizer.forDisplay(withEmoji)
        val speech = ReplySanitizer.forSpeech(withEmoji)
        assertTrue(display.contains("你好"))
        assertTrue(speech.contains("你好"))
        assertFalse(speech.contains(String(Character.toChars(0x1F31F))))
        assertFalse(speech.contains(String(Character.toChars(0x1F496))))
    }

    @Test
    fun forSpeechStripsThinkAndTags() {
        val raw = "<think>内部计划</think>\n嗯嗯，我在呢[[mood=joy]]" +
            String(Character.toChars(0x1F60A))
        val speech = ReplySanitizer.forSpeech(raw)
        assertTrue(speech.contains("我在") || speech.contains("嗯"))
        assertFalse(speech.contains("think", ignoreCase = true))
        assertFalse(speech.contains("[["))
        assertFalse(speech.contains(String(Character.toChars(0x1F60A))))
        assertFalse(speech.contains("内部计划"))
    }

    @Test
    fun stripsAngleMetaTagsLikeMeiju() {
        val raw = "呜...哥哥是不是讨厌我了🥺<好感变化:-2><信任变化:-1><当前心情:[伤心]>"
        val display = ReplySanitizer.forDisplay(raw)
        assertTrue(display.contains("哥哥") || display.contains("讨厌"))
        assertFalse(display.contains("好感变化"))
        assertFalse(display.contains("信任变化"))
        assertFalse(display.contains("当前心情"))
        val speech = ReplySanitizer.forSpeech(raw)
        assertFalse(speech.contains(String(Character.toChars(0x1F97A)))) // 🥺
    }

    @Test
    fun appendOutputConstraintOnlyWhenThinkingOff() {
        val off = ReplySanitizer.appendOutputConstraint(null, showThinking = false)
        assertTrue(off!!.contains("不要输出"))
        assertTrue(off.contains("emoji") || off.contains("表情") || off.contains("兰心"))
        val on = ReplySanitizer.appendOutputConstraint("sys", showThinking = true)
        assertEquals("sys", on)
    }

    @Test
    fun actionTagListenStripped() {
        val d = ReplySanitizer.forDisplay("嗯[[listen]]好")
        assertTrue(d.contains("嗯"))
        assertTrue(d.contains("好"))
        assertFalse(d.contains("listen"))
        assertFalse(d.contains("[["))
    }

    @Test
    fun stripsBareThinkingWithoutTags() {
        val raw = """
            让我分析一下用户说的是什么。
            用户意图是打招呼，所以我应该友好回应。
            哥哥好呀，我是兰儿，今天想聊点什么？
        """.trimIndent()
        val cleaned = ReplySanitizer.clean(raw, showThinking = false)
        assertTrue(cleaned.displayText.contains("兰儿") || cleaned.displayText.contains("哥哥"))
        assertFalse(cleaned.displayText.contains("让我分析"))
        assertFalse(cleaned.displayText.contains("用户意图"))
        assertFalse(cleaned.displayText.contains("所以我应该"))
    }

    @Test
    fun noThinkInstructionForbidsUntaggedThinking() {
        assertTrue(ReplySanitizer.NO_THINK_INSTRUCTION.contains("禁止思考外泄") ||
            ReplySanitizer.NO_THINK_INSTRUCTION.contains("无标签思考"))
        assertTrue(ReplySanitizer.NO_THINK_INSTRUCTION.contains("第一句就必须是对用户说的话") ||
            ReplySanitizer.NO_THINK_INSTRUCTION.contains("开场第一句"))
    }

}
