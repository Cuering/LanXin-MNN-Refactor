package com.lanxin.localllm.domain

/**
 * 显式 stub：仅用于 JVM 单测或演示，状态永远不是伪装的 Ready。
 */
class StubLocalLlmEngine(
    private val reason: String = "explicit_stub"
) : LocalLlmEngine {
    override var state: EngineState = EngineState.Stub(reason)
        private set

    override suspend fun load(modelPath: String): EngineState {
        state = EngineState.Stub("$reason path=$modelPath")
        return state
    }

    override suspend fun generate(prompt: String, maxTokens: Int): String? =
        "（本地 stub：$reason）收到：${prompt.take(80)}"

    override suspend fun unload() {
        state = EngineState.Stub(reason)
    }
}
