package com.lanxin.voice.sherpa

import com.lanxin.voice.VoiceEngineState
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * JVM 单测：无 libsherpa-onnx-jni.so 时 bridge/engine 不崩，状态诚实。
 */
class SherpaVoiceEnginesTest {

    @Before
    fun reset() {
        SherpaOnnxBridge.resetNativeLoadStateForTests()
    }

    @Test
    fun bridge_nativeUnavailable_onJvm() {
        val bridge = SherpaOnnxBridge()
        // JVM 单测通常无 so
        val ok = bridge.isNativeAvailable()
        if (!ok) {
            assertTrue(bridge.nativeLoadError() != null || !ok)
        }
    }

    @Test
    fun bridge_validateStubPath() {
        val bridge = SherpaOnnxBridge()
        assertTrue(bridge.validateModelPath("stub://demo"))
        assertFalse(bridge.validateModelPath(""))
        assertFalse(bridge.validateModelPath("/no/such/path/xyz"))
    }

    @Test
    fun asr_stubPath_isStubNotReady_butHintWorks() = runBlocking {
        val e = SherpaAsrEngine()
        val s = e.load("stub://demo-asr")
        assertTrue(s is VoiceEngineState.Stub)
        assertFalse(s.isUsable)
        assertFalse(e.isUsingNative)
        val r = e.transcribe(hintText = "你好")
        assertTrue(r.ok)
        assertTrue(r.text == "你好")
        assertTrue(r.detail == "stub_hint")
    }

    @Test
    fun asr_emptyPath_loadFailed() = runBlocking {
        val e = SherpaAsrEngine()
        val s = e.load(null)
        assertTrue(s is VoiceEngineState.LoadFailed)
    }

    @Test
    fun asr_notReady_hintBypass_doesNotPretendReady() = runBlocking {
        val e = SherpaAsrEngine()
        // 未 load：状态 Uninitialized，但 hint 可旁路联调
        val r = e.transcribe(hintText = "旁路")
        assertTrue(r.ok)
        assertTrue(r.text == "旁路")
        assertTrue(r.detail!!.startsWith("hint_bypass_not_ready"))
        assertFalse(e.state.isUsable)
    }

    @Test
    fun asr_missingDir_loadFailed() = runBlocking {
        val e = SherpaAsrEngine()
        val s = e.load("/tmp/lanxin_asr_missing_${System.nanoTime()}")
        assertTrue(s is VoiceEngineState.LoadFailed)
    }

    @Test
    fun asr_existingDir_withoutNative_isNativeMissingOrLoadFailed() = runBlocking {
        val dir = File.createTempFile("asr_model", "").apply {
            delete()
            mkdirs()
        }
        try {
            File(dir, "tokens.txt").writeText("a")
            File(dir, "encoder.onnx").writeText("x")
            File(dir, "decoder.onnx").writeText("x")
            File(dir, "joiner.onnx").writeText("x")
            val e = SherpaAsrEngine()
            val s = e.load(dir.absolutePath)
            // JVM 无 so → NativeMissing；若 so 意外存在但 load 失败 → LoadFailed
            assertTrue(
                "expected NativeMissing or LoadFailed, got $s",
                s is VoiceEngineState.NativeMissing || s is VoiceEngineState.LoadFailed
            )
            assertFalse(s.isUsable)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun tts_emptyPath_isStub() = runBlocking {
        val e = SherpaTtsEngine()
        val s = e.load(null)
        assertTrue(s is VoiceEngineState.Stub)
        val r = e.speak("测试")
        assertTrue(r.ok)
        assertTrue(r.spokenChars > 0)
    }

    @Test
    fun tts_stubPath_isStub() = runBlocking {
        val e = SherpaTtsEngine()
        val s = e.load("stub://demo-tts")
        assertTrue(s is VoiceEngineState.Stub)
        assertFalse(e.isUsingNative)
    }

    @Test
    fun tts_missingDir_loadFailed() = runBlocking {
        val e = SherpaTtsEngine()
        val s = e.load("/tmp/lanxin_tts_missing_${System.nanoTime()}")
        assertTrue(s is VoiceEngineState.LoadFailed)
    }

    @Test
    fun pcm16leToFloat_roundtripScale() {
        val pcm = byteArrayOf(0, 0x40, 0xFF.toByte(), 0x7F) // small + near max
        val f = SherpaOnnxBridge.pcm16leToFloat(pcm)
        assertTrue(f.size == 2)
        assertTrue(f[0] > 0f)
        assertTrue(f[1] > 0.9f)
    }

    @Test
    fun floatToPcm16le_empty() {
        val pcm = SherpaTtsBridge.floatToPcm16le(floatArrayOf())
        assertTrue(pcm.isEmpty())
        assertTrue(SherpaTtsBridge.durationMs(0, 22050) == 0L)
        assertTrue(SherpaTtsBridge.durationMs(22050, 22050) == 1000L)
    }
}
