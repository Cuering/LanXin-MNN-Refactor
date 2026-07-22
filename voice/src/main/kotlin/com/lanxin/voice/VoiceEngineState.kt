package com.lanxin.voice

/**
 * ASR/TTS 可观测状态 —— 与 LocalLlm 一致：禁止「UI READY 实际 stub」。
 */
sealed class VoiceEngineState {
    data object Uninitialized : VoiceEngineState()
    data object Loading : VoiceEngineState()
    data class Ready(val modelPath: String?, val engineHint: String?) : VoiceEngineState()
    data class NativeMissing(val detail: String) : VoiceEngineState()
    data class LoadFailed(val detail: String) : VoiceEngineState()
    data class Stub(val reason: String) : VoiceEngineState()

    val isUsable: Boolean get() = this is Ready

    val shortLabel: String
        get() = when (this) {
            is Uninitialized -> "未初始化"
            is Loading -> "加载中"
            is Ready -> "READY(${engineHint ?: "?"})"
            is NativeMissing -> "NATIVE_MISSING"
            is LoadFailed -> "LOAD_FAILED"
            is Stub -> "STUB"
        }
}
