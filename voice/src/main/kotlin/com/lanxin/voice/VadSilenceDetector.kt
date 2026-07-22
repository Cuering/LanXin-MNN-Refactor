package com.lanxin.voice

import kotlin.math.sqrt

/**
 * 基于 PCM16LE RMS 的端点检测（VAD 轻量版）。
 *
 * 状态机：
 * - WAITING_SPEECH：等第一段超过 [speechThreshold] 的帧
 * - IN_SPEECH：已检测到语音，累计静音
 * - 连续静音 ≥ [silenceMs] 且已说过话 → [shouldStop]=true
 * - 总时长 ≥ [maxDurationMs] → 强制停
 *
 * 无 native 依赖，JVM 可单测；阈值可调。
 */
class VadSilenceDetector(
    val sampleRateHz: Int = DEFAULT_SAMPLE_RATE_HZ,
    val frameMs: Int = DEFAULT_FRAME_MS,
    val speechThreshold: Float = DEFAULT_SPEECH_THRESHOLD,
    val silenceMs: Long = DEFAULT_SILENCE_MS,
    val minSpeechMs: Long = DEFAULT_MIN_SPEECH_MS,
    val maxDurationMs: Long = DEFAULT_MAX_DURATION_MS,
    val prerollMs: Long = DEFAULT_PREROLL_MS
) {
    enum class Phase {
        WAITING_SPEECH,
        IN_SPEECH,
        SHOULD_STOP
    }

    private var phase: Phase = Phase.WAITING_SPEECH
    private var speechFrames: Int = 0
    private var silenceFrames: Int = 0
    private var totalFrames: Int = 0
    private var lastRms: Float = 0f

    val samplesPerFrame: Int
        get() = ((sampleRateHz * frameMs) / 1000).coerceAtLeast(1)

    val bytesPerFrame: Int
        get() = samplesPerFrame * 2

    val currentPhase: Phase get() = phase
    val lastFrameRms: Float get() = lastRms
    val elapsedMs: Long get() = totalFrames * frameMs.toLong()
    val speechMs: Long get() = speechFrames * frameMs.toLong()
    val silenceAccumMs: Long get() = silenceFrames * frameMs.toLong()

    val shouldStop: Boolean get() = phase == Phase.SHOULD_STOP

    fun reset() {
        phase = Phase.WAITING_SPEECH
        speechFrames = 0
        silenceFrames = 0
        totalFrames = 0
        lastRms = 0f
    }

    /**
     * 喂入一帧 PCM16LE（长度建议 = [bytesPerFrame]，短帧也可）。
     * @return 更新后的 phase
     */
    fun feedPcm16leFrame(frame: ByteArray, offset: Int = 0, length: Int = frame.size): Phase {
        if (phase == Phase.SHOULD_STOP) return phase
        val end = (offset + length).coerceAtMost(frame.size)
        val start = offset.coerceAtLeast(0)
        val rms = frameRms(frame, start, end)
        lastRms = rms
        totalFrames++

        val isSpeech = rms >= speechThreshold
        when (phase) {
            Phase.WAITING_SPEECH -> {
                // 预热期：刚开麦的环境噪声不立刻判语音
                if (elapsedMs < prerollMs) {
                    // still waiting
                } else if (isSpeech) {
                    speechFrames++
                    silenceFrames = 0
                    if (speechMs >= minSpeechMs.coerceAtMost(frameMs.toLong())) {
                        phase = Phase.IN_SPEECH
                    }
                } else {
                    silenceFrames++
                }
                // 全程超时（一直没说话也停，避免挂死）
                if (elapsedMs >= maxDurationMs) {
                    phase = Phase.SHOULD_STOP
                }
            }
            Phase.IN_SPEECH -> {
                if (isSpeech) {
                    speechFrames++
                    silenceFrames = 0
                } else {
                    silenceFrames++
                    if (silenceAccumMs >= silenceMs && speechMs >= minSpeechMs) {
                        phase = Phase.SHOULD_STOP
                    }
                }
                if (elapsedMs >= maxDurationMs) {
                    phase = Phase.SHOULD_STOP
                }
            }
            Phase.SHOULD_STOP -> Unit
        }
        return phase
    }

    /**
     * 从连续 PCM 流按帧推进（会跳过不完整尾帧）。
     * @return 是否应停止
     */
    fun feedPcmStream(pcm16le: ByteArray, offset: Int = 0, length: Int = pcm16le.size - offset): Boolean {
        var i = offset
        val end = (offset + length).coerceAtMost(pcm16le.size)
        val step = bytesPerFrame
        while (i + step <= end) {
            feedPcm16leFrame(pcm16le, i, step)
            if (shouldStop) return true
            i += step
        }
        // 尾部不足一帧也喂一次，避免丢末尾语音
        if (i < end) {
            feedPcm16leFrame(pcm16le, i, end - i)
        }
        return shouldStop
    }

    fun snapshot(): VadSnapshot = VadSnapshot(
        phase = phase,
        elapsedMs = elapsedMs,
        speechMs = speechMs,
        silenceMs = silenceAccumMs,
        lastRms = lastRms
    )

    companion object {
        const val DEFAULT_SAMPLE_RATE_HZ = 16_000
        const val DEFAULT_FRAME_MS = 20
        /** 归一化 RMS 阈值（相对 full-scale ~1.0）；0.02 ≈ 轻声说话 */
        const val DEFAULT_SPEECH_THRESHOLD = 0.02f
        const val DEFAULT_SILENCE_MS = 900L
        const val DEFAULT_MIN_SPEECH_MS = 200L
        const val DEFAULT_MAX_DURATION_MS = 20_000L
        const val DEFAULT_PREROLL_MS = 200L

        fun frameRms(pcm: ByteArray, offset: Int, endExclusive: Int): Float {
            var sumSq = 0.0
            var n = 0
            var i = offset
            val end = endExclusive.coerceAtMost(pcm.size)
            // 保证偶数字节对齐
            if ((i and 1) == 1) i++
            while (i + 1 < end) {
                val lo = pcm[i].toInt() and 0xFF
                val hi = pcm[i + 1].toInt()
                val s = ((hi shl 8) or lo).toShort().toInt()
                val v = s / 32768.0
                sumSq += v * v
                n++
                i += 2
            }
            if (n == 0) return 0f
            return sqrt(sumSq / n).toFloat()
        }
    }
}

data class VadSnapshot(
    val phase: VadSilenceDetector.Phase,
    val elapsedMs: Long,
    val speechMs: Long,
    val silenceMs: Long,
    val lastRms: Float
) {
    val shortLabel: String
        get() = "vad=${phase.name} t=${elapsedMs}ms sp=${speechMs}ms sil=${silenceMs}ms rms=${"%.3f".format(lastRms)}"
}
