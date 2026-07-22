package com.lanxin.voice

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext

/**
 * 在 [PcmAudioRecorder] 之上叠加 VAD：开麦后轮询缓冲能量，静音达标自动 [stopRecording]。
 *
 * stop 只允许一次（VAD / 手动互斥），避免双 stop 竞态。
 */
class VadAutoStopRecorder(
    private val recorder: PcmAudioRecorder,
    private val vadFactory: (sampleRateHz: Int) -> VadSilenceDetector = {
        VadSilenceDetector(sampleRateHz = it)
    }
) {
    @Volatile
    private var vad: VadSilenceDetector? = null

    private val stopOnce = AtomicBoolean(false)

    @Volatile
    var lastSnapshot: VadSnapshot? = null
        private set

    val isRecording: Boolean get() = recorder.isRecording

    suspend fun start(
        sampleRateHz: Int = PcmAudioRecorder.DEFAULT_SAMPLE_RATE_HZ
    ): Result<Unit> {
        vad = vadFactory(sampleRateHz).also { it.reset() }
        lastSnapshot = null
        stopOnce.set(false)
        return recorder.startRecording(sampleRateHz)
    }

    /**
     * 轮询已采集 PCM，直到 VAD 判定停或 [timeoutMs]。
     * 成功时**已调用** stopRecording；若已被手动 stop，返回 failure(not_recording)。
     */
    suspend fun awaitAutoStop(
        pollMs: Long = 80L,
        timeoutMs: Long = VadSilenceDetector.DEFAULT_MAX_DURATION_MS + 2_000L
    ): Result<RecordedAudio> = withContext(Dispatchers.IO) {
        val detector = vad
            ?: return@withContext Result.failure(IllegalStateException("vad_not_started"))
        val startedAt = System.currentTimeMillis()
        var lastByteCount = 0

        try {
            while (coroutineContext.isActive && recorder.isRecording && !stopOnce.get()) {
                if (System.currentTimeMillis() - startedAt >= timeoutMs) {
                    break
                }
                val snap = recorder.snapshotPcm()
                if (snap != null && snap.pcm.size > lastByteCount) {
                    val newSlice = snap.pcm.copyOfRange(lastByteCount, snap.pcm.size)
                    lastByteCount = snap.pcm.size
                    if (detector.feedPcmStream(newSlice)) {
                        lastSnapshot = detector.snapshot()
                        break
                    }
                    lastSnapshot = detector.snapshot()
                } else if (snap == null && !recorder.isRecording) {
                    break
                } else if (recorder.isStubCapturing) {
                    val elapsed = System.currentTimeMillis() - startedAt
                    if (elapsed in 100..400 &&
                        detector.currentPhase == VadSilenceDetector.Phase.WAITING_SPEECH
                    ) {
                        detector.feedPcmStream(syntheticSpeechFrame(detector))
                    }
                    if (elapsed > detector.minSpeechMs + detector.silenceMs + detector.prerollMs) {
                        val silence = ByteArray(detector.bytesPerFrame)
                        while (!detector.shouldStop) {
                            detector.feedPcm16leFrame(silence)
                            if (detector.elapsedMs > detector.maxDurationMs) break
                        }
                        lastSnapshot = detector.snapshot()
                        break
                    }
                }
                delay(pollMs)
            }
        } catch (c: CancellationException) {
            throw c
        }

        stopOnceIfRecording()
    }

    /** 手动停麦（与 VAD 互斥，只生效一次）。 */
    suspend fun stopManual(): Result<RecordedAudio> = stopOnceIfRecording()

    suspend fun cancel() {
        stopOnce.set(true)
        recorder.cancelRecording()
        vad?.reset()
    }

    private suspend fun stopOnceIfRecording(): Result<RecordedAudio> {
        if (!stopOnce.compareAndSet(false, true)) {
            return Result.failure(IllegalStateException("not_recording"))
        }
        if (!recorder.isRecording) {
            return Result.failure(IllegalStateException("not_recording"))
        }
        return recorder.stopRecording()
    }

    private fun syntheticSpeechFrame(detector: VadSilenceDetector): ByteArray {
        val n = detector.samplesPerFrame
        val out = ByteArray(n * 2)
        for (i in 0 until n) {
            val s = ((i % 16) * 2000 - 16000).toShort()
            out[i * 2] = (s.toInt() and 0xFF).toByte()
            out[i * 2 + 1] = (s.toInt() shr 8).toByte()
        }
        val frames = (detector.minSpeechMs / detector.frameMs).toInt().coerceAtLeast(1) + 1
        val all = ByteArray(out.size * frames)
        for (f in 0 until frames) {
            System.arraycopy(out, 0, all, f * out.size, out.size)
        }
        return all
    }
}
