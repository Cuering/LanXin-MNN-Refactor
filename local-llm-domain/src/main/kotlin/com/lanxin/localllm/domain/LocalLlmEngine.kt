package com.lanxin.localllm.domain

/**
 * 本地 LLM 领域接口（可替换实现：MNN / stub / 未来其它引擎）。
 */
interface LocalLlmEngine {
    val state: EngineState
    suspend fun load(modelPath: String): EngineState
    suspend fun generate(prompt: String, maxTokens: Int = 256): String?
    suspend fun unload()
}
