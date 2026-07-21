package com.lanxin.companion

import com.lanxin.core.memory.MemoryEnricher
import com.lanxin.localllm.domain.EngineState
import com.lanxin.localllm.domain.LocalLlmEngine
import com.lanxin.localllm.domain.ReplySanitizer

/**
 * 本地陪伴会话：记忆 enrich + 本地 LLM 生成。
 * UI 无关，方便单测与替换。
 */
class LocalCompanionSession(
    private val engine: LocalLlmEngine,
    private val memoryEnricher: MemoryEnricher,
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

    private fun stateLabel(s: EngineState): String = when (s) {
        is EngineState.Uninitialized -> "未初始化"
        is EngineState.Loading -> "加载中"
        is EngineState.Ready -> "就绪(${s.backendHint ?: "?"})"
        is EngineState.NativeMissing -> "native缺失:${s.detail}"
        is EngineState.LoadFailed -> "加载失败:${s.detail}"
        is EngineState.Stub -> "stub:${s.reason}"
    }
}
