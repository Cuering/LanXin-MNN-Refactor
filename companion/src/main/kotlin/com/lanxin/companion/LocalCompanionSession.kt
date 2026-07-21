package com.lanxin.companion

import com.lanxin.core.memory.MemoryEnricher
import com.lanxin.core.memory.MemoryItem
import com.lanxin.core.memory.MemoryStore
import com.lanxin.core.memory.MemoryType
import com.lanxin.localllm.domain.EngineState
import com.lanxin.localllm.domain.LocalLlmEngine
import com.lanxin.localllm.domain.ReplySanitizer
import java.util.UUID

/**
 * 本地陪伴会话：记忆 enrich + 可选轻量记取 + 本地 LLM。
 */
class LocalCompanionSession(
    private val engine: LocalLlmEngine,
    private val memoryEnricher: MemoryEnricher,
    private val memoryStore: MemoryStore? = null,
    private val persona: String = "你是兰心/兰儿，温柔亲近的陪伴角色，用第一人称对用户说话。"
) {
    data class TurnResult(
        val reply: String,
        val engineState: EngineState,
        val ok: Boolean
    )

    suspend fun ensureLoaded(modelPath: String): EngineState {
        val s = engine.state
        if (s is EngineState.Ready && s.modelPath == modelPath) return s
        return engine.load(modelPath)
    }

    suspend fun chat(userMessage: String, maxTokens: Int = 256): TurnResult {
        val state = engine.state
        if (!state.isUsable) {
            return TurnResult(
                reply = "本地脑未就绪：${stateLabel(state)}",
                engineState = state,
                ok = false
            )
        }
        maybeCapturePreference(userMessage)
        val system = memoryEnricher.enrich(
            userMessage,
            "$persona\n${ReplySanitizer.NO_THINK_INSTRUCTION}"
        )
        val prompt = buildString {
            appendLine(system)
            appendLine()
            appendLine("用户：$userMessage")
            append("兰儿：")
        }
        val raw = engine.generate(prompt, maxTokens)
        if (raw.isNullOrBlank()) {
            return TurnResult(
                reply = "生成失败：${(engine.state as? EngineState.LoadFailed)?.detail ?: "empty"}",
                engineState = engine.state,
                ok = false
            )
        }
        return TurnResult(reply = raw, engineState = engine.state, ok = true)
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
