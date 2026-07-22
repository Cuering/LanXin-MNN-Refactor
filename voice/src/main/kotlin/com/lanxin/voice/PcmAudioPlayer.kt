package com.lanxin.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 16-bit LE mono PCM 播放（AudioTrack）。
 *
 * - [forceStub]=true：不碰硬件，仅 sleep 模拟时长（JVM 单测）
 * - [stop] 可打断当前播放
 * - 失败不抛到上层（返回 Result）
 */
class PcmAudioPlayer {

    @Volatile
    var forceStub: Boolean = false

    private val playing = AtomicBoolean(false)
    private val activeTrack = AtomicReference<AudioTrack?>(null)

    val isPlaying: Boolean get() = playing.get()

    suspend fun play(
        pcm16leMono: ByteArray,
        sampleRateHz: Int
    ): Result<PlayInfo> = withContext(Dispatchers.IO) {
        if (pcm16leMono.isEmpty() || sampleRateHz <= 0) {
            return@withContext Result.failure(IllegalArgumentException("empty_or_bad_rate"))
        }
        if (!playing.compareAndSet(false, true)) {
            stopInternal()
            playing.set(true)
        }
        val durationMs = (pcm16leMono.size / 2L * 1000L) / sampleRateHz
        try {
            if (forceStub) {
                Thread.sleep(durationMs.coerceAtMost(2_000L).coerceAtLeast(5L))
                return@withContext Result.success(
                    PlayInfo(durationMs = durationMs, stub = true, bytes = pcm16leMono.size)
                )
            }
            val minBuf = AudioTrack.getMinBufferSize(
                sampleRateHz,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuf <= 0) {
                return@withContext Result.failure(IllegalStateException("unsupported_out_rate:$sampleRateHz"))
            }
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRateHz)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minBuf, pcm16leMono.size))
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            activeTrack.set(track)
            try {
                track.write(pcm16leMono, 0, pcm16leMono.size)
                track.play()
                // 轮询等待结束或 stop
                val deadline = System.currentTimeMillis() + durationMs.coerceAtMost(60_000L) + 200L
                while (playing.get() && System.currentTimeMillis() < deadline) {
                    val st = try {
                        track.playState
                    } catch (_: Throwable) {
                        break
                    }
                    if (st != AudioTrack.PLAYSTATE_PLAYING) break
                    Thread.sleep(20)
                }
            } finally {
                try {
                    track.stop()
                } catch (_: Throwable) {
                }
                try {
                    track.release()
                } catch (_: Throwable) {
                }
                activeTrack.compareAndSet(track, null)
            }
            Result.success(
                PlayInfo(durationMs = durationMs, stub = false, bytes = pcm16leMono.size)
            )
        } catch (t: Throwable) {
            Log.e(TAG, "play failed", t)
            Result.failure(t)
        } finally {
            playing.set(false)
        }
    }

    fun stop() {
        stopInternal()
    }

    private fun stopInternal() {
        playing.set(false)
        val track = activeTrack.getAndSet(null)
        if (track != null) {
            try {
                track.pause()
            } catch (_: Throwable) {
            }
            try {
                track.flush()
            } catch (_: Throwable) {
            }
            try {
                track.stop()
            } catch (_: Throwable) {
            }
            try {
                track.release()
            } catch (_: Throwable) {
            }
        }
    }

    data class PlayInfo(
        val durationMs: Long,
        val stub: Boolean,
        val bytes: Int
    )

    companion object {
        private const val TAG = "PcmAudioPlayer"
    }
}
