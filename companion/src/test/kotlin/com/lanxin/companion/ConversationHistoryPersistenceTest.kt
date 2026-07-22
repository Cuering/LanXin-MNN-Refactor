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
import org.junit.Assert.assertTrue
import org.junit.Test

private class PersistFakeEngine : LocalLlmEngine {
    override var state: EngineState = EngineState.Uninitialized
        private set

    override suspend fun load(modelPath: String): EngineState {
        state = EngineState.Ready(modelPath, backendHint = "fake")
        return state
    }

    override suspend fun generate(prompt: String, maxTokens: Int): String? {
        // 若 prompt 含历史「草莓」，回「记得你喜欢草莓」
        val reply = if (prompt.contains("草莓")) "记得你喜欢草莓" else "收到"
        return reply.take(maxTokens)
    }

    override suspend fun unload() {
        state = EngineState.Uninitialized
    }
}

class ConversationHistoryPersistenceTest {

    @Test
    fun chat_persists_and_reload_sees_history() = runBlocking {
        val store = InMemoryConversationHistoryStore()
        val eng = PersistFakeEngine().also { it.load("/m") }
        val mem = InMemoryMemoryStore()

        val s1 = LocalCompanionSession(
            engine = eng,
            memoryEnricher = MemoryEnricher(mem),
            memoryStore = mem,
            routePolicy = ChatRoutePolicy.PREFER_LOCAL,
            cloudClient = UnconfiguredCloudChatClient(),
            asrEngine = StubAsrEngine(),
            ttsEngine = StubTtsEngine(simulateReady = true),
            autoSpeak = false,
            conversationHistory = ConversationHistory(maxTurns = 10),
            historyStore = store
        )
        val r1 = s1.chat("我喜欢草莓")
        assertTrue(r1.ok)
        assertEquals(ChatBackend.LOCAL, r1.backend)
        assertEquals(2, store.load().size)

        // 新会话 + 同一 store，应 load 出历史并注入 prompt
        val s2 = LocalCompanionSession(
            engine = eng,
            memoryEnricher = MemoryEnricher(mem),
            memoryStore = mem,
            routePolicy = ChatRoutePolicy.PREFER_LOCAL,
            cloudClient = UnconfiguredCloudChatClient(),
            asrEngine = StubAsrEngine(),
            ttsEngine = StubTtsEngine(simulateReady = true),
            autoSpeak = false,
            conversationHistory = ConversationHistory(maxTurns = 10),
            historyStore = store
        )
        val n = s2.loadHistory()
        assertEquals(2, n)
        assertEquals("用户", s2.historyTurns[0].role)
        assertTrue(s2.historyTurns[0].content.contains("草莓"))

        val r2 = s2.chat("还记得吗")
        assertTrue(r2.ok)
        assertTrue(r2.reply.contains("草莓"))
        assertEquals(4, store.load().size)
    }

    @Test
    fun clearHistory_wipesStore() = runBlocking {
        val store = InMemoryConversationHistoryStore()
        val eng = PersistFakeEngine().also { it.load("/m") }
        val mem = InMemoryMemoryStore()
        val s = LocalCompanionSession(
            engine = eng,
            memoryEnricher = MemoryEnricher(mem),
            conversationHistory = ConversationHistory(),
            historyStore = store,
            autoSpeak = false
        )
        s.chat("hi")
        assertEquals(2, store.load().size)
        s.clearHistory()
        assertTrue(store.load().isEmpty())
        assertEquals(0, s.historyTurns.size)
    }
}
