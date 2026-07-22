package com.lanxin.voice.sherpa

import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineParaformerModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Sherpa-ONNX JNI 接入点（ASR）。
 *
 * - 运行时 so 由官方 AAR 打进 APK
 * - ASR 模型外置：设备 /sdcard/Android/data/.../files/models/asr/<model-dir>/
 * - JVM 单测：无 so 时 isNativeAvailable() 为 false；loadModel/transcribe 安全降级
 *
 * 支持目录布局：
 * 1. 流式 zipformer transducer：encoder*.onnx + decoder*.onnx + joiner*.onnx + tokens.txt
 * 2. 离线 paraformer：model*.onnx 或 paraformer*.onnx + tokens.txt
 */
class SherpaOnnxBridge {

    @Volatile
    private var online: OnlineRecognizer? = null

    @Volatile
    private var offline: OfflineRecognizer? = null

    @Volatile
    private var mode: Mode = Mode.NONE

    @Volatile
    private var lastLoadError: String? = null

    enum class Mode { NONE, ONLINE_TRANSDUCER, OFFLINE_PARAFORMER }

    fun isNativeAvailable(): Boolean = tryLoadNative()
    fun nativeLoadError(): String? = companionNativeLoadError()
    fun currentMode(): Mode = mode
    fun lastError(): String? = lastLoadError

    fun validateModelPath(path: String): Boolean {
        if (path.isBlank()) return false
        if (path.startsWith(STUB_SCHEME)) return true
        return File(path).exists()
    }

    @Synchronized
    fun loadModel(path: String, language: String): Boolean {
        lastLoadError = null
        if (path.startsWith(STUB_SCHEME)) {
            lastLoadError = "stub_path_no_native"
            return false
        }
        if (!tryLoadNative()) {
            lastLoadError = companionNativeLoadError() ?: "native_unavailable"
            return false
        }
        val root = File(path)
        if (!root.exists()) {
            lastLoadError = "model_path_missing:$path"
            return false
        }
        unloadInternal()
        return try {
            val layout = detectLayout(root)
            when (layout) {
                is ModelLayout.OnlineTransducer -> {
                    online = createOnline(layout)
                    mode = Mode.ONLINE_TRANSDUCER
                    Log.i(TAG, "loaded online transducer lang=$language dir=${root.name}")
                    true
                }
                is ModelLayout.OfflineParaformer -> {
                    offline = createOfflineParaformer(layout)
                    mode = Mode.OFFLINE_PARAFORMER
                    Log.i(TAG, "loaded offline paraformer lang=$language dir=${root.name}")
                    true
                }
                null -> {
                    lastLoadError = "unsupported_model_layout:$path"
                    false
                }
            }
        } catch (t: Throwable) {
            unloadInternal()
            lastLoadError = "load_failed:${t.javaClass.simpleName}:${t.message}"
            Log.e(TAG, "loadModel failed", t)
            false
        }
    }

    @Synchronized
    fun transcribe(pcm16leMono: ByteArray, sampleRateHz: Int): String? {
        if (pcm16leMono.isEmpty()) return ""
        val samples = pcm16leToFloat(pcm16leMono)
        return try {
            when (mode) {
                Mode.ONLINE_TRANSDUCER -> {
                    val rec = online ?: return null
                    val stream = rec.createStream()
                    try {
                        stream.acceptWaveform(samples, sampleRateHz)
                        stream.inputFinished()
                        while (rec.isReady(stream)) rec.decode(stream)
                        rec.getResult(stream).text.trim()
                    } finally {
                        stream.release()
                    }
                }
                Mode.OFFLINE_PARAFORMER -> {
                    val rec = offline ?: return null
                    val stream = rec.createStream()
                    try {
                        stream.acceptWaveform(samples, sampleRateHz)
                        rec.decode(stream)
                        rec.getResult(stream).text.trim()
                    } finally {
                        stream.release()
                    }
                }
                Mode.NONE -> null
            }
        } catch (t: Throwable) {
            lastLoadError = "transcribe_failed:${t.javaClass.simpleName}:${t.message}"
            Log.e(TAG, "transcribe failed", t)
            null
        }
    }

    @Synchronized
    fun unload() {
        unloadInternal()
    }

    private fun unloadInternal() {
        try { online?.release() } catch (_: Throwable) {}
        try { offline?.release() } catch (_: Throwable) {}
        online = null
        offline = null
        mode = Mode.NONE
    }

    private sealed class ModelLayout {
        data class OnlineTransducer(
            val encoder: String, val decoder: String,
            val joiner: String, val tokens: String
        ) : ModelLayout()

        data class OfflineParaformer(
            val model: String, val tokens: String
        ) : ModelLayout()
    }

    private fun detectLayout(root: File): ModelLayout? {
        val dir = if (root.isDirectory) root else root.parentFile ?: return null
        val tokens = firstExisting(File(dir, "tokens.txt"), File(dir, "tokens")) ?: return null

        val encoder = findModelFile(dir, listOf("encoder"))
        val decoder = findModelFile(dir, listOf("decoder"))
        val joiner = findModelFile(dir, listOf("joiner"))
        if (encoder != null && decoder != null && joiner != null) {
            return ModelLayout.OnlineTransducer(
                encoder.absolutePath, decoder.absolutePath,
                joiner.absolutePath, tokens.absolutePath
            )
        }

        val para = findModelFile(dir, listOf("model", "paraformer"))
            ?: dir.listFiles()?.firstOrNull { f ->
                f.isFile && f.name.endsWith(".onnx", true) &&
                    !f.name.contains("encoder", true) &&
                    !f.name.contains("decoder", true) &&
                    !f.name.contains("joiner", true)
            }
        if (para != null) {
            return ModelLayout.OfflineParaformer(para.absolutePath, tokens.absolutePath)
        }
        return null
    }

    private fun findModelFile(dir: File, prefixes: List<String>): File? {
        val onnx = dir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".onnx", true) }.orEmpty()
        for (p in prefixes) {
            onnx.firstOrNull { it.name.startsWith(p, true) && it.name.contains("int8", true) }?.let { return it }
            onnx.firstOrNull { it.name.startsWith(p, true) }?.let { return it }
        }
        return null
    }

    private fun firstExisting(vararg files: File): File? = files.firstOrNull { it.exists() }

    private fun createOnline(layout: ModelLayout.OnlineTransducer): OnlineRecognizer {
        val modelConfig = OnlineModelConfig()
        modelConfig.transducer = OnlineTransducerModelConfig(
            encoder = layout.encoder, decoder = layout.decoder, joiner = layout.joiner
        )
        modelConfig.tokens = layout.tokens
        modelConfig.numThreads = 2
        modelConfig.provider = "cpu"
        modelConfig.modelType = "zipformer"
        val config = OnlineRecognizerConfig()
        config.featConfig = FeatureConfig(sampleRate = 16_000, featureDim = 80)
        config.modelConfig = modelConfig
        config.enableEndpoint = true
        config.decodingMethod = "greedy_search"
        return OnlineRecognizer(assetManager = null, config = config)
    }

    private fun createOfflineParaformer(layout: ModelLayout.OfflineParaformer): OfflineRecognizer {
        val modelConfig = OfflineModelConfig()
        modelConfig.paraformer = OfflineParaformerModelConfig(model = layout.model)
        modelConfig.tokens = layout.tokens
        modelConfig.numThreads = 2
        modelConfig.provider = "cpu"
        modelConfig.modelType = "paraformer"
        val config = OfflineRecognizerConfig()
        config.featConfig = FeatureConfig(sampleRate = 16_000, featureDim = 80)
        config.modelConfig = modelConfig
        config.decodingMethod = "greedy_search"
        return OfflineRecognizer(assetManager = null, config = config)
    }

    companion object {
        const val STUB_SCHEME = "stub://"
        private const val TAG = "SherpaOnnxBridge"
        private const val NATIVE_LIB = "sherpa-onnx-jni"

        @Volatile private var nativeLoadAttempted = false
        @Volatile private var nativeOk = false
        @Volatile private var nativeLoadError: String? = null

        @JvmStatic
        fun tryLoadNative(): Boolean {
            if (nativeLoadAttempted) return nativeOk
            synchronized(this) {
                if (nativeLoadAttempted) return nativeOk
                nativeLoadAttempted = true
                return try {
                    System.loadLibrary(NATIVE_LIB)
                    nativeOk = true
                    nativeLoadError = null
                    true
                } catch (e: UnsatisfiedLinkError) {
                    nativeOk = false
                    nativeLoadError = "UnsatisfiedLinkError:${e.message}"
                    false
                } catch (t: Throwable) {
                    nativeOk = false
                    nativeLoadError = "${t.javaClass.simpleName}:${t.message}"
                    false
                }
            }
        }

        @JvmStatic
        fun resetNativeLoadStateForTests() {
            nativeLoadAttempted = false
            nativeOk = false
            nativeLoadError = null
        }

        @JvmStatic
        fun companionNativeLoadError(): String? = nativeLoadError

        fun pcm16leToFloat(pcm16leMono: ByteArray): FloatArray {
            val n = pcm16leMono.size / 2
            val out = FloatArray(n)
            val buf = ByteBuffer.wrap(pcm16leMono).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until n) out[i] = buf.short / 32768.0f
            return out
        }
    }
}
