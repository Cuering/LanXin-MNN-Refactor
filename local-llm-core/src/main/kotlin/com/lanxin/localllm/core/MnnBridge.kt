package com.lanxin.localllm.core

import android.util.Log
import java.io.File

/**
 * MNN LLM JNI 接入点（无业务逻辑）。
 *
 * - so: 官方 3.6.0 预编译 + 自研 libmnn_lanxin.so
 * - 模型外置目录；失败时 [lastError] 可观测，禁止静默假 READY
 */
class MnnBridge {

    @Volatile private var lastLoadError: String? = null
    @Volatile private var sessionLoaded: Boolean = false

    fun isNativeAvailable(): Boolean = tryLoadNative()
    fun nativeLoadError(): String? = companionNativeLoadError()
    fun lastError(): String? = lastLoadError ?: companionNativeLoadError()
    fun isSessionLoaded(): Boolean = sessionLoaded

    fun validateModelPath(path: String): Boolean {
        if (path.isBlank()) return false
        if (path.startsWith(STUB_SCHEME)) return true
        return File(path).exists()
    }

    @Synchronized
    fun loadModel(path: String): Boolean {
        lastLoadError = null
        sessionLoaded = false
        if (path.startsWith(STUB_SCHEME)) {
            lastLoadError = "stub_path_no_native"
            return false
        }
        if (!tryLoadNative()) {
            lastLoadError = nativeLoadError ?: "native_unavailable"
            return false
        }
        if (!File(path).exists()) {
            lastLoadError = "model_path_missing:$path"
            return false
        }
        return try {
            unloadNativeSafe()
            val ok = nativeLoadModel(path)
            if (ok) {
                sessionLoaded = true
                lastLoadError = null
                Log.i(TAG, "loadModel ok")
                true
            } else {
                lastLoadError = nativeLastError() ?: "load_failed"
                sessionLoaded = false
                false
            }
        } catch (t: Throwable) {
            unloadNativeSafe()
            lastLoadError = "load_failed:${t.javaClass.simpleName}:${t.message}"
            sessionLoaded = false
            Log.e(TAG, "loadModel failed", t)
            false
        }
    }

    @Synchronized
    fun generate(prompt: String, maxTokens: Int): String? {
        if (!sessionLoaded) {
            lastLoadError = lastLoadError ?: "not_loaded"
            return null
        }
        if (!tryLoadNative()) {
            lastLoadError = nativeLoadError ?: "native_unavailable"
            return null
        }
        return try {
            val out = nativeGenerate(prompt, maxTokens.coerceIn(1, 4096))
            if (out == null) {
                lastLoadError = nativeLastError() ?: "generate_null"
            } else {
                lastLoadError = null
            }
            out
        } catch (t: Throwable) {
            lastLoadError = "generate_failed:${t.javaClass.simpleName}:${t.message}"
            Log.e(TAG, "generate failed", t)
            null
        }
    }

    @Synchronized
    fun unload() {
        try {
            if (nativeLoaded) unloadNativeSafe()
        } catch (_: Throwable) {
        }
        sessionLoaded = false
    }

    private fun unloadNativeSafe() {
        try {
            nativeUnload()
        } catch (t: Throwable) {
            Log.w(TAG, "nativeUnload: ${t.message}")
        }
    }

    private external fun nativeLoadModel(path: String): Boolean
    private external fun nativeGenerate(prompt: String, maxTokens: Int): String?
    private external fun nativeUnload()
    private external fun nativeLastError(): String?
    private external fun nativeIsLoaded(): Boolean

    companion object {
        private const val TAG = "MnnBridge"
        const val STUB_SCHEME = "stub://"

        @Volatile private var nativeLoaded: Boolean = false
        @Volatile private var nativeLoadError: String? = null
        private val loadLock = Any()

        private fun companionNativeLoadError(): String? = nativeLoadError

        private fun tryLoadNative(): Boolean {
            if (nativeLoaded) return true
            synchronized(loadLock) {
                if (nativeLoaded) return true
                // Order: c++_shared → MNN core → express → llm → our jni
                val libs = listOf(
                    "c++_shared",
                    "MNN",
                    "MNN_Express",
                    "llm",
                    "mnn_lanxin"
                )
                // Optional GPU backends if present
                val optional = listOf("MNN_CL", "MNN_Vulkan")
                return try {
                    for (name in libs) {
                        System.loadLibrary(name)
                    }
                    for (name in optional) {
                        try {
                            System.loadLibrary(name)
                        } catch (_: UnsatisfiedLinkError) {
                            // optional
                        }
                    }
                    nativeLoaded = true
                    nativeLoadError = null
                    true
                } catch (e: UnsatisfiedLinkError) {
                    nativeLoadError = "loadLibrary:${e.message}"
                    Log.e(TAG, "loadLibrary failed", e)
                    false
                }
            }
        }
    }
}
