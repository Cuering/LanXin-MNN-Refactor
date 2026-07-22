package com.lanxin.companion

import com.lanxin.core.memory.InMemoryMemoryStore
import com.lanxin.core.memory.MemoryEnricher
import com.lanxin.localllm.domain.ChatBackend
import com.lanxin.localllm.domain.ChatRoutePolicy
import com.lanxin.localllm.domain.EngineState
import com.lanxin.localllm.domain.LocalLlmEngine
import com.lanxin.localllm.domain.UnconfiguredCloudChatClient
import com.lanxin.voice.StubAsrEngine
import com.lanxin.voice.StubTtsEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 本地假引擎：记录最后 prompt，便于断言多轮历史已注入。 */
private class CapturePromptEngine : LocalLlmEngine {
    override var state: EngineState = EngineState.Uninitialized
        private set

    @Volatile
    var lastPrompt: String = ""
        private set

    override suspend fun load(modelPath: String): EngineState {
        state = EngineState.Ready(modelPath, backendHint = "fake-local")
        return state
    }

    override suspend fun generate(prompt: String, maxTokens: Int): String? {
        lastPrompt = prompt
        return "本地回：${prompt.lines().lastOrNull { it.startsWith("用户：") }?.removePrefix("用户：") ?: ""}"
            .take(maxTokens)
    }

    override suspend fun unload() {
        state = EngineState.Uninitialized
    }
}

/**
 * 保证：无云端配置时，本地可独立完成多轮对话。
 * 云端能力可保留，但不成为本地路径的前置依赖。
 */
class LocalStandaloneTest {

    private fun localSession(
        eng: LocalLlmEngine,
        hist: ConversationHistory = ConversationHistory(maxTurns = 10),
        policy: ChatRoutePolicy = ChatRoutePolicy.LOCAL_ONLY
    ): LocalCompanionSession {
        val mem = InMemoryMemoryStore()
        return LocalCompanionSession(
            engine = eng,
            memoryEnricher = MemoryEnricher(mem),
            memoryStore = mem,
            routePolicy = policy,
            cloudClient = UnconfiguredCloudChatClient(),
            asrEngine = StubAsrEngine(),
            ttsEngine = StubTtsEngine(simulateReady = true),
            autoSpeak = false,
            conversationHistory = hist
        )
    }

    @Test
    fun localOnly_noCloud_multiTurn_injectsHistory() = runBlocking {
        val eng = CapturePromptEngine()
        eng.load("/models/local-llm")
        val s = localSession(eng, policy = ChatRoutePolicy.LOCAL_ONLY)

        val r1 = s.chat("我喜欢草莓")
        assertTrue(r1.ok)
        assertEquals(ChatBackend.LOCAL, r1.backend)
        assertEquals(2, s.historyTurns.size)

        val r2 = s.chat("还记得吗")
        assertTrue(r2.ok)
        assertEquals(ChatBackend.LOCAL, r2.backend)
        assertTrue(eng.lastPrompt.contains("草莓"))
        assertTrue(eng.lastPrompt.contains("最近对话"))
        assertTrue(eng.lastPrompt.contains("还记得吗"))
        assertEquals(4, s.historyTurns.size)
    }

    @Test
    fun preferLocal_noCloud_stillUsesLocalWhenReady() = runBlocking {
        val eng = CapturePromptEngine()
        eng.load("/m")
        val s = localSession(eng, policy = ChatRoutePolicy.PREFER_LOCAL)
        val r = s.chat("你好")
        assertTrue(r.ok)
        assertEquals(ChatBackend.LOCAL, r.backend)
        assertFalse(s.historyTurns.isEmpty())
    }

    @Test
    fun localOnly_unloaded_failsHonestly_withoutCloud() = runBlocking {
        val eng = CapturePromptEngine() // 未 load
        val s = localSession(eng, policy = ChatRoutePolicy.LOCAL_ONLY)
        val r = s.chat("hi")
        assertFalse(r.ok)
        assertEquals(ChatBackend.NONE, r.backend)
        assertTrue(r.reply.contains("无可用后端") || r.routeReason.contains("LOCAL_ONLY"))
    }
}
