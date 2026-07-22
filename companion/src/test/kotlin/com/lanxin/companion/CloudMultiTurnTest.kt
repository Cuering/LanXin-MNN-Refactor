package com.lanxin.companion

import com.lanxin.core.memory.InMemoryMemoryStore
import com.lanxin.core.memory.MemoryEnricher
import com.lanxin.localllm.domain.ChatBackend
import com.lanxin.localllm.domain.ChatRoutePolicy
import com.lanxin.localllm.domain.CloudRole
import com.lanxin.localllm.domain.EngineState
import com.lanxin.localllm.domain.LocalLlmEngine
import com.lanxin.localllm.domain.StubCloudChatClient
import com.lanxin.localllm.domain.StubLocalLlmEngine
import com.lanxin.voice.StubAsrEngine
import com.lanxin.voice.StubTtsEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class DeadLocalEngine : LocalLlmEngine {
    override var state: EngineState = EngineState.Stub("force_cloud")
        private set

    override suspend fun load(modelPath: String): EngineState = state
    override suspend fun generate(prompt: String, maxTokens: Int): String? = null
    override suspend fun unload() {}
}

class CloudMultiTurnTest {

    @Test
    fun buildCloudMessages_mapsRoles_andAppendsCurrent() {
        val hist = ConversationHistory(maxTurns = 10)
        hist.add("用户", "我喜欢草莓")
        hist.add("兰儿", "记下了")
        val s = LocalCompanionSession(
            engine = StubLocalLlmEngine("x"),
            memoryEnricher = MemoryEnricher(InMemoryMemoryStore()),
            conversationHistory = hist,
            autoSpeak = false
        )
        val msgs = s.buildCloudMessages("还记得吗")
        assertEquals(3, msgs.size)
        assertEquals(CloudRole.USER, msgs[0].role)
        assertEquals("我喜欢草莓", msgs[0].content)
        assertEquals(CloudRole.ASSISTANT, msgs[1].role)
        assertEquals("记下了", msgs[1].content)
        assertEquals(CloudRole.USER, msgs[2].role)
        assertEquals("还记得吗", msgs[2].content)
    }

    @Test
    fun cloudPath_sendsMultiTurnMessagesArray() = runBlocking {
        val cloud = StubCloudChatClient(replyPrefix = "云：")
        val hist = ConversationHistory(maxTurns = 10)
        hist.add("用户", "我叫哥哥")
        hist.add("兰儿", "好的哥哥")
        val s = LocalCompanionSession(
            engine = DeadLocalEngine(),
            memoryEnricher = MemoryEnricher(InMemoryMemoryStore()),
            routePolicy = ChatRoutePolicy.PREFER_LOCAL,
            cloudClient = cloud,
            asrEngine = StubAsrEngine(),
            ttsEngine = StubTtsEngine(simulateReady = true),
            autoSpeak = false,
            conversationHistory = hist
        )
        val r = s.chat("还在吗")
        assertTrue(r.ok)
        assertEquals(ChatBackend.CLOUD, r.backend)
        assertEquals(3, cloud.lastMessages.size)
        assertEquals(CloudRole.USER, cloud.lastMessages[0].role)
        assertEquals("我叫哥哥", cloud.lastMessages[0].content)
        assertEquals(CloudRole.ASSISTANT, cloud.lastMessages[1].role)
        assertEquals(CloudRole.USER, cloud.lastMessages[2].role)
        assertEquals("还在吗", cloud.lastMessages[2].content)
        assertTrue(cloud.lastSystem.isNotBlank())
        assertTrue(r.reply.contains("还在吗"))
        // 成功后历史再 +2
        assertEquals(4, s.historyTurns.size)
    }
}
