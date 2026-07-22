package com.lanxin.voice

import java.io.File

/**
 * 外置语音模型默认路径约定（不进 git / 不进 APK）。
 *
 * 目录布局（建议）：
 * ```
 * <filesDir>/
 *   models/
 *     local-llm/          # MNN LLM
 *     asr/<model-name>/   # sherpa ASR（encoder/decoder/joiner + tokens 或 paraformer）
 *     tts/<model-name>/   # sherpa TTS（matcha/vits）
 * ```
 */
object VoiceModelPaths {

    const val ASR_SUBDIR = "models/asr"
    const val TTS_SUBDIR = "models/tts"

    /** 在 baseDir 下解析 ASR 模型目录；若只有一层子目录且唯一则自动选中。 */
    fun resolveAsrDir(baseDir: File, preferredName: String? = null): String? {
        val root = File(baseDir, ASR_SUBDIR)
        return resolveModelDir(root, preferredName)
    }

    fun resolveTtsDir(baseDir: File, preferredName: String? = null): String? {
        val root = File(baseDir, TTS_SUBDIR)
        return resolveModelDir(root, preferredName)
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
