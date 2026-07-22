package com.lanxin.voice

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StubVoiceEnginesTest {

    @Test
    fun asrStub_neverReady() = runBlocking {
        val asr = StubAsrEngine()
        val s = asr.load("/tmp/asr")
        assertTrue(s is VoiceEngineState.Stub)
        assertFalse(s.isUsable)
        val r = asr.transcribe(hintText = "你好兰儿")
        assertTrue(r.ok)
        assertEquals("你好兰儿", r.text)
    }

    @Test
    fun ttsStub_speakWithoutPretendReady() = runBlocking {
        val tts = StubTtsEngine(simulateReady = false)
        tts.load(null)
        assertTrue(tts.state is VoiceEngineState.Stub)
        val r = tts.speak("  测试播报  ")
        assertTrue(r.ok)
        assertEquals(4, r.spokenChars)
        assertEquals("测试播报", tts.lastSpokenText)
        // 新字段：stub 路径不填 audioDurationMs / pcm
        assertEquals(0L, r.audioDurationMs)
        assert(r.pcm16le == null)
    }

    @Test
    fun ttsStub_simulateReadyIsExplicit() = runBlocking {
        val tts = StubTtsEngine(simulateReady = true)
        val s = tts.load("/models/tts")
        assertTrue(s is VoiceEngineState.Ready)
        assertTrue(s.isUsable)
        assertEquals("stub_sim", (s as VoiceEngineState.Ready).engineHint)
    }

    @Test
    fun petPlaceholder_label() {
        val pet = PlaceholderPetDisplay()
        assertTrue(pet.state.shortLabel.contains("占位"))
        pet.markReadyBuiltin("Mao")
        assertTrue(pet.state is PetDisplayState.Ready)
    }
}
