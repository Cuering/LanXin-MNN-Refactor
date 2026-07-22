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
