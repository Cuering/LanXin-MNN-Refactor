package com.lanxin.voice

import java.io.File

/**
 * 外置语音模型路径解析（LanXin 约定）。
 *
 * 目录布局：
 * ```
 * <baseDir>/LanXin/
 *   asr/<model-name>/   # sherpa ASR
 *   tts/<model-name>/   # sherpa TTS
 * ```
 *
 * 兼容旧 `models/asr` / `models/tts` 布局。
 */
object VoiceModelPaths {

    const val LANXIN_ROOT = "LanXin"

    const val ASR_SUBDIR = "$LANXIN_ROOT/asr"
    const val TTS_SUBDIR = "$LANXIN_ROOT/tts"

    private const val LEGACY_ASR_SUBDIR = "models/asr"
    private const val LEGACY_TTS_SUBDIR = "models/tts"

    fun resolveAsrDir(baseDir: File, preferredName: String? = null): String? {
        val lanXinRoot = File(baseDir, ASR_SUBDIR)
        val resolved = resolveModelDir(lanXinRoot, preferredName, requireOnnx = false)
        if (resolved != null) return resolved
        val legacyRoot = File(baseDir, LEGACY_ASR_SUBDIR)
        return resolveModelDir(legacyRoot, preferredName, requireOnnx = false)
    }

    fun resolveTtsDir(baseDir: File, preferredName: String? = null): String? {
        val lanXinRoot = File(baseDir, TTS_SUBDIR)
        val resolved = resolveModelDir(lanXinRoot, preferredName, requireOnnx = true)
        if (resolved != null) return resolved
        val legacyRoot = File(baseDir, LEGACY_TTS_SUBDIR)
        return resolveModelDir(legacyRoot, preferredName, requireOnnx = true)
    }

    fun defaultAsrDir(baseDir: File): String = File(baseDir, ASR_SUBDIR).absolutePath
    fun defaultTtsDir(baseDir: File): String = File(baseDir, TTS_SUBDIR).absolutePath

    /** 目录是否像可用 TTS（tokens + 至少一个 .onnx） */
    fun looksLikeTtsModel(dir: File): Boolean {
        if (!dir.isDirectory) return false
        val hasTokens = File(dir, "tokens.txt").isFile || File(dir, "tokens").isFile
        if (!hasTokens) return false
        val hasOnnx = dir.listFiles()?.any {
            it.isFile && it.name.endsWith(".onnx", ignoreCase = true) && it.length() > 10_000L
        } == true
        return hasOnnx
    }

    private fun resolveModelDir(
        root: File,
        preferredName: String?,
        requireOnnx: Boolean
    ): String? {
        if (!root.isDirectory) return null
        if (preferredName != null) {
            val p = File(root, preferredName)
            if (p.isDirectory && (!requireOnnx || looksLikeTtsModel(p) || hasTokens(p))) {
                return p.absolutePath
            }
        }
        if (hasTokens(root) && (!requireOnnx || hasOnnx(root))) {
            return root.absolutePath
        }
        val children = root.listFiles()?.filter { it.isDirectory }.orEmpty()
        // 优先完整 TTS 布局
        if (requireOnnx) {
            children.firstOrNull { looksLikeTtsModel(it) }?.let { return it.absolutePath }
        }
        children.firstOrNull { hasTokens(it) }?.let { return it.absolutePath }
        if (children.size == 1) return children[0].absolutePath
        return children.firstOrNull()?.absolutePath
    }

    private fun hasTokens(dir: File): Boolean =
        File(dir, "tokens.txt").isFile || File(dir, "tokens").isFile

    private fun hasOnnx(dir: File): Boolean =
        dir.listFiles()?.any {
            it.isFile && it.name.endsWith(".onnx", ignoreCase = true)
        } == true
}
