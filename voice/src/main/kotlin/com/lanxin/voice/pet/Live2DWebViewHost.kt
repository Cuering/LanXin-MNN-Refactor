package com.lanxin.voice.pet

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lanxin.voice.PetDisplayState
import java.util.concurrent.atomic.AtomicReference

/**
 * Live2D 显示壳：WebView + 本地 assets HTML。
 *
 * - 默认加载 `file:///android_asset/live2d/index.html`（占位 canvas，无 moc3 也可显示）
 * - 真 moc3 可放到 assets/live2d/models/ 或外置 filesDir，通过 [loadModelUrl] 切换
 * - 状态可观测：Placeholder → Loading → Ready / Failed
 * - 不静默失败：load 错误写入 [PetDisplayState.Failed]
 */
class Live2DWebViewHost {

    private val stateRef = AtomicReference<PetDisplayState>(PetDisplayState.Uninitialized)
    private var webView: WebView? = null

    val state: PetDisplayState get() = stateRef.get()

    /**
     * 创建（或复用）WebView 并挂到 [container]。
     * 须在主线程调用。
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun attach(
        context: Context,
        container: ViewGroup,
        assetEntry: String = DEFAULT_ASSET_ENTRY
    ): WebView {
        val existing = webView
        if (existing != null) {
            (existing.parent as? ViewGroup)?.removeView(existing)
            container.addView(
                existing,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            return existing
        }
        stateRef.set(PetDisplayState.Placeholder("webview_creating"))
        val wv = WebView(context.applicationContext).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            settings.allowContentAccess = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                    Log.d(TAG, "js: ${consoleMessage?.message()}")
                    return true
                }
            }
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    val modelId = extractModelId(url)
                    stateRef.set(
                        PetDisplayState.Ready(
                            modelId = modelId,
                            source = url ?: assetEntry
                        )
                    )
                    Log.i(TAG, "page finished url=$url state=${stateRef.get().shortLabel}")
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?
                ) {
                    if (request?.isForMainFrame == true) {
                        val detail = "web_error:${error?.description}:${request.url}"
                        stateRef.set(PetDisplayState.Failed(detail))
                        Log.e(TAG, detail)
                    }
                }
            }
        }
        webView = wv
        container.addView(wv)
        loadAsset(assetEntry)
        return wv
    }

    fun loadAsset(assetEntry: String = DEFAULT_ASSET_ENTRY) {
        val wv = webView ?: return
        stateRef.set(PetDisplayState.Placeholder("loading:$assetEntry"))
        val url = if (assetEntry.startsWith("file:") || assetEntry.startsWith("http")) {
            assetEntry
        } else {
            "file:///android_asset/$assetEntry"
        }
        wv.loadUrl(url)
    }

    /** 外置或自定义 URL（如 filesDir 下的 index.html）。 */
    fun loadModelUrl(url: String, modelId: String = "custom") {
        val wv = webView ?: return
        stateRef.set(PetDisplayState.Placeholder("loading:$modelId"))
        wv.loadUrl(url)
    }

    /** 驱动表情/动作占位：向页面 post 消息（真 Cubism 后续接）。 */
    fun postExpression(name: String) {
        val wv = webView ?: return
        val safe = name.replace("'", "")
        wv.evaluateJavascript("window.LanXinPet && window.LanXinPet.setExpression('$safe')", null)
    }

    fun postMouthOpen(amount: Float) {
        val wv = webView ?: return
        val a = amount.coerceIn(0f, 1f)
        wv.evaluateJavascript("window.LanXinPet && window.LanXinPet.setMouth($a)", null)
    }

    /**
     * 播报期间嘴型动画（占位）：按 [durationMs] 轮播 mouth open/close。
     * 无 PCM 时使用；有 PCM 时优先 [lipSyncFromPcm]。
     */
    suspend fun lipSyncDuring(durationMs: Long) {
        if (durationMs <= 0) return
        val steps = (durationMs / 80L).toInt().coerceIn(1, 500)
        for (i in 0 until steps) {
            val open = if (i % 3 == 0) 0.8f else if (i % 3 == 1) 0.3f else 0.5f
            postMouthOpen(open)
            kotlinx.coroutines.delay(80)
        }
        postMouthOpen(0f)
    }

    /**
     * 用 TTS PCM 能量驱动嘴型（P9）。
     * - 有有效 PCM：按帧 RMS 推 [postMouthOpen]
     * - 无 PCM / 全静音：回退 [lipSyncDuring]
     *
     * @param pcm16le little-endian signed 16-bit mono，可 null
     * @param sampleRateHz 采样率
     * @param durationMsFallback 无 PCM 时的占位时长
     * @param frameMs RMS 帧长，默认 40ms
     */
    suspend fun lipSyncFromPcm(
        pcm16le: ByteArray?,
        sampleRateHz: Int,
        durationMsFallback: Long = 0L,
        frameMs: Int = com.lanxin.voice.PcmRmsAnalyzer.DEFAULT_FRAME_MS
    ) {
        val pcm = pcm16le
        if (pcm == null || pcm.isEmpty() || sampleRateHz <= 0) {
            lipSyncDuring(durationMsFallback)
            return
        }
        val frames = com.lanxin.voice.PcmRmsAnalyzer.analyze(pcm, sampleRateHz, frameMs)
        if (frames.isEmpty() || frames.all { it < 1e-4f }) {
            val dur = if (durationMsFallback > 0) {
                durationMsFallback
            } else {
                com.lanxin.voice.PcmRmsAnalyzer.durationMs(pcm, sampleRateHz)
            }
            lipSyncDuring(dur)
            return
        }
        val step = frameMs.toLong().coerceAtLeast(1L)
        for (level in frames) {
            // 弱能量也给一点开口，避免完全闭嘴看起来卡顿
            val open = if (level < 0.05f) 0f else (0.15f + 0.85f * level).coerceIn(0f, 1f)
            postMouthOpen(open)
            kotlinx.coroutines.delay(step)
        }
        postMouthOpen(0f)
    }

    fun detach() {
        val wv = webView
        webView = null
        if (wv != null) {
            (wv.parent as? ViewGroup)?.removeView(wv)
            try {
                wv.stopLoading()
                wv.destroy()
            } catch (_: Throwable) {
            }
        }
        stateRef.set(PetDisplayState.Uninitialized)
    }

    private fun extractModelId(url: String?): String {
        if (url.isNullOrBlank()) return "unknown"
        return url.substringAfterLast('/').substringBefore('?').ifBlank { "live2d" }
    }

    companion object {
        private const val TAG = "Live2DWebViewHost"
        const val DEFAULT_ASSET_ENTRY = "live2d/index.html"
    }
}
