package com.lanxin.voice

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Android 系统 TTS 回退引擎。
 *
 * 无 Sherpa 模型 / native 失败时仍可「文字 → 出声」，用于先验证语音回复链路。
 * 无 PCM 输出（系统播报），[TtsResult.audioDurationMs] 为按字数估算。
 */
class AndroidSystemTtsEngine(
    context: Context
) : TtsEngine {

    private val appContext = context.applicationContext

    @Volatile
    override var state: VoiceEngineState = VoiceEngineState.Uninitialized
        private set

    private val ttsRef = AtomicReference<TextToSpeech?>(null)

    override suspend fun load(modelPath: String?): VoiceEngineState = withContext(Dispatchers.Main) {
        state = VoiceEngineState.Loading
        // 释放旧实例
        ttsRef.getAndSet(null)?.let { old ->
            runCatching { old.stop() }
            runCatching { old.shutdown() }
        }
        val deferred = CompletableDeferred<Boolean>()
        val engine = try {
            TextToSpeech(appContext) { status ->
                if (!deferred.isCompleted) {
                    deferred.complete(status == TextToSpeech.SUCCESS)
                }
            }
        } catch (t: Throwable) {
            state = VoiceEngineState.LoadFailed("android_tts_ctor:${t.message}")
            Log.e(TAG, "TextToSpeech ctor failed", t)
            return@withContext state
        }
        ttsRef.set(engine)
        val ok = withTimeoutOrNull(10_000L) { deferred.await() } ?: false
        if (!ok) {
            runCatching { engine.shutdown() }
            ttsRef.compareAndSet(engine, null)
            state = VoiceEngineState.LoadFailed("android_tts_init_timeout_or_fail")
            return@withContext state
        }
        // 优先简体中文
        val lang = try {
            val r = engine.setLanguage(Locale.SIMPLIFIED_CHINESE)
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                engine.setLanguage(Locale.getDefault())
            } else {
                r
            }
        } catch (t: Throwable) {
            Log.w(TAG, "setLanguage failed", t)
            TextToSpeech.LANG_NOT_SUPPORTED
        }
        state = VoiceEngineState.Ready(
            modelPath = modelPath ?: "android://tts",
            engineHint = "android_tts:lang=$lang"
        )
        Log.i(TAG, "system TTS ready lang=$lang")
        state
    }

    override suspend fun speak(text: String): TtsResult = withContext(Dispatchers.Main) {
        val t = text.trim()
        if (t.isEmpty()) {
            return@withContext TtsResult(ok = false, detail = "empty_text")
        }
        val engine = ttsRef.get()
        if (engine == null || state !is VoiceEngineState.Ready) {
            return@withContext TtsResult(
                ok = false,
                detail = "not_ready:${state.shortLabel}"
            )
        }
        val done = CompletableDeferred<String>()
        val utteranceId = UUID.randomUUID().toString()
        try {
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) {
                    if (!done.isCompleted) done.complete("done")
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    if (!done.isCompleted) done.complete("error")
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    if (!done.isCompleted) done.complete("error:$errorCode")
                }
            })
        } catch (t: Throwable) {
            return@withContext TtsResult(ok = false, detail = "listener_fail:${t.message}")
        }
        val params = Bundle()
        val code = try {
            engine.speak(t, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
        } catch (t: Throwable) {
            return@withContext TtsResult(ok = false, detail = "speak_throw:${t.message}")
        }
        if (code != TextToSpeech.SUCCESS) {
            return@withContext TtsResult(ok = false, detail = "speak_code:$code")
        }
        // 超时按字数放宽；系统播报结束后返回
        val timeoutMs = (t.length * 400L + 5_000L).coerceIn(8_000L, 90_000L)
        val result = withTimeoutOrNull(timeoutMs) { done.await() } ?: "timeout"
        val ok = result == "done"
        // 无 PCM：按时长估算，供嘴型占位
        val durationMs = estimateDurationMs(t)
        TtsResult(
            ok = ok,
            spokenChars = t.length,
            detail = if (ok) "android_tts" else "android_tts:$result",
            audioDurationMs = if (ok) durationMs else 0L,
            pcm16le = null,
            pcmSampleRate = 0
        )
    }

    override suspend fun stop() = withContext(Dispatchers.Main) {
        runCatching { ttsRef.get()?.stop() }
        Unit
    }

    override suspend fun unload() = withContext(Dispatchers.Main) {
        val engine = ttsRef.getAndSet(null)
        if (engine != null) {
            runCatching { engine.stop() }
            runCatching { engine.shutdown() }
        }
        state = VoiceEngineState.Uninitialized
    }

    companion object {
        private const val TAG = "AndroidSystemTts"

        /** 粗估：中文约 4 字/秒 */
        fun estimateDurationMs(text: String): Long {
            val n = text.trim().length.coerceAtLeast(1)
            return (n * 250L).coerceIn(400L, 60_000L)
        }
    }
}
