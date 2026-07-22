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

    // 兼容旧路径
    private const val LEGACY_ASR_SUBDIR = "models/asr"
    private const val LEGACY_TTS_SUBDIR = "models/tts"

    /** 在 baseDir 下解析 ASR 模型目录；优先 LanXin/asr/，回退 models/asr/ */
    fun resolveAsrDir(baseDir: File, preferredName: String? = null): String? {
        val lanXinRoot = File(baseDir, ASR_SUBDIR)
        val resolved = resolveModelDir(lanXinRoot, preferredName)
        if (resolved != null) return resolved
        // fallback legacy
        val legacyRoot = File(baseDir, LEGACY_ASR_SUBDIR)
        return resolveModelDir(legacyRoot, preferredName)
    }

    fun resolveTtsDir(baseDir: File, preferredName: String? = null): String? {
        val lanXinRoot = File(baseDir, TTS_SUBDIR)
        val resolved = resolveModelDir(lanXinRoot, preferredName)
        if (resolved != null) return resolved
        val legacyRoot = File(baseDir, LEGACY_TTS_SUBDIR)
        return resolveModelDir(legacyRoot, preferredName)
    }

    fun defaultAsrDir(baseDir: File): String = File(baseDir, ASR_SUBDIR).absolutePath
    fun defaultTtsDir(baseDir: File): String = File(baseDir, TTS_SUBDIR).absolutePath

    private fun resolveModelDir(root: File, preferredName: String?): String? {
        if (!root.isDirectory) return null
        if (preferredName != null) {
            val p = File(root, preferredName)
            if (p.isDirectory) return p.absolutePath
        }
        // 根目录本身就是模型（含 tokens.txt）
        if (File(root, "tokens.txt").isFile || File(root, "tokens").isFile) {
            return root.absolutePath
        }
        val children = root.listFiles()?.filter { it.isDirectory }.orEmpty()
        if (children.size == 1) return children[0].absolutePath
        // 优先含 tokens 的子目录
        children.firstOrNull { File(it, "tokens.txt").isFile || File(it, "tokens").isFile }
            ?.let { return it.absolutePath }
        return children.firstOrNull()?.absolutePath
    }
}
