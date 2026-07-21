package com.lanxin.localllm.domain

/**
 * 可观测引擎状态 —— 禁止“UI 显示 READY 实际 stub”。
 */
sealed class EngineState {
    data object Uninitialized : EngineState()
    data object Loading : EngineState()
    data class Ready(val modelPath: String, val backendHint: String?) : EngineState()
    data class NativeMissing(val detail: String) : EngineState()
    data class LoadFailed(val detail: String) : EngineState()
    data class Stub(val reason: String) : EngineState()

    val isUsable: Boolean get() = this is Ready
}
