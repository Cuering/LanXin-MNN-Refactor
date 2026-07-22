package com.lanxin.voice

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreferNativeTtsEngineTest {

    @Test
    fun usesPrimaryWhenReady() = runTest {
        val primary = StubTtsEngine(reason = "primary", simulateReady = true)
        val fallback = StubTtsEngine(reason = "fallback", simulateReady = true)
        val engine = PreferNativeTtsEngine(primary, fallback)
        engine.load("/fake/tts")
        assertTrue(engine.state.isUsable)
        val r = engine.speak("你好呀")
        assertTrue(r.ok)
        assertEquals("primary", engine.lastSpeakSource)
        assertEquals("你好呀", primary.lastSpokenText)
    }

    @Test
    fun usesFallbackWhenPrimaryNotReady() = runTest {
        val primary = StubTtsEngine(reason = "primary_stub", simulateReady = false)
        val fallback = StubTtsEngine(reason = "fallback", simulateReady = true)
        val engine = PreferNativeTtsEngine(primary, fallback)
        engine.load(null)
        assertTrue("fallback should make state usable", engine.state.isUsable)
        val r = engine.speak("系统播报")
        assertTrue(r.ok)
        assertEquals("fallback", engine.lastSpeakSource)
        assertTrue(r.detail!!.startsWith("fallback:"))
        assertEquals("系统播报", fallback.lastSpokenText)
    }

    @Test
    fun failsWhenNeitherUsable() = runTest {
        val primary = StubTtsEngine(simulateReady = false)
        val fallback = StubTtsEngine(simulateReady = false)
        val engine = PreferNativeTtsEngine(primary, fallback)
        engine.load(null)
        assertFalse(engine.state.isUsable)
        val r = engine.speak("不会播")
        assertFalse(r.ok)
        assertTrue(r.detail!!.contains("no_tts"))
    }

    @Test
    fun emptyTextRejected() = runTest {
        val engine = PreferNativeTtsEngine(
            StubTtsEngine(simulateReady = true),
            StubTtsEngine(simulateReady = true)
        )
        engine.load(null)
        val r = engine.speak("   ")
        assertFalse(r.ok)
        assertEquals("empty_text", r.detail)
    }
}
