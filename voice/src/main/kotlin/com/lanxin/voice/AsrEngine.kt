package com.lanxin.voice

/**
 * 语音识别引擎接口（P5）。
 * 真实现后续可接 sherpa-onnx；当前提供显式 Stub。
 */
interface AsrEngine {
    val state: VoiceEngineState
    suspend fun load(modelPath: String?): VoiceEngineState
    /** 传入 PCM 16k mono 短片段或占位；stub 返回固定文案。 */
    suspend fun transcribe(pcm16le: ByteArray? = null, hintText: String? = null): AsrResult
    suspend fun unload()
}

data class AsrResult(
    val ok: Boolean,
    val text: String?,
    val detail: String? = null
)

/**
 * 显式 stub：永远不是伪装 Ready。
 * [acceptHintAsResult]=true 时，把 hintText 当「识别结果」便于无麦联调。
 */
class StubAsrEngine(
    private val reason: String = "explicit_asr_stub",
    private val acceptHintAsResult: Boolean = true
) : AsrEngine {
    override var state: VoiceEngineState = VoiceEngineState.Stub(reason)
        private set

    override suspend fun load(modelPath: String?): VoiceEngineState {
        state = VoiceEngineState.Stub("$reason path=${modelPath ?: "null"}")
        return state
    }

    override suspend fun transcribe(pcm16le: ByteArray?, hintText: String?): AsrResult {
        if (acceptHintAsResult && !hintText.isNullOrBlank()) {
            return AsrResult(ok = true, text = hintText.trim(), detail = "stub_hint")
        }
        return AsrResult(
            ok = false,
            text = null,
            detail = "stub_no_native state=${state.shortLabel}"
        )
    }

    override suspend fun unload() {
        state = VoiceEngineState.Stub(reason)
    }
}
