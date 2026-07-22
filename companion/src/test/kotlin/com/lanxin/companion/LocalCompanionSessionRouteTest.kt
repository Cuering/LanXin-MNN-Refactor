package com.lanxin.companion

import com.lanxin.core.memory.InMemoryMemoryStore
import com.lanxin.core.memory.MemoryEnricher
import com.lanxin.localllm.domain.ChatBackend
import com.lanxin.localllm.domain.ChatRoutePolicy
import com.lanxin.localllm.domain.EngineState
import com.lanxin.localllm.domain.LocalLlmEngine
import com.lanxin.localllm.domain.StubCloudChatClient
import com.lanxin.localllm.domain.StubLocalLlmEngine
import com.lanxin.localllm.domain.UnconfiguredCloudChatClient
import com.lanxin.voice.StubAsrEngine
import com.lanxin.voice.StubTtsEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 本地可用假引擎：状态 Ready，generate 固定回包。 */
private class FakeReadyEngine : LocalLlmEngine {
    override var state: EngineState = EngineState.Uninitialized
        private set

    override suspend fun load(modelPath: String): EngineState {
        state = EngineState.Ready(modelPath, backendHint = "fake")
        return state
    }

    override suspend fun generate(prompt: String, maxTokens: Int): String? =
        "本地回：${prompt.lines().lastOrNull() ?: ""}".take(maxTokens)

    override suspend fun unload() {
        state = EngineState.Uninitialized
    }
}

class LocalCompanionSessionRouteTest {

    private fun session(
        engine: LocalLlmEngine,
        policy: ChatRoutePolicy,
        cloud: com.lanxin.localllm.domain.CloudChatClient
    ): LocalCompanionSession {
        val store = InMemoryMemoryStore()
        return LocalCompanionSession(
            engine = engine,
            memoryEnricher = MemoryEnricher(store),
            memoryStore = store,
            routePolicy = policy,
            cloudClient = cloud,
            asrEngine = StubAsrEngine(),
            ttsEngine = StubTtsEngine(simulateReady = true),
            autoSpeak = true
        )
    }

    @Test
    fun preferLocal_usesLocalWhenReady() = runBlocking {
        val eng = FakeReadyEngine()
        eng.load("/m")
        val s = session(eng, ChatRoutePolicy.PREFER_LOCAL, UnconfiguredCloudChatClient())
        val r = s.chat("你好")
        assertTrue(r.ok)
        assertEquals(ChatBackend.LOCAL, r.backend)
        assertTrue(r.reply.contains("本地回") || r.reply.isNotBlank())
        assertTrue(r.tts?.ok == true)
    }

    @Test
    fun preferLocal_fallsToCloudWhenLocalStub() = runBlocking {
        val s = session(
            StubLocalLlmEngine("no_native"),
            ChatRoutePolicy.PREFER_LOCAL,
            StubCloudChatClient("云：")
        )
        val r = s.chat("在吗")
        assertTrue(r.ok)
        assertEquals(ChatBackend.CLOUD, r.backend)
        assertTrue(r.reply.contains("在吗"))
    }

    @Test
    fun noBackend_reportsFailure() = runBlocking {
        val s = session(
            StubLocalLlmEngine("x"),
            ChatRoutePolicy.LOCAL_ONLY,
            UnconfiguredCloudChatClient()
        )
        val r = s.chat("hi")
        assertFalse(r.ok)
        assertEquals(ChatBackend.NONE, r.backend)
    }

    @Test
    fun voicePath_usesAsrHint() = runBlocking {
        val eng = FakeReadyEngine()
        eng.load("/m")
        val s = session(eng, ChatRoutePolicy.PREFER_LOCAL, UnconfiguredCloudChatClient())
        val r = s.chatFromVoice(hintText = "我喜欢草莓")
        assertTrue(r.ok)
        assertEquals(ChatBackend.LOCAL, r.backend)
    }
}
