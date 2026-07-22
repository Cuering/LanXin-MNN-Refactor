package com.lanxin.companion

import com.lanxin.core.memory.MemoryEnricher
import com.lanxin.core.memory.MemoryItem
import com.lanxin.core.memory.MemoryStore
import com.lanxin.core.memory.MemoryType
import com.lanxin.localllm.domain.ChatBackend
import com.lanxin.localllm.domain.ChatRoutePolicy
import com.lanxin.localllm.domain.ChatRouter
import com.lanxin.localllm.domain.CloudChatClient
import com.lanxin.localllm.domain.EngineState
import com.lanxin.localllm.domain.LocalLlmEngine
import com.lanxin.localllm.domain.ReplySanitizer
import com.lanxin.localllm.domain.UnconfiguredCloudChatClient
import com.lanxin.voice.AsrEngine
import com.lanxin.voice.AsrResult
import com.lanxin.voice.StubAsrEngine
import com.lanxin.voice.StubTtsEngine
import com.lanxin.voice.TtsEngine
import com.lanxin.voice.TtsResult
import com.lanxin.voice.VoiceEngineState
import java.util.UUID

/**
 * 本地陪伴会话：记忆 enrich + 多轮对话历史 + 本地/云端路由 + 可选 TTS。
 *
 * @param conversationHistory 多轮对话历史；传 null 禁用历史
 */
class LocalCompanionSession(
    private val engine: LocalLlmEngine,
    private val memoryEnricher: MemoryEnricher,
    private val memoryStore: MemoryStore? = null,
    private val persona: String = "你是兰心/兰儿，温柔亲近的陪伴角色，用第一人称对用户说话。",
    private val routePolicy: ChatRoutePolicy = ChatRoutePolicy.PREFER_LOCAL,
    private val cloudClient: CloudChatClient = UnconfiguredCloudChatClient(),
    private val asrEngine: AsrEngine = StubAsrEngine(),
    private val ttsEngine: TtsEngine = StubTtsEngine(),
    private val autoSpeak: Boolean = true,
    private val conversationHistory: ConversationHistory? = ConversationHistory()
) {
    private val router = ChatRouter(routePolicy)

    data class TurnResult(
        val reply: String,
        val engineState: EngineState,
        val ok: Boolean,
        val backend: ChatBackend = ChatBackend.NONE,
        val routeReason: String = "",
        val tts: TtsResult? = null
    )

    val asrState: VoiceEngineState get() = asrEngine.state
    val ttsState: VoiceEngineState get() = ttsEngine.state
    val policy: ChatRoutePolicy get() = routePolicy

    /** 对外暴露历史（只读），供 UI 展示 */
    val historyTurns: List<ConversationTurn> get() = conversationHistory?.turns ?: emptyList()

    suspend fun ensureLoaded(modelPath: String): EngineState {
        val s = engine.state
        if (s is EngineState.Ready && s.modelPath == modelPath) return s
        return engine.load(modelPath)
    }

    suspend fun ensureVoiceLoaded(asrPath: String? = null, ttsPath: String? = null) {
        asrEngine.load(asrPath)
        ttsEngine.load(ttsPath)
    }

    suspend fun clearHistory() {
        conversationHistory?.clear()
    }

    /**
     * 文本轮次：按路由策略选本地或云端，可选自动 TTS。
     * 自动维护多轮对话历史（若 [conversationHistory] 不为 null）。
     */
    suspend fun chat(userMessage: String, maxTokens: Int = 256): TurnResult {
        maybeCapturePreference(userMessage)
        // 先读历史（不含本轮），成功后再追加，避免当前用户消息重复进 prompt
        val historyBlock = conversationHistory?.formatForPrompt()?.let { h ->
            if (h.isNotBlank()) "\n--- 最近对话 ---\n$h\n---\n" else ""
        }.orEmpty()
        val system = memoryEnricher.enrich(
            userMessage,
            "$persona\n${ReplySanitizer.NO_THINK_INSTRUCTION}"
        )
        val localUsable = engine.state.isUsable
        val cloudOk = cloudClient.isConfigured
        val decision = router.decide(localUsable, cloudOk)
        if (decision.backend == ChatBackend.NONE) {
            return TurnResult(
                reply = "无可用后端：${decision.reason}；本地=${stateLabel(engine.state)} 云端配置=$cloudOk",
                engineState = engine.state,
                ok = false,
                backend = ChatBackend.NONE,
                routeReason = decision.reason
            )
        }
        var used = decision
        var reply = runBackend(used.backend, system, userMessage, maxTokens, historyBlock)
        if (reply == null) {
            val fb = router.fallback(used.backend, localUsable, cloudOk)
            if (fb != null) {
                used = fb
                reply = runBackend(fb.backend, system, userMessage, maxTokens, historyBlock)
            }
        }
        if (reply.isNullOrBlank()) {
            return TurnResult(
                reply = "生成失败：backend=${used.backend} ${used.reason}",
                engineState = engine.state,
                ok = false,
                backend = used.backend,
                routeReason = used.reason
            )
        }
        conversationHistory?.add("用户", userMessage)
        conversationHistory?.add("兰儿", reply)
        val ttsResult = if (autoSpeak) ttsEngine.speak(reply) else null
        return TurnResult(
            reply = reply,
            engineState = engine.state,
            ok = true,
            backend = used.backend,
            routeReason = used.reason,
            tts = ttsResult
        )
    }

    /**
     * 语音输入轮次：ASR（hint 或 pcm）→ chat。
     * 无真实 mic 时可用 [hintText] 走 stub ASR。
     */
    suspend fun chatFromVoice(
        hintText: String? = null,
        pcm16le: ByteArray? = null,
        maxTokens: Int = 256
    ): TurnResult {
        val asr: AsrResult = asrEngine.transcribe(pcm16le = pcm16le, hintText = hintText)
        if (!asr.ok || asr.text.isNullOrBlank()) {
            return TurnResult(
                reply = "ASR 失败：${asr.detail ?: asrState.shortLabel}",
                engineState = engine.state,
                ok = false,
                backend = ChatBackend.NONE,
                routeReason = "asr_failed"
            )
        }
        return chat(asr.text!!, maxTokens)
    }

    private suspend fun runBackend(
        backend: ChatBackend,
        system: String,
        userMessage: String,
        maxTokens: Int,
        historyBlock: String = ""
    ): String? {
        return when (backend) {
            ChatBackend.LOCAL -> {
                val prompt = buildString {
                    appendLine(system)
                    if (historyBlock.isNotBlank()) {
                        appendLine()
                        append(historyBlock)
                    }
                    appendLine()
                    appendLine("用户：$userMessage")
                    append("兰儿：")
                }
                engine.generate(prompt, maxTokens)?.takeIf { it.isNotBlank() }
            }
            ChatBackend.CLOUD -> {
                // 云端：system 后附历史（有限窗口，避免过长）
                val cloudSystem = if (historyBlock.isNotBlank()) {
                    system + "
" + historyBlock
                } else system
                val r = cloudClient.chat(cloudSystem, userMessage, maxTokens)
                if (r.ok) r.text?.let { ReplySanitizer.clean(it).displayText }.orEmpty().ifBlank { null }
                else null
            }
            ChatBackend.NONE -> null
        }
    }

    /** 极简规则：用户说「我喜欢/我爱/我叫」时落一条 preference/factual。 */
    private suspend fun maybeCapturePreference(userMessage: String) {
        val store = memoryStore ?: return
        val t = userMessage.trim()
        val patterns = listOf(
            Regex("""我(?:喜欢|爱)(.+)""") to MemoryType.PREFERENCE,
            Regex("""我(?:的名字|叫)(.+)""") to MemoryType.FACTUAL,
            Regex("""请记住[：:\s]*(.+)""") to MemoryType.INSTRUCTION
        )
        for ((re, type) in patterns) {
            val m = re.find(t) ?: continue
            val content = m.groupValues.getOrNull(1)?.trim()?.trim('。', '！', '!', '.', ' ') ?: continue
            if (content.length < 2 || content.length > 120) continue
            store.upsert(
                MemoryItem(
                    id = UUID.randomUUID().toString(),
                    content = content,
                    type = type,
                    importance = 0.85f
                )
            )
            break
        }
    }

    private fun stateLabel(s: EngineState): String = when (s) {
        is EngineState.Uninitialized -> "未初始化"
        is EngineState.Loading -> "加载中"
        is EngineState.Ready -> "就绪(${s.backendHint ?: "?"})"
        is EngineState.NativeMissing -> "native缺失:${s.detail}"
        is EngineState.LoadFailed -> "加载失败:${s.detail}"
        is EngineState.Stub -> "stub:${s.reason}"
    }
}
