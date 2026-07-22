package com.lanxin.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 用户显式触发的 PCM 录音（16k mono 16-bit LE）。
 *
 * - 不在后台偷偷录音；stop/cancel 立即 release
 * - [forceStubHardware]=true 或 factory=null → 不碰硬件（JVM 单测）
 * - 调用方须自行保证 RECORD_AUDIO 已授予
 */
class PcmAudioRecorder {

    @Volatile
    var audioRecordFactory: ((sampleRateHz: Int, bufferSize: Int) -> AudioRecord?)? =
        DEFAULT_FACTORY

    /** JVM / 联调：不打开麦克风，stop 时按时长合成 stub PCM */
    @Volatile
    var forceStubHardware: Boolean = false

    private val recording = AtomicBoolean(false)
    private var activeRecord: AudioRecord? = null
    private var captureBuffer: ByteArrayOutputStream? = null
    private var captureSampleRate: Int = DEFAULT_SAMPLE_RATE_HZ
    private var captureStartedAtMs: Long = 0L
    private var captureThread: Thread? = null
    private var stubMode: Boolean = false

    val isRecording: Boolean get() = recording.get()

    /** 当前是否走 stub 采集（无硬件）。供 VAD 自动停麦旁路。 */
    val isStubCapturing: Boolean get() = recording.get() && stubMode

    /**
     * 非破坏性读取已采集 PCM 快照（VAD 轮询用）。
     * 未在录或 stub 模式返回 null（stub 由 [VadAutoStopRecorder] 时间推进）。
     */
    fun snapshotPcm(): PcmSnapshot? {
        if (!recording.get() || stubMode) return null
        val buf = captureBuffer ?: return null
        val bytes = synchronized(buf) { buf.toByteArray() }
        if (bytes.isEmpty()) {
            return PcmSnapshot(pcm = ByteArray(0), sampleRateHz = captureSampleRate)
        }
        return PcmSnapshot(pcm = bytes, sampleRateHz = captureSampleRate)
    }

    data class PcmSnapshot(
        val pcm: ByteArray,
        val sampleRateHz: Int
    )

    /** 生成 stub PCM，不触碰硬件。 */
    suspend fun recordStubPcm(
        durationMs: Long = DEFAULT_STUB_DURATION_MS,
        sampleRateHz: Int = DEFAULT_SAMPLE_RATE_HZ
    ): RecordedAudio = withContext(Dispatchers.Default) {
        val clampedMs = durationMs.coerceIn(50L, MAX_DURATION_MS)
        val rate = sampleRateHz.coerceIn(MIN_SAMPLE_RATE_HZ, MAX_SAMPLE_RATE_HZ)
        delay(5)
        RecordedAudio(
            pcm16leMono = synthesizeStubPcm(rate, clampedMs),
            sampleRateHz = rate,
            durationMs = clampedMs,
            isStub = true
        )
    }

    /**
     * 开始录音。重复 start → failure。
     * 权限由 UI 层校验；此处不二次申请。
     */
    @SuppressLint("MissingPermission")
    suspend fun startRecording(
        sampleRateHz: Int = DEFAULT_SAMPLE_RATE_HZ
    ): Result<Unit> = withContext(Dispatchers.IO) {
        if (!recording.compareAndSet(false, true)) {
            return@withContext Result.failure(IllegalStateException("already_recording"))
        }
        val rate = sampleRateHz.coerceIn(MIN_SAMPLE_RATE_HZ, MAX_SAMPLE_RATE_HZ)
        captureSampleRate = rate
        captureStartedAtMs = System.currentTimeMillis()
        captureBuffer = ByteArrayOutputStream()
        stubMode = forceStubHardware || audioRecordFactory == null

        if (stubMode) {
            return@withContext Result.success(Unit)
        }

        val minBuf = AudioRecord.getMinBufferSize(
            rate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) {
            resetCaptureState()
            return@withContext Result.failure(IllegalStateException("unsupported_rate:$rate"))
        }
        val bufferSize = (minBuf * 2).coerceAtLeast(minBuf)
        val record = try {
            audioRecordFactory?.invoke(rate, bufferSize)
        } catch (t: Throwable) {
            resetCaptureState()
            return@withContext Result.failure(
                IllegalStateException("open_mic_failed:${t.javaClass.simpleName}:${t.message}")
            )
        }
        if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { record?.release() }
            resetCaptureState()
            return@withContext Result.failure(IllegalStateException("audio_record_not_initialized"))
        }
        try {
            record.startRecording()
        } catch (t: Throwable) {
            runCatching { record.release() }
            resetCaptureState()
            return@withContext Result.failure(
                IllegalStateException("start_recording_failed:${t.message}")
            )
        }
        activeRecord = record
        val buf = captureBuffer!!
        captureThread = Thread({
            val chunk = ByteArray(bufferSize)
            while (recording.get()) {
                val n = try {
                    record.read(chunk, 0, chunk.size)
                } catch (_: Throwable) {
                    break
                }
                if (n > 0) {
                    synchronized(buf) { buf.write(chunk, 0, n) }
                } else if (n < 0) {
                    break
                }
            }
        }, "pcm-capture").also {
            it.isDaemon = true
            it.start()
        }
        Result.success(Unit)
    }

    /** 停止并返回 PCM；超时自动截断。 */
    suspend fun stopRecording(): Result<RecordedAudio> = withContext(Dispatchers.IO) {
        if (!recording.get()) {
            return@withContext Result.failure(IllegalStateException("not_recording"))
        }
        val elapsed = (System.currentTimeMillis() - captureStartedAtMs)
            .coerceIn(0L, MAX_DURATION_MS)
        val rate = captureSampleRate
        val wasStub = stubMode

        // 先停采集标志，再 join 线程
        recording.set(false)
        val th = captureThread
        captureThread = null
        th?.join(500)

        val rec = activeRecord
        activeRecord = null
        if (rec != null) {
            try {
                rec.stop()
            } catch (_: Throwable) {
            }
            try {
                rec.release()
            } catch (_: Throwable) {
            }
        }

        val pcm: ByteArray = if (wasStub) {
            synthesizeStubPcm(rate, elapsed.coerceAtLeast(50L))
        } else {
            val buf = captureBuffer
            captureBuffer = null
            synchronized(buf ?: ByteArrayOutputStream()) {
                (buf?.toByteArray()) ?: ByteArray(0)
            }
        }
        captureBuffer = null
        stubMode = false

        if (pcm.isEmpty()) {
            return@withContext Result.failure(IllegalStateException("empty_pcm"))
        }
        Result.success(
            RecordedAudio(
                pcm16leMono = pcm,
                sampleRateHz = rate,
                durationMs = elapsed.coerceAtLeast(1L),
                isStub = wasStub
            )
        )
    }

    /** 取消录音，丢弃数据。 */
    suspend fun cancelRecording() = withContext(Dispatchers.IO) {
        if (!recording.getAndSet(false) && activeRecord == null) return@withContext
        val th = captureThread
        captureThread = null
        th?.join(300)
        val rec = activeRecord
        activeRecord = null
        if (rec != null) {
            try {
                rec.stop()
            } catch (_: Throwable) {
            }
            try {
                rec.release()
            } catch (_: Throwable) {
            }
        }
        captureBuffer = null
        stubMode = false
        Log.i(TAG, "recording cancelled")
    }

    private fun resetCaptureState() {
        recording.set(false)
        activeRecord = null
        captureBuffer = null
        captureThread = null
        stubMode = false
    }

    companion object {
        private const val TAG = "PcmAudioRecorder"
        const val DEFAULT_SAMPLE_RATE_HZ = 16_000
        const val MIN_SAMPLE_RATE_HZ = 8_000
        const val MAX_SAMPLE_RATE_HZ = 48_000
        const val DEFAULT_STUB_DURATION_MS = 800L
        const val MAX_DURATION_MS = 30_000L

        val DEFAULT_FACTORY: (Int, Int) -> AudioRecord? = { rate, bufferSize ->
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                rate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
        }

        fun synthesizeStubPcm(sampleRateHz: Int, durationMs: Long): ByteArray {
            val samples = ((sampleRateHz * durationMs) / 1000L).toInt().coerceAtLeast(1)
            val pcm = ByteArray(samples * 2)
            for (i in 0 until samples) {
                val sample = ((i % 32) - 16).toShort()
                pcm[i * 2] = (sample.toInt() and 0xFF).toByte()
                pcm[i * 2 + 1] = (sample.toInt() shr 8).toByte()
            }
            return pcm
        }
    }
}
