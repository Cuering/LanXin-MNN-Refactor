package com.lanxin.localllm.domain

import com.lanxin.localllm.core.MnnBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * MNN 真引擎。加载失败时状态为 [EngineState.LoadFailed] / [EngineState.NativeMissing]，
 * 不伪装 Ready。
 */
class MnnLocalLlmEngine(
    private val bridge: MnnBridge = MnnBridge()
) : LocalLlmEngine {

    @Volatile
    override var state: EngineState = EngineState.Uninitialized
        private set

    override suspend fun load(modelPath: String): EngineState = withContext(Dispatchers.IO) {
        state = EngineState.Loading
        if (!bridge.isNativeAvailable()) {
            val detail = bridge.nativeLoadError() ?: "native_unavailable"
            state = EngineState.NativeMissing(detail)
            return@withContext state
        }
        if (!bridge.validateModelPath(modelPath)) {
            state = EngineState.LoadFailed("model_path_missing:$modelPath")
            return@withContext state
        }
        val backendHint = readBackendHint(modelPath)
        val ok = bridge.loadModel(modelPath)
        state = if (ok) {
            EngineState.Ready(modelPath, backendHint)
        } else {
            EngineState.LoadFailed(bridge.lastError() ?: "load_failed")
        }
        state
    }

    override suspend fun generate(prompt: String, maxTokens: Int): String? =
        withContext(Dispatchers.IO) {
            if (state !is EngineState.Ready) return@withContext null
            val raw = bridge.generate(prompt, maxTokens) ?: return@withContext null
            ReplySanitizer.clean(raw).displayText
        }

    override suspend fun unload() = withContext(Dispatchers.IO) {
        bridge.unload()
        state = EngineState.Uninitialized
    }

    private fun readBackendHint(modelPath: String): String? {
        val dir = File(modelPath)
        val config = when {
            dir.isFile && dir.name.endsWith(".json") -> dir
            dir.isDirectory -> File(dir, "config.json")
            else -> File(dir.parentFile, "config.json")
        }
        if (!config.isFile) return null
        return try {
            val text = config.readText()
            Regex(""""backend_type"\s*:\s*"([^"]+)"""").find(text)?.groupValues?.getOrNull(1)
        } catch (_: Exception) {
            null
        }
    }
}
