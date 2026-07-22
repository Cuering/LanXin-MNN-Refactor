package com.lanxin.voice

/**
 * 优先原生 TTS（Sherpa 等），失败或未 READY 时回退到 [fallback]（通常为系统 TTS）。
 *
 * 目标：先打通「文字 → 出声」；有模型走真机合成，无模型也能播。
 */
class PreferNativeTtsEngine(
    private val primary: TtsEngine,
    private val fallback: TtsEngine
) : TtsEngine {

    @Volatile
    private var lastSource: String = "none"

    val lastSpeakSource: String get() = lastSource
    val primaryState: VoiceEngineState get() = primary.state
    val fallbackState: VoiceEngineState get() = fallback.state

    override val state: VoiceEngineState
        get() = when {
            primary.state.isUsable -> primary.state
            fallback.state.isUsable -> fallback.state
            primary.state is VoiceEngineState.Loading ||
                fallback.state is VoiceEngineState.Loading -> VoiceEngineState.Loading
            primary.state is VoiceEngineState.LoadFailed -> primary.state
            fallback.state is VoiceEngineState.LoadFailed -> fallback.state
            primary.state is VoiceEngineState.NativeMissing -> {
                // 有回退可用时不挡住 UI；否则暴露 native missing
                if (fallback.state is VoiceEngineState.Stub ||
                    fallback.state is VoiceEngineState.Uninitialized
                ) {
                    primary.state
                } else {
                    primary.state
                }
            }
            else -> primary.state
        }

    override suspend fun load(modelPath: String?): VoiceEngineState {
        primary.load(modelPath)
        // 系统 TTS 不依赖路径
        fallback.load(null)
        return state
    }

    override suspend fun speak(text: String): TtsResult {
        val t = text.trim()
        if (t.isEmpty()) {
            return TtsResult(ok = false, detail = "empty_text")
        }
        if (primary.state.isUsable) {
            val r = primary.speak(t)
            if (r.ok) {
                lastSource = "primary"
                return r
            }
            // 原生合成失败 → 尝试回退
        }
        if (fallback.state.isUsable) {
            val r = fallback.speak(t)
            lastSource = if (r.ok) "fallback" else "fallback_fail"
            return if (r.ok) {
                r.copy(detail = "fallback:${r.detail ?: "ok"}")
            } else {
                r
            }
        }
        lastSource = "none"
        return TtsResult(
            ok = false,
            detail = "no_tts primary=${primary.state.shortLabel} fallback=${fallback.state.shortLabel}"
        )
    }

    override suspend fun stop() {
        runCatching { primary.stop() }
        runCatching { fallback.stop() }
    }

    override suspend fun unload() {
        runCatching { primary.unload() }
        runCatching { fallback.unload() }
        lastSource = "none"
    }
}
