package com.lanxin.voice.sherpa

import android.util.Log
import com.lanxin.voice.PcmAudioPlayer
import com.lanxin.voice.TtsEngine
import com.lanxin.voice.TtsResult
import com.lanxin.voice.VoiceEngineState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 真机 Sherpa-ONNX Offline TTS 引擎 —— 适配新项目 TtsEngine 接口。
 *
 * - native 可用：load → SherpaTtsBridge.loadModel / synthesize → [PcmAudioPlayer] 播放
 * - native 不可用或 load 失败：返回 NativeMissing / LoadFailed，不伪装 Ready
 * - stub:// 路径：返回 Stub 状态
 * - [autoPlay]=false 时只合成不播（单测 / 仅取 PCM）
 */
class SherpaTtsEngine(
    private val bridge: SherpaTtsBridge = SherpaTtsBridge(),
    private val player: PcmAudioPlayer = PcmAudioPlayer(),
    private val autoPlay: Boolean = true
) : TtsEngine {

    @Volatile
    override var state: VoiceEngineState = VoiceEngineState.Uninitialized
        private set

    @Volatile
    private var loadedPath: String? = null

    @Volatile
    private var usingNative: Boolean = false

    @Volatile
    private var lastPcm: ByteArray? = null

    @Volatile
    private var lastSampleRate: Int = 0

    val isUsingNative: Boolean get() = usingNative
    val lastPcm16le: ByteArray? get() = lastPcm
    val lastPcmSampleRate: Int get() = lastSampleRate

    override suspend fun load(modelPath: String?): VoiceEngineState = withContext(Dispatchers.IO) {
        if (modelPath.isNullOrBlank()) {
            // 无路径：保持 Stub，不伪装 Ready
            state = VoiceEngineState.Stub("model_path_empty")
            return@withContext state
        }
        state = VoiceEngineState.Loading

        if (modelPath.startsWith(SherpaOnnxBridge.STUB_SCHEME)) {
            loadedPath = modelPath
            usingNative = false
            state = VoiceEngineState.Stub("stub_path:$modelPath")
            return@withContext state
        }

        if (!bridge.validateModelPath(modelPath)) {
            state = VoiceEngineState.LoadFailed("model_dir_missing:$modelPath")
            return@withContext state
        }

        if (!bridge.isNativeAvailable()) {
            loadedPath = modelPath
            usingNative = false
            state = VoiceEngineState.NativeMissing(
                bridge.nativeLoadError() ?: "native_unavailable"
            )
            return@withContext state
        }

        val ok = bridge.loadModel(modelPath)
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

    override suspend fun speak(text: String): TtsResult = withContext(Dispatchers.IO) {
        val t = text.trim()
        if (t.isEmpty()) {
            return@withContext TtsResult(ok = false, detail = "empty_text")
        }

        if (state !is VoiceEngineState.Ready) {
            // Stub 状态也允许虚拟播报（与 StubTtsEngine 一致），便于联调
            if (state is VoiceEngineState.Stub) {
                return@withContext TtsResult(ok = true, spokenChars = t.length, detail = "stub_speak")
            }
            return@withContext TtsResult(
                ok = false,
                detail = "not_ready:${state.shortLabel}"
            )
        }

        if (!usingNative) {
            return@withContext TtsResult(ok = true, spokenChars = t.length, detail = "stub_speak")
        }

        val audio = bridge.synthesize(text = t, speakerId = 0, speed = 1.0f)
        if (audio == null) {
            return@withContext TtsResult(
                ok = false,
                detail = "native_synth_null:${bridge.lastError()}"
            )
        }

        val pcm = SherpaTtsBridge.floatToPcm16le(audio.samples)
        lastPcm = pcm
        lastSampleRate = audio.sampleRateHz
        val durationMs = SherpaTtsBridge.durationMs(audio.samples.size, audio.sampleRateHz)
        var playDetail = "no_play"
        if (autoPlay) {
            val play = player.play(pcm, audio.sampleRateHz)
            playDetail = if (play.isSuccess) {
                val info = play.getOrNull()!!
                "play:${if (info.stub) "stub" else "hw"}:${info.durationMs}ms"
            } else {
                "play_fail:${play.exceptionOrNull()?.message}"
            }
        }
        Log.i(
            TAG,
            "speak ok chars=${t.length} pcm=${pcm.size}B rate=${audio.sampleRateHz} ~${durationMs}ms $playDetail"
        )
        TtsResult(
            ok = true,
            spokenChars = t.length,
            detail = "native:${bridge.currentMode()}:${durationMs}ms;$playDetail",
            audioDurationMs = durationMs,
            pcm16le = pcm,
            pcmSampleRate = audio.sampleRateHz
        )
    }

    override suspend fun stop() {
        player.stop()
    }

    override suspend fun unload() = withContext(Dispatchers.IO) {
        bridge.unload()
        loadedPath = null
        usingNative = false
        lastPcm = null
        lastSampleRate = 0
        state = VoiceEngineState.Uninitialized
    }

    companion object {
        private const val TAG = "SherpaTtsEngine"
    }
}
