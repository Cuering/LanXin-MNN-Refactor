package com.lanxin.voice.sherpa

import android.util.Log
import com.lanxin.voice.AsrEngine
import com.lanxin.voice.AsrResult
import com.lanxin.voice.VoiceEngineState
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 真机 Sherpa-ONNX ASR 引擎 —— 适配新项目 AsrEngine 接口。
 *
 * - native 可用：load → SherpaOnnxBridge.loadModel / transcribe
 * - native 不可用或 load 失败：返回 NativeMissing / LoadFailed，不伪装 Ready
 * - stub:// 路径：返回 Stub 状态（单测 / 无真模型）
 */
class SherpaAsrEngine(
    private val bridge: SherpaOnnxBridge = SherpaOnnxBridge()
) : AsrEngine {

    @Volatile
    override var state: VoiceEngineState = VoiceEngineState.Uninitialized
        private set

    @Volatile
    private var loadedPath: String? = null

    @Volatile
    private var usingNative: Boolean = false

    val isUsingNative: Boolean get() = usingNative

    override suspend fun load(modelPath: String?): VoiceEngineState = withContext(Dispatchers.IO) {
        if (modelPath.isNullOrBlank()) {
            state = VoiceEngineState.LoadFailed("model_path_empty")
            return@withContext state
        }
        state = VoiceEngineState.Loading

        // stub:// → Stub
        if (modelPath.startsWith(SherpaOnnxBridge.STUB_SCHEME)) {
            loadedPath = modelPath
            usingNative = false
            state = VoiceEngineState.Stub("stub_path:$modelPath")
            return@withContext state
        }

        // 路径校验
        if (!bridge.validateModelPath(modelPath)) {
            state = VoiceEngineState.LoadFailed("model_path_missing:$modelPath")
            return@withContext state
        }

        // native 可用？
        if (!bridge.isNativeAvailable()) {
            loadedPath = modelPath
            usingNative = false
            state = VoiceEngineState.NativeMissing(
                bridge.nativeLoadError() ?: "native_unavailable"
            )
            return@withContext state
        }

        val ok = bridge.loadModel(modelPath, "zh")
        if (ok) {
            loadedPath = modelPath
            usingNative = true
            state = VoiceEngineState.Ready(
                modelPath = modelPath,
                engineHint = "sherpa:${bridge.currentMode()}"
            )
        } else {
            loadedPath = null
            usingNative = false
            state = VoiceEngineState.LoadFailed(
                bridge.lastError() ?: "load_failed"
            )
        }
        state
    }

    override suspend fun transcribe(pcm16le: ByteArray?, hintText: String?): AsrResult =
        withContext(Dispatchers.IO) {
            // stub:// 或显式 Stub：允许 hint 联调（与 StubAsrEngine 一致，不伪装 Ready）
            if (state is VoiceEngineState.Stub) {
                return@withContext if (!hintText.isNullOrBlank()) {
                    AsrResult(ok = true, text = hintText.trim(), detail = "stub_hint")
                } else {
                    AsrResult(ok = false, text = null, detail = "stub_no_native")
                }
            }

            if (state !is VoiceEngineState.Ready) {
                // 联调旁路：无 so / 未 load 成功时仍可用 hint 走文本会话，不伪装 Ready
                if (!hintText.isNullOrBlank()) {
                    return@withContext AsrResult(
                        ok = true,
                        text = hintText.trim(),
                        detail = "hint_bypass_not_ready:${state.shortLabel}"
                    )
                }
                return@withContext AsrResult(
                    ok = false,
                    text = null,
                    detail = "not_ready:${state.shortLabel}"
                )
            }

            if (!usingNative) {
                return@withContext if (!hintText.isNullOrBlank()) {
                    AsrResult(ok = true, text = hintText.trim(), detail = "degraded_hint")
                } else {
                    AsrResult(ok = false, text = null, detail = "ready_but_no_native")
                }
            }

            val bytes = pcm16le ?: ByteArray(0)
            if (bytes.isEmpty()) {
                // 无 PCM 时可用 hint 兜底（调试）
                if (!hintText.isNullOrBlank()) {
                    return@withContext AsrResult(ok = true, text = hintText.trim(), detail = "hint_no_pcm")
                }
                return@withContext AsrResult(ok = true, text = "", detail = "empty_pcm")
            }
            val text = bridge.transcribe(bytes, 16_000)
            if (text != null) {
                AsrResult(ok = true, text = text, detail = "native:${bridge.currentMode()}")
            } else {
                AsrResult(
                    ok = false,
                    text = null,
                    detail = "native_transcribe_null:${bridge.lastError()}"
                )
            }
        }

    override suspend fun unload() = withContext(Dispatchers.IO) {
        bridge.unload()
        loadedPath = null
        usingNative = false
        state = VoiceEngineState.Uninitialized
    }
}
