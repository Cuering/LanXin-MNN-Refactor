package com.lanxin.voice

import kotlin.math.sqrt

/**
 * PCM16LE mono RMS 分析：把原始 PCM 切成固定时长帧，输出归一化能量 [0,1]。
 *
 * 用于 TTS 播报时驱动 Live2D 嘴型（P9），无 native 依赖，JVM 可测。
 */
object PcmRmsAnalyzer {

    /** 默认帧长 40ms，兼顾嘴型平滑与响应。 */
    const val DEFAULT_FRAME_MS: Int = 40

    /**
     * @param pcm16le little-endian signed 16-bit mono
     * @param sampleRateHz 采样率（通常 16000 / 22050）
     * @param frameMs 每帧毫秒
     * @return 每帧归一化 RMS（相对本段峰值，峰值帧 ≈ 1.0；全静音返回全 0）
     */
    fun analyze(
        pcm16le: ByteArray,
        sampleRateHz: Int,
        frameMs: Int = DEFAULT_FRAME_MS
    ): FloatArray {
        if (pcm16le.isEmpty() || sampleRateHz <= 0 || frameMs <= 0) {
            return floatArrayOf()
        }
        val samplesPerFrame = ((sampleRateHz * frameMs) / 1000).coerceAtLeast(1)
        val totalSamples = pcm16le.size / 2
        if (totalSamples == 0) return floatArrayOf()

        val frameCount = (totalSamples + samplesPerFrame - 1) / samplesPerFrame
        val raw = FloatArray(frameCount)
        var peak = 0f

        for (f in 0 until frameCount) {
            val start = f * samplesPerFrame
            val end = minOf(start + samplesPerFrame, totalSamples)
            var sumSq = 0.0
            var n = 0
            var i = start
            while (i < end) {
                val lo = pcm16le[i * 2].toInt() and 0xFF
                val hi = pcm16le[i * 2 + 1].toInt()
                val s = ((hi shl 8) or lo).toShort().toInt()
                val v = s / 32768.0
                sumSq += v * v
                n++
                i++
            }
            val rms = if (n > 0) sqrt(sumSq / n).toFloat() else 0f
            raw[f] = rms
            if (rms > peak) peak = rms
        }

        if (peak < 1e-6f) {
            // 全静音：返回全 0，调用方可回退时间步进
            return FloatArray(frameCount) { 0f }
        }
        // 相对峰值归一化，再做轻微压缩让小音量仍可见
        val out = FloatArray(frameCount)
        for (i in raw.indices) {
            val n = (raw[i] / peak).coerceIn(0f, 1f)
            // sqrt 压缩：弱能量抬高一点，强能量仍接近 1
            out[i] = sqrt(n).coerceIn(0f, 1f)
        }
        return out
    }

    /** 估算整段时长（毫秒）。 */
    fun durationMs(pcm16le: ByteArray, sampleRateHz: Int): Long {
        if (sampleRateHz <= 0 || pcm16le.size < 2) return 0L
        val samples = pcm16le.size / 2
        return (samples * 1000L) / sampleRateHz
    }
}
