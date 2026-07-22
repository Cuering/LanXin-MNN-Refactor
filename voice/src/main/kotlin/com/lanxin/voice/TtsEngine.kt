package com.lanxin.voice

/**
 * 语音合成引擎接口（P5）。
 * 真实现后续可接 sherpa-onnx Offline TTS；当前提供显式 Stub。
 */
interface TtsEngine {
    val state: VoiceEngineState
    suspend fun load(modelPath: String?): VoiceEngineState
    suspend fun speak(text: String): TtsResult
    suspend fun stop()
    suspend fun unload()
}

data class TtsResult(
    val ok: Boolean,
    /** stub 不播真实音频，仅返回将要合成的清洗文本长度等 */
    val spokenChars: Int = 0,
    val detail: String? = null,
    /** 合成音频时长（毫秒）；native 可用时填充，stub 为 0 */
    val audioDurationMs: Long = 0L,
    /** 合成 PCM（native 可用时填充，供调用方自行播放或驱动嘴型） */
    val pcm16le: ByteArray? = null,
    val pcmSampleRate: Int = 0
)

/**
 * 显式 stub：状态永远 Stub，speak 只记日志语义成功（不伪装 Ready）。
 * 若 [simulateReady]=true，load 后进入 Ready 以便联调路由/UI（仍无真实音频）。
 */
class StubTtsEngine(
    private val reason: String = "explicit_tts_stub",
    private val simulateReady: Boolean = false
) : TtsEngine {
    override var state: VoiceEngineState = VoiceEngineState.Stub(reason)
        private set

    private var lastSpoken: String? = null

    val lastSpokenText: String? get() = lastSpoken

    override suspend fun load(modelPath: String?): VoiceEngineState {
        state = if (simulateReady) {
            VoiceEngineState.Ready(modelPath = modelPath, engineHint = "stub_sim")
        } else {
            VoiceEngineState.Stub("$reason path=${modelPath ?: "null"}")
        }
        return state
    }

    override suspend fun speak(text: String): TtsResult {
        val t = text.trim()
        if (t.isEmpty()) {
            return TtsResult(ok = false, detail = "empty_text")
        }
        // 即便 Stub 也允许「虚拟播报」，便于 companion 闭环联调；UI 必须显示 STUB/READY 真实 state
        lastSpoken = t
        return TtsResult(
            ok = true,
            spokenChars = t.length,
            detail = if (state is VoiceEngineState.Ready) "stub_sim_speak" else "stub_speak"
        )
    }

    override suspend fun stop() {
        // no-op
    }

    override suspend fun unload() {
        lastSpoken = null
        state = VoiceEngineState.Stub(reason)
    }
}
