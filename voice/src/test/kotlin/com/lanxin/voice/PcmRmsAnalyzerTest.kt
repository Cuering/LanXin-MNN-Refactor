package com.lanxin.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

/**
 * JVM 单测：PcmRmsAnalyzer 无 Android 依赖。
 */
class PcmRmsAnalyzerTest {

    /** 生成 sine 波 PCM16LE。 */
    private fun sinePcm(
        sampleRate: Int,
        durationMs: Int,
        freqHz: Double,
        amplitude: Float = 0.5f
    ): ByteArray {
        val n = (sampleRate * durationMs) / 1000
        val out = ByteArray(n * 2)
        for (i in 0 until n) {
            val t = i.toDouble() / sampleRate
            val s = (sin(2.0 * Math.PI * freqHz * t) * amplitude * 32767.0).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            out[i * 2] = (s and 0xFF).toByte()
            out[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
        }
        return out
    }

    private fun silencePcm(sampleRate: Int, durationMs: Int): ByteArray {
        val n = (sampleRate * durationMs) / 1000
        return ByteArray(n * 2)
    }

    @Test
    fun emptyInput_returnsEmpty() {
        assertTrue(PcmRmsAnalyzer.analyze(ByteArray(0), 16_000).isEmpty())
        assertTrue(PcmRmsAnalyzer.analyze(sinePcm(16_000, 100, 440.0), 0).isEmpty())
    }

    @Test
    fun silence_allZeroFrames() {
        val pcm = silencePcm(16_000, 200)
        val frames = PcmRmsAnalyzer.analyze(pcm, 16_000, frameMs = 40)
        assertTrue(frames.isNotEmpty())
        assertTrue(frames.all { it == 0f })
    }

    @Test
    fun sine_peakNearOne_andPositive() {
        val pcm = sinePcm(16_000, 200, freqHz = 220.0, amplitude = 0.6f)
        val frames = PcmRmsAnalyzer.analyze(pcm, 16_000, frameMs = 40)
        // 200ms / 40ms = 5 frames
        assertEquals(5, frames.size)
        assertTrue(frames.all { it > 0.5f })
        // 峰值归一化后至少一帧接近 1
        assertTrue(frames.maxOrNull()!! >= 0.95f)
    }

    @Test
    fun loudVsQuiet_relativeNormalized() {
        // 半段强 + 半段弱：归一化后强帧应明显高于弱帧
        val loud = sinePcm(16_000, 80, 300.0, amplitude = 0.8f)
        val quiet = sinePcm(16_000, 80, 300.0, amplitude = 0.1f)
        val pcm = loud + quiet
        val frames = PcmRmsAnalyzer.analyze(pcm, 16_000, frameMs = 40)
        assertTrue(frames.size >= 3)
        val first = frames.take(2).average()
        val last = frames.takeLast(2).average()
        assertTrue("loud($first) should > quiet($last)", first > last * 1.5)
    }

    @Test
    fun durationMs_matchesSampleCount() {
        val pcm = sinePcm(16_000, 250, 440.0)
        assertEquals(250L, PcmRmsAnalyzer.durationMs(pcm, 16_000))
        assertEquals(0L, PcmRmsAnalyzer.durationMs(ByteArray(0), 16_000))
        assertEquals(0L, PcmRmsAnalyzer.durationMs(pcm, 0))
    }

    @Test
    fun oddTrailingByte_ignoredSafely() {
        // 奇数长度：最后 1 字节被忽略，不崩
        val base = sinePcm(8_000, 40, 100.0)
        val odd = base + byteArrayOf(0x7F)
        val frames = PcmRmsAnalyzer.analyze(odd, 8_000, frameMs = 40)
        assertTrue(frames.isNotEmpty())
    }
}
